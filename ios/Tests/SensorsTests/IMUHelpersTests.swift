// Spec §7.2 + REQ-HGT-004. Pitch-from-gravity sanity + ring buffer
// windowed-median correctness. Drives the buffer with synthetic samples
// so tests run identically on macOS and iOS (CoreMotion unavailable on
// macOS; the pure-Swift pieces cover the decision-critical math).

import XCTest
import simd
@testable import Sensors

final class IMUHelpersTests: XCTestCase {

    // MARK: - pitchFromGravity

    func testPitchUprightIsZero() {
        // Phone upright in portrait, back camera at horizon.
        let g = SIMD3<Double>(0, -1, 0)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), 0, accuracy: 1e-9)
    }

    func testPitchScreenFaceDownIsPlusHalfPi() {
        // Screen face-down → back camera at zenith (looking straight up).
        // Sign convention: aiming back camera UP → POSITIVE pitch.
        let g = SIMD3<Double>(0, 0, 1)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), .pi / 2, accuracy: 1e-9)
    }

    func testPitchScreenFaceUpIsMinusHalfPi() {
        // Screen face-up → back camera at nadir (looking straight down).
        // Sign convention: aiming back camera DOWN → NEGATIVE pitch.
        let g = SIMD3<Double>(0, 0, -1)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), -.pi / 2, accuracy: 1e-9)
    }

    func testPitchBackCameraAimed45DegUp() {
        // Top of phone tilts forward (away from cruiser) by 45° so the
        // back camera elevates from horizon to 45° above horizontal.
        // In the device frame, gravity rotates from (0,-1,0) to
        // (0, -cos45, +sin45). Expected pitch: +π/4.
        let c = cos(Double.pi / 4)
        let s = sin(Double.pi / 4)
        let g = SIMD3<Double>(0, -c, s)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), .pi / 4, accuracy: 1e-9)
    }

    func testPitchBackCameraAimed45DegDown() {
        // Top of phone tilts backward (toward cruiser) by 45° so the
        // back camera depresses from horizon to 45° below horizontal.
        // Gravity rotates to (0, -cos45, -sin45). Expected pitch: −π/4.
        let c = cos(Double.pi / 4)
        let s = sin(Double.pi / 4)
        let g = SIMD3<Double>(0, -c, -s)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), -.pi / 4, accuracy: 1e-9)
    }

    // MARK: - Roll-invariance (Phase 13.3 regression)

    /// Real-device bug 2026-04-28: a desk measurement reported H = 51 m
    /// because the cruiser was holding the phone in landscape. The old
    /// `atan2(g.z, -g.y)` form collapses to ±π/2 in landscape (g.y → 0
    /// because gravity moves into the X axis), so α_top / α_base saturate
    /// at ±90° regardless of where the phone is actually pointing.
    /// `atan2(g.z, sqrt(g.x²+g.y²))` is roll-invariant and recovers the
    /// correct elevation in any rotation around the screen-out axis.
    func testPitchIsRollInvariantInLandscape() {
        let elevation = -25.0 * .pi / 180.0  // back cam aimed 25° below horizon
        // Portrait: gravity has no x component.
        let portrait = SIMD3<Double>(0,
                                     -cos(abs(elevation)),
                                     sin(elevation))
        // Landscape (right side down): gravity rotated 90° from Y to X.
        let landscape = SIMD3<Double>(cos(abs(elevation)),
                                       0,
                                       sin(elevation))
        let pPortrait = IMUHelpers.pitchFromGravity(portrait)
        let pLandscape = IMUHelpers.pitchFromGravity(landscape)
        XCTAssertEqual(pPortrait, elevation, accuracy: 1e-9)
        XCTAssertEqual(pLandscape, elevation, accuracy: 1e-9,
            "Pitch must be invariant to roll around the screen-out axis. "
            + "Got \(pLandscape) in landscape vs \(pPortrait) in portrait.")
    }

    func testPitchIsRollInvariantAt45Roll() {
        // Phone rolled 45° (somewhere between portrait and landscape) and
        // aimed back cam 30° above horizon. Gravity in body frame has
        // both x and y components but z stays = sin(elevation).
        let elevation = 30.0 * .pi / 180.0
        let roll = 45.0 * .pi / 180.0
        let horiz = cos(elevation)
        let g = SIMD3<Double>(horiz * sin(roll),
                              -horiz * cos(roll),
                              sin(elevation))
        let pitch = IMUHelpers.pitchFromGravity(g)
        XCTAssertEqual(pitch, elevation, accuracy: 1e-9)
    }

    // MARK: - IMUPitchBuffer median window

    func testMedianInWindowReturnsCenterValue() {
        let b = IMUPitchBuffer()
        // Seven samples at 50 ms spacing, centered on t = 1.0.
        let pitches = [0.10, 0.12, 0.11, 0.13, 0.10, 0.14, 0.12]
        for (i, p) in pitches.enumerated() {
            let t = 1.0 - 0.15 + Double(i) * 0.05
            b.append(timestamp: t, pitchRad: p)
        }
        let median = b.medianPitch(centeredOn: 1.0, windowMs: 400)
        XCTAssertNotNil(median)
        // Sorted: [0.10, 0.10, 0.11, 0.12, 0.12, 0.13, 0.14] → median = 0.12.
        XCTAssertEqual(median!, 0.12, accuracy: 1e-9)
    }

    func testMedianIgnoresSamplesOutsideWindow() {
        let b = IMUPitchBuffer()
        // Three samples *inside* ±200 ms around t = 2.0, all at 0.20 rad.
        b.append(timestamp: 1.95, pitchRad: 0.20)
        b.append(timestamp: 2.00, pitchRad: 0.20)
        b.append(timestamp: 2.05, pitchRad: 0.20)
        // Two outliers *outside* the window at 1.70 and 2.50 s.
        b.append(timestamp: 1.70, pitchRad: -5.0)
        b.append(timestamp: 2.50, pitchRad:  5.0)
        let median = b.medianPitch(centeredOn: 2.0, windowMs: 400)
        XCTAssertNotNil(median)
        XCTAssertEqual(median!, 0.20, accuracy: 1e-9)
    }

    func testMedianReturnsNilWhenWindowEmpty() {
        let b = IMUPitchBuffer()
        b.append(timestamp: 0.0, pitchRad: 0.5)
        let median = b.medianPitch(centeredOn: 10.0, windowMs: 400)
        XCTAssertNil(median)
    }

    func testEvenCountAveragesMiddleTwo() {
        let b = IMUPitchBuffer()
        for (i, p) in [0.10, 0.20, 0.30, 0.40].enumerated() {
            b.append(timestamp: 1.0 + Double(i) * 0.05, pitchRad: p)
        }
        let median = b.medianPitch(centeredOn: 1.075, windowMs: 400)!
        XCTAssertEqual(median, 0.25, accuracy: 1e-9)
    }

    func testRetentionEvictsOldSamples() {
        let b = IMUPitchBuffer(retention: 1.0)
        b.append(timestamp: 0.0, pitchRad: 0.0)
        b.append(timestamp: 0.5, pitchRad: 0.0)
        b.append(timestamp: 1.5, pitchRad: 0.0) // evicts t=0.0
        XCTAssertEqual(b.count, 2)
        b.append(timestamp: 2.0, pitchRad: 0.0) // evicts t=0.5
        XCTAssertEqual(b.count, 2)
    }

    // MARK: - End-to-end sign sanity (regression: negative height bug)

    /// Regression for the inverted-pitch bug that produced negative
    /// heights on every real measurement: passing the gravity vectors
    /// the IMU would actually report for "back camera looking up at
    /// treetop" and "back camera looking down at base" through
    /// `pitchFromGravity` must yield α_top > α_base, so the §7.2
    /// formula `H = d_h × (tan α_top − tan α_base)` comes out POSITIVE.
    func testHeightFormulaIsPositiveWithRealisticGravityVectors() {
        // Cruiser stands 25 m back from a 30 m tree, eyes at 1.6 m.
        // To aim back camera at treetop: it elevates ≈ +48.7° above
        // horizontal. Gravity in device frame: top of phone tilts
        // forward 48.7° → g = (0, -cos48.7°, +sin48.7°).
        // To aim back camera at tree base: it depresses ≈ −3.4°.
        // Top of phone tilts backward 3.4° → g = (0, -cos3.4°, -sin3.4°).
        let topAngleRad = 48.7 * .pi / 180.0
        let baseAngleRad = -3.4 * .pi / 180.0

        let gTop = SIMD3<Double>(0,
                                  -cos(topAngleRad),
                                   sin(topAngleRad))
        let gBase = SIMD3<Double>(0,
                                   -cos(abs(baseAngleRad)),
                                  -sin(abs(baseAngleRad)))

        let alphaTop = IMUHelpers.pitchFromGravity(gTop)
        let alphaBase = IMUHelpers.pitchFromGravity(gBase)

        XCTAssertGreaterThan(alphaTop, alphaBase,
            "α_top must exceed α_base for a positive height. "
            + "α_top=\(alphaTop), α_base=\(alphaBase)")
        let H = 25.0 * (tan(alphaTop) - tan(alphaBase))
        XCTAssertGreaterThan(H, 0,
            "Height must be positive for a tree taller than eye level. H=\(H)")
        XCTAssertEqual(H, 30.0, accuracy: 0.5,
            "Formula recovers the 30 m tree to within 0.5 m. H=\(H)")
    }

    func testSampleCountInsideWindowMatchesFeed() {
        let b = IMUPitchBuffer()
        for i in 0..<40 {
            // 100 Hz → 10 ms spacing. 40 samples span 390 ms.
            b.append(timestamp: 1.0 + Double(i) * 0.01, pitchRad: 0.1)
        }
        // Window ±200 ms around t=1.2: [1.0, 1.4]. All 40 samples fall
        // in this band.
        XCTAssertEqual(b.sampleCount(centeredOn: 1.2, windowMs: 400), 40)
        // Tighter window ±50 ms → ≤ 11 samples.
        XCTAssertLessThanOrEqual(
            b.sampleCount(centeredOn: 1.2, windowMs: 100), 11)
    }
}
