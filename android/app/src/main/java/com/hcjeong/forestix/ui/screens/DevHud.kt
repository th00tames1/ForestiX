// Developer / research HUD — a translucent label:value panel that surfaces
// the live measurement internals on the AR screens when Developer Mode is
// on (Settings). This is the on-device window into everything the
// validation study needs to log: depth source, camera intrinsics, depth-map
// size, point counts, raw chord/diameter, camera pitch, distance, σ.
//
// Pinned top-right so it doesn't collide with the back button (top-left),
// the radius slider (top-centre), or the bottom status panel.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// `lines` is a list of (label, value) pairs the caller assembles from its
/// ArController + measurement state. Pass an empty list to hide.
/// `topPadding` lets a screen push the HUD below other top-right chrome
/// (the plot mini-map widget on DBH/Height when a plot is active).
@Composable
fun BoxScope.DevHud(title: String, lines: List<Pair<String, String>>, topPadding: Dp = 56.dp) {
    if (lines.isEmpty()) return
    Column(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = topPadding, end = 8.dp)
            .widthIn(max = 230.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.62f))
            .border(0.5.dp, Color(0xFF4A9B5C).copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            "DEV · $title",
            color = Color(0xFF6BE08A),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
        for ((label, value) in lines) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Text(value, color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
            }
        }
    }
}
