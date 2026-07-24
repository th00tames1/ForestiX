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
    private const val SELF_CHECK_REL_TOL = 1e-3

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

    /// Serialize one DBH burst. `frames` must carry rawDepthMm (recorder
    /// armed). Returns the bundle id, or null if there's nothing
    /// reproducible (fewer than 5 raw frames). Runs entirely off the main
    /// thread.
    suspend fun recordDbh(
        context: Context,
        frames: List<ArDepthFrame>,
        tapX: Double,
        tapY: Double,
        axisRow: Boolean,
        bracket: BracketSpec?,
        cal: ProjectCalibration,
        algorithmRaw: String,          // "silhouette" | "band"
        unitSystem: String,            // "metric" | "imperial"
        ctx: CaptureContext,
        captureRgb: (File) -> Boolean,
    ): String? = withContext(Dispatchers.IO) {
        val raw = frames.filter { it.rawDepthMm != null }.take(MAX_FRAMES)
        if (raw.size < 5) return@withContext null
        val id = UUID.randomUUID().toString()
        val dir = bundleDir(context, id)
        dir.mkdirs()
        try {
            // 1) Raw depth grids.
            raw.forEachIndexed { i, f -> writeU16Le(File(dir, "depth_$i.bin"), f.rawDepthMm!!) }
            // 2) One reference RGB (first frame). Best-effort — a miss just
            //    leaves rgb_file null.
            val rgbFile = File(dir, "rgb_0.jpg")
            val haveRgb = try { captureRgb(rgbFile) } catch (_: Throwable) { false }

            // 3) Live estimator result over the STORED frames (the
            //    reproducible unit the self-check re-derives). For the manual
            //    bracket the view-space handles are converted ONCE to
            //    view-independent DEPTH-guide-axis fractions (matching the
            //    iOS bracket schema — no view size / no guide_y stored), then
            //    the recording + replay share DBHEstimator.bracketChordEstimate.
            val algorithm = ChordAlgorithm.fromRaw(algorithmRaw)
            val bracketGeom = if (bracket != null) deriveBracket(raw.first(), bracket) else null
            if (bracket != null && bracketGeom == null) { dir.deleteRecursively(); return@withContext null }
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
            // accepted starts false at capture (recording fires at burst
            // end, before the cruiser decides) — flipped by markAccepted on
            // Accept. iOS parity, so cross-platform "acceptance" matches.
            manifest.put("result_live", resultLiveJson(
                value = liveRes?.diameterCm?.toDouble() ?: 0.0,
                sigma = liveRes?.sigmaRmm?.toDouble() ?: 0.0,
                tier = liveRes?.confidence?.raw ?: "red",
                accepted = false,
                perFrame = perFrame,
            ))
            manifest.put("truth", truthJson(null, null))
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

            File(dir, "manifest.json").writeText(manifest.toString())
            id
        } catch (_: Throwable) {
            dir.deleteRecursively()
            null
        }
    }

    // MARK: - Height recording

    suspend fun recordHeight(
        context: Context,
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
        unitSystem: String,
        ctx: CaptureContext,
        baseAim: HeightAim? = null,
        topAim: HeightAim? = null,
    ): String? = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
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
                sigma = live.sigmaHm.toDouble(),
                tier = live.confidence.raw,
                accepted = false,          // flipped by markAccepted on Accept
                perFrame = emptyList(),
            ))
            manifest.put("truth", truthJson(null, null))
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
            attachAimFrame(dir, baseAim, "depth_base.bin", "rgb_base.jpg", baseJson)
            heightBlock.put("base", baseJson)
            val topJson = JSONObject()
            topJson.put("pitch_deg", topPitchDeg.toDouble())
            topJson.put("camera_pose", floatArr(topPose))
            // Schema 2: top-aim depth + RGB (keys omitted when unavailable).
            attachAimFrame(dir, topAim, "depth_top.bin", "rgb_top.jpg", topJson)
            heightBlock.put("top", topJson)
            heightBlock.put("d_h_m", dHm.toDouble())
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

            File(dir, "manifest.json").writeText(manifest.toString())
            id
        } catch (_: Throwable) {
            dir.deleteRecursively()
            null
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
        val selfCheckStatus: String?,
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
                selfCheckStatus = selfObj?.optString("status")?.takeIf { it.isNotEmpty() },
            )
        }.sortedByDescending { it.createdAt }
    }

    fun manifestOf(context: Context, id: String): JSONObject? {
        val mf = File(bundleDir(context, id), "manifest.json")
        if (!mf.exists()) return null
        return try { JSONObject(mf.readText()) } catch (_: Throwable) { null }
    }

    fun dirOf(context: Context, id: String): File = bundleDir(context, id)

    fun count(context: Context): Int =
        root(context).listFiles()?.count { it.isDirectory && File(it, "manifest.json").exists() } ?: 0

    fun totalSizeBytes(context: Context): Long = root(context).walkTopDown()
        .filter { it.isFile }.map { it.length() }.sum()

    fun clearAll(context: Context) {
        root(context).deleteRecursively()
    }

    fun delete(context: Context, id: String) {
        bundleDir(context, id).deleteRecursively()
    }

    /// Flip a bundle's result_live.accepted to true — called from the scan
    /// screen when the cruiser taps Accept (recording happens earlier, at
    /// burst finalize / height compute). iOS RawCaptureStore.markAccepted parity.
    suspend fun markAccepted(context: Context, id: String): Boolean =
        withContext(Dispatchers.IO) {
            val m = manifestOf(context, id) ?: return@withContext false
            val res = m.optJSONObject("result_live") ?: return@withContext false
            res.put("accepted", true)
            try {
                File(bundleDir(context, id), "manifest.json").writeText(m.toString())
                true
            } catch (_: Throwable) {
                false
            }
        }

    /// Persist an operator-entered ground-truth value into a bundle's
    /// manifest (dev replay screen). Rewrites manifest.json in place.
    suspend fun setTruth(context: Context, id: String, value: Double?): Boolean =
        withContext(Dispatchers.IO) {
            val m = manifestOf(context, id) ?: return@withContext false
            m.put("truth", truthJson(value, if (value != null) iso8601() else null))
            try {
                File(bundleDir(context, id), "manifest.json").writeText(m.toString())
                true
            } catch (_: Throwable) {
                false
            }
        }

    /// Zip the whole raw-captures directory into the shared export cache and
    /// return a shareable Uri (same FileProvider flow as the research CSV).
    suspend fun exportZipUri(context: Context): Uri? = withContext(Dispatchers.IO) {
        val src = root(context)
        val bundles = src.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (bundles.isEmpty()) return@withContext null
        val dir = File(context.cacheDir, "Exports").apply { mkdirs() }
        val zip = File(dir, "forestix_raw_captures.zip")
        try {
            ZipOutputStream(zip.outputStream().buffered()).use { zos ->
                src.walkTopDown().filter { it.isFile }.forEach { file ->
                    val rel = file.relativeTo(src).path
                    zos.putNextEntry(ZipEntry(rel))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zip)
        } catch (_: Throwable) {
            null
        }
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
    private fun resultLiveJson(
        value: Double, sigma: Double, tier: String, accepted: Boolean, perFrame: List<Double>,
    ): JSONObject = JSONObject().apply {
        put("value", value)
        put("sigma", sigma)
        put("tier", tier)
        put("accepted", accepted)
        put("per_frame", JSONArray().apply { perFrame.forEach { put(it) } })
    }

    private fun truthJson(value: Double?, enteredAt: String?): JSONObject = JSONObject().apply {
        put("value", value ?: JSONObject.NULL)
        put("entered_at", enteredAt ?: JSONObject.NULL)
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
    private fun attachAimFrame(
        dir: File, aim: HeightAim?, depthName: String, rgbName: String, json: JSONObject,
    ) {
        val frame = aim?.frame ?: return
        val raw = frame.rawDepthMm ?: return
        if (raw.size != frame.width * frame.height) return
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
