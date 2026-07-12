// DBH estimator — 1:1 port of iOS Sensors/DBHEstimator.swift `estimate()`
// (spec §7.1). Same burst pipeline: 5×5 median tap depth + range/confidence
// gates -> per-frame stem-strip extraction -> world-XZ back-projection ->
// statistical outlier removal -> 500-iter RANSAC + Taubin refit -> chord
// silhouette sanity -> the identical §7.9 check matrix -> cylinder
// calibration. Produces the same diameter / σ_R / arc-coverage / tier as iOS
// for the same depth burst.
//
// Platform note: the *input* (ArDepthFrame) comes from the ARCore Depth API
// instead of ARKit sceneDepth; the depth-pixel ↔ screen mapping + intrinsics
// scaling live in the capture layer and need on-device tuning. The math here
// is platform-independent and matches iOS exactly.

package com.hcjeong.forestix.sensors

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class ProjectCalibration(
    val depthNoiseMm: Float = 5.0f,
    val dbhCorrectionAlpha: Float = 0f,
    val dbhCorrectionBeta: Float = 1f,
    val vioDriftFraction: Float = 0.02f,
    val depthDiscontinuityM: Float = 0.04f,
) {
    companion object { val identity = ProjectCalibration() }
}

enum class DBHMethod(val raw: String) {
    LIDAR_PARTIAL_ARC_SINGLE_VIEW("lidarPartialArcSingleView"),
    // Dual-view partial-arc fit — raw MUST match iOS `lidarPartialArcDualView`
    // (Models/Tree.swift) so cross-platform exports join.
    LIDAR_PARTIAL_ARC_DUAL_VIEW("lidarPartialArcDualView"),
    // Irregular cross-section capture — raw MUST match iOS `lidarIrregular`.
    LIDAR_IRREGULAR("lidarIrregular"),
    LIDAR_CHORD_SILHOUETTE("lidarChordSilhouette"),
    MANUAL_CALIPER("manualCaliper"),
    // Two-tap trunk-edge caliper (no depth) — raw string MUST match iOS
    // `arCaliper` so cross-platform exports join.
    AR_CALIPER("arCaliper"),
    // AR-motion — circle fit to VIO feature points from a short sweep.
    // Raw MUST match iOS `arVioCircleFit`.
    AR_VIO_CIRCLE_FIT("arVioCircleFit"),
    // Typed manual entry — raw MUST match iOS `manualVisual`.
    MANUAL_VISUAL("manualVisual"),
}

data class DBHResult(
    val diameterCm: Float,
    val centerX: Float,
    val centerZ: Float,
    val arcCoverageDeg: Float,
    val rmseMm: Float,
    val sigmaRmm: Float,
    val nInliers: Int,
    val confidence: ConfidenceTier,
    val method: DBHMethod,
    val rejectionReason: String?,
)

/// Row-major depth + confidence grid with pinhole intrinsics and the
/// column-major camera->world pose. Mirror of iOS ARDepthFrame.
///
/// Android note: fx/fy/cx/cy are the GPU-TEXTURE intrinsics scaled per axis
/// to the depth grid (the ARCore depth image is registered to the texture's
/// TEXTURE_NORMALIZED space). When the depth aspect differs from the texture
/// aspect (typical: 4:3 depth vs 16:9 texture) the scaled fx and fy are NOT
/// equal — consumers must use the focal matching the axis they measure
/// along. iOS never sees this (sceneDepth and image are both 4:3 → fx==fy).
class ArDepthFrame(
    val width: Int,
    val height: Int,
    val depth: FloatArray,        // metres, row-major (h*w)
    val confidence: ByteArray,    // 0/1/2, row-major
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val pose: FloatArray,         // column-major 4x4 (T_world_camera)
    /// Diagnostic focals — what fx/fy would be if the depth image were
    /// registered to the CPU IMAGE instead of the GPU texture (CPU-image
    /// intrinsics × depth/CPU dims). Dev-HUD only: lets a field run decide
    /// which registration the device actually honours.
    val fxImg: Double = 0.0,
    val fyImg: Double = 0.0,
    /// VIEW-px -> depth-px affine (a, b, tx, c, d, ty):
    ///   depthX = a·viewX + b·viewY + tx;  depthY = c·viewX + d·viewY + ty.
    /// Built by the capture layer from ARCore's own VIEW→TEXTURE_NORMALIZED
    /// transform, so it carries the display rotation + aspect crop exactly.
    /// Null when the mapping isn't available (no viewport yet) — callers
    /// fall back to centre-of-grid behaviour.
    val depthFromViewAffine: FloatArray? = null,
) {
    fun depthAt(x: Int, y: Int): Float = depth[y * width + x]
    fun confidenceAt(x: Int, y: Int): Int = confidence[y * width + x].toInt() and 0xFF

    /// Map a view-space pixel (e.g. the crosshair centre) to depth-grid
    /// coordinates. Null when no mapping was captured.
    fun viewToDepth(vx: Float, vy: Float): Pair<Double, Double>? {
        val m = depthFromViewAffine ?: return null
        val dx = m[0] * vx + m[1] * vy + m[2]
        val dy = m[3] * vx + m[4] * vy + m[5]
        return dx.toDouble() to dy.toDouble()
    }

    /// Map a depth-grid coordinate back to view-space pixels (inverse of
    /// the affine). Null when no mapping / degenerate.
    fun depthToView(dx: Double, dy: Double): Pair<Float, Float>? {
        val m = depthFromViewAffine ?: return null
        val det = m[0] * m[4] - m[1] * m[3]
        if (abs(det) < 1e-9f) return null
        val rx = dx - m[2]
        val ry = dy - m[5]
        val vx = (m[4] * rx - m[1] * ry) / det
        val vy = (-m[3] * rx + m[0] * ry) / det
        return vx.toFloat() to vy.toFloat()
    }
}

