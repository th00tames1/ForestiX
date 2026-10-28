// Raw-capture STORE — writes and manages the replay bundles. Each bundle is
// a directory filesDir/raw-captures/<uuid>/ holding manifest.json plus, for
// DBH, the raw depth grids (depth_0..N.bin, native u16 mm or f32 metres, little-endian,
// row-major) and one reference rgb_0.jpg. The manifest schema is LOCKED and
// key-identical with the iOS sibling (platform "android", depth format
// "u16mm" or unquantized "f32m") so the two platforms' bundles pool for the cross-platform
// accuracy study.
//
// Recording is developer-mode + tc.rawCaptureEnabled only, fired at the end
// of every DBH burst and every Height compute; all serialization runs off
// the main thread (Dispatchers.IO). Immediately after writing, the bundle
// is loaded back and the CURRENT estimator re-run purely from disk
// (RawCaptureReplay) — the reproducibility self-check, recorded in
// replay_selfcheck. A failing self-check still saves the bundle, flagged.

package com.hcjeong.forestix.sensors

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

object RawCaptureStore {

    // Schema 2 (was 1): a Height bundle now ALSO stores the depth frame + a
    // reference RGB at the base aim and the top aim (depth_base/top.bin,
    // rgb_base/top.jpg) with new per-aim keys under height.base / height.top
    // (depth_file, rgb_file, width, height, format, fx, fy, cx, cy). The bump
    // is IDENTICAL on iOS. Old schema-1 bundles (and schema-2 height bundles
    // whose aim frames failed to capture) omit those keys and still replay the
    // tangent method exactly — replay is tolerant of their absence.
    const val SCHEMA = 2
    private const val MAX_FRAMES = 5

    /// Frame FLOOR for a DBH bundle: one raw frame is enough. The old floor
    /// of 5 silently dropped the whole capture under dusk / canopy / tracking
    /// loss — exactly the conditions the validation corpus needs most — and
    /// the caller discarded the null without a word. iOS has no floor either,
    /// so recording whatever exists also aligns the two corpora; the actual
    /// count is written to the manifest (`result_live.frame_count`) so a thin bundle
    /// is visible in analysis instead of invisible.
    private const val MIN_FRAMES = 1
    private const val SELF_CHECK_REL_TOL = 1e-3

    /// Refuse to start a new bundle below this much free space. A depth
    /// burst is a few MB; running the phone to zero mid-transect would
    /// half-write bundles and can wedge the whole app, so the recorder stops
    /// LOUDLY (the scan screen shows the failure) while there is still room.
    const val MIN_FREE_BYTES = 2L * 1024L * 1024L * 1024L

    /// Outcome of one record attempt — never a bare null. `error` is the
    /// user-visible reason the bundle did NOT save; `frameCount` is what
    /// actually reached disk.
    ///
    /// The two truth fields close the queued-edit hole: an operator can type a
    /// ground truth and Accept while this write is still in flight, and that
    /// value lives only in the pending queue until the manifest lands.
    ///   queuedTruthSaved — the queued value was folded into the manifest; it
    ///                      is DURABLE, so the scan screen may finally clear
    ///                      the input field.
    ///   queuedTruthLost  — the write failed with a queued value on it. The
    ///                      pairing is gone; the value is handed back so the
    ///                      screen can put it on screen again. Losing the
    ///                      pairing is acceptable, losing it invisibly is not.
    data class RecordOutcome(
        val id: String?,
        val frameCount: Int,
        val error: String?,
        val queuedTruthSaved: TruthEntry? = null,
        val queuedTruthLost: TruthEntry? = null,
        val replayValue: Double? = null,
        val replayStatus: String? = null,
        val aiEvidenceStatus:String? = null,
        val aiEvidenceReason:String? = null,
    ) {
        val saved: Boolean get() = id != null && error == null
    }

    /// A typed ground truth AND the unit it was typed in. `value` is always
    /// the app's metric base (cm for a diameter, m for a height); `unit` is
    /// the operator's tag ("cm" | "in" | "m" | "ft"). The two travel together
    /// everywhere, so a value handed back to a scan screen after a failed
    /// write is shown in the scale it was typed at rather than converted
    /// behind the cruiser's back.
    data class TruthEntry(val value: Double, val unit: TruthInput.Unit)

    /// QUEUED confirms a durable operator sidecar, not a completed raw bundle.
    /// Keep the typed text until the recorder confirms the manifest is SAVED.
    enum class TruthWrite { SAVED, QUEUED, FAILED }

    internal val pendingLock = Any()

    /// Mint a bundle id SYNCHRONOUSLY at capture time. The scan screen holds
    /// this id immediately, so Accept can attach truth to a bundle whose
    /// serialization is still in flight.
    fun newBundleId(): String = UUID.randomUUID().toString()

    /// Free space on the volume holding the bundles.
    fun freeSpaceBytes(context: Context): Long =
        try { root(context).usableSpace } catch (_: Throwable) { Long.MAX_VALUE }

    fun lowOnSpace(context: Context): Boolean = freeSpaceBytes(context) < MIN_FREE_BYTES

    /// Reason text shown on the scan screens' NOT SAVED pill (iOS wording).
    private fun lowStorageReason(free: Long): String =
        "Low storage (${byteLabel(free)} free)"

