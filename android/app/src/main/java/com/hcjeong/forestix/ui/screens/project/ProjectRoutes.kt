// Route constants for the surviving project-cluster destinations. The
// Phase B retirement removed the dashboard/cruise-design/pre-field/
// plot-map/cruise-flow routes together with their screens; StratumDraw
// (the cruise setup sheet's "Draw boundary"), Export ("Choose files…"),
// and per-project Calibration remain.

package com.hcjeong.forestix.ui.screens.project

object ProjectFlowRoutes {
    const val STRATUM_DRAW = "stratumDraw/{projectId}"
    const val EXPORT = "export/{projectId}"
    const val CALIBRATION = "calibration/{projectId}"

    fun stratumDraw(projectId: String) = "stratumDraw/$projectId"
    fun export(projectId: String) = "export/$projectId"
    fun calibration(projectId: String) = "calibration/$projectId"
}
