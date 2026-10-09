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
import com.hcjeong.forestix.ui.screens.FieldLogScope
import com.hcjeong.forestix.ui.screens.FieldLogScreen
import com.hcjeong.forestix.ui.screens.MapHomeScreen
import com.hcjeong.forestix.ui.screens.RawCapturesScreen
import com.hcjeong.forestix.ui.screens.ReferenceLibraryScreen
import com.hcjeong.forestix.ui.screens.SamplingPlotScreen
import com.hcjeong.forestix.ui.screens.SettingsScreen
import com.hcjeong.forestix.ui.screens.cruise.CruiseOffsetHostScreen
import com.hcjeong.forestix.ui.screens.cruise.CruiseRoutes
import com.hcjeong.forestix.ui.screens.cruise.CruiseStartPlotScreen
import com.hcjeong.forestix.ui.screens.cruise.ProjectBrowserScreen
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
    /// The field log opened already narrowed to ONE cruise plot — the one
    /// the cruiser has open (cruise project sheet → "Field log · Plot N").
    /// Separate from [FIELD_LOG] so the unscoped entries keep showing
    /// everything.
    const val FIELD_LOG_PLOT = "fieldLog/plot/{plotId}"

    fun fieldLogPlot(plotId: String) = "fieldLog/plot/$plotId"
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
        composable(Routes.FIELD_LOG_PLOT) { back ->
            // A malformed id would be a bug in the caller, not something the
            // cruiser can produce — but parse it defensively and fall back to
            // the unscoped log rather than crashing on the way to a read-back.
            val plotId = runCatching { UUID.fromString(back.arg("plotId")) }.getOrNull()
            val scope: FieldLogScope =
                if (plotId != null) FieldLogScope.CruisePlot(plotId) else FieldLogScope.Everything
            FieldLogScreen(nav, initialScope = scope)
        }
        composable(
            Routes.DBH_PATTERN,
            arguments = listOf(navArgument("chain") { type = NavType.BoolType; defaultValue = false }),
        ) { back ->
            DBHScanScreen(nav, chainToHeight = back.arguments?.getBoolean("chain") == true)
        }
        // `chained=true` = the cruise DIAMETER → HEIGHT chain (field report
        // F10): Height was pushed automatically on top of the tally after a
        // diameter, so it offers a labelled "Skip" and both Skip and Accept
        // pop back onto the tally rather than to the map.
        composable(
            "height?tree={tree}&chained={chained}",
            arguments = listOf(
                navArgument("tree") { type = NavType.IntType; defaultValue = -1 },
                navArgument("chained") { type = NavType.BoolType; defaultValue = false },
            ),
        ) { back ->
            val tree = back.arguments?.getInt("tree").takeIf { it != null && it >= 0 }
            HeightScanScreen(
                nav,
                treeOverride = tree,
                chainedFromDiameter = back.arguments?.getBoolean("chained") == true,
            )
        }
        composable(Routes.DISTANCE) { DistanceMeasureScreen(nav) }
        composable(Routes.SAMPLING) { SamplingPlotScreen(nav) }
        composable(Routes.SETTINGS) { SettingsScreen(nav) }
        composable(Routes.CALIBRATION) { CalibrationScreen(nav) }
        composable(Routes.REFERENCE_LIBRARY) { ReferenceLibraryScreen(nav) }
        composable(Routes.RAW_CAPTURES) { RawCapturesScreen(nav) }

        // MARK: - Cruise mode (v3.1: a MODE of the map home, not a route —
        // only its pushed flows live in the graph)

        composable(
            CruiseRoutes.START_PLOT,
            arguments = listOf(
                navArgument("editPlotId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { back ->
            CruiseStartPlotScreen(nav, back.arg("projectId"), back.arg("editPlotId"))
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
        composable(CruiseRoutes.PROJECT_BROWSER) { ProjectBrowserScreen(nav) }

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
