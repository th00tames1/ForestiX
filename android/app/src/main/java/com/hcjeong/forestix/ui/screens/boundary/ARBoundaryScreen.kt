// Port of iOS Screens/ARBoundaryScreen.swift (spec §5.1 + §7.8 +
// REQ-BND-001..004).
//
// Two states:
//   - center not set — "Stand at the plot center, then tap Set Center".
//   - center set     — overlay shows radius + live distance to center;
//                      15 m drift banner appears when user walks out.
//
// The live camera feed + boundary ring render through ARBoundarySceneView
// (the shared ArCameraView marker pipeline). The user position feeding the
// drift logic is polled from the AR frame at 4 Hz — the Android analogue of
// the iOS depth-frame subscription.

package com.hcjeong.forestix.ui.screens.boundary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun ARBoundaryScreen(nav: NavController) {
    // Shared app-scoped AR session (one ARCore world across the AR screens).
    val controller = ArSessionHub.controller
    val viewModel = remember { ARBoundaryViewModel(session = controller) }

    val centerWorld by viewModel.centerWorld.collectAsStateWithLifecycle()
    val radiusM by viewModel.radiusM.collectAsStateWithLifecycle()
    val userDistanceM by viewModel.userDistanceM.collectAsStateWithLifecycle()
    val isDrifted by viewModel.isDrifted.collectAsStateWithLifecycle()
    val driftRadiusM by viewModel.driftRadiusM.collectAsStateWithLifecycle()

    // onAppear/onDisappear parity (session lifecycle itself is owned by
    // the ArCameraView composition).
    DisposableEffect(Unit) {
        viewModel.onAppear()
        onDispose { viewModel.onDisappear() }
    }

    // 4 Hz user-position pump — the Android analogue of the iOS
    // latestDepthFrame subscription driving updateUserPosition.
    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            controller.currentCameraPosition()?.let { viewModel.updateUserPosition(it) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Live AR camera feed + boundary ring anchored at plot centre.
        ARBoundarySceneView(viewModel = viewModel, modifier = Modifier.fillMaxSize())

        MeasureBackButton { nav.popBackStack() }

        // MARK: - Reticle / ring summary
        if (centerWorld == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .border(2.dp, Color.Yellow, CircleShape),
                )
                Text(
                    "Stand at plot center",
                    style = Forestix.type.dataSmall,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.xxs),
                modifier = Modifier.align(Alignment.Center),
            ) {
                Box(
                    Modifier
                        .size(120.dp)
                        .border(2.dp, Color.Green, CircleShape),
                )
                Text(
                    String.format(Locale.US, "R = %.2f m", radiusM),
                    style = Forestix.type.dataSmall,
                    color = Color.Green,
                )
            }
        }

        // MARK: - Bottom panel
        Column(
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.lg)
                .clip(RoundedCornerShape(12.dp))
                // G8 — AR chrome panels are flat black 0.55 on both platforms.
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(ForestixSpace.md),
        ) {
            if (isDrifted) {
                Text(
                    String.format(
                        Locale.US,
                        "You are %.1f m from plot center (>%.0f m limit). Re-seat the center.",
                        userDistanceM, driftRadiusM,
                    ),
                    style = Forestix.type.bodyBold,
                    color = Color.White,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFDD8500).copy(alpha = 0.85f))
                        .padding(ForestixSpace.md),
                )
            }

            val statusText = if (centerWorld == null) {
                "Stand at the plot center and tap Set Center."
            } else {
                String.format(
                    Locale.US,
                    "Plot R = %.2f m · distance to center = %.1f m",
                    radiusM, userDistanceM,
                )
            }
            Text(
                statusText,
                style = Forestix.type.body,
                color = Color.White,
                modifier = Modifier.fillMaxWidth(),
            )

            if (centerWorld == null) {
                Row {
                    // iOS Button("Set Center").buttonStyle(.forestixProminent);
                    // falls back to a synthetic origin center when no AR
                    // frame exists yet so the flow stays exercisable.
                    ForestixProminentButton(label = "Set Center") {
                        if (!viewModel.setCenterAtCurrentCamera()) {
                            viewModel.setCenter(com.hcjeong.forestix.ar.Vec3(0f, 0f, 0f))
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                    // iOS `.bordered` — tinted label; primary reads on the
                    // black AR panel in both appearances.
                    ForestixBorderedButton(
                        label = "Reset",
                        tint = Forestix.colors.primary,
                    ) { viewModel.clearCenter() }
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}
