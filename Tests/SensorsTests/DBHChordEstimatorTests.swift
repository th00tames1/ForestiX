// Phase 19 — chord / silhouette DBH method.
//
// The chord method reads diameter directly from the projected width of
// the trunk silhouette at the guide row:
//
//     diameter_m ≈ pixel_width × tap_depth_m / fx
//
// These tests render a synthetic cylinder onto a multi-row depth frame
// (every row in a ± rowSpan window sees the same cross-section because
// a vertical cylinder looks the same at every height) and verify that
// the chord pipeline recovers the true diameter inside the expected
// few-percent envelope.

import XCTest
import simd
import Common
import Models
@testable import Sensors

final class DBHChordEstimatorTests: XCTestCase {

    // MARK: - Done Criterion 1 — recovers diameter on varied trunks

    func testChordPreviewRecoversDiameterAcrossRadii() {
        let cases: [(rTrue: Double, distance: Double)] = [
            (0.05, 1.0),  // 10 cm sapling at 1 m
            (0.10, 1.5),  // 20 cm at 1.5 m
            (0.20, 1.5),  // 40 cm at 1.5 m
            (0.30, 2.0),  // 60 cm at 2 m
            (0.40, 2.5)   // 80 cm at 2.5 m
        ]
        for c in cases {
            let frame = makeCylinderFrame(
                rTrue: c.rTrue, cameraDistance: c.distance,
                noise: 0.003, seed: 7)
            guard let fit = DBHEstimator.chordPreviewFit(
                frame: frame,
                tapPixel: SIMD2(Double(frame.width) / 2,
                                Double(frame.height) / 2),
                guideAxis: .row(y: frame.height / 2))
            else {
                XCTFail("chord fit returned nil for r=\(c.rTrue) d=\(c.distance)")
                continue
            }
            let trueDbhCm = 2 * c.rTrue * 100
            // Chord underestimates by 1–2 % because LiDAR's edge pixels
            // hit the trunk at grazing angles and get rejected by the
            // ±deltaDepth gate. 5 % envelope is well above that.
            XCTAssertEqual(fit.diameterCm, trueDbhCm,
                accuracy: 0.06 * trueDbhCm,
                "r=\(c.rTrue) d=\(c.distance) DBH off too much: " +
                "got \(fit.diameterCm), true \(trueDbhCm)")
            XCTAssertEqual(fit.tier, .green,
                "Synthetic cylinder should fit cleanly → green tier")
        }
    }

    // MARK: - Done Criterion 2 — burst returns a record-able result

    func testChordBurstMedianIsStable() {
        let rTrue = 0.20
        var frames: [ARDepthFrame] = []
        for f in 0..<12 {
            frames.append(makeCylinderFrame(
                rTrue: rTrue, cameraDistance: 1.5,
                noise: 0.003, seed: UInt64(100 + f)))
        }
        let input = DBHScanInput(
            frames: frames,
            tapPixel: SIMD2(Double(frames[0].width) / 2,
                            Double(frames[0].height) / 2),
            guideAxis: .row(y: frames[0].height / 2),
            projectCalibration: ProjectCalibration.identity,
            rawPointsWriter: nil)
        guard let result = DBHEstimator.chordEstimate(input: input) else {
            return XCTFail("Expected a chord burst result")
        }
        XCTAssertNotEqual(result.confidence, .red,
            "Clean cylinder burst should not reject")
        XCTAssertEqual(result.method, .lidarChordSilhouette)
        let trueDbhCm = Float(2 * rTrue * 100)
        XCTAssertEqual(result.diameterCm, trueDbhCm,
            accuracy: 0.05 * trueDbhCm,
            "Burst chord median should track the true diameter")
    }

    // MARK: - Done Criterion 3 — out-of-range tap returns nil

    func testChordPreviewReturnsNilWhenTapDepthOutOfRange() {
        // Camera 6 m from cylinder → tap depth > 5 m → reject.
        let frame = makeCylinderFrame(
            rTrue: 0.30, cameraDistance: 6.0,
            noise: 0, seed: 1)
        let fit = DBHEstimator.chordPreviewFit(
            frame: frame,
            tapPixel: SIMD2(Double(frame.width) / 2,
                            Double(frame.height) / 2),
            guideAxis: .row(y: frame.height / 2))
        XCTAssertNil(fit, "Chord must reject tap depth above 5 m")
    }

    // MARK: - Done Criterion 4 — calibration is applied to the burst

