import XCTest
import simd
@testable import Sensors

/// Synthetic app fixtures: Auto chooses bounds, Adjust measures current depth.
final class AutoMaskBracketTests: XCTestCase {
    private let guide = StemExtent(leftFraction: 1.0 / 3, rightFraction: 2.0 / 3, score: 0.8, maskPixels: 1)
    private var mask: LiveStemMask { LiveStemMask(width: 1, height: 1, runs: [.init(row: 0, start: 0, end: 1)]) }
    private func frame(depth: Float) -> ARDepthFrame {
        ARDepthFrame(width: 120, height: 90, depth: .init(repeating: depth, count: 10800),
            confidence: .init(repeating: 2, count: 10800),
            intrinsics: simd_float3x3(columns: (SIMD3(100,0,0), SIMD3(0,150,0), SIMD3(60,45,1))),
            cameraPoseWorld: matrix_identity_float4x4, timestamp: 11)
    }
    func testFirstMaskPlacesExactBracketDespiteLegacyMotionAndLatencyRefusal() throws {
        let decision = LiveStemResultPolicy.evaluate(age: 0.8, travelMeters: 0.03, facingDot: 0.998)
        XCTAssertTrue(decision.displayMask)
        XCTAssertFalse(decision.allowMeasurement)
        var preview = LiveStemMaskPreview()
        preview.update(.init(extent: nil, mask: mask, guideExtent: guide), at: 10, receivedAt: 10.8)
        XCTAssertEqual(try XCTUnwrap(preview.bracketForDepth(at: 10.8)), guide)
        XCTAssertEqual(try XCTUnwrap(preview.bracketForDepth(at: 11.2)), guide)
        XCTAssertNil(preview.bracketForDepth(at: 11.41))
    }
    func testAutoBoundsUseSameCurrentDepthAndAxisMathAsAdjust() throws {
        var preview = LiveStemMaskPreview()
        preview.update(.init(extent: nil, mask: mask, guideExtent: guide), at: 11)
        let bounds = try XCTUnwrap(preview.bracketForDepth(at: 11))
        for column in [false, true] {
            let axis: GuideAxis = column ? .col(x: 60) : .row(y: 45)
            for depth: Float in [1, 2] {
                let f = frame(depth: depth)
                let auto = try XCTUnwrap(DBHEstimator.bracketChordFit(frame: f, guideAxis: axis,
                    leftFraction: bounds.leftFraction, rightFraction: bounds.rightFraction))
                let adjust = try XCTUnwrap(DBHEstimator.bracketChordFit(frame: f, guideAxis: axis,
                    leftFraction: guide.leftFraction, rightFraction: guide.rightFraction))
                XCTAssertEqual(auto.diameterCm, adjust.diameterCm)
                XCTAssertEqual(auto.effectiveTapDepth, Double(depth), accuracy: 1e-6)
                let expected = try XCTUnwrap(BoundaryAlignment.sampledDiameterCm(
                    spanPx: column ? 30 : 40, medianDepthM: Double(depth), focalPx: column ? 150 : 100))
                XCTAssertEqual(auto.diameterCm, expected, accuracy: 1e-8)
            }
            XCTAssertNil(DBHEstimator.bracketChordFit(frame: frame(depth: 0), guideAxis: axis,
                leftFraction: bounds.leftFraction, rightFraction: bounds.rightFraction))
        }
    }
    func testNoMaskNoGuideAndInvalidBoundsNeverProvideAutoBracket() {
        var preview = LiveStemMaskPreview()
        for observation in [LiveStemObservation(extent: guide, mask: nil, guideExtent: guide),
                            .init(extent: guide, mask: mask),
                            .init(extent: guide, mask: mask, guideExtent:
                                .init(leftFraction: .nan, rightFraction: 0.7, score: 0.8, maskPixels: 1))] {
            preview.reset(); preview.update(observation, at: 10)
            XCTAssertNil(preview.bracketForDepth(at: 10))
        }
        preview.reset(); preview.update(.init(extent: nil, mask: mask, guideExtent: guide), at: 10, receivedAt: 11.4)
        XCTAssertNil(preview.bracketForDepth(at: 11.51)) // source age, not arrival age
        XCTAssertNil(preview.bracketForDepth(at: 11.3)) // backwards time
    }
    func testCentreGuideDoesNotRequireFiveRowAgreement() throws {
        func contains(_ x: Double, _ y: Double) -> Bool {
            x > (y == 0.5 ? 0.35 : 0.42) && x < 0.65
        }
        XCTAssertNil(LiveStemMask.centreExtent(score: 0.8, contains: contains))
        let raw = try XCTUnwrap(LiveStemMask.guideExtent(score: 0.8, contains: contains))
        var preview = LiveStemMaskPreview()
        preview.update(.init(extent: nil, mask: mask, guideExtent: raw), at: 10)
        XCTAssertEqual(preview.bracketForDepth(at: 10), raw)
    }
}
