import XCTest
import simd
import Models
@testable import Sensors

/// Synthetic uniform depths, not field data.
final class DBHCaptureWindowTests: XCTestCase {
    private func frame(_ z: Float = 1) -> ARDepthFrame {
        ARDepthFrame(width: 256, height: 192,
            depth: [Float](repeating: z, count: 256 * 192),
            confidence: [UInt8](repeating: 2, count: 256 * 192),
            intrinsics: simd_float3x3(SIMD3(210, 0, 0), SIMD3(0, 210, 0), SIMD3(128, 96, 1)),
            cameraPoseWorld: matrix_identity_float4x4, timestamp: 0)
    }

    private func measure(_ frames: [ARDepthFrame]) -> DBHResult? {
        DBHEstimator.bracketChordEstimate(frames: frames, guideAxis: .row(y: 96),
            leftFraction: 0.35, rightFraction: 0.65, calibration: .identity)
    }

    func testFiveFrameDeveloperWindowMatchesSingleOnStableSurface() throws {
        let single = try XCTUnwrap(measure([frame()]))
        let multi = try XCTUnwrap(measure((0..<5).map { _ in frame() }))
        XCTAssertEqual(single.diameterCm, multi.diameterCm, accuracy: 0.0001)
        XCTAssertEqual(single.confidence, .yellow)
        XCTAssertEqual(multi.confidence, .green)
    }

    func testFiveFrameMedianDoesNotUseLastOutlierAsSingleShot() throws {
        let single = try XCTUnwrap(measure([frame()]))
        let multi = try XCTUnwrap(measure([frame(), frame(), frame(), frame(), frame(1.8)]))
        XCTAssertEqual(single.diameterCm, multi.diameterCm, accuracy: 0.0001)
    }

    func testIncompleteWindowIsNotSilentlyTreatedAsSingleFrame() {
        XCTAssertNil(measure((0..<4).map { _ in frame() }))
    }
}
