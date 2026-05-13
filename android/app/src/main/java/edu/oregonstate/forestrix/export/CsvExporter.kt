package edu.oregonstate.forestrix.export

import edu.oregonstate.forestrix.inventory.PlotStats
import edu.oregonstate.forestrix.measurement.ConfidenceTier
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.HeightMethod
import edu.oregonstate.forestrix.models.Plot
import edu.oregonstate.forestrix.models.PositionSource
import edu.oregonstate.forestrix.models.PositionTier
import edu.oregonstate.forestrix.models.Tree
import edu.oregonstate.forestrix.models.TreeStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

object CsvExporter {
    const val UTF8_BOM = "\uFEFF"

    fun treesCsv(trees: List<Tree>, includeBom: Boolean = false): String {
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
            "created_at", "updated_at", "deleted_at"
        ).joinToString(",")
        val lines = mutableListOf(header)
        trees.sortedWith(compareBy<Tree> { it.plotId.toString() }.thenBy { it.treeNumber })
            .forEach { tree ->
                lines += listOf(
                    quote(tree.id.toString()),
                    quote(tree.plotId.toString()),
                    tree.treeNumber.toString(),
                    quote(tree.speciesCode),
                    quote(tree.status.csvName()),
                    format(tree.dbhCm, 2),
                    quote(tree.dbhMethod.csvName()),
                    optional(tree.dbhSigmaMm, 2),
                    optional(tree.dbhRmseMm, 2),
                    optional(tree.dbhCoverageDeg, 2),
                    tree.dbhNInliers?.toString() ?: "",
                    quote(tree.dbhConfidence.csvName()),
                    tree.dbhIsIrregular.toString(),
                    optional(tree.heightM, 2),
                    tree.heightMethod?.let { quote(it.csvName()) } ?: "",
                    quote(tree.heightSource ?: ""),
                    optional(tree.heightSigmaM, 2),
                    optional(tree.heightDHM, 2),
                    optional(tree.heightAlphaTopDeg, 2),
                    optional(tree.heightAlphaBaseDeg, 2),
                    tree.heightConfidence?.let { quote(it.csvName()) } ?: "",
                    optional(tree.bearingFromCenterDeg, 2),
                    optional(tree.distanceFromCenterM, 2),
                    quote(tree.boundaryCall ?: ""),
                    quote(tree.crownClass ?: ""),
                    quote(tree.damageCodes.joinToString(";")),
                    tree.isMultistem.toString(),
                    quote(tree.parentTreeId?.toString() ?: ""),
                    quote(tree.notes),
                    quote(tree.photoPath ?: ""),
                    quote(tree.rawScanPath ?: ""),
                    iso8601(tree.createdAtMillis),
                    iso8601(tree.updatedAtMillis),
                    tree.deletedAtMillis?.let { iso8601(it) } ?: ""
                ).joinToString(",")
            }
        return (if (includeBom) UTF8_BOM else "") + join(lines)
    }

    fun plotsCsv(
        plots: List<Plot>,
        statsByPlot: Map<UUID, PlotStats> = emptyMap(),
        includeBom: Boolean = false
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
            "gross_volume_per_acre_m3", "merch_volume_per_acre_m3",
            "started_at", "closed_at", "notes"
        ).joinToString(",")
        val lines = mutableListOf(header)
        plots.sortedBy { it.plotNumber }.forEach { plot ->
            val stats = statsByPlot[plot.id]
            lines += listOf(
                quote(plot.id.toString()),
                plot.plotNumber.toString(),
                quote(plot.projectId.toString()),
                quote(plot.plannedPlotId?.toString() ?: ""),
                format(plot.centerLat, 7),
                format(plot.centerLon, 7),
                quote(plot.positionSource.csvName()),
                quote(plot.positionTier.csvName()),
                plot.gpsNSamples.toString(),
                format(plot.gpsMedianHAccuracyM, 3),
                format(plot.gpsSampleStdXyM, 3),
                optional(plot.offsetWalkM, 2),
                format(plot.slopeDeg, 2),
                format(plot.aspectDeg, 2),
                format(plot.plotAreaAcres, 4),
                stats?.liveTreeCount?.toString() ?: "",
                stats?.let { format(it.tpa, 2) } ?: "",
                stats?.let { format(it.baPerAcreM2, 4) } ?: "",
                stats?.let { format(it.qmdCm, 2) } ?: "",
                stats?.let { format(it.grossVolumePerAcreM3, 4) } ?: "",
                stats?.let { format(it.merchVolumePerAcreM3, 4) } ?: "",
                iso8601(plot.startedAtMillis),
                plot.closedAtMillis?.let { iso8601(it) } ?: "",
                quote(plot.notes)
            ).joinToString(",")
        }
        return (if (includeBom) UTF8_BOM else "") + join(lines)
    }

    private fun quote(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    private fun optional(value: Float?, places: Int): String =
        value?.let { format(it, places) } ?: ""

    private fun format(value: Float, places: Int): String =
        String.format(Locale.US, "%.${places}f", value)

    private fun format(value: Double, places: Int): String =
        String.format(Locale.US, "%.${places}f", value)

    private fun join(lines: List<String>): String =
        lines.joinToString(separator = "\r\n", postfix = "\r\n")

    private fun iso8601(millis: Long): String =
        synchronized(IsoFormatter) {
            IsoFormatter.format(Date(millis))
        }

    private object IsoFormatter {
        private val delegate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        fun format(date: Date): String = delegate.format(date)
    }

    private fun TreeStatus.csvName(): String = when (this) {
        TreeStatus.LIVE -> "live"
        TreeStatus.DEAD_STANDING -> "deadStanding"
        TreeStatus.DEAD_DOWN -> "deadDown"
        TreeStatus.CULL -> "cull"
    }

    private fun DbhMethod.csvName(): String = when (this) {
        DbhMethod.LIDAR_PARTIAL_ARC_SINGLE_VIEW -> "lidarPartialArcSingleView"
        DbhMethod.LIDAR_PARTIAL_ARC_DUAL_VIEW -> "lidarPartialArcDualView"
        DbhMethod.LIDAR_IRREGULAR -> "lidarIrregular"
        DbhMethod.RAW_DEPTH_CHORD_SILHOUETTE -> "rawDepthChordSilhouette"
        DbhMethod.MANUAL_CALIPER -> "manualCaliper"
        DbhMethod.MANUAL_VISUAL -> "manualVisual"
    }

    private fun HeightMethod.csvName(): String = when (this) {
        HeightMethod.ARCORE_VIO_WALKOFF_TANGENT -> "arcoreVioWalkoffTangent"
        HeightMethod.TAPE_TANGENT -> "tapeTangent"
        HeightMethod.MANUAL_ENTRY -> "manualEntry"
        HeightMethod.IMPUTED_HD -> "imputedHD"
    }

    private fun ConfidenceTier.csvName(): String = when (this) {
        ConfidenceTier.GREEN -> "green"
        ConfidenceTier.YELLOW -> "yellow"
        ConfidenceTier.RED -> "red"
    }

    private fun PositionSource.csvName(): String = when (this) {
        PositionSource.GPS_AVERAGED -> "gpsAveraged"
        PositionSource.VIO_OFFSET -> "vioOffset"
        PositionSource.VIO_CHAIN -> "vioChain"
        PositionSource.EXTERNAL_RTK -> "externalRtk"
        PositionSource.MANUAL -> "manual"
    }

    private fun PositionTier.csvName(): String = name
}
