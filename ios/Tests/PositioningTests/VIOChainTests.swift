// v0.4 minimal VIOChain: append anchors, transfer to a new plot by
// walking, reject when walk > 200 m or tracking broke, demote tier
// with distance.

import XCTest
import simd
@testable import Positioning
import Models

final class VIOChainTests: XCTestCase {

    private let anchorA = VIOChain.Anchor(
        lat: 45, lon: -122,
        pointWorld: SIMD3<Float>(0, 0, 0),
        tier: .A)

    func testTransferFromTierAOver50mNorthInheritsTierA() {
        var chain = VIOChain()
        chain.append(anchorA)
        let r = chain.transfer(
            to: SIMD3<Float>(0, 0, -50),
            trackingStateWasNormalThroughout: true)
        XCTAssertEqual(r?.tier, .A)
        XCTAssertEqual(r?.source, .vioChain)
        let expectedDLat = 50.0 / 111_320.0
        XCTAssertEqual(r?.lat ?? 0, 45 + expectedDLat, accuracy: 1e-9)
    }

    func testTransferBeyondBudgetReturnsNil() {
        var chain = VIOChain()
        chain.append(anchorA)
        let r = chain.transfer(
            to: SIMD3<Float>(0, 0, -250),
            trackingStateWasNormalThroughout: true)
        XCTAssertNil(r, "walk > 200 m must break the chain")
    }

    func testTrackingNotNormalReturnsNil() {
        var chain = VIOChain()
        chain.append(anchorA)
        XCTAssertNil(chain.transfer(
            to: SIMD3<Float>(0, 0, -50),
            trackingStateWasNormalThroughout: false))
    }

    func testEmptyChainReturnsNil() {
        let chain = VIOChain()
        XCTAssertNil(chain.transfer(
            to: SIMD3<Float>(0, 0, -50),
            trackingStateWasNormalThroughout: true))
    }

    func testResetClearsAnchors() {
        var chain = VIOChain()
        chain.append(anchorA)
        XCTAssertEqual(chain.anchors.count, 1)
        chain.reset()
        XCTAssertTrue(chain.anchors.isEmpty)
    }

    func testDuplicateAppendAtSameWorldPointIsNoOp() {
        var chain = VIOChain()
        chain.append(anchorA)
        chain.append(anchorA)
        XCTAssertEqual(chain.anchors.count, 1)
    }

    func testTransferDemotesOneStepAt150m() {
        var chain = VIOChain()
        chain.append(anchorA)
        let r = chain.transfer(
            to: SIMD3<Float>(0, 0, -150),
            trackingStateWasNormalThroughout: true)
        XCTAssertEqual(r?.tier, .B)
    }

    func testAlignChainToFixesIsNoOpForNow() {
        // v0.5 stub — must compile and not crash.
        var chain = VIOChain()
        chain.append(anchorA)
        chain.alignChainToFixes([
            (SIMD3<Float>(0, 0, 0), 45, -122)
        ])
        XCTAssertEqual(chain.anchors.count, 1)
    }
}
