// Phase 5 §9.2 "full plot loop works start-to-finish" — ViewModel-level E2E.
//
// The spec's headline DoD calls for a full XCUITest driving the actual SwiftUI
// hierarchy with a mock sensor. XCUITest requires an Xcode project host; the
// Forestix repo is still SwiftPM-only, so we exercise the full state machine
// through the view models instead, using the same Core Data stack and pure
// engine functions the UI would hit. A future Phase 5.1 pass will layer a
// real XCUITest target on top once an Xcode project is introduced.
//
// Sensor input (bearing/distance) is injected by writing the AR-derived
// placement directly to the AddTreeFlowViewModel, mirroring the mock sensor
// path that the XCUITest will use.

import XCTest
import CoreData
@testable import Common
@testable import Models
@testable import Persistence
@testable import InventoryEngine
@testable import UI

@MainActor
final class FullLoopViewModelE2ETests: XCTestCase {

    private struct Fixture {
        let stack: CoreDataStack
        let env: AppEnvironment
        let project: Project
        let design: CruiseDesign
    }

    private func buildFixture() throws -> Fixture {
        let model = try TestLoopModelLoader.loadTimberCruisingModel()
        let stack = try CoreDataStack(configuration: .inMemory, model: model)
        let settings = AppSettings.ephemeral()
        let env = AppEnvironment(stack: stack, settings: settings)

        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let project = try env.projectRepository.create(
            Project(
                id: UUID(), name: "E2E", description: "",
                owner: "tester", createdAt: now, updatedAt: now,
                units: .metric, breastHeightConvention: .uphill,
                slopeCorrection: false,
                lidarBiasMm: 0, depthNoiseMm: 0,
                dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
                vioDriftFraction: 0.02))
        let design = try env.cruiseDesignRepository.create(
            CruiseDesign(
                id: UUID(), projectId: project.id,
                plotType: .fixedArea, plotAreaAcres: 0.1,
                baf: nil, samplingScheme: .systematicGrid,
                gridSpacingMeters: 50,
                heightSubsampleRule: .everyKth(k: 3)))

        // Bruce (1950) Douglas-fir form: log10(V_cf) = b0 + b1·log10(D_in) + b2·log10(H_ft).
        _ = try env.volumeEquationRepository.create(
            Models.VolumeEquation(
                id: "bruce-df", form: "bruce",
                coefficients: ["b0": -2.725, "b1": 1.8219, "b2": 1.0757],
                unitsIn: "cm, m", unitsOut: "m3",
                sourceCitation: "test"))
        _ = try env.speciesRepository.create(
            SpeciesConfig(
                code: "DF", commonName: "Douglas-fir",
                scientificName: "Pseudotsuga menziesii",
                volumeEquationId: "bruce-df",
                merchTopDibCm: 12, stumpHeightCm: 30,
                expectedDbhMinCm: 5, expectedDbhMaxCm: 150,
                expectedHeightMinM: 3, expectedHeightMaxM: 70))

        return Fixture(stack: stack, env: env,
                       project: project, design: design)
    }

