// Port of iOS Export/CSVExporter.swift (spec §8 Export/CSVExporter).
// Phase 1 shipped plan-only exports; Phase 6 extends with tree-level,
// plot-level, and stand-summary CSVs.
//
// All rows use RFC 4180 quoting: any field containing comma, quote, or
// newline is wrapped in double quotes with `"` escaped as `""`. Lines use
// CRLF terminators. Floats are locale-independent (dot-decimal, %f via
// Locale.US).
//
// Column names include SI units where applicable ("_cm", "_m", "_deg",
// "_m2_per_acre", etc.) so downstream consumers don't have to guess.
// Per spec: volumes are always m³, lengths m, diameters cm — the Project
// unit system does **not** translate CSV column units (PDF does). CSV is
// the raw machine-readable dump and intentionally single-unit.
//
// Output is byte-identical to the iOS exporter for the same inputs: same
// column order, same "%.Nf" number formatting, uppercase UUIDs, UTC
// ISO-8601 timestamps.

package com.hcjeong.forestix.export

import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.inventory.PlotStats
import com.hcjeong.forestix.inventory.StandStat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/// Foundation `UUID.uuidString` is uppercase; `java.util.UUID.toString()` is
/// lowercase. Exports must emit the uppercase form so files produced by the
/// two platforms are byte-identical (and sort identically).
internal val UUID.uuidString: String
    get() = toString().uppercase(Locale.US)

object CSVExporter {

    // MARK: - Stratum list (Phase 1)

    fun stratumListCSV(strata: List<Stratum>): String {
        val lines = mutableListOf("stratum_id,name,area_acres")
        for (s in strata) {
            lines.add(
                listOf(
                    quote(s.id.uuidString),
                    quote(s.name),
                    format(s.areaAcres.toDouble(), places = 4),
                ).joinToString(",")
            )
        }
        return join(lines)
    }

    // MARK: - Planned plots (Phase 1)

    fun plannedPlotsCSV(
        plannedPlots: List<PlannedPlot>,
        strata: List<Stratum> = emptyList(),
    ): String {
        val byId: Map<UUID, Stratum> = strata.associateBy { it.id }
        val lines = mutableListOf(
            "plot_number,stratum_id,stratum_name,planned_lat,planned_lon,visited,skipped"
        )
        val sorted = plannedPlots.sortedBy { it.plotNumber }
        for (p in sorted) {
            val stratumName = p.stratumId?.let { byId[it]?.name } ?: ""
            lines.add(
                listOf(
                    "${p.plotNumber}",
                    quote(p.stratumId?.uuidString ?: ""),
                    quote(stratumName),
                    format(p.plannedLat, places = 7),
                    format(p.plannedLon, places = 7),
                    if (p.visited) "true" else "false",
                    if (p.skipped) "true" else "false",
                ).joinToString(",")
            )
        }
        return join(lines)
    }

    // MARK: - Tree-level CSV (Phase 6)

