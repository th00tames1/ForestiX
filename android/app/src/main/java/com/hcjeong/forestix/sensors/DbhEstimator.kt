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
import kotlin.math.ceil
import kotlin.math.floor
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
    /// The estimator epoch alpha/beta were FITTED against — iOS
    /// `ProjectCalibration.dbhCalibrationEpoch` parity.
    ///
    /// A cylinder calibration is a line through (raw, true) pairs, so it
    /// absorbs whatever systematic error the estimator had on the day it was
    /// fitted. Change the estimator and beta corrects for something that is
    /// no longer there: the geometry's own correction and the fitted one both
    /// pull the diameter down, they stack, and the project under-reads
    /// silently. 0 means never calibrated, which is safe at any epoch.
    val dbhCalibrationEpoch: Int = 0,
) {
    /// Do these coefficients still answer the estimator that is running?
    val calibrationIsStale: Boolean
        get() = (dbhCorrectionAlpha != 0f || dbhCorrectionBeta != 1f) &&
            dbhCalibrationEpoch != DBHEstimator.ESTIMATOR_EPOCH

    /// The published diameter for a raw one — the ONLY place the cylinder
    /// coefficients may be applied, so no call site can forget the check.
    fun appliedToRawCm(rawCm: Double): Double =
        if (calibrationIsStale) rawCm
        else dbhCorrectionAlpha + dbhCorrectionBeta * rawCm

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
    /// What the estimators compute, as a number that changes when they do —
    /// iOS `DBHEstimator.estimatorEpoch` parity, and the two MUST move
    /// together or a pooled corpus silently mixes geometries.
    ///
    ///   1  chord identity, full-span depth median
    ///   2  chord identity, middle-half depth median (bracketCoreRange)
    ///   3  cylinder-tangent inversion, middle-half median corrected to the
    ///      near face — silhouetteDiameterCm
    /// 4 — the auto path joined the bracket on the tangent form, and the guide
    /// strip stopped truncating at 0.15 m.
    ///
    /// THE NUMBER IS THE CONTRACT. Epoch 3 shipped on 7/30 with the BRACKET on
    /// the tangent inversion and the auto path still on the chord-at-axis form.
    /// Changing the auto path and the walk's depth budget alters an auto
    /// diameter by up to a fifth on a large stem, and leaving the number at 3
    /// would have meant two geometries under one label — with DbhEpochRecompute
    /// then refusing, as "already at epoch 3", precisely the rows that needed
    /// re-deriving.
    ///
    /// iOS `DBHEstimator.estimatorEpoch` parity — the two MUST move together.
    const val ESTIMATOR_EPOCH = 4

    val PLAUSIBLE_DIAMETER_CM = 2.5..300.0

    /// How far behind the near face the middle-half depth median sits, as a
    /// fraction of the radius: 1 - sqrt(15)/4. iOS `medianDepthOffsetFactor`.
    val MEDIAN_DEPTH_OFFSET_FACTOR = 1.0 - sqrt(15.0) / 4.0

    /// A stem's diameter from the width of its silhouette — iOS
    /// `DBHEstimator.silhouetteDiameterCm` parity, same derivation, same
    /// constants. Keep the two in step.
    ///
    /// The bracket's edges are TANGENT points: sight lines graze the bark,
    /// touching it nearer the camera than the axis and closer together than
    /// the full width. With k = w/2f = tan(theta) and z the depth to the near
    /// face, tangency gives R = (z + R) sin(theta), hence
    ///
    ///     d = 2 z k (k + sqrt(k^2 + 1))
    ///
    /// The identity this replaces, d = w z / (f - w/2), is the same
    /// expression with sin swapped for tan; rearranged it reads
    /// d = w (z + d/2) / f, a segment at the stem's AXIS depth, which is not
    /// what a silhouette marks. It over-reads by about k^2/2.
    ///
    /// The caller's z is a median over the bracket's middle half, and those
    /// pixels lie on a curved face, so it sits MEDIAN_DEPTH_OFFSET_FACTOR * R
    /// behind the near point the formula wants. R is the unknown, so solve
    /// rather than iterate: R = z K / (1 + c K), K = k(k + sqrt(k^2+1)).
    ///
    /// Measured on 100 taped stems this removes about a third of the
    /// over-read (Android +9.1 % to +5.6 %, RMSE down 12 %); the remainder is
    /// unexplained, and both forms assume the stem is centred in frame.
    /// How far the guide-strip walk lets the surface recede before it calls
    /// the stem finished, in metres.
    ///
    /// THIS IS A RADIUS BUDGET, not a noise tolerance, and it was set as
    /// though it were one. On a round stem the surface at the silhouette
    /// sits exactly R behind the near face, so a walk that stops at 0.15 m
    /// stops short of the tangent points on anything over 30 cm — and stops
    /// further short the bigger the tree. Traced against a perfect cylinder,
    /// the auto path read -0.9 % at 20 cm, -4.4 % at 40, -11.0 % at 60 and
    /// -21.4 % at 90: not a bias, a taper.
    ///
    /// 0.80 m covers a 160 cm stem, past anything this app will meet in a
    /// coastal-PNW cruise. What stops the walk running onto the next trunk
    /// is NOT this number — it is `depthDiscontinuityM`, a 4 cm jump between
    /// ADJACENT pixels, untouched and doing that job on its own.
    ///
    /// iOS `DBHEstimator.guideStripDepthBudgetM` parity.
    const val GUIDE_STRIP_DEPTH_BUDGET_M = 0.80f

    fun silhouetteDiameterCm(spanPx: Double, depthM: Double, focalPx: Double): Double? {
        if (spanPx <= 0.0 || depthM <= 0.0 || focalPx <= 1.0) return null
        val k = spanPx / (2.0 * focalPx)
        val kk = k * (k + sqrt(k * k + 1.0))
        val radiusM = depthM * kk / (1.0 + MEDIAN_DEPTH_OFFSET_FACTOR * kk)
        if (!radiusM.isFinite() || radiusM <= 0.0) return null
        return 2.0 * radiusM * 100.0
    }


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
                deltaDepth = GUIDE_STRIP_DEPTH_BUDGET_M,
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
        val dbhCm = cal.appliedToRawCm(dbhRawCm)

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
        // Diameter from the silhouette, by the tangent form.
        //
        //   k = w / 2f                      half-angle, from the pinhole
        //   K = k(k + sqrt(k^2 + 1))        = R / z_near, the tangent solution
        //   d = 2 * dTap * K
        //
        // WHAT THIS REPLACED, and why. The old line inverted
        // d = w*dTap/(f - w/2), which puts the edge at the widest point of
        // the circle — half a diameter behind the near face — and calls the
        // depth to THAT the axis distance. It is wrong about where the edge
        // is: a camera sees the TANGENT point, which is nearer and narrower.
        // Every other diameter in this app inverts the tangent form, and
        // this path was the last one that did not, which is why Auto and
        // ADJUST stopped agreeing the day the bracket moved over.
        //
        // The offset term is ZERO here, and that is the difference between
        // the two paths rather than an omission. `silhouetteDiameterCm`
        // carries MEDIAN_DEPTH_OFFSET_FACTOR because the bracket medians
        // depth across the middle half of a curved face, which sits
        // 0.031754 R behind the near face. This path has no such spread:
        // `dTap` is one reading at the tap, on the near face the tangent
        // form already wants.
        //
        // Mirrors iOS `DBHEstimator` chordPreviewFit.
        val halfWidth = medianWidth / 2.0
        if (focal - halfWidth <= 1.0) return FrameScan(null, clippedRows, ownWidths)
        val kAuto = medianWidth / (2.0 * focal)
        val diameterM = 2.0 * dTap.toDouble() * kAuto * (kAuto + sqrt(kAuto * kAuto + 1.0))
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
        val dia = cal.appliedToRawCm(chord.diameterM * 100).toFloat()
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

    // MARK: - Manual edge-bracket (ADJUST) fits
    //
    // TWO ENTRY POINTS, and the split is the whole point of this section.
    //
    //   `bracketDepthGeometry` is the BOUNDARY. It takes the handles where
    //   the cruiser put them — VIEW-space pixels on the guide line — and
    //   converts them ONCE, through the frame's own view→depth affine, into
    //   a walk axis plus two fractions along it.
    //
    //   `bracketChordFit` / `bracketChordEstimate` work purely in DEPTH
    //   space below that boundary, which is also the space the raw-capture
    //   manifest stores, so a recorded bracket replays through exactly the
    //   code that produced it.
    //
    // ONE MEASUREMENT, ONE PATH. The live readout used to carry its own copy
    // of the arithmetic below the boundary: it kept the span fractional and
    // medianed depth along the interpolated line between the two mapped
    // handles, rounding x and y per step, while the recording rounded each
    // handle to a pixel first and medianed a pure row. Across the validation
    // corpus the two records of the SAME bracket differ by a median 1.0028,
    // sd 2.6 %, worst 1.133, with only 38 of 106 inside 1 % — a 160×90 depth
    // grid puts one pixel at ~2.4 % of a 42 px stem, and the two walks touch
    // different pixels on top of that. Nothing a cruiser reads was wrong;
    // the raw-capture corpus and the field log simply were not the same
    // measurement, which is exactly what the study compares.
    //
    // iOS `DBHEstimator.bracketDepthGeometry` draws the same line, and the
    // two platforms keep it in the same place.

    /// The middle half of a bracket, as an inclusive index range on the
    /// walk axis.
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

    /// The bracket as the DEPTH MAP sees it: which axis it walks, and where
    /// its two ends fall along that axis, as fractions of that axis' extent.
    data class BracketAxisSpan(
        val axis: GuideAxis,
        val leftFraction: Double,
        val rightFraction: Double,
    )

    /// Convert the two view-space handles on the guide line into the depth
    /// map's own terms — ONCE, here, for every ADJUST caller.
    ///
    /// The walk axis is whichever depth axis the screen-horizontal bracket
    /// covers more of; the two sit 90° apart in portrait. It comes from the
    /// display transform the affine carries, which is stable per frame,
    /// rather than from a vote on depth content, which is not.
    ///
    /// Null when the frame carries no view mapping. Fail closed: a view span
    /// and a depth span do not share a scale, so there is no 1:1 fallback to
    /// reach for. iOS `DBHEstimator.bracketDepthGeometry` parity.
    fun bracketDepthGeometry(
        frame: ArDepthFrame,
        leftViewX: Float,
        rightViewX: Float,
        guideViewY: Float,
    ): BracketAxisSpan? {
        if (frame.width < 2 || frame.height < 2) return null
        val pL = frame.viewToDepth(min(leftViewX, rightViewX), guideViewY) ?: return null
        val pR = frame.viewToDepth(max(leftViewX, rightViewX), guideViewY) ?: return null
        val rowWalk = abs(pR.first - pL.first) >= abs(pR.second - pL.second)
        val midX = (pL.first + pR.first) / 2.0
        val midY = (pL.second + pR.second) / 2.0
        val axis: GuideAxis = if (rowWalk) {
            GuideAxis.Row(Math.round(midY).toInt().coerceIn(0, frame.height - 1))
        } else {
            GuideAxis.Col(Math.round(midX).toInt().coerceIn(0, frame.width - 1))
        }
        val extent = axisExtent(frame, axis).toDouble()
        val a = (if (rowWalk) pL.first else pL.second) / extent
        val b = (if (rowWalk) pR.first else pR.second) / extent
        val lo = min(a, b)
        val hi = max(a, b)
        if (!lo.isFinite() || !hi.isFinite() || hi <= lo) return null
        return BracketAxisSpan(axis, lo, hi)
    }

    /// The bracket's index arithmetic on the walk axis, in ONE place, so the
    /// fit and the read-only spread probe can only ever ask about the same
    /// pixels. Pure index math — no depth is read here.
    ///
    /// The span stays FRACTIONAL. The handles are continuous positions on
    /// the depth axis, and rounding each to a pixel before subtracting costs
    /// up to a whole pixel of width — on a stem 42 px across a 160-px grid
    /// that is 2.4 % of the diameter. Only the SAMPLE range is integral, and
    /// it is the pixels lying inside the span.
    ///
    /// iOS `DBHEstimator.bracketGeometry` parity.
    data class BracketGeometry(
        val lo: Double,
        val hi: Double,
        val widthPx: Double,
        val iLo: Int,
        val iHi: Int,
    )

    fun bracketGeometry(
        frame: ArDepthFrame,
        guideAxis: GuideAxis,
        leftFraction: Double,
        rightFraction: Double,
    ): BracketGeometry? {
        when (guideAxis) {
            is GuideAxis.Row -> if (guideAxis.y < 0 || guideAxis.y >= frame.height) return null
            is GuideAxis.Col -> if (guideAxis.x < 0 || guideAxis.x >= frame.width) return null
        }
        val extent = axisExtent(frame, guideAxis)
        if (extent < 2) return null
        val lo = min(leftFraction, rightFraction)
        val hi = max(leftFraction, rightFraction)
        val leftPx = lo * extent
        val rightPx = hi * extent
        val widthPx = rightPx - leftPx
        if (!widthPx.isFinite() || widthPx < 2.0) return null
        val iLo = max(0, ceil(leftPx).toInt())
        val iHi = min(extent - 1, floor(rightPx).toInt())
        if (iHi < iLo) return null
        return BracketGeometry(lo, hi, widthPx, iLo, iHi)
    }

    /// The valid depths over the bracket's middle half, sorted ascending —
    /// the exact sample the fit takes its median from, so the probe below
    /// describes the fit's own pixels rather than a second copy of them.
    /// iOS `DBHEstimator.bracketCoreDepthsSorted` parity.
    fun bracketCoreDepthsSorted(
        frame: ArDepthFrame,
        guideAxis: GuideAxis,
        iLo: Int,
        iHi: Int,
    ): List<Float> {
        val depths = ArrayList<Float>(max(0, iHi - iLo + 1))
        val core = bracketCoreRange(iLo, iHi)
        for (idx in core.first..core.second) {
            val (px, py) = pixelCoords(guideAxis, idx)
            if (px < 0 || px >= frame.width || py < 0 || py >= frame.height) continue
            if (frame.confidenceAt(px, py) < 1) continue
            val d = frame.depthAt(px, py)
            if (d > 0f) depths.add(d)
        }
        depths.sort()
        return depths
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
    /// Mirrors iOS `DBHEstimator.bracketCoreDepthSpreadM`, and reaches the
    /// samples through the same `bracketDepthGeometry` / `bracketGeometry` /
    /// `bracketCoreDepthsSorted` the fit medians, so the two can never
    /// describe different pixels.
    fun bracketCoreDepthSpreadM(
        frame: ArDepthFrame,
        leftViewX: Float,
        rightViewX: Float,
        guideViewY: Float,
    ): Double? {
        val span = bracketDepthGeometry(frame, leftViewX, rightViewX, guideViewY) ?: return null
        val g = bracketGeometry(frame, span.axis, span.leftFraction, span.rightFraction)
            ?: return null
        val depths = bracketCoreDepthsSorted(frame, span.axis, g.iLo, g.iHi)
        // Same floor the fit uses: below it there is no median worth
        // describing, and the fit has already refused.
        if (depths.size < 3) return null
        return (depths[(depths.size * 3) / 4] - depths[depths.size / 4]).toDouble()
    }

    /// The VIEW-space entry to the manual edge-bracket (ADJUST) mode: the
    /// cruiser places the trunk's two silhouette edges as x positions on the
    /// horizontal guide line, so the handle span IS the width and depth only
    /// supplies z. The automatic edge search never runs here; the auto path
    /// is untouched.
    ///
    /// This function is the boundary crossing and nothing else — the handles
    /// become a depth walk axis and two fractions, and `bracketChordFit`
    /// does the measuring. The calibration is applied to what comes back,
    /// which is the one thing the live readout does that the recorded fit
    /// does not (the corpus stores raw).
    ///
    /// Null when the view↔depth mapping is unavailable or the fit refuses.
    fun constrainedEstimate(
        frame: ArDepthFrame,
        leftViewX: Float,
        rightViewX: Float,
        guideViewY: Float,
        cal: ProjectCalibration,
    ): DbhPreview? {
        val span = bracketDepthGeometry(frame, leftViewX, rightViewX, guideViewY) ?: return null
        val fit = bracketChordFit(frame, span.axis, span.leftFraction, span.rightFraction)
            ?: return null
        val dia = cal.appliedToRawCm(fit.diameterCm).toFloat()
        // A returned fit IS capturable (the user vouches for the edges) —
        // iOS tap-gate parity. nPoints carries the bracket span in
        // walk-axis px (iOS PreviewFit.inlierCount).
        return DbhPreview(dia, fit.depthM.toFloat(), locked = true, nPoints = fit.spanPx)
    }

    /// Single-frame ADJUST bracket fit in DEPTH-axis-fraction space — the
    /// one manual-bracket measurement on this platform, and the
    /// cross-platform-canonical one (iOS `DBHEstimator.bracketChordFit`
    /// parity). The live readout reaches it through `constrainedEstimate`,
    /// the recording and the replay call it directly.
    ///
    /// The two handles are fractions [0,1] ALONG THE GUIDE AXIS of the depth
    /// grid (row → x, col → y); their span is the silhouette width w in
    /// walk-axis pixels and z is the median depth inside that span at the
    /// guide line. `silhouetteDiameterCm` inverts the tangent form on that
    /// pair. Returns the RAW (un-calibrated) diameter (cm), the span rounded
    /// to whole pixels, and the depth the fit read — or null on the same
    /// gates iOS uses (bracket depth 0.3–5 m, raw diameter within
    /// PLAUSIBLE_DIAMETER_CM).
    ///
    /// Fractions, not view pixels, because that is what the raw-capture
    /// manifest stores: view-independent, byte-identical to the iOS bracket
    /// schema, so replaying a bundle needs neither view size nor guide_y.
    ///
    /// FOCAL CHOICE: the axis-matched focal (fx for a row walk, fy for a
    /// column walk), which is what the auto path does and what the Android
    /// depth grid requires — its texture-registered intrinsics have fx ≠ fy
    /// whenever the depth aspect differs from the texture aspect. iOS
    /// divides by fx on both axes and records that divergence in its own
    /// `bracketChordFit`; settling it is a device-and-tape job on both
    /// platforms at once.
    data class BracketFit(val diameterCm: Double, val spanPx: Int, val depthM: Double)

    fun bracketChordFit(
        frame: ArDepthFrame, guideAxis: GuideAxis, leftFraction: Double, rightFraction: Double,
    ): BracketFit? {
        val g = bracketGeometry(frame, guideAxis, leftFraction, rightFraction) ?: return null
        val focal = when (guideAxis) {
            is GuideAxis.Row -> frame.fx
            is GuideAxis.Col -> frame.fy
        }
        if (focal <= 1.0) return null
        // Median depth over the bracket's MIDDLE HALF along the guide line —
        // see bracketCoreRange.
        val depths = bracketCoreDepthsSorted(frame, guideAxis, g.iLo, g.iHi)
        if (depths.size < 3) return null
        val z = depths[depths.size / 2]
        if (z !in 0.3f..5.0f) return null
        if (focal - g.widthPx / 2.0 <= 1.0) return null
        val rawCm = silhouetteDiameterCm(g.widthPx, z.toDouble(), focal) ?: return null
        if (rawCm !in PLAUSIBLE_DIAMETER_CM) return null
        return BracketFit(rawCm, Math.round(g.widthPx).toInt(), z.toDouble())
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
        val diaCm = cal.appliedToRawCm(medianRawCm)
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
        val diaCm = cal.appliedToRawCm(medianM * 100).toFloat()

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
