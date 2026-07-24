// Raw-capture REPLAY engine — the pure, UI-shared path that reconstructs a
// stored bundle's estimator inputs from disk and re-runs the CURRENT
// production estimators over them. No forked math: DBH goes through
// DBHEstimator.estimateChord / bracketChordEstimate, height through
// HeightEstimator.estimate — the exact entry points the live scan screens
// call. This is what makes "re-run updated estimator code on field data"
// meaningful: the only thing that changes between the live number and a
// replay is the estimator version, never the reconstruction.
//
// The u16-mm depth bytes are re-ingested through ArDepthFrame.ingestMmInto
// (the single shared rule the live capture path uses), so a reconstructed
// frame is bit-identical to the one the estimator consumed in the field.

package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.ar.Vec3
import org.json.JSONObject
import java.io.File
import java.nio.ByteOrder
import kotlin.math.tan

object RawCaptureReplay {

    /// DBH replay outcome. `value` is the current estimator's diameter (cm)
    /// over the stored frames; `perFrame` the per-frame chord diameters.
    data class DbhReplay(
        val value: Double,
        val sigmaMm: Double,
        val tier: String,
        val perFrame: List<Double>,
        val rejectionReason: String?,
    )

    /// Height replay outcome — BOTH the stored-geometry re-run (`value`,
    /// anchor + locked standing pose) AND the pose-reposed re-run
    /// (`valueReposed`, standing taken from the last 5 Hz pose sample) so a
    /// reviewer can see VIO drift between the two.
    data class HeightReplay(
        val value: Double,
        val valueReposed: Double,
        val sigmaM: Double,
        val tier: String,
        val rejectionReason: String?,
    )

    // MARK: - Frame reconstruction

    /// Rebuild the exact ArDepthFrame list the DBH estimator consumed:
    /// re-ingest each depth_N.bin (little-endian u16 mm, row-major) through
    /// the shared ingest rule and attach the stored intrinsics / pose /
    /// view→depth affine. Skips any frame whose .bin is missing or the wrong
    /// size (defensive — a truncated bundle replays what it can).
    fun reconstructDbhFrames(dir: File, manifest: JSONObject): List<ArDepthFrame> {
        val dbh = manifest.optJSONObject("dbh") ?: return emptyList()
        val frames = dbh.optJSONArray("frames") ?: return emptyList()
        val out = ArrayList<ArDepthFrame>(frames.length())
        for (i in 0 until frames.length()) {
            val fo = frames.optJSONObject(i) ?: continue
            val w = fo.optInt("width")
            val h = fo.optInt("height")
            if (w <= 0 || h <= 0) continue
            val depthFile = File(dir, fo.optString("depth_file"))
            val shorts = readU16Le(depthFile, w * h) ?: continue
            val depth = FloatArray(w * h)
            val conf = ByteArray(w * h)
            for (idx in 0 until w * h) {
                ArDepthFrame.ingestMmInto(shorts[idx].toInt() and 0xFFFF, idx, depth, conf)
            }
            val pose = jsonToFloatArray(fo.optJSONArray("camera_pose"), 16) ?: continue
            val affine = jsonToFloatArray(fo.optJSONArray("view_to_depth"), 6)
            out.add(
                ArDepthFrame(
                    w, h, depth, conf,
                    fo.optDouble("fx"), fo.optDouble("fy"),
                    fo.optDouble("cx"), fo.optDouble("cy"),
                    pose, depthFromViewAffine = affine,
                ),
            )
        }
        return out
    }

    // MARK: - DBH re-run (shared production entry points)

