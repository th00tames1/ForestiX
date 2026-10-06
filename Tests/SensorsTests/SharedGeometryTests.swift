import XCTest
import Foundation
import Common
import Models
@testable import Sensors

final class SharedGeometryTests: XCTestCase {
    private func fixtures(_ key: String) throws -> [[String: Any]] {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
        let data = try Data(contentsOf: root.appendingPathComponent("validation/fixtures/geometry.json"))
        return try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Any])?[key] as? [[String: Any]])
    }

    func testAnalyticCylinderFixtures() throws {
        for f in try fixtures("diameter") {
            let result = DBHEstimator.silhouetteDiameterCm(
                spanPx: f["span_px"] as! Double, depthM: f["depth_m"] as! Double,
                focalPx: f["focal_px"] as! Double)
            XCTAssertEqual(try XCTUnwrap(result), f["expected_cm"] as! Double, accuracy: 1e-8)
        }
        XCTAssertNil(DBHEstimator.silhouetteDiameterCm(spanPx: 0, depthM: 1, focalPx: 100))
        XCTAssertNil(DBHEstimator.silhouetteDiameterCm(spanPx: .nan, depthM: 1, focalPx: 100))
    }

    func testHeightAndWarnCountFixtures() throws {
        for f in try fixtures("height") {
            let result = HeightEstimator.estimate(input: HeightMeasureInput(
                anchorPointWorld: .zero,
                standingPointWorld: SIMD3<Float>(Float(f["d_m"] as! Double), 4, 0),
                alphaTopRad: Float(f["top_rad"] as! Double),
                alphaBaseRad: Float(f["base_rad"] as! Double),
                trackingStateWasNormalThroughout: true, projectCalibration: .identity))
            XCTAssertEqual(Double(result.heightM), f["height_m"] as! Double, accuracy: 0.0001)
            XCTAssertEqual(Double(try XCTUnwrap(result.sigmaHm)), f["sigma_m"] as! Double, accuracy: 0.0001)
            XCTAssertEqual(result.confidence.rawValue, f["tier"] as! String, f["name"] as! String)
            XCTAssertTrue(HeightEstimator.canAccept(result), "A finite red-quality reading remains flaggable")
        }
    }

    func testStaleCalibrationDoesNotAlterRawDiameter() {
        let stale = ProjectCalibration(depthNoiseMm: 5, dbhCorrectionAlpha: 3,
            dbhCorrectionBeta: 2, dbhCalibrationEpoch: DBHEstimator.estimatorEpoch - 1)
        XCTAssertEqual(stale.appliedToRawCm(30), 30)
        let current = ProjectCalibration(depthNoiseMm: 5, dbhCorrectionAlpha: 3,
            dbhCorrectionBeta: 2, dbhCalibrationEpoch: DBHEstimator.estimatorEpoch)
        XCTAssertEqual(current.appliedToRawCm(30), 63)
    }

    func testAggregationUsesUpperMedianThenThreeNearestSamples() throws {
        func sample(_ d: Float, tier: ConfidenceTier = .green) -> DBHResult {
            DBHResult(diameterCm: d, centerXZ: .zero, arcCoverageDeg: 60,
                rmseMm: 1, sigmaRmm: 1, nInliers: 20, confidence: tier,
                method: .lidarChordSilhouette, rawPointsPath: nil, rejectionReason: nil)
        }
        let values: [Float] = [10, 20, 32, 70]
        let result = try XCTUnwrap(DBHEstimator.aggregateSamples(values.map { sample($0) }))
        XCTAssertEqual(result.diameterCm, 62.0/3.0, accuracy: 0.00001)
        XCTAssertNil(DBHEstimator.aggregateSamples([sample(10), sample(20), sample(32, tier: .red)]))
        let withRed = try XCTUnwrap(DBHEstimator.aggregateSamples(values.map { sample($0) } + [sample(500, tier: .red)]))
        XCTAssertEqual(result.diameterCm, withRed.diameterCm)
    }
}
