import XCTest
import simd
import Models
@testable import Sensors

/// Synthetic application fixtures only; no field measurements or fitted values.
final class FullSpanGeometryTests: XCTestCase {
    func testSurfaceCorrectedFullSpanRecoversAnalyticDiameter() throws {
        // Radius .2 m, nearest surface 1.5 m, focal 120 px. The median
        // surface depth is z_near + (1 - sqrt(3)/2) * radius.
        let result = BoundaryAlignment.fullSpanDiameterCm(
            spanPx: 28.432746132436435,
            medianDepthM: 1.5 + (1 - sqrt(0.75)) * 0.2, focalPx: 120)
        XCTAssertEqual(try XCTUnwrap(result), 40, accuracy: 1e-10)
    }

    func testContinuousBoundsSelectPixelCentresAndEvenMedian() throws {
        var selected: [Int] = []
        let result = try XCTUnwrap(BoundaryAlignment.fullSpanSample(
            width: 20, left: 4.2, right: 14.8, focal: 100,
            depthAt: { selected.append($0); return Double($0) / 10 }))
        XCTAssertEqual(selected, Array(5...14))
        XCTAssertEqual(result.1, 0.95, accuracy: 1e-12)
        XCTAssertEqual(result.0, try XCTUnwrap(BoundaryAlignment.fullSpanDiameterCm(
            spanPx: 10.6, medianDepthM: 0.95, focalPx: 100)), accuracy: 1e-12)
    }

    func testInvalidAndUndersampledGeometryFailsClosed() {
        for bad in [Double.nan, .infinity, -.infinity, 0, -1] {
            XCTAssertNil(BoundaryAlignment.fullSpanDiameterCm(spanPx: bad, medianDepthM: 1, focalPx: 100))
            XCTAssertNil(BoundaryAlignment.fullSpanDiameterCm(spanPx: 20, medianDepthM: bad, focalPx: 100))
            XCTAssertNil(BoundaryAlignment.fullSpanDiameterCm(spanPx: 20, medianDepthM: 1, focalPx: bad))
        }
        XCTAssertNil(BoundaryAlignment.fullSpanSample(width: 20, left: .nan, right: 10, focal: 100) { _ in 1 })
        XCTAssertNil(BoundaryAlignment.fullSpanSample(width: 20, left: 4.2, right: 6.8, focal: 100) { _ in 1 })
        XCTAssertNil(BoundaryAlignment.fullSpanSample(width: 20, left: 4, right: 14, focal: 100) { _ in .nan })
    }

    func testAIAdjustsSupportedSideOnlyAndUsesSameDiameter() throws {
        let grid = BoundaryAlignment.Grid(width:100,height:100,row:50,
            depth:.init(repeating:1,count:10000),left:30,right:70,focal:100)
        let result = try XCTUnwrap(BoundaryAlignment.correct(grid,rgbPixelsPerDepthPixel:4,
            mask:{ _,_ in true },rowSegments:{ _ in [(28,80)] }))
        XCTAssertEqual(result.left,29)
        XCTAssertEqual(result.right,70)
        XCTAssertEqual(result.leftReason,"accepted")
        XCTAssertEqual(result.rightReason,"large_shift")
        XCTAssertEqual(result.diameterCm,try XCTUnwrap(BoundaryAlignment.diameter(
            grid,left:29,right:70)).0,accuracy:1e-12)
    }

    func testUnsupportedAIAndForegroundOcclusionRetainOperatorGuides() throws {
        var depth = [Double](repeating:1,count:10000)
        let grid = BoundaryAlignment.Grid(width:100,height:100,row:50,
            depth:depth,left:30,right:70,focal:100)
        let unsupported = try XCTUnwrap(BoundaryAlignment.correct(grid,rgbPixelsPerDepthPixel:4,
            mask:{ _,_ in false },rowSegments:{ _ in [(28,72)] }))
        XCTAssertEqual(unsupported.left,30); XCTAssertEqual(unsupported.right,70)
        XCTAssertEqual(unsupported.leftReason,"mask_core_gap")
        for y in 48...52 { for x in 40...60 { depth[y*100+x] = 0.5 } }
        let occluded = BoundaryAlignment.Grid(width:100,height:100,row:50,
            depth:depth,left:30,right:70,focal:100)
        let retained = try XCTUnwrap(BoundaryAlignment.correct(occluded,rgbPixelsPerDepthPixel:4,
            mask:{ _,_ in true },rowSegments:{ _ in [(28,72)] }))
        XCTAssertEqual(retained.leftReason,"depth_occlusion")
        XCTAssertEqual(retained.left,30); XCTAssertEqual(retained.right,70)
    }

    private func frame(column: Bool) -> ARDepthFrame {
        let width = 120, height = 90
        let depth = (0..<height).flatMap { y in (0..<width).map { x -> Float in
            let idx = column ? y : x
            let lo = column ? 30 : 40, hi = column ? 60 : 80, mid = column ? 45 : 60
            return (lo...hi).contains(idx) ? (abs(idx-mid) <= 2 ? 1.02 : 1) : 3
        } }
        let k = simd_float3x3(columns: (SIMD3(100,0,0), SIMD3(0,150,0), SIMD3(60,45,1)))
        return ARDepthFrame(width: width, height: height, depth: depth,
            confidence: .init(repeating: 2, count: depth.count), intrinsics: k,
            cameraPoseWorld: matrix_identity_float4x4, timestamp: 0)
    }

    func testAutoAndManualUseSameSpanDepthAndAxisFocal() throws {
        for column in [false, true] {
            let f = frame(column: column)
            let axis: GuideAxis = column ? .col(x: 60) : .row(y: 45)
            let auto = try XCTUnwrap(DBHEstimator.chordPreviewFit(frame: f,
                tapPixel: SIMD2(60,45), guideAxis: axis))
            let manual = try XCTUnwrap(DBHEstimator.bracketChordFit(frame: f, guideAxis: axis,
                leftFraction: auto.stripLeftFraction, rightFraction: auto.stripRightFraction))
            XCTAssertEqual(auto.diameterCm, manual.diameterCm, accuracy: 1e-10)
            XCTAssertEqual(auto.effectiveTapDepth, 1)
            let span = column ? 30.0 : 40.0, focal = column ? 150.0 : 100.0
            XCTAssertEqual(auto.diameterCm, try XCTUnwrap(BoundaryAlignment.fullSpanDiameterCm(
                spanPx: span, medianDepthM: 1, focalPx: focal)), accuracy: 1e-10)
            let result = try XCTUnwrap(DBHEstimator.chordEstimate(input: DBHScanInput(
                frames: [f], tapPixel: SIMD2(60,45), guideAxis: axis,
                projectCalibration: .identity, rawPointsWriter: nil)))
            XCTAssertEqual(Double(result.diameterCm), auto.diameterCm, accuracy: 1e-5)
        }
    }

    func testAutoDoesNotSubstituteNeighbouringRowWhenMeasurementRowIsMissing() {
        let f = frame(column: false)
        var depth = f.depth
        for x in 0..<f.width { depth[45*f.width+x] = 0 }
        let missing = ARDepthFrame(width:f.width,height:f.height,depth:depth,
            confidence:f.confidence,intrinsics:f.intrinsics,cameraPoseWorld:f.cameraPoseWorld,timestamp:0)
        XCTAssertNil(DBHEstimator.chordPreviewFit(frame:missing,tapPixel:SIMD2(60,45),guideAxis:.row(y:45)))
    }
}
