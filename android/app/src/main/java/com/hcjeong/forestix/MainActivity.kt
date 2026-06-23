// Hosting Activity — the Android equivalent of Forestix/ForestixApp.swift +
// ContentView. Builds the AppEnvironment, then hands it to the Compose tree
// rooted at ModeSelectionScreen (the two-button landing), matching RootView.

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
import com.hcjeong.forestix.ui.ForestixRoot
import com.hcjeong.forestix.ui.theme.ForestixTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ForestixTheme {
                val context = LocalContext.current
                val env by produceState<AppEnvironment?>(initialValue = null) {
                    value = AppEnvironment.create(context)
                }
                val environment = env
                if (environment != null) {
                    CompositionLocalProvider(LocalAppEnvironment provides environment) {
                        ForestixRoot()
                    }
                } else {
                    // Branded splash while Room + bootstrap run, so the
                    // first frame isn't a blank window (mirrors the iOS
                    // LaunchSplash fix for the white-screen-on-launch bug).
                    com.hcjeong.forestix.ui.LaunchSplash()
                }
            }
        }
    }
}
