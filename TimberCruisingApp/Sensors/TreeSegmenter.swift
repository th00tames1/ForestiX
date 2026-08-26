// THE STEM'S EDGES, READ OUT OF A SEGMENTATION MASK.
//
// The Auto diameter path finds the trunk's left and right edge by walking the
// DEPTH map outward from the crosshair until depth becomes invalid or jumps a
// discontinuity. That works on a clean stem at working distance and fails the
// way depth fails: a branch crossing the row, a second trunk seen through a
// lean, wet bark that returns nothing. This reads the same two edges out of an
// RGB instance mask instead, where a stem is a stem whatever the depth camera
// made of it.
//
// IT PRODUCES EDGES, NOT A DIAMETER. The chord identity, the 21-row median,
// the tier, the sigma and every stored number downstream are untouched — this
// replaces one step, the step that answers "which pixels are trunk?", and
// hands back exactly what the depth walk hands back: a left and a right
// fraction across the frame. If it has no answer it says so and the depth walk
// runs, which is also what happens on every build that has no weights.
//
// THE MODEL is a YOLO11n-seg export, 640x640, batch 1, classes
// {0: soot, 1: tree} — the BSI bark-scorch study's weights, used here for the
// `tree` class alone. It is ~11 MB and gitignored; see Models/README.md. The
// decode below is a port of that study's own bench app so the two agree on
// what the network said: same letterbox with 114-grey padding, same 0.25
// confidence gate, same 0.45 IoU NMS, same centre-weighted target pick.
//
// WHAT IT HAS NOT BEEN SHOWN. The weights were fitted on burned Korean pine.
// A trunk silhouette is a far more transferable thing than bark scorch, but
// Douglas-fir in an Oregon winter is not the training set, and nothing here
// has been validated against tape. That is why it is off by default, why it
// is a separate method rather than a swap, and why every reading it produces
// is stamped as its own so a corpus can be split on it later.

import Foundation
import Common
import Models
import simd

#if canImport(OnnxRuntimeBindings)
import OnnxRuntimeBindings
#endif

/// Where a stem's two edges were found, as fractions across the CAMERA IMAGE
/// (0 = left edge of the frame, 1 = right edge) — the same normalisation
/// `PreviewFit.stripLeftFraction` / `stripRightFraction` already carry, so a
/// caller can hand this to the estimator without a second convention.
public struct StemExtent: Equatable, Sendable {
    public let leftFraction: Double
    public let rightFraction: Double
    /// The mask's confidence for the instance these edges came from.
    public let score: Float
    /// How many mask pixels the chosen instance covered. A stem that is one
    /// speck of mask is a detection, not a measurement.
    public let maskPixels: Int

    public init(leftFraction: Double, rightFraction: Double,
                score: Float, maskPixels: Int) {
        self.leftFraction = leftFraction
        self.rightFraction = rightFraction
        self.score = score
        self.maskPixels = maskPixels
    }

    public var widthFraction: Double { rightFraction - leftFraction }
}

/// Why a segmentation is not available, in words the screen can print.
public enum SegmenterAvailability: Equatable, Sendable {
    case ready
    /// The build has no weights — the ordinary state of a fresh clone.
    case noModel
    /// The runtime is missing (a macOS host build of this target).
    case unsupportedPlatform
    case failed(String)

    public var isReady: Bool { self == .ready }
}

public enum TreeSegmenterConfig {
    /// Class indices in the shipped weights.
    public static let sootClass = 0
    public static let treeClass = 1
    /// The bench app's gates, kept identical so a mask decoded here and a mask
    /// decoded there are the same mask.
    public static let confidence: Float = 0.25
    public static let iou: Float = 0.45
    /// Square input the export was frozen at.
    public static let inputSize = 640
    /// Below this the instance is a speck and its edges mean nothing. 0.4 % of
    /// the 160x160 proto grid — a stem at the far end of the usable band still
    /// covers several times this.
    public static let minMaskPixels = 100
}

// MARK: - The decoder, which needs no runtime

/// The parts of the pipeline that are pure arithmetic, split out so they can
/// be tested on any host — the runtime is iOS-only, this is not.
public enum TreeSegDecode {

    public struct Detection: Equatable {
        public var cls: Int
        public var score: Float
        public var x1, y1, x2, y2: Float
        public var coeffs: [Float]
    }

    /// Letterbox geometry: how the source image was placed inside the square
    /// the network sees, so mask coordinates can be brought back out again.
    public struct Letterbox: Equatable, Sendable {
        public let scale: Double
        public let padX: Double
        public let padY: Double
        public let sourceWidth: Int
        public let sourceHeight: Int

