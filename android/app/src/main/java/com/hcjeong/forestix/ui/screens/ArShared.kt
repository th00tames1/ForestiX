// Shared AR-screen overlay chrome: the centre crosshair every AR
// measurement screen layers over the camera feed (mirrors the SwiftUI
// overlay code on iOS).

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun CenterCrosshair(modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).border(4.dp, Color.Black.copy(alpha = 0.5f), CircleShape),
        )
        Box(Modifier.size(32.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape))
        // Dark backing bars under the white ticks for sun-glare contrast —
        // iOS DistanceMeasureScreen crosshair (14×3 black 0.6 halos).
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(Color.Black.copy(alpha = 0.6f)))
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(Color.Black.copy(alpha = 0.6f)))
        Box(Modifier.size(width = 12.dp, height = 1.5.dp).background(Color.White))
        Box(Modifier.size(width = 1.5.dp, height = 12.dp).background(Color.White))
    }
}


/// The plot pillar the tap WOULD plant, drawn translucent at the live
/// crosshair hit (FIELD REPORT 8).
///
/// The aiming state used to be a crosshair and nothing else, so the cruiser
/// was placing the plot centre blind and only found out where it had landed
/// after the tap. Both plot-centre screens (quick sampling and the cruise
/// plot start) call this, so the ghost cannot drift from one to the other.
///
/// Same four pieces, same sizes, same colours as the placed assembly the hub
/// builds in rebuildPlotNodes — a preview that looked different would be
/// teaching the cruiser the wrong thing. Only the alpha changes, and the
/// ring is included because the radius slider is right there: the cruiser
/// can see how much ground a 5.6 m plot actually covers before committing to
/// a centre, which is the part that is hardest to judge by eye.
///
/// These are plain world positions, not anchored — there is no ARCore anchor
/// yet, that is what the tap creates — so the ghost rides the aim.
fun plotPillarPreviewMarkers(centre: Vec3?, radiusM: Float): List<ArSceneMarker> {
    if (centre == null) return emptyList()
    val ghost = 0.35f
    return listOf(
        ArSceneMarker(centre, MarkerShape.Sphere(0.07f), floatArrayOf(1f, 0.25f, 0.25f, ghost)),
        ArSceneMarker(
            Vec3(centre.x, centre.y + 0.6f, centre.z),
            MarkerShape.Cylinder(0.03f, 1.2f), floatArrayOf(1f, 1f, 1f, ghost)),
        ArSceneMarker(
            Vec3(centre.x, centre.y + 1.2f, centre.z),
            MarkerShape.Sphere(0.12f), floatArrayOf(1f, 0.85f, 0.15f, ghost)),
        ArSceneMarker(
            Vec3(centre.x, centre.y + 0.02f, centre.z),
            MarkerShape.Ring(radiusM, 0.4f), floatArrayOf(0.2f, 0.85f, 1f, ghost)),
    )
}