    private fun byteLabel(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.0f MB", bytes / 1_048_576.0)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    }

    /// Capture context — the cruise/quick provenance + GPS the manifest's
    /// `context` / `gps` blocks record.
    data class CaptureContext(
        val mode: String,               // "quick" | "cruise"
        val projectId: String?,
        val plotId: String?,
        val treeNumber: Int?,
        val gps: CLLocationSnapshot?,
    )

    /// ADJUST bracket geometry in VIEW-space pixels — the exact three
    /// numbers constrainedEstimate consumes, so the replay is pure.
    data class BracketSpec(val leftViewX: Float, val rightViewX: Float, val guideViewY: Float)

    /// One height 5 Hz pose sample: milliseconds since anchoring + the raw
    /// ARCore camera pose (16, column-major).
    data class PoseSample(val tMs: Long, val pose: FloatArray)

    /// Optional per-aim capture for a schema-2 height bundle: the AR depth
    /// frame grabbed AT the base/top tap and its reference RGB JPEG bytes.
    /// Either half may be null. A missing native u16 buffer uses the actual
    /// float depth without quantization; RGB does not depend on depth existing.
    /// Tangent replay remains independent of these optional attachments.
    data class HeightAim(val frame: ArDepthFrame?, val rgb: ByteArray?,val imageMetadata:RawCapturedImage?=null)

    // MARK: - Directory

    private fun root(context: Context): File =
        File(context.filesDir, "raw-captures").apply { mkdirs() }

    private fun bundleDir(context: Context, id: String): File = File(root(context), id)

    // MARK: - DBH recording

    /// Serialize one DBH burst under the id the caller already minted
    /// (newBundleId), so an Accept racing this write still lands its truth.
    /// `frames` retain their original order; missing rawDepthMm uses f32m.
    /// `rgb` is the
    /// reference JPEG grabbed at BURST START by the scan screen (iOS parity —
    /// the old post-burst background grab often pointed elsewhere or came
    /// back null). Returns a RecordOutcome that always says why a bundle did
    /// not save. Runs entirely off the main thread.
    suspend fun recordDbh(
        context: Context,
        id: String,
        frames: List<ArDepthFrame>,
        tapX: Double,
        tapY: Double,
        axisRow: Boolean,
        bracket: BracketSpec?,
        cal: ProjectCalibration,
        algorithmRaw: String,          // "silhouette" | "band"
        unitSystem: String,            // "metric" | "imperial"
        ctx: CaptureContext,
        rgb: ByteArray?,
        referenceImageMetadata:RawCapturedImage? = null,
        captureEvidence: RawCaptureEvidence? = null,
        aiRequest:RawAIWriteRequest? = null,
    ): RecordOutcome {
        try { return withContext(Dispatchers.IO) {
        val raw = frames
        if (raw.size !in MIN_FRAMES..MAX_FRAMES) {
            return@withContext RecordOutcome(
                null, 0, "unsupported depth frame count: ${raw.size} (expected $MIN_FRAMES..$MAX_FRAMES)",
                queuedTruthLost = pendingTruthOf(context,id))
        }
        val free = freeSpaceBytes(context)
        if (free < MIN_FREE_BYTES) {
            return@withContext RecordOutcome(
                null, 0, lowStorageReason(free), queuedTruthLost = pendingTruthOf(context,id))
        }
        val dir = bundleDir(context, id)
        try{RawCaptureWriteGuard.claim(dir,id,"dbh")}catch(e:Exception){
            return@withContext RecordOutcome(null,0,e.message?:"Raw writer claim failed",queuedTruthLost=pendingTruthOf(context,id))}
        try {
            require(raw.map{it.depthInputPolicy}.distinct().size==1){"Mixed measurement depth policies"}
            if(raw.any{it.depthInputPolicy!=DbhMeasurementDepth.LEGACY_FULL})require(captureEvidence!=null){"Qualified depth requires original evidence"}
            // 1) Raw depth grids.
            val storedGrids=raw.mapIndexed { i, f -> RawDepthGridIO.write(f,dir,"depth_$i.bin") }
            // 2) One reference RGB, captured by the caller at burst start.
            //    Best-effort — a miss just leaves rgb_file null.
            val haveRgb = try {
                if (rgb != null && rgb.isNotEmpty()) { File(dir, "rgb_0.jpg").writeBytes(rgb); true }
                else false
            } catch (_: Throwable) { false }

            // 3) Live estimator result over the STORED frames (the
            //    reproducible unit the self-check re-derives). For the manual
            //    bracket the view-space handles are converted ONCE to
            //    view-independent DEPTH-guide-axis fractions (matching the
            //    iOS bracket schema — no view size / no guide_y stored), then
            //    the recording + replay share DBHEstimator.bracketChordEstimate.
            val algorithm = ChordAlgorithm.fromRaw(algorithmRaw)
            val bracketGeom = if (bracket != null) deriveBracket(raw.first(), bracket) else null
            if (bracket != null && bracketGeom == null) {
                val reason="bracket geometry couldn't be mapped to the depth frame"
                return@withContext RecordOutcome(
                    null, 0, reason+" · partial raw files retained",
                    queuedTruthLost = preserveFailedBundle(context,id,reason))
            }
            val effTapX: Double
            val effTapY: Double
            val effAxisStr: String
            val liveRes: DBHResult?
            val perFrame: List<Double>
            if (bracketGeom != null) {
                liveRes = DBHEstimator.bracketChordEstimate(
                    raw, bracketGeom.axis, bracketGeom.leftFrac, bracketGeom.rightFrac, cal)
                perFrame = raw.mapNotNull {
                    DBHEstimator.bracketChordFit(
                        it, bracketGeom.axis, bracketGeom.leftFrac, bracketGeom.rightFrac)?.diameterCm
                }
                effTapX = bracketGeom.tapX; effTapY = bracketGeom.tapY; effAxisStr = bracketGeom.axisStr
            } else {
                val axis: GuideAxis = if (axisRow) GuideAxis.Row(Math.round(tapY).toInt())
                else GuideAxis.Col(Math.round(tapX).toInt())
                liveRes = DBHEstimator.estimateChord(raw, tapX, tapY, axis, cal, algorithm)
                perFrame = raw.mapNotNull {
                    DBHEstimator.livePreview(it, tapX, tapY, axis, cal, algorithm)
                        ?.takeIf { p -> p.locked }?.diameterCm?.toDouble()
                }
                effTapX = tapX; effTapY = tapY; effAxisStr = if (axisRow) "row" else "col"
            }

            // 4) Manifest (schema-locked).
            val manifest = JSONObject()
            manifest.put("schema", SCHEMA)
            manifest.put("platform", "android")
            manifest.put("device", Build.MODEL)
            manifest.put("app_commit", appCommit(context))
            manifest.put("created_at", iso8601())
            manifest.put("kind", "dbh")
            manifest.put("context", contextJson(ctx))
            manifest.put("settings", settingsJson(
                algorithm = mapAlgorithm(algorithmRaw),
                units = unitSystem,
                captureMode = if (bracket != null) "manual" else "auto",
                cal = cal,
            ))
            // operator_accepted starts false at capture (recording fires at
            // burst end, before the cruiser decides) — flipped by
            // markAccepted on Accept. tier_ok is the RECORD-TIME fit verdict.
            // iOS writes both keys with the same meaning.
            manifest.put("result_live", resultLiveJson(
                value = liveRes?.diameterCm?.toDouble() ?: 0.0,
                sigma = liveRes?.sigmaRmm?.toDouble() ?: 0.0,
                tier = liveRes?.confidence?.raw ?: "red",
                accepted = false,
                frameCount = raw.size,
                perFrame = perFrame,
            ))
            manifest.put("truth", truthJson(null, null, null))
            manifest.put("gps", gpsJson(ctx.gps))

            // DBH block: per-frame raw descriptors. view_to_depth is ALWAYS
            // a 6-array (identity [1,0,0,0,1,0] when no affine, matching iOS —
            // iOS's depth grid shares screen orientation so its mapping is
            // identity, Android carries the real display-rotation affine).
            val framesArr = JSONArray()
            raw.forEachIndexed { i, f ->
                val fo = JSONObject()
                fo.put("depth_file", "depth_$i.bin")
                fo.put("width", f.width)
                fo.put("height", f.height)
                fo.put("format", storedGrids[i].format)
                fo.put("confidence_file", storedGrids[i].confidenceFile)
                fo.put("fx", f.fx); fo.put("fy", f.fy)
                fo.put("cx", f.cx); fo.put("cy", f.cy)
                fo.put("tap_px", JSONArray().put(effTapX).put(effTapY))
                fo.put("axis", effAxisStr)
                fo.put("depth_input_policy",f.depthInputPolicy)
                fo.put("camera_pose", floatArr(f.pose))
                fo.put("view_to_depth", floatArr(f.depthFromViewAffine ?: IDENTITY_AFFINE))
                framesArr.put(fo)
            }
            val dbhBlock = JSONObject()
            dbhBlock.put("frames", framesArr)
            // Bracket (schema-locked): {enabled, left, right}, left/right are
            // guide-axis fractions (0 for the auto path — enabled=false).
            val bracketJson = JSONObject()
            bracketJson.put("enabled", bracketGeom != null)
            bracketJson.put("left", bracketGeom?.leftFrac ?: 0.0)
            bracketJson.put("right", bracketGeom?.rightFrac ?: 0.0)
            dbhBlock.put("bracket", bracketJson)
            dbhBlock.put("rgb_file", if (haveRgb) "rgb_0.jpg" else JSONObject.NULL)
            manifest.put("dbh", dbhBlock)
            manifest.put("height", JSONObject.NULL)
            manifest.put("measurement_id",id)
            manifest.getJSONObject("settings").put("dbh_depth_input_policy",raw.first().depthInputPolicy)
            manifest.getJSONObject("settings").put("dbh_depth_calibration_epoch",DbhMeasurementDepth.calibrationEpoch(raw.first().depthInputPolicy))
            val depthComparisons=ArrayList<DbhDepthComparisonIO.Input>()
            var evidence = captureEvidence
            if(evidence!=null){
                require(evidence.measurementId==id&&frames.size<=32){"Capture evidence identity/count"}
                if(frames.any{it.depthInputPolicy!=DbhMeasurementDepth.LEGACY_FULL})evidence=evidence.copy(version=2)
                evidence=evidence.copy(frames=frames.mapIndexed{i,f->
                    val recorded=RawCaptureEvidenceIO.writeFrame(f,"dbh_$i",dir)
                    val axis=if(effAxisStr=="row")GuideAxis.Row(Math.round(effTapY).toInt())else GuideAxis.Col(Math.round(effTapX).toInt())
                    val p=if(bracketGeom==null)DBHEstimator.livePreview(f,effTapX,effTapY,axis,cal,algorithm)else null
                    val left=bracketGeom?.leftFrac?:p?.stripLeftFraction?.toDouble()
                    val right=bracketGeom?.rightFrac?:p?.stripRightFraction?.toDouble()
                    val support=if(left!=null&&right!=null)DbhRawDepthSupport.assess(f,axis,left,right)else null
                    val comparison=buildMap<String,String>{
                        put("raw_comparison_policy","diagnostic_only_v1")
                        put("raw_comparison_status",support?.status?:"boundary_unavailable")
                        support?.let{s->
                            put("raw_supported_pixels",s.supportedPixels.toString());put("raw_sampled_pixels",s.sampledPixels.toString())
                            s.pairedDifferenceM?.let{put("raw_full_paired_difference_m",it.toString())}
                            s.rawMedianM?.let{put("raw_supported_median_m",it.toString())}
                            s.fullPairedMedianM?.let{put("full_supported_median_m",it.toString())}
                        }
                        if(DbhMeasurementDepth.usesRaw(f.depthInputPolicy)&&left!=null&&right!=null){
                            val extent=if(axis is GuideAxis.Col)f.height else f.width
                            val selected=DbhMeasurementDepth.measurementProfile(f,axis,left*extent,right*extent)
                            put("measurement_depth_status",selected.status)
                            put("measurement_supported_pixels",selected.supportedPixels.toString())
                            put("measurement_candidate_pixels",selected.candidatePixels.toString())
                            put("measurement_axis_samples",selected.supportedAxisSamples.toString())
                            selected.medianM?.let{put("measurement_sample_depth_m",it.toString())}
                            selected.diameterCm?.let{put("measurement_raw_diameter_cm",it.toString())}
                            selected.rawInformationAgeNanos?.let{put("raw_information_age_ns",it.toString())}
                        }
                    }
                    val geometry=if(left!=null&&right!=null)DbhDepthComparisonIO.Geometry(axis,left,right)else null
                    val annotated=DbhDepthComparisonIO.annotate(recorded.copy(depthDiagnostics=recorded.depthDiagnostics+comparison),geometry)
                    depthComparisons.add(DbhDepthComparisonIO.Input(f,annotated,geometry))
                    annotated
                })
                evidence=evidence.copy(images=listOfNotNull(RawCaptureEvidenceIO.attachImage(referenceImageMetadata,rgb,
                    if(haveRgb)"rgb_0.jpg"else null,"dbh_reference",dir)))
                val aiRecorded=aiRequest?.write(dir)
                if(aiRecorded!=null)evidence=evidence.copy(aiInference=aiRecorded.inference,aiEvidenceStatus=aiRecorded.status,aiEvidenceReason=aiRecorded.reason)
                // Round-trip metadata and all original bytes, not confidence reconstructed from depth.
                evidence=RawCaptureEvidence.parse(evidence.json().toString())
                manifest.put("capture_evidence",JSONObject(evidence.json().toString()))
                evidence=evidence.noteReplay(RawCaptureReplay.rerunCapturedDbh(dir,manifest))
                manifest.put("capture_evidence",JSONObject(evidence.json().toString()))
            }

            // 5) Reproducibility self-check — reload from disk, re-run.
            val rerun = try { RawCaptureReplay.rerunDbh(dir, manifest)?.value } catch (_: Throwable) { null }
            manifest.put("replay_selfcheck",
                selfCheckJson(liveRes?.diameterCm?.toDouble(), rerun, null))
            writeDepthComparison(dir,id,manifest,depthComparisons,cal)

            val foldedTruth = writeManifest(id, dir, manifest)
            RecordOutcome(id, raw.size, null, queuedTruthSaved = foldedTruth,replayValue=evidence?.replayValue,replayStatus=evidence?.replayStatus,
                aiEvidenceStatus=evidence?.aiEvidenceStatus,aiEvidenceReason=evidence?.aiEvidenceReason)
        } catch (t: Throwable) {
            val reason=writeFailureReason(context,t)
            RecordOutcome(
                null, 0, reason+" · partial raw files retained", queuedTruthLost = preserveFailedBundle(context,id,reason))
        }
        } } finally { aiRequest?.cancel() }
    }

    suspend fun recordRefusedAttempt(context:Context,id:String,cal:ProjectCalibration,units:String,ctx:CaptureContext,
                                evidence:RawCaptureEvidence,aiRequest:RawAIWriteRequest,
                                snapshot:ArDepthFrame?=null,reference:RawCameraJPEG?=null,
                                comparisonBracket:BracketSpec?=null):RecordOutcome {
        try{return withContext(Dispatchers.IO){
        var ownsWrite=false
        try{
            require(evidence.measurementId==id&&!evidence.displayedResult.valid&&evidence.attemptFailureReason!=null){"Refused attempt identity"}
            val free=freeSpaceBytes(context);if(free<MIN_FREE_BYTES)return@withContext RecordOutcome(null,0,lowStorageReason(free))
            val dir=bundleDir(context,id);RawCaptureWriteGuard.claim(dir,id,"attempt_only");ownsWrite=true
            val recorded=aiRequest.write(dir)
            val images=reference?.let { listOfNotNull(RawCaptureEvidenceIO.attachImage(it.metadata,it.data,null,"shutter_reference",dir)) }
            val comparisonGeometry=snapshot?.let{f->comparisonBracket?.let{deriveBracket(f,it)}}?.let{
                DbhDepthComparisonIO.Geometry(it.axis,it.leftFrac,it.rightFrac)}
            val stored=evidence.copy(aiInference=recorded.inference,aiEvidenceStatus=recorded.status,aiEvidenceReason=recorded.reason,
                version=if(snapshot?.depthInputPolicy?.let(DbhMeasurementDepth::usesRaw)==true)2 else evidence.version,
                frames=snapshot?.let{listOf(DbhDepthComparisonIO.annotate(RawCaptureEvidenceIO.writeFrame(it,"shutter_refused",dir),comparisonGeometry))}?:evidence.frames,
                images=images?:evidence.images)
            val manifest=JSONObject().put("schema",SCHEMA).put("platform","android").put("device",Build.MODEL)
                .put("app_commit",appCommit(context)).put("created_at",iso8601()).put("kind","dbh").put("measurement_id",id)
                .put("context",contextJson(ctx)).put("settings",settingsJson("not_measured",units,"attempt_only",cal))
                .put("result_live",resultLiveJson(0.0,0.0,"red",false,0,emptyList()))
                .put("truth",truthJson(null,null,null)).put("gps",gpsJson(ctx.gps)).put("dbh",JSONObject.NULL).put("height",JSONObject.NULL)
                .put("capture_evidence",JSONObject(stored.json().toString()))
                .put("replay_selfcheck",JSONObject().put("status","not_applicable").put("rerun_value",JSONObject.NULL).put("delta",JSONObject.NULL))
            if(snapshot!=null){
                writeDepthComparison(dir,id,manifest,listOf(DbhDepthComparisonIO.Input(snapshot,stored.frames.single(),comparisonGeometry)),cal)
            }
            val truth=writeManifest(id,dir,manifest)
            if(recorded.status=="saved"||((snapshot!=null||evidence.depthDiagnostics!=null)&&recorded.status in listOf("not_requested","no_inference")))RecordOutcome(id,if(snapshot==null)0 else 1,null,queuedTruthSaved=truth)
            else RecordOutcome(null,0,"AI attempt input ${recorded.status}: ${recorded.reason?:"unavailable"}")
        }catch(e:Exception){val reason=e.message?:"Attempt evidence write failed"
            RecordOutcome(null,0,reason+if(ownsWrite)" · partial raw files retained"else"",
                queuedTruthLost=if(ownsWrite)preserveFailedBundle(context,id,reason)else pendingTruthOf(context,id))}
        }}finally{aiRequest.cancel()}
    }

    /** Experimental comparison must not stop the original input/result from
     * being saved. Failure is explicit, never a different measurement. */
    internal fun writeDepthComparison(dir:File,id:String,manifest:JSONObject,inputs:List<DbhDepthComparisonIO.Input>,cal:ProjectCalibration){
        if(inputs.isEmpty())return
        try{
            val descriptor=DbhDepthComparisonIO.write(dir,id,inputs,cal)
            manifest.put("dbh_depth_comparison",JSONObject(descriptor.toString()))
            manifest.put("dbh_depth_comparison_status","saved")
        }catch(e:Exception){
            manifest.put("dbh_depth_comparison_status","failed")
            manifest.put("dbh_depth_comparison_error",e.javaClass.simpleName)
        }
    }

    // MARK: - Height recording

    suspend fun recordHeight(
        context: Context,
        id: String,
        anchorWorld: Vec3,
        anchorHitType: String,
        anchorDistanceM: Float,
        anchorPose: FloatArray,
        basePitchDeg: Float,
        basePose: FloatArray,
        topPitchDeg: Float,
        topPose: FloatArray,
        dHm: Float,
        live: HeightResult,
        cal: ProjectCalibration,
        poseSamples: List<PoseSample>,
        // Whether VIO tracking dropped between the anchor and the aims — the
        // on-screen warning, recorded so the bundle can be filtered on it.
        trackingDropped: Boolean,
        unitSystem: String,
        ctx: CaptureContext,
        baseAim: HeightAim? = null,
        topAim: HeightAim? = null,
        captureEvidence: RawCaptureEvidence? = null,
    ): RecordOutcome = withContext(Dispatchers.IO) {
        val free = freeSpaceBytes(context)
        if (free < MIN_FREE_BYTES) {
            return@withContext RecordOutcome(
                null, 0, lowStorageReason(free), queuedTruthLost = pendingTruthOf(context,id))
        }
        val dir = bundleDir(context, id)
        try{RawCaptureWriteGuard.claim(dir,id,"height")}catch(e:Exception){
            return@withContext RecordOutcome(null,0,e.message?:"Raw writer claim failed",queuedTruthLost=pendingTruthOf(context,id))}
        try {
            val manifest = JSONObject()
            manifest.put("schema", SCHEMA)
            manifest.put("platform", "android")
            manifest.put("device", Build.MODEL)
            manifest.put("app_commit", appCommit(context))
            manifest.put("created_at", iso8601())
            manifest.put("kind", "height")
            manifest.put("context", contextJson(ctx))
            // Height's algorithm tag is "tangent" (iOS parity) — the VIO
            // walk-off two-tangent method, not a DBH chord algorithm.
            manifest.put("settings", settingsJson(
                algorithm = "tangent",
                units = unitSystem,
                captureMode = "auto",
                cal = cal,
            ))
            manifest.put("result_live", resultLiveJson(
                value = live.heightM.toDouble(),
                // σ_H is null when the estimator could not derive one at all
                // (degenerate d_h, inverted aims) — a NON-measurement, which
                // is also refused at Accept. The manifest's sigma key is
                // schema-locked non-optional, so it falls back to 0 exactly
                // as the DBH path does for a failed fit; the tier below is
                // "red" for every such bundle and the raw geometry is stored,
                // so the value is re-derivable offline.
                sigma = live.sigmaHm?.toDouble() ?: 0.0,
                tier = live.confidence.raw,
                accepted = false,          // operator_accepted — flipped on Accept
                frameCount = 0,            // patched below once the aims are attached
                perFrame = emptyList(),
            ))
            manifest.put("truth", truthJson(null, null, null))
            manifest.put("gps", gpsJson(ctx.gps))
            manifest.put("dbh", JSONObject.NULL)

            val heightBlock = JSONObject()
            val anchorJson = JSONObject()
            anchorJson.put("world", JSONArray().put(anchorWorld.x.toDouble())
                .put(anchorWorld.y.toDouble()).put(anchorWorld.z.toDouble()))
            anchorJson.put("hit_type", anchorHitType)
            anchorJson.put("distance_m", anchorDistanceM.toDouble())
            anchorJson.put("camera_pose", floatArr(anchorPose))
            heightBlock.put("anchor", anchorJson)
            val baseJson = JSONObject()
            baseJson.put("pitch_deg", basePitchDeg.toDouble())
            baseJson.put("camera_pose", floatArr(basePose))
            // Schema 2: base-aim depth + RGB (keys omitted when unavailable).
            var aimFrames = 0
            if (attachAimFrame(dir, baseAim, "depth_base.bin", "rgb_base.jpg", baseJson)) aimFrames++
            heightBlock.put("base", baseJson)
            val topJson = JSONObject()
            topJson.put("pitch_deg", topPitchDeg.toDouble())
            topJson.put("camera_pose", floatArr(topPose))
            // Schema 2: top-aim depth + RGB (keys omitted when unavailable).
            if (attachAimFrame(dir, topAim, "depth_top.bin", "rgb_top.jpg", topJson)) aimFrames++
            heightBlock.put("top", topJson)
            heightBlock.put("d_h_m", dHm.toDouble())
            // A dropout moves the world frame the trunk anchor sits in, so it
            // moves d_h — the entire scale of H — and the pose trail below has
            // a hole where it happened. Written on every height bundle, so
            // `false` states the walk-off was continuous rather than leaving
            // the question unanswered. Byte-identical key on iOS
            // (`HeightBundle.trackingDropped`).
            heightBlock.put("tracking_dropped", trackingDropped)
            val samplesArr = JSONArray()
            for (s in poseSamples) {
                val so = JSONObject()
                so.put("t_ms", s.tMs)
                so.put("pose", floatArr(s.pose))
                samplesArr.put(so)
            }
            heightBlock.put("pose_samples", samplesArr)
            manifest.put("height", heightBlock)
            manifest.put("measurement_id",id)
            var evidence=captureEvidence
            if(evidence!=null){
                require(evidence.measurementId==id){"Capture evidence identity"}
                evidence=evidence.copy(frames=listOf("base" to baseAim?.frame,"top" to topAim?.frame).mapNotNull{(role,f)->
                    f?.let{RawCaptureEvidenceIO.writeFrame(it,role,dir)}})
                evidence=evidence.copy(images=listOfNotNull(
                    RawCaptureEvidenceIO.attachImage(baseAim?.imageMetadata,baseAim?.rgb,baseJson.optString("rgb_file").takeIf{it.isNotEmpty()&&it!="null"},"base",dir),
                    RawCaptureEvidenceIO.attachImage(topAim?.imageMetadata,topAim?.rgb,topJson.optString("rgb_file").takeIf{it.isNotEmpty()&&it!="null"},"top",dir)))
                evidence=RawCaptureEvidence.parse(evidence.json().toString())
                manifest.put("capture_evidence",JSONObject(evidence.json().toString()))
                evidence=evidence.noteReplay(RawCaptureReplay.rerunCapturedHeight(manifest))
                manifest.put("capture_evidence",JSONObject(evidence.json().toString()))
            }

            // Self-check + the pose-reposed re-derivation (rerun_value_reposed,
            // iOS parity): both re-run HeightEstimator.estimate from disk.
            val replay = try { RawCaptureReplay.rerunHeight(manifest) } catch (_: Throwable) { null }
            manifest.put("replay_selfcheck", selfCheckJson(
                live.heightM.toDouble(), replay?.value, replay?.valueReposed))

            // Aim depth frames actually stored (0-2): a chained height that
            // lost the recorder arm mid-flight is explicit rather than an
            // invisible schema-1 degradation.
            manifest.optJSONObject("result_live")?.put("frame_count", aimFrames)

            val foldedTruth = writeManifest(id, dir, manifest)
            RecordOutcome(id, aimFrames, null, queuedTruthSaved = foldedTruth,replayValue=evidence?.replayValue,replayStatus=evidence?.replayStatus)
        } catch (t: Throwable) {
            val reason=writeFailureReason(context,t)
            RecordOutcome(
                null, 0, reason+" · partial raw files retained", queuedTruthLost = preserveFailedBundle(context,id,reason))
        }
    }

    // MARK: - Listing / management

    /// Lightweight summary parsed from a bundle's manifest for the list UI.
    data class Summary(
        val id: String,
        val kind: String,
        val createdAt: String,
        val treeNumber: Int?,
        val mode: String,
        val liveValue: Double?,
        val unit: String,
        val tier: String?,
        val truthValue: Double?,
        /// The unit [truthValue] was typed in, or null when the bundle records
        /// none. Null is the marker TruthUnitRepair selects on, so it must be
        /// carried into the summary rather than re-read per bundle: "absent"
        /// and "present but JSON null" are the same state and both arrive here
        /// as null, which is also how the iOS decoder reads them.
        val truthUnit: String?,
        val selfCheckStatus: String?,
        /// Raw depth frames actually stored (DBH bundles). Null for height /
        /// pre-frame_count bundles.
        val frameCount: Int?,
        val allowsLegacyInference: Boolean = true,
    )

    fun list(context: Context): List<Summary> {
        val dirs = root(context).listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val mf = File(d, "manifest.json")
            if (!mf.exists()) return@mapNotNull null
            val m = try { JSONObject(mf.readText()) } catch (_: Throwable) { return@mapNotNull null }
            val kind = m.optString("kind")
            val ctxObj = m.optJSONObject("context")
            val resObj = m.optJSONObject("result_live")
            val truthObj = m.optJSONObject("truth")
            val selfObj = m.optJSONObject("replay_selfcheck")
            val presentation = RawCapturePresentation.from(m.toString())
            Summary(
                id = d.name,
                kind = kind,
                createdAt = m.optString("created_at"),
                treeNumber = ctxObj?.optInt("tree_number", -1)?.takeIf { it >= 0 },
                mode = ctxObj?.optString("mode") ?: "quick",
                liveValue = presentation.displayedValue,
                unit = if (kind == "height") "m" else "cm",
                tier = presentation.tier,
                truthValue = truthObj?.optDouble("value")?.takeIf { !it.isNaN() },
                truthUnit = truthObj?.optString("truth_unit")?.takeIf { it.isNotEmpty() },
                selfCheckStatus = selfObj?.optString("status")?.takeIf { it.isNotEmpty() },
                frameCount = resObj?.optInt("frame_count", -1)?.takeIf { it >= 0 }
                    ?: m.optJSONObject("dbh")?.optJSONArray("frames")?.length(),
                allowsLegacyInference = m.isNull("measurement_id") && m.isNull("capture_evidence"),
            )
        }.sortedByDescending { it.createdAt }
    }