        public init(sourceWidth: Int, sourceHeight: Int, size: Int) {
            self.sourceWidth = sourceWidth
            self.sourceHeight = sourceHeight
            let r = min(Double(size) / Double(sourceWidth),
                        Double(size) / Double(sourceHeight))
            self.scale = r
            self.padX = (Double(size) - Double(sourceWidth) * r) / 2
            self.padY = (Double(size) - Double(sourceHeight) * r) / 2
        }

        /// An x in model space back to a fraction across the source image.
        public func sourceXFraction(modelX: Double) -> Double {
            guard scale > 0, sourceWidth > 0 else { return 0 }
            return ((modelX - padX) / scale) / Double(sourceWidth)
        }

        /// A fraction down the source image to a row in model space.
        public func modelY(sourceYFraction: Double) -> Double {
            padY + sourceYFraction * Double(sourceHeight) * scale
        }
    }

    /// Non-maximum suppression, highest score first.
    public static func nms(_ input: [Detection], iouGate: Float) -> [Detection] {
        var sorted = input.sorted { $0.score > $1.score }
        var keep: [Detection] = []
        while !sorted.isEmpty {
            let a = sorted.removeFirst()
            keep.append(a)
            sorted.removeAll { iou(a, $0) > iouGate }
        }
        return keep
    }

    public static func iou(_ a: Detection, _ b: Detection) -> Float {
        let ix1 = max(a.x1, b.x1), iy1 = max(a.y1, b.y1)
        let ix2 = min(a.x2, b.x2), iy2 = min(a.y2, b.y2)
        let iw = max(0, ix2 - ix1), ih = max(0, iy2 - iy1)
        let inter = iw * ih
        let ua = max(0, a.x2 - a.x1) * max(0, a.y2 - a.y1)
        let ub = max(0, b.x2 - b.x1) * max(0, b.y2 - b.y1)
        let u = ua + ub - inter
        return u <= 0 ? 0 : inter / u
    }

    /// WHICH STEM THE CRUISER MEANS. Biggest instance, discounted by how far
    /// its centre sits from the middle of the frame — the same rule the bench
    /// app uses. A cruiser aims at the tree they are measuring, so the one
    /// under the crosshair wins ties against a larger trunk at the edge.
    public static func pickTarget(_ trees: [Detection], size: Int) -> Detection? {
        guard !trees.isEmpty else { return nil }
        let c = Float(size) / 2
        let halfDiag = 0.5 * Float(2 * size * size).squareRoot()
        var best: Detection?
        var bestScore: Float = -1
        for d in trees {
            let area = max(0, d.x2 - d.x1) * max(0, d.y2 - d.y1)
            let mx = (d.x1 + d.x2) / 2, my = (d.y1 + d.y2) / 2
            let dist = ((mx - c) * (mx - c) + (my - c) * (my - c)).squareRoot()
            let score = area * (1 - 0.5 * (dist / halfDiag))
            if score > bestScore { bestScore = score; best = d }
        }
        return best
    }

    /// Raw network output to the `tree` detections it contains.
    ///
    /// Handles BOTH export layouts, because the study exported both and either
    /// may be dropped in: YOLO26's end-to-end `[1, nDet, 6+nm]` (already
    /// NMS'd) and YOLO11's dense `[1, 4+nc+nm, anchors]` (channel-major,
    /// needs NMS).
    public static func detections(det: [Float], detShape: [Int],
                                  maskCoeffCount nm: Int) -> [Detection] {
        guard detShape.count == 3, !det.isEmpty else { return [] }
        let dim1 = detShape[1], dim2 = detShape[2]

        if dim2 == nm + 6 {
            var raw: [Detection] = []
            for i in 0..<dim1 {
                let base = i * dim2
                let conf = det[base + 4]
                guard Int(det[base + 5].rounded()) == TreeSegmenterConfig.treeClass,
                      conf >= TreeSegmenterConfig.confidence else { continue }
                raw.append(Detection(cls: TreeSegmenterConfig.treeClass, score: conf,
                                     x1: det[base], y1: det[base + 1],
                                     x2: det[base + 2], y2: det[base + 3],
                                     coeffs: Array(det[(base + 6)..<(base + 6 + nm)])))
            }
            return raw
        }

        let nc = dim1 - 4 - nm
        guard nc >= 1, nc <= 100 else { return [] }
        let n = dim2
        var raw: [Detection] = []
        for i in 0..<n {
            var best = 0
            var bestScore: Float = -1
            for k in 0..<nc {
                let s = det[(4 + k) * n + i]
                if s > bestScore { bestScore = s; best = k }
            }
            guard best == TreeSegmenterConfig.treeClass,
                  bestScore >= TreeSegmenterConfig.confidence else { continue }
            let cx = det[i], cy = det[n + i], w = det[2 * n + i], h = det[3 * n + i]
            var coeffs = [Float](repeating: 0, count: nm)
            for j in 0..<nm { coeffs[j] = det[(4 + nc + j) * n + i] }
            raw.append(Detection(cls: best, score: bestScore,
                                 x1: cx - w / 2, y1: cy - h / 2,
                                 x2: cx + w / 2, y2: cy + h / 2,
                                 coeffs: coeffs))
        }
        return nms(raw, iouGate: TreeSegmenterConfig.iou)
    }