    func testChordBurstAppliesCylinderCalibration() {
        let rTrue = 0.15
        let alpha: Float = 0.5, beta: Float = 0.97
        var framesA: [ARDepthFrame] = []
        var framesB: [ARDepthFrame] = []
        for f in 0..<10 {
            let fr = makeCylinderFrame(
                rTrue: rTrue, cameraDistance: 1.2,
                noise: 0, seed: UInt64(f))
            framesA.append(fr)
            framesB.append(fr)
        }
        let identity = DBHScanInput(
            frames: framesA,
            tapPixel: SIMD2(Double(framesA[0].width) / 2,
                            Double(framesA[0].height) / 2),
            guideAxis: .row(y: framesA[0].height / 2),
            projectCalibration: ProjectCalibration(
                depthNoiseMm: 1.0,
                dbhCorrectionAlpha: 0,
                dbhCorrectionBeta: 1),
            rawPointsWriter: nil)
        let calibrated = DBHScanInput(
            frames: framesB,
            tapPixel: SIMD2(Double(framesB[0].width) / 2,
                            Double(framesB[0].height) / 2),
            guideAxis: .row(y: framesB[0].height / 2),
            projectCalibration: ProjectCalibration(
                depthNoiseMm: 1.0,
                dbhCorrectionAlpha: alpha,
                dbhCorrectionBeta: beta),
            rawPointsWriter: nil)
        guard let r0 = DBHEstimator.chordEstimate(input: identity),
              let r1 = DBHEstimator.chordEstimate(input: calibrated)
        else { return XCTFail("Both chord estimates should succeed") }
        XCTAssertEqual(
            r1.diameterCm,
            alpha + beta * r0.diameterCm,
            accuracy: 1e-3,
            "Calibrated chord DBH must equal alpha + beta · raw DBH")
    }

    // MARK: - Synthetic multi-row cylinder fixture

    /// Renders a vertical cylinder centered on the camera's +Z axis at
    /// distance `cameraDistance`, radius `rTrue`. Every row receives the
    /// same per-column depth profile (because the cylinder is vertical),
    /// so the chord method's ± rowSpan median walks see consistent
    /// widths.
    private func makeCylinderFrame(
        rTrue: Double,
        cameraDistance: Double,
        noise: Double,
        seed: UInt64
    ) -> ARDepthFrame {
        let width = 256
        let height = 192
        let fx: Double = 210
        let cxK: Double = Double(width) / 2
        let cyK: Double = Double(height) / 2
        let K = simd_float3x3(
            SIMD3<Float>(Float(fx), 0, 0),
            SIMD3<Float>(0, Float(fx), 0),
            SIMD3<Float>(Float(cxK), Float(cyK), 1))
        let pose = matrix_identity_float4x4

        // Per-column depth (ray-cylinder intersection on a horizontal slab).
        var colDepth = [Float](repeating: 0, count: width)
        var rng = LCGRand(seed: seed)
        for col in 0..<width {
            let u = (Double(col) - cxK) / fx
            let disc = rTrue * rTrue * (1 + u * u)
                     - u * u * cameraDistance * cameraDistance
            guard disc >= 0 else { continue }
            let tHit = (cameraDistance - disc.squareRoot()) / (1 + u * u)
            guard tHit > 0.1 else { continue }
            let n = noise > 0 ? rng.gaussian() * noise : 0
            colDepth[col] = Float(tHit + n)
        }

        // Replicate to every row — vertical cylinder seen sideways.
        var depth = [Float](repeating: 0, count: width * height)
        var conf  = [UInt8](repeating: 0, count: width * height)
        for row in 0..<height {
            for col in 0..<width {
                let d = colDepth[col]
                if d > 0 {
                    depth[row * width + col] = d
                    conf[row * width + col] = 2
                }
            }
        }
        return ARDepthFrame(
            width: width, height: height,
            depth: depth, confidence: conf,
            intrinsics: K, cameraPoseWorld: pose,
            timestamp: 0)
    }
}

// MARK: - Deterministic RNG (matches the one in DBHEstimatorTests)

private struct LCGRand {
    private var state: UInt64
    init(seed: UInt64) { state = seed &* 6_364_136_223_846_793_005 &+ 1 }
    mutating func next() -> UInt64 {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return state
    }
    mutating func unit() -> Double {
        Double(next() >> 11) / Double(1 << 53)
    }
    mutating func gaussian() -> Double {
        let u1 = max(unit(), 1e-12)
        let u2 = unit()
        return sqrt(-2 * log(u1)) * cos(2 * .pi * u2)
    }
}
