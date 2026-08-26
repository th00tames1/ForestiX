// THE STEM'S EDGES, READ OUT OF A SEGMENTATION MASK. iOS
// Sensors/TreeSegmenter.swift port — same model file, same gates, same decode,
// so a mask read on one phone is the mask read on the other.
//
// The Auto diameter path finds the trunk's left and right edge by walking the
// DEPTH map outward from the crosshair until depth becomes invalid or jumps a
// discontinuity. That works on a clean stem at working distance and fails the
// way depth fails: a branch crossing the row, a second trunk seen through a
// lean, wet bark that returns nothing. This reads the same two edges out of an
// RGB instance mask instead.
//
// IT PRODUCES EDGES, NOT A DIAMETER. The chord identity, the median, the tier
// and the sigma are all downstream and all untouched — this replaces the step
// that answers "which pixels are trunk?" and hands back what the depth walk
// hands back: a left and a right fraction across the frame.
//
// THE MODEL is a YOLO11n-seg export, 640x640, batch 1, classes
// {0: soot, 1: tree}, of which only `tree` is used. ~11 MB, gitignored; see
// assets/models/README.md. A build without it reports NO_MODEL and the depth
// walk runs.
//
// WHAT IT HAS NOT BEEN SHOWN: the weights were fitted on burned Korean pine.
// A trunk silhouette transfers far better than bark scorch does, but nothing
// here has been checked against tape. Off by default.

package com.hcjeong.forestix.sensors

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/// Where a stem's two edges were found, as fractions across the CAMERA IMAGE.
/// The same normalisation `DbhPreview.stripLeftFraction` already carries.
data class StemExtent(
    val leftFraction: Double,
    val rightFraction: Double,
    val score: Float,
    val maskPixels: Int,
) {
    val widthFraction: Double get() = rightFraction - leftFraction
}

enum class SegmenterAvailability { READY, NO_MODEL, FAILED }

object TreeSegmenterConfig {
    const val SOOT_CLASS = 0
    const val TREE_CLASS = 1
    /// The BSI bench app's gates, kept identical so the two agree on what the
    /// network said.
    const val CONFIDENCE = 0.25f
    const val IOU = 0.45f
    const val INPUT_SIZE = 640
    /// Below this the instance is a speck and its edges mean nothing.
    const val MIN_MASK_PIXELS = 100
    const val ASSET_PATH = "models/tree_seg_640.onnx"
}

/// Letterbox geometry: how the source image was placed inside the square the
/// network sees, so mask coordinates can be brought back out again.
data class Letterbox(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val size: Int,
) {
    val scale: Double =
        minOf(size.toDouble() / sourceWidth, size.toDouble() / sourceHeight)
    val padX: Double = (size - sourceWidth * scale) / 2.0
    val padY: Double = (size - sourceHeight * scale) / 2.0

    fun sourceXFraction(modelX: Double): Double {
        if (scale <= 0.0 || sourceWidth <= 0) return 0.0
        return ((modelX - padX) / scale) / sourceWidth
    }

    fun modelY(sourceYFraction: Double): Double =
        padY + sourceYFraction * sourceHeight * scale
}

/// The parts that are pure arithmetic — no runtime, no Android, testable.
object TreeSegDecode {

    data class Detection(
        val cls: Int, val score: Float,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val coeffs: FloatArray,
    )

    fun iou(a: Detection, b: Detection): Float {
        val ix1 = maxOf(a.x1, b.x1); val iy1 = maxOf(a.y1, b.y1)
        val ix2 = minOf(a.x2, b.x2); val iy2 = minOf(a.y2, b.y2)
        val iw = maxOf(0f, ix2 - ix1); val ih = maxOf(0f, iy2 - iy1)
        val inter = iw * ih
        val ua = maxOf(0f, a.x2 - a.x1) * maxOf(0f, a.y2 - a.y1)
        val ub = maxOf(0f, b.x2 - b.x1) * maxOf(0f, b.y2 - b.y1)
        val u = ua + ub - inter
        return if (u <= 0f) 0f else inter / u
    }