    /// The instance's mask on the prototype grid, cropped to its own box.
    public static func fillMask(_ d: Detection, proto: [Float],
                                nm: Int, mh: Int, mw: Int,
                                modelSize: Int) -> [UInt8] {
        var mask = [UInt8](repeating: 0, count: mh * mw)
        let factor = Float(mw) / Float(modelSize)
        let mhmw = mh * mw
        func clamp(_ v: Int, _ lo: Int, _ hi: Int) -> Int { min(max(v, lo), hi) }
        let bx1 = clamp(Int(d.x1 * factor), 0, mw - 1)
        let bx2 = clamp(Int(d.x2 * factor), 0, mw - 1)
        let by1 = clamp(Int(d.y1 * factor), 0, mh - 1)
        let by2 = clamp(Int(d.y2 * factor), 0, mh - 1)
        guard bx2 >= bx1, by2 >= by1 else { return mask }
        for y in by1...by2 {
            let rowBase = y * mw
            for x in bx1...bx2 {
                let pix = rowBase + x
                var s: Float = 0
                for j in 0..<nm { s += d.coeffs[j] * proto[j * mhmw + pix] }
                if 1 / (1 + exp(-s)) > 0.5 { mask[pix] = 1 }
            }
        }
        return mask
    }

    /// THE MASK AS THE NETWORK LEFT IT, on the prototype grid.
    ///
    /// Handed out rather than reduced to two numbers here, because the two
    /// numbers the estimator wants are in a coordinate system this file has
    /// no business guessing at — see `extentAcrossView`.
    public struct StemMask: Sendable {
        public let cells: [UInt8]
        public let height: Int
        public let width: Int
        public let letterbox: Letterbox
        public let score: Float

        public init(cells: [UInt8], height: Int, width: Int,
                    letterbox: Letterbox, score: Float) {
            self.cells = cells; self.height = height; self.width = width
            self.letterbox = letterbox; self.score = score
        }

        /// Is this proto cell inside the stem?
        func filled(imageU: Double, imageV: Double) -> Bool {
            // Normalised image → model space → proto cell. The letterbox is
            // what puts the image inside the square the network saw.
            let mx = letterbox.padX + imageU * Double(letterbox.sourceWidth) * letterbox.scale
            let my = letterbox.padY + imageV * Double(letterbox.sourceHeight) * letterbox.scale
            let cell = Double(TreeSegmenterConfig.inputSize)
            let px = Int(mx * Double(width) / cell)
            let py = Int(my * Double(height) / cell)
            guard px >= 0, px < width, py >= 0, py < height else { return false }
            return cells[py * width + px] == 1
        }
    }

