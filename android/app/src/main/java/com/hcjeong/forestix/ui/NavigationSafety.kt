package com.hcjeong.forestix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController

/** Bind a back callback to its originating screen, not whatever screen is
 * current when an outgoing animation or asynchronous save finishes. Never
 * pop the last real destination (the map). No data or save state is changed. */
internal fun NavController.popBackStackFrom(source: NavBackStackEntry?): Boolean {
    if (source == null || currentBackStackEntry !== source || previousBackStackEntry == null) return false
    return popBackStack()
}

@Composable
internal fun rememberSafeBack(nav: NavController): () -> Unit {
    val owner = LocalLifecycleOwner.current
    val source = remember(nav, owner) { owner as? NavBackStackEntry ?: nav.currentBackStackEntry }
    return remember(nav, source) { { nav.popBackStackFrom(source); Unit } }
}

/** Safety net for an unexpectedly empty stack. Wait until a real screen has
 * been observed, so initial graph installation/restoration is never raced. */
internal fun NavController.restoreMapIfEmpty(hasShownScreen: Boolean): Boolean {
    if (!hasShownScreen || currentDestination != null) return false
    navigate(Routes.MAP_HOME) { launchSingleTop = true }
    return true
}

@Composable
internal fun RecoverEmptyNavigation(nav: NavController) {
    LaunchedEffect(nav) {
        var hasShownScreen = false
        nav.visibleEntries.collect { entries ->
            if (entries.isNotEmpty()) hasShownScreen = true
            else nav.restoreMapIfEmpty(hasShownScreen)
        }
    }
}