    fun nms(input: List<Detection>, iouGate: Float): List<Detection> {
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val a = sorted.removeAt(0)
            keep.add(a)
            sorted.removeAll { iou(a, it) > iouGate }
        }
        return keep
    }

    /// WHICH STEM THE CRUISER MEANS. Biggest instance, discounted by how far
    /// its centre sits from the middle of the frame — a cruiser aims at the
    /// tree they are measuring.
    fun pickTarget(trees: List<Detection>, size: Int): Detection? {
        if (trees.isEmpty()) return null
        val c = size / 2f
        val halfDiag = 0.5f * kotlin.math.sqrt(2f * size * size)
        var best: Detection? = null
        var bestScore = -1f
        for (d in trees) {
            val area = maxOf(0f, d.x2 - d.x1) * maxOf(0f, d.y2 - d.y1)
            val mx = (d.x1 + d.x2) / 2f; val my = (d.y1 + d.y2) / 2f
            val dist = kotlin.math.sqrt((mx - c) * (mx - c) + (my - c) * (my - c))
            val score = area * (1 - 0.5f * (dist / halfDiag))
            if (score > bestScore) { bestScore = score; best = d }
        }
        return best
    }

    /// Raw network output to the `tree` detections it contains. Handles BOTH
    /// export layouts — YOLO26's end-to-end [1, nDet, 6+nm] and YOLO11's dense
    /// [1, 4+nc+nm, anchors] — because the study exported both.
    fun detections(det: FloatArray, detShape: IntArray, maskCoeffCount: Int): List<Detection> {
        if (detShape.size != 3 || det.isEmpty()) return emptyList()
        val nm = maskCoeffCount
        val dim1 = detShape[1]; val dim2 = detShape[2]

        if (dim2 == nm + 6) {
            val raw = mutableListOf<Detection>()
            for (i in 0 until dim1) {
                val base = i * dim2
                val conf = det[base + 4]
                if (Math.round(det[base + 5]) != TreeSegmenterConfig.TREE_CLASS) continue
                if (conf < TreeSegmenterConfig.CONFIDENCE) continue
                raw.add(
                    Detection(
                        TreeSegmenterConfig.TREE_CLASS, conf,
                        det[base], det[base + 1], det[base + 2], det[base + 3],
                        det.copyOfRange(base + 6, base + 6 + nm),
                    ),
                )
            }
            return raw
        }

        val nc = dim1 - 4 - nm
        if (nc < 1 || nc > 100) return emptyList()
        val n = dim2
        val raw = mutableListOf<Detection>()
        for (i in 0 until n) {
            var best = 0; var bestScore = -1f
            for (k in 0 until nc) {
                val s = det[(4 + k) * n + i]
                if (s > bestScore) { bestScore = s; best = k }
            }
            if (best != TreeSegmenterConfig.TREE_CLASS) continue
            if (bestScore < TreeSegmenterConfig.CONFIDENCE) continue
            val cx = det[i]; val cy = det[n + i]
            val w = det[2 * n + i]; val h = det[3 * n + i]
            val coeffs = FloatArray(nm) { j -> det[(4 + nc + j) * n + i] }
            raw.add(
                Detection(best, bestScore, cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, coeffs),
            )
        }
        return nms(raw, TreeSegmenterConfig.IOU)
    }

    fun fillMask(
        d: Detection, proto: FloatArray, nm: Int, mh: Int, mw: Int, modelSize: Int,
    ): ByteArray {
        val mask = ByteArray(mh * mw)
        val factor = mw.toFloat() / modelSize
        val mhmw = mh * mw
        fun clamp(v: Int, lo: Int, hi: Int) = minOf(maxOf(v, lo), hi)
        val bx1 = clamp((d.x1 * factor).toInt(), 0, mw - 1)
        val bx2 = clamp((d.x2 * factor).toInt(), 0, mw - 1)
        val by1 = clamp((d.y1 * factor).toInt(), 0, mh - 1)
        val by2 = clamp((d.y2 * factor).toInt(), 0, mh - 1)
        if (bx2 < bx1 || by2 < by1) return mask
        for (y in by1..by2) {
            val rowBase = y * mw
            for (x in bx1..bx2) {
                val pix = rowBase + x
                var s = 0f
                for (j in 0 until nm) s += d.coeffs[j] * proto[j * mhmw + pix]
                if (1f / (1f + kotlin.math.exp(-s)) > 0.5f) mask[pix] = 1
            }
        }
        return mask
    }

    /// THE TWO EDGES, at the row the estimator is going to read.
    ///
    /// A median over a BAND of mask rows rather than the single row, for the
    /// reason the depth path takes 21: one row can clip a branch or a notch in
    /// the silhouette, and the diameter is linear in this width. Empty rows do
    /// not vote.
    fun extent(
        mask: ByteArray, mh: Int, mw: Int,
        centreRow: Int, bandRows: Int, letterbox: Letterbox, score: Float,
    ): StemExtent? {
        val lefts = mutableListOf<Int>(); val rights = mutableListOf<Int>()
        var pixels = 0
        val lo = maxOf(0, centreRow - bandRows)
        val hi = minOf(mh - 1, centreRow + bandRows)
        if (lo > hi) return null
        for (y in lo..hi) {
            val base = y * mw
            var l = -1; var r = -1
            for (x in 0 until mw) {
                if (mask[base + x].toInt() == 1) {
                    if (l < 0) l = x
                    r = x
                    pixels++
                }
            }
            if (l >= 0) { lefts.add(l); rights.add(r) }
        }
        if (lefts.isEmpty() || pixels < TreeSegmenterConfig.MIN_MASK_PIXELS) return null
        lefts.sort(); rights.sort()
        val lm = lefts[lefts.size / 2].toDouble()
        val rm = rights[rights.size / 2].toDouble()
        val cell = TreeSegmenterConfig.INPUT_SIZE.toDouble() / mw
        val lf = letterbox.sourceXFraction(lm * cell)
        // +1 counts the cell itself: a one-cell mask is one cell wide.
        val rf = letterbox.sourceXFraction((rm + 1) * cell)
        if (rf <= lf) return null
        return StemExtent(
            leftFraction = lf.coerceIn(0.0, 1.0),
            rightFraction = rf.coerceIn(0.0, 1.0),
            score = score,
            maskPixels = pixels,
        )
    }
}