    func testFullLoop_AddTrees_ClosePlot_RollUpStandStats() throws {
        let f = try buildFixture()
        let now = Date(timeIntervalSince1970: 1_700_000_000)

        // ---------- Open a plot ----------
        let plot = try f.env.plotRepository.create(
            Plot(
                id: UUID(), projectId: f.project.id,
                plannedPlotId: nil, plotNumber: 1,
                centerLat: 0, centerLon: 0,
                positionSource: .manual, positionTier: .D,
                gpsNSamples: 0, gpsMedianHAccuracyM: 0, gpsSampleStdXyM: 0,
                offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
                plotAreaAcres: 0.1,
                startedAt: now, closedAt: nil, closedBy: nil,
                notes: "", coverPhotoPath: nil, panoramaPath: nil))

        // ---------- Open PlotTallyViewModel ----------
        let tallyVM = PlotTallyViewModel(
            project: f.project, design: f.design, plot: plot,
            plotRepo: f.env.plotRepository,
            treeRepo: f.env.treeRepository,
            speciesRepo: f.env.speciesRepository,
            volRepo: f.env.volumeEquationRepository,
            hdFitRepo: f.env.hdFitRepository)
        tallyVM.refresh()
        XCTAssertEqual(tallyVM.liveTrees.count, 0)
        XCTAssertEqual(tallyVM.stats.liveTreeCount, 0)

        // ---------- Add 10 trees via AddTreeFlowViewModel ----------
        // Fixed DBH=30, H=25 so expected BA/ac = 7.069, TPA = 100.
        for _ in 0..<10 {
            let addVM = AddTreeFlowViewModel(
                project: f.project, design: f.design, plot: plot,
                existingTrees: tallyVM.trees,
                speciesByCode: tallyVM.speciesByCode,
                treeRepo: f.env.treeRepository,
                recentSpeciesCodes: ["DF"])
            addVM.speciesCode = "DF"
            addVM.advance()                        // species → dbh
            addVM.dbhCm = 30
            addVM.advance()                        // dbh → (height or extras)
            if addVM.currentStep == .height {
                // Mock sensor injection: measured height + placement.
                addVM.heightM = 25
                addVM.heightMethod = .vioWalkoffTangent
                addVM.advance()                    // height → extras
            }
            addVM.bearingFromCenterDeg = 90        // mock AR placement
            addVM.distanceFromCenterM = 4.5
            addVM.advance()                        // extras → review
            addVM.save()
            XCTAssertNil(addVM.errorMessage, "save should not fail")
            XCTAssertNotNil(addVM.savedTree)
            tallyVM.addTreeCompleted()
        }
        XCTAssertEqual(tallyVM.liveTrees.count, 10)
        XCTAssertEqual(tallyVM.stats.tpa, 100, accuracy: 1e-3)
        XCTAssertEqual(tallyVM.stats.baPerAcreM2, 7.069, accuracy: 0.005)

        // Every 3rd tree should have been a measured-height subsample
        // (rule = everyKth(k:3)) ⇒ trees #1, 4, 7, 10 measured (n=4).
        // The rest (n=6) will be height=nil (imputed at close).
        let measured = tallyVM.liveTrees.filter { $0.heightSource == "measured" }
        XCTAssertEqual(measured.count, 4)

        // ---------- Soft-delete tree #1, verify live count drops ----------
        let first = tallyVM.liveTrees.first!
        tallyVM.softDelete(treeId: first.id)
        XCTAssertEqual(tallyVM.liveTrees.count, 9)
        XCTAssertEqual(tallyVM.stats.liveTreeCount, 9)

        // Undo — TreeDetailViewModel path.
        let detailVM = TreeDetailViewModel(
            tree: tallyVM.softDeletedTrees.first!,
            treeRepo: f.env.treeRepository)
        XCTAssertTrue(detailVM.isDeleted)
        detailVM.undelete()
        XCTAssertFalse(detailVM.isDeleted)
        tallyVM.refresh()
        XCTAssertEqual(tallyVM.liveTrees.count, 10)

        // ---------- Close plot via PlotSummaryViewModel ----------
        let summaryVM = PlotSummaryViewModel(
            project: f.project, design: f.design, plot: plot,
            plotRepo: f.env.plotRepository,
            treeRepo: f.env.treeRepository,
            speciesRepo: f.env.speciesRepository,
            volRepo: f.env.volumeEquationRepository,
            hdFitRepo: f.env.hdFitRepository)
        summaryVM.refresh()
        XCTAssertTrue(summaryVM.validation.canClose,
                      "No errors expected for a well-formed plot")
        summaryVM.close(closedBy: "tester")
        XCTAssertNil(summaryVM.errorMessage)
        XCTAssertNotNil(summaryVM.closedAt)
        // REQ §7.4: rolling fit must finish under 500 ms.
        XCTAssertLessThan(summaryVM.hdFitDurationMs, 500,
                          "H–D rolling update exceeded 500 ms budget")

        // With n=4 measured heights < 8 (minN), fit should NOT be persisted yet.
        let fit = try f.env.hdFitRepository.forProjectAndSpecies(
            projectId: f.project.id, speciesCode: "DF")
        XCTAssertNil(fit, "Should not fit with < minN measured heights")

        // ---------- Open + close two more plots to accumulate fit data ----------
        for plotNum in 2...3 {
            let p = try f.env.plotRepository.create(
                Plot(
                    id: UUID(), projectId: f.project.id,
                    plannedPlotId: nil, plotNumber: plotNum,
                    centerLat: 0, centerLon: 0,
                    positionSource: .manual, positionTier: .D,
                    gpsNSamples: 0, gpsMedianHAccuracyM: 0, gpsSampleStdXyM: 0,
                    offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
                    plotAreaAcres: 0.1,
                    startedAt: now, closedAt: nil, closedBy: nil,
                    notes: "", coverPhotoPath: nil, panoramaPath: nil))
            // Add 10 trees; every tree measured (rule overridden).
            let pVM = PlotTallyViewModel(
                project: f.project, design: f.design, plot: p,
                plotRepo: f.env.plotRepository,
                treeRepo: f.env.treeRepository,
                speciesRepo: f.env.speciesRepository,
                volRepo: f.env.volumeEquationRepository,
                hdFitRepo: f.env.hdFitRepository)
            pVM.refresh()
            for i in 1...10 {
                _ = try f.env.treeRepository.create(
                    Tree(
                        id: UUID(), plotId: p.id, treeNumber: i,
                        speciesCode: "DF", status: .live,
                        dbhCm: Float(25 + plotNum),
                        dbhMethod: .manualCaliper, dbhSigmaMm: nil,
                        dbhRmseMm: nil, dbhCoverageDeg: nil,
                        dbhNInliers: nil, dbhConfidence: .green,
                        dbhIsIrregular: false,
                        heightM: Float(24 + plotNum), heightMethod: .manualEntry,
                        heightSource: "measured",
                        heightSigmaM: nil, heightDHM: nil,
                        heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
                        heightConfidence: .green,
                        bearingFromCenterDeg: nil, distanceFromCenterM: nil,
                        boundaryCall: nil, crownClass: nil,
                        damageCodes: [], isMultistem: false, parentTreeId: nil,
                        notes: "", photoPath: nil, rawScanPath: nil,
                        createdAt: now, updatedAt: now, deletedAt: nil))
            }
            let sVM = PlotSummaryViewModel(
                project: f.project, design: f.design, plot: p,
                plotRepo: f.env.plotRepository,
                treeRepo: f.env.treeRepository,
                speciesRepo: f.env.speciesRepository,
                volRepo: f.env.volumeEquationRepository,
                hdFitRepo: f.env.hdFitRepository)
            sVM.refresh()
            sVM.close(closedBy: "tester")
            XCTAssertNotNil(sVM.closedAt)
            XCTAssertLessThan(sVM.hdFitDurationMs, 500)
        }

        // Now there are 4 + 20 = 24 measured heights → fit should exist.
        let fit2 = try f.env.hdFitRepository.forProjectAndSpecies(
            projectId: f.project.id, speciesCode: "DF")
        XCTAssertNotNil(fit2, "Fit should exist after accumulating ≥8 observations")
        XCTAssertEqual(fit2?.nObs ?? 0, 24)

        // ---------- StandSummary across closed plots ----------
        let standVM = StandSummaryViewModel(
            project: f.project, design: f.design,
            plotRepo: f.env.plotRepository,
            treeRepo: f.env.treeRepository,
            speciesRepo: f.env.speciesRepository,
            volRepo: f.env.volumeEquationRepository,
            hdFitRepo: f.env.hdFitRepository,
            stratumRepo: f.env.stratumRepository,
            plannedRepo: f.env.plannedPlotRepository)
        standVM.refresh()
        XCTAssertEqual(standVM.closedPlots.count, 3)
        XCTAssertEqual(standVM.totalLiveTreeCount, 30)
        // 30 trees × 3 plots @ 0.1 ac ⇒ each plot TPA = 100 exactly.
        XCTAssertEqual(standVM.tpaStat.mean, 100, accuracy: 1e-3)
        XCTAssertGreaterThan(standVM.baStat.mean, 0)
    }
}

