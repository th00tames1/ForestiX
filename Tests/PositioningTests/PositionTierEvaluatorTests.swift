// Verifies §7.3's "four strategies in priority order" aggregator:
// best tier wins, ties broken by source priority.

import XCTest
@testable import Positioning
import Models

final class PositionTierEvaluatorTests: XCTestCase {

    private func result(
        source: PositionSource, tier: PositionTier,
        lat: Double = 0, lon: Double = 0
    ) -> PlotCenterResult {
        PlotCenterResult(
            lat: lat, lon: lon,
            source: source, tier: tier,
            nSamples: 30, medianHAccuracyM: 5,
            sampleStdXyM: 2, offsetWalkM: nil)
    }

    func testEmptyCandidatesReturnsNil() {
        XCTAssertNil(PositionTierEvaluator.decide(candidates: []))
    }

    func testPicksHighestTier() {
        let candidates: [PositionTierEvaluator.Candidate] = [
            .init(result: result(source: .gpsAveraged, tier: .C)),
            .init(result: result(source: .vioChain,   tier: .A)),
            .init(result: result(source: .vioOffset,  tier: .B))
        ]
        let d = PositionTierEvaluator.decide(candidates: candidates)
        XCTAssertEqual(d?.chosen.source, .vioChain)
        XCTAssertEqual(d?.chosen.tier, .A)
    }

    func testTieBreaksByPriorityExternalRTKFirst() {
        // All tier A — externalRTK must win per §7.3 priority.
        let candidates: [PositionTierEvaluator.Candidate] = [
            .init(result: result(source: .gpsAveraged, tier: .A)),
            .init(result: result(source: .externalRTK, tier: .A)),
            .init(result: result(source: .vioOffset,   tier: .A))
        ]
        let d = PositionTierEvaluator.decide(candidates: candidates)
        XCTAssertEqual(d?.chosen.source, .externalRTK)
    }

    func testTieBreaksByPriorityGPSOverVIO() {
        let candidates: [PositionTierEvaluator.Candidate] = [
            .init(result: result(source: .vioOffset,   tier: .B)),
            .init(result: result(source: .gpsAveraged, tier: .B)),
            .init(result: result(source: .vioChain,    tier: .B))
        ]
        let d = PositionTierEvaluator.decide(candidates: candidates)
        XCTAssertEqual(d?.chosen.source, .gpsAveraged)
    }

    func testConsideredListPreservesAllCandidates() {
        let candidates: [PositionTierEvaluator.Candidate] = [
            .init(result: result(source: .gpsAveraged, tier: .C),
                  note: "accepted"),
            .init(result: result(source: .vioOffset,   tier: .A),
                  note: "accepted"),
            .init(result: result(source: .vioChain,    tier: .D),
                  note: "rejected: walk 210 m")
        ]
        let d = PositionTierEvaluator.decide(candidates: candidates)
        XCTAssertEqual(d?.considered.count, 3)
        XCTAssertEqual(d?.considered[2].note, "rejected: walk 210 m")
    }

    func testManualIsLowestPriorityOnTie() {
        let candidates: [PositionTierEvaluator.Candidate] = [
            .init(result: result(source: .manual,    tier: .D)),
            .init(result: result(source: .vioChain,  tier: .D))
        ]
        let d = PositionTierEvaluator.decide(candidates: candidates)
        XCTAssertEqual(d?.chosen.source, .vioChain)
    }

    func testSingleCandidateIsReturnedAsIs() {
        let r = result(source: .gpsAveraged, tier: .B, lat: 45, lon: -122)
        let d = PositionTierEvaluator.decide(
            candidates: [.init(result: r)])
        XCTAssertEqual(d?.chosen.lat, 45)
        XCTAssertEqual(d?.chosen.lon, -122)
        XCTAssertEqual(d?.chosen.source, .gpsAveraged)
    }

    func testTierRankOrdering() {
        XCTAssertGreaterThan(
            PositionTierEvaluator.tierRank(.A),
            PositionTierEvaluator.tierRank(.B))
        XCTAssertGreaterThan(
            PositionTierEvaluator.tierRank(.C),
            PositionTierEvaluator.tierRank(.D))
    }

    func testSourcePriorityOrdering() {
        XCTAssertLessThan(
            PositionTierEvaluator.sourcePriority(.externalRTK),
            PositionTierEvaluator.sourcePriority(.gpsAveraged))
        XCTAssertLessThan(
            PositionTierEvaluator.sourcePriority(.gpsAveraged),
            PositionTierEvaluator.sourcePriority(.vioOffset))
        XCTAssertLessThan(
            PositionTierEvaluator.sourcePriority(.vioOffset),
            PositionTierEvaluator.sourcePriority(.vioChain))
        XCTAssertLessThan(
            PositionTierEvaluator.sourcePriority(.vioChain),
            PositionTierEvaluator.sourcePriority(.manual))
    }
}
