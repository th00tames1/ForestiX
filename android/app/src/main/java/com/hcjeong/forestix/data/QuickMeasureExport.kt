// CSV + 5-file ZIP bundle export — direct port of exportCSV / exportBundle
// from iOS QuickMeasureHistory.swift. Output columns, RFC-4180 quoting,
// CRLF line endings, and UTF-8 BOM all match the iOS app so spreadsheets
// open identically on either platform.

package com.hcjeong.forestix.data

import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.sensors.VolumeConversion
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt

object QuickMeasureExport {

    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    private fun isoStamp(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        return fmt.format(Date(epochMs))
    }

    /// RFC-4180 field quoting: wrap in quotes, double embedded quotes.
    private fun csv(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""

    private fun fmt(v: Double, p: Int = 3) = String.format(Locale.US, "%.${p}f", v)

    // MARK: - Single-file CSV ------------------------------------------------

    fun buildCsv(entries: List<QuickMeasureEntry>, plots: List<QuickMeasurePlot>): ByteArray {
        val headers = listOf(
            "id", "timestamp", "plot", "tree", "tree_name", "kind",
            // "truth" sits beside the value it is the truth OF, in the same
            // unit ("value_unit"); blank means none was ever entered.
            "value", "value_unit", "truth",
            "secondary_value", "secondary_unit",
            "sigma", "sigma_unit",
            "species", "position", "damage", "note",
            "confidence", "method",
            "latitude", "longitude", "photo",
            "capture_mode",
        )
        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { csv(it) }).append("\r\n")

        for (e in entries) {
            val sigma = e.sigma?.let { fmt(it) } ?: ""
            val plotName = e.plotID?.let { id -> plots.firstOrNull { it.id == id }?.name } ?: ""
            val secVal = e.secondaryValue?.let { fmt(it) } ?: ""
            val row = listOf(
                e.id.toString(),
                isoStamp(e.createdAt),
                plotName,
                e.treeNumber?.toString() ?: "",
                e.treeName ?: "",
                e.kind.raw,
                fmt(e.value),
                e.valueUnit,
                e.truth?.let { fmt(it) } ?: "",
                secVal,
                if (e.secondaryValue == null) "" else e.secondaryValueUnit,
                sigma,
                if (e.sigma == null) "" else e.sigmaUnit,
                e.speciesCode ?: "",
                e.position?.raw ?: "",
                e.damageCodes.joinToString("|"),
                e.note ?: "",
                e.confidenceRaw,
                e.method,
                e.latitude?.let { String.format(java.util.Locale.US, "%.6f", it) } ?: "",
                e.longitude?.let { String.format(java.util.Locale.US, "%.6f", it) } ?: "",
                e.photoPath ?: "",
                e.captureMode ?: "",
            ).joinToString(",") { csv(it) }
            sb.append(row).append("\r\n")
        }
        return BOM + sb.toString().toByteArray(Charsets.UTF_8)
    }

    // MARK: - 5-file ZIP bundle ----------------------------------------------

