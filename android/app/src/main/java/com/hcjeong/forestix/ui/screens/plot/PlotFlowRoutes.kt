// Route constants for the plot flow cluster (center → tally → summary,
// plus the quick-measure plot detail + project plot map). Kept out of
// ForestixRoot.kt so the screens in this package can navigate among
// themselves before the integrator wires the composable destinations
// into the NavHost.
//
// Suggested NavHost wiring:
//   composable(PlotFlowRoutes.PLOT_CENTER)  { PlotCenterScreen(nav) }
//   composable(PlotFlowRoutes.PLOT_TALLY)   { PlotTallyScreen(nav, it.plotId()) }
//   composable(PlotFlowRoutes.PLOT_SUMMARY) { PlotSummaryScreen(nav, it.plotId()) }
//   composable(PlotFlowRoutes.PLOT_DETAIL)  { PlotDetailScreen(nav, it.plotID()) }
//   composable(ProjectFlowRoutes.PLOT_MAP)  { PlotMapScreen(nav, it.projectId()) }
// ADD_TREE_FLOW / TREE_DETAIL are owned by other port waves; the tally
// screen's default callbacks navigate to them by these strings.

package com.hcjeong.forestix.ui.screens.plot

object PlotFlowRoutes {
    const val PLOT_CENTER = "plotCenter"
    const val PLOT_TALLY = "plotTally/{plotId}"
    const val PLOT_SUMMARY = "plotSummary/{plotId}"
    const val PLOT_DETAIL = "plotDetail/{plotID}"
    // Owned by other port waves — referenced by PlotTallyScreen defaults.
    const val ADD_TREE_FLOW = "addTreeFlow/{plotId}"
    const val TREE_DETAIL = "treeDetail/{treeId}"

    fun plotTally(plotId: String) = "plotTally/$plotId"
    fun plotSummary(plotId: String) = "plotSummary/$plotId"
    fun plotDetail(plotID: String) = "plotDetail/$plotID"
    fun addTreeFlow(plotId: String) = "addTreeFlow/$plotId"
    fun treeDetail(treeId: String) = "treeDetail/$treeId"
}
