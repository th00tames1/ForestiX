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

    func testDistanceAdmissionIncludesHalfMetreAndFiveMetres() {
        XCTAssertEqual(DBHEstimator.usableTapDepthM, 0.5...5.0)
        for z in [0.5, 1.0, 4.0, 5.0] {
            XCTAssertTrue(DBHEstimator.isUsableDepthM(z))
            XCTAssertNotNil(measure([frame(Float(z))]))
        }
        for z in [0.0, 0.499, 5.001, Double.nan, Double.infinity] {
            XCTAssertFalse(DBHEstimator.isUsableDepthM(z))
            XCTAssertNil(measure([frame(Float(z))]))
        }
    }

    func testDeveloperBurstCannotAcceptOnlyOutOfRangeFrames() {
        for z: Float in [0.499, 5.001] {
            let result = measure((0..<5).map { _ in frame(z) })
            XCTAssertTrue(result == nil || result?.confidence == .red)
        }
    }

    func testAdmittedSingleFrameGeometryIsUnchanged() throws {
        for z: Float in [0.5, 1.0, 4.0, 5.0] {
            let result = try XCTUnwrap(measure([frame(z)]))
            let expected = try XCTUnwrap(BoundaryAlignment.sampledDiameterCm(
                spanPx: 0.3 * 256, medianDepthM: Double(z), focalPx: 210))
            XCTAssertEqual(Double(result.diameterCm), expected, accuracy: 0.0001)
        }
    }
}