// Same momc shim as PersistenceIntegrationTests — re-declared here because
// test targets don't share code.
enum TestLoopModelLoader {

    static func loadTimberCruisingModel() throws -> NSManagedObjectModel {
        let src = try locateModelSource()
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("TC-E2E-\(UUID().uuidString)")
        try FileManager.default.createDirectory(
            at: tmp, withIntermediateDirectories: true)
        let out = tmp.appendingPathComponent("TimberCruising.mom")
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/xcrun")
        proc.arguments = ["momc", src.path, out.path]
        let err = Pipe()
        proc.standardError = err
        try proc.run()
        proc.waitUntilExit()
        guard proc.terminationStatus == 0 else {
            let msg = String(data: err.fileHandleForReading.readDataToEndOfFile(),
                             encoding: .utf8) ?? ""
            throw NSError(domain: "TestLoopModelLoader", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "momc failed: \(msg)"])
        }
        guard let model = NSManagedObjectModel(contentsOf: out) else {
            throw NSError(domain: "TestLoopModelLoader", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "load NSManagedObjectModel failed"])
        }
        return model
    }

    private static func locateModelSource() throws -> URL {
        let f = URL(fileURLWithPath: #file)
        let root = f
            .deletingLastPathComponent() // UIFlowTests
            .deletingLastPathComponent() // Tests
            .deletingLastPathComponent() // repo root
        let p = root.appendingPathComponent(
            "TimberCruisingApp/Persistence/TimberCruising.xcdatamodeld")
        guard FileManager.default.fileExists(atPath: p.path) else {
            throw NSError(domain: "TestLoopModelLoader", code: -3,
                          userInfo: [NSLocalizedDescriptionKey:
                                        "xcdatamodeld not found at \(p.path)"])
        }
        return p
    }
}