sealed class GuideAxis {
    data class Row(val y: Int) : GuideAxis()
    data class Col(val x: Int) : GuideAxis()
}

/// Per-frame chord DBH algorithm, user-selectable in Settings.
/// SILHOUETTE = iOS-identical pixel-width identity (d = w·z/(fx−w/2));
/// DEPTH_BAND = the original Android world-space point-cloud diagonal.
enum class ChordAlgorithm(val raw: String) {
    SILHOUETTE("silhouette"), DEPTH_BAND("band");
    companion object { fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s } ?: SILHOUETTE }
}

data class DbhScanInput(
    val frames: List<ArDepthFrame>,
    val tapX: Double,
    val tapY: Double,
    val guideAxis: GuideAxis,
    val projectCalibration: ProjectCalibration,
)

object DBHEstimator {

    /// Full §7.1 pipeline. Returns null only if the burst is too small.
    fun estimate(input: DbhScanInput): DBHResult? {
        if (input.frames.size < 5) return null
        val lastFrame = input.frames.last()

        val dTap = medianDepth(input.tapX, input.tapY, lastFrame, radius = 2)
            ?: return red("Tap pixel outside depth map")
        if (dTap !in 0.5f..3.0f)
            return red("Move closer or step back; tap depth ${fmt2(dTap)} m out of range")
        if (confidenceAt(input.tapX, input.tapY, lastFrame) < 1)
            return red("Trunk surface not reliably seen; try a cleaner stem area")

        val tapAlong = tapAlongAxis(input.tapX, input.tapY, input.guideAxis)
        val combined = ArrayList<V2>(input.frames.size * 64)
        for (frame in input.frames) {
            val strip = extractGuideStemStrip(
                frame, input.guideAxis, tapAlong, dTap,
                deltaDepth = 0.15f,
                discontinuityThresholdM = input.projectCalibration.depthDiscontinuityM,
            )
            for (idx in strip) {
                val (px, py) = pixelCoords(input.guideAxis, idx)
                combined.add(
                    BackProjection.worldXZ(
                        px.toDouble(), py.toDouble(),
                        frame.depthAt(px, py).toDouble(),
                        frame.fx, frame.fy, frame.cx, frame.cy, frame.pose,
                    )
                )
            }
        }

        if (combined.size < 30)
            return red("Not enough surface points; hold steadier or move closer", nInliers = combined.size)

        val cleaned = OutlierRemoval.statistical(combined, k = 8, sigmaMult = 2.0)
        if (cleaned.size < 20)
            return red("Too few points after outlier removal", nInliers = cleaned.size)

        val cal = input.projectCalibration
        val noiseM = cal.depthNoiseMm / 1000.0
        val inlierTol = max(0.003, 2.0 * noiseM)
        val fit = RANSACCircle.fit(cleaned, inlierTol, iterations = 500, minInliers = 20)
            ?: return red("Could not fit a circle", nInliers = 0)

        val rmse = rootMeanSquaredResidual(fit.inliers, fit.circle)
        val arcDeg = arcCoverageDeg(fit.inliers, fit.circle.cx, fit.circle.cy)
        val sigmaR = sigmaR(noiseM, fit.inliers.size, arcDeg)
        val radiusCoV = perFrameRadiusCoV(
            input.frames, input.guideAxis, tapAlong, dTap, cal.depthDiscontinuityM,
        )

        // Step 8.5: chord silhouette sanity.
        val chordDiameterM = chordDiameterFromCloud(cleaned)
        var r = fit.circle.radius
        var chordOverride = false
        if (chordDiameterM > 0.025) {
            val ratio = (2.0 * r) / chordDiameterM
            if (ratio > 3.0 || ratio < 0.65) { r = chordDiameterM / 2.0; chordOverride = true }
        }

        // Step 9: §7.9 sanity tree (identical thresholds to iOS Phase 18.2).
        val checks = listOf(
            check(fit.inliers.size >= 10, Severity.REJECT, "Fewer than 10 trunk surface points"),
            check(fit.inliers.size >= 20, Severity.WARN, "Only 10\u201320 trunk surface points"),
            check(arcDeg >= 30, Severity.REJECT, "Trunk arc coverage below 30\u00B0"),
            check(arcDeg >= 45, Severity.WARN, "Trunk arc coverage 30\u00B0\u201345\u00B0"),
            check(r >= 0.025 && r <= 1.0, Severity.REJECT, "Fitted radius outside 2.5\u2013100 cm"),
            check(rmse / r <= 0.07, Severity.REJECT, "Fit error worse than 7% of radius"),
            check(rmse / r <= 0.05, Severity.WARN, "Fit error 5\u20137% of radius"),
            check(sigmaR / r <= 0.05, Severity.REJECT, "Radius precision worse than \u00B15%"),
            check(sigmaR / r <= 0.02, Severity.WARN, "Radius precision \u00B12\u20135%"),
            check(radiusCoV <= 0.10, Severity.REJECT, "Per-frame radius spread above 10%"),
            check(radiusCoV <= 0.05, Severity.WARN, "Per-frame radius spread 5\u201310%"),
            check(!chordOverride, Severity.WARN, "Fit disagreed with silhouette; using chord"),
        )
        val tier = combineChecks(checks)
        val rejectionReason = if (tier == ConfidenceTier.RED)
            (firstFailingRejectReason(checks) ?: "Quality below threshold") else null

        // Step 10: cylinder calibration.
        val dbhRawCm = 2 * r * 100
        val dbhCm = cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * dbhRawCm

        return DBHResult(
            diameterCm = dbhCm.toFloat(),
            centerX = fit.circle.cx.toFloat(),
            centerZ = fit.circle.cy.toFloat(),
            arcCoverageDeg = arcDeg.toFloat(),
            rmseMm = (rmse * 1000).toFloat(),
            sigmaRmm = (sigmaR * 1000).toFloat(),
            nInliers = fit.inliers.size,
            confidence = tier,
            method = DBHMethod.LIDAR_PARTIAL_ARC_SINGLE_VIEW,
            rejectionReason = rejectionReason,
        )
    }