    /// What is ON DISK versus what could be READ. [list] skips any bundle
    /// whose manifest.json is missing or won't parse, so a truncated or
    /// half-written manifest used to make a whole capture vanish: absent from
    /// the total, from every skip counter, from the Settings count and from
    /// the sweep footer — whose arithmetic still balanced. Counting the bundle
    /// DIRECTORIES separately makes that class visible.
    ///
    /// A bundle being serialized RIGHT NOW (directory created, manifest not
    /// written yet) counts as unparseable for those few hundred milliseconds.
    /// That over-report is deliberate: the same on-disk shape left behind by a
    /// process killed mid-write is a permanent corpse, and it must not be
    /// possible to hide one by calling it a transient.
    data class Inventory(val directories: Int, val parsed: Int) {
        /// Bundles that exist on disk but cannot be read. Never negative.
        val unparseable: Int get() = (directories - parsed).coerceAtLeast(0)
    }

    fun inventory(context: Context): Inventory = Inventory(
        directories = directoryCount(context),
        parsed = list(context).size,
    )

    /// Bundle DIRECTORIES on disk, parsing nothing — the same number
    /// [Inventory.directories] carries, for a caller that already holds the
    /// summaries and only needs the denominator. Calling [inventory] there
    /// would re-parse every manifest on disk a second time.
    fun directoryCount(context: Context): Int =
        root(context).listFiles()?.count { it.isDirectory } ?: 0

