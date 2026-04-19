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
        let g = SIMD3<Double>(0, -1, 0)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), 0, accuracy: 1e-9)
    }

    func testPitchFaceUpIsPlusHalfPi() {
        let g = SIMD3<Double>(0, 0, -1)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), .pi / 2, accuracy: 1e-9)
    }

    func testPitchFaceDownIsMinusHalfPi() {
        let g = SIMD3<Double>(0, 0, 1)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), -.pi / 2, accuracy: 1e-9)
    }

    func testPitchTilted45DegUp() {
        // Tilting the top of the phone back 45° from portrait rotates
        // gravity from (0,-1,0) to (0, -cos45, -sin45).
        let c = cos(Double.pi / 4)
        let s = sin(Double.pi / 4)
        let g = SIMD3<Double>(0, -c, -s)
        XCTAssertEqual(IMUHelpers.pitchFromGravity(g), .pi / 4, accuracy: 1e-9)
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