    // MARK: - Guide-axis auto-selection

    /// The depth image arrives in the camera's sensor orientation, which
    /// differs by device/rotation — so a fixed guide axis can end up walking
    /// the strip ALONG the trunk (length) instead of ACROSS it (width). That
    /// collapses the back-projected points to a single world XZ spot and the
    /// diameter reads a few cm. We pick whichever axis yields the wider XZ
    /// chord at the centre, which is the across-the-trunk direction.
    fun pickGuideAxis(frame: ArDepthFrame, tapX: Double, tapY: Double, cal: ProjectCalibration): GuideAxis {
        val dTap = medianDepth(tapX, tapY, frame, 2) ?: return GuideAxis.Col(Math.round(tapX).toInt())
        fun chordFor(axis: GuideAxis): Double {
            val along = tapAlongAxis(tapX, tapY, axis)
            val strip = extractGuideStemStrip(frame, axis, along, dTap, 0.15f, cal.depthDiscontinuityM)
            if (strip.size < 4) return 0.0
            val pts = strip.map { idx ->
                val (px, py) = pixelCoords(axis, idx)
                BackProjection.worldXZ(px.toDouble(), py.toDouble(), frame.depthAt(px, py).toDouble(),
                    frame.fx, frame.fy, frame.cx, frame.cy, frame.pose)
            }
            return chordDiameterFromCloud(pts)
        }
        val row = GuideAxis.Row(Math.round(tapY).toInt())
        val col = GuideAxis.Col(Math.round(tapX).toInt())
        return if (chordFor(row) >= chordFor(col)) row else col
    }

    // MARK: - Live single-frame preview (cheap, no RANSAC)

    /// Lightweight per-frame estimate used by the scan HUD to show a live
    /// DBH digit + distance + a "locked" state — the Android analogue of
    /// iOS DBHEstimator.previewFit. Returns null if the tap is outside the
    /// depth map. `locked` = a plausible trunk fit this frame.
    /// `stripLeftFraction`/`stripRightFraction` are the trunk-strip edges as
    /// fractions of the depth-map extent along the guide axis (× screen
    /// width = the on-screen chord span, iOS PreviewFit parity). `tier` is
    /// the preview confidence (green = tight width consistency).
    data class DbhPreview(
        val diameterCm: Float,
        val distanceM: Float,
        val locked: Boolean,
        val nPoints: Int,
        val stripLeftFraction: Float = 0f,
        val stripRightFraction: Float = 0f,
        val tier: ConfidenceTier = ConfidenceTier.YELLOW,
    )

