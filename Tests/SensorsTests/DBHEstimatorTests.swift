// Spec §7.1 Done Criteria — synthetic ARDepthFrame bursts built from a
// known cylinder cross-section, passed through the full DBH pipeline.
//
// Single-view geometry constraint: the stem-strip tolerance (deltaDepth
// = 0.15 m) caps the arc that a single pose can observe to roughly
//   arc_max_deg = 2 · acos(1 − 0.15/r)
// For r = 0.15 m that is 180°; for r = 0.50 m it is ≈ 92°. Tests
// therefore exercise arcs that fit inside this envelope per radius, plus
// explicit red-tier and calibration paths.

import XCTest
import simd
import Common
import Models
@testable import Sensors

final class DBHEstimatorTests: XCTestCase {

    // MARK: - Done Criterion 1 (green tier, varied arcs/radii)

    func testPipelineRecoversRadiusOnSingleViewArcs() {
        // Matrix keeps every cell within the deltaDepth-visibility envelope.
        let cases: [(rTrue: Double, arcDeg: Double)] = [
            (0.15, 90),  (0.15, 120), (0.15, 180),
            (0.30, 60),  (0.30, 90),  (0.30, 120),
            (0.50, 60),  (0.50, 90)
        ]
        for c in cases {
            let input = makeInput(
                rTrue: c.rTrue, arcDeg: c.arcDeg,
                frames: 10, samplesPerFrame: 180,
                noise: 0.005, seed: 42)
            guard let result = DBHEstimator.estimate(input: input) else {
                XCTFail("Expected a result for r=\(c.rTrue) arc=\(c.arcDeg)")
                continue
            }
            XCTAssertNotEqual(result.confidence, .red,
                "r=\(c.rTrue) arc=\(c.arcDeg) should not reject: " +
                "reason=\(result.rejectionReason ?? "-")")
            // 2 · r_true · 100 = DBH in cm. Tolerance scales with radius
            // (Taubin bias shrinks with arc; 5% of diameter is the upper
            // bound §7.1 Step 9 considers reject-worthy).
            let dbhTrueCm = 2 * c.rTrue * 100
            XCTAssertEqual(Double(result.diameterCm), dbhTrueCm,
                accuracy: 0.05 * dbhTrueCm,
                "r=\(c.rTrue) arc=\(c.arcDeg) DBH off by more than 5%")
        }
    }

    // MARK: - Done Criterion 2 (red tier + human-readable reason)

    func testPipelineReturnsRedTierWhenTooFewPoints() {
        // 4 frames of data — below the burst minimum → nil (hard).
        let tiny = makeInput(
            rTrue: 0.30, arcDeg: 90,
            frames: 4, samplesPerFrame: 120,
            noise: 0.005, seed: 9)
        XCTAssertNil(DBHEstimator.estimate(input: tiny),
            "Sub-5-frame bursts must return nil (hard failure)")

        // Enough frames but almost no pixels → red with a reason.
        let sparse = makeInput(
            rTrue: 0.30, arcDeg: 90,
            frames: 6, samplesPerFrame: 3,
            noise: 0.005, seed: 9)
        guard let result = DBHEstimator.estimate(input: sparse) else {
            return XCTFail("Expected red-tier result, got nil")
        }
        XCTAssertEqual(result.confidence, .red)
        XCTAssertNotNil(result.rejectionReason)
        XCTAssertFalse(result.rejectionReason!.isEmpty)
    }

    func testPipelineRedWhenArcBelow45Degrees() {
        let input = makeInput(
            rTrue: 0.30, arcDeg: 30,
            frames: 10, samplesPerFrame: 200,
            noise: 0.005, seed: 11)
        guard let result = DBHEstimator.estimate(input: input) else {
            return XCTFail("Expected red-tier result, got nil")
        }
        XCTAssertEqual(result.confidence, .red,
            "30° arc must reject (< 45°)")
        XCTAssertNotNil(result.rejectionReason)
    }

    func testPipelineRedWhenTapDepthOutOfRange() {
        // Camera 5 m from cylinder → tap depth > 3.0 m → reject.
        let input = makeInput(
            rTrue: 0.30, arcDeg: 90,
            frames: 10, samplesPerFrame: 120,
            noise: 0.005, seed: 3,
            cameraDistance: 5.0)
        guard let result = DBHEstimator.estimate(input: input) else {
            return XCTFail("Expected red-tier result for out-of-range tap")
        }
        XCTAssertEqual(result.confidence, .red)
        XCTAssertNotNil(result.rejectionReason)
        XCTAssertTrue(result.rejectionReason!.contains("tap depth"),
            "reason should mention tap depth — got \(result.rejectionReason!)")
    }

    // MARK: - Done Criterion 3 (yellow tier on 45–60° arcs)

    func testPipelineReturnsYellowOnCleanFortyFiveDegArc() {
        // Noise-free synthetic arc so per-frame Taubin radii match
        // exactly (radiusCoV = 0) and rmse ≈ 0 — only the 45–60° warn
        // check should fire → yellow (exactly one warn failure).
        let input = makeInput(
            rTrue: 0.20, arcDeg: 50,
            frames: 10, samplesPerFrame: 200,
            noise: 0, seed: 8)
        guard let result = DBHEstimator.estimate(input: input) else {
            return XCTFail("Expected a result, got nil")
        }
        XCTAssertEqual(result.confidence, .yellow,
            "Clean 50° arc should be yellow: " +
            "reason=\(result.rejectionReason ?? "-")")
        XCTAssertEqual(result.arcCoverageDeg, 50, accuracy: 10,
            "Reported arc should be close to synthetic 50°")
    }

    // MARK: - Done Criterion 4 (cylinder calibration)

