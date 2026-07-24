// Navigation root — the Compose analogue of the iOS NavigationStack tree.
// Routes mirror the screen graph after the v3.1 cruise-mode merge: the ONE
// map home (design/forestix-redesign-v2-maphome.html) renders measure OR
// cruise mode in place (tc.mapMode toggle — no cruise-map destination)
// -> the five AR measurement tools + the shared Field Log, plus the cruise
// mode's pushed flows (design/forestix-redesign-v3-cruise.html): AR
// start-plot / offset fallback / stratum draw / plot summary / tree detail
// / stand summary / export. The old hub/dashboard cruise stack was retired
// in Phase B (both platforms).

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
import com.hcjeong.forestix.ui.screens.RawCapturesScreen
import com.hcjeong.forestix.ui.screens.ReferenceLibraryScreen
import com.hcjeong.forestix.ui.screens.SamplingPlotScreen
import com.hcjeong.forestix.ui.screens.SettingsScreen
import com.hcjeong.forestix.ui.screens.cruise.CruiseOffsetHostScreen
import com.hcjeong.forestix.ui.screens.cruise.CruiseRoutes
import com.hcjeong.forestix.ui.screens.cruise.CruiseStartPlotScreen
import com.hcjeong.forestix.ui.screens.dbh.DBHScanScreen
import com.hcjeong.forestix.ui.screens.height.HeightScanScreen
import com.hcjeong.forestix.ui.screens.plot.PlotFlowRoutes
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryScreen
import com.hcjeong.forestix.ui.screens.project.ProjectFlowRoutes
import com.hcjeong.forestix.ui.screens.project.StratumDrawScreen
import com.hcjeong.forestix.ui.screens.stand.StandSummaryScreen
import com.hcjeong.forestix.ui.screens.tree.TreeDetailScreen
import java.util.UUID

object Routes {
    /// Map-first home (redesign v2): pins from the field log, the capture
    /// cluster, and the offline-basemap sheet. The app's start destination.
    const val MAP_HOME = "mapHome"
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
    const val REFERENCE_LIBRARY = "referenceLibrary"
    /// Developer-only raw-capture replay console (Settings › Developer).
    const val RAW_CAPTURES = "rawCaptures"
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
        composable(Routes.REFERENCE_LIBRARY) { ReferenceLibraryScreen(nav) }
        composable(Routes.RAW_CAPTURES) { RawCapturesScreen(nav) }

        // MARK: - Cruise mode (v3.1: a MODE of the map home, not a route —
        // only its pushed flows live in the graph)

        composable(CruiseRoutes.START_PLOT) { back ->
            CruiseStartPlotScreen(nav, back.arg("projectId"))
        }
        // "GPS weak? Use offset" from the inline centre-record sheet —
        // hosts the KEPT OffsetFlowScreen and lands the centre through the
        // same planned-plot conversion as "Save centre".
        composable(CruiseRoutes.OFFSET) { back ->
            CruiseOffsetHostScreen(nav, back.arg("projectId"), back.arg("plannedPlotId"))
        }
        composable(CruiseRoutes.STAND_SUMMARY) { back ->
            StandSummaryScreen(nav, UUID.fromString(back.arg("projectId")))
        }

        // MARK: - Kept detail/tool screens

        composable(PlotFlowRoutes.PLOT_SUMMARY) { back ->
            PlotSummaryScreen(nav, back.arg("plotId"))
        }
        composable(PlotFlowRoutes.TREE_DETAIL) { back ->
            TreeDetailScreen(nav, back.arg("treeId"))
        }
        composable(ProjectFlowRoutes.STRATUM_DRAW) { back ->
            StratumDrawScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.EXPORT) { back ->
            ExportScreen(nav, back.arg("projectId"))
        }
        composable(ProjectFlowRoutes.CALIBRATION) { back ->
            CalibrationScreen(nav, back.arg("projectId"))
        }
    }
}
