package com.hcjeong.forestix.fieldstudy

import android.app.Instrumentation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.popBackStackFrom
import com.hcjeong.forestix.ui.restoreMapIfEmpty

/** Real AndroidX navigation objects on the main thread. Synthetic graph only:
 * never starts AR, opens a user repository, or creates a measurement. */
internal object NavigationSafetyChecks {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    fun run(runner: Instrumentation) {
        var failure: Throwable? = null
        runner.runOnMainSync {
            val owner = Owner()
            val models = ViewModelStore()
            try {
                owner.registry.currentState = Lifecycle.State.RESUMED
                val nav = NavHostController(runner.targetContext)
                nav.setLifecycleOwner(owner)
                nav.setViewModelStore(models)
                nav.navigatorProvider.addNavigator(ComposeNavigator())
                nav.graph = nav.createGraph(startDestination = Routes.MAP_HOME) {
                    composable(Routes.MAP_HOME) { }
                    composable(Routes.SAMPLING) { }
                    composable("syntheticDetail") { }
                }
                val map = checkNotNull(nav.currentBackStackEntry)
                check(!nav.popBackStackFrom(map)) { "Root was popped" }
                check(!nav.popBackStackFrom(null)) { "Missing source was accepted" }
                repeat(100) {
                    nav.navigate(Routes.SAMPLING)
                    val source = checkNotNull(nav.currentBackStackEntry)
                    check(nav.popBackStackFrom(source)) { "First Back failed" }
                    repeat(20) { check(!nav.popBackStackFrom(source)) { "Stale callback popped map" } }
                    check(nav.currentBackStackEntry === map) { "Map identity lost" }
                }
                nav.navigate("syntheticDetail")
                val detail = checkNotNull(nav.currentBackStackEntry)
                nav.navigate(Routes.SAMPLING)
                val plot = checkNotNull(nav.currentBackStackEntry)
                check(!nav.popBackStackFrom(detail)) { "Old async completion popped new screen" }
                check(nav.popBackStackFrom(plot))
                check(!nav.popBackStackFrom(plot)) { "Double Back popped parent detail" }
                check(nav.currentBackStackEntry === detail)
                check(nav.popBackStackFrom(detail))
                check(nav.currentBackStackEntry === map)
                check(!nav.restoreMapIfEmpty(true)) { "Recovery changed healthy navigation" }
                // Deliberately recreate the old bug in this isolated graph.
                nav.popBackStack()
                check(nav.currentDestination == null)
                check(!nav.restoreMapIfEmpty(false)) { "Recovery raced initial graph setup" }
                check(nav.restoreMapIfEmpty(true)) { "Empty stack did not recover" }
                check(nav.currentDestination?.route == Routes.MAP_HOME)
                check(!nav.popBackStackFrom(nav.currentBackStackEntry))
            } catch (error: Throwable) { failure = error }
            finally { owner.registry.currentState = Lifecycle.State.DESTROYED; models.clear() }
        }
        failure?.let { throw it }
    }
}