    /// THE TWO EDGES, AS A FRACTION ACROSS THE SCREEN — which is the only
    /// thing the bracket means.
    ///
    /// THIS IS THE STEP THAT WAS WRONG. The obvious reading of the mask is to
    /// walk its rows and report where the stem starts and stops along the
    /// IMAGE's x. That number is worthless here. The camera image is in
    /// SENSOR orientation — landscape — while the app is held portrait, so
    /// the image's x runs DOWN the screen: the naive extent reports the
    /// stem's LENGTH and hands it to the estimator as a width, and a stem
    /// that fills the frame saturates the bracket to the full screen. The
    /// diameter identity is linear in that span, so a 30 cm stem comes out a
    /// metre and change — inside the plausibility gate, so nothing refuses it.
    /// The image is also aspect-fill CROPPED into the view, so even after a
    /// rotation the fractions differ by the crop factor.
    ///
    /// Both go away by never working in image space at all. This walks the
    /// VIEW's own horizontal centre line, sample by sample, and asks the mask
    /// whether each point is stem — going through `viewToDepth`, the mapping
    /// the rest of the app already measures brackets with, and then straight
    /// to normalised image coordinates, which the depth grid shares by
    /// construction (see `ARKitSessionManager.viewMapping`: "the depth map
    /// and the captured image share an orientation and an aspect"). Rotation
    /// and crop are the mapping's job, and it already does it correctly.
    ///
    /// Returns nil when the line crosses no stem, or crosses too little of
    /// one to be a measurement.
    public static func extentAcrossView(
        mask: StemMask,
        viewSize: CGSize,
        mapping: DepthViewMapping,
        depthWidth: Int,
        depthHeight: Int,
        rowFraction: Double = 0.5,
        samples: Int = 240
    ) -> StemExtent? {
        guard viewSize.width > 1, viewSize.height > 1,
              depthWidth > 0, depthHeight > 0, samples > 8 else { return nil }
        let y = rowFraction * Double(viewSize.height)
        var first = -1, last = -1, hits = 0
        for i in 0..<samples {
            let f = Double(i) / Double(samples - 1)
            let p = mapping.viewToDepth(x: f * Double(viewSize.width), y: y)
            let u = p.x / Double(depthWidth)
            let v = p.y / Double(depthHeight)
            guard u >= 0, u <= 1, v >= 0, v <= 1 else { continue }
            if mask.filled(imageU: u, imageV: v) {
                if first < 0 { first = i }
                last = i
                hits += 1
            }
        }
        guard first >= 0, last > first, hits >= 3 else { return nil }
        let lf = Double(first) / Double(samples - 1)
        let rf = Double(last) / Double(samples - 1)
        // A span that reaches both edges of the screen is not a stem, it is a
        // mask that has swallowed the frame — refuse rather than record a
        // diameter the size of the viewport.
        guard rf - lf < 0.95 else { return nil }
        return StemExtent(leftFraction: lf, rightFraction: rf,
                          score: mask.score, maskPixels: hits)
    }
}

// MARK: - The runtime half

#if canImport(OnnxRuntimeBindings) && canImport(CoreImage) && canImport(CoreVideo)
import CoreImage
import CoreVideo

/// Runs the segmentation model on camera frames and answers with a stem's two
/// edges. One instance per scan screen; `segment` is synchronous and must be
/// called OFF the main thread — see `TreeSegmentationFeed`.
public final class TreeSegmenter {

    public private(set) var availability: SegmenterAvailability = .noModel

    private var session: ORTSession?
    private var env: ORTEnv?
    private var inputName = "images"
    private var outputNames: Set<String> = []
    /// Reused across frames — a CIContext is expensive to build and cheap to
    /// keep, and this one is asked for a 640x640 render ten times a second.
    private let ciContext: CIContext
    /// Scratch for the letterboxed render, so a per-frame allocation of
    /// 640·640·4 bytes does not land in the middle of an AR session.
    private var rgba: [UInt8]

    public init(modelURL: URL? = TreeSegmenter.bundledModelURL()) {
        ciContext = CIContext(options: [.cacheIntermediates: false])
        let side = TreeSegmenterConfig.inputSize
        rgba = [UInt8](repeating: 114, count: side * side * 4)
        guard let modelURL, FileManager.default.fileExists(atPath: modelURL.path) else {
            availability = .noModel
            return
        }
        do {
            let e = try ORTEnv(loggingLevel: .warning)
            let opts = try ORTSessionOptions()
            // The same ceiling the study's bench app applies. An AR session is
            // already holding the depth stream, the camera and a RealityKit
            // scene; a segmenter that grabs every core to shave 4 ms is a
            // segmenter that makes the preview stutter.
            try? opts.setIntraOpNumThreads(2)
            try? opts.setGraphOptimizationLevel(.basic)
            try? opts.addConfigEntry(withKey: "session.disable_prepacking", value: "1")
            try? opts.addConfigEntry(
                withKey: "session.memory.enable_memory_arena_shrinkage", value: "cpu:0")
            let s = try ORTSession(env: e, modelPath: modelURL.path, sessionOptions: opts)
            env = e
            session = s
            inputName = (try? s.inputNames())?.first ?? "images"
            outputNames = Set((try? s.outputNames()) ?? [])
            availability = .ready
        } catch {
            availability = .failed(error.localizedDescription)
        }
    }

    /// The weights, when this build has them. Nil is the ordinary state of a
    /// fresh clone — see Models/README.md.
    public static func bundledModelURL() -> URL? {
        Bundle.module.url(forResource: "tree_seg_640", withExtension: "onnx",
                          subdirectory: "Models")
            ?? Bundle.module.url(forResource: "tree_seg_640", withExtension: "onnx")
    }

