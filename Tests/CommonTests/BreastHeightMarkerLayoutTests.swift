import XCTest
@testable import Common

final class BreastHeightMarkerLayoutTests: XCTestCase {
    func testTicksAndLabelLeaveBracketAndCenterClear() throws {
        let layout = try XCTUnwrap(BreastHeightMarkerLayout(x: 200, y: 400,
            width: 400, height: 800, stemLeft: 140, stemRight: 260))
        XCTAssertEqual(layout.y, 400)
        XCTAssertLessThan(try XCTUnwrap(layout.leftTick).upperBound, 140)
        XCTAssertGreaterThan(try XCTUnwrap(layout.rightTick).lowerBound, 260)
        XCTAssertGreaterThan(try XCTUnwrap(layout.labelCenterX) - 32, 260)
    }

    func testLabelMovesLeftAtRightEdgeWithoutMovingHeight() throws {
        let layout = try XCTUnwrap(BreastHeightMarkerLayout(x: 360, y: 350, width: 400, height: 800))
        XCTAssertNil(layout.rightTick)
        XCTAssertLessThan(try XCTUnwrap(layout.labelCenterX) + 32, 360)
        XCTAssertEqual(layout.y, 350)
    }

    func testOffScreenAndInvalidPointsAreHidden() {
        for (x, y) in [(-1.0, 300.0), (401, 300), (200, -1), (200, 801), (.nan, 300), (200, .infinity)] {
            XCTAssertNil(BreastHeightMarkerLayout(x: x, y: y, width: 400, height: 800))
        }
    }

    func testNoSpaceHidesTheLabelInsteadOfCoveringTheStem() throws {
        let layout = try XCTUnwrap(BreastHeightMarkerLayout(x: 200, y: 400,
            width: 400, height: 800, stemLeft: 60, stemRight: 340))
        XCTAssertNotNil(layout.leftTick)
        XCTAssertNotNil(layout.rightTick)
        XCTAssertNil(layout.labelCenterX)
        XCTAssertNil(BreastHeightMarkerLayout(x: 200, y: 400,
            width: 400, height: 800, stemLeft: 10, stemRight: 390))
    }
}