    fun manifestOf(context: Context, id: String): JSONObject? {
        val mf = File(bundleDir(context, id), "manifest.json")
        if (!mf.exists()) return null
        return try { JSONObject(mf.readText()) } catch (_: Throwable) { null }
    }

    fun dirOf(context: Context, id: String): File = bundleDir(context, id)

    /// READABLE bundles only — exactly the set [list] returns. It used to
    /// count any directory holding a manifest.json file, parseable or not, so
    /// it disagreed with every other number in the app. Use [inventory] when
    /// the unreadable ones matter.
    fun count(context: Context): Int = list(context).size

    fun totalSizeBytes(context: Context): Long = root(context).walkTopDown()
        .filter { it.isFile }.map { it.length() }.sum()

    fun clearAll(context: Context) {
        root(context).deleteRecursively()
    }

    fun delete(context: Context, id: String) {
        bundleDir(context, id).deleteRecursively()
    }

    /// Flip a bundle's operator_accepted (and the legacy `accepted` mirror)
    /// to true — called when the cruiser taps Accept. If the bundle is still
    /// being serialized the flag is QUEUED and folded in by the recorder, so
    /// a fast Accept is never lost. iOS markAccepted parity.
    ///
    /// The WHOLE read-modify-write runs under pendingLock (see setTruth).
    suspend fun markAccepted(context: Context, id: String): Boolean =
        withContext(Dispatchers.IO) {
            try{RawOperatorEditJournal.update(bundleDir(context,id),id,accepted=true);true}catch(_:Exception){false}
        }