    /// Segment one camera frame and return the stem's edges at `rowFraction`
    /// down the image (0.5 = the guide row the estimator reads).
    ///
    /// SYNCHRONOUS AND SLOW — tens of milliseconds. Never on the main thread.
    public func segment(pixelBuffer: CVPixelBuffer) -> TreeSegDecode.StemMask? {
        guard let session, availability.isReady else { return nil }
        let side = TreeSegmenterConfig.inputSize
        let srcW = CVPixelBufferGetWidth(pixelBuffer)
        let srcH = CVPixelBufferGetHeight(pixelBuffer)
        guard srcW > 0, srcH > 0 else { return nil }
        let box = TreeSegDecode.Letterbox(sourceWidth: srcW, sourceHeight: srcH, size: side)
        guard let chw = letterboxCHW(pixelBuffer, box: box, side: side) else { return nil }

        do {
            let shape: [NSNumber] = [1, 3, side, side].map { NSNumber(value: $0) }
            let data = chw.withUnsafeBufferPointer {
                NSMutableData(bytes: $0.baseAddress,
                              length: $0.count * MemoryLayout<Float>.size)
            }
            let input = try ORTValue(tensorData: data, elementType: .float, shape: shape)
            let outputs = try session.run(withInputs: [inputName: input],
                                          outputNames: outputNames, runOptions: nil)
            var det: [Float] = [], detShape: [Int] = []
            var proto: [Float] = [], protoShape: [Int] = []
            for (_, v) in outputs {
                let sh = ((try? v.tensorTypeAndShapeInfo().shape) ?? []).map { $0.intValue }
                guard let d = try? v.tensorData() else { continue }
                let arr = (d as Data).withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
                if sh.count == 3 { det = arr; detShape = sh }
                else if sh.count == 4 { proto = arr; protoShape = sh }
            }
            guard protoShape.count == 4, !proto.isEmpty else { return nil }
            let nm = protoShape[1], mh = protoShape[2], mw = protoShape[3]
            let trees = TreeSegDecode.detections(det: det, detShape: detShape,
                                                 maskCoeffCount: nm)
            guard let target = TreeSegDecode.pickTarget(trees, size: side) else { return nil }
            let cells = TreeSegDecode.fillMask(target, proto: proto, nm: nm, mh: mh, mw: mw,
                                               modelSize: side)
            return TreeSegDecode.StemMask(cells: cells, height: mh, width: mw,
                                          letterbox: box, score: target.score)
        } catch {
            return nil
        }
    }

    /// Letterbox to side×side with 114-grey padding and return normalised RGB
    /// CHW — the study's own preprocessing, so the network sees what it was
    /// benchmarked on.
    private func letterboxCHW(_ pb: CVPixelBuffer,
                              box: TreeSegDecode.Letterbox,
                              side: Int) -> [Float]? {
        // THE PADDING HAS TO BE IN THE IMAGE, not pre-filled in the buffer.
        // `CIContext.render(_:toBitmap:)` fills the whole bounds rect it is
        // given, so a buffer pre-set to 114-grey comes back with the pad areas
        // BLACK — a quarter of a 4:3 frame arriving in a distribution the
        // network never saw, on exactly the frames the bracket is placed from.
        // Compositing over a constant-colour image puts the grey where the
        // letterbox wants it and leaves nothing for render to clear.
        let grey = CIImage(color: CIColor(red: 114.0 / 255, green: 114.0 / 255,
                                          blue: 114.0 / 255, alpha: 1))
            .cropped(to: CGRect(x: 0, y: 0, width: side, height: side))
        let ci = CIImage(cvPixelBuffer: pb)
            .transformed(by: CGAffineTransform(scaleX: CGFloat(box.scale),
                                               y: CGFloat(box.scale)))
            .transformed(by: CGAffineTransform(translationX: CGFloat(box.padX),
                                               y: CGFloat(box.padY)))
            .composited(over: grey)
        let bytesPerRow = side * 4
        rgba.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return }
            ciContext.render(ci,
                             toBitmap: base,
                             rowBytes: bytesPerRow,
                             bounds: CGRect(x: 0, y: 0, width: side, height: side),
                             format: .RGBA8,
                             colorSpace: CGColorSpaceCreateDeviceRGB())
        }
        let area = side * side
        var chw = [Float](repeating: 0, count: 3 * area)
        // CoreImage renders bottom-left origin; the network reads top-left.
        for my in 0..<side {
            let by = side - 1 - my
            let rowBase = by * bytesPerRow
            let outRow = my * side
            for x in 0..<side {
                let p = rowBase + x * 4
                let idx = outRow + x
                chw[idx] = Float(rgba[p]) / 255
                chw[area + idx] = Float(rgba[p + 1]) / 255
                chw[2 * area + idx] = Float(rgba[p + 2]) / 255
            }
        }
        return chw
    }
}
#endif
