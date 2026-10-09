// THE MASK'S ARITHMETIC, WITHOUT A CAMERA.
//
// `TreeSegDecode` is deliberately the half of the segmenter that has no ONNX
// session and no ARKit in it, so the step that decides WHERE THE STEM'S EDGES
// ARE can be checked on a desk. It shipped without a single test, and the
// coordinate bug these tests are mostly about — measuring the stem's LENGTH
// and handing it to the estimator as a width — reached a build because of it.
//
// The affine used below is not invented. It is `view_to_depth` copied out of a
// real Android raw-capture manifest from the validation corpus
// (val/raw/android/06d9cdf6…/manifest.json), which is a portrait phone with a
// 160x90 depth grid, and it is ANTI-DIAGONAL: the screen's x maps to the depth
// grid's Y and the screen's y to its X. That rotation IS the bug's habitat, so
// the regression test wants the real thing rather than an identity matrix that
// could never have caught it.

import XCTest
@testable import Sensors

final class TreeSegmenterTests: XCTestCase {

    // MARK: - The field capture's own geometry

    /// 160x90 depth grid, portrait viewport ~1198x2340, taken from the
    /// manifest named above.
    private let depthW = 160
    private let depthH = 90
    private let viewSize = CGSize(width: 1198, height: 2340)

    private var fieldMapping: DepthViewMapping {
        // depthX = 0.06838·vy          (screen y  -> depth X)
        // depthY = 81.923 − 0.06838·vx (screen x  -> depth Y, reversed)
        DepthViewMapping(a: 0, b: 0.0683760717511177, tx: 0,
                         c: -0.06837606430053711, d: 0, ty: 81.9230728149414)
    }

    /// A mask holding a single filled band, expressed in NORMALISED IMAGE
    /// coordinates so the test can say "the trunk lies here" without thinking
    /// about proto cells. `along` is the axis the band runs ALONG.
    private func bandMask(alongImageX: Bool,
                          from: Double,
                          to: Double,
                          size: Int = 160) -> TreeSegDecode.StemMask {
        var cells = [UInt8](repeating: 0, count: size * size)
        for y in 0..<size {
            for x in 0..<size {
                // A band running along image-x is bounded in image-y, and
                // vice versa — the band's THICKNESS is across the other axis.
                let across = alongImageX ? Double(y) / Double(size)
                                         : Double(x) / Double(size)
                if across >= from, across <= to { cells[y * size + x] = 1 }
            }
        }
        // Square source, so the letterbox is a plain scale with no padding —
        // the padding itself is covered separately below.
        let box = TreeSegDecode.Letterbox(sourceWidth: 640, sourceHeight: 640, size: 640)
        return TreeSegDecode.StemMask(cells: cells, height: size, width: size,
                                      letterbox: box, score: 0.9)
    }

    // MARK: - The regression the whole file exists for

    /// A VERTICAL TRUNK IN A PORTRAIT PHONE IS A HORIZONTAL BAND IN THE
    /// SENSOR IMAGE, and its WIDTH is that band's thickness — never its run.
    ///
    /// This is the shape of every real capture in the corpus: the stored frame
    /// is landscape while the scene is rotated 90°, so the trunk lies across
    /// the image. Measuring the mask's own middle ROW reports how far the
    /// trunk extends up and down the tree; the bracket then reads that as a
    /// chord and the identity turns it into a diameter several times too big.
    ///
    /// The band here is 20 % of the image thick. `extentAcrossView` must
    /// report ≈0.20, not ≈1.0.
    func testExtentMeasuresAcrossTheTrunkNotAlongIt() throws {
        // Trunk running along image-x, occupying image-y 0.40…0.60.
        let mask = bandMask(alongImageX: true, from: 0.40, to: 0.60)
        let extent = try XCTUnwrap(
            TreeSegDecode.extentAcrossView(mask: mask,
                                           viewSize: viewSize,
                                           mapping: fieldMapping,
                                           depthWidth: depthW,
                                           depthHeight: depthH))
        // The screen-x sweep crosses the band's THICKNESS through this
        // affine, and the affine reaches 0.91 of the depth height over the
        // viewport's width, so a 0.20 band lands near 0.20/0.91 ≈ 0.22.
        XCTAssertEqual(extent.widthFraction, 0.22, accuracy: 0.03,
                       "the extent must be the trunk's width, not its length")
        // And emphatically NOT the near-saturated span the old image-space
        // walk produced on exactly this geometry.
        XCTAssertLessThan(extent.widthFraction, 0.5)
    }