    /// Persist an operator-entered ground truth into a bundle's manifest.
    /// `value == null` CLEARS the stored truth and is only ever reached from
    /// an explicit Clear action — an empty or unparseable field never gets
    /// here, so a stored truth cannot be wiped by a stray Save. Queued the
    /// same way as markAccepted while the bundle is still being written.
    ///
    /// QUEUED is deliberately DISTINCT from SAVED: the value is not in any
    /// manifest yet, so the caller must keep the operator's typed text until
    /// the recorder reports it folded in (RecordOutcome.queuedTruthSaved).
    ///
    /// The read-modify-write happens INSIDE pendingLock, the same lock the
    /// recorder's manifest drain holds — the two used to interleave, and a
    /// direct setTruth landing between the drain's read and its write was
    /// clobbered with no error.
    /// `value` is the metric base (cm / m); `unit` is what the operator typed,
    /// recorded beside it so the export never has to infer the scale. A clear
    /// (`value == null`) carries no unit.
    suspend fun setTruth(
        context: Context,
        id: String,
        value: Double?,
        unit: TruthInput.Unit?,
    ): TruthWrite =
        withContext(Dispatchers.IO) {
            try{if(RawOperatorEditJournal.update(bundleDir(context,id),id,RawOperatorEditJournal.truth(value,if(value!=null)unit?.raw else null)))
                TruthWrite.SAVED else TruthWrite.QUEUED}catch(_:Exception){TruthWrite.FAILED}
        }

