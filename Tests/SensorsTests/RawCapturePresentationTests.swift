import XCTest
import Models
@testable import Sensors

final class RawCapturePresentationTests: XCTestCase {
    func testRecoveryExportIsAvailableWhenEveryManifestIsUnreadableButNeverWhileBusy() {
        for parsed in 0...3 {
            let inventory = RawCaptureStore.Inventory(directories: 3,parsed: parsed)
            XCTAssertTrue(RawCaptureExportAvailability.canExportRaw(inventory,isExporting: false))
            XCTAssertFalse(RawCaptureExportAvailability.canExportRaw(inventory,isExporting: true))
        }
        XCTAssertFalse(RawCaptureExportAvailability.canExportRaw(.init(directories: 0,parsed: 0),isExporting: false))
    }
    private func manifest() -> RawCaptureManifest {
        RawCaptureRecorder.baseManifest(kind: "dbh",context: .quick,algorithm: "silhouette",captureMode: "manual",calibration: .identity,
            resultLive: .init(value: 31,sigma: 1,tier: "green",accepted: false,tierOk: true,frameCount: 1,perFrame: [31]),gps: nil)
    }
    func testActualAndCanonicalRemainDistinctAndLegacyDoesNotGetInventedIdentity() {
        var m = manifest(); let legacy = RawCapturePresentation(m)
        XCTAssertEqual(legacy.displayedValue,31); XCTAssertTrue(legacy.allowsTruth); XCTAssertFalse(legacy.usesExactEvidence)
        let id = UUID().uuidString; m.measurementId = id
        XCTAssertNil(RawCapturePresentation(m).displayedValue); XCTAssertFalse(RawCapturePresentation(m).allowsTruth)
        m.captureEvidence = .init(measurementId: id,resultSeconds: 1,displayedResult: .init(value: 25,sigma: 0.5,tier: "yellow",method: "silhouette",valid: true))
        let actual = RawCapturePresentation(m)
        XCTAssertEqual(actual.displayedValue,25); XCTAssertEqual(actual.canonicalValue,31); XCTAssertEqual(actual.sigma,0.5)
        XCTAssertEqual(actual.tier,"yellow"); XCTAssertTrue(actual.allowsTruth)
    }
    func testFailedMissingZeroAndMisboundEvidenceCannotBecomeNumericMeasurements() {
        for value: Double? in [nil,0,25] {
            var m = manifest(); let id = UUID().uuidString; m.measurementId = id
            m.captureEvidence = .init(measurementId: id,resultSeconds: 1,displayedResult: .init(value: value,sigma: nil,tier: nil,method: nil,valid: false))
            XCTAssertNil(RawCapturePresentation(m).displayedValue); XCTAssertFalse(RawCapturePresentation(m).allowsTruth)
            m.captureEvidence?.displayedResult.valid = true
            m.captureEvidence?.measurementId = UUID().uuidString
            XCTAssertNil(RawCapturePresentation(m).displayedValue)
        }
    }
}
