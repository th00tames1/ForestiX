// Route constants for the surviving plot-cluster destinations (the plot
// summary + the single-tree inspector the cruise peeks open). Kept out of
// ForestixRoot.kt so screens in this package can navigate among
// themselves; the Phase B retirement removed the centre/tally/detail/
// add-tree routes together with their screens.

package com.hcjeong.forestix.ui.screens.plot

object PlotFlowRoutes {
    const val PLOT_SUMMARY = "plotSummary/{plotId}"
    const val TREE_DETAIL = "treeDetail/{treeId}"

    fun plotSummary(plotId: String) = "plotSummary/$plotId"
    fun treeDetail(treeId: String) = "treeDetail/$treeId"
}
