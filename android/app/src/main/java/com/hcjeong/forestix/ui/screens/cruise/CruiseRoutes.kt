// Route constants for the v3 cruise-mode cluster (cruise map + AR plot
// creation). Kept in this package like the sibling *FlowRoutes objects so
// the cruise screens can navigate among themselves; ForestixRoot registers
// the composable destinations.

package com.hcjeong.forestix.ui.screens.cruise

object CruiseRoutes {
    /// The cruise-mode map — entered from the map home's Cruise circle.
    const val MAP = "cruiseMap"

    /// AR sampling-ring plot creation, saving a cruise Plot into {projectId}.
    const val START_PLOT = "cruiseStartPlot/{projectId}"

    fun startPlot(projectId: String) = "cruiseStartPlot/$projectId"
}