    fun rerunDbh(dir: File, manifest: JSONObject): DbhReplay? {
        val frames = reconstructDbhFrames(dir, manifest)
        if (frames.isEmpty()) return null
        val dbh = manifest.optJSONObject("dbh") ?: return null
        val settings = manifest.optJSONObject("settings")
        val cal = calibrationFrom(settings)
        val algorithmTag = settings?.optString("algorithm") ?: "silhouette"
        val f0 = dbh.optJSONArray("frames")?.optJSONObject(0)

        // Tap + axis round-trip exactly from the stored per-frame metadata
        // (pickGuideAxis derives the fixed row/col from the tap, so
        // tap + "row"/"col" reconstructs the same GuideAxis — iOS parity).
        val tapX = f0?.optJSONArray("tap_px")?.optDouble(0) ?: (frames.first().width / 2.0)
        val tapY = f0?.optJSONArray("tap_px")?.optDouble(1) ?: (frames.first().height / 2.0)
        val axis: GuideAxis = if (f0?.optString("axis") == "col") {
            GuideAxis.Col(Math.round(tapX).toInt())
        } else {
            GuideAxis.Row(Math.round(tapY).toInt())
        }

        val bracket = dbh.optJSONObject("bracket")
        if (bracket != null && bracket.optBoolean("enabled")) {
            // Bracket handles are guide-axis fractions (view-independent).
            val left = bracket.optDouble("left")
            val right = bracket.optDouble("right")
            val res = DBHEstimator.bracketChordEstimate(frames, axis, left, right, cal) ?: return null
            val perFrame = frames.mapNotNull {
                DBHEstimator.bracketChordFit(it, axis, left, right)?.diameterCm
            }
            return DbhReplay(
                res.diameterCm.toDouble(), res.sigmaRmm.toDouble(),
                res.confidence.raw, perFrame, res.rejectionReason,
            )
        }

        // Auto path. "arc" → the §7.1 partial-arc pipeline; otherwise the
        // chord method (silhouette / depth-band).
        if (algorithmTag == "arc") {
            val input = DbhScanInput(frames, tapX, tapY, axis, cal)
            val res = DBHEstimator.estimate(input) ?: return null
            return DbhReplay(
                res.diameterCm.toDouble(), res.sigmaRmm.toDouble(),
                res.confidence.raw, emptyList(), res.rejectionReason,
            )
        }
        val algorithm = ChordAlgorithm.fromRaw(if (algorithmTag == "depthBand") "band" else "silhouette")
        val res = DBHEstimator.estimateChord(frames, tapX, tapY, axis, cal, algorithm) ?: return null
        val perFrame = frames.mapNotNull {
            DBHEstimator.livePreview(it, tapX, tapY, axis, cal, algorithm)
                ?.takeIf { p -> p.locked }?.diameterCm?.toDouble()
        }
        return DbhReplay(
            res.diameterCm.toDouble(), res.sigmaRmm.toDouble(),
            res.confidence.raw, perFrame, res.rejectionReason,
        )
    }

    // MARK: - DBH multi-algorithm sweep + candidate registry

    /// Reconstructed replay inputs shared by every candidate geometry — the
    /// SAME depth+intrinsics+pose frames plus the stored tap / guide axis /
    /// calibration / manual bracket. Built ONCE per capture so a sweep runs
    /// all candidates over identical bytes.
    data class DbhSweepInput(
        val frames: List<ArDepthFrame>,
        val tapX: Double,
        val tapY: Double,
        val axis: GuideAxis,
        val cal: ProjectCalibration,
        /// Guide-axis fractions of the manual ADJUST bracket, or null when the
        /// capture is an auto burst (no bracket) — the bracket-chord candidate
        /// is N/A for those.
        val bracket: Pair<Double, Double>?,
    )

    /// One candidate DBH geometry the sweep runs. `id` is a stable slug (also
    /// the manifest `settings.algorithm` tag where one exists); `run` produces
    /// an estimated diameter (cm) from the shared inputs, or null when this
    /// capture lacks what the algorithm needs (e.g. bracket-chord on an auto
    /// capture) or the fit didn't lock. ADD A NEW ALGORITHM by adding ONE
    /// entry here — the sweep, ranking, and UI pick it up automatically.
    class DbhCandidate(
        val id: String,
        val label: String,
        val run: (DbhSweepInput) -> Double?,
    )

    /// THE candidate registry (cross-platform seed set: the four existing
    /// geometries). Order is the table's default/tie-break order.
    val DBH_CANDIDATES: List<DbhCandidate> = listOf(
        DbhCandidate("silhouette", "Chord / silhouette") { i ->
            DBHEstimator.estimateChord(
                i.frames, i.tapX, i.tapY, i.axis, i.cal, ChordAlgorithm.SILHOUETTE,
            )?.diameterCm?.toDouble()
        },
        DbhCandidate("arc", "Partial-arc circle fit") { i ->
            DBHEstimator.estimate(
                DbhScanInput(i.frames, i.tapX, i.tapY, i.axis, i.cal),
            )?.diameterCm?.toDouble()
        },
        DbhCandidate("depthBand", "Depth-band") { i ->
            DBHEstimator.estimateChord(
                i.frames, i.tapX, i.tapY, i.axis, i.cal, ChordAlgorithm.DEPTH_BAND,
            )?.diameterCm?.toDouble()
        },
        DbhCandidate("bracketChord", "Manual bracket-chord") { i ->
            val (left, right) = i.bracket ?: return@DbhCandidate null   // N/A: no manual bracket
            DBHEstimator.bracketChordEstimate(i.frames, i.axis, left, right, i.cal)
                ?.diameterCm?.toDouble()
        },
    )

