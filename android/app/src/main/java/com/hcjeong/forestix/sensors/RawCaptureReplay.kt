// Raw-capture REPLAY engine — the pure, UI-shared path that reconstructs a
// stored bundle's estimator inputs from disk and re-runs the CURRENT
// production estimators over them. No forked math: DBH goes through
// DBHEstimator.estimateChord / bracketChordEstimate, height through
// HeightEstimator.estimate — the exact entry points the live scan screens
// call. This is what makes "re-run updated estimator code on field data"
// meaningful: the only thing that changes between the live number and a
// replay is the estimator version, never the reconstruction.
//
// Legacy u16-mm bytes use the live ingestMmInto rule; f32m fallback is read
// without quantization. Explicit confidence and modern checksummed inputs
// are never replaced by confidence derived from depth.

package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.ar.Vec3
import org.json.JSONObject
import java.io.File
import kotlin.math.tan

object RawCaptureReplay {
    /** Independent shadow comparison; does not change production replay or
     * enter the calibration/winner registry with experimental algorithms. */
    fun replayDepthComparison(dir:File,manifest:JSONObject)=manifest.optJSONObject("dbh_depth_comparison")?.let{
        DbhDepthComparisonIO.replay(dir,manifest.getString("measurement_id"),
            kotlinx.serialization.json.Json.parseToJsonElement(it.toString()) as kotlinx.serialization.json.JsonObject)
            .also{report->DbhDepthComparisonIO.requireOriginalAssociation(report,
                RawCaptureEvidence.parse(manifest.getJSONObject("capture_evidence").toString()))}
    }
    fun rerunCapturedDbh(dir:File,manifest:JSONObject):Double? {
        if(!manifest.has("capture_evidence")||manifest.isNull("capture_evidence"))return null
        return rerunDbh(dir,manifest,modernDbhFrames(dir,manifest))?.value
    }
    private fun modernDbhFrames(dir:File,manifest:JSONObject):List<ArDepthFrame> {
        val e=RawCaptureEvidence.parse(manifest.getJSONObject("capture_evidence").toString())
        val descriptors=manifest.optJSONObject("dbh")?.optJSONArray("frames")
        require(descriptors!=null){"Recorded DBH descriptors"}
        require(e.frames.withIndex().all{(i,f)->descriptors.optJSONObject(i)?.optString("depth_input_policy",DbhMeasurementDepth.LEGACY_FULL)==f.depthInputPolicy}){"Recorded input policy identity"}
        val dimensions=(0 until descriptors.length()).map{val d=descriptors.getJSONObject(it);d.getInt("width") to d.getInt("height")}
        return restoreOriginalDbhFrames(dir,manifest.optString("measurement_id"),e,dimensions)
    }
    internal fun restoreOriginalDbhFrames(dir:File,id:String,e:RawCaptureEvidence,dimensions:List<Pair<Int,Int>>):List<ArDepthFrame> {
        require(e.measurementId==id&&e.frames.size in 1..32&&e.frames.size==dimensions.size&&
            e.frames.withIndex().all{(i,f)->f.role=="dbh_$i"&&f.width==dimensions[i].first&&f.height==dimensions[i].second}){"Recorded DBH input identity"}
        return e.frames.map{RawCaptureEvidenceIO.readFrame(it,dir)}
    }
    fun rerunCapturedHeight(manifest:JSONObject):Double? {
        val text=manifest.optJSONObject("capture_evidence")?.toString()?:return null
        val e=RawCaptureEvidence.parse(text);require(e.measurementId==manifest.optString("measurement_id")){"Recorded height identity"}
        val v=e.scalars
        require(listOf("alpha_base_rad","alpha_top_rad","anchor_x","anchor_z","standing_x","standing_z").all{it in v}){"Recorded height scalars"}
        return HeightEstimator.estimate(v.getValue("anchor_x").toFloat(),v.getValue("anchor_z").toFloat(),
            v.getValue("standing_x").toFloat(),v.getValue("standing_z").toFloat(),v.getValue("alpha_top_rad").toFloat(),
            v.getValue("alpha_base_rad").toFloat(),calibrationFrom(manifest.optJSONObject("settings")).vioDriftFraction).heightM.toDouble()
    }

