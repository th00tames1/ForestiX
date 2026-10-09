import XCTest
@testable import Sensors

final class LiveStemTrackingTests: XCTestCase {
    private func stem(_ l: Double = 0.3, _ r: Double = 0.7) -> StemExtent {
        StemExtent(leftFraction: l, rightFraction: r, score: 0.9, maskPixels: 100)
    }
    func testAcquisitionNeedsTwoConsistentFreshFrames() {
        var tracker = LiveStemTracker()
        XCTAssertNil(tracker.update(stem(), at: 100))
        XCTAssertEqual(tracker.update(stem(), at: 100.1), stem())
        XCTAssertTrue(tracker.isFresh(at: 100.2))
    }
    func testSmallMotionIsSmoothedWithoutFeedingAnOldBracketIntoInference() throws {
        var tracker = LiveStemTracker()
        tracker.update(stem(), at: 100); tracker.update(stem(), at: 100.1)
        let next = try XCTUnwrap(tracker.update(stem(0.31, 0.71), at: 100.2))
        XCTAssertEqual(next.leftFraction, 0.3045, accuracy: 1e-12)
        XCTAssertEqual(next.rightFraction, 0.7045, accuracy: 1e-12)
    }
    func testSingleOutlierDoesNotMoveGuidesButRepeatedNewPositionDoes() {
        var tracker = LiveStemTracker()
        tracker.update(stem(), at: 100); tracker.update(stem(), at: 100.1)
        XCTAssertEqual(tracker.update(stem(0.1, 0.6), at: 100.2), stem())
        XCTAssertEqual(tracker.lastGood, 100.1)
        XCTAssertEqual(tracker.update(stem(0.1, 0.6), at: 100.3), stem(0.1, 0.6))
    }
    func testBriefDropoutHoldsButDoesNotRefreshCaptureLock() {
        var tracker = LiveStemTracker()
        tracker.update(stem(), at: 100); tracker.update(stem(), at: 100.1)
        XCTAssertEqual(tracker.update(nil, at: 100.5), stem())
        XCTAssertNil(tracker.update(nil, at: 100.71))
        XCTAssertFalse(tracker.isFresh(at: 100.71))
    }
    func testPendingFramesCannotConfirmAcrossLongPause() {
        var tracker = LiveStemTracker()
        tracker.update(stem(), at: 100)
        XCTAssertNil(tracker.update(stem(), at: 102))
        XCTAssertEqual(tracker.update(stem(), at: 102.1), stem())
    }
    func testResetAndInvalidClockDiscardAllLocks() {
        var tracker = LiveStemTracker()
        tracker.update(stem(), at: 100); tracker.update(stem(), at: 100.1)
        tracker.reset(); XCTAssertNil(tracker.current)
        tracker.update(stem(), at: 100.2); tracker.update(stem(), at: 100.3)
        XCTAssertNil(tracker.update(stem(), at: .nan))
        XCTAssertFalse(tracker.isFresh(at: 100.4))
    }
    func testInvalidBoundaryOrConfidenceCannotAcquire() {
        var tracker = LiveStemTracker()
        let invalid = StemExtent(leftFraction: .nan, rightFraction: 0.7, score: 0.9, maskPixels: 100)
        XCTAssertNil(tracker.update(invalid, at: 100))
        XCTAssertNil(tracker.update(invalid, at: 100.1))
        XCTAssertNil(LiveStemMask.centreExtent(score: .nan) { _, _ in true })
    }
    func testVisibleMaskPreservesShapeRatherThanPaintingBoundingRectangle() throws {
        let mask = try XCTUnwrap(LiveStemMask.sample(viewWidth: 192, viewHeight: 256) { x, y in
            x >= 0.2 + 0.15 * y && x < 0.6 + 0.15 * y
        })
        XCTAssertEqual(mask.runs.count, mask.height)
        XCTAssertGreaterThan(mask.runs.last!.start, mask.runs.first!.start + 5)
        XCTAssertEqual(mask.width, 96)
    }
    func testCentreLineUsesHorizontalStemSpan() throws {
        let extent = try XCTUnwrap(LiveStemMask.centreExtent(score: 0.9) { x, _ in x >= 0.3 && x < 0.7 })
        XCTAssertEqual(extent.leftFraction, 0.3, accuracy: 1.0 / 512)
        XCTAssertEqual(extent.rightFraction, 0.7, accuracy: 1.0 / 512)
    }
    func testHolesClippedMasksAndNeighbouringObjectsDoNotBecomeAWidth() {
        XCTAssertNil(LiveStemMask.centreExtent(score: 0.9) { _, _ in true })
        XCTAssertNil(LiveStemMask.centreExtent(score: 0.9) { x, _ in x > 0.6 && x < 0.8 })
        XCTAssertNil(LiveStemMask.centreExtent(score: 0.9) { x, y in
            x >= 0.3 && x < 0.7 && !(abs(y - 0.5) < 0.001 && x > 0.49 && x < 0.51)
        })
        XCTAssertNil(LiveStemMask.sample(viewWidth: 192, viewHeight: 256) { _, _ in false })
    }
    func testProjectionAllFourRotationsAndInvalidMapping() throws {
        let expected = [(101, 81), (110, 101), (154, 110), (81, 154)]
        for turn in 0...3 {
            let p = try XCTUnwrap(LiveStemMask.cameraPoint(depthX: 100, depthY: 80,
                depthWidth: 256, depthHeight: 192, cameraWidth: 256, cameraHeight: 192, quarterTurns: turn))
            XCTAssertEqual(p.0, expected[turn].0); XCTAssertEqual(p.1, expected[turn].1)
        }
        XCTAssertNil(LiveStemMask.cameraPoint(depthX: .nan, depthY: 0, depthWidth: 256,
            depthHeight: 192, cameraWidth: 256, cameraHeight: 192, quarterTurns: 1))
    }
    func testPortraitColumnMappingStillProducesHorizontalOverlayAndGuide() throws {
        // Synthetic 90-degree screen→depth transform: screen-x changes depth-y.
        let contains: (Double, Double) -> Bool = { x, y in
            guard let rgb = LiveStemMask.cameraPoint(depthX: y * 256 - 1, depthY: 192 - x * 192 - 1,
                depthWidth: 256, depthHeight: 192, cameraWidth: 256, cameraHeight: 192, quarterTurns: 1)
            else { return false }
            return rgb.0 >= 57 && rgb.0 < 134
        }
        let extent = try XCTUnwrap(LiveStemMask.centreExtent(score: 0.9, contains: contains))
        XCTAssertEqual(extent.leftFraction, 0.3, accuracy: 0.02)
        XCTAssertEqual(extent.rightFraction, 0.7, accuracy: 0.02)
        let mask = try XCTUnwrap(LiveStemMask.sample(viewWidth: 192, viewHeight: 256, contains: contains))
        XCTAssertTrue(mask.runs.allSatisfy { $0.start > 20 && $0.end < 75 })
    }
}