    /// Per-capture sweep result: every candidate's estimate over one bundle's
    /// stored bytes. `estimates[id]` is null when that algorithm is N/A for
    /// this capture (missing input or no usable fit — a non-finite / ≤0
    /// diameter is treated as no fit, not a wildly-wrong estimate).
    data class DbhSweep(
        val truth: Double?,
        val liveValue: Double?,
        val estimates: Map<String, Double?>,
    )

    /// Rebuild the shared sweep inputs from a DBH bundle (frames + tap + axis
    /// + calibration + optional bracket). Null when the bundle can't be
    /// reconstructed. Mirrors rerunDbh's reconstruction WITHOUT dispatching on
    /// a single stored algorithm — that is what lets one capture feed every
    /// candidate. rerunDbh (and its self-check) is left untouched.
    private fun dbhSweepInput(dir: File, manifest: JSONObject): DbhSweepInput? {
        val frames = reconstructDbhFrames(dir, manifest)
        if (frames.isEmpty()) return null
        val dbh = manifest.optJSONObject("dbh") ?: return null
        val cal = calibrationFrom(manifest.optJSONObject("settings"))
        val f0 = dbh.optJSONArray("frames")?.optJSONObject(0)
        val tapX = f0?.optJSONArray("tap_px")?.optDouble(0) ?: (frames.first().width / 2.0)
        val tapY = f0?.optJSONArray("tap_px")?.optDouble(1) ?: (frames.first().height / 2.0)
        val axis: GuideAxis = if (f0?.optString("axis") == "col") {
            GuideAxis.Col(Math.round(tapX).toInt())
        } else {
            GuideAxis.Row(Math.round(tapY).toInt())
        }
        val bracketJson = dbh.optJSONObject("bracket")
        val bracket = if (bracketJson != null && bracketJson.optBoolean("enabled")) {
            bracketJson.optDouble("left") to bracketJson.optDouble("right")
        } else {
            null
        }
        return DbhSweepInput(frames, tapX, tapY, axis, cal, bracket)
    }

    /// Run EVERY candidate geometry over a single DBH capture's stored
    /// depth+intrinsics+pose bytes and return {algorithmId -> estimate (cm)}.
    /// A candidate that can't run on this capture is null (N/A), never a
    /// failure that aborts the sweep. Non-DBH bundles return null.
    fun sweepDbh(dir: File, manifest: JSONObject): DbhSweep? {
        if (manifest.optString("kind") != "dbh") return null
        val input = dbhSweepInput(dir, manifest) ?: return null
        val estimates = LinkedHashMap<String, Double?>(DBH_CANDIDATES.size)
        for (c in DBH_CANDIDATES) {
            val v = try { c.run(input) } catch (_: Throwable) { null }
            estimates[c.id] = v?.takeIf { it.isFinite() && it > 0.0 }
        }
        val truth = manifest.optJSONObject("truth")?.optDouble("value")?.takeIf { !it.isNaN() }
        val live = manifest.optJSONObject("result_live")?.optDouble("value")?.takeIf { !it.isNaN() }
        return DbhSweep(truth, live, estimates)
    }

    // MARK: - Height re-run (shared production entry point)