    /// Enough scored captures for a "best algorithm" claim to mean anything.
    /// Below this the ranking is shown but no winner is crowned — an RMSE
    /// ordering over a handful of trees is noise. Identical value on iOS.
    const val MIN_RANKING_N = 5

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
        /// Re-derived σ_H — null when the stored geometry admits none
        /// (degenerate d_h, inverted aims), never a stand-in 0.
        val sigmaM: Double?,
        val tier: String,
        val rejectionReason: String?,
    )

    // MARK: - Frame reconstruction

    /// Rebuild every ordered frame or refuse the bundle. A partial subset
    /// cannot stand in for the original burst. Historical confidence is
    /// derived only when confidence_file was never recorded.
    fun reconstructDbhFrames(dir: File, manifest: JSONObject): List<ArDepthFrame> {
        // Prefer the exact, checksummed input, including known depth age.
        // Malformed modern evidence must NEVER fall back to age=0 legacy bytes.
        val original=if(manifest.has("capture_evidence")&&!manifest.isNull("capture_evidence"))
            runCatching{modernDbhFrames(dir,manifest)}.getOrElse{return emptyList()} else null
        val dbh = manifest.optJSONObject("dbh") ?: return emptyList()
        val frames = dbh.optJSONArray("frames") ?: return emptyList()
        if(frames.length() !in 1..32)return emptyList()
        if(original==null&&(0 until frames.length()).any{frames.optJSONObject(it)?.optString("depth_input_policy",DbhMeasurementDepth.LEGACY_FULL)!=DbhMeasurementDepth.LEGACY_FULL})
            return emptyList() // New policy cannot be replayed as age-free dense input.
        val out = ArrayList<ArDepthFrame>(frames.length())
        for (i in 0 until frames.length()) {
            val fo = frames.optJSONObject(i) ?: return emptyList()
            val w = fo.optInt("width")
            val h = fo.optInt("height")
            val grid=runCatching{RawDepthGridIO.read(dir,w,h,fo.optString("format"),fo.optString("depth_file"),
                if(fo.has("confidence_file")&&!fo.isNull("confidence_file"))fo.getString("confidence_file")else null)}.getOrNull()?:return emptyList()
            val pose = jsonToFloatArray(fo.optJSONArray("camera_pose"), 16) ?: return emptyList()
            val affine = jsonToFloatArray(fo.optJSONArray("view_to_depth"), 6)
            if(!pose.all{it.isFinite()}||!listOf("fx","fy","cx","cy").all{fo.optDouble(it).isFinite()}||
                (fo.has("view_to_depth")&&!fo.isNull("view_to_depth")&&(affine==null||!affine.all{it.isFinite()})))return emptyList()
            out.add(
                ArDepthFrame(
                    w, h, grid.depth, grid.confidence,
                    fo.optDouble("fx"), fo.optDouble("fy"),
                    fo.optDouble("cx"), fo.optDouble("cy"),
                    pose, depthFromViewAffine = affine,
                ),
            )
        }
        // The legacy bundle completeness audit remains intact. Once it
        // passes, calculate with the exact original, not age-free legacy input.
        return original?:out
    }

    // MARK: - DBH re-run (shared production entry points)

    fun rerunDbh(dir: File, manifest: JSONObject, recordedFrames:List<ArDepthFrame>?=null): DbhReplay? {
        val frames = recordedFrames ?: reconstructDbhFrames(dir, manifest)
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
        DbhCandidate("silhouette", "Chord (silhouette)") { i ->
            DBHEstimator.estimateChord(
                i.frames, i.tapX, i.tapY, i.axis, i.cal, ChordAlgorithm.SILHOUETTE,
            )?.diameterCm?.toDouble()
        },
        DbhCandidate("arc", "Partial-arc circle fit") { i ->
            DBHEstimator.estimate(
                DbhScanInput(i.frames, i.tapX, i.tapY, i.axis, i.cal),
            )?.diameterCm?.toDouble()
        },
        DbhCandidate("depthBand", "Depth-band chord") { i ->
            DBHEstimator.estimateChord(
                i.frames, i.tapX, i.tapY, i.axis, i.cal, ChordAlgorithm.DEPTH_BAND,
            )?.diameterCm?.toDouble()
        },
        // Id is "bracket" — the SAME slug iOS writes. It used to be
        // "bracketChord" here, so the two platforms' sweep tables could not
        // be pooled by algorithm id.
        DbhCandidate("bracket", "Manual bracket-chord") { i ->
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

    // MARK: - Height multi-algorithm sweep + candidate registry

    /// Reconstructed replay inputs shared by every HEIGHT candidate — the
    /// stored tangent geometry (anchor + standing X/Z + base/top pitch) plus
    /// the calibration's VIO-drift fraction. Built ONCE per capture from the
    /// SAME scalars rerunHeight consumes, so a sweep runs all candidates over
    /// identical stored bytes (no new geometry, no re-collection).
    data class HeightSweepInput(
        val anchorX: Float,
        val anchorZ: Float,
        val standingX: Float,
        val standingZ: Float,
        val alphaTopRad: Float,
        val alphaBaseRad: Float,
        val vioDriftFraction: Float,
    )

    /// One candidate HEIGHT method the sweep runs. Shaped exactly like
    /// DbhCandidate (preprocess -> estimator -> correction, all inside `run`):
    /// `id` is a stable slug pooled with iOS; `run` returns an estimated height
    /// (m) from the shared stored inputs, or null when N/A / no usable value.
    /// ADD A NEW METHOD (slope/terrain-corrected tangent, future depth-based
    /// height, …) by adding ONE entry here — sweep, ranking, and UI follow.
    class HeightCandidate(
        val id: String,
        val label: String,
        val run: (HeightSweepInput) -> Double?,
    )

    /// THE height candidate registry. Base candidate = the production two-
    /// tangent estimator over the stored inputs (parity with rerunHeight's
    /// `value`). The second entry demonstrates the preprocess->estimator->
    /// correction shape: it runs the SAME estimator then applies a fixed bias
    /// offset — a stand-in showing exactly where a real correction (e.g. a
    /// slope-corrected standing distance, or a fitted bias) would live.
    val HEIGHT_CANDIDATES: List<HeightCandidate> = listOf(
        HeightCandidate("tangent", "Two-tangent") { i ->
            HeightEstimator.estimate(
                anchorX = i.anchorX, anchorZ = i.anchorZ,
                standingX = i.standingX, standingZ = i.standingZ,
                alphaTopRad = i.alphaTopRad, alphaBaseRad = i.alphaBaseRad,
                vioDriftFraction = i.vioDriftFraction,
            ).heightM.toDouble().takeIf { it.isFinite() && it > 0.0 }
        },
        // Example corrected candidate: estimator output + a fixed bias offset.
        // Purely illustrative of the correction hook — the sweep's OLS fit is
        // the real per-algorithm bias/slope calibration the user bakes in.
        HeightCandidate("tangentBias", "Two-tangent + fixed bias") { i ->
            val base = HeightEstimator.estimate(
                anchorX = i.anchorX, anchorZ = i.anchorZ,
                standingX = i.standingX, standingZ = i.standingZ,
                alphaTopRad = i.alphaTopRad, alphaBaseRad = i.alphaBaseRad,
                vioDriftFraction = i.vioDriftFraction,
            ).heightM.toDouble()
            (base + HEIGHT_FIXED_BIAS_M).takeIf { it.isFinite() && it > 0.0 }
        },
    )

    /// Illustrative fixed bias for the "tangent + fixed bias" example
    /// candidate (m). Not a tuned constant — it exists to show the shape.
    private const val HEIGHT_FIXED_BIAS_M = 0.0

    /// Per-capture height sweep result: every candidate's estimate over one
    /// bundle's stored scalars, plus the stored truth / live value. Mirrors
    /// DbhSweep. `estimates[id]` null = N/A / no usable value for this capture.
    data class HeightSweep(
        val truth: Double?,
        val liveValue: Double?,
        val estimates: Map<String, Double?>,
    )

    /// Rebuild the shared height sweep inputs from a height bundle (the same
    /// scalars rerunHeight reads: anchor world X/Z, base camera pose -> standing
    /// X/Z, base/top pitch, calibration VIO drift). Null when the bundle lacks
    /// what the tangent method needs. rerunHeight is left untouched.
    private fun heightSweepInput(manifest: JSONObject): HeightSweepInput? {
        val height = manifest.optJSONObject("height") ?: return null
        val anchor = height.optJSONObject("anchor") ?: return null
        val base = height.optJSONObject("base") ?: return null
        val top = height.optJSONObject("top") ?: return null
        val anchorWorld = anchor.optJSONArray("world") ?: return null
        val basePose = jsonToFloatArray(base.optJSONArray("camera_pose"), 16) ?: return null
        val cal = calibrationFrom(manifest.optJSONObject("settings"))
        return HeightSweepInput(
            anchorX = anchorWorld.optDouble(0).toFloat(),
            anchorZ = anchorWorld.optDouble(2).toFloat(),
            standingX = basePose[12],
            standingZ = basePose[14],
            alphaTopRad = Math.toRadians(top.optDouble("pitch_deg")).toFloat(),
            alphaBaseRad = Math.toRadians(base.optDouble("pitch_deg")).toFloat(),
            vioDriftFraction = cal.vioDriftFraction,
        )
    }

    /// Run EVERY height candidate over a single height capture's stored scalars
    /// and return {methodId -> estimate (m)}. A candidate that can't run is null
    /// (N/A), never a failure that aborts the sweep. Non-height bundles -> null.
    fun sweepHeight(manifest: JSONObject): HeightSweep? {
        if (manifest.optString("kind") != "height") return null
        val input = heightSweepInput(manifest) ?: return null
        val estimates = LinkedHashMap<String, Double?>(HEIGHT_CANDIDATES.size)
        for (c in HEIGHT_CANDIDATES) {
            val v = try { c.run(input) } catch (_: Throwable) { null }
            estimates[c.id] = v?.takeIf { it.isFinite() && it > 0.0 }
        }
        val truth = manifest.optJSONObject("truth")?.optDouble("value")?.takeIf { !it.isNaN() }
        val live = manifest.optJSONObject("result_live")?.optDouble("value")?.takeIf { !it.isNaN() }
        return HeightSweep(truth, live, estimates)
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
            res.heightM.toDouble(), reposed, res.sigmaHm?.toDouble(),
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
            // 0 for a bundle written before the key existed, which leaves its
            // coefficients refused — the same answer that build gave. Omitting
            // this was what made EVERY replay epoch 0, so a calibrated
            // project's replay silently dropped its correction.
            dbhCalibrationEpoch = cal.optInt("dbh_calibration_epoch", 0),
        )
    }

    private fun jsonToFloatArray(arr: org.json.JSONArray?, expected: Int): FloatArray? {
        if (arr == null || arr.length() != expected) return null
        val out = FloatArray(expected)
        for (i in 0 until expected) out[i] = arr.optDouble(i).toFloat()
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
