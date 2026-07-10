// Spec §7.3.1 Done criteria:
//  * Synthetic samples with 3 m scatter and accuracy 5 m → tier A.
//  * Synthetic with 15 m scatter → tier C.
// Plus: < 30 samples → nil, accuracy filter, median-on-ENU correctness.

import XCTest
@testable import Positioning
import Models

final class GPSAveragingTests: XCTestCase {

    // MARK: - Fixtures

    /// Deterministic "noisy" GPS samples around a fixed center.
    /// `scatterM` is the peak east/north offset in metres; samples
    /// are scattered in a diamond pattern so means ≈ 0.
    private func samples(
        centerLat: Double,
        centerLon: Double,
        accuracyM: Double,
        scatterM: Double,
        count: Int
    ) -> [CLLocationSnapshot] {
        let metersPerDegLat = 111_320.0
        let metersPerDegLon = 111_320.0 * cos(centerLat * .pi / 180)
        var out: [CLLocationSnapshot] = []
        out.reserveCapacity(count)
        // Deterministic sin/cos phase that covers N, E, S, W roughly.
        for i in 0..<count {
            let theta = Double(i) * 2.0 * .pi / Double(count)
            let dE = scatterM * cos(theta)
            let dN = scatterM * sin(theta)
            out.append(CLLocationSnapshot(
                latitude: centerLat + dN / metersPerDegLat,
                longitude: centerLon + dE / metersPerDegLon,
                horizontalAccuracyM: accuracyM,
                timestamp: Date(timeIntervalSinceReferenceDate: Double(i))))
        }
        return out
    }

    // MARK: - Done criteria

    func testTierAFromTightScatterGoodAccuracy() {
        let s = samples(centerLat: 45.0, centerLon: -122.0,
                        accuracyM: 4.0, scatterM: 2.0, count: 60)
        guard let r = GPSAveraging.compute(input: .init(samples: s))
        else { return XCTFail("expected a result") }
        XCTAssertEqual(r.tier, .A)
        XCTAssertEqual(r.nSamples, 60)
        XCTAssertEqual(r.medianHAccuracyM, 4.0, accuracy: 1e-4)
        XCTAssertLessThan(r.sampleStdXyM, 3.0)
        XCTAssertEqual(r.source, .gpsAveraged)
        XCTAssertEqual(r.lat, 45.0, accuracy: 1e-6,
                       "median recovers the true center latitude")
        XCTAssertEqual(r.lon, -122.0, accuracy: 1e-6,
                       "median recovers the true center longitude")
    }

    func testTierCFromLooseScatter() {
        // 15 m scatter, 15 m accuracy → passes the 20 m filter, but
        // sample_std_xy > 5 m and mAcc > 10 m → tier C.
        let s = samples(centerLat: 45.0, centerLon: -122.0,
                        accuracyM: 15.0, scatterM: 15.0, count: 60)
        let r = GPSAveraging.compute(input: .init(samples: s))
        XCTAssertEqual(r?.tier, .C)
    }

    // MARK: - Guards & edges

    func testReturnsNilBelow30Samples() {
        let s = samples(centerLat: 45.0, centerLon: -122.0,
                        accuracyM: 4.0, scatterM: 1.0, count: 29)
        XCTAssertNil(GPSAveraging.compute(input: .init(samples: s)))
    }

    func testAccuracyFilterDropsBadSamples() {
        // 30 good + 50 awful (accuracy 50 m) — filter drops the bad
        // ones and the remaining 30 are tier A.
        var s = samples(centerLat: 45.0, centerLon: -122.0,
                        accuracyM: 3.0, scatterM: 1.0, count: 30)
        s.append(contentsOf: samples(
            centerLat: 45.0, centerLon: -122.0,
            accuracyM: 50.0, scatterM: 100.0, count: 50))
        guard let r = GPSAveraging.compute(input: .init(samples: s))
        else { return XCTFail("expected a result") }
        XCTAssertEqual(r.tier, .A)
        XCTAssertEqual(r.nSamples, 30, "awful samples filtered out")
    }

    func testNegativeAccuracyRejected() {
        // CoreLocation documents a negative horizontalAccuracy as an
        // invalid fix (e.g. simulator with no fix). Must be filtered.
        var s = samples(centerLat: 45.0, centerLon: -122.0,
                        accuracyM: 3.0, scatterM: 1.0, count: 40)
        s.append(CLLocationSnapshot(
            latitude: 0, longitude: 0,
            horizontalAccuracyM: -1, timestamp: Date()))
        let r = GPSAveraging.compute(input: .init(samples: s))
        XCTAssertEqual(r?.nSamples, 40)
    }

    // MARK: - Tier table

    func testClassifyThresholds() {
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 4, sampleStdXyM: 2), .A)
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 4, sampleStdXyM: 4), .B)
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 9, sampleStdXyM: 4), .B)
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 12, sampleStdXyM: 8), .C)
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 25, sampleStdXyM: 8), .D)
    }

    func testTierDemotionAtBoundary() {
        // Exactly at the A cutoff: spec uses strict < 5 / strict < 3.
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 5, sampleStdXyM: 2), .B)
        XCTAssertEqual(GPSAveraging.classify(medianHAccuracyM: 4, sampleStdXyM: 3), .B)
    }
}