    fun rerunHeight(manifest: JSONObject): HeightReplay? {
        val height = manifest.optJSONObject("height") ?: return null
        val anchor = height.optJSONObject("anchor") ?: return null
        val base = height.optJSONObject("base") ?: return null
        val top = height.optJSONObject("top") ?: return null
        val anchorWorld = anchor.optJSONArray("world") ?: return null
        val anchorX = anchorWorld.optDouble(0).toFloat()
        val anchorZ = anchorWorld.optDouble(2).toFloat()
        val basePose = jsonToFloatArray(base.optJSONArray("camera_pose"), 16) ?: return null
        val alphaBase = Math.toRadians(base.optDouble("pitch_deg")).toFloat()
        val alphaTop = Math.toRadians(top.optDouble("pitch_deg")).toFloat()
        val cal = calibrationFrom(manifest.optJSONObject("settings"))

        // Stored-geometry re-run: anchor + the standing pose locked at base
        // aim (camera_pose translation = standing X/Z).
        val standingX = basePose[12]
        val standingZ = basePose[14]
        val res = HeightEstimator.estimate(
            anchorX = anchorX, anchorZ = anchorZ,
            standingX = standingX, standingZ = standingZ,
            alphaTopRad = alphaTop, alphaBaseRad = alphaBase,
            vioDriftFraction = cal.vioDriftFraction,
        )

        // Pose-reposed re-run: standing taken from the LAST 5 Hz pose sample
        // (compute-time camera pose) — surfaces VIO drift over the walk.
        val samples = height.optJSONArray("pose_samples")
        val lastPose = samples?.let {
            if (it.length() == 0) null
            else jsonToFloatArray(it.optJSONObject(it.length() - 1)?.optJSONArray("pose"), 16)
        }
        val reposed = if (lastPose != null) {
            HeightEstimator.estimate(
                anchorX = anchorX, anchorZ = anchorZ,
                standingX = lastPose[12], standingZ = lastPose[14],
                alphaTopRad = alphaTop, alphaBaseRad = alphaBase,
                vioDriftFraction = cal.vioDriftFraction,
            ).heightM.toDouble()
        } else {
            res.heightM.toDouble()
        }

        return HeightReplay(
            res.heightM.toDouble(), reposed, res.sigmaHm.toDouble(),
            res.confidence.raw, res.rejectionReason,
        )
    }

    // MARK: - Helpers

    /// Re-run the estimator for whichever kind this manifest is, returning
    /// the single scalar value the summary / re-run-all compares against
    /// truth. Null when the bundle can't be replayed.
    fun rerunValue(dir: File, manifest: JSONObject): Double? = when (manifest.optString("kind")) {
        "dbh" -> rerunDbh(dir, manifest)?.value
        "height" -> rerunHeight(manifest)?.value
        else -> null
    }

    private fun calibrationFrom(settings: JSONObject?): ProjectCalibration {
        val cal = settings?.optJSONObject("calibration") ?: return ProjectCalibration.identity
        return ProjectCalibration(
            depthNoiseMm = cal.optDouble("depth_noise_mm", 5.0).toFloat(),
            dbhCorrectionAlpha = cal.optDouble("alpha", 0.0).toFloat(),
            dbhCorrectionBeta = cal.optDouble("beta", 1.0).toFloat(),
            vioDriftFraction = cal.optDouble(
                "vio_drift_fraction", HeightEstimator.DEFAULT_VIO_DRIFT_FRACTION.toDouble()).toFloat(),
        )
    }

    private fun jsonToFloatArray(arr: org.json.JSONArray?, expected: Int): FloatArray? {
        if (arr == null || arr.length() != expected) return null
        val out = FloatArray(expected)
        for (i in 0 until expected) out[i] = arr.optDouble(i).toFloat()
        return out
    }

    /// Read a little-endian u16 grid of exactly `count` samples, or null if
    /// the file is missing / the wrong length.
    private fun readU16Le(file: File, count: Int): ShortArray? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size != count * 2) return null
        val buf = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val out = ShortArray(count)
        buf.get(out)
        return out
    }

    /// Ground-plane tangent height straight from stored scalars (pitch +
    /// d_h) — used only for the detail screen's "stored inputs" readout, not
    /// the estimator path. Kept here so the two height numbers share a file.
    fun tangentHeight(dHm: Double, alphaTopRad: Double, alphaBaseRad: Double): Double =
        dHm * (tan(alphaTopRad) - tan(alphaBaseRad))

    // Vec3 re-export convenience for callers that build anchors from JSON.
    fun worldVec(arr: org.json.JSONArray?): Vec3? {
        if (arr == null || arr.length() != 3) return null
        return Vec3(arr.optDouble(0).toFloat(), arr.optDouble(1).toFloat(), arr.optDouble(2).toFloat())
    }
}
