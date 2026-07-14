// Navigation root — the Compose analogue of the iOS NavigationStack tree.
// Routes mirror the screen graph: the map-first home (pins + capture
// cluster; design/forestix-redesign-v2-maphome.html) -> the two hubs ->
// the five AR measurement tools + the shared Field Log, plus the full
// timber-cruising
// stack: Projects (HomeScreen) -> ProjectDashboard -> StratumDraw /
// CruiseDesign / PreFieldChecklist / PlotMap / Export / Calibration ->
// CruiseFlow (navigate -> record centre / offset -> tally -> add tree /
// tree detail / AR boundary -> plot summary -> stand summary), and the
// standalone Recon cruise / Reference library.

package com.hcjeong.forestix.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hcjeong.forestix.ui.screens.CalibrationScreen
import com.hcjeong.forestix.ui.screens.DistanceMeasureScreen
import com.hcjeong.forestix.ui.screens.ExportScreen
import com.hcjeong.forestix.ui.screens.FieldLogScreen
import com.hcjeong.forestix.ui.screens.MapHomeScreen
import com.hcjeong.forestix.ui.screens.ReferenceLibraryScreen
import com.hcjeong.forestix.ui.screens.SamplingPlotScreen
import com.hcjeong.forestix.ui.screens.SettingsScreen
import com.hcjeong.forestix.ui.screens.TimberCruisingHubScreen
import com.hcjeong.forestix.ui.screens.TreeMeasurementHubScreen
import com.hcjeong.forestix.ui.screens.boundary.ARBoundaryScreen
import com.hcjeong.forestix.ui.screens.cruise.CruiseMapScreen
import com.hcjeong.forestix.ui.screens.cruise.CruiseRoutes
import com.hcjeong.forestix.ui.screens.cruise.CruiseStartPlotScreen
import com.hcjeong.forestix.ui.screens.dbh.DBHScanScreen
import com.hcjeong.forestix.ui.screens.height.HeightScanScreen
import com.hcjeong.forestix.ui.screens.nav.NavigationScreen
import com.hcjeong.forestix.ui.screens.plot.PlotFlowRoutes
import com.hcjeong.forestix.ui.screens.plot.PlotMapScreen
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryScreen
import com.hcjeong.forestix.ui.screens.plot.PlotTallyScreen
import com.hcjeong.forestix.ui.screens.project.CruiseDesignScreen
import com.hcjeong.forestix.ui.screens.project.CruiseFlowScreen
import com.hcjeong.forestix.ui.screens.project.CruiseOffsetScreen
import com.hcjeong.forestix.ui.screens.project.CruiseRecordCenterScreen
import com.hcjeong.forestix.ui.screens.project.CruiseStepRoutes
import com.hcjeong.forestix.ui.screens.project.HomeScreen
import com.hcjeong.forestix.ui.screens.project.PreFieldChecklistScreen
import com.hcjeong.forestix.ui.screens.project.ProjectDashboardScreen
import com.hcjeong.forestix.ui.screens.project.ProjectFlowRoutes
import com.hcjeong.forestix.ui.screens.project.StratumDrawScreen
import com.hcjeong.forestix.ui.screens.stand.ReconCruiseScreen
import com.hcjeong.forestix.ui.screens.stand.StandSummaryScreen
import com.hcjeong.forestix.ui.screens.tree.AddTreeFlowScreen
import com.hcjeong.forestix.ui.screens.tree.TreeDetailScreen
import com.hcjeong.forestix.ui.screens.tree.TreeFlowRoutes
import java.util.UUID

