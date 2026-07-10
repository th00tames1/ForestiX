// Spec §7.10 — wall + cylinder calibration.

import XCTest
import simd
@testable import Sensors

final class LiDARCalibrationTests: XCTestCase {

    // MARK: - Wall calibration

    /// 400 points on z = 2.0 plane + Gaussian perpendicular noise 0.005 m
    /// (5 mm). RMS residual ≈ 5 mm → depth_noise_mm ≈ 5.
    func testWallCalibrationRecoversNoiseFromSyntheticPlane() {
        var rng = LCG(seed: 123)
        var pts: [SIMD3<Double>] = []
        for _ in 0..<400 {
            let x = (rng.unit() - 0.5) * 2.0
            let y = (rng.unit() - 0.5) * 2.0
            let z = 2.0 + rng.gaussian() * 0.005
            pts.append(SIMD3(x, y, z))
        }
        let r = WallCalibration.fit(points: pts)
        guard case .success(let result) = r else {
            return XCTFail("Expected successful wall fit, got \(r)")
        }
        XCTAssertEqual(result.depthNoiseMm, 5.0, accuracy: 0.8,
            "RMS residual should recover synthetic 5 mm noise")
        XCTAssertEqual(abs(result.depthBiasMm), 0.0, accuracy: 1e-6,
            "Mean residual from PCA plane fit should be ~0 by construction")
        XCTAssertEqual(abs(result.planeNormal.z), 1.0, accuracy: 0.05,
            "Plane normal should be ≈ ±ẑ for a z-plane")
        XCTAssertEqual(result.pointCount, 400)
    }

    /// With < 30 points the fit refuses.
    func testWallCalibrationRejectsInsufficientPoints() {
        let pts: [SIMD3<Double>] = (0..<10).map {
            SIMD3(Double($0), 0, 0)
        }
        let r = WallCalibration.fit(points: pts)
        guard case .failure(let err) = r else {
            return XCTFail("Expected failure")
        }
        XCTAssertEqual(err, .tooFewPoints(count: 10, minimum: 30))
    }

    // MARK: - Cylinder calibration

    /// Perfect measurements on y = 0.2 + 0.98 · x across three diameters.
    func testCylinderCalibrationRecoversKnownCoefficients() {
        let alpha = 0.2, beta = 0.98
        let measured = [10.0, 20.0, 30.0, 10.0, 20.0, 30.0]
        let samples = measured.map {
            CylinderCalibration.Sample(
                dbhMeasuredCm: $0,
                dbhTrueCm: alpha + beta * $0)
        }
        let r = CylinderCalibration.fit(samples: samples)
        guard case .success(let result) = r else {
            return XCTFail("Expected successful cylinder fit, got \(r)")
        }
        XCTAssertEqual(result.alpha, alpha, accuracy: 1e-9)
        XCTAssertEqual(result.beta, beta, accuracy: 1e-9)
        XCTAssertEqual(result.rSquared, 1.0, accuracy: 1e-9)
        XCTAssertEqual(result.sampleCount, 6)
    }

    /// Noisy samples still recover coefficients within a few percent.
    func testCylinderCalibrationNoisyRegression() {
        var rng = LCG(seed: 7)
        let alpha = -0.3, beta = 1.02
        var samples: [CylinderCalibration.Sample] = []
        for x in stride(from: 8.0, through: 32.0, by: 2.0) {
            let y = alpha + beta * x + rng.gaussian() * 0.15
            samples.append(.init(dbhMeasuredCm: x, dbhTrueCm: y))
        }
        let r = CylinderCalibration.fit(samples: samples)
        guard case .success(let result) = r else {
            return XCTFail("Expected successful cylinder fit, got \(r)")
        }
        XCTAssertEqual(result.alpha, alpha, accuracy: 0.3)
        XCTAssertEqual(result.beta,  beta,  accuracy: 0.03)
        XCTAssertGreaterThan(result.rSquared, 0.99)
    }

    /// All x identical → degenerate, must fail.
    func testCylinderCalibrationDegenerateX() {
        let samples = [
            CylinderCalibration.Sample(dbhMeasuredCm: 20, dbhTrueCm: 19.8),
            CylinderCalibration.Sample(dbhMeasuredCm: 20, dbhTrueCm: 20.1),
            CylinderCalibration.Sample(dbhMeasuredCm: 20, dbhTrueCm: 20.2)
        ]
        let r = CylinderCalibration.fit(samples: samples)
        guard case .failure(let err) = r else {
            return XCTFail("Expected failure")
        }
        XCTAssertEqual(err, .degenerateX)
    }

    func testCylinderCalibrationRejectsSingleSample() {
        let r = CylinderCalibration.fit(samples: [
            .init(dbhMeasuredCm: 10, dbhTrueCm: 10.2)
        ])
        guard case .failure(let err) = r else {
            return XCTFail("Expected failure")
        }
        XCTAssertEqual(err, .tooFewSamples(count: 1, minimum: 2))
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
