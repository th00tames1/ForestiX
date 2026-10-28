import XCTest
import Models
import Persistence
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class CruiseIdentityRuntimeTests: XCTestCase {
    private func tree() -> Tree {
        .init(id: UUID(),plotId: UUID(),treeNumber: 7,speciesCode: "DF",status: .live,dbhCm: 25,dbhMethod: .lidarChordSilhouette,
              dbhSigmaMm: 1,dbhRmseMm: 2,dbhCoverageDeg: 90,dbhNInliers: 60,dbhConfidence: .green,dbhIsIrregular: false,dbhCaptureMode: "manual",dbhEstimatorEpoch: 0,
              heightM: 20,heightMethod: .manualEntry,heightSource: "measured",heightSigmaM: 0.2,heightDHM: 13.625,heightAlphaTopDeg: 50,heightAlphaBaseDeg: -5,heightConfidence: .yellow,
              bearingFromCenterDeg: nil,distanceFromCenterM: nil,boundaryCall: nil,crownClass: nil,damageCodes: [],isMultistem: false,parentTreeId: nil,
              notes: "SYNTHETIC",photoPath: nil,rawScanPath: nil,createdAt: Date(),updatedAt: Date(),deletedAt: nil,heightMeasurementId: UUID().uuidString)
    }
    private final class Repo: TreeRepository {
        var row: Tree; var afterRead: Tree?; var writes = 0
        init(_ tree: Tree) { row = tree }
        func create(_ t: Tree) throws -> Tree { t }
        func read(id: UUID,includeDeleted: Bool) throws -> Tree? { let before = row; if let afterRead { row = afterRead }; return before }
        func update(_ t: Tree) throws -> Tree { writes += 1; row = t; return t }
        func replaceIfUnchanged(_ next: Tree,expected: Tree) throws -> Tree {
            guard row == expected else { throw FieldStudyError.conflict("Changed result") }
            writes += 1; row = try FieldTreeMeasurementIdentity.prepare(next,previous: expected); return row
        }
        func delete(id: UUID,at date: Date) throws { }
        func hardDelete(id: UUID) throws { }
        func listByPlot(_ plotId: UUID,includeDeleted: Bool) throws -> [Tree] { [row] }
        func bySpeciesInProject(_ projectId: UUID,speciesCode: String,includeDeleted: Bool) throws -> [Tree] { [row] }
        func recentSpeciesCodes(projectId: UUID,limit: Int) throws -> [String] { [] }
    }
    @MainActor func testIdentifiedPlanAndWriteRefuseLegacyReplayWithoutChangingResult() {
        var original = tree(); original.dbhMeasurementId = UUID().uuidString
        let plan = DBHEpochRecompute.plan(project: .init(projectID: UUID(),projectName: "SYNTHETIC",trees: [original]),summaries: [])
        XCTAssertTrue(plan.changes.isEmpty); XCTAssertEqual(plan.skips.first?.reason,.identifiedMeasurement)
        let repo = Repo(original),change = DBHEpochRecompute.Change(treeID: original.id,treeLabel: "SYNTHETIC",treeNumber: 7,bundleID: UUID().uuidString,storedCm: 25,recomputedCm: 45)
        XCTAssertEqual(DBHEpochRecompute.apply([change],repository: repo).written,0); XCTAssertEqual(repo.writes,0); XCTAssertEqual(repo.row,original)
    }
    @MainActor func testLegacyEpochCASCannotOverwriteConcurrentSameValueNewCapture() {
        let original = tree(),repo = Repo(tree())
        repo.row = original; var latest = original; latest.dbhMeasurementId = UUID().uuidString; repo.afterRead = latest
        let change = DBHEpochRecompute.Change(treeID: original.id,treeLabel: "SYNTHETIC",treeNumber: 7,bundleID: UUID().uuidString,storedCm: 25,recomputedCm: 45)
        let refused = DBHEpochRecompute.apply([change],repository: repo)
        XCTAssertEqual(refused.written,0); XCTAssertEqual(repo.writes,0); XCTAssertEqual(repo.row,latest)
        repo.row = original; repo.afterRead = nil
        XCTAssertEqual(DBHEpochRecompute.apply([change],repository: repo).written,1)
        XCTAssertEqual(repo.row.dbhCm,45); XCTAssertNil(repo.row.dbhMeasurementId); XCTAssertEqual(repo.row.heightMeasurementId,original.heightMeasurementId)
    }
}