    /// Per-frame chord fit + the strip extent it was read from.
    private data class FrameChord(
        val diameterM: Double,
        val leftFrac: Float,
        val rightFrac: Float,
        /// Coefficient of variation of the per-row silhouette widths —
        /// null for the DEPTH_BAND algorithm (no row stack there).
        val widthCov: Double?,
    )

    /// Single-frame chord diameter in metres, or null if the trunk strip
    /// can't be read this frame. This is the SAME quantity the committed
    /// burst (estimateChord) medians, so the live number and the recorded
    /// number agree from the user's point of view. Two algorithms, user-
    /// selectable: SILHOUETTE (iOS-identical pixel-width) and DEPTH_BAND
    /// (the original Android world-space point-cloud diagonal).
    private fun frameChordDiameterM(
        frame: ArDepthFrame, tapX: Double, tapY: Double, axis: GuideAxis, dTap: Float,
        cal: ProjectCalibration, algorithm: ChordAlgorithm,
    ): FrameChord? = when (algorithm) {
        ChordAlgorithm.SILHOUETTE -> frameSilhouetteDiameterM(frame, tapX, tapY, axis, dTap)
        ChordAlgorithm.DEPTH_BAND -> {
            val tapAlong = tapAlongAxis(tapX, tapY, axis)
            val strip = extractGuideStemStrip(frame, axis, tapAlong, dTap, 0.15f, cal.depthDiscontinuityM)
            if (strip.size < 6) null else {
                val pts = strip.map { idx ->
                    val (px, py) = pixelCoords(axis, idx)
                    BackProjection.worldXZ(px.toDouble(), py.toDouble(), frame.depthAt(px, py).toDouble(),
                        frame.fx, frame.fy, frame.cx, frame.cy, frame.pose)
                }
                val chord = chordDiameterFromCloud(pts)
                if (chord > 0.0) {
                    val extent = axisExtent(frame, axis).toFloat().coerceAtLeast(1f)
                    FrameChord(chord, strip.min() / extent, strip.max() / extent, widthCov = null)
                } else null
            }
        }
    }

    /// Walk length of the guide axis in depth-map pixels — the denominator
    /// for the strip-edge fractions (iOS `frameExtent`).
    private fun axisExtent(frame: ArDepthFrame, axis: GuideAxis): Int = when (axis) {
        is GuideAxis.Row -> frame.width
        is GuideAxis.Col -> frame.height
    }

    /// Absolute validity band of the silhouette walk around the tap depth.
    /// Cylinder model: for a trunk of radius r whose FRONT surface is at
    /// depth z (≈ dTap), every true silhouette pixel lies at depth
    /// z … √((z+r)²−r²) < z+r, so |d − dTap| < r for ALL trunk pixels.
    /// With r ≤ 0.6 m (120 cm DBH — beyond any cruise tree) a pixel more
    /// than 0.6 m off the tap depth provably is NOT the trunk. On crisp
    /// LiDAR-style edges this band is a no-op (a >0.6 m step would already
    /// break the per-step jump rule); it exists because ARCore's ML/fused
    /// depth on non-ToF phones blurs the trunk→background edge into a
    /// smooth ramp whose PER-STEP deltas stay under silhouetteJumpM — the
    /// walk then never stops, the chord bar spans the screen, and the
    /// diameter reads far too wide (field round 7).
    private const val SILHOUETTE_MAX_DEV_M = 0.60f

    /// Silhouette strip walk — port of iOS extractChordSilhouetteStrip.
    /// Walks outward from the tap seed along the axis, stopping at an invalid
    /// pixel (conf 0 / depth ≤ 0), a depth jump > silhouetteJumpM, or a pixel
    /// outside the ±bandHalfWidthM validity band around bandCenterM (Android
    /// addition, see SILHOUETTE_MAX_DEV_M). Returns the contiguous trunk
    /// pixel indices (the projected silhouette edges).
    private fun extractChordSilhouetteStrip(
        frame: ArDepthFrame, axis: GuideAxis, tapAlong: Int, silhouetteJumpM: Float = 0.30f,
        bandCenterM: Float = 0f, bandHalfWidthM: Float = Float.POSITIVE_INFINITY,
    ): List<Int> {
        val walkLength = when (axis) {
            is GuideAxis.Row -> { if (axis.y < 0 || axis.y >= frame.height) return emptyList(); frame.width }
            is GuideAxis.Col -> { if (axis.x < 0 || axis.x >= frame.width) return emptyList(); frame.height }
        }
        val clampedTap = tapAlong.coerceIn(0, walkLength - 1)
        fun depthAt(idx: Int): Float { val (x, y) = pixelCoords(axis, idx); return frame.depthAt(x, y) }
        fun valid(idx: Int): Boolean {
            val (x, y) = pixelCoords(axis, idx)
            if (frame.confidenceAt(x, y) < 1) return false
            val d = frame.depthAt(x, y)
            if (d <= 0f) return false
            return bandCenterM <= 0f || abs(d - bandCenterM) <= bandHalfWidthM
        }
        var seed = clampedTap
        if (!valid(seed)) {
            var found = -1
            for (off in 1..10) {
                val l = clampedTap - off; if (l >= 0 && valid(l)) { found = l; break }
                val r = clampedTap + off; if (r < walkLength && valid(r)) { found = r; break }
            }
            if (found < 0) return emptyList()
            seed = found
        }
        val indices = ArrayList<Int>(); indices.add(seed)
        var lastDepth = depthAt(seed)
        var i = seed - 1
        while (i >= 0 && valid(i)) {
            val d = depthAt(i); if (abs(d - lastDepth) > silhouetteJumpM) break
            indices.add(i); lastDepth = d; i--
        }
        lastDepth = depthAt(seed); i = seed + 1
        while (i < walkLength && valid(i)) {
            val d = depthAt(i); if (abs(d - lastDepth) > silhouetteJumpM) break
            indices.add(i); lastDepth = d; i++
        }
        indices.sort()
        return indices
    }

