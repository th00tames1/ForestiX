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
import Foundation
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

    func testDepthIntrinsicsScalerMapsCameraPixelsToDepthPixels() {
        let cameraK = simd_float3x3(
            SIMD3<Float>(2400, 0, 0),
            SIMD3<Float>(0, 2400, 0),
            SIMD3<Float>(960, 720, 1))
        let scaled = DepthIntrinsicsScaler.scaled(
            cameraIntrinsics: cameraK,
            cameraWidth: 1920,
            cameraHeight: 1440,
            depthWidth: 160,
            depthHeight: 120)

        XCTAssertEqual(scaled[0, 0], 200, accuracy: 0.001)
        XCTAssertEqual(scaled[1, 1], 200, accuracy: 0.001)
        XCTAssertEqual(scaled[2, 0], 80, accuracy: 0.001)
        XCTAssertEqual(scaled[2, 1], 60, accuracy: 0.001)
    }

    func testChordPreviewUsesAxisSpecificFocalLength() {
        let width = 160
        let height = 120
        let centerX = width / 2
        let centerY = height / 2
        let depthM = 2.0
        let diameterM = 0.50
        let fx = 400.0
        let fy = 200.0
        let pixelWidth = Int((diameterM * fy / (depthM + diameterM / 2.0)).rounded())
        let top = centerY - pixelWidth / 2
        let bottom = centerY + pixelWidth / 2

        let K = simd_float3x3(
            SIMD3<Float>(Float(fx), 0, 0),
            SIMD3<Float>(0, Float(fy), 0),
            SIMD3<Float>(Float(centerX), Float(centerY), 1))
        var depth = [Float](repeating: 0, count: width * height)
        var conf = [UInt8](repeating: 0, count: width * height)
        for y in max(0, top)...min(height - 1, bottom) {
            for x in (centerX - 12)...(centerX + 12) {
                depth[y * width + x] = Float(depthM)
                conf[y * width + x] = 2
            }
        }
        let frame = ARDepthFrame(
            width: width,
            height: height,
            depth: depth,
            confidence: conf,
            intrinsics: K,
            cameraPoseWorld: matrix_identity_float4x4,
            timestamp: 0)

        let fit = DBHEstimator.chordPreviewFit(
            frame: frame,
            tapPixel: SIMD2(Double(centerX), Double(centerY)),
            guideAxis: .col(x: centerX))

        XCTAssertNotNil(fit)
        XCTAssertEqual(fit?.diameterCm ?? 0, 50.0, accuracy: 3.0)
    }

    func testSharedGoldenDbhCasesMatchChordEstimator() throws {
        let cases = try loadSharedDbhCases()
        XCTAssertFalse(cases.isEmpty, "Shared DBH fixture must contain cases")

        for c in cases {
            let frame = makeCylinderFrame(
                rTrue: c.radiusM,
                cameraDistance: c.axisDistanceM,
                noise: 0,
                seed: 42,
                width: c.width,
                height: c.height,
                fx: c.focalPx)
            let fit = DBHEstimator.chordPreviewFit(
                frame: frame,
                tapPixel: SIMD2(Double(frame.width) / 2,
                                Double(frame.height) / 2),
                guideAxis: .row(y: frame.height / 2))

            XCTAssertNotNil(fit, "Shared DBH case \(c.id) did not fit")
            XCTAssertEqual(fit?.diameterCm ?? 0,
                           c.expectedDbhCm,
                           accuracy: c.toleranceCm,
                           "Shared DBH case \(c.id) diverged")
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

    private struct SharedDbhCase {
        let id: String
        let radiusM: Double
        let axisDistanceM: Double
        let focalPx: Double
        let width: Int
        let height: Int
        let expectedDbhCm: Double
        let toleranceCm: Double
    }

    private func loadSharedDbhCases() throws -> [SharedDbhCase] {
        let url = try sharedDbhFixtureURL()
        let text = try String(contentsOf: url, encoding: .utf8)
        return try text.split(whereSeparator: { $0.isNewline }).compactMap { rawLine in
            let line = String(rawLine).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !line.isEmpty, !line.hasPrefix("#"), !line.hasPrefix("id,") else {
                return nil
            }
            let parts = line.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard parts.count == 8 else {
                throw NSError(domain: "ForestiXSharedFixture", code: 1,
                              userInfo: [NSLocalizedDescriptionKey: "Invalid DBH fixture row: \(line)"])
            }
            guard let radius = Double(parts[1]),
                  let distance = Double(parts[2]),
                  let focal = Double(parts[3]),
                  let width = Int(parts[4]),
                  let height = Int(parts[5]),
                  let expected = Double(parts[6]),
                  let tolerance = Double(parts[7])
            else {
                throw NSError(domain: "ForestiXSharedFixture", code: 2,
                              userInfo: [NSLocalizedDescriptionKey: "Invalid DBH fixture number: \(line)"])
            }
            return SharedDbhCase(id: parts[0], radiusM: radius,
                                 axisDistanceM: distance, focalPx: focal,
                                 width: width, height: height,
                                 expectedDbhCm: expected, toleranceCm: tolerance)
        }
    }

    private func sharedDbhFixtureURL() throws -> URL {
        let relative = ["fixtures", "dbh_golden_cases.csv"]
        var candidates: [URL] = []
        if let env = ProcessInfo.processInfo.environment["FORESTIX_SHARED_DIR"], !env.isEmpty {
            candidates.append(relative.reduce(URL(fileURLWithPath: env)) { $0.appendingPathComponent($1) })
        }
        let testFile = URL(fileURLWithPath: #filePath)
        let iosRepo = testFile
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        candidates.append(iosRepo.deletingLastPathComponent()
            .appendingPathComponent("shared")
            .appendingPathComponent(relative[0])
            .appendingPathComponent(relative[1]))
        candidates.append(URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("../shared")
            .appendingPathComponent(relative[0])
            .appendingPathComponent(relative[1]))
        candidates.append(URL(fileURLWithPath: FileManager.default.currentDirectoryPath)
            .appendingPathComponent("shared")
            .appendingPathComponent(relative[0])
            .appendingPathComponent(relative[1]))

        for url in candidates where FileManager.default.fileExists(atPath: url.path) {
            return url
        }
        throw NSError(domain: "ForestiXSharedFixture", code: 3,
                      userInfo: [NSLocalizedDescriptionKey: "Shared DBH fixture not found. Set FORESTIX_SHARED_DIR or keep shared/ in the monorepo root."])
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
        seed: UInt64,
        width: Int = 256,
        height: Int = 192,
        fx: Double = 210
    ) -> ARDepthFrame {
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
