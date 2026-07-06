// Port of iOS Screens/ARBoundarySceneView.swift (Phase 7.3 boundary ring
// rendering). On iOS this is a UIViewRepresentable that rebuilds a
// RealityKit ModelEntity from the view model's ringVertices; on Android
// the rendering goes through the shared ArCameraView marker pipeline —
// a MarkerShape.Ring node anchored at the plot center, rebuilt whenever
// the center or radius changes (the same "skip work when nothing
// changed" contract the iOS Coordinator enforces, courtesy of
// remember(center, radius)).

package com.hcjeong.forestix.ui.screens.boundary

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.PlotBoundaryRenderer
import com.hcjeong.forestix.ar.Vec3

@Composable
fun ARBoundarySceneView(
    viewModel: ARBoundaryViewModel,
    style: PlotBoundaryRenderer.RingStyle = PlotBoundaryRenderer.RingStyle(),
    modifier: Modifier = Modifier,
) {
    val center by viewModel.centerWorld.collectAsStateWithLifecycle()
    val radiusM by viewModel.radiusM.collectAsStateWithLifecycle()

    // No center -> no leftover ring (mirror of detachRing on iOS). The
    // marker list is stable across frames so the AR nodes are only
    // rebuilt when the center or radius actually changes.
    val markers = remember(center, radiusM, style) {
        val c = center ?: return@remember emptyList<ArSceneMarker>()
        listOf(
            // Boundary ring on the ground plane. Rim band = 2x the iOS
            // 2 cm line width so the thin ring stays visible through the
            // camera at 11+ m radius.
            ArSceneMarker(
                worldPosition = Vec3(c.x, c.y + 0.02f, c.z),
                shape = MarkerShape.Ring(radiusM = radiusM, thicknessM = style.lineWidthM * 2f),
                colorRGBA = style.color,
            ),
            // Small center stake so the anchor point itself is visible.
            ArSceneMarker(
                worldPosition = c,
                shape = MarkerShape.Sphere(0.05f),
                colorRGBA = style.color,
            ),
        )
    }

    ArCameraView(
        controller = viewModel.session,
        markers = markers,
        modifier = modifier.fillMaxSize(),
    )
}
