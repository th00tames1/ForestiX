// Spec §7.3.2 Done criteria:
//  * 50 m north walk from a tier-A opening fix → plot center 50 m
//    north, tier A (demotion kicks in above 100 m).
//  * 250 m walk → tier D.
// Plus: tracking-not-normal → nil, ENU → lat/lon conversion accuracy.

import XCTest
import simd
@testable import Positioning
import Models

final class OffsetFromOpeningTests: XCTestCase {

    private func openingFix(lat: Double, lon: Double,
                            tier: PositionTier) -> PlotCenterResult {
        PlotCenterResult(
            lat: lat, lon: lon,
            source: .gpsAveraged,
            tier: tier,
            nSamples: 60,
            medianHAccuracyM: 3,
            sampleStdXyM: 2,
            offsetWalkM: nil)
    }

    // MARK: - Done criteria

    func test50mNorthFromTierAReturnsTierAAt50mNorth() {
        // Walk 50 m due north: Δworld.z = −50 (world −Z is north).
        let input = OffsetFromOpening.Input(
            openingFix: openingFix(lat: 45, lon: -122, tier: .A),
            openingPointWorld: SIMD3<Float>(0, 0, 0),
            plotPointWorld:    SIMD3<Float>(0, 0, -50),
            trackingStateWasNormalThroughout: true)
        guard let r = OffsetFromOpening.compute(input: input)
        else { return XCTFail("expected a result") }

        // 50 m north at lat 45° is 50 / 111320 ≈ 4.492e-4° latitude.
        let expectedDLat = 50.0 / 111_320.0
        XCTAssertEqual(r.lat, 45 + expectedDLat, accuracy: 1e-9)
        XCTAssertEqual(r.lon, -122, accuracy: 1e-9)
        XCTAssertEqual(r.tier, .A, "≤ 100 m walk → tier inherited")
        XCTAssertEqual(r.source, .vioOffset)
        XCTAssertNotNil(r.offsetWalkM)
        XCTAssertEqual(r.offsetWalkM!, 50, accuracy: 1e-4)
    }

    func test250mWalkReturnsTierD() {
        let input = OffsetFromOpening.Input(
            openingFix: openingFix(lat: 45, lon: -122, tier: .A),
            openingPointWorld: SIMD3<Float>(0, 0, 0),
            plotPointWorld:    SIMD3<Float>(0, 0, -250),
            trackingStateWasNormalThroughout: true)
        let r = OffsetFromOpening.compute(input: input)
        XCTAssertEqual(r?.tier, .D)
    }

    // MARK: - Guards

    func testTrackingNotNormalReturnsNil() {
        let input = OffsetFromOpening.Input(
            openingFix: openingFix(lat: 45, lon: -122, tier: .A),
            openingPointWorld: SIMD3<Float>(0, 0, 0),
            plotPointWorld:    SIMD3<Float>(0, 0, -50),
            trackingStateWasNormalThroughout: false)
        XCTAssertNil(OffsetFromOpening.compute(input: input))
    }

    func testWalkBetween100and200mDemotesOneStep() {
        let input = OffsetFromOpening.Input(
            openingFix: openingFix(lat: 45, lon: -122, tier: .A),
            openingPointWorld: SIMD3<Float>(0, 0, 0),
            plotPointWorld:    SIMD3<Float>(0, 0, -150),
            trackingStateWasNormalThroughout: true)
        XCTAssertEqual(OffsetFromOpening.compute(input: input)?.tier, .B)
    }

    // MARK: - Geometry

    func test100mEastWalkMovesLongitudeOnly() {
        // East-only displacement → only dLon moves, dLat = 0.
        let input = OffsetFromOpening.Input(
            openingFix: openingFix(lat: 45, lon: -122, tier: .B),
            openingPointWorld: SIMD3<Float>(0, 0, 0),
            plotPointWorld:    SIMD3<Float>(100, 0, 0),
            trackingStateWasNormalThroughout: true)
        guard let r = OffsetFromOpening.compute(input: input)
        else { return XCTFail("expected a result") }
        XCTAssertEqual(r.lat, 45, accuracy: 1e-9)
        let metersPerDegLon = 111_320.0 * cos(45 * .pi / 180)
        XCTAssertEqual(r.lon, -122 + 100 / metersPerDegLon, accuracy: 1e-9)
        XCTAssertEqual(r.tier, .B, "100 m is not > 100 → no demote")
    }

    // MARK: - Tier demotion table

    func testDemoteTable() {
        XCTAssertEqual(OffsetFromOpening.demote(base: .A, walkDistanceM: 50),  .A)
        XCTAssertEqual(OffsetFromOpening.demote(base: .A, walkDistanceM: 150), .B)
        XCTAssertEqual(OffsetFromOpening.demote(base: .B, walkDistanceM: 150), .C)
        XCTAssertEqual(OffsetFromOpening.demote(base: .C, walkDistanceM: 150), .D)
        XCTAssertEqual(OffsetFromOpening.demote(base: .A, walkDistanceM: 250), .D)
        XCTAssertEqual(OffsetFromOpening.demote(base: .D, walkDistanceM: 50),  .D)
    }
}
