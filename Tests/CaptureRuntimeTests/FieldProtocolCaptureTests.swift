import XCTest
import Models
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class FieldProtocolCaptureTests: XCTestCase {
    @MainActor func testDeniedStartDoesNotCreateIDsEventsResultsOrAnchorsInEitherViewModel() {
        let dbh = DBHScanViewModel.preview(state: .aligning),height = HeightScanViewModel.preview(state: .idle)
        var events: [FieldCaptureEvent] = []
        dbh.onFieldCaptureEvent = { events.append($0) };height.onFieldCaptureEvent = { events.append($0) }
        dbh.fieldCapturePermission = { _,_ in "protocol mismatch" };height.fieldCapturePermission = { _,_ in "protocol mismatch" }
        dbh.tap(at: .init(128,96));dbh.manualDbhCm = "25";dbh.submitManualEntry()
        height.anchorHereNow();height.anchorHere(anchorPointWorld: .zero,standingPointWorld: .init(10,0,0))
        height.manualHeightM = "20";height.submitManualEntry()
        XCTAssertNil(dbh.measurementId);XCTAssertNil(dbh.result);XCTAssertEqual(dbh.state,.aligning)
        XCTAssertNil(height.measurementId);XCTAssertNil(height.result);XCTAssertEqual(height.state,.idle)
        XCTAssertTrue(events.isEmpty)
    }
    @MainActor func testActualHeightCoordinatorPinsBeforeAnchorAndRejectsChangedCalibrationAcrossReopen() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FieldStudyStore(root: root)
        for _ in 0..<1000 { if store.loaded { break };try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration();c.enabled = true;c.studyId = "SYNTHETIC";c.operatorId = "TEST"
        c.protocolLocked = true;c.pinnedRuntimeSettings = FieldProtocolBinding.policy
        try await store.saveConfiguration(c,developerMode: true)
        let settings = AppSettings.ephemeral(),vm = HeightScanViewModel.preview(state: .idle)
        var suppliedCalibration = ProjectCalibration.identity
        let capture = FieldCaptureCoordinator(applicationVersion: { ("1.0","17") })
        capture.configure(store: store,kind: .height,settings: settings,calibration: .identity,context: [:])
        vm.fieldCapturePermission = { [weak vm] typed,continuing in
            guard let vm else { return "closed" }
            return capture.capturePermission(typed: typed,continuing: continuing,calibration: suppliedCalibration)
        }
        vm.onFieldCaptureEvent = { capture.handle($0) }
        vm.anchorHere(anchorPointWorld: .zero,standingPointWorld: .init(10,0,0))
        let first = try XCTUnwrap(vm.measurementId)
        XCTAssertTrue(try XCTUnwrap(capture.displayRecord).protocolConforms)
        var changed = ProjectCalibration.identity;changed.vioDriftFraction = 0.04
        suppliedCalibration = changed
        vm.continueToAimTop()
        XCTAssertEqual(vm.measurementId,first);XCTAssertNil(vm.result);XCTAssertNotNil(capture.error);XCTAssertEqual(vm.state,.walking)
        suppliedCalibration = .identity;vm.continueToAimTop()
        vm.captureBaseDirect(alphaBaseRad: -0.1,standingPointWorld: .init(10,0,0));vm.captureTopDirect(alphaTopRad: 0.6)
        XCTAssertNotNil(vm.result)
        await store.awaitPendingWrites()
        XCTAssertTrue(try store.archive.measurement(id: first).protocolConforms)
        let displayed = capture.displayRecord
        XCTAssertNotNil(capture.capturePermission(typed: false,calibration: changed))
        XCTAssertEqual(capture.currentId,first);XCTAssertEqual(capture.displayRecord,displayed)
        XCTAssertNotNil(vm.result)
        let next = FieldCaptureCoordinator(applicationVersion: { ("1.0","17") })
        next.configure(store: store,kind: .height,settings: settings,calibration: changed,context: ["application.tree_number":"4"])
        XCTAssertNotNil(next.capturePermission(typed: false));XCTAssertNil(next.currentId)
        next.configure(store: store,kind: .height,settings: settings,calibration: .identity,context: ["application.tree_number":"4"])
        XCTAssertNil(next.capturePermission(typed: false))
        c.enabled = false;try await store.saveConfiguration(c,developerMode: true)
        XCTAssertNil(capture.capturePermission(typed: false,calibration: changed))
        let unrecorded = UUID().uuidString
        capture.handle(.started(id: unrecorded,rawRequested: false,aiEnabled: false,typed: false))
        XCTAssertNil(capture.displayRecord);XCTAssertNil(capture.receipt(id: unrecorded).measurementId)
        await store.awaitPendingWrites()
    }
    @MainActor func testTypedDoesNotBindOrRequireSensorModelAndLegacyLockNeedsNewVersion() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = FieldStudyStore(root: root)
        for _ in 0..<1000 { if store.loaded { break };try await Task.sleep(for: .milliseconds(2)) }
        var c = FieldStudyConfiguration();c.enabled = true;c.studyId = "SYNTHETIC";c.operatorId = "TEST"
        c.protocolLocked = true
        try await store.saveConfiguration(c,developerMode: true)
        let capture = FieldCaptureCoordinator(applicationVersion: { ("1.0","17") }),vm = DBHScanViewModel.preview(state: .manualEntry)
        capture.configure(store: store,kind: .dbh,settings: .ephemeral(),calibration: .identity,context: [:])
        vm.fieldCapturePermission = { typed,continuing in capture.capturePermission(typed: typed,continuing: continuing) }
        vm.onFieldCaptureEvent = { capture.handle($0) };vm.unitSystem = .metric;vm.manualDbhCm = "25"
        vm.submitManualEntry();XCTAssertNil(vm.measurementId);XCTAssertNil(vm.result)
        c.protocolVersion = "2";c.pinnedRuntimeSettings = FieldProtocolBinding.policy
        try await store.saveConfiguration(c,developerMode: true)
        vm.submitManualEntry();XCTAssertEqual(vm.result?.diameterCm,25)
        XCTAssertTrue(try XCTUnwrap(capture.displayRecord).protocolConforms)
        XCTAssertEqual(capture.displayRecord?.protocolBinding?.inputMode,"typed")
        await store.awaitPendingWrites()
    }
}