    fun recoverPendingOperatorEdits(context:Context):List<RawOperatorRecoveryIssue> = RawOperatorEditJournal.recover(root(context))

    /// Re-base a stored truth that was typed in imperial and stored as if it
    /// were the metric base — TruthUnitRepair's write into a bundle.
    ///
    /// This is the ONE thing in the app that edits a manifest's truth without
    /// the cruiser typing into that bundle. It is not an inference: it acts
    /// only where the bundle ITSELF records no `truth_unit`, and it corrects
    /// the scale of the field rather than replacing the observation. The digits
    /// typed are kept in `repaired_from` and `entered_at` is NOT restamped —
    /// when the cruiser measured the stem has not changed.
    ///
    /// RE-CHECKS UNDER pendingLock, the same lock [setTruth] and the recorder's
    /// manifest drain hold: [before] must still be what is on disk and the
    /// bundle must still record no unit, so a truth re-typed in the console
    /// between the plan and this write is left alone, and a second run of the
    /// repair finds a unit and does nothing. No pending-edit path: a bundle
    /// with no manifest has no stored truth to re-base, and queueing a
    /// correction for a value that does not exist yet would apply it to
    /// whatever the recorder writes next.
    suspend fun repairTruthUnit(
        context: Context,
        id: String,
        before: Double,
        after: Double,
        unit: TruthInput.Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        synchronized(pendingLock) {
            val file = manifestFile(context, id)
            if (!file.exists()) return@synchronized false
            val m = manifestOf(context, id) ?: return@synchronized false
            if(!m.isNull("measurement_id")||!m.isNull("capture_evidence")||File(bundleDir(context,id),"pending_patch.json").exists())return@synchronized false
            val truth = m.optJSONObject("truth") ?: return@synchronized false
            if (truth.optString("truth_unit").isNotEmpty()) return@synchronized false
            val stored = truth.optDouble("value")
            if (stored.isNaN() || abs(stored - before) > TRUTH_VALUE_EPSILON) {
                return@synchronized false
            }
            val enteredAt = truth.optString("entered_at").takeIf { it.isNotEmpty() }
            m.put("truth", truthJson(after, enteredAt, unit, repairedFrom = stored))
            writeQuietly(file, m)
        }
    }

    /// Two truth values are the SAME value inside this band — the same number
    /// and reasoning as TruthBackfill.VALUE_EPSILON: every writer puts the
    /// metric base through one parser, so the only difference between two
    /// copies of a truth is float round-trip.
    const val TRUTH_VALUE_EPSILON = 0.001

    /// Zip result — a Uri, or the reason the export could not be produced.
    data class ExportResult(val uri: Uri?, val error: String?)

