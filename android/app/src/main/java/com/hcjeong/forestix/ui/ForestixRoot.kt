// Navigation root — the Compose analogue of the iOS NavigationStack tree.
// Routes mirror the screen graph: mode picker -> the two hubs -> the five
// AR measurement tools + the shared Field Log.

package com.hcjeong.forestix.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.hcjeong.forestix.ui.screens.DistanceMeasureScreen
import com.hcjeong.forestix.ui.screens.FieldLogScreen
import com.hcjeong.forestix.ui.screens.ModeSelectionScreen
import com.hcjeong.forestix.ui.screens.SamplingPlotScreen
import com.hcjeong.forestix.ui.screens.SettingsScreen
import com.hcjeong.forestix.ui.screens.TimberCruisingHubScreen
import com.hcjeong.forestix.ui.screens.TreeMeasurementHubScreen
import com.hcjeong.forestix.ui.screens.dbh.DBHScanScreen
import com.hcjeong.forestix.ui.screens.height.HeightScanScreen

object Routes {
    const val MODE = "mode"
    const val TREE_HUB = "treeHub"
    const val TIMBER_HUB = "timberHub"
    const val FIELD_LOG = "fieldLog"
    const val DBH = "dbh"
    const val HEIGHT = "height"
    const val DISTANCE = "distance"
    const val SAMPLING = "sampling"
    const val SETTINGS = "settings"
}

@Composable
fun ForestixRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MODE) {
        composable(Routes.MODE) { ModeSelectionScreen(nav) }
        composable(Routes.TREE_HUB) { TreeMeasurementHubScreen(nav) }
        composable(Routes.TIMBER_HUB) { TimberCruisingHubScreen(nav) }
        composable(Routes.FIELD_LOG) { FieldLogScreen(nav) }
        composable(Routes.DBH) { DBHScanScreen(nav) }
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
    }
}