    /// The same mask turned 90° — a band running along image-Y — is what a
    /// trunk would look like if the frame were NOT rotated. Through the same
    /// affine the screen sweep now runs ALONG it, so the walk sees stem for
    /// nearly the whole sweep and the saturation guard must refuse it rather
    /// than report a diameter the width of the viewport.
    func testASpanThatSwallowsTheFrameIsRefused() {
        let mask = bandMask(alongImageX: false, from: 0.02, to: 0.98)
        XCTAssertNil(TreeSegDecode.extentAcrossView(mask: mask,
                                                    viewSize: viewSize,
                                                    mapping: fieldMapping,
                                                    depthWidth: depthW,
                                                    depthHeight: depthH),
                     "a mask covering the frame is not a stem")
    }

    /// An empty mask is an answer, not a crash — the caller falls back to the
    /// depth walk.
    func testNoStemReturnsNil() {
        let box = TreeSegDecode.Letterbox(sourceWidth: 640, sourceHeight: 640, size: 640)
        let empty = TreeSegDecode.StemMask(cells: [UInt8](repeating: 0, count: 160 * 160),
                                           height: 160, width: 160,
                                           letterbox: box, score: 0.5)
        XCTAssertNil(TreeSegDecode.extentAcrossView(mask: empty,
                                                    viewSize: viewSize,
                                                    mapping: fieldMapping,
                                                    depthWidth: depthW,
                                                    depthHeight: depthH))
    }

    /// The band median is there because a mask has holes. Punch one straight
    /// through the middle of the trunk on the centre row: a single-line walk
    /// would report the hole, the five-row median must not.
    func testAHoleOnTheCentreRowDoesNotNarrowTheAnswer() throws {
        var mask = bandMask(alongImageX: true, from: 0.40, to: 0.60)
        var cells = mask.cells
        let size = mask.width
        // The centre row of the VIEW maps to a fixed image column; clear a
        // patch of the band around the middle column to make the hole.
        for y in 0..<size {
            for x in (size / 2 - 2)...(size / 2 + 2) {
                cells[y * size + x] = 0
            }
        }
        mask = TreeSegDecode.StemMask(cells: cells, height: mask.height,
                                      width: mask.width, letterbox: mask.letterbox,
                                      score: mask.score)
        let extent = try XCTUnwrap(
            TreeSegDecode.extentAcrossView(mask: mask,
                                           viewSize: viewSize,
                                           mapping: fieldMapping,
                                           depthWidth: depthW,
                                           depthHeight: depthH))
        // Still the band's thickness — the neighbouring rows outvote the hole.
        XCTAssertEqual(extent.widthFraction, 0.22, accuracy: 0.04)
    }

    /// A degenerate viewport cannot produce a bracket. The scan screen reports
    /// its size asynchronously, so this really does happen on the first ticks.
    func testDegenerateViewportRefuses() {
        let mask = bandMask(alongImageX: true, from: 0.4, to: 0.6)
        XCTAssertNil(TreeSegDecode.extentAcrossView(mask: mask,
                                                    viewSize: .zero,
                                                    mapping: fieldMapping,
                                                    depthWidth: depthW,
                                                    depthHeight: depthH))
        XCTAssertNil(TreeSegDecode.extentAcrossView(mask: mask,
                                                    viewSize: viewSize,
                                                    mapping: fieldMapping,
                                                    depthWidth: 0,
                                                    depthHeight: depthH))
    }

    // MARK: - Letterbox

    /// 4:3 into a square: the scale is the smaller ratio and the padding is
    /// all on the short axis. These are the numbers `StemMask.filled` undoes,
    /// so they have to be right or every mask lookup lands off the stem.
    func testLetterboxGeometry() {
        let box = TreeSegDecode.Letterbox(sourceWidth: 640, sourceHeight: 480, size: 640)
        XCTAssertEqual(box.scale, 1.0, accuracy: 1e-9)
        XCTAssertEqual(box.padX, 0, accuracy: 1e-9)
        XCTAssertEqual(box.padY, 80, accuracy: 1e-9)   // (640 − 480)/2
    }