    func testPipelineAppliesCylinderCalibration() {
        let alpha: Float = 0.2, beta: Float = 0.98
        // Reference run — identity calibration
        let identity = makeInput(
            rTrue: 0.15, arcDeg: 120,
            frames: 10, samplesPerFrame: 200,
            noise: 0.0, seed: 1,
            calibration: ProjectCalibration(
                depthNoiseMm: 1.0,
                dbhCorrectionAlpha: 0,
                dbhCorrectionBeta: 1))
        // Same input, calibration applied
        let calibrated = makeInput(
            rTrue: 0.15, arcDeg: 120,
            frames: 10, samplesPerFrame: 200,
            noise: 0.0, seed: 1,
            calibration: ProjectCalibration(
                depthNoiseMm: 1.0,
                dbhCorrectionAlpha: alpha,
                dbhCorrectionBeta: beta))
        guard let r0 = DBHEstimator.estimate(input: identity),
              let r1 = DBHEstimator.estimate(input: calibrated)
        else { return XCTFail("Expected both estimates to succeed") }
        XCTAssertEqual(
            r1.diameterCm,
            alpha + beta * r0.diameterCm,
            accuracy: 1e-3,
            "Calibrated DBH must equal alpha + beta · raw DBH")
    }

    // MARK: - Synthetic ARDepthFrame fixture

    /// Builds a DBHScanInput where each frame's depth map encodes a
    /// partial arc of a cylinder centered on the camera's +Z axis.
    ///
    /// Camera is at world origin, pose = identity, looking down +Z.
    /// Cylinder axis at world (0, *, cameraDistance) along world +Y,
    /// radius `rTrue`. Depth is assigned per-column by ray-tracing each
    /// image column's ray at row y=guideRowY into the cylinder (nearest
    /// intersection). Columns outside the requested arc OR outside the
    /// cylinder's visibility envelope carry depth 0 / confidence 0.
    ///
    /// `samplesPerFrame` is ignored by this per-column synthesizer — it
    /// exists only as a legacy knob for the degenerate-input tests that
    /// want to prove < 30 total points forces rejection. When the value
    /// is < 30 we subsample the rendered columns down to that count.
    private func makeInput(
        rTrue: Double,
        arcDeg: Double,
        frames: Int,
        samplesPerFrame: Int,
        noise: Double,
        seed: UInt64,
        cameraDistance: Double = 1.5,
        calibration: ProjectCalibration = ProjectCalibration(
            depthNoiseMm: 5.0,
            dbhCorrectionAlpha: 0,
            dbhCorrectionBeta: 1)
    ) -> DBHScanInput {
        let width = 256
        let height = 192
        let guideRowY = height / 2
        let fx: Double = 210
        let cxK: Double = Double(width) / 2
        let cyK: Double = Double(height) / 2
        let K = simd_float3x3(
            SIMD3<Float>(Float(fx), 0, 0),
            SIMD3<Float>(0, Float(fx), 0),
            SIMD3<Float>(Float(cxK), Float(cyK), 1))
        let pose = matrix_identity_float4x4

        let halfArc = arcDeg / 2 * .pi / 180
        let arcCenterAngle = -Double.pi / 2   // near-point faces camera
        let tap = SIMD2<Double>(cxK, Double(guideRowY))

        var out: [ARDepthFrame] = []
        var rng = LCG(seed: seed)
        for f in 0..<frames {
            var depth = [Float](repeating: 0, count: width * height)
            var conf  = [UInt8](repeating: 0, count: width * height)
            var filledCols: [Int] = []
            for col in 0..<width {
                let u = (Double(col) - cxK) / fx
                // Ray (u, 0, 1) intersects cylinder at (0, *, d), radius r:
                //   (u²+1) t² − 2·d·t + (d²−r²) = 0
                let disc = rTrue * rTrue * (1 + u * u)
                         - u * u * cameraDistance * cameraDistance
                guard disc >= 0 else { continue }
                let tHit = (cameraDistance - disc.squareRoot()) / (1 + u * u)
                guard tHit > 0.1 else { continue }
                // Cylinder-surface angle of the hit point:
                //   α = atan2(Z − d, X) with X = u·t, Z = t
                let alpha = atan2(tHit - cameraDistance, u * tHit)
                let delta = abs(wrapAngle(alpha - arcCenterAngle))
                guard delta <= halfArc else { continue }
                let noisyZ = tHit + (noise > 0 ? rng.gaussian() * noise : 0)
                depth[guideRowY * width + col] = Float(noisyZ)
                conf[guideRowY * width + col] = 2
                filledCols.append(col)
            }
            // Sub-sparsify if the caller asked for fewer samples.
            if samplesPerFrame < filledCols.count {
                var keep = Set<Int>()
                let stride = Double(filledCols.count) / Double(samplesPerFrame)
                for i in 0..<samplesPerFrame {
                    keep.insert(filledCols[Int(Double(i) * stride)])
                }
                for col in filledCols where !keep.contains(col) {
                    depth[guideRowY * width + col] = 0
                    conf[guideRowY * width + col] = 0
                }
            }
            out.append(ARDepthFrame(
                width: width, height: height,
                depth: depth, confidence: conf,
                intrinsics: K, cameraPoseWorld: pose,
                timestamp: TimeInterval(f) / 15))
        }

        return DBHScanInput(
            frames: out,
            tapPixel: tap,
            guideRowY: guideRowY,
            projectCalibration: calibration,
            rawPointsWriter: nil)
    }

    private func wrapAngle(_ a: Double) -> Double {
        var x = a
        while x > .pi  { x -= 2 * .pi }
        while x < -.pi { x += 2 * .pi }
        return x
    }
}

// MARK: - Deterministic RNG

private struct LCG {
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
