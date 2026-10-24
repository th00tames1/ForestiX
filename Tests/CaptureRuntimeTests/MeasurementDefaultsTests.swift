import XCTest
import Common
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

final class MeasurementDefaultsTests: XCTestCase {
    @MainActor func testNewSettingsDefaultToAutoAndExistingGuideConvention() {
        let settings = AppSettings.ephemeral()
        XCTAssertFalse(settings.dbhEdgeAdjustDefault)
        XCTAssertEqual(settings.breastHeightGuideHeight, .meters137)
        XCTAssertFalse(settings.developerMode)
        XCTAssertFalse(settings.rawCaptureEnabled)
    }
}