object Routes {
    /// Map-first home (redesign v2): pins from the field log, the capture
    /// cluster, and the offline-basemap sheet. The app's start destination.
    const val MAP_HOME = "mapHome"
    const val TREE_HUB = "treeHub"
    const val TIMBER_HUB = "timberHub"
    const val FIELD_LOG = "fieldLog"
    const val DBH = "dbh"
    /// Registered DBH route pattern. The optional `chain` flag
    /// ("dbh?chain=true" — the map-home Full measurement row) makes DBH
    /// Accept skip the continuation dialog and jump straight to Height on
    /// the same tree; plain "dbh" keeps today's behaviour.
    const val DBH_PATTERN = "dbh?chain={chain}"
    const val HEIGHT = "height"
    const val DISTANCE = "distance"
    const val SAMPLING = "sampling"
    const val SETTINGS = "settings"
    /// Project-less calibration entry from Settings — iOS Settings
    /// calibrationSection NavigationLink { CalibrationScreen() }.
    const val CALIBRATION = "calibration"
    // Timber-cruising stack (iOS TimberCruisingHubScreen tiles).
    const val PROJECTS = "projects"
    const val RECON_CRUISE = "reconCruise"
    const val REFERENCE_LIBRARY = "referenceLibrary"

    /// v3 cruise mode — the map home's Cruise circle enters here (the old
    /// TimberCruisingHub stays reachable via the cruise project sheet's
    /// "Classic view" row until Phase B).
    const val CRUISE_MAP = "cruiseMap"
}

/// Required-string route argument; screens behind these routes are only
/// reachable with the argument present.
private fun NavBackStackEntry.arg(name: String): String =
    arguments?.getString(name) ?: ""