    /// A point at the centre of the source lands at the centre of the model
    /// square, padding included.
    func testLetterboxPutsTheCentreAtTheCentre() {
        let box = TreeSegDecode.Letterbox(sourceWidth: 1920, sourceHeight: 1440, size: 640)
        let mx = box.padX + 0.5 * Double(box.sourceWidth) * box.scale
        let my = box.padY + 0.5 * Double(box.sourceHeight) * box.scale
        XCTAssertEqual(mx, 320, accuracy: 1e-6)
        XCTAssertEqual(my, 320, accuracy: 1e-6)
    }

    // MARK: - Which instance is the stem

    /// BETWEEN INSTANCES OF COMPARABLE SIZE, the one the cruiser is aiming at
    /// wins. `pickTarget` scores `area × (1 − 0.5·dist/halfDiag)`, so the
    /// centre is worth at most a halving — enough to break a tie between two
    /// stems of similar size, which is the case it exists for.
    func testCentreWeightingBreaksATieTowardTheAimedStem() throws {
        let coeffs = [Float](repeating: 0, count: 32)
        // Same area, one under the crosshair and one against the frame edge.
        let centred = TreeSegDecode.Detection(
            cls: 1, score: 0.6, x1: 280, y1: 200, x2: 360, y2: 440, coeffs: coeffs)
        let atTheEdge = TreeSegDecode.Detection(
            cls: 1, score: 0.9, x1: 0, y1: 200, x2: 80, y2: 440, coeffs: coeffs)
        let pick = try XCTUnwrap(
            TreeSegDecode.pickTarget([atTheEdge, centred], size: 640))
        XCTAssertEqual(pick.x1, centred.x1)
        XCTAssertEqual(pick.x2, centred.x2)
    }

    /// AND THE DISCOUNT IS ONLY A HALVING, which is worth writing down because
    /// it is a real limit of the rule rather than a bug in it: a stem more
    /// than about twice the area still wins from the edge of the frame. A
    /// cruiser standing close to a big trunk with the crosshair on a sapling
    /// gets the big trunk. Nothing depends on this today — the setting is
    /// developer-only and unvalidated — but the next person to tune the
    /// weighting should know the test noticed.
    func testAMuchLargerInstanceStillWinsFromTheEdge() throws {
        let coeffs = [Float](repeating: 0, count: 32)
        let centredSmall = TreeSegDecode.Detection(
            cls: 1, score: 0.6, x1: 280, y1: 200, x2: 360, y2: 440, coeffs: coeffs)
        let hugeAtTheEdge = TreeSegDecode.Detection(
            cls: 1, score: 0.9, x1: 0, y1: 0, x2: 140, y2: 620, coeffs: coeffs)
        let pick = try XCTUnwrap(
            TreeSegDecode.pickTarget([hugeAtTheEdge, centredSmall], size: 640))
        XCTAssertEqual(pick.x1, hugeAtTheEdge.x1,
                       "area dominates beyond roughly 2x — documented, not desired")
    }

    func testPickTargetOnNothingIsNil() {
        XCTAssertNil(TreeSegDecode.pickTarget([], size: 640))
    }

    // MARK: - NMS

    /// Two boxes on the same trunk collapse to the higher-scoring one; a box
    /// on a different trunk survives.
    func testNMSCollapsesDuplicatesAndKeepsDistinctStems() {
        let coeffs = [Float](repeating: 0, count: 32)
        let a = TreeSegDecode.Detection(cls: 1, score: 0.9, x1: 100, y1: 100,
                                        x2: 200, y2: 400, coeffs: coeffs)
        let nearlyA = TreeSegDecode.Detection(cls: 1, score: 0.5, x1: 105, y1: 105,
                                              x2: 205, y2: 405, coeffs: coeffs)
        let elsewhere = TreeSegDecode.Detection(cls: 1, score: 0.7, x1: 400, y1: 100,
                                                x2: 500, y2: 400, coeffs: coeffs)
        let kept = TreeSegDecode.nms([a, nearlyA, elsewhere], iouGate: 0.45)
        XCTAssertEqual(kept.count, 2)
        XCTAssertEqual(kept.first?.score, 0.9)
    }

    func testIoUOfDisjointBoxesIsZero() {
        let coeffs = [Float](repeating: 0, count: 32)
        let a = TreeSegDecode.Detection(cls: 1, score: 1, x1: 0, y1: 0,
                                        x2: 10, y2: 10, coeffs: coeffs)
        let b = TreeSegDecode.Detection(cls: 1, score: 1, x1: 50, y1: 50,
                                        x2: 60, y2: 60, coeffs: coeffs)
        XCTAssertEqual(TreeSegDecode.iou(a, b), 0, accuracy: 1e-6)
    }
}
