// Live device-tilt badge for the Diameter scan screen — port of iOS
// Screens/TiltBadge.swift.
//
// The single biggest source of DBH bias is a leaning phone — a chord
// projected through a non-vertical slice of the cylinder reads a
// systematically wrong diameter. Showing the cruiser the live pitch lets
// them self-correct before tapping.
//
// Pitch source: the AR camera's forward elevation (ArController), the same
// convention the height pipeline uses — so the badge, the dev HUD, and the
// research CSV all agree on sign.
//
// Tier mapping uses the existing confidence palette so the colour language
// matches the rest of the app:
//   • |pitch| ≤ 3°  → Level  (confidenceOk)
//   • |pitch| ≤ 8°  → Tilted (confidenceWarn)
//   • |pitch| > 8°  → Tilted (confidenceBad)

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ui.theme.Forestix
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun TiltBadge(controller: ArController, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    var pitchDeg by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            pitchDeg = controller.cameraPitchDeg()
            delay(100)
        }
    }
    val p = pitchDeg
    val (label, tint) = when {
        p == null -> "Tilt —" to colors.confidenceBad
        kotlin.math.abs(p) <= 3f -> "Level" to colors.confidenceOk
        kotlin.math.abs(p) <= 8f -> "Tilted" to colors.confidenceWarn
        else -> "Tilted" to colors.confidenceBad
    }
    Row(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.20f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Bubble-level glyph — nearest Material analogue of SF "level".
        Icon(
            Icons.Filled.Straighten,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(11.dp),
        )
        Text(label, style = type.dataSmall, color = Color.White)
        if (p != null) {
            Text(
                String.format(Locale.US, "%+.0f°", p),
                style = type.dataSmall.copy(fontSize = 13.sp),
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}
