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

    /// THE TWO EDGES, at the row the estimator is going to read.
    ///
    /// A median over a BAND of mask rows rather than the single row, for the
    /// reason the depth path takes 21 rows: one row can clip a branch or a
    /// notch in the silhouette, and the diameter is linear in this width.
    /// Rows whose mask is empty do not vote — a band that runs off the top of
    /// a leaning stem should narrow the sample, not widen the answer.
    public static func extent(mask: [UInt8], mh: Int, mw: Int,
                              centreRow: Int, bandRows: Int,
                              letterbox: Letterbox,
                              score: Float) -> StemExtent? {
        var lefts: [Int] = [], rights: [Int] = []
        var pixels = 0
        let lo = max(0, centreRow - bandRows), hi = min(mh - 1, centreRow + bandRows)
        guard lo <= hi else { return nil }
        for y in lo...hi {
            let base = y * mw
            var l = -1, r = -1
            for x in 0..<mw where mask[base + x] == 1 {
                if l < 0 { l = x }
                r = x
                pixels += 1
            }
            if l >= 0 { lefts.append(l); rights.append(r) }
        }
        guard !lefts.isEmpty, pixels >= TreeSegmenterConfig.minMaskPixels else { return nil }
        lefts.sort(); rights.sort()
        let lm = Double(lefts[lefts.count / 2])
        let rm = Double(rights[rights.count / 2])
        // Proto grid to model space, then out through the letterbox. The +1 on
        // the right edge counts the pixel itself: a one-cell mask is one cell
        // wide, not zero.
        let cell = Double(TreeSegmenterConfig.inputSize) / Double(mw)
        let lf = letterbox.sourceXFraction(modelX: lm * cell)
        let rf = letterbox.sourceXFraction(modelX: (rm + 1) * cell)
        guard rf > lf else { return nil }
        return StemExtent(leftFraction: max(0, min(1, lf)),
                          rightFraction: max(0, min(1, rf)),
                          score: score, maskPixels: pixels)
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
    public func segment(pixelBuffer: CVPixelBuffer,
                        rowFraction: Double = 0.5) -> StemExtent? {
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
            let mask = TreeSegDecode.fillMask(target, proto: proto, nm: nm, mh: mh, mw: mw,
                                              modelSize: side)
            // The guide row, carried into proto space through the same
            // letterbox the image went in through.
            let modelRow = box.modelY(sourceYFraction: rowFraction)
            let protoRow = Int((modelRow * Double(mh) / Double(side)).rounded())
            // +/- 4 proto rows is ~16 model rows, the same neighbourhood the
            // depth walk medians over.
            return TreeSegDecode.extent(mask: mask, mh: mh, mw: mw,
                                        centreRow: max(0, min(mh - 1, protoRow)),
                                        bandRows: 4, letterbox: box,
                                        score: target.score)
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
        let nw = max(1, Int((Double(box.sourceWidth) * box.scale).rounded()))
        let nh = max(1, Int((Double(box.sourceHeight) * box.scale).rounded()))
        let ci = CIImage(cvPixelBuffer: pb)
            .transformed(by: CGAffineTransform(scaleX: CGFloat(box.scale),
                                               y: CGFloat(box.scale)))
            .transformed(by: CGAffineTransform(translationX: CGFloat(box.padX),
                                               y: CGFloat(box.padY)))
        for i in rgba.indices { rgba[i] = (i % 4 == 3) ? 255 : 114 }
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
        _ = (nw, nh)
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
