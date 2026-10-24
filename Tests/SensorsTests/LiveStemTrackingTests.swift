import XCTest
@testable import Sensors

final class LiveStemTrackingTests: XCTestCase {
    private func stem(_ l: Double = 0.3, _ r: Double = 0.7) -> StemExtent {
        StemExtent(leftFraction: l, rightFraction: r, score: 0.9, maskPixels: 100)
    }
    private func mask() -> LiveStemMask {
        LiveStemMask.sample(viewWidth: 192, viewHeight: 256) { x, _ in x >= 0.3 && x < 0.7 }!
    }
    private func depthResult(z: Double = 1, foreground: Bool = false, maskSupported: Bool = true) -> BoundaryAlignment.Result? {
        var depth = [Double](repeating: z, count: 10_000)
        if foreground { for y in 48...52 { for x in 40...60 { depth[y * 100 + x] = 0.5 } } }
        let grid = BoundaryAlignment.Grid(width: 100, height: 100, row: 50, depth: depth, left: 30, right: 70, focal: 100)
        return BoundaryAlignment.correct(grid, rgbPixelsPerDepthPixel: 4,
            mask: { _, _ in maskSupported }, rowSegments: { _ in [(28, 72)] })
    }
    func testMissingDepthKeepsGuideButCannotProduceMeasurement() {
        let result = depthResult(z: 0)
        XCTAssertNil(result)
        let observation = LiveStemObservation.validatingGuide(stem(), mask: mask(), depthResult: result)
        XCTAssertEqual(observation.guideExtent, stem()); XCTAssertNotNil(observation.mask)
        XCTAssertNil(observation.extent)
    }
    func testOcclusionAndMaskSupportFailuresKeepGuidesButRejectMeasurement() {
        for result in [depthResult(foreground: true), depthResult(maskSupported: false)] {
            XCTAssertNotNil(result)
            let observation = LiveStemObservation.validatingGuide(stem(), mask: mask(), depthResult: result)
            XCTAssertEqual(observation.guideExtent, stem()); XCTAssertNil(observation.extent)
        }
    }
    func testApprovedDepthUsesExactVisibleGuideNotHalfCorrectedSpan() throws {
        let result = try XCTUnwrap(depthResult())
        XCTAssertEqual(result.left, 29, accuracy: 1e-10)
        let observation = LiveStemObservation.validatingGuide(stem(), mask: mask(), depthResult: result)
        XCTAssertEqual(observation.extent, stem()); XCTAssertEqual(observation.extent, observation.guideExtent)
        let noGuide = LiveStemObservation.validatingGuide(nil, mask: mask(), depthResult: result)
        XCTAssertNil(noGuide.extent)
        XCTAssertNil(LiveStemObservation.validatingGuide(stem(), mask: nil, depthResult: result).extent)
    }
    func testGuideAppearsOnFirstMaskWithoutAnyDepthLock() {
        var preview = LiveStemMaskPreview(), tracker = LiveStemTracker()
        let observation = LiveStemObservation(extent: nil, mask: mask(), guideExtent: stem())
        preview.update(observation, at: 100)
        XCTAssertEqual(preview.guideExtent, stem())
        XCTAssertNil(tracker.update(observation.extent, at: 100))
        XCTAssertFalse(preview.matchesMeasurement(nil, at: 100))
        XCTAssertEqual(preview.guidance(measurementReady: false), "Stem found — checking depth…")
    }
    func testGuideUsesActualCentreIntersectionEvenWhenNearbyRowsRejectMeasurement() throws {
        let shape: (Double, Double) -> Bool = { x, y in x >= 0.45 + (y - 0.5) * 0.8 && x < 0.55 + (y - 0.5) * 0.2 }
        let guide = try XCTUnwrap(LiveStemMask.guideExtent(score: 0.9, contains: shape))
        XCTAssertEqual(guide.leftFraction, 0.45, accuracy: 1.0 / 512)
        XCTAssertEqual(guide.rightFraction, 0.55, accuracy: 1.0 / 512)
        XCTAssertNil(LiveStemMask.centreExtent(score: 0.9, contains: shape))
    }
    func testGuideNeverBridgesCentreHoleClippedOrOffCentreMasks() {
        XCTAssertNil(LiveStemMask.guideExtent(score: 0.9) { _, _ in true })
        XCTAssertNil(LiveStemMask.guideExtent(score: 0.9) { x, _ in x > 0.6 && x < 0.8 })
        XCTAssertNil(LiveStemMask.guideExtent(score: 0.9) { x, _ in (x > 0.2 && x < 0.45) || (x > 0.55 && x < 0.8) })
        XCTAssertNil(LiveStemMask.guideExtent(score: 0.24) { x, _ in x > 0.3 && x < 0.7 })
    }
    func testGuideMovesImmediatelyWithoutGrantingMeasurementOnNewMask() {
        var preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(extent: stem(), mask: mask(), guideExtent: stem()), at: 100)
        XCTAssertTrue(preview.matchesMeasurement(stem(), at: 100))
        let moved = stem(0.35, 0.75)
        preview.update(LiveStemObservation(extent: nil, mask: mask(), guideExtent: moved), at: 100.1)
        XCTAssertEqual(preview.guideExtent, moved)
        XCTAssertFalse(preview.matchesMeasurement(stem(), at: 100))
        XCTAssertFalse(preview.matchesMeasurement(stem(), at: 100.1))
        XCTAssertFalse(preview.matchesMeasurement(nil, at: 100.1))
    }
    func testSameGeometryFromOlderMaskCannotAuthorizeNewMask() {
        var preview = LiveStemMaskPreview()
        let observation = LiveStemObservation(extent: stem(), mask: mask(), guideExtent: stem())
        preview.update(observation, at: 100); preview.update(observation, at: 100.1)
        XCTAssertFalse(preview.matchesMeasurement(stem(), at: 100))
        XCTAssertTrue(preview.matchesMeasurement(stem(), at: 100.1))
        XCTAssertFalse(preview.matchesMeasurement(stem(0.3001, 0.7), at: 100.1))
    }
    func testMaskAndGuideExpireAndResetTogetherAndMissDoesNotRefresh() {
        var preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(extent: nil, mask: mask(), guideExtent: stem()), at: 100)
        preview.update(nil, at: 100.3); XCTAssertEqual(preview.guideExtent, stem())
        preview.expire(at: 100.61); XCTAssertNil(preview.current); XCTAssertNil(preview.guideExtent)
        XCTAssertFalse(preview.matchesMeasurement(stem(), at: 100))
        preview.update(LiveStemObservation(extent: nil, mask: mask(), guideExtent: stem()), at: 101)
        preview.reset(); XCTAssertNil(preview.guideExtent)
        XCTAssertEqual(preview.guidance(measurementReady: false), "Finding stem…")
    }
    func testMaskWithoutCentreIntersectionClearsOldGuide() {
        var preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(extent: nil, mask: mask(), guideExtent: stem()), at: 100)
        preview.update(LiveStemObservation(extent: nil, mask: mask()), at: 100.1)
        XCTAssertNil(preview.guideExtent)
        XCTAssertEqual(preview.guidance(measurementReady: false), "Aim at the trunk centre.")
    }
    func testSlowInferenceCanDisplayButCannotMeasure() {
        let decision = LiveStemResultPolicy.evaluate(age: 0.8, travelMeters: 0.01, facingDot: 0.9998)
        XCTAssertTrue(decision.displayMask); XCTAssertFalse(decision.allowMeasurement)
        var preview = LiveStemMaskPreview()
        XCTAssertNotNil(preview.update(LiveStemObservation(extent: stem(), mask: mask()), at: 100, receivedAt: 100.8))
        XCTAssertNotNil(preview.expire(at: 101.3))
        XCTAssertNil(preview.expire(at: 101.41))
    }
    func testPreviewReceiptHoldStillHonoursMaximumSourceAge() {
        var preview = LiveStemMaskPreview()
        let observation = LiveStemObservation(extent: nil, mask: mask())
        XCTAssertNotNil(preview.update(observation, at: 100, receivedAt: 101.3))
        XCTAssertNotNil(preview.expire(at: 101.45))
        XCTAssertNil(preview.expire(at: 101.51))
        XCTAssertNil(preview.update(observation, at: 102, receivedAt: 103.51))
        XCTAssertNil(preview.update(observation, at: 105, receivedAt: 104))
    }
    func testPreviewMotionToleranceNeverWeakensMeasurementPolicy() {
        let handMotion = LiveStemResultPolicy.evaluate(age: 0.2, travelMeters: 0.03, facingDot: 0.998)
        XCTAssertTrue(handMotion.displayMask); XCTAssertFalse(handMotion.allowMeasurement)
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.2, travelMeters: 0.05, facingDot: 1).displayMask)
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.2, travelMeters: 0, facingDot: 0.995).displayMask)
    }
    func testMeasurementAgeAndPoseLimitsRemainUnchanged() {
        let fast = LiveStemResultPolicy.evaluate(age: 0.45, travelMeters: 0.019, facingDot: 0.9995)
        XCTAssertTrue(fast.displayMask); XCTAssertTrue(fast.allowMeasurement)
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.4501, travelMeters: 0, facingDot: 1).allowMeasurement)
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: 0.02, facingDot: 1).allowMeasurement)
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: 0, facingDot: 0.9994).allowMeasurement)
    }
    func testPreviewRejectsDeviceRollEvenWhenCameraStillFacesForward() {
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: 0, facingDot: 1, upDot: 0.98).displayMask)
        XCTAssertFalse(LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: 0, facingDot: 1, upDot: .nan).displayMask)
    }
    func testInvalidTimingOrChangedViewportRejectBothUses() {
        let invalid = [
            LiveStemResultPolicy.evaluate(age: -0.1, travelMeters: 0, facingDot: 1),
            LiveStemResultPolicy.evaluate(age: .nan, travelMeters: 0, facingDot: 1),
            LiveStemResultPolicy.evaluate(age: 1.51, travelMeters: 0, facingDot: 1),
            LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: .infinity, facingDot: 1),
            LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: 0, facingDot: .nan),
            LiveStemResultPolicy.evaluate(age: 0.1, travelMeters: 0, facingDot: 1, mappingCompatible: false)
        ]
        XCTAssertTrue(invalid.allSatisfy { !$0.displayMask && !$0.allowMeasurement })
    }
    func testMissAndOutOfOrderResultCannotExtendSlowMaskHold() {
        var preview = LiveStemMaskPreview()
        let observation = LiveStemObservation(extent: nil, mask: mask())
        preview.update(observation, at: 100, receivedAt: 100.8)
        XCTAssertNotNil(preview.update(nil, at: 101.2))
        XCTAssertNil(preview.expire(at: 101.41))
        preview.update(observation, at: 102, receivedAt: 102.5)
        XCTAssertNil(preview.update(observation, at: 101.9, receivedAt: 102.6))
    }
    func testDetectedMaskIsVisibleEvenWhenDepthOrGuideExtentIsRejected() throws {
        var preview = LiveStemMaskPreview(), tracker = LiveStemTracker()
        let observation = LiveStemObservation(extent: nil, mask: mask())
        XCTAssertNotNil(preview.update(observation, at: 100))
        XCTAssertNil(tracker.update(observation.extent, at: 100))
        XCTAssertFalse(tracker.isFresh(at: 100))
    }
    func testFirstMaskDoesNotWaitForTwoFrameMeasurementLock() {
        var preview = LiveStemMaskPreview(), tracker = LiveStemTracker()
        let observation = LiveStemObservation(extent: stem(), mask: mask())
        XCTAssertNotNil(preview.update(observation, at: 100))
        XCTAssertNil(tracker.update(observation.extent, at: 100))
        XCTAssertEqual(tracker.update(observation.extent, at: 100.1), stem())
    }
    func testRepeatedMaskWithoutExtentNeverRefreshesCaptureLock() {
        var preview = LiveStemMaskPreview(), tracker = LiveStemTracker()
        tracker.update(stem(), at: 100); tracker.update(stem(), at: 100.1)
        let rejected = LiveStemObservation(extent: nil, mask: mask())
        preview.update(rejected, at: 100.2); tracker.update(rejected.extent, at: 100.2)
        XCTAssertNotNil(preview.update(rejected, at: 100.8))
        XCTAssertNil(tracker.update(rejected.extent, at: 100.8))
        XCTAssertFalse(tracker.isFresh(at: 100.8))
    }
    func testMaskBriefMissIsHeldButMissesDoNotExtendExpiry() {
        var preview = LiveStemMaskPreview()
        preview.update(LiveStemObservation(extent: nil, mask: mask()), at: 100)
        XCTAssertNotNil(preview.update(nil, at: 100.3))
        XCTAssertNotNil(preview.update(LiveStemObservation(extent: stem(), mask: nil), at: 100.5))
        XCTAssertNil(preview.expire(at: 100.61))
        XCTAssertNil(preview.current)
    }
    func testMaskResetAndInvalidOrBackwardsClockDiscardPreview() {
        var preview = LiveStemMaskPreview()
        let observation = LiveStemObservation(extent: nil, mask: mask())
        preview.update(observation, at: 100); preview.reset()
        XCTAssertNil(preview.current)
        preview.update(observation, at: 101)
        XCTAssertNil(preview.expire(at: 100))
        preview.update(observation, at: 102)
        XCTAssertNil(preview.update(observation, at: .nan))
        preview.update(observation, at: 103)
        XCTAssertNil(preview.expire(at: .infinity))
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
        let lowConfidence = StemExtent(leftFraction: 0.3, rightFraction: 0.7, score: 0.24, maskPixels: 100)
        XCTAssertNil(tracker.update(lowConfidence, at: 100.2))
        XCTAssertNil(tracker.update(lowConfidence, at: 100.3))
        XCTAssertNil(LiveStemMask.centreExtent(score: 0.24) { x, _ in x >= 0.3 && x < 0.7 })
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