    /// Per-frame silhouette diameter (m) — port of iOS chordPreviewFit's
    /// core: median silhouette width across ±rowSpan rows, then the pinhole
    /// chord identity d = w·dTap/(f − w/2). Null if too few usable rows.
    ///
    /// FOCAL CHOICE (Android): the width w is counted along the WALK axis —
    /// depth-x for a Row walk (divide by fx), depth-y for a Col walk (divide
    /// by fy). iOS always divides by fx because its depth grid has square
    /// pixels (fx == fy); on Android the texture-registered per-axis scaling
    /// makes fx ≠ fy whenever the depth aspect ≠ texture aspect (e.g. 4:3
    /// depth on a 16:9 texture → fy = 4/3·fx), and a portrait trunk chord is
    /// a Col walk — dividing by fx there over-reads the diameter by exactly
    /// fy/fx (≈1.33×, field round 7).
    private fun frameSilhouetteDiameterM(
        frame: ArDepthFrame, tapX: Double, tapY: Double, axis: GuideAxis, dTap: Float,
        rowSpan: Int = 10, silhouetteJumpM: Float = 0.30f,
    ): FrameChord? {
        val focal = when (axis) {
            is GuideAxis.Row -> frame.fx
            is GuideAxis.Col -> frame.fy
        }
        if (focal <= 0) return null
        val centerAlong = tapAlongAxis(tapX, tapY, axis)
        val widths = ArrayList<Int>()
        // First usable strip extent → the on-screen chord span (iOS
        // `firstUsableExtent`).
        var extentL = -1
        var extentR = -1
        for (offset in -rowSpan..rowSpan) {
            val neighbour = when (axis) {
                is GuideAxis.Row -> GuideAxis.Row(axis.y + offset)
                is GuideAxis.Col -> GuideAxis.Col(axis.x + offset)
            }
            val strip = extractChordSilhouetteStrip(
                frame, neighbour, centerAlong, silhouetteJumpM,
                bandCenterM = dTap, bandHalfWidthM = SILHOUETTE_MAX_DEV_M,
            )
            val l = strip.firstOrNull() ?: continue
            val r = strip.lastOrNull() ?: continue
            if (r <= l) continue
            val w = r - l + 1
            if (w < 5) continue
            widths.add(w)
            if (extentL < 0) { extentL = l; extentR = r }
        }
        if (widths.size < 5) return null
        widths.sort()
        val medianWidth = widths[widths.size / 2]
        val halfWidth = medianWidth / 2.0
        if (focal - halfWidth <= 1.0) return null
        val diameterM = medianWidth * dTap.toDouble() / (focal - halfWidth)
        if (diameterM <= 0.0) return null
        // Width consistency across the row stack — iOS chordPreviewFit's
        // tier input (CoV ≤ 0.10 ⇒ green preview chip).
        val mean = widths.sum().toDouble() / widths.size
        val cov = if (mean > 0) {
            sqrt(widths.sumOf { (it - mean) * (it - mean) } / widths.size) / mean
        } else 1.0
        val extent = axisExtent(frame, axis).toFloat().coerceAtLeast(1f)
        return FrameChord(
            diameterM,
            if (extentL >= 0) extentL / extent else 0f,
            if (extentR >= 0) extentR / extent else 1f,
            widthCov = cov,
        )
    }

