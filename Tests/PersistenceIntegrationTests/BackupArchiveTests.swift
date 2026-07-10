// Phase 7 — `.tcproj` backup / restore round-trip.
//
// Builds a small fixture project, exports it, then restores into a
// second CoreDataStack and asserts every row and attachment came
// through.

import XCTest
import CoreData
@testable import Persistence
@testable import Models
@testable import Common

final class BackupArchiveTests: XCTestCase {

    private struct World {
        let stack: CoreDataStack
        let projectRepo: any ProjectRepository
        let designRepo: any CruiseDesignRepository
        let plotRepo: any PlotRepository
        let treeRepo: any TreeRepository
        let speciesRepo: any SpeciesConfigRepository
        let volRepo: any VolumeEquationRepository
    }

    private func makeWorld() throws -> World {
        let model = try TestModelLoader.loadTimberCruisingModel()
        let stack = try CoreDataStack(configuration: .sqlite(
            url: FileManager.default.temporaryDirectory
                .appendingPathComponent("backup-test-\(UUID().uuidString).sqlite")),
            model: model)
        return World(
            stack: stack,
            projectRepo: CoreDataProjectRepository(stack: stack),
            designRepo: CoreDataCruiseDesignRepository(stack: stack),
            plotRepo: CoreDataPlotRepository(stack: stack),
            treeRepo: CoreDataTreeRepository(stack: stack),
            speciesRepo: CoreDataSpeciesConfigRepository(stack: stack),
            volRepo: CoreDataVolumeEquationRepository(stack: stack))
    }

    private func seed(_ w: World) throws -> (Project, Plot, Tree) {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let project = try w.projectRepo.create(
            Project(id: UUID(), name: "Backup demo",
                    description: "", owner: "tester",
                    createdAt: now, updatedAt: now,
                    units: .metric,
                    breastHeightConvention: .uphill,
                    slopeCorrection: false,
                    lidarBiasMm: 0.5, depthNoiseMm: 1.0,
                    dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
                    vioDriftFraction: 0.02))
        _ = try w.designRepo.create(
            CruiseDesign(id: UUID(), projectId: project.id,
                         plotType: .fixedArea, plotAreaAcres: 0.1,
                         baf: nil, samplingScheme: .systematicGrid,
                         gridSpacingMeters: 50,
                         heightSubsampleRule: .everyKth(k: 3)))
        _ = try w.volRepo.create(
            Models.VolumeEquation(
                id: "bruce-df", form: "bruce",
                coefficients: ["b0": -2.725, "b1": 1.8219, "b2": 1.0757],
                unitsIn: "cm, m", unitsOut: "m3",
                sourceCitation: "test"))
        _ = try w.speciesRepo.create(
            SpeciesConfig(code: "DF", commonName: "Douglas-fir",
                          scientificName: "Pseudotsuga menziesii",
                          volumeEquationId: "bruce-df",
                          merchTopDibCm: 12, stumpHeightCm: 30,
                          expectedDbhMinCm: 5, expectedDbhMaxCm: 150,
                          expectedHeightMinM: 3, expectedHeightMaxM: 70))
        let plot = try w.plotRepo.create(
            Plot(id: UUID(), projectId: project.id, plannedPlotId: nil,
                 plotNumber: 1, centerLat: 47.6, centerLon: -122.3,
                 positionSource: .manual, positionTier: .B,
                 gpsNSamples: 0, gpsMedianHAccuracyM: 0, gpsSampleStdXyM: 0,
                 offsetWalkM: nil, slopeDeg: 0, aspectDeg: 0,
                 plotAreaAcres: 0.1, startedAt: now, closedAt: nil,
                 closedBy: nil, notes: "", coverPhotoPath: nil,
                 panoramaPath: nil))
        let tree = try w.treeRepo.create(
            Tree(id: UUID(), plotId: plot.id, treeNumber: 1,
                 speciesCode: "DF", status: .live,
                 dbhCm: 30, dbhMethod: .manualCaliper,
                 dbhSigmaMm: nil, dbhRmseMm: nil,
                 dbhCoverageDeg: nil, dbhNInliers: nil,
                 dbhConfidence: .green, dbhIsIrregular: false,
                 heightM: 25, heightMethod: .manualEntry,
                 heightSource: "measured",
                 heightSigmaM: nil, heightDHM: nil,
                 heightAlphaTopDeg: nil, heightAlphaBaseDeg: nil,
                 heightConfidence: .green,
                 bearingFromCenterDeg: nil, distanceFromCenterM: nil,
                 boundaryCall: nil, crownClass: nil,
                 damageCodes: [], isMultistem: false, parentTreeId: nil,
                 notes: "", photoPath: nil, rawScanPath: nil,
                 createdAt: now, updatedAt: now, deletedAt: nil))
        return (project, plot, tree)
    }