    /// Emit one row per Tree, in `treeNumber` order, including soft-deleted
    /// rows (with `deleted_at` populated) so the CSV is a lossless dump.
    fun treesCSV(trees: List<Tree>): String {
        val header = listOf(
            "id", "plot_id", "tree_number", "species_code", "status",
            "dbh_cm", "dbh_method",
            "dbh_sigma_mm", "dbh_rmse_mm", "dbh_coverage_deg",
            "dbh_n_inliers", "dbh_confidence", "dbh_is_irregular",
            "height_m", "height_method", "height_source",
            "height_sigma_m", "height_dh_m",
            "height_alpha_top_deg", "height_alpha_base_deg", "height_confidence",
            "bearing_from_center_deg", "distance_from_center_m", "boundary_call",
            "crown_class", "damage_codes",
            "is_multistem", "parent_tree_id",
            "notes", "photo_path", "raw_scan_path",
            "created_at", "updated_at", "deleted_at",
        ).joinToString(",")
        val lines = mutableListOf(header)
        val sorted = trees.sortedWith(
            compareBy({ it.plotId.uuidString }, { it.treeNumber })
        )
        for (t in sorted) {
            val cells = mutableListOf<String>()
            cells.add(quote(t.id.uuidString))
            cells.add(quote(t.plotId.uuidString))
            cells.add("${t.treeNumber}")
            cells.add(quote(t.speciesCode))
            cells.add(quote(t.status.raw))
            cells.add(format(t.dbhCm.toDouble(), places = 2))
            cells.add(quote(t.dbhMethod.raw))
            cells.add(optional(t.dbhSigmaMm, places = 2))
            cells.add(optional(t.dbhRmseMm, places = 2))
            cells.add(optional(t.dbhCoverageDeg, places = 2))
            cells.add(t.dbhNInliers?.toString() ?: "")
            cells.add(quote(t.dbhConfidence.raw))
            cells.add(if (t.dbhIsIrregular) "true" else "false")
            cells.add(optional(t.heightM, places = 2))
            cells.add(t.heightMethod?.let { quote(it.raw) } ?: "")
            cells.add(quote(t.heightSource ?: ""))
            cells.add(optional(t.heightSigmaM, places = 2))
            cells.add(optional(t.heightDHM, places = 2))
            cells.add(optional(t.heightAlphaTopDeg, places = 2))
            cells.add(optional(t.heightAlphaBaseDeg, places = 2))
            cells.add(t.heightConfidence?.let { quote(it.raw) } ?: "")
            cells.add(optional(t.bearingFromCenterDeg, places = 2))
            cells.add(optional(t.distanceFromCenterM, places = 2))
            cells.add(quote(t.boundaryCall ?: ""))
            cells.add(quote(t.crownClass ?: ""))
            cells.add(quote(t.damageCodes.joinToString(";")))
            cells.add(if (t.isMultistem) "true" else "false")
            cells.add(quote(t.parentTreeId?.uuidString ?: ""))
            cells.add(quote(t.notes))
            cells.add(quote(t.photoPath ?: ""))
            cells.add(quote(t.rawScanPath ?: ""))
            cells.add(iso8601(t.createdAt))
            cells.add(iso8601(t.updatedAt))
            cells.add(t.deletedAt?.let { iso8601(it) } ?: "")
            lines.add(cells.joinToString(","))
        }
        return join(lines)
    }

    // MARK: - Plot-level CSV (Phase 6)

    /// Per-plot position + aggregate row. `statsByPlot` supplies the already-
    /// computed PlotStats (tallies exclude soft-deleted trees); pass an empty
    /// map to emit position-only rows.
    fun plotsCSV(
        plots: List<Plot>,
        statsByPlot: Map<UUID, PlotStats> = emptyMap(),
    ): String {
        val header = listOf(
            "plot_id", "plot_number", "project_id", "planned_plot_id",
            "center_lat", "center_lon",
            "position_source", "position_tier",
            "gps_n_samples", "gps_median_h_accuracy_m", "gps_sample_std_xy_m",
            "offset_walk_m",
            "slope_deg", "aspect_deg", "plot_area_acres",
            "n_trees_live",
            "tpa", "ba_per_acre_m2", "qmd_cm",
            "gross_v_per_acre_m3", "merch_v_per_acre_m3",
            "started_at", "closed_at", "closed_by", "notes",
        ).joinToString(",")
        val lines = mutableListOf(header)
        val sorted = plots.sortedBy { it.plotNumber }
        for (p in sorted) {
            val s = statsByPlot[p.id]
            val cells = mutableListOf<String>()
            cells.add(quote(p.id.uuidString))
            cells.add("${p.plotNumber}")
            cells.add(quote(p.projectId.uuidString))
            cells.add(quote(p.plannedPlotId?.uuidString ?: ""))
            cells.add(format(p.centerLat, places = 7))
            cells.add(format(p.centerLon, places = 7))
            cells.add(quote(p.positionSource.raw))
            cells.add(quote(p.positionTier.raw))
            cells.add("${p.gpsNSamples}")
            cells.add(format(p.gpsMedianHAccuracyM.toDouble(), places = 3))
            cells.add(format(p.gpsSampleStdXyM.toDouble(), places = 3))
            cells.add(optional(p.offsetWalkM, places = 2))
            cells.add(format(p.slopeDeg.toDouble(), places = 2))
            cells.add(format(p.aspectDeg.toDouble(), places = 2))
            cells.add(format(p.plotAreaAcres.toDouble(), places = 4))
            cells.add(s?.let { "${it.liveTreeCount}" } ?: "")
            cells.add(s?.let { format(it.tpa.toDouble(), places = 2) } ?: "")
            cells.add(s?.let { format(it.baPerAcreM2.toDouble(), places = 4) } ?: "")
            cells.add(s?.let { format(it.qmdCm.toDouble(), places = 2) } ?: "")
            cells.add(s?.let { format(it.grossVolumePerAcreM3.toDouble(), places = 4) } ?: "")
            cells.add(s?.let { format(it.merchVolumePerAcreM3.toDouble(), places = 4) } ?: "")
            cells.add(iso8601(p.startedAt))
            cells.add(p.closedAt?.let { iso8601(it) } ?: "")
            cells.add(quote(p.closedBy ?: ""))
            cells.add(quote(p.notes))
            lines.add(cells.joinToString(","))
        }
        return join(lines)
    }

