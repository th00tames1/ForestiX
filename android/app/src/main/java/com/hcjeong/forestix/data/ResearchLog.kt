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
        // 1-based repeat per (tree_id, measure_type), counted from the CSV so
        // it survives restarts. Safe naive comma split: the only field that
        // can contain a comma (note) sits AFTER the indices read here.
        val tree = f["tree_id"]
        if (!tree.isNullOrEmpty() && f["repeat"] == null) {
            f["repeat"] = "${nextRepeat(context, tree, f["measure_type"] ?: "")}"
        }
        val row = COLUMNS.joinToString(",") { csvEscape(f[it] ?: "") }
        val out = file(context)
        try {
            if (!out.exists()) out.writeText(COLUMNS.joinToString(",") + "\n")
            out.appendText(row + "\n")
        } catch (_: Exception) {
            // Skip the row rather than corrupt the log.
        }
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

    fun clear(context: Context) {
        file(context).delete()
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
