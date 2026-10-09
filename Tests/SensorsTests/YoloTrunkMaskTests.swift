import XCTest
@testable import Sensors

final class YoloTrunkMaskTests: XCTestCase {
    private func head(_ boxes: [(Int,Float,Float,Float,Float,Float)]) -> [Float] {
        let n = 8400
        var d = [Float](repeating:0,count:37*n)
        for (i,score,x1,y1,x2,y2) in boxes {
            d[i] = (x1+x2)/2;d[n+i] = (y1+y2)/2
            d[2*n+i] = x2-x1;d[3*n+i] = y2-y1;d[4*n+i] = score;d[5*n+i] = 1
        }
        return d
    }
    private func prototype(_ first: (Int)->Float = { _ in 1 }) -> [Float] {
        var p = [Float](repeating:0,count:32*25600)
        for i in 0..<25600 { p[i] = first(i) }
        return p
    }
    func testOneClassHeadAndPortraitLetterbox() throws {
        let mask = try XCTUnwrap(YoloAlignmentMask.select(head:head([(0,0.8,200,80,440,560)]),prototypes:prototype(),width:200,height:400))
        XCTAssertEqual(mask.score,0.8)
        XCTAssertTrue(mask.contains(x:25,y:50));XCTAssertTrue(mask.contains(x:174,y:349))
        XCTAssertFalse(mask.contains(x:24,y:50));XCTAssertFalse(mask.contains(x:175,y:349))
    }
    func testTargetIsCentralStemRatherThanHighestConfidenceBackground() throws {
        let mask = try XCTUnwrap(YoloAlignmentMask.select(head:head([(0,0.95,0,0,150,640),(1,0.7,240,0,400,640)]),prototypes:prototype(),width:640,height:640))
        XCTAssertEqual(mask.score,0.7)
        XCTAssertTrue(mask.contains(x:320,y:320));XCTAssertFalse(mask.contains(x:100,y:320))
    }
    func testResizedLogitsAndSafeNoTargetFallback() throws {
        let mask = try XCTUnwrap(YoloAlignmentMask.select(head:head([(0,0.8,0,0,640,640)]),prototypes:prototype { $0%160<80 ? -1:1 },width:640,height:640))
        XCTAssertFalse(mask.contains(x:319,y:320));XCTAssertTrue(mask.contains(x:320,y:320))
        XCTAssertNil(YoloAlignmentMask.select(head:head([(0,0.9,0,0,100,640)]),prototypes:prototype(),width:640,height:640))
        XCTAssertNil(YoloAlignmentMask.select(head:[],prototypes:[],width:640,height:640))
    }
    func testCentralCoverageFallbackSurvivesFastBoxPrefilter() throws {
        let mask = try XCTUnwrap(YoloAlignmentMask.select(head:head([(0,0.8,288,0,304,640)]),
            prototypes:prototype(),width:640,height:640))
        XCTAssertTrue(mask.contains(x:300,y:320))
        XCTAssertFalse(mask.contains(x:320,y:320))
    }
}