    // MARK: - Stand summary CSV (Phase 6)

    /// Three metrics (TPA, BA, Volume), one total row + one row per stratum.
    /// Layout: `metric,stratum_key,stratum_name,n_plots,mean,se_mean,
    /// variance,area_acres,weight,ci95_half_width,df_satterthwaite`.
    fun standSummaryCSV(
        tpa: StandStat,
        ba: StandStat,
        volume: StandStat,
        stratumNamesByKey: Map<String, String> = emptyMap(),
    ): String {
        val header = listOf(
            "metric", "stratum_key", "stratum_name",
            "n_plots", "mean", "se_mean", "variance",
            "area_acres", "weight",
            "ci95_half_width", "df_satterthwaite",
        ).joinToString(",")
        val lines = mutableListOf(header)

        fun emit(metric: String, stat: StandStat) {
            // Total row.
            lines.add(
                listOf(
                    quote(metric),
                    quote("TOTAL"),
                    quote("(all strata)"),
                    "${stat.nPlots}",
                    format(stat.mean, places = 4),
                    format(stat.seMean, places = 4),
                    "",
                    format(stat.byStratum.values.fold(0.0) { acc, s -> acc + s.areaAcres },
                        places = 4),
                    "1.0000",
                    format(stat.ci95HalfWidth, places = 4),
                    format(stat.dfSatterthwaite, places = 4),
                ).joinToString(",")
            )
            val totalArea = stat.byStratum.values.fold(0.0) { acc, s -> acc + s.areaAcres }
            val sortedKeys = stat.byStratum.keys.sorted()
            for (key in sortedKeys) {
                val ss = stat.byStratum[key] ?: continue
                val weight = if (totalArea > 0) ss.areaAcres / totalArea else 0.0
                lines.add(
                    listOf(
                        quote(metric),
                        quote(key),
                        quote(stratumNamesByKey[key] ?: key),
                        "${ss.nPlots}",
                        format(ss.mean, places = 4),
                        "",
                        format(ss.variance, places = 6),
                        format(ss.areaAcres, places = 4),
                        format(weight, places = 4),
                        "",
                        "",
                    ).joinToString(",")
                )
            }
        }
        emit("tpa", tpa)
        emit("ba_per_acre_m2", ba)
        emit("gross_v_per_acre_m3", volume)
        return join(lines)
    }

    // MARK: - RFC 4180 helpers

    internal fun quote(s: String): String {
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return "\"" + s.replace("\"", "\"\"") + "\""
        }
        return s
    }

    internal fun format(v: Double, places: Int): String = printfF(v, places)

    /// Fixed-decimal formatting with C-printf semantics. Swift's
    /// `String(format: "%.Nf", v)` delegates to printf, which rounds the
    /// EXACT binary value half-to-even; Java's `String.format("%.Nf")`
    /// rounds the shortest decimal representation HALF_UP, so the two
    /// diverge on ties (12.125 → "12.13" vs "12.12"). The exporters
    /// promise byte-identical cross-platform output, so every numeric
    /// cell (CSV, GPX, DBF) routes through BigDecimal(v) HALF_EVEN.
    internal fun printfF(v: Double, places: Int): String {
        // BigDecimal(double) throws on non-finite values; match Swift/C
        // printf's lowercase spellings.
        if (v.isNaN()) return "nan"
        if (v.isInfinite()) return if (v > 0) "inf" else "-inf"
        val s = java.math.BigDecimal(v)
            .setScale(places, java.math.RoundingMode.HALF_EVEN)
            .toPlainString()
        // BigDecimal drops the sign when a negative value (or -0.0) rounds
        // to zero; printf keeps it ("-0.00").
        val negative = v < 0.0 || (v == 0.0 && 1.0 / v < 0.0)
        return if (negative && !s.startsWith("-")) "-$s" else s
    }

    internal fun optional(v: Float?, places: Int): String =
        v?.let { format(it.toDouble(), places) } ?: ""

    internal fun join(lines: List<String>): String =
        lines.joinToString("\r\n") + "\r\n"

    /// ISO-8601 with internet date-time options: UTC, second precision,
    /// trailing "Z" — matches iOS ISO8601DateFormatter(.withInternetDateTime).
    internal fun iso8601(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }

    /// UTF-8 BOM prefix — consumers like Excel on Windows need this to
    /// recognise non-ASCII content. Call sites decide whether to prepend.
    val utf8BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}