    fun buildBundleZip(
        entries: List<QuickMeasureEntry>,
        plots: List<QuickMeasurePlot>,
        logRule: LogRule,
    ): ByteArray {
        // -- Samples.csv --
        val samples = StringBuilder("id,name,unit,acres,type,baf_ft2_ac,radius_ft,created\r\n")
        for (p in plots) {
            samples.append(
                listOf(
                    p.id.toString(), p.name, p.unitName,
                    p.acres?.let { fmt(it) } ?: "",
                    p.typeRaw,
                    p.baf?.let { String.format(Locale.US, "%.0f", it) } ?: "",
                    p.radiusFt?.let { String.format(Locale.US, "%.1f", it) } ?: "",
                    isoStamp(p.createdAt),
                ).joinToString(",") { csv(it) }
            ).append("\r\n")
        }

        // -- Trees.csv (plot x treeNumber) --
        val trees = StringBuilder("plot_id,plot,tree_number,tree_name,species,damage,note\r\n")
        val byPlotTree = entries.groupBy { "${it.plotID ?: ""}|${it.treeNumber ?: -1}" }
        for ((_, group) in byPlotTree.toSortedMap()) {
            val any = group.first()
            val plotName = any.plotID?.let { id -> plots.firstOrNull { it.id == id }?.name } ?: ""
            val species = group.mapNotNull { it.speciesCode }.firstOrNull() ?: ""
            val dmg = group.flatMap { it.damageCodes }.toSet().joinToString("|")
            val note = group.mapNotNull { it.note }.firstOrNull() ?: ""
            // Any one reading on the tree carries the name; take the first
            // that has one rather than `any`'s, which may be a later
            // re-measurement recorded before the name existed.
            val name = group.mapNotNull { it.treeName }.firstOrNull() ?: ""
            trees.append(
                listOf(
                    any.plotID?.toString() ?: "", plotName,
                    any.treeNumber?.toString() ?: "", name, species, dmg, note,
                ).joinToString(",") { csv(it) }
            ).append("\r\n")
        }

        // -- Stems.csv (per DBH) --
        val stems = StringBuilder("id,plot_id,tree_number,timestamp,dbh_cm,truth_cm,sigma_mm,position,confidence,method,capture_mode\r\n")
        for (e in entries.filter { it.kind == MeasureKind.DBH }) {
            stems.append(
                listOf(
                    e.id.toString(), e.plotID?.toString() ?: "",
                    e.treeNumber?.toString() ?: "", isoStamp(e.createdAt),
                    fmt(e.value), e.truth?.let { fmt(it) } ?: "",
                    e.sigma?.let { fmt(it) } ?: "",
                    e.position?.raw ?: "", e.confidenceRaw, e.method,
                    e.captureMode ?: "",
                ).joinToString(",") { csv(it) }
            ).append("\r\n")
        }

        // -- Heights.csv (per Height) --
        val heights = StringBuilder("id,plot_id,tree_number,timestamp,height_m,truth_m,sigma_m,confidence,method\r\n")
        for (e in entries.filter { it.kind == MeasureKind.HEIGHT }) {
            heights.append(
                listOf(
                    e.id.toString(), e.plotID?.toString() ?: "",
                    e.treeNumber?.toString() ?: "", isoStamp(e.createdAt),
                    fmt(e.value), e.truth?.let { fmt(it) } ?: "",
                    e.sigma?.let { fmt(it) } ?: "",
                    e.confidenceRaw, e.method,
                ).joinToString(",") { csv(it) }
            ).append("\r\n")
        }

        // -- Calculations.csv (per-plot summary) --
        val calcs = StringBuilder("plot_id,plot,trees,ba_per_acre_ft2,tpa,qmd_cm,mean_h_m,total_bf,bf_per_acre,log_rule\r\n")
        for (p in plots) {
            val plotEntries = entries.filter { it.plotID == p.id }
            if (plotEntries.isEmpty()) continue
            val byTree = plotEntries.groupBy { it.treeNumber ?: -1 }
            val dbhTrees = byTree.mapNotNull { (_, group) ->
                val dbh = group.firstOrNull { it.kind == MeasureKind.DBH }?.value ?: return@mapNotNull null
                val h = group.firstOrNull { it.kind == MeasureKind.HEIGHT }?.value
                dbh to h
            }
            if (dbhTrees.isEmpty()) continue
            val acres = maxOf(p.acres ?: 0.1, 0.05)
            val baFt2 = dbhTrees.sumOf { (dbh, _) ->
                val inches = dbh / 2.54
                0.005454 * inches * inches
            }
            val baPerAcre = baFt2 / acres
            val tpa = dbhTrees.size / acres
            val qmd = sqrt(dbhTrees.sumOf { it.first * it.first } / dbhTrees.size)
            val heightsM = dbhTrees.mapNotNull { it.second }
            val meanH = if (heightsM.isEmpty()) "" else String.format(Locale.US, "%.2f", heightsM.sum() / heightsM.size)
            var bfTotal = 0.0
            for ((dbh, hOpt) in dbhTrees) {
                val h = hOpt ?: continue
                val bf = VolumeConversion.boardFeet(dbh, h, logRule) ?: continue
                bfTotal += bf
            }
            val bfPerAcre = if (bfTotal > 0) String.format(Locale.US, "%.0f", bfTotal / acres) else ""
            calcs.append(
                listOf(
                    p.id.toString(), p.name, dbhTrees.size.toString(),
                    String.format(Locale.US, "%.1f", baPerAcre),
                    String.format(Locale.US, "%.1f", tpa),
                    String.format(Locale.US, "%.2f", qmd),
                    meanH,
                    if (bfTotal > 0) String.format(Locale.US, "%.0f", bfTotal) else "",
                    bfPerAcre, logRule.name.lowercase(),
                ).joinToString(",") { csv(it) }
            ).append("\r\n")
        }

        fun payload(s: String) = BOM + s.toString().toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            mapOf(
                "Samples.csv" to samples,
                "Trees.csv" to trees,
                "Stems.csv" to stems,
                "Heights.csv" to heights,
                "Calculations.csv" to calcs,
            ).forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload(body.toString()))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