    fun livePreview(
        frame: ArDepthFrame, tapX: Double, tapY: Double, axis: GuideAxis, cal: ProjectCalibration,
        algorithm: ChordAlgorithm = ChordAlgorithm.SILHOUETTE,
    ): DbhPreview? {
        val dTap = medianDepth(tapX, tapY, frame, 2) ?: return null
        if (dTap !in 0.4f..3.5f) return DbhPreview(0f, dTap, false, 0)
        val chord = frameChordDiameterM(frame, tapX, tapY, axis, dTap, cal, algorithm)
            ?: return DbhPreview(0f, dTap, false, 0)
        val locked = chord.diameterM in 0.025..2.0
        val dia = (cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * (chord.diameterM * 100)).toFloat()
        // Preview tier — width consistency, iOS chordPreviewFit parity:
        // CoV ≤ 0.10 ⇒ green (chip shown); otherwise yellow (silent).
        val tier = if (chord.widthCov != null && chord.widthCov <= 0.10) {
            ConfidenceTier.GREEN
        } else {
            ConfidenceTier.YELLOW
        }
        return DbhPreview(dia, dTap, locked, 1, chord.leftFrac, chord.rightFrac, tier)
    }

    /// Committed DBH = MEDIAN of the per-frame chord diameters over the
    /// burst (the iOS Phase-19 default "chord / silhouette" method). Uses the
    /// exact same per-frame chord the live preview shows, so what the cruiser
    /// sees is what gets recorded; the median + a per-frame spread check give
    /// the robustness the single-frame preview can't.
    fun estimateChord(
        frames: List<ArDepthFrame>, tapX: Double, tapY: Double, axis: GuideAxis, cal: ProjectCalibration,
        algorithm: ChordAlgorithm = ChordAlgorithm.SILHOUETTE,
    ): DBHResult? {
        // Min 5 frames to match the iOS chord burst (was 3 — the capture flow
        // already gates at ≥5, so this only aligns the estimator's own floor).
        if (frames.size < 5) return null
        val last = frames.last()
        val dTap = medianDepth(tapX, tapY, last, 2)
            ?: return redChord("Trunk surface not seen at the crosshair", 0)
        if (dTap !in 0.4f..3.5f)
            return redChord("Move to 0.4–3.5 m; tap depth ${fmt2(dTap)} m out of range", 0)

        val diameters = ArrayList<Double>(frames.size)
        for (f in frames) {
            frameChordDiameterM(f, tapX, tapY, axis, dTap, cal, algorithm)?.let { diameters.add(it.diameterM) }
        }
        if (diameters.size < 3) return redChord("Not enough trunk surface; hold steadier / move closer", diameters.size)

        diameters.sort()
        val medianM = diameters[diameters.size / 2]
        val mean = diameters.average()
        val sd = sqrt(diameters.sumOf { (it - mean) * (it - mean) } / diameters.size)
        val cov = if (mean > 0) sd / mean else 1.0
        val diaCm = (cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * (medianM * 100)).toFloat()

        val checks = listOf(
            check(medianM in 0.025..2.0, Severity.REJECT, "Diameter outside 2.5–200 cm"),
            check(cov <= 0.15, Severity.REJECT, "Per-frame spread above 15%"),
            check(cov <= 0.08, Severity.WARN, "Per-frame spread 8–15%"),
            check(diameters.size >= maxOf(3, frames.size / 2), Severity.WARN, "Few usable frames"),
        )
        val tier = combineChecks(checks)
        val reason = if (tier == ConfidenceTier.RED)
            (checks.firstOrNull { !it.passed && it.severity == Severity.REJECT }?.reason ?: "Quality below threshold") else null

        return DBHResult(
            diameterCm = diaCm, centerX = 0f, centerZ = 0f, arcCoverageDeg = 0f,
            // Per-sub-sample σ is 0 (matches iOS chordEstimate) — the published
            // ± comes from aggregateSamples' cross-sample standard error. The
            // old `sd*1000` stored a DIAMETER sigma in the RADIUS-sigma field
            // (2× too large) and double-counted spread with the aggregate SE.
            rmseMm = 0f, sigmaRmm = 0f, nInliers = diameters.size,
            confidence = tier, method = DBHMethod.LIDAR_CHORD_SILHOUETTE, rejectionReason = reason,
        )
    }

    private fun redChord(reason: String, n: Int) = DBHResult(
        0f, 0f, 0f, 0f, 0f, 0f, n, ConfidenceTier.RED, DBHMethod.LIDAR_CHORD_SILHOUETTE, reason,
    )

    // MARK: - Multi-sample aggregation (trimmed mean)

