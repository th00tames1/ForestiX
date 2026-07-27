// Route constants for the v3 cruise-mode cluster (AR plot creation +
// planned-plot offset fallback + stand summary). Kept in this package like
// the sibling *FlowRoutes objects so the cruise screens can navigate among
// themselves; ForestixRoot registers the composable destinations. (The
// cruise MAP route retired with v3.1 — cruise is a MODE of the map home.)

package com.hcjeong.forestix.ui.screens.cruise

object CruiseRoutes {
    /// AR sampling-ring plot creation, saving a cruise Plot into {projectId}.
    /// With `editPlotId` set (field report F11 — the scan screens' top-right
    /// mini-map is tappable) the SAME screen re-opens an existing plot and
    /// Save rewrites it instead of creating Plot N+1.
    const val START_PLOT = "cruiseStartPlot/{projectId}?editPlotId={editPlotId}"

    /// Offset-from-Opening fallback for a planned plot centre — the
    /// "GPS weak? Use offset" link on the inline recording sheet (⑧).
    /// Hosts the KEPT OffsetFlowScreen; completion converts the planned
    /// plot exactly like the sheet's "Save centre".
    const val OFFSET = "cruiseOffset/{projectId}/{plannedPlotId}"

    /// Whole-project roll-up (kept StandSummaryScreen) — reached from the
    /// cruise project sheet's "Stand summary" row.
    const val STAND_SUMMARY = "standSummary/{projectId}"

    fun startPlot(projectId: String) = "cruiseStartPlot/$projectId"

    /// Re-open an existing plot's setup (F11).
    fun editPlot(projectId: String, plotId: String) =
        "cruiseStartPlot/$projectId?editPlotId=$plotId"
    fun offset(projectId: String, plannedPlotId: String) =
        "cruiseOffset/$projectId/$plannedPlotId"
    fun standSummary(projectId: String) = "standSummary/$projectId"
}
