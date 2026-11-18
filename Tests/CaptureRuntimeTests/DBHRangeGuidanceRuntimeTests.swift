import XCTest
import simd
import Sensors
#if canImport(UI)
@testable import UI
#else
@testable import CaptureRuntime
#endif

/// Synthetic sensor grids only; no field measurements or model assets.
final class DBHRangeGuidanceRuntimeTests: XCTestCase {
    private func frame(mapping: DepthViewMapping? = .init(a: 0,b: 1,tx: 0,c: -1,d: 0,ty: 120),
                       depth: Float = 0.3, confidence: UInt8 = 2) -> ARDepthFrame {
        ARDepthFrame(width: 90,height: 120,depth: .init(repeating: depth,count: 10800),
            confidence: .init(repeating: confidence,count: 10800),intrinsics: matrix_identity_float3x3,
            cameraPoseWorld: matrix_identity_float4x4,timestamp: 10,viewMapping: mapping)
    }
    @MainActor func testNoMaskRequiredAndRotatedViewMapsToTheCrosshair() {
        let vm = DBHScanViewModel.preview(state: .aligning)
        XCTAssertNil(vm.liveStemMask)
        // The mapped crosshair (55,60) differs from the grid centre (45,60).
        // Looking at the grid centre instead would incorrectly report 1 m.
        var depths = [Float](repeating: 1,count: 10800)
        for y in 58...62 { for x in 53...57 { depths[y*90+x] = 0.3 } }
        let mapped = ARDepthFrame(width: 90,height: 120,depth: depths,
            confidence: .init(repeating: 2,count: 10800),intrinsics: matrix_identity_float3x3,
            cameraPoseWorld: matrix_identity_float4x4,timestamp: 10,
            viewMapping: .init(a: 0,b: 1,tx: 10,c: -1,d: 0,ty: 120))
        XCTAssertEqual(DBHScanViewModel.depthRangeMessage(frame: mapped,viewSize: .init(width: 120,height: 90),
            now: 10,imperial: false),"Move back — keep at least 0.5 m from the target.")
    }
    @MainActor func testMissingMappingAndLowConfidenceStayUnknown() {
        XCTAssertNil(DBHScanViewModel.depthRangeMessage(frame: frame(mapping: nil),viewSize: .init(width: 120,height: 90),now: 10,imperial: false))
        XCTAssertNil(DBHScanViewModel.depthRangeMessage(frame: frame(confidence: 0),viewSize: .init(width: 120,height: 90),now: 10,imperial: false))
    }
    @MainActor func testStaleFrameDoesNotRepeatTheLastNearWarning() {
        XCTAssertNil(DBHScanViewModel.depthRangeMessage(frame: frame(),viewSize: .init(width: 120,height: 90),now: 10.501,imperial: false))
    }
    @MainActor func testWithinRangeClearsWarningWithoutAnAILock() {
        XCTAssertNil(DBHScanViewModel.depthRangeMessage(frame: frame(depth: 0.5),viewSize: .init(width: 120,height: 90),now: 10,imperial: false))
        XCTAssertNil(DBHScanViewModel.depthRangeMessage(frame: frame(depth: 5),viewSize: .init(width: 120,height: 90),now: 10,imperial: false))
    }
}
