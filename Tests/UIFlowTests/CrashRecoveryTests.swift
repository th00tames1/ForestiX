// Phase 7 — surface-in-progress-plot detection (crash recovery resume
// prompt feed).
//
// Runs against in-memory Core Data repositories so we can control clock
// and set `plot.closedAt` / `tree.updatedAt` precisely.

import XCTest
import CoreData
@testable import Models
@testable import Persistence
@testable import Common
@testable import UI

@MainActor
final class CrashRecoveryTests: XCTestCase {

    private func makeWorld() throws -> (CoreDataStack,
                                        any ProjectRepository,
                                        any PlotRepository,
                                        any TreeRepository) {
        let model = try TestLoopModelLoader.loadTimberCruisingModel()
        let stack = try CoreDataStack(configuration: .inMemory, model: model)
        return (stack,
                CoreDataProjectRepository(stack: stack),
                CoreDataPlotRepository(stack: stack),
                CoreDataTreeRepository(stack: stack))
    }

    private func project(_ projectRepo: any ProjectRepository) throws -> Project {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        return try projectRepo.create(
            Project(id: UUID(), name: "Crash test",
                    description: "", owner: "tester",
                    createdAt: now, updatedAt: now,
                    units: .metric, breastHeightConvention: .uphill,
                    slopeCorrection: false,
                    lidarBiasMm: 0, depthNoiseMm: 0,
                    dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
                    vioDriftFraction: 0.02))
    }

    private func seedPlot(plotRepo: any PlotRepository,
                          project: Project,
                          plotNumber: Int,
                          startedAt: Date,
                          closedAt: Date?) throws -> Plot {
        try plotRepo.create(Plot(
            id: UUID(), projectId: project.id, plannedPlotId: nil,
            plotNumber: plotNumber, centerLat: 0, centerLon: 0,
            positionSource: .manual, positionTier: .D,
            gpsNSamples: 0, gpsMedianHAccuracyM: 0, gpsSampleStdXyM: 0,
            offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
            plotAreaAcres: 0.1,
            startedAt: startedAt,
            closedAt: closedAt, closedBy: closedAt == nil ? nil : "t",
            notes: "", coverPhotoPath: nil, panoramaPath: nil))
    }

    // MARK: - Tests

    func testYoungOpenPlotShowsUp() throws {
        let (_, projectRepo, plotRepo, treeRepo) = try makeWorld()
        let p = try project(projectRepo)
        let now = Date()
        _ = try seedPlot(plotRepo: plotRepo, project: p,
                         plotNumber: 1,
                         startedAt: now.addingTimeInterval(-3600),
                         closedAt: nil)

        let candidates = try CrashRecoveryService.openPlotsWithinLast(
            86400, projectRepo: projectRepo,
            plotRepo: plotRepo, treeRepo: treeRepo, now: now)
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates.first?.plot.plotNumber, 1)
    }

    func testWeekOldPlotIsSkipped() throws {
        let (_, projectRepo, plotRepo, treeRepo) = try makeWorld()
        let p = try project(projectRepo)
        let now = Date()
        _ = try seedPlot(plotRepo: plotRepo, project: p,
                         plotNumber: 9,
                         startedAt: now.addingTimeInterval(-7 * 86400),
                         closedAt: nil)

        let candidates = try CrashRecoveryService.openPlotsWithinLast(
            86400, projectRepo: projectRepo,
            plotRepo: plotRepo, treeRepo: treeRepo, now: now)
        XCTAssertTrue(candidates.isEmpty)
    }

    func testClosedPlotIgnored() throws {
        let (_, projectRepo, plotRepo, treeRepo) = try makeWorld()
        let p = try project(projectRepo)
        let now = Date()
        _ = try seedPlot(plotRepo: plotRepo, project: p,
                         plotNumber: 3,
                         startedAt: now.addingTimeInterval(-600),
                         closedAt: now.addingTimeInterval(-300))
        let candidates = try CrashRecoveryService.openPlotsWithinLast(
            86400, projectRepo: projectRepo,
            plotRepo: plotRepo, treeRepo: treeRepo, now: now)
        XCTAssertTrue(candidates.isEmpty)
    }

    func testRecentTreeEditKeepsOldPlotFresh() throws {
        let (_, projectRepo, plotRepo, treeRepo) = try makeWorld()
        let p = try project(projectRepo)
        let now = Date()
        let oldStart = now.addingTimeInterval(-2 * 86400)
        let plot = try seedPlot(plotRepo: plotRepo, project: p,
                                plotNumber: 2,
                                startedAt: oldStart,
                                closedAt: nil)
        // Tree updated 2 h ago — should still bring the plot in scope.
        _ = try treeRepo.create(Tree(
            id: UUID(), plotId: plot.id, treeNumber: 1,
            speciesCode: "DF", status: .live,
            dbhCm: 30, dbhMethod: .manualCaliper,
            dbhSigmaMm: nil, dbhRmseMm: nil,
            dbhCoverageDeg: nil, dbhNInliers: nil,
            dbhConfidence: .green, dbhIsIrregular: false,
            heightM: nil, heightMethod: nil, heightSource: nil,
            heightSigmaM: nil, heightDHM: nil,
            heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
            heightConfidence: nil,
            bearingFromCenterDeg: nil, distanceFromCenterM: nil,
            boundaryCall: nil, crownClass: nil,
            damageCodes: [], isMultistem: false, parentTreeId: nil,
            notes: "", photoPath: nil, rawScanPath: nil,
            createdAt: oldStart, updatedAt: now.addingTimeInterval(-7200),
            deletedAt: nil))

        let candidates = try CrashRecoveryService.openPlotsWithinLast(
            86400, projectRepo: projectRepo,
            plotRepo: plotRepo, treeRepo: treeRepo, now: now)
        XCTAssertEqual(candidates.count, 1)
        XCTAssertEqual(candidates.first?.liveTreeCount, 1)
    }
}