@Composable
fun ForestixRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAP_HOME) {
        composable(Routes.MAP_HOME) { MapHomeScreen(nav) }
        composable(Routes.TREE_HUB) { TreeMeasurementHubScreen(nav) }
        composable(Routes.TIMBER_HUB) { TimberCruisingHubScreen(nav) }
        composable(Routes.FIELD_LOG) { FieldLogScreen(nav) }
        composable(
            Routes.DBH_PATTERN,
            arguments = listOf(navArgument("chain") { type = NavType.BoolType; defaultValue = false }),
        ) { back ->
            DBHScanScreen(nav, chainToHeight = back.arguments?.getBoolean("chain") == true)
        }
        composable(
            "height?tree={tree}",
            arguments = listOf(navArgument("tree") { type = NavType.IntType; defaultValue = -1 }),
        ) { back ->
            val tree = back.arguments?.getInt("tree").takeIf { it != null && it >= 0 }
            HeightScanScreen(nav, treeOverride = tree)
        }
        composable(Routes.DISTANCE) { DistanceMeasureScreen(nav) }
        composable(Routes.SAMPLING) { SamplingPlotScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.CALIBRATION) { CalibrationScreen(nav) }

        // MARK: - Cruise mode (v3 redesign: the map IS the cruise)

        composable(Routes.CRUISE_MAP) { CruiseMapScreen(nav) }
        composable(CruiseRoutes.START_PLOT) { back ->
            CruiseStartPlotScreen(nav, back.arg("projectId"))
        }

        // MARK: - Timber-cruising hub tiles

        composable(Routes.PROJECTS) { HomeScreen(nav) }
        composable(Routes.RECON_CRUISE) { ReconCruiseScreen(nav) }
        composable(Routes.REFERENCE_LIBRARY) { ReferenceLibraryScreen(nav) }

        // MARK: - Project flow (dashboard -> design -> tools)

        composable(ProjectFlowRoutes.DASHBOARD) { back ->
            ProjectDashboardScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.STRATUM_DRAW) { back ->
            StratumDrawScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.CRUISE_DESIGN) { back ->
            CruiseDesignScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.PRE_FIELD_CHECKLIST) { back ->
            PreFieldChecklistScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.PLOT_MAP) { back ->
            PlotMapScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.EXPORT) { back ->
            ExportScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.CALIBRATION) { back ->
            CalibrationScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.CRUISE_FLOW) { back ->
            CruiseFlowScreen(nav, back.arg("projectId"))
        }

        // MARK: - Cruise steps (iOS CruiseStep path enum)

        composable(CruiseStepRoutes.NAVIGATE) { back ->
            val projectId = back.arg("projectId")
            val plannedPlotId = back.arg("plannedPlotId")
            NavigationScreen(
                nav, plannedPlotId,
                onArrival = {
                    // Replace navigate -> recordCenter (iOS pushRecordCenter):
                    // the cruiser can't "go back to navigation" after arriving.
                    nav.navigate(CruiseStepRoutes.recordCenter(projectId, plannedPlotId)) {
                        popUpTo(CruiseStepRoutes.NAVIGATE) { inclusive = true }
                    }
                })
        }
        composable(CruiseStepRoutes.RECORD_CENTER) { back ->
            CruiseRecordCenterScreen(nav, back.arg("projectId"), back.arg("plannedPlotId"))
        }
        composable(CruiseStepRoutes.OFFSET) { back ->
            CruiseOffsetScreen(nav, back.arg("projectId"), back.arg("plannedPlotId"))
        }
        composable(CruiseStepRoutes.TALLY) { back ->
            val projectId = back.arg("projectId")
            val plotId = back.arg("plotId")
            PlotTallyScreen(
                nav, plotId,
                onAddTree = { nav.navigate(CruiseStepRoutes.addTree(projectId, plotId)) },
                onOpenTree = { tree ->
                    nav.navigate(CruiseStepRoutes.treeDetail(tree.id.toString()))
                },
                onClosePlot = { nav.navigate(CruiseStepRoutes.summarize(projectId, plotId)) },
                // iOS CruiseFlowScreen tally toolbar: "AR Boundary" button
                // pushes the AR plot-boundary walk (circle.dashed).
                onArBoundary = { nav.navigate(CruiseStepRoutes.arBoundary(plotId)) })
        }
        composable(CruiseStepRoutes.ADD_TREE) { back ->
            AddTreeFlowScreen(nav, back.arg("plotId"))
        }
        composable(CruiseStepRoutes.TREE_DETAIL) { back ->
            TreeDetailScreen(nav, back.arg("treeId"))
        }
        composable(CruiseStepRoutes.AR_BOUNDARY) { ARBoundaryScreen(nav) }
        composable(CruiseStepRoutes.SUMMARIZE) { back ->
            val projectId = back.arg("projectId")
            PlotSummaryScreen(
                nav, back.arg("plotId"),
                onClosed = {
                    // iOS onPlotClosed(): back to the plot picker, then push
                    // the stand summary so the cruiser can verify the roll-up.
                    nav.navigate(CruiseStepRoutes.standSummary(projectId)) {
                        popUpTo(ProjectFlowRoutes.CRUISE_FLOW) { inclusive = false }
                    }
                })
        }
        composable(CruiseStepRoutes.STAND_SUMMARY) { back ->
            StandSummaryScreen(nav, UUID.fromString(back.arg("projectId")))
        }

        // MARK: - Quick-measure plot flow (standalone, no project)
        //
        // The standalone plotCenter / plotDetail / standStock destinations
        // were removed: their iOS counterparts (PlotCenterScreen outside the
        // cruise flow, PlotDetailScreen, StandStockReport) are unreachable
        // dead code in the live iOS navigation tree, so registering them
        // here only exposed no-op wiring. Re-add alongside iOS entry points.

        composable(PlotFlowRoutes.PLOT_TALLY) { back ->
            PlotTallyScreen(nav, back.arg("plotId"))
        }
        composable(PlotFlowRoutes.PLOT_SUMMARY) { back ->
            PlotSummaryScreen(nav, back.arg("plotId"))
        }
        composable(PlotFlowRoutes.ADD_TREE_FLOW) { back ->
            AddTreeFlowScreen(nav, back.arg("plotId"))
        }
        composable(TreeFlowRoutes.ADD_TREE) { back ->
            AddTreeFlowScreen(nav, back.arg("plotId"))
        }
    }
}