    /// Combine the hold-steady capture's repeated sub-measurements into one
    /// result: keep the 3 samples closest to the median diameter (with 5
    /// samples that trims the 2 largest deviations) and average them. Red
    /// sub-samples are excluded up front — they represent "couldn't
    /// measure", not a value. Mirror of iOS DBHEstimator.aggregateSamples
    /// so the two platforms record identical statistics.
    fun aggregateSamples(samples: List<DBHResult>): DBHResult? {
        val valid = samples.filter { it.confidence != ConfidenceTier.RED }
        if (valid.size < 3) return null

        val sorted = valid.map { it.diameterCm.toDouble() }.sorted()
        val median = sorted[sorted.size / 2]
        val kept = valid
            .sortedBy { abs(it.diameterCm.toDouble() - median) }
            .take(3)

        val dias = kept.map { it.diameterCm.toDouble() }
        val meanDia = dias.average()
        // Scatter of the kept samples → standard error of the mean, folded
        // into σ_R (radius, mm) on top of the per-sample σ so repeat-to-
        // repeat disagreement is visible in the published ±.
        val variance = dias.sumOf { (it - meanDia) * (it - meanDia) } / dias.size
        val seDiaCm = sqrt(variance) / sqrt(dias.size.toDouble())
        val seRadiusMm = seDiaCm * 10.0 / 2.0
        val meanSigma = kept.sumOf { it.sigmaRmm.toDouble() } / kept.size
        val sigmaRmm = sqrt(meanSigma * meanSigma + seRadiusMm * seRadiusMm)

        // Majority (median) tier across the kept samples — one noisy yellow
        // among greens doesn't demote the capture, two do.
        val tiers = kept.map { it.confidence }.sortedBy { it.ordinal }
        val tier = tiers[tiers.size / 2]

        fun medianF(xs: List<Float>): Float = xs.sorted()[xs.size / 2]
        return DBHResult(
            diameterCm = meanDia.toFloat(),
            centerX = medianF(kept.map { it.centerX }),
            centerZ = medianF(kept.map { it.centerZ }),
            arcCoverageDeg = medianF(kept.map { it.arcCoverageDeg }),
            rmseMm = medianF(kept.map { it.rmseMm }),
            sigmaRmm = sigmaRmm.toFloat(),
            nInliers = kept.sumOf { it.nInliers },
            confidence = tier,
            method = kept.first().method,
            rejectionReason = null,
        )
    }

    // MARK: - Sub-functions (verbatim ports)

    fun medianDepth(px: Double, py: Double, frame: ArDepthFrame, radius: Int): Float? {
        val cx = Math.round(px).toInt()
        val cy = Math.round(py).toInt()
        if (cx < 0 || cx >= frame.width || cy < 0 || cy >= frame.height) return null
        val samples = ArrayList<Float>((2 * radius + 1) * (2 * radius + 1))
        for (dy in -radius..radius) {
            val y = cy + dy
            if (y < 0 || y >= frame.height) continue
            for (dx in -radius..radius) {
                val x = cx + dx
                if (x < 0 || x >= frame.width) continue
                val d = frame.depthAt(x, y)
                if (d > 0) samples.add(d)
            }
        }
        if (samples.isEmpty()) return null
        samples.sort()
        return samples[samples.size / 2]
    }

    fun confidenceAt(px: Double, py: Double, frame: ArDepthFrame): Int {
        val cx = Math.round(px).toInt()
        val cy = Math.round(py).toInt()
        if (cx < 0 || cx >= frame.width || cy < 0 || cy >= frame.height) return 0
        return frame.confidenceAt(cx, cy)
    }

    private fun pixelCoords(axis: GuideAxis, idx: Int): Pair<Int, Int> = when (axis) {
        is GuideAxis.Row -> idx to axis.y
        is GuideAxis.Col -> axis.x to idx
    }

    private fun tapAlongAxis(tapX: Double, tapY: Double, axis: GuideAxis): Int = when (axis) {
        is GuideAxis.Row -> Math.round(tapX).toInt()
        is GuideAxis.Col -> Math.round(tapY).toInt()
    }

    fun extractGuideStemStrip(
        frame: ArDepthFrame,
        axis: GuideAxis,
        tapAlong: Int,
        dTap: Float,
        deltaDepth: Float,
        discontinuityThresholdM: Float = Float.POSITIVE_INFINITY,
    ): List<Int> {
        val walkLength: Int = when (axis) {
            is GuideAxis.Row -> { if (axis.y < 0 || axis.y >= frame.height) return emptyList(); frame.width }
            is GuideAxis.Col -> { if (axis.x < 0 || axis.x >= frame.width) return emptyList(); frame.height }
        }
        val clampedTap = max(0, min(walkLength - 1, tapAlong))

        fun depthAt(idx: Int): Float { val (x, y) = pixelCoords(axis, idx); return frame.depthAt(x, y) }
        fun confAt(idx: Int): Int { val (x, y) = pixelCoords(axis, idx); return frame.confidenceAt(x, y) }
        fun pixelValid(idx: Int): Boolean {
            if (confAt(idx) < 1) return false
            val d = depthAt(idx)
            if (d <= 0) return false
            return abs(d - dTap) < deltaDepth
        }

        var seed = clampedTap
        if (!pixelValid(seed)) {
            var found = -1
            for (off in 1..10) {
                val l = clampedTap - off
                if (l >= 0 && pixelValid(l)) { found = l; break }
                val rr = clampedTap + off
                if (rr < walkLength && pixelValid(rr)) { found = rr; break }
            }
            if (found < 0) return emptyList()
            seed = found
        }

        val seedDepth = depthAt(seed)
        val indices = ArrayList<Int>()
        indices.add(seed)

        var i = seed - 1
        var lastDepth = seedDepth
        while (i >= 0 && pixelValid(i)) {
            val d = depthAt(i)
            if (abs(d - lastDepth) > discontinuityThresholdM) break
            indices.add(i); lastDepth = d; i -= 1
        }

        i = seed + 1
        lastDepth = seedDepth
        while (i < walkLength && pixelValid(i)) {
            val d = depthAt(i)
            if (abs(d - lastDepth) > discontinuityThresholdM) break
            indices.add(i); lastDepth = d; i += 1
        }

        indices.sort()
        return indices
    }

