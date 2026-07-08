// Hosting Activity — the Android equivalent of Forestix/ForestixApp.swift +
// ContentView. Builds the AppEnvironment, then hands it to the Compose tree
// rooted at MapHomeScreen (the map-first home), matching RootView.

package com.hcjeong.forestix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.data.AppSettings
import com.hcjeong.forestix.ui.ForestixRoot
import com.hcjeong.forestix.ui.theme.ForestixTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val env by produceState<AppEnvironment?>(initialValue = null) {
                value = AppEnvironment.create(context)
            }
            // Field fix: hold the splash a minimum of 3 s (covers the first
            // satellite-tile load), even when env comes up faster.
            val splashElapsed by produceState(initialValue = false) {
                delay(3_000)
                value = true
            }
            // Splash follows the SAVED appearance setting (default light) —
            // read straight from DataStore because AppEnvironment doesn't
            // exist yet while the splash is up.
            val splashDark = remember { AppSettings.peekAppearanceIsDark(context) }
            val environment = env
            if (environment != null && splashElapsed) {
                // Appearance is user-selected (default light); collecting it
                // here flips tokens + Material scheme app-wide together.
                val settings by environment.settings.state.collectAsStateWithLifecycle()
                ForestixTheme(darkTheme = settings.appearance == "dark") {
                    CompositionLocalProvider(LocalAppEnvironment provides environment) {
                        ForestixRoot()
                    }
                }
            } else {
                // Branded splash while Room + bootstrap run, so the
                // first frame isn't a blank window (mirrors the iOS
                // LaunchSplash fix). Themed by the persisted appearance.
                ForestixTheme(darkTheme = splashDark) {
                    com.hcjeong.forestix.ui.LaunchSplash()
                }
            }
        }
    }
}
