// Raw-capture STORE — writes and manages the replay bundles. Each bundle is
// a directory filesDir/raw-captures/<uuid>/ holding manifest.json plus, for
// DBH, the raw depth grids (depth_0..N.bin, native u16 mm, little-endian,
// row-major) and one reference rgb_0.jpg. The manifest schema is LOCKED and
// key-identical with the iOS sibling (platform "android", depth format
// "u16mm") so the two platforms' bundles pool for the cross-platform
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /// Result of a truth write. QUEUED is NOT durable — the value sits in the
    /// pending queue until the in-flight bundle write folds it in, so a caller
    /// must keep the operator's typed text until it sees SAVED.
    enum class TruthWrite { SAVED, QUEUED, FAILED }

    /// Truth / acceptance typed BEFORE the async bundle write finished.
    /// Held here and folded into the manifest by the recorder itself, so a
    /// fast Accept can never drop a hand-measured value (the old code only
    /// wrote truth when lastRecordedBundleID already existed).
    private class PendingEdit {
        var truth: Double? = null
        var truthUnit: TruthInput.Unit? = null
        var hasTruth: Boolean = false
        var accepted: Boolean = false
    }

    private val pendingLock = Any()
    private val pending = HashMap<String, PendingEdit>()

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
    /// frame grabbed AT the base/top tap (its rawDepthMm u16 grid + intrinsics
    /// are what get serialized) and the reference RGB JPEG bytes captured at
    /// the same instant. Either half may be null — a null frame (or a frame
    /// with no armed raw depth) leaves the aim block schema-1-shaped, and
    /// replay falls back to the tangent method exactly as before.
    data class HeightAim(val frame: ArDepthFrame?, val rgb: ByteArray?)

    // MARK: - Directory

    private fun root(context: Context): File =
        File(context.filesDir, "raw-captures").apply { mkdirs() }

    private fun bundleDir(context: Context, id: String): File = File(root(context), id)

    // MARK: - DBH recording

    /// Serialize one DBH burst under the id the caller already minted
    /// (newBundleId), so an Accept racing this write still lands its truth.
    /// `frames` must carry rawDepthMm (recorder armed); `rgb` is the
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
    ): RecordOutcome = withContext(Dispatchers.IO) {
        val raw = frames.filter { it.rawDepthMm != null }.take(MAX_FRAMES)
        if (raw.size < MIN_FRAMES) {
            return@withContext RecordOutcome(
                null, 0, "no raw depth frames in this burst", queuedTruthLost = dropPending(id))
        }
        val free = freeSpaceBytes(context)
        if (free < MIN_FREE_BYTES) {
            return@withContext RecordOutcome(
                null, 0, lowStorageReason(free), queuedTruthLost = dropPending(id))
        }
        val dir = bundleDir(context, id)
        dir.mkdirs()
        try {
            // 1) Raw depth grids.
            raw.forEachIndexed { i, f -> writeU16Le(File(dir, "depth_$i.bin"), f.rawDepthMm!!) }
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
                dir.deleteRecursively()
                return@withContext RecordOutcome(
                    null, 0, "bracket geometry couldn't be mapped to the depth frame",
                    queuedTruthLost = dropPending(id))
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
                fo.put("format", "u16mm")
                fo.put("fx", f.fx); fo.put("fy", f.fy)
                fo.put("cx", f.cx); fo.put("cy", f.cy)
                fo.put("tap_px", JSONArray().put(effTapX).put(effTapY))
                fo.put("axis", effAxisStr)
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

            // 5) Reproducibility self-check — reload from disk, re-run.
            val rerun = try { RawCaptureReplay.rerunDbh(dir, manifest)?.value } catch (_: Throwable) { null }
            manifest.put("replay_selfcheck",
                selfCheckJson(liveRes?.diameterCm?.toDouble(), rerun, null))

            val foldedTruth = writeManifest(id, dir, manifest)
            RecordOutcome(id, raw.size, null, queuedTruthSaved = foldedTruth)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            RecordOutcome(
                null, 0, writeFailureReason(context, t), queuedTruthLost = dropPending(id))
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
    ): RecordOutcome = withContext(Dispatchers.IO) {
        val free = freeSpaceBytes(context)
        if (free < MIN_FREE_BYTES) {
            return@withContext RecordOutcome(
                null, 0, lowStorageReason(free), queuedTruthLost = dropPending(id))
        }
        val dir = bundleDir(context, id)
        dir.mkdirs()
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
            RecordOutcome(id, aimFrames, null, queuedTruthSaved = foldedTruth)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            RecordOutcome(
                null, 0, writeFailureReason(context, t), queuedTruthLost = dropPending(id))
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
            Summary(
                id = d.name,
                kind = kind,
                createdAt = m.optString("created_at"),
                treeNumber = ctxObj?.optInt("tree_number", -1)?.takeIf { it >= 0 },
                mode = ctxObj?.optString("mode") ?: "quick",
                liveValue = resObj?.optDouble("value")?.takeIf { !it.isNaN() },
                unit = if (kind == "height") "m" else "cm",
                tier = resObj?.optString("tier")?.takeIf { it.isNotEmpty() },
                truthValue = truthObj?.optDouble("value")?.takeIf { !it.isNaN() },
                truthUnit = truthObj?.optString("truth_unit")?.takeIf { it.isNotEmpty() },
                selfCheckStatus = selfObj?.optString("status")?.takeIf { it.isNotEmpty() },
                frameCount = resObj?.optInt("frame_count", -1)?.takeIf { it >= 0 }
                    ?: m.optJSONObject("dbh")?.optJSONArray("frames")?.length(),
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
            synchronized(pendingLock) {
                val file = manifestFile(context, id)
                if (!file.exists()) {
                    pending.getOrPut(id) { PendingEdit() }.accepted = true
                    return@synchronized true
                }
                val m = manifestOf(context, id) ?: return@synchronized false
                applyAccepted(m)
                writeQuietly(file, m)
            }
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
            synchronized(pendingLock) {
                val file = manifestFile(context, id)
                if (!file.exists()) {
                    pending.getOrPut(id) { PendingEdit() }.also {
                        it.truth = value
                        it.truthUnit = if (value != null) unit else null
                        it.hasTruth = true
                    }
                    return@synchronized TruthWrite.QUEUED
                }
                val m = manifestOf(context, id) ?: return@synchronized TruthWrite.FAILED
                m.put("truth", truthJson(
                    value,
                    if (value != null) iso8601() else null,
                    if (value != null) unit else null,
                ))
                if (writeQuietly(file, m)) TruthWrite.SAVED else TruthWrite.FAILED
            }
        }

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
            val edit = pending.remove(id)
            if (edit != null) applyPending(manifest, edit)
            try {
                writeAtomic(File(dir, "manifest.json"), manifest.toString())
            } catch (t: Throwable) {
                if (edit != null) pending[id] = edit
                throw t
            }
            edit?.let { truthEntryOf(it) }
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
            runCatching { out.fd.sync() }
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

    private fun applyPending(manifest: JSONObject, edit: PendingEdit) {
        if (edit.hasTruth) {
            manifest.put(
                "truth",
                truthJson(
                    edit.truth,
                    if (edit.truth != null) iso8601() else null,
                    edit.truthUnit,
                ),
            )
        }
        if (edit.accepted) applyAccepted(manifest)
    }

    /// The queued value + its typed unit, or null when the edit carried no
    /// truth (or was an explicit clear). A truth with no unit tag cannot be
    /// handed back to a field without guessing its scale, so it is reported as
    /// "nothing to hand back" rather than assumed metric.
    private fun truthEntryOf(edit: PendingEdit): TruthEntry? {
        if (!edit.hasTruth) return null
        val v = edit.truth ?: return null
        val u = edit.truthUnit ?: return null
        return TruthEntry(v, u)
    }

    private fun applyAccepted(manifest: JSONObject) {
        val res = manifest.optJSONObject("result_live") ?: return
        res.put("operator_accepted", true)
        res.put("accepted", true)          // legacy mirror (older readers)
    }

    /// Discard a bundle's queued edits after its write failed, RETURNING the
    /// ground truth that died with it (null when none was queued, or when the
    /// queued edit was an explicit clear). The caller must surface it — a
    /// queued truth thrown away in silence is exactly the invisible loss this
    /// whole path exists to prevent.
    private fun dropPending(id: String): TruthEntry? {
        val edit = synchronized(pendingLock) { pending.remove(id) } ?: return null
        return truthEntryOf(edit)
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
        put("calibration", JSONObject().apply {
            put("alpha", cal.dbhCorrectionAlpha.toDouble())
            put("beta", cal.dbhCorrectionBeta.toDouble())
            put("depth_noise_mm", cal.depthNoiseMm.toDouble())
            put("vio_drift_fraction", cal.vioDriftFraction.toDouble())
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
        val pL = frame0.viewToDepth(minOf(b.leftViewX, b.rightViewX), b.guideViewY) ?: return null
        val pR = frame0.viewToDepth(maxOf(b.leftViewX, b.rightViewX), b.guideViewY) ?: return null
        val dxSpan = abs(pR.first - pL.first)
        val dySpan = abs(pR.second - pL.second)
        val midX = (pL.first + pR.first) / 2.0
        val midY = (pL.second + pR.second) / 2.0
        return if (dxSpan >= dySpan) {
            val w = frame0.width.toDouble()
            BracketGeom(
                minOf(pL.first, pR.first) / w, maxOf(pL.first, pR.first) / w,
                GuideAxis.Row(Math.round(midY).toInt()), "row", midX, midY,
            )
        } else {
            val h = frame0.height.toDouble()
            BracketGeom(
                minOf(pL.second, pR.second) / h, maxOf(pL.second, pR.second) / h,
                GuideAxis.Col(Math.round(midX).toInt()), "col", midX, midY,
            )
        }
    }

    /// Serialize a height aim's depth grid (native u16-mm, little-endian,
    /// row-major — identical byte layout to the DBH depth grids) + reference
    /// RGB into the bundle, and add the schema-2 aim keys to `json`:
    /// depth_file, rgb_file, width, height, format, fx, fy, cx, cy — the SAME
    /// key names iOS writes (the depth FORMAT string stays platform-specific,
    /// "u16mm"). No-op (no keys added) when the frame or its armed raw depth is
    /// absent, so the aim block stays schema-1-shaped and tangent replay is
    /// unaffected.
    /// Returns true when a depth grid was actually attached (false = the aim
    /// block stays schema-1-shaped), so the caller can report how many aim
    /// frames a bundle really carries.
    private fun attachAimFrame(
        dir: File, aim: HeightAim?, depthName: String, rgbName: String, json: JSONObject,
    ): Boolean {
        val frame = aim?.frame ?: return false
        val raw = frame.rawDepthMm ?: return false
        if (raw.size != frame.width * frame.height) return false
        writeU16Le(File(dir, depthName), raw)
        var haveRgb = false
        aim.rgb?.let { bytes ->
            haveRgb = try {
                if (bytes.isNotEmpty()) { File(dir, rgbName).writeBytes(bytes); true } else false
            } catch (_: Throwable) { false }
        }
        json.put("depth_file", depthName)
        json.put("rgb_file", if (haveRgb) rgbName else JSONObject.NULL)
        json.put("width", frame.width)
        json.put("height", frame.height)
        json.put("format", "u16mm")
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
        // iOS format parity: "<shortVersion> (<build>)".
        "${pkg.versionName} ($code)"
    } catch (_: Throwable) {
        "unknown"
    }

    private fun iso8601(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun writeU16Le(file: File, shorts: ShortArray) {
        val buf = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(shorts)
        file.writeBytes(buf.array())
    }
}
