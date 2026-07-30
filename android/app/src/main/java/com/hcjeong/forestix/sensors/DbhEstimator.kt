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

import com.hcjeong.forestix.ar.Vec3
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
    // LEGACY, decode-only. The AR-caliper (two-tap trunk edges) and AR-motion
    // (VIO circle fit) capture arms were removed — they recorded no raw
    // captures, so nothing could be re-derived from them. Trees already
    // tallied with either arm still carry these raw strings, so the cases
    // stay so Mappers can decode them; nothing produces them any more. Raw
    // strings MUST keep matching iOS so cross-platform exports join.
    AR_CALIPER("arCaliper"),
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
    /// Raw sensor u16 depth (native millimetres, row-major) — populated
    /// ONLY while the raw-capture recorder is armed
    /// (ArController.captureRawDepth). The serialized replay bundles store
    /// these bytes verbatim and re-ingest them through `ingestMmInto`, so a
    /// replayed frame is bit-identical to what the estimator consumed live.
    /// Null in normal operation (zero cost with recording off).
    val rawDepthMm: ShortArray? = null,
) {
    companion object {
        /// Shared u16-mm → (metres, confidence) ingest rule — the SINGLE
        /// definition of how a raw ARCore D_16 sample becomes the float
        /// depth + confidence the estimators consume (1–8000 mm valid,
        /// everything else a conf-0 hole). Used by the live capture path
        /// (ArController.acquireDepthFrame) AND the raw-capture replay
        /// deserializer so replayed inputs match live ones exactly.
        fun ingestMmInto(mm: Int, idx: Int, depth: FloatArray, confidence: ByteArray) {
            if (mm in 1..8000) {
                depth[idx] = mm / 1000f
                confidence[idx] = 2
            } else {
                depth[idx] = 0f
                confidence[idx] = 0
            }
        }
    }

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

    /// Plausible-diameter window for any single-frame fit, in centimetres.
    ///
    /// THE CEILING USED TO BE 100 cm, WHICH IS 39.4 INCHES. The stand at McDunn
    /// has Douglas-fir over 40 in, so the gate refused them outright: the bracket
    /// would be placed correctly on a 42 in stem, the arithmetic would return
    /// ~107 cm, and the fit would come back null with nothing on screen. It also
    /// explains why a deliberately wide bracket "stopped working" past about half
    /// the screen. Same number, three symptoms.
    ///
    /// 300 cm clears any stem this app will meet while still refusing the
    /// degenerate cases the gate exists for. Floor stays at 2.5 cm. iOS value 1:1.
    val PLAUSIBLE_DIAMETER_CM = 2.5..300.0

    /// §7.9 tier thresholds, named once so the cruiser-facing confidence
    /// explainer can QUOTE the numbers the checks apply instead of carrying a
    /// prose copy of them that drifts the first time one of them moves. Every
    /// value is the shipped one; this is a naming change, not a tuning one,
    /// and no stored measurement, sigma, method or capture_mode moves with it.
    ///
    /// Mirrored byte-for-byte by iOS `DBHEstimator.TierThresholds`.
    object TierThresholds {
        // The §7.1 partial-arc circle fit (`estimate`). NOT the default
        // capture — see FRAME_SPREAD_GREEN for the one that is.
        const val MIN_INLIERS_REJECT = 10
        const val MIN_INLIERS_WARN = 20
        const val MIN_ARC_DEG_REJECT = 30.0
        const val MIN_ARC_DEG_WARN = 45.0
        const val RMSE_OVER_RADIUS_REJECT = 0.07
        const val RMSE_OVER_RADIUS_WARN = 0.05
        const val SIGMA_OVER_RADIUS_REJECT = 0.05
        const val SIGMA_OVER_RADIUS_WARN = 0.02
        const val RADIUS_COV_REJECT = 0.10
        const val RADIUS_COV_WARN = 0.05

        /// THE DEFAULT CAPTURE'S ONLY TIER RULE. The edge-bracket (Adjust)
        /// path grades a burst on frame-to-frame agreement alone —
        /// (max − min) / mean of the per-frame diameters. At or below this
        /// it is green, above it yellow. None of the circle-fit criteria
        /// above run on that path at all.
        const val FRAME_SPREAD_GREEN = 0.15

        /// A burst needs this many usable frames before it is graded; fewer
        /// is the one way the default path produces a red.
        const val MIN_USABLE_FRAMES = 3
    }

    /// Full §7.1 pipeline. Returns null only if the burst is too small.
    fun estimate(input: DbhScanInput): DBHResult? {
        if (input.frames.size < 5) return null
        val lastFrame = input.frames.last()

        val dTap = medianDepth(input.tapX, input.tapY, lastFrame, radius = 2)
            ?: return red("The crosshair isn't on anything the depth camera can see — aim at the trunk and capture again.")
        if (dTap !in 0.5f..3.0f)
            // The instruction IS the whole message; the "tap depth … out of
            // range" half echoed the depth sample under the crosshair, which
            // a cruiser cannot act on. The 0.5–3 m gate above is unchanged.
            return red("Stand 0.5–3 m from the trunk and capture again.")
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
            // Was "Too few points after outlier removal" — a pipeline step's
            // name, asking for nothing. The COUNT gate (>= 20 after the
            // k = 8 statistical trim) is unchanged; only the sentence is.
            return red("Too much of that scan was noise to measure from — move closer, fill the crosshair with bark, and capture again.", nInliers = cleaned.size)

        val cal = input.projectCalibration
        val noiseM = cal.depthNoiseMm / 1000.0
        val inlierTol = max(0.003, 2.0 * noiseM)
        val fit = RANSACCircle.fit(cleaned, inlierTol, iterations = 500, minInliers = 20)
            // Was "Could not fit a circle" — the name of the algorithm, with
            // no instruction. RANSAC's tolerance, iteration budget and
            // 20-inlier floor above are all unchanged.
            ?: return red("Couldn't find a round trunk in that scan — aim at the stem, hold steadier, and capture again.", nInliers = 0)

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
        // REJECT reasons are the ones a cruiser reads (the banner shows
        // `firstFailingRejectReason`), so they are written as instructions.
        // WARN reasons stay in the estimator's own vocabulary — they never
        // leave the struct. Every THRESHOLD below is unchanged.
        val checks = listOf(
            check(fit.inliers.size >= TierThresholds.MIN_INLIERS_REJECT, Severity.REJECT,
                "Too little of the trunk was picked up — move closer, fill the crosshair with bark, and capture again."),
            check(fit.inliers.size >= TierThresholds.MIN_INLIERS_WARN, Severity.WARN, "Only 10\u201320 trunk surface points"),
            check(arcDeg >= TierThresholds.MIN_ARC_DEG_REJECT, Severity.REJECT,
                "Not enough of the trunk in view — step back or centre the guide line."),
            check(arcDeg >= TierThresholds.MIN_ARC_DEG_WARN, Severity.WARN, "Trunk arc coverage 30\u00B0\u201345\u00B0"),
            check(r >= 0.025 && r <= 1.0, Severity.REJECT,
                "That doesn't measure like a trunk — aim at the stem and capture again."),
            check(rmse / r <= TierThresholds.RMSE_OVER_RADIUS_REJECT, Severity.REJECT,
                "The shape didn't match a trunk — hold steadier and capture again."),
            check(rmse / r <= TierThresholds.RMSE_OVER_RADIUS_WARN, Severity.WARN, "Fit error 5\u20137% of radius"),
            check(sigmaR / r <= TierThresholds.SIGMA_OVER_RADIUS_REJECT, Severity.REJECT,
                "This diameter isn't settling — hold steadier and capture again."),
            check(sigmaR / r <= TierThresholds.SIGMA_OVER_RADIUS_WARN, Severity.WARN, "Radius precision \u00B12\u20135%"),
            check(radiusCoV <= TierThresholds.RADIUS_COV_REJECT, Severity.REJECT,
                "The trunk width kept changing between shots — hold the phone steadier and capture again."),
            check(radiusCoV <= TierThresholds.RADIUS_COV_WARN, Severity.WARN, "Per-frame radius spread 5\u201310%"),
            check(!chordOverride, Severity.WARN, "Fit disagreed with silhouette; using chord"),
        )
        val tier = combineChecks(checks)
        val rejectionReason = if (tier == ConfidenceTier.RED)
            (firstFailingRejectReason(checks) ?: "This reading didn't pass the accuracy checks — retake it, or accept it and flag the tree.") else null

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
        /// True when this frame produced no fit BECAUSE the silhouette walk
        /// ran off the image border (edges not found) — a framing problem;
        /// the UI reports "adjust framing" instead of a generic miss.
        val edgesClipped: Boolean = false,
        /// Rows discarded for border touches this frame (dev HUD).
        val clippedRows: Int = 0,
        /// Median silhouette width in walk-axis pixels (dev HUD).
        val widthPx: Int = 0,
        /// Clean rows THIS tick contributed (before any carry) — dev HUD
        /// miss diagnosis ("rows 3/5") + the caller's rolling-quorum carry.
        val cleanRows: Int = 0,
        /// This tick's own clean-row widths — the caller may feed them back
        /// as `carryWidths` on the next tick (rolling row quorum while the
        /// aim is steady). Never includes borrowed carry widths.
        val cleanWidths: List<Int> = emptyList(),
        /// World-space cylinder-axis centre of this tick's fit (strip
        /// midpoint back-projected, pushed one radius behind the surface) —
        /// the overlay cylinder's anchor. Null when no fit.
        val centerWorld: Vec3? = null,
    )

    /// Per-frame chord fit + the strip extent it was read from.
    private data class FrameChord(
        val diameterM: Double,
        val leftFrac: Float,
        val rightFrac: Float,
        /// Coefficient of variation of the per-row silhouette widths —
        /// null for the DEPTH_BAND algorithm (no row stack there).
        val widthCov: Double?,
        /// Median silhouette width in walk-axis pixels (dev HUD).
        val widthPx: Int = 0,
        /// World-space CYLINDER-AXIS centre of this fit: the strip midpoint
        /// back-projected at its own depth, pushed one radius behind the
        /// front surface (iOS chordPreviewFit `center` parity). Anchors the
        /// translucent cylinder overlay on the SAME fit the bar is drawn
        /// from. Null when unavailable.
        val centerWorld: Vec3? = null,
    )

    /// Per-frame scan outcome: the chord fit (null when unusable) plus how
    /// many rows were discarded because the silhouette walk ran off the
    /// image border — edges not found inside the frame, so their "width"
    /// would have been the image extent, not the trunk — and the clean-row
    /// widths this frame produced on its own (rolling-quorum carry).
    private data class FrameScan(
        val chord: FrameChord?,
        val borderClippedRows: Int = 0,
        val cleanWidths: List<Int> = emptyList(),
    )

    /// Rows that must have run off the border for a failed frame to be
    /// reported as a framing problem ("Edges not found — adjust framing.")
    /// rather than a generic surface miss. 3 of the 21 attempted rows.
    private const val EDGE_CLIP_ROWS_MIN = 3

    /// Single-frame chord diameter in metres, or null if the trunk strip
    /// can't be read this frame. This is the SAME quantity the committed
    /// burst (estimateChord) medians, so the live number and the recorded
    /// number agree from the user's point of view. Two algorithms, user-
    /// selectable: SILHOUETTE (iOS-identical pixel-width) and DEPTH_BAND
    /// (the original Android world-space point-cloud diagonal).
    private fun frameChordDiameterM(
        frame: ArDepthFrame, tapX: Double, tapY: Double, axis: GuideAxis, dTap: Float,
        cal: ProjectCalibration, algorithm: ChordAlgorithm,
        carryWidths: List<Int> = emptyList(),
    ): FrameScan = when (algorithm) {
        ChordAlgorithm.SILHOUETTE ->
            frameSilhouetteDiameterM(frame, tapX, tapY, axis, dTap, carryWidths = carryWidths)
        ChordAlgorithm.DEPTH_BAND -> {
            val tapAlong = tapAlongAxis(tapX, tapY, axis)
            val strip = extractGuideStemStrip(frame, axis, tapAlong, dTap, 0.15f, cal.depthDiscontinuityM)
            val chordFit = if (strip.size < 6) null else {
                val pts = strip.map { idx ->
                    val (px, py) = pixelCoords(axis, idx)
                    BackProjection.worldXZ(px.toDouble(), py.toDouble(), frame.depthAt(px, py).toDouble(),
                        frame.fx, frame.fy, frame.cx, frame.cy, frame.pose)
                }
                val chord = chordDiameterFromCloud(pts)
                if (chord > 0.0) {
                    val extent = axisExtent(frame, axis).toFloat().coerceAtLeast(1f)
                    FrameChord(chord, strip.min() / extent, strip.max() / extent,
                        widthCov = null, widthPx = strip.size)
                } else null
            }
            FrameScan(chordFit)
        }
    }

    /// Walk length of the guide axis in depth-map pixels — the denominator
    /// for the strip-edge fractions (iOS `frameExtent`).
    private fun axisExtent(frame: ArDepthFrame, axis: GuideAxis): Int = when (axis) {
        is GuideAxis.Row -> frame.width
        is GuideAxis.Col -> frame.height
    }

    /// Noise margin of the cylinder envelope: base term + range-scaled term.
    /// This budgets RELATIVE depth wobble within one smoothed map (spatially
    /// correlated ML-depth bias cancels in |d − dTap|), not absolute
    /// accuracy: ~4.5 cm at 0.5 m, ~10.5 cm at 2.5 m. Simulated against the
    /// acceptance cases: keeps the full tangent width of pure trunks
    /// (10 cm@0.5 m … 60 cm@1.5 m all retain w = 2p*+1) while stopping a
    /// wall +0.4 m behind a 10 cm object within 1–2 px of its edge.
    private const val ENVELOPE_MARGIN_BASE_M = 0.03
    private const val ENVELOPE_MARGIN_PER_M = 0.03

    /// Cylinder-envelope edge criterion (field round 7, desk-test fix).
    ///
    /// A FIXED depth band around dTap cannot work: it must admit the trunk's
    /// own front-face rise at the silhouette, √((z+r)²−r²) − z (e.g. +0.28 m
    /// for r=0.30 at z=2.5), yet reject backgrounds that can sit closer than
    /// that behind a small object (10 cm tumbler at 0.53 m with a wall
    /// +0.4 m read DBH 64 cm through a 0.60 m band). The resolving fact:
    /// the admissible deviation depends on the pixel's ANGULAR OFFSET from
    /// the aim ray. Among all cylinders (any r) whose front is at depth
    /// z = dTap and whose silhouette lies AT or BEYOND angular offset θ,
    /// the maximum front-surface deviation at θ is achieved by the cylinder
    /// tangent exactly at θ:  sin θ = r/(z+r) ⇒ r = z·sinθ/(1−sinθ), and the
    /// rise there is √(z² + 2zr) − z = z·(√((1+sinθ)/(1−sinθ)) − 1).
    /// So a pixel at offset p (θ = p/f_axis) whose |depth − dTap| exceeds
    /// that envelope (+ noise margin) provably is NOT on any such trunk —
    /// small offsets get a tight leash (≈ z·θ + margin, millimetres near the
    /// seed) while big close trunks keep their full tangent rise.
    private fun envelopeAllowanceM(dTap: Float, offsetPx: Int, focalPx: Double): Float {
        val s = (offsetPx / focalPx).coerceIn(0.0, 0.95)
        val rise = dTap * (sqrt((1.0 + s) / (1.0 - s)) - 1.0)
        return (rise + ENVELOPE_MARGIN_BASE_M + ENVELOPE_MARGIN_PER_M * dTap).toFloat()
    }

    /// Silhouette walk result: the strip + whether each outward walk found a
    /// real edge INSIDE the image (invalid pixel / per-step jump / envelope
    /// stop). A walk that ran off the image border never located the
    /// silhouette — its "width" is the image extent, not the trunk, so the
    /// row is unusable (border-touch invalidity).
    private data class SilhouetteStrip(
        val indices: List<Int>,
        val leftEdgeFound: Boolean,
        val rightEdgeFound: Boolean,
    ) {
        val bothEdgesFound: Boolean get() = leftEdgeFound && rightEdgeFound
        companion object { val EMPTY = SilhouetteStrip(emptyList(), false, false) }
    }

    /// Silhouette strip walk — port of iOS extractChordSilhouetteStrip plus
    /// two Android additions (ML-depth edges are smooth ramps, not LiDAR
    /// steps): the cylinder-envelope criterion above, and border-touch
    /// flags. Walks outward from the tap seed along the axis; a walk stops
    /// with "edge found" at an invalid pixel (conf 0 / depth ≤ 0), a
    /// per-step jump > silhouetteJumpM, or an envelope violation — and with
    /// "edge NOT found" when it runs off the image border.
    private fun extractChordSilhouetteStrip(
        frame: ArDepthFrame, axis: GuideAxis, tapAlong: Int, silhouetteJumpM: Float = 0.30f,
        tapDepthM: Float = 0f, focalPx: Double = 0.0,
    ): SilhouetteStrip {
        val walkLength = when (axis) {
            is GuideAxis.Row -> { if (axis.y < 0 || axis.y >= frame.height) return SilhouetteStrip.EMPTY; frame.width }
            is GuideAxis.Col -> { if (axis.x < 0 || axis.x >= frame.width) return SilhouetteStrip.EMPTY; frame.height }
        }
        val clampedTap = tapAlong.coerceIn(0, walkLength - 1)
        fun depthAt(idx: Int): Float { val (x, y) = pixelCoords(axis, idx); return frame.depthAt(x, y) }
        fun rawValid(idx: Int): Boolean {
            val (x, y) = pixelCoords(axis, idx)
            return frame.confidenceAt(x, y) >= 1 && frame.depthAt(x, y) > 0f
        }
        var seed = clampedTap
        if (!rawValid(seed)) {
            var found = -1
            for (off in 1..10) {
                val l = clampedTap - off; if (l >= 0 && rawValid(l)) { found = l; break }
                val r = clampedTap + off; if (r < walkLength && rawValid(r)) { found = r; break }
            }
            if (found < 0) return SilhouetteStrip.EMPTY
            seed = found
        }
        val useEnvelope = tapDepthM > 0f && focalPx > 1.0
        fun withinEnvelope(idx: Int): Boolean {
            if (!useEnvelope) return true
            return abs(depthAt(idx) - tapDepthM) <=
                envelopeAllowanceM(tapDepthM, abs(idx - seed), focalPx)
        }
        val indices = ArrayList<Int>(); indices.add(seed)
        var lastDepth = depthAt(seed)
        var leftEdgeFound = false
        var i = seed - 1
        while (i >= 0) {
            if (!rawValid(i) || !withinEnvelope(i)) { leftEdgeFound = true; break }
            val d = depthAt(i)
            if (abs(d - lastDepth) > silhouetteJumpM) { leftEdgeFound = true; break }
            indices.add(i); lastDepth = d; i--
        }
        // (falling out at i == -1 leaves leftEdgeFound == false: border touch)
        lastDepth = depthAt(seed)
        var rightEdgeFound = false
        i = seed + 1
        while (i < walkLength) {
            if (!rawValid(i) || !withinEnvelope(i)) { rightEdgeFound = true; break }
            val d = depthAt(i)
            if (abs(d - lastDepth) > silhouetteJumpM) { rightEdgeFound = true; break }
            indices.add(i); lastDepth = d; i++
        }
        indices.sort()
        return SilhouetteStrip(indices, leftEdgeFound, rightEdgeFound)
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
        /// Clean-row widths from the caller's immediately-preceding ticks
        /// (rolling row quorum). Borrowed ONLY when this tick found at
        /// least one clean row of its own but fewer than the 5-row quorum —
        /// single-tick edge jitter then no longer kills an established fit.
        /// The committed burst (estimateChord) never passes carry.
        carryWidths: List<Int> = emptyList(),
    ): FrameScan {
        val focal = when (axis) {
            is GuideAxis.Row -> frame.fx
            is GuideAxis.Col -> frame.fy
        }
        if (focal <= 0) return FrameScan(null)
        val centerAlong = tapAlongAxis(tapX, tapY, axis)
        val widths = ArrayList<Int>()
        var clippedRows = 0
        // First usable strip extent → the on-screen chord span (iOS
        // `firstUsableExtent`).
        var extentL = -1
        var extentR = -1
        for (offset in -rowSpan..rowSpan) {
            val neighbour = when (axis) {
                is GuideAxis.Row -> GuideAxis.Row(axis.y + offset)
                is GuideAxis.Col -> GuideAxis.Col(axis.x + offset)
            }
            val scan = extractChordSilhouetteStrip(
                frame, neighbour, centerAlong, silhouetteJumpM,
                tapDepthM = dTap, focalPx = focal,
            )
            // Border-touch invalidity: a walk that ran off the image never
            // located the silhouette — the row contributes nothing (its
            // "width" would be the image extent, not the trunk).
            if (scan.indices.isNotEmpty() && !scan.bothEdgesFound) { clippedRows++; continue }
            val strip = scan.indices
            val l = strip.firstOrNull() ?: continue
            val r = strip.lastOrNull() ?: continue
            if (r <= l) continue
            val w = r - l + 1
            if (w < 5) continue
            widths.add(w)
            if (extentL < 0) { extentL = l; extentR = r }
        }
        val ownWidths = widths.toList()
        // Rolling row quorum: the 5-row requirement may be met across this
        // tick + the carry, but ONLY when this tick contributed at least one
        // clean row of its own (never fabricate a fit from stale rows).
        val fitWidths = when {
            widths.size >= 5 -> widths
            widths.isNotEmpty() && widths.size + carryWidths.size >= 5 ->
                ArrayList(widths).apply { addAll(carryWidths) }
            else -> return FrameScan(null, clippedRows, ownWidths)
        }
        fitWidths.sort()
        val medianWidth = fitWidths[fitWidths.size / 2]
        val halfWidth = medianWidth / 2.0
        if (focal - halfWidth <= 1.0) return FrameScan(null, clippedRows, ownWidths)
        val diameterM = medianWidth * dTap.toDouble() / (focal - halfWidth)
        if (diameterM <= 0.0) return FrameScan(null, clippedRows, ownWidths)
        // Width consistency across the row stack — iOS chordPreviewFit's
        // tier input (CoV ≤ 0.10 ⇒ green preview chip).
        val mean = fitWidths.sum().toDouble() / fitWidths.size
        val cov = if (mean > 0) {
            sqrt(fitWidths.sumOf { (it - mean) * (it - mean) } / fitWidths.size) / mean
        } else 1.0
        // Cylinder-overlay anchor — iOS chordPreviewFit `center` parity:
        // back-project the guide row's strip MIDPOINT at its own depth
        // (fallback dTap), then push one radius further along the
        // camera→surface XZ ray — the cylinder AXIS sits one radius behind
        // the visible front face. Because this is the SAME fit the chord
        // bar is drawn from, the rendered cylinder and the bar agree in
        // position and width by construction.
        val centerWorld: Vec3? = if (extentL >= 0 && extentR >= 0) {
            val midIdx = (extentL + extentR) / 2
            val (mpx, mpy) = pixelCoords(axis, midIdx)
            val pixDepth = frame.depthAt(mpx, mpy).toDouble()
            val depthBP = if (pixDepth > 0) pixDepth else dTap.toDouble()
            val surface = BackProjection.worldXZ(
                mpx.toDouble(), mpy.toDouble(), depthBP,
                frame.fx, frame.fy, frame.cx, frame.cy, frame.pose,
            )
            // World Y of the midpoint pixel (worldXZ omits it) — same
            // row-1 dot product convention as BackProjection/Calibration.
            val xc = (mpx - frame.cx) * depthBP / frame.fx
            val yc = (mpy - frame.cy) * depthBP / frame.fy
            val worldY = frame.pose[1] * xc + frame.pose[5] * yc +
                frame.pose[9] * depthBP + frame.pose[13]
            val camX = frame.pose[12].toDouble()
            val camZ = frame.pose[14].toDouble()
            val dx = surface.x - camX
            val dz = surface.y - camZ          // V2.y carries world Z
            val len = sqrt(dx * dx + dz * dz)
            val rM = diameterM / 2.0
            if (len > 1e-6) Vec3(
                (surface.x + dx / len * rM).toFloat(),
                worldY.toFloat(),
                (surface.y + dz / len * rM).toFloat(),
            ) else null
        } else null
        val extent = axisExtent(frame, axis).toFloat().coerceAtLeast(1f)
        return FrameScan(
            FrameChord(
                diameterM,
                if (extentL >= 0) extentL / extent else 0f,
                if (extentR >= 0) extentR / extent else 1f,
                widthCov = cov,
                widthPx = medianWidth,
                centerWorld = centerWorld,
            ),
            clippedRows,
            ownWidths,
        )
    }

    fun livePreview(
        frame: ArDepthFrame, tapX: Double, tapY: Double, axis: GuideAxis, cal: ProjectCalibration,
        algorithm: ChordAlgorithm = ChordAlgorithm.SILHOUETTE,
        /// Preview-layer tap-depth override (aiming robustness): the screen
        /// may seed the walk from its EMA-smoothed distance when the fresh
        /// centre median is a hole or a one-tick outlier, instead of failing
        /// the tick. The committed burst (estimateChord) never overrides.
        dTapOverrideM: Float? = null,
        /// Clean-row widths from the previous 1–2 ticks (rolling quorum).
        carryWidths: List<Int> = emptyList(),
    ): DbhPreview? {
        val dTap = dTapOverrideM ?: medianDepth(tapX, tapY, frame, 2) ?: return null
        if (dTap !in 0.4f..3.5f) return DbhPreview(0f, dTap, false, 0)
        val scan = frameChordDiameterM(frame, tapX, tapY, axis, dTap, cal, algorithm, carryWidths)
        val chord = scan.chord
            ?: return DbhPreview(
                0f, dTap, false, 0,
                edgesClipped = scan.borderClippedRows >= EDGE_CLIP_ROWS_MIN,
                clippedRows = scan.borderClippedRows,
                cleanRows = scan.cleanWidths.size,
                cleanWidths = scan.cleanWidths,
            )
        val locked = chord.diameterM in 0.025..2.0
        val dia = (cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * (chord.diameterM * 100)).toFloat()
        // Preview tier — width consistency, iOS chordPreviewFit parity:
        // CoV ≤ 0.10 ⇒ green (chip shown); otherwise yellow (silent).
        val tier = if (chord.widthCov != null && chord.widthCov <= 0.10) {
            ConfidenceTier.GREEN
        } else {
            ConfidenceTier.YELLOW
        }
        return DbhPreview(
            dia, dTap, locked, 1, chord.leftFrac, chord.rightFrac, tier,
            clippedRows = scan.borderClippedRows, widthPx = chord.widthPx,
            cleanRows = scan.cleanWidths.size, cleanWidths = scan.cleanWidths,
            centerWorld = chord.centerWorld,
        )
    }

    // MARK: - Manual edge-bracket (ADJUST) constrained estimate

    /// The middle half of a bracket, as an inclusive index range along
    /// whatever the caller is stepping (walk-axis pixels, or interpolation
    /// steps between the two mapped handles).
    ///
    /// FIELD REPORT 13 — with the bracket held perfectly still on a trunk,
    /// the diameter jumped by several inches at random. The bracket's z is a
    /// median, the chord identity d = w·z/(f − w/2) is LINEAR in z, and the
    /// median was taken over the WHOLE span. The cruiser puts the handles ON
    /// the silhouette edges, so the span's end samples sit on the boundary
    /// and routinely return the background instead of the stem — several
    /// metres further away in a stand. Whenever the valid samples split near
    /// evenly between stem and background, one sample dropping in or out of
    /// validity moves the median from one cluster to the other, and the
    /// diameter moves with it in exact proportion.
    ///
    /// The middle half cannot be background if the bracket is on a trunk at
    /// all — that is what placing the handles on the edges MEANS — so the
    /// median is taken from stem samples only and the bimodal hop is gone.
    ///
    /// A short temporal median over consecutive frames was the alternative
    /// and is the wrong tool twice over: it slows a hop it can't remove (the
    /// distribution is bimodal in SPACE, and the wrong mode persists for as
    /// long as the cruiser holds still), and ADJUST deliberately publishes
    /// the raw per-frame fit so the number tracks a handle drag immediately.
    ///
    /// Nothing about the geometry changes — same identity, same span, same
    /// focal. Only which samples the depth is read from. iOS
    /// DBHEstimator.bracketCoreRange parity.
    ///
    /// The measured VALUE does move, though, and whoever pools this study's
    /// corpora needs to know it: a stem's centre is up to one radius nearer
    /// than its edges, so a median over the middle half reads a smaller z
    /// and every bracketed diameter comes out slightly lower than before.
    /// That is the geometrically right input for the identity — but it is
    /// not backwards-compatible. A project whose dbhCorrectionAlpha /
    /// dbhCorrectionBeta were fitted on ADJUST captures from before this
    /// change now carries that bias into the correction applied on top, and
    /// a raw-capture bundle recorded before it will not replay to the live
    /// value in its manifest. The manifest's app_commit is what separates
    /// the two corpora.
    fun bracketCoreRange(iLo: Int, iHi: Int): Pair<Int, Int> {
        val span = iHi - iLo
        // Too few samples to trim and still make a median of: a bracket this
        // narrow is a handful of returns either way, and dropping to one or
        // two would fail the >= 3 gate on a fit that is otherwise fine.
        if (span < 8) return iLo to iHi
        val quarter = span / 4
        return (iLo + quarter) to (iHi - quarter)
    }

    /// Widest depth separation, in metres, that a bracket sitting entirely on
    /// bark can produce across its middle half.
    ///
    /// GEOMETRY, not a tuned number. Over the middle half of a chord the stem
    /// surface recedes from its nearest point by r·(1 − cos30°) = 0.134·r, so
    /// even a 1 m stem contributes under 7 cm, and depth noise at bracket
    /// range adds a centimetre or two. 0.20 m is several times anything bark
    /// alone can produce, while the excursions it exists to name are 30–60 cm
    /// of depth — the gap between a stem and whatever stands behind it. iOS
    /// holds the identical value in
    /// `DBHEstimator.bracketCoreDepthSpreadLimitM`.
    const val BRACKET_CORE_DEPTH_SPREAD_LIMIT_M = 0.20

    /// Interquartile depth spread over the ADJUST bracket's middle half, in
    /// metres — READ-ONLY, and never consulted by any estimator.
    ///
    /// FIELD ROUND 10 — THE DIAMETER THAT JUMPS BY INCHES. Over 140 bursts the
    /// median within-burst spread is 0.41 cm here and 0.49 cm on iOS, the
    /// same; iOS carries a tail this platform does not (7 of 68 ADJUST bursts
    /// spanning two inches or more, worst case 26 cm). d = w·z/(f − w/2) is
    /// LINEAR in z, so 26 cm of diameter on a 30 cm stem is z moving by most
    /// of a metre — nothing in a depth return moves that far, but that is
    /// exactly the distance from the bark to what is behind it. Some of the
    /// sample is not bark.
    ///
    /// `bracketCoreRange` already removed the worst of it — the handles sit ON
    /// the silhouette, so the span's END samples read the background — but the
    /// middle half is not immune: a gap between stems, a limb, or foliage seen
    /// through a lean puts a far cluster under the middle too. The median only
    /// MOVES when that cluster reaches half the sample, which is why the
    /// failure is intermittent (about one capture in ten) rather than a bias.
    ///
    /// THE INTERQUARTILE RANGE IS THE RIGHT STATISTIC, precisely because the
    /// median is what has to be defended. A handful of stray far samples
    /// cannot move a median of a dozen and does not widen the IQR either. A
    /// sample split near evenly between two surfaces moves the median on the
    /// next return that flips validity — and puts the two clusters on opposite
    /// sides of the quartiles, which is what a wide IQR reports. min–max would
    /// fire on the single stray and blank a readout that was fine.
    ///
    /// WHAT THIS DELIBERATELY DOES NOT DO is change which samples the
    /// estimator admits. `constrainedEstimate` below is called by BOTH the
    /// live readout and the capture burst, and the estimator is frozen. Nor is
    /// it needed: the stored value is a median of five frames and the
    /// excursions do not reach it (rho = −0.11 between burst spread and error
    /// against tape), so the corpus is intact and the defect is entirely in
    /// what the cruiser sees. This reports; the screen decides.
    ///
    /// Mirrors iOS `DBHEstimator.bracketCoreDepthSpreadM`, and walks the same
    /// interpolated samples `constrainedEstimate` medians so the two can never
    /// describe different pixels.
    fun bracketCoreDepthSpreadM(
        frame: ArDepthFrame,
        leftViewX: Float,
        rightViewX: Float,
        guideViewY: Float,
    ): Double? {
        val pL = frame.viewToDepth(min(leftViewX, rightViewX), guideViewY) ?: return null
        val pR = frame.viewToDepth(max(leftViewX, rightViewX), guideViewY) ?: return null
        val w = max(abs(pR.first - pL.first), abs(pR.second - pL.second))
        if (w < 2.0) return null
        val steps = Math.round(w).toInt().coerceAtLeast(2)
        val depths = ArrayList<Float>(steps + 1)
        val core = bracketCoreRange(0, steps)
        for (i in core.first..core.second) {
            val t = i.toDouble() / steps
            val x = Math.round(pL.first + (pR.first - pL.first) * t).toInt()
            val y = Math.round(pL.second + (pR.second - pL.second) * t).toInt()
            if (x < 0 || x >= frame.width || y < 0 || y >= frame.height) continue
            if (frame.confidenceAt(x, y) < 1) continue
            val d = frame.depthAt(x, y)
            if (d > 0f) depths.add(d)
        }
        // Same floor the fit uses: below it there is no median worth
        // describing, and the fit has already refused.
        if (depths.size < 3) return null
        depths.sort()
        return (depths[(depths.size * 3) / 4] - depths[depths.size / 4]).toDouble()
    }

    /// Constrained estimate for the manual edge-bracket (ADJUST) mode: the
    /// user places the trunk's two silhouette edges as VIEW-space x
    /// positions on the horizontal guide line, so the handle span IS the
    /// width — depth only supplies z. Same pinhole chord identity and
    /// axis-matched focal as the auto silhouette path,
    /// d = w·z/(f_axis − w/2), where w is the span in depth walk-axis
    /// pixels (view x mapped through the view↔depth affine) and z is the
    /// median depth INSIDE the bracket at the guide row. The automatic
    /// edge search never runs here; the auto path is untouched.
    /// Null when the view↔depth mapping is unavailable or no usable depth
    /// exists inside the bracket.
    fun constrainedEstimate(
        frame: ArDepthFrame,
        leftViewX: Float,
        rightViewX: Float,
        guideViewY: Float,
        cal: ProjectCalibration,
    ): DbhPreview? {
        val pL = frame.viewToDepth(min(leftViewX, rightViewX), guideViewY) ?: return null
        val pR = frame.viewToDepth(max(leftViewX, rightViewX), guideViewY) ?: return null
        val dxSpan = abs(pR.first - pL.first)
        val dySpan = abs(pR.second - pL.second)
        // Walk axis = the depth axis the screen-horizontal bracket spans
        // (rotated 90° in portrait); divide by the SAME axis-matched focal
        // as the auto path (fx for a depth-x walk, fy for a depth-y walk).
        val isRowWalk = dxSpan >= dySpan
        val focal = if (isRowWalk) frame.fx else frame.fy
        if (focal <= 1.0) return null
        val w = max(dxSpan, dySpan)
        if (w < 2.0) return null
        // Median depth over the bracket's MIDDLE HALF along the guide row —
        // see bracketCoreRange.
        val steps = Math.round(w).toInt().coerceAtLeast(2)
        val depths = ArrayList<Float>(steps + 1)
        val core = bracketCoreRange(0, steps)
        for (i in core.first..core.second) {
            val t = i.toDouble() / steps
            val x = Math.round(pL.first + (pR.first - pL.first) * t).toInt()
            val y = Math.round(pL.second + (pR.second - pL.second) * t).toInt()
            if (x < 0 || x >= frame.width || y < 0 || y >= frame.height) continue
            if (frame.confidenceAt(x, y) < 1) continue
            val d = frame.depthAt(x, y)
            if (d > 0f) depths.add(d)
        }
        if (depths.size < 3) return null
        depths.sort()
        val z = depths[depths.size / 2]
        // Same null gates as iOS bracketChordFit: bracket depth 0.3–5 m,
        // RAW diameter within PLAUSIBLE_DIAMETER_CM — outside them there is
        // no fit at all.
        if (z !in 0.3f..5.0f) return null
        val halfW = w / 2.0
        if (focal - halfW <= 1.0) return null
        val diameterM = w * z / (focal - halfW)
        val rawCm = diameterM * 100.0
        if (rawCm !in PLAUSIBLE_DIAMETER_CM) return null
        val dia = (cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * rawCm).toFloat()
        // A returned fit IS capturable (the user vouches for the edges) —
        // iOS tap-gate parity. nPoints carries the bracket span in
        // walk-axis px (iOS PreviewFit.inlierCount).
        return DbhPreview(dia, z, locked = true, nPoints = Math.round(w).toInt())
    }

    /// Single-frame ADJUST bracket fit in DEPTH-axis-fraction space — the
    /// cross-platform-canonical manual-bracket primitive (iOS
    /// DBHEstimator.bracketChordFit parity). The two handles are fractions
    /// [0,1] ALONG THE GUIDE AXIS of the depth grid (row → x, col → y); their
    /// span is the silhouette width w in walk-axis pixels and z is the median
    /// depth inside that span at the guide line. Same pinhole chord identity
    /// as the auto path, d = w·z/(f_axis − w/2), axis-matched focal. Returns
    /// the RAW (un-calibrated) diameter (cm) + span, or null on the same
    /// gates iOS uses (bracket depth 0.3–5 m, raw diameter within
    /// PLAUSIBLE_DIAMETER_CM).
    ///
    /// This lives in depth-fraction space (not view-px like constrainedEstimate)
    /// so the raw-capture manifest can store the two handles as view-independent
    /// fractions — byte-identical to the iOS bracket schema (no view size / no
    /// guide_y needed to replay). The live view-space ADJUST UI keeps using
    /// constrainedEstimate; this is the recording/replay-shared entry point.
    data class BracketFit(val diameterCm: Double, val spanPx: Int)

    fun bracketChordFit(
        frame: ArDepthFrame, guideAxis: GuideAxis, leftFraction: Double, rightFraction: Double,
    ): BracketFit? {
        val extent = axisExtent(frame, guideAxis)
        if (extent < 2) return null
        val lo = min(leftFraction, rightFraction)
        val hi = max(leftFraction, rightFraction)
        val a = Math.round(lo * extent).toInt().coerceIn(0, extent - 1)
        val b = Math.round(hi * extent).toInt().coerceIn(0, extent - 1)
        val w = b - a
        if (w < 2) return null
        val focal = when (guideAxis) {
            is GuideAxis.Row -> frame.fx
            is GuideAxis.Col -> frame.fy
        }
        if (focal <= 1.0) return null
        // Bounds check the fixed guide coordinate.
        when (guideAxis) {
            is GuideAxis.Row -> if (guideAxis.y < 0 || guideAxis.y >= frame.height) return null
            is GuideAxis.Col -> if (guideAxis.x < 0 || guideAxis.x >= frame.width) return null
        }
        val depths = ArrayList<Float>(w + 1)
        val core = bracketCoreRange(a, b)
        for (idx in core.first..core.second) {
            val (px, py) = pixelCoords(guideAxis, idx)
            if (px < 0 || px >= frame.width || py < 0 || py >= frame.height) continue
            if (frame.confidenceAt(px, py) < 1) continue
            val d = frame.depthAt(px, py)
            if (d > 0f) depths.add(d)
        }
        if (depths.size < 3) return null
        depths.sort()
        val z = depths[depths.size / 2]
        if (z !in 0.3f..5.0f) return null
        val halfW = w / 2.0
        if (focal - halfW <= 1.0) return null
        val diameterM = w * z.toDouble() / (focal - halfW)
        val rawCm = diameterM * 100.0
        if (rawCm !in PLAUSIBLE_DIAMETER_CM) return null
        return BracketFit(rawCm, w)
    }

    /// ADJUST bracket burst estimate over the stored frames — median of the
    /// per-frame bracket fits, calibration applied, frame-to-frame agreement
    /// grading the tier (iOS bracketChordEstimate parity: range/mean ≤ 15% ⇒
    /// green). The recording + replay share THIS entry point (no forked
    /// bracket math). `leftFraction`/`rightFraction` are guide-axis fractions.
    fun bracketChordEstimate(
        frames: List<ArDepthFrame>,
        guideAxis: GuideAxis,
        leftFraction: Double,
        rightFraction: Double,
        cal: ProjectCalibration,
    ): DBHResult? {
        if (frames.size < 5) return null
        val diameters = ArrayList<Double>(frames.size)
        var spanPxSum = 0
        for (f in frames) {
            val fit = bracketChordFit(f, guideAxis, leftFraction, rightFraction) ?: continue
            diameters.add(fit.diameterCm)
            spanPxSum += fit.spanPx
        }
        if (diameters.size < TierThresholds.MIN_USABLE_FRAMES) {
            return DBHResult(
                diameterCm = 0f, centerX = 0f, centerZ = 0f,
                arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
                nInliers = diameters.size, confidence = ConfidenceTier.RED,
                method = DBHMethod.LIDAR_CHORD_SILHOUETTE,
                rejectionReason = "Not enough usable frames; hold steadier or move closer",
            )
        }
        diameters.sort()
        val medianRawCm = diameters[diameters.size / 2]
        val mean = diameters.average()
        val cov = if (mean > 0) (diameters.last() - diameters.first()) / mean else 1.0
        val diaCm = cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * medianRawCm
        return DBHResult(
            diameterCm = diaCm.toFloat(), centerX = 0f, centerZ = 0f,
            arcCoverageDeg = 0f, rmseMm = 0f, sigmaRmm = 0f,
            nInliers = spanPxSum,
            confidence = if (cov <= TierThresholds.FRAME_SPREAD_GREEN) ConfidenceTier.GREEN else ConfidenceTier.YELLOW,
            method = DBHMethod.LIDAR_CHORD_SILHOUETTE, rejectionReason = null,
        )
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
            return redChord("Stand 0.4–3.5 m from the trunk and capture again.", 0)

        val diameters = ArrayList<Double>(frames.size)
        var clippedFrames = 0
        for (f in frames) {
            val scan = frameChordDiameterM(f, tapX, tapY, axis, dTap, cal, algorithm)
            val c = scan.chord
            if (c != null) diameters.add(c.diameterM)
            else if (scan.borderClippedRows >= EDGE_CLIP_ROWS_MIN) clippedFrames++
        }
        if (diameters.size < TierThresholds.MIN_USABLE_FRAMES) {
            // Distinguish the FRAMING failure (silhouette ran off the image
            // border — trunk edges not visible) from a plain surface miss.
            return if (clippedFrames > frames.size / 2)
                redChord("Can't see both sides of the trunk — step back so the whole trunk is in view.", diameters.size)
            else redChord("Not enough trunk surface; hold steadier / move closer", diameters.size)
        }

        diameters.sort()
        val medianM = diameters[diameters.size / 2]
        val mean = diameters.average()
        val sd = sqrt(diameters.sumOf { (it - mean) * (it - mean) } / diameters.size)
        val cov = if (mean > 0) sd / mean else 1.0
        val diaCm = (cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * (medianM * 100)).toFloat()

        val checks = listOf(
            check(medianM in 0.025..2.0, Severity.REJECT, "Diameter outside 2.5–200 cm"),
            // NOT the same rule as the bracket path above, and NOT the
            // same rule as iOS `chordEstimate`, which grades this method
            // green/yellow at FRAME_SPREAD_GREEN and never reds it. Left
            // exactly as shipped (the estimator is frozen) and deliberately
            // NOT given a shared constant, because sharing a name with the
            // bracket rule would hide that they disagree.
            check(cov <= 0.15, Severity.REJECT,
                "The trunk width kept changing between shots — hold the phone steadier and capture again."),
            check(cov <= 0.08, Severity.WARN, "Per-frame spread 8–15%"),
            check(diameters.size >= maxOf(3, frames.size / 2), Severity.WARN, "Few usable frames"),
        )
        val tier = combineChecks(checks)
        val reason = if (tier == ConfidenceTier.RED)
            (checks.firstOrNull { !it.passed && it.severity == Severity.REJECT }?.reason ?: "This reading didn't pass the accuracy checks — retake it, or accept it and flag the tree.") else null

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
}