/// Runs the model and answers with a stem's two edges. One instance per scan
/// screen; `segment` is blocking and must be called off the main thread.
class TreeSegmenter(context: Context) {

    var availability: SegmenterAvailability = SegmenterAvailability.NO_MODEL
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var inputName: String = "images"

    init {
        try {
            val bytes = context.assets.open(TreeSegmenterConfig.ASSET_PATH).use { it.readBytes() }
            val e = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                // The same ceiling iOS applies. An AR session is already
                // holding the depth stream, the camera and a rendered scene; a
                // segmenter that grabs every core to shave a few ms is a
                // segmenter that makes the preview stutter.
                setIntraOpNumThreads(2)
            }
            session = e.createSession(bytes, opts)
            env = e
            inputName = session?.inputNames?.firstOrNull() ?: "images"
            availability = SegmenterAvailability.READY
        } catch (_: java.io.FileNotFoundException) {
            availability = SegmenterAvailability.NO_MODEL
        } catch (_: Throwable) {
            availability = SegmenterAvailability.FAILED
        }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
    }

    /// Segment one letterboxed frame. `chw` must be normalised RGB in CHW at
    /// INPUT_SIZE — see `ArController.cameraLetterboxCHW`, which produces it
    /// straight from the ARCore YUV image without a bitmap round trip.
    fun segment(chw: FloatArray, box: Letterbox, rowFraction: Double = 0.5): StemExtent? {
        val s = session ?: return null
        val e = env ?: return null
        if (availability != SegmenterAvailability.READY) return null
        val side = TreeSegmenterConfig.INPUT_SIZE
        return try {
            val shape = longArrayOf(1, 3, side.toLong(), side.toLong())
            OnnxTensor.createTensor(e, FloatBuffer.wrap(chw), shape).use { input ->
                s.run(mapOf(inputName to input)).use { outputs ->
                    var det: FloatArray? = null; var detShape: IntArray? = null
                    var proto: FloatArray? = null; var protoShape: IntArray? = null
                    for (i in 0 until outputs.size()) {
                        val v = outputs.get(i).value
                        when (v) {
                            is Array<*> -> {
                                val flat = flatten(v) ?: continue
                                if (flat.second.size == 3) { det = flat.first; detShape = flat.second }
                                else if (flat.second.size == 4) { proto = flat.first; protoShape = flat.second }
                            }
                        }
                    }
                    val pShape = protoShape ?: return null
                    val p = proto ?: return null
                    val nm = pShape[1]; val mh = pShape[2]; val mw = pShape[3]
                    val trees = TreeSegDecode.detections(
                        det ?: return null, detShape ?: return null, nm,
                    )
                    val target = TreeSegDecode.pickTarget(trees, side) ?: return null
                    val mask = TreeSegDecode.fillMask(target, p, nm, mh, mw, side)
                    val modelRow = box.modelY(rowFraction)
                    val protoRow = Math.round(modelRow * mh / side).toInt()
                    TreeSegDecode.extent(
                        mask, mh, mw,
                        centreRow = protoRow.coerceIn(0, mh - 1),
                        bandRows = 4, letterbox = box, score = target.score,
                    )
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /// ONNX Runtime hands back nested Java arrays; the decode wants one flat
    /// buffer plus the shape, the way the iOS bindings already give it.
    private fun flatten(v: Any?): Pair<FloatArray, IntArray>? {
        val dims = mutableListOf<Int>()
        var cur: Any? = v
        while (cur is Array<*>) {
            dims.add(cur.size)
            cur = cur.firstOrNull()
        }
        if (cur is FloatArray) dims.add(cur.size) else return null
        val total = dims.fold(1) { a, b -> a * b }
        val out = FloatArray(total)
        var idx = 0
        fun walk(node: Any?) {
            when (node) {
                is Array<*> -> node.forEach { walk(it) }
                is FloatArray -> { node.copyInto(out, idx); idx += node.size }
            }
        }
        walk(v)
        return out to dims.toIntArray()
    }
}
