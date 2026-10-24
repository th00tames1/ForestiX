package com.hcjeong.forestix.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState

/** Register before map's peek/mode handlers so those keep first refusal.
 * This handles system Back only; measurement Back never exits the app. */
@Composable
internal fun MapBackExitHandler(nav: NavController) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycleState by owner.lifecycle.currentStateFlow.collectAsState()
    val destination by nav.currentBackStackEntryAsState()
    val policy = remember { DoubleBackExitPolicy() }
    val prompt = remember(context) { Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT) }
    DisposableEffect(owner, policy, prompt) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { policy.reset(); prompt.cancel() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); policy.reset(); prompt.cancel() }
    }
    BackHandler(enabled = lifecycleState == Lifecycle.State.RESUMED && destination?.destination?.route == Routes.MAP_HOME) {
        if (policy.shouldExit(SystemClock.elapsedRealtime())) {
            prompt.cancel()
            context.findExitActivity()?.finish()
        } else prompt.show()
    }
}

private fun Context.findExitActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findExitActivity()
    else -> null
}
