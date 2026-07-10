// Route constants for the project flow cluster (dashboard → design →
// cruise → tools). Kept out of ForestixRoot.kt so the screens in this
// package can navigate among themselves before the integrator wires the
// composable destinations into the NavHost.
//
// Suggested NavHost wiring (all take a {projectId} string argument):
//   composable(ProjectFlowRoutes.DASHBOARD)  { ProjectDashboardScreen(nav, it.projectId()) }
//   composable(ProjectFlowRoutes.STRATUM_DRAW) { StratumDrawScreen(nav, it.projectId()) }
//   composable(ProjectFlowRoutes.CRUISE_DESIGN) { CruiseDesignScreen(nav, it.projectId()) }
//   composable(ProjectFlowRoutes.CRUISE_FLOW) { CruiseFlowScreen(nav, it.projectId()) }
//   composable(ProjectFlowRoutes.PRE_FIELD_CHECKLIST) { PreFieldChecklistScreen(nav, it.projectId()) }
// PLOT_MAP / EXPORT / CALIBRATION are owned by other port waves; the
// dashboard navigates to them by these strings.

package com.hcjeong.forestix.ui.screens.project

object ProjectFlowRoutes {
    const val DASHBOARD = "projectDashboard/{projectId}"
    const val STRATUM_DRAW = "stratumDraw/{projectId}"
    const val CRUISE_DESIGN = "cruiseDesign/{projectId}"
    const val CRUISE_FLOW = "cruiseFlow/{projectId}"
    const val PRE_FIELD_CHECKLIST = "preFieldChecklist/{projectId}"
    const val PLOT_MAP = "plotMap/{projectId}"
    const val EXPORT = "export/{projectId}"
    const val CALIBRATION = "calibration/{projectId}"

    fun dashboard(projectId: String) = "projectDashboard/$projectId"
    fun stratumDraw(projectId: String) = "stratumDraw/$projectId"
    fun cruiseDesign(projectId: String) = "cruiseDesign/$projectId"
    fun cruiseFlow(projectId: String) = "cruiseFlow/$projectId"
    fun preFieldChecklist(projectId: String) = "preFieldChecklist/$projectId"
    fun plotMap(projectId: String) = "plotMap/$projectId"
    fun export(projectId: String) = "export/$projectId"
    fun calibration(projectId: String) = "calibration/$projectId"
}
