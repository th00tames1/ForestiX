// Shared AR-screen overlay chrome: centre crosshair, capture-failure
// banner, and the translucent bottom panel — the common pieces every AR
// measurement screen layers over the camera feed (mirrors the repeated
// SwiftUI overlay code on iOS).

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
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun CenterCrosshair(modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).border(4.dp, Color.Black.copy(alpha = 0.5f), CircleShape),
        )
        Box(Modifier.size(32.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape))
        Box(Modifier.size(width = 12.dp, height = 1.5.dp).background(Color.White))
        Box(Modifier.size(width = 1.5.dp, height = 12.dp).background(Color.White))
    }
}

@Composable
fun CaptureFailureBanner(text: String) {
    val type = Forestix.type
    Text(
        text,
        style = type.caption,
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xD9FF9800))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
fun ArBottomPanel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ForestixSpace.md)
            .padding(bottom = ForestixSpace.lg)
            .clip(RoundedCornerShape(ForestixSpace.sm))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(ForestixSpace.md),
    ) { Column { content() } }
}
