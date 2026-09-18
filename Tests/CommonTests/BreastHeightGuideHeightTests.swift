import XCTest
@testable import Common

final class BreastHeightGuideHeightTests: XCTestCase {
    func testDefaultsAndSavedChoice() {
        XCTAssertEqual(BreastHeightGuideHeight.fromRaw(nil), .meters130)
        XCTAssertEqual(BreastHeightGuideHeight.fromRaw("invalid"), .meters130)
        for height in BreastHeightGuideHeight.allCases {
            XCTAssertEqual(BreastHeightGuideHeight.fromRaw(height.rawValue), height)
        }
    }

    func testHeightIsVerticalAboveGroundAtDifferentElevations() {
        for ground in [SIMD3<Float>(2, -4, 6), SIMD3<Float>(-3, 8, -2)] {
            for height in BreastHeightGuideHeight.allCases {
                let top = height.point(above: ground)
                XCTAssertEqual(top.x, ground.x)
                XCTAssertEqual(top.z, ground.z)
                XCTAssertEqual(top.y - ground.y, Float(height.meters), accuracy: 0.000001)
            }
        }
        XCTAssertEqual(BreastHeightGuideHeight.meters130.meters, 1.30)
        XCTAssertEqual(BreastHeightGuideHeight.meters137.meters, 1.37)
    }

    func testLabelsIdentifyTheChosenHeightInEitherUnit() {
        XCTAssertEqual(BreastHeightGuideHeight.meters130.metricLabel, "1.30 m")
        XCTAssertEqual(BreastHeightGuideHeight.meters130.imperialLabel, "4.27 ft")
        XCTAssertEqual(BreastHeightGuideHeight.meters137.metricLabel, "1.37 m")
        XCTAssertEqual(BreastHeightGuideHeight.meters137.imperialLabel, "4.5 ft")
    }
}
