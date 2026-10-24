import XCTest
@testable import Sensors

/// Synthetic app masks, compared with a frozen decoder rather than the new helper.
final class OptimizedMaskSelectionTests: XCTestCase {
    private func fixture(zero: Bool = false) -> ([Float], [Float]) {
        var head = [Float](repeating: 0, count: 37*8400)
        for i in 0..<9 {
            head[i] = Float(320+(i%3-1)*10); head[8400+i] = 320
            head[2*8400+i] = Float(20+i*40); head[3*8400+i] = Float(200+i*50)
            head[4*8400+i] = 0.8-Float(i)*0.01
            for c in 0..<32 { head[(5+c)*8400+i] = c == 0 ? 1 : Float((c+i)%7-3)*0.03 }
        }
        let proto: [Float] = (0..<(32*25600)).map { i in
            zero ? 0 : (i < 25600 ? 1 : Float((i*73+19)%251-125)/256)
        }
        return (head, proto)
    }
    private func compare(_ head: [Float], _ proto: [Float], _ w: Int, _ h: Int) {
        let old = FrozenYoloAlignmentMask.select(head: head, prototypes: proto, width: w, height: h)
        let now = YoloAlignmentMask.select(head: head, prototypes: proto, width: w, height: h)
        XCTAssertEqual(old == nil, now == nil)
        if let old, let now {
            XCTAssertEqual(old.score, now.score)
            XCTAssertEqual(old.logits.map(\.bitPattern), now.logits.map(\.bitPattern))
            for y in stride(from: 0, to: h, by: max(1,h/96)) {
                for x in stride(from: 0, to: w, by: max(1,w/96)) {
                    XCTAssertEqual(old.contains(x: x, y: y), now.contains(x: x, y: y))
                }
            }
        }
    }
    func testSelectedMaskAndEveryLogitRemainBitExactAcrossOrientations() {
        let (head,proto) = fixture()
        for (w,h) in [(96,160),(160,96),(127,191),(640,640),(1,1)] { compare(head,proto,w,h) }
    }
    func testZeroLogitsAndMissingTargetsRemainMissing() {
        let (head,proto) = fixture(zero: true); compare(head,proto,96,160)
        compare(.init(repeating: 0, count: head.count),proto,96,160)
    }
    func testInvalidAndClippedCandidateHandlingIsUnchanged() {
        var (head,proto) = fixture()
        head[0] = .nan; head[1] = .infinity; head[2*8400+2] = -1
        head[2*8400+3] = 1500; head[3*8400+3] = 1500
        compare(head,proto,96,160)
    }
}