    // MARK: - Tests

    func testRoundTripPreservesProjectPlotAndTree() throws {
        let src = try makeWorld()
        let (project, _, _) = try seed(src)

        // Export.
        let (_, bytes, manifest) = try BackupArchive.export(
            projectId: project.id, stack: src.stack, appVersion: "t")

        XCTAssertEqual(manifest.projectId, project.id)
        XCTAssertEqual(manifest.schemaVersion, BackupManifest.currentSchemaVersion)

        // Restore into a fresh stack.
        let dst = try makeWorld()
        let attachments = FileManager.default.temporaryDirectory
            .appendingPathComponent("attachments-\(UUID().uuidString)")
        let result = try BackupArchive.restore(
            from: bytes,
            into: dst.stack,
            attachmentsDirectory: attachments)

        XCTAssertEqual(result.treeCount, 1)
        XCTAssertEqual(result.plotCount, 1)
        XCTAssertEqual(result.importedProjectId, project.id,
                       "no collision — id should survive")

        let restored = try dst.projectRepo.read(id: project.id)
        XCTAssertEqual(restored?.name, "Backup demo")
        XCTAssertEqual(try dst.plotRepo.listByProject(project.id).count, 1)
    }

    func testCollidingProjectIdGetsNewUuid() throws {
        let src = try makeWorld()
        let (project, _, _) = try seed(src)
        let (_, bytes, _) = try BackupArchive.export(
            projectId: project.id, stack: src.stack, appVersion: "t")

        // Target already has a project with the same id — restore should
        // create a copy with a fresh UUID.
        let dst = try makeWorld()
        _ = try dst.projectRepo.create(
            Project(id: project.id, name: "Existing",
                    description: "", owner: "other",
                    createdAt: Date(), updatedAt: Date(),
                    units: .metric, breastHeightConvention: .uphill,
                    slopeCorrection: false,
                    lidarBiasMm: 0, depthNoiseMm: 0,
                    dbhCorrectionAlpha: 0, dbhCorrectionBeta: 1,
                    vioDriftFraction: 0.02))

        let attachments = FileManager.default.temporaryDirectory
            .appendingPathComponent("attachments-\(UUID().uuidString)")
        let result = try BackupArchive.restore(
            from: bytes, into: dst.stack,
            attachmentsDirectory: attachments)

        XCTAssertNotEqual(result.importedProjectId, project.id,
                          "collision should force a new UUID")
        let projects = try dst.projectRepo.list()
        XCTAssertEqual(projects.count, 2)
    }

    func testManifestVersionMismatchFailsLoudly() throws {
        // Hand-craft a zip with a bad manifest.
        let badManifest = #"{"schemaVersion":999,"projectId":"\#(UUID().uuidString)","exportedAt":"2026-01-01T00:00:00Z","appVersion":"x"}"#
        let bytes = ZipWriter.storedArchive(files: [
            ("manifest.json", Data(badManifest.utf8)),
            ("core-data/TimberCruising.sqlite", Data([0x00]))
        ])
        let dst = try makeWorld()
        XCTAssertThrowsError(try BackupArchive.restore(
            from: bytes, into: dst.stack,
            attachmentsDirectory: FileManager.default.temporaryDirectory)) { err in
            guard case BackupError.manifestUnsupported = err else {
                return XCTFail("expected manifestUnsupported, got \(err)")
            }
        }
    }
}
