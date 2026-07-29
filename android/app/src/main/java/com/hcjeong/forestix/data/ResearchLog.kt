// Research log — the data-capture spine of the ForestiX validation study.
// 1:1 port of iOS App/ResearchLog.swift: IDENTICAL column order so the two
// platforms' CSVs concatenate for the cross-platform accuracy analysis.
//
// WIRED (developer mode only): the DBH / Height / Distance screens append a
// row on every accepted reading, with an optional user-entered true value →
// error column. Settings › Developer exports/clears this CSV.

package com.hcjeong.forestix.data

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object ResearchLog {

    /// Canonical column order — keep BYTE-FOR-BYTE identical to the iOS
    /// ResearchLog.columns so the two platforms' exports concatenate.
    val COLUMNS = listOf(
        "timestamp_iso", "platform", "os_version", "device_model",
        "measure_type", "method", "depth_source",
        "tree_id", "repeat", "true_value", "measured_value", "unit", "error",
        "sigma", "confidence_tier", "distance_m", "n_points", "arc_deg", "rmse_mm",
        "pitch_deg", "alpha_top_deg", "alpha_base_deg",
        "fx", "depth_w", "depth_h", "depth_noise_mm",
        "raw_points_path", "species", "note",
        // aim_drift_m — how far the instrument moved between the base and
        // the top sighting on a walk-off height. The tangent identity
        // H = d_h(tan a_top - tan a_base) assumes ONE origin, so this is the
        // size of the assumption violation, and the error it implies is
        // roughly the vertical component one-for-one plus the radial
        // component scaled by H/d_h. It used to be shown to the cruiser at
        // accept time and then discarded, which left the analyst unable to
        // flag, weight or exclude a drifted row — including every row that
        // stayed under the warning threshold and so was never announced at
        // all. Empty for non-height rows and when no pose was available.
        "aim_drift_m",
        // truth_unit — the unit the operator TYPED `true_value` in ("cm" |
        // "in" | "m" | "ft"). `true_value` itself is always the metric base,
        // normalised on the way in, so nothing else in the row says whether
        // the cruiser was working in inches or centimetres. Recording it makes
        // the reconciliation arithmetic instead of guesswork. Empty when no
        // truth was typed. NEW COLUMNS GO AT THE END: `prepareFile`
        // re-emits an older on-disk log under this order by column NAME, so
        // every row a device already holds keeps its columns where they were
        // and a pooled export sees one schema.
        "truth_unit",
        // tracking_dropped — "true" | "false" on a walk-off height row: did
        // VIO tracking drop at any point between anchoring the trunk and the
        // aim taps? A dropout moves the world frame the trunk anchor sits in,
        // so it moves d_h — and d_h is the ENTIRE scale of H, which makes it a
        // larger threat to the reading than the aim drift beside it. The
        // cruiser is warned on screen and can retake, but before this column an
        // accepted run taken across a dropout exported byte-identical to a
        // clean one and the analyst could neither flag nor exclude it. Empty on
        // rows with no walk-off (DBH, typed manual heights).
        "tracking_dropped",
    )

    private fun file(context: Context): File =
        File(context.filesDir, "forestix_research_log.csv")

    fun hasData(context: Context): Boolean = file(context).exists()

    fun rowCount(context: Context): Int {
        val f = file(context)
        if (!f.exists()) return 0
        return maxOf(0, f.readLines().count { it.isNotBlank() } - 1) // minus header
    }

    /// Append one measurement. The caller supplies the measurement-specific
    /// fields; this fills timestamp / platform / os / device. Same append-only
    /// contract as iOS (header written once, rows never truncated).
    @Synchronized
    fun record(context: Context, fields: Map<String, String>) {
        val f = HashMap(fields)
        f["timestamp_iso"] = timestamp()
        f["platform"] = "Android"
        f["os_version"] = "Android ${Build.VERSION.RELEASE}"
        f["device_model"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        val out = file(context)
        // Bring an older on-disk header up to the current column order FIRST:
        // a row is written positionally, so appending under a header this
        // build no longer matches is silent corruption.
        val state = prepareFile(out)
        if (state == FileState.UNUSABLE) return
        // 1-based repeat per (tree_id, measure_type), counted from the CSV so
        // it survives restarts. Runs AFTER the migration, so the indices it
        // reads are the ones the file now actually uses. Safe naive comma
        // split: the only field that can contain a comma (note) sits AFTER
        // the indices read here.
        val tree = f["tree_id"]
        if (!tree.isNullOrEmpty() && f["repeat"] == null) {
            f["repeat"] = "${nextRepeat(context, tree, f["measure_type"] ?: "")}"
        }
        val row = COLUMNS.joinToString(",") { csvEscape(f[it] ?: "") }
        try {
            if (state == FileState.FRESH) out.writeText(COLUMNS.joinToString(",") + "\n")
            out.appendText(row + "\n")
        } catch (_: Exception) {
            // Skip the row rather than corrupt the log.
        }
    }

    // MARK: - Header migration

    /// What the log on disk is, from the point of view of the next append.
    private enum class FileState {
        /// Nothing usable on disk — write the header, then the row.
        FRESH,
        /// The header on disk IS the current column order — append.
        READY,
        /// The header could not be brought up to date. Nothing may be
        /// appended: a mismatched row is worse than a missing one.
        UNUSABLE,
    }

    /// True once the on-disk header has been confirmed to be the current
    /// column order in this process. The check parses the whole file, and the
    /// header can only change under us via [clear], which resets this. Both
    /// are reached only from @Synchronized members.
    private var headerIsCurrent = false

    /// Bring a log written by an EARLIER build up to the current column order
    /// before anything is appended to it.
    ///
    /// A row is written positionally, so appending a 31-field row under the
    /// 30-name header a device already carries leaves every column after the
    /// difference reading shifted: the pooled export either raises "Expected
    /// 30 fields, saw 31" or, with bad lines suppressed, drops exactly the
    /// newest rows. Deleting the log was the only remedy, which throws away
    /// the field days already recorded.
    ///
    /// Old rows are re-emitted under the new order by COLUMN NAME. A column
    /// the old build never had is written empty — that is what "this build did
    /// not record it" means, and no value is invented. If the old header names
    /// a column this build no longer has, re-emitting would silently drop that
    /// data, so the file is set aside whole and a new log is started instead.
    private fun prepareFile(out: File): FileState {
        if (!out.exists()) {
            headerIsCurrent = false
            return FileState.FRESH
        }
        if (headerIsCurrent) return FileState.READY
        // Exists but unreadable: do NOT fall through to FRESH, whose writeText
        // would truncate the whole log.
        val text = try { out.readText() } catch (_: Exception) { return FileState.UNUSABLE }
        val records = parseRecords(text)
        // Exists but holds no record (zero-length, or only blank lines): safe
        // to start it over under the current header.
        val header = records.firstOrNull() ?: return FileState.FRESH
        if (header == COLUMNS) {
            headerIsCurrent = true
            return FileState.READY
        }
        if (header.any { it !in COLUMNS }) {
            return if (archive(out)) FileState.FRESH else FileState.UNUSABLE
        }
        val sb = StringBuilder(COLUMNS.joinToString(",")).append('\n')
        records.drop(1).forEach { row ->
            val byName = HashMap<String, String>()
            header.forEachIndexed { i, name -> if (i < row.size) byName[name] = row[i] }
            sb.append(COLUMNS.joinToString(",") { csvEscape(byName[it] ?: "") }).append('\n')
        }
        // Write-then-rename: a crash mid-migration must not leave a
        // half-written log where the whole day's rows used to be.
        val tmp = File(out.parentFile, out.name + ".migrating")
        return try {
            tmp.writeText(sb.toString())
            if (tmp.renameTo(out)) {
                headerIsCurrent = true
                FileState.READY
            } else {
                tmp.delete()
                FileState.UNUSABLE
            }
        } catch (_: Exception) {
            tmp.delete()
            FileState.UNUSABLE
        }
    }

    /// Move a log this build cannot re-emit out of the way, KEEPING IT WHOLE,
    /// so a new log can start under the current header. Returns false when it
    /// could not be moved — in which case nothing may be appended.
    private fun archive(src: File): Boolean {
        for (n in 1..50) {
            val dst = File(src.parentFile, "forestix_research_log.legacy$n.csv")
            if (dst.exists()) continue
            return src.renameTo(dst)
        }
        return false
    }

    /// Split CSV text into records of fields, honouring the quoting
    /// [csvEscape] emits: a quoted field may contain ',', '"' and newlines, so
    /// neither a naive line split nor a naive comma split can be used to
    /// rewrite the file. Blank lines are skipped.
    internal fun parseRecords(text: String): List<List<String>> {
        val records = ArrayList<List<String>>()
        var record = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        // Tells a genuinely empty trailing field apart from "no field yet", so
        // a trailing newline doesn't append a phantom one-field record.
        var started = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')   // "" inside quotes is one quote
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    field.append(c)
                }
            } else when (c) {
                '"' -> { inQuotes = true; started = true }
                ',' -> { record.add(field.toString()); field.setLength(0); started = true }
                '\n', '\r' -> {
                    if (started || field.isNotEmpty()) {
                        record.add(field.toString())
                        records.add(record)
                    }
                    record = ArrayList()
                    field.setLength(0)
                    started = false
                }
                else -> { field.append(c); started = true }
            }
            i++
        }
        if (started || field.isNotEmpty()) {
            record.add(field.toString())
            records.add(record)
        }
        return records
    }

    private fun nextRepeat(context: Context, treeId: String, measureType: String): Int {
        val src = file(context)
        if (!src.exists()) return 1
        val typeIdx = COLUMNS.indexOf("measure_type")
        val treeIdx = COLUMNS.indexOf("tree_id")
        var n = 0
        src.readLines().drop(1).forEach { line ->
            val cols = line.split(",")
            if (cols.size > maxOf(typeIdx, treeIdx) &&
                cols[typeIdx] == measureType && cols[treeIdx] == treeId
            ) n++
        }
        return n + 1
    }

    /// Copy the log into the shared-export cache and return a shareable Uri
    /// (same FileProvider flow as QuickMeasureHistory.writeExport).
    fun exportUri(context: Context): Uri? {
        val src = file(context)
        if (!src.exists()) return null
        val dir = File(context.cacheDir, "Exports").apply { mkdirs() }
        val dst = File(dir, "forestix_research_log.csv")
        src.copyTo(dst, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
    }

    @Synchronized
    fun clear(context: Context) {
        file(context).delete()
        headerIsCurrent = false
    }

    private fun timestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    private fun csvEscape(s: String): String =
        if (s.contains(",") || s.contains("\"") || s.contains("\n"))
            "\"" + s.replace("\"", "\"\"") + "\""
        else s
}