    /// Zip the whole raw-captures directory into the shared export cache and
    /// return a shareable Uri (same FileProvider flow as the research CSV).
    /// Streams entry by entry — nothing is held in memory — and reports the
    /// failure instead of returning a silent null.
    suspend fun exportZip(context: Context): ExportResult = withContext(Dispatchers.IO) {
        val src = root(context)
        val bundles = src.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (bundles.isEmpty()) return@withContext ExportResult(null, "No captures to export.")
        val dir = File(context.cacheDir, "Exports").apply { mkdirs() }
        val zip = File(dir, "forestix_raw_captures.zip")
        try {
            if (zip.exists()) zip.delete()
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                src.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(src).path
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            ExportResult(
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip),
                null,
            )
        } catch (t: Throwable) {
            runCatching { zip.delete() }
            ExportResult(null, "Export failed — ${writeFailureReason(context, t)}.")
        }
    }

    // MARK: - Manifest writing / pending edits

    private fun manifestFile(context: Context, id: String): File =
        File(bundleDir(context, id), "manifest.json")

    /// Write a freshly built manifest, folding in anything the operator typed
    /// while the write was in flight. Drain + apply + write happen in ONE
    /// critical section: setTruth/markAccepted take the same lock, so an edit
    /// either queues (and is folded here) or finds the finished file and
    /// rewrites it — it can never land between a drain's read and its write
    /// and be clobbered.
    ///
    /// Returns the queued TRUTH that was folded in (null = none), so the
    /// recorder can tell the scan screen its typed value is now durable.
    /// A failed write puts the queued edit BACK on the queue and rethrows, so
    /// the caller's failure path reports it as lost instead of eating it.
    private fun writeManifest(id: String, dir: File, manifest: JSONObject): TruthEntry? =
        synchronized(pendingLock) {
            truthEntryOf(RawOperatorEditJournal.commitRecorder(dir,id,manifest.toString()))
        }

    /// Temp file + rename. `writeText` truncates in place, so a crash (or a
    /// disk filling up) mid-rewrite left a HALF-WRITTEN manifest.json — a
    /// bundle that then disappears from every listing because it no longer
    /// parses. The rename is atomic within the app's own storage, so a reader
    /// sees either the whole old manifest or the whole new one.
    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (tmp.renameTo(file)) return
        // Fallback for volumes that refuse rename-onto-existing. The ORIGINAL
        // is moved aside, never deleted outright: deleting it first and then
        // failing the second rename would turn a perfectly readable bundle
        // into an unreadable one — the exact damage this function exists to
        // prevent.
        val backup = File(file.parentFile, "${file.name}.bak")
        backup.delete()
        val movedAside = file.exists() && file.renameTo(backup)
        if (tmp.renameTo(file)) {
            backup.delete()
            return
        }
        if (movedAside) backup.renameTo(file)   // put the old manifest back
        tmp.delete()
        throw java.io.IOException("couldn't replace ${file.name}")
    }

    private fun writeQuietly(file: File, manifest: JSONObject): Boolean = try {
        writeAtomic(file, manifest.toString())
        true
    } catch (_: Throwable) {
        false
    }

    /// Return uncommitted typed input without deleting its durable sidecar.
    private fun pendingTruthOf(context:Context,id:String):TruthEntry? {
        return try{truthEntryOf(RawOperatorEditJournal.pendingTruth(bundleDir(context,id),id))}catch(_:Exception){null}
    }

    private fun truthEntryOf(truth:JsonObject?):TruthEntry?{
        val value=truth?.get("value")?.takeUnless{it==JsonNull}?.jsonPrimitive?.doubleOrNull?:return null
        val raw=truth["truth_unit"]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content?:return null
        val unit=TruthInput.Unit.entries.firstOrNull{it.raw==raw}?:return null
        return TruthEntry(value,unit)
    }

    /** Never erase a failed field capture, even if the failure note itself
     * cannot be written. Return uncommitted input to the UI as before. */
    internal fun preserveFailedBundle(context:Context,id:String,reason:String):TruthEntry?=synchronized(pendingLock){
        val dir=bundleDir(context,id)
        if(dir.isDirectory)try{
            val note=JSONObject().put("schema",1).put("measurement_id",id).put("status","partial_write_failed")
                .put("created_at",iso8601()).put("reason",reason)
            val sidecar=File(dir,"pending_patch.json")
            if(sidecar.isFile)note.put("pending_edit",JSONObject(sidecar.readText()))
            writeAtomic(File(dir,"write_failure.json"),note.toString())
        }catch(_:Exception){/* Preserve existing bytes even with no space for the note. */}
        pendingTruthOf(context,id)
    }

    /// Human-readable reason for a failed write — a full disk is the one the
    /// field cares about, so it is named explicitly.
    private fun writeFailureReason(context: Context, t: Throwable): String {
        val free = freeSpaceBytes(context)
        if (free < MIN_FREE_BYTES) return lowStorageReason(free)
        return t::class.java.simpleName + (t.message?.let { ": $it" } ?: "")
    }

    // MARK: - JSON builders (schema-locked)

    private fun contextJson(ctx: CaptureContext): JSONObject = JSONObject().apply {
        put("mode", ctx.mode)
        put("project_id", ctx.projectId ?: JSONObject.NULL)
        put("plot_id", ctx.plotId ?: JSONObject.NULL)
        put("tree_number", ctx.treeNumber ?: JSONObject.NULL)
    }

    private fun settingsJson(
        algorithm: String, units: String, captureMode: String, cal: ProjectCalibration,
    ): JSONObject = JSONObject().apply {
        put("algorithm", algorithm)
        put("units", units)
        put("capture_mode", captureMode)
        // Where the height sighting angle comes from: the elevation of the
        // AR camera's forward axis, on both platforms (iOS writes the same).
        put("height_angle_source", "camera_pose")
        put("calibration", JSONObject().apply {
            put("alpha", cal.dbhCorrectionAlpha.toDouble())
            put("beta", cal.dbhCorrectionBeta.toDouble())
            put("depth_noise_mm", cal.depthNoiseMm.toDouble())
            put("vio_drift_fraction", cal.vioDriftFraction.toDouble())
            // WHICH ESTIMATOR alpha/beta were fitted against, carried so a
            // replay can put them back exactly as the capture had them.
            //
            // It was missing, and the omission silently disarmed the
            // coefficients on every replay: the rebuilt ProjectCalibration
            // defaulted to epoch 0, 0 never equals a live epoch, so
            // appliedToRawCm judged it stale and returned the RAW diameter.
            // For a calibrated project that made the epoch recompute write
            // uncalibrated widths back over calibrated ones — and the
            // difference, alpha + (beta-1)*raw, is about a percent, the same
            // size as a genuine epoch shift, so the review screen showed a
            // plausible correction and nothing marked it as a loss.
            // iOS RawCaptureManifest.Calibration parity, same key spelling.
            put("dbh_calibration_epoch", cal.dbhCalibrationEpoch)
        })
    }

    // result_live — value/sigma/tier are non-optional (iOS parity: 0/0/"red"
    // fallbacks), so a cross-platform decoder never meets a null here.
    //
    // TWO explicit booleans (identical on iOS), because the old single
    // `accepted` key meant "fit not red" on one platform and "the operator
    // pressed Accept" on the other — the same key, two different questions:
    //   tier_ok          — RECORD-TIME verdict: the live fit was not red.
    //   operator_accepted — the cruiser actually accepted this reading
    //                       (false at record time, set by markAccepted).
    // `accepted` is kept as a mirror of operator_accepted so bundles written
    // before this change still decode.
    private fun resultLiveJson(
        value: Double, sigma: Double, tier: String, accepted: Boolean,
        frameCount: Int, perFrame: List<Double>,
    ): JSONObject = JSONObject().apply {
        put("value", value)
        put("sigma", sigma)
        put("tier", tier)
        put("tier_ok", tier != "red")
        put("operator_accepted", accepted)
        put("accepted", accepted)
        // Frames actually stored (DBH: raw depth grids; height: aim depth
        // frames, 0-2). Same key + placement as iOS: `result_live.frame_count`.
        put("frame_count", frameCount)
        put("per_frame", JSONArray().apply { perFrame.forEach { put(it) } })
    }

    // truth — `value` is ALWAYS the app's metric base (cm for a diameter, m
    // for a height); `truth_unit` is the unit the operator actually TYPED.
    // Without the tag the export cannot tell an imperial entry from a metric
    // one, because the conversion has already happened by the time the number
    // is written. Null on a bundle with no truth and on bundles written before
    // the tag existed — a reader must treat that as "not stated", not metric.
    //
    // `repaired_from` is the number that WAS in `value` before TruthUnitRepair
    // re-based it, exactly as the cruiser typed it. Null on every truth that
    // has not been repaired, which is nearly all of them. It is an audit crumb,
    // not the idempotence mark — `truth_unit` is that, because "no unit
    // recorded" is the whole definition of an affected truth. It is written
    // unconditionally, like the other three, so the two platforms' manifests
    // stay one document.
    private fun truthJson(
        value: Double?,
        enteredAt: String?,
        unit: TruthInput.Unit?,
        repairedFrom: Double? = null,
    ): JSONObject = JSONObject().apply {
        put("value", value ?: JSONObject.NULL)
        put("entered_at", enteredAt ?: JSONObject.NULL)
        put("truth_unit", unit?.raw ?: JSONObject.NULL)
        put("repaired_from", repairedFrom ?: JSONObject.NULL)
    }

    // GPS block — {lat, lon, acc_m}, byte-identical to the iOS schema.
    private fun gpsJson(fix: CLLocationSnapshot?): Any {
        if (fix == null) return JSONObject.NULL
        return JSONObject().apply {
            put("lat", fix.latitude)
            put("lon", fix.longitude)
            put("acc_m", fix.horizontalAccuracyM)
        }
    }

    /// replay_selfcheck: {status, rerun_value, delta}, plus rerun_value_reposed
    /// for height (the pose-reposed re-derivation — omitted for DBH, iOS parity).
    private fun selfCheckJson(live: Double?, rerun: Double?, rerunReposed: Double?): JSONObject =
        JSONObject().apply {
            val pass = live != null && rerun != null &&
                (abs(live) < 1e-9 && abs(rerun) < 1e-9 ||
                    abs(live - rerun) <= SELF_CHECK_REL_TOL * maxOf(abs(live), 1e-6))
            put("status", if (pass) "pass" else "fail")
            put("rerun_value", rerun ?: JSONObject.NULL)
            put("delta", if (live != null && rerun != null) (rerun - live) else JSONObject.NULL)
            if (rerunReposed != null) put("rerun_value_reposed", rerunReposed)
        }

    /// Guide-axis fractions + the reconstructed guide axis derived ONCE from
    /// the manual bracket's view-space handles (mapped through the frame's
    /// view→depth affine). View-independent, so the manifest stores only the
    /// fractions (iOS bracket schema parity).
    private data class BracketGeom(
        val leftFrac: Double, val rightFrac: Double,
        val axis: GuideAxis, val axisStr: String,
        val tapX: Double, val tapY: Double,
    )

    private fun deriveBracket(frame0: ArDepthFrame, b: BracketSpec): BracketGeom? {
        val geometry = DBHEstimator.bracketDepthGeometry(
            frame0, b.leftViewX, b.rightViewX, b.guideViewY,
        ) ?: return null
        val row = geometry.axis is GuideAxis.Row
        val mid = (geometry.leftFraction + geometry.rightFraction) * 0.5
        val tapX = if (row) mid * frame0.width else (geometry.axis as GuideAxis.Col).x.toDouble()
        val tapY = if (row) (geometry.axis as GuideAxis.Row).y.toDouble() else mid * frame0.height
        return BracketGeom(
            geometry.leftFraction, geometry.rightFraction, geometry.axis,
            if (row) "row" else "col", tapX, tapY,
        )
    }

    /// Attach the independently available depth/confidence and RGB. Native
    /// u16 bytes are retained; f32m is a lossless fallback, not a conversion
    /// to synthetic millimetres. Returns the actual depth attachment count.
    private fun attachAimFrame(
        dir: File, aim: HeightAim?, depthName: String, rgbName: String, json: JSONObject,
    ): Boolean {
        aim ?: return false
        var haveRgb = false
        aim.rgb?.let { bytes ->
            haveRgb = try {
                if (bytes.isNotEmpty()) { File(dir, rgbName).writeBytes(bytes); true } else false
            } catch (_: Throwable) { false }
        }
        json.put("rgb_file", if (haveRgb) rgbName else JSONObject.NULL)
        val frame = aim.frame ?: return false
        val stored=RawDepthGridIO.write(frame,dir,depthName)
        json.put("depth_file", depthName)
        json.put("confidence_file", stored.confidenceFile)
        json.put("width", frame.width)
        json.put("height", frame.height)
        json.put("format", stored.format)
        json.put("fx", frame.fx)
        json.put("fy", frame.fy)
        json.put("cx", frame.cx)
        json.put("cy", frame.cy)
        return true
    }

    private val IDENTITY_AFFINE = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

    private fun mapAlgorithm(raw: String): String = when (raw) {
        "band" -> "depthBand"
        else -> "silhouette"
    }

    private fun floatArr(a: FloatArray): JSONArray = JSONArray().apply { a.forEach { put(it.toDouble()) } }

    private fun appCommit(context: Context): String = try {
        val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pkg.longVersionCode
        else @Suppress("DEPRECATION") pkg.versionCode.toLong()
        // iOS format parity: "est<epoch>/<shortVersion> (<build>)".
        //
        // THE ESTIMATOR EPOCH LEADS, because the marketing version does
        // not move and this field is what has to partition a corpus. It
        // failed to: the bracket depth trim shipped partway through a
        // 100-stem collection and both eras stamped the same string.
        "est${DBHEstimator.ESTIMATOR_EPOCH}/${pkg.versionName} ($code)"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun iso8601(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

}
