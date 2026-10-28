import XCTest
import Models
import Common
@testable import Sensors

final class RawOperatorEditTests: XCTestCase {
    private func root() throws -> URL {
        let r = FileManager.default.temporaryDirectory.appendingPathComponent("forestix-operator-"+UUID().uuidString)
        try FileManager.default.createDirectory(at: r,withIntermediateDirectories: true); return r
    }
    private func base(_ id: String) -> RawCaptureManifest {
        var m = RawCaptureRecorder.baseManifest(kind: "dbh",context: .quick,algorithm: "silhouette",captureMode: "manual",calibration: .identity,
            resultLive: .init(value: 25,sigma: 1,tier: "green",accepted: false,tierOk: true,frameCount: 1,perFrame: [25]),gps: nil)
        m.measurementId = id; return m
    }
    private func write(_ m: RawCaptureManifest,to dir: URL) throws {
        try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
        try RawOperatorEditJournal.atomic(RawCaptureStore.encoder().encode(m),to: dir.appendingPathComponent("manifest.json"))
    }
    private func truth(_ value: Double?) -> RawCaptureManifest.Truth { .init(value: value,enteredAt: value == nil ? nil : "FIXED_TIME",truthUnit: value == nil ? nil : "cm") }
    func testPendingTruthAndAcceptanceRecoverFromIntermediateManifest() throws {
        let r = try root(); defer { try? FileManager.default.removeItem(at: r) }; let id = UUID().uuidString,dir = r.appendingPathComponent(id)
        XCTAssertFalse(try RawOperatorEditJournal.update(directory: dir,id: id,truth: truth(37.125)))
        XCTAssertFalse(try RawOperatorEditJournal.update(directory: dir,id: id,accepted: true))
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("pending_patch.json").path))
        try RawCaptureWriteGuard.claim(directory: dir,id: id,kind: "dbh") // Pending-only directory is legitimate.
        try write(base(id),to: dir)
        XCTAssertTrue(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
        let m = try XCTUnwrap(RawOperatorEditJournal.readManifest(dir,id: id))
        XCTAssertEqual(m.truth.value,37.125); XCTAssertEqual(m.truth.enteredAt,"FIXED_TIME"); XCTAssertEqual(m.truth.truthUnit,"cm")
        XCTAssertTrue(m.resultLive.operatorAccepted); XCTAssertEqual(m.resultLive.value,25); XCTAssertNotNil(m.operatorEditId)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("pending_patch.json").path))
    }
    func testConsumedSidecarCannotResurrectOldTruthOrAnExplicitClear() throws {
        let r = try root(); defer { try? FileManager.default.removeItem(at: r) }; let id = UUID().uuidString,dir = r.appendingPathComponent(id)
        XCTAssertFalse(try RawOperatorEditJournal.update(directory: dir,id: id,truth: truth(30)))
        let sidecar = dir.appendingPathComponent("pending_patch.json"),consumed = try Data(contentsOf: sidecar)
        try RawOperatorEditJournal.commitRecorder(base(id),directory: dir,id: id)
        try consumed.write(to: sidecar) // Crash/unlink failure after receipt commit.
        XCTAssertTrue(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
        XCTAssertTrue(try RawOperatorEditJournal.update(directory: dir,id: id,truth: truth(42)))
        XCTAssertTrue(try RawOperatorEditJournal.update(directory: dir,id: id,truth: truth(nil)))
        try RawOperatorEditJournal.commitRecorder(base(id),directory: dir,id: id) // Late replay self-check flush.
        XCTAssertNil(try RawOperatorEditJournal.readManifest(dir,id: id)?.truth.value)
        XCTAssertTrue(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
    }
    func testLegacyPendingSidecarGetsReceiptWithoutChangingTypedTimeOrUnits() throws {
        let r = try root(); defer { try? FileManager.default.removeItem(at: r) }; let id = UUID().uuidString,dir = r.appendingPathComponent(id)
        try write(base(id),to: dir)
        let bytes = Data("{\"truth\":{\"value\":37.125,\"entered_at\":\"OLD_TIME\",\"truth_unit\":\"in\"},\"operator_accepted\":true}".utf8)
        try bytes.write(to: dir.appendingPathComponent("pending_patch.json"))
        XCTAssertTrue(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
        let m = try XCTUnwrap(RawOperatorEditJournal.readManifest(dir,id: id))
        XCTAssertEqual(m.truth.value,37.125); XCTAssertEqual(m.truth.enteredAt,"OLD_TIME"); XCTAssertEqual(m.truth.truthUnit,"in"); XCTAssertNotNil(m.operatorEditId)
    }
    func testCorruptOrMisboundInputNeverGetsOverwrittenOrSilentlyIgnored() throws {
        for mode in 0...2 {
            let r = try root(); defer { try? FileManager.default.removeItem(at: r) }; let id = UUID().uuidString,dir = r.appendingPathComponent(id)
            try write(base(id),to: dir)
            let sidecar = dir.appendingPathComponent("pending_patch.json"),manifest = dir.appendingPathComponent("manifest.json")
            if mode == 0 { try Data("{broken".utf8).write(to: sidecar) }
            if mode == 1 { try Data("{broken".utf8).write(to: manifest); try Data("{\"operator_accepted\":true}".utf8).write(to: sidecar) }
            if mode == 2 { try Data("{\"measurement_id\":\"\(UUID().uuidString)\",\"operator_accepted\":true}".utf8).write(to: sidecar) }
            let before = try Data(contentsOf: manifest),pending = try Data(contentsOf: sidecar)
            XCTAssertFalse(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
            XCTAssertThrowsError(try RawOperatorEditJournal.update(directory: dir,id: id,truth: truth(42)))
            XCTAssertEqual(try Data(contentsOf: manifest),before); XCTAssertEqual(try Data(contentsOf: sidecar),pending)
        }
    }
    func testMissingManifestRetainsIntentAndInputOnlyCaptureCannotBeAccepted() throws {
        let r = try root(); defer { try? FileManager.default.removeItem(at: r) }; let id = UUID().uuidString,dir = r.appendingPathComponent(id)
        XCTAssertFalse(try RawOperatorEditJournal.update(directory: dir,id: id,accepted: true))
        XCTAssertFalse(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
        let pending = try Data(contentsOf: dir.appendingPathComponent("pending_patch.json"))
        var m = base(id); m.captureEvidence = .init(measurementId: id,resultSeconds: 1,
            displayedResult: .init(value: nil,sigma: nil,tier: nil,method: nil,valid: false))
        try write(m,to: dir)
        XCTAssertThrowsError(try RawOperatorEditJournal.update(directory: dir,id: id,accepted: true))
        XCTAssertFalse(RawCaptureStore.recoverPendingOperatorEdits(root: r).isEmpty)
        XCTAssertFalse(try XCTUnwrap(RawOperatorEditJournal.readManifest(dir,id: id)).resultLive.operatorAccepted)
        XCTAssertEqual(try Data(contentsOf: dir.appendingPathComponent("pending_patch.json")),pending)
    }
    func testExclusiveClaimsRefuseEveryCompletedPartialOrUnknownExistingPayload() throws {
        let r = try root(); defer { try? FileManager.default.removeItem(at: r) }
        for name in ["manifest.json","input_dbh_0.f32","unexpected.txt","writer_claim.json"] {
            let id = UUID().uuidString,dir = r.appendingPathComponent(id); try FileManager.default.createDirectory(at: dir,withIntermediateDirectories: true)
            let file = dir.appendingPathComponent(name),bytes = Data([7,8,9]); try bytes.write(to: file)
            XCTAssertThrowsError(try RawCaptureWriteGuard.claim(directory: dir,id: id,kind: "dbh"))
            XCTAssertEqual(try Data(contentsOf: file),bytes); XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: dir.path),[name])
        }
        let id = UUID().uuidString,dir = r.appendingPathComponent(id),guardLock = NSLock(); var successes = 0
        DispatchQueue.concurrentPerform(iterations: 16) { _ in
            do { try RawCaptureWriteGuard.claim(directory: dir,id: id,kind: "dbh"); guardLock.lock(); successes += 1; guardLock.unlock() } catch {}
        }
        XCTAssertEqual(successes,1)
        XCTAssertThrowsError(try RawCaptureWriteGuard.claim(directory: r.appendingPathComponent("escaped"),id: "../escaped",kind: "dbh"))
    }
}
