import XCTest
import Models
@testable import Sensors

/// Synthetic surfaces only. Checks the selected depth region and its geometry.
final class OptimalSamplingTests: XCTestCase {
    func testSelectedSurfaceCorrectionRecoversAnalyticDiameter() throws {
        let radius = 0.2, front = 1.5
        let theta = asin(radius / (front + radius))
        let span = 2 * 120 * tan(theta)
        let offset = 1 - sqrt(1 - 0.4 * 0.4)
        XCTAssertEqual(BoundaryAlignment.samplingFraction, 0.8)
        XCTAssertEqual(BoundaryAlignment.sampledDepthOffsetFactor, offset, accuracy: 1e-14)
        XCTAssertEqual(try XCTUnwrap(BoundaryAlignment.sampledDiameterCm(
            spanPx: span, medianDepthM: front + offset * radius, focalPx: 120)), 40, accuracy: 1e-10)
    }

    func testFractionalGuidesKeepFullWidthButTrimDepthPixels() throws {
        var read: [Int] = []
        let result = try XCTUnwrap(BoundaryAlignment.sampledDepth(
            width: 20, left: 4.2, right: 14.8, focal: 100,
            depthAt: { read.append($0); return Double($0) / 10 }))
        XCTAssertEqual(read, Array(6...13))
        XCTAssertEqual(result.1, 0.95, accuracy: 1e-12)
        XCTAssertEqual(result.0, try XCTUnwrap(BoundaryAlignment.sampledDiameterCm(
            spanPx: 10.6, medianDepthM: 0.95, focalPx: 100)), accuracy: 1e-12)
    }

    func testPixelCentresExactlyOnSelectedBoundsAreRetained() throws {
        var read: [Int] = []
        _ = try XCTUnwrap(BoundaryAlignment.sampledDepth(width: 30, left: 0, right: 20,
            focal: 100, depthAt: { read.append($0); return 1 }))
        XCTAssertEqual(read, Array(2...18))
    }

    func testPeripheralDepthsCannotChangeSelectedMedian() throws {
        var read: [Int] = []
        let result = try XCTUnwrap(BoundaryAlignment.sampledDepth(width: 120, left: 10,
            right: 110, focal: 100, depthAt: { x in
                read.append(x); return (20...100).contains(x) ? 1 : 7
            }))
        XCTAssertEqual(read, Array(20...100))
        XCTAssertEqual(result.1, 1)
    }

    func testInvalidOrUndersampledSelectedRegionIsRejected() {
        XCTAssertNil(BoundaryAlignment.sampledDepth(width: 20, left: 4.2, right: 6.8,
            focal: 100, depthAt: { _ in 1 }))
        XCTAssertNil(BoundaryAlignment.sampledDepth(width: 20, left: 4, right: 14,
            focal: 100, depthAt: { _ in .nan }))
        XCTAssertNil(BoundaryAlignment.sampledDiameterCm(spanPx: 20, medianDepthM: .nan, focalPx: 100))
    }

    func testPreviousCalibrationIsNotAppliedToChangedEstimator() {
        XCTAssertEqual(DBHCalibration.state(alpha: 1, beta: 1, epoch: 8), .ignoredStale)
        XCTAssertEqual(DBHCalibration.state(alpha: 1, beta: 1, epoch: DBHCalibration.currentEpoch), .applied)
    }
}