    fun rootMeanSquaredResidual(inliers: List<V2>, circle: Circle2D): Double {
        if (inliers.isEmpty()) return 0.0
        var sumSq = 0.0
        for (p in inliers) {
            val dx = p.x - circle.cx; val dy = p.y - circle.cy
            val r = sqrt(dx * dx + dy * dy)
            val e = r - circle.radius
            sumSq += e * e
        }
        return sqrt(sumSq / inliers.size)
    }

    fun chordDiameterFromCloud(points: List<V2>): Double {
        if (points.isEmpty()) return 0.0
        var minX = Double.POSITIVE_INFINITY; var maxX = Double.NEGATIVE_INFINITY
        var minZ = Double.POSITIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
        for (p in points) {
            if (p.x < minX) minX = p.x
            if (p.x > maxX) maxX = p.x
            if (p.y < minZ) minZ = p.y
            if (p.y > maxZ) maxZ = p.y
        }
        val dx = maxX - minX; val dz = maxZ - minZ
        return sqrt(dx * dx + dz * dz)
    }

    fun arcCoverageDeg(inliers: List<V2>, centerX: Double, centerY: Double): Double {
        if (inliers.size < 2) return 0.0
        val angles = inliers.map { atan2(it.y - centerY, it.x - centerX) }.sorted()
        var maxGap = 0.0
        for (i in 1 until angles.size) maxGap = max(maxGap, angles[i] - angles[i - 1])
        val wrap = 2 * PI - (angles.last() - angles.first())
        maxGap = max(maxGap, wrap)
        val span = 2 * PI - maxGap
        return span * 180 / PI
    }

    fun sigmaR(noiseMeters: Double, nInliers: Int, arcDeg: Double): Double {
        if (nInliers <= 0 || arcDeg <= 0) return Double.POSITIVE_INFINITY
        val halfArcRad = arcDeg * PI / 360
        val sinHalf = max(sin(halfArcRad), 1e-3)
        return noiseMeters / (sqrt(nInliers.toDouble()) * sinHalf)
    }

    fun perFrameRadiusCoV(
        frames: List<ArDepthFrame>,
        axis: GuideAxis,
        tapAlong: Int,
        dTap: Float,
        discontinuityThresholdM: Float = Float.POSITIVE_INFINITY,
    ): Double {
        val radii = ArrayList<Double>()
        for (frame in frames) {
            val strip = extractGuideStemStrip(frame, axis, tapAlong, dTap, 0.15f, discontinuityThresholdM)
            if (strip.size < 5) continue
            val pts = strip.map { idx ->
                val (px, py) = pixelCoords(axis, idx)
                BackProjection.worldXZ(
                    px.toDouble(), py.toDouble(), frame.depthAt(px, py).toDouble(),
                    frame.fx, frame.fy, frame.cx, frame.cy, frame.pose,
                )
            }
            TaubinFit.fit(pts)?.let { radii.add(it.radius) }
        }
        if (radii.size < 2) return 0.0
        val mean = radii.sum() / radii.size
        if (mean <= 0) return 0.0
        var varSum = 0.0
        for (rr in radii) varSum += (rr - mean) * (rr - mean)
        val sd = sqrt(varSum / radii.size)
        return sd / mean
    }

    private fun firstFailingRejectReason(checks: List<Check>): String? =
        checks.firstOrNull { !it.passed && it.severity == Severity.REJECT }?.reason

    private fun red(reason: String, nInliers: Int = 0): DBHResult = DBHResult(
        diameterCm = 0f, centerX = 0f, centerZ = 0f, arcCoverageDeg = 0f,
        rmseMm = 0f, sigmaRmm = 0f, nInliers = nInliers,
        confidence = ConfidenceTier.RED, method = DBHMethod.LIDAR_PARTIAL_ARC_SINGLE_VIEW,
        rejectionReason = reason,
    )

    private fun fmt2(v: Float) = String.format(Locale.US, "%.2f", v)
}
