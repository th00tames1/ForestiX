// Shared AR-measurement control chrome — Android mirror of iOS
// MeasurementControls.swift, aligned with SlashScan: a right-edge vertical
// stack of floating round buttons with soft drop shadows over the camera,
// white-tinted icons, plus a centred half-width readout.
//
// Android note: there is no LiDAR hardware, so there is no LiDAR/AR source
// toggle here — the app uses the ARCore Depth API automatically when the
// device supports it and falls back to plane hits otherwise.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix

// MARK: - Back button (top-left, every AR screen)

/// Circular translucent back button pinned top-left so every AR screen has
/// a consistent, always-visible exit affordance over the camera feed.
@Composable
fun BoxScope.MeasureBackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .padding(start = 16.dp, top = 16.dp)
            .size(44.dp)
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

// MARK: - Right-edge control column

@Composable
fun BoxScope.MeasureControlColumn(
    onCapture: () -> Unit,
    extra: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CaptureButton(onCapture)
        if (extra != null) extra()
    }
}

@Composable
fun CaptureButton(onClick: () -> Unit) {
    val colors = Forestix.colors
    Box(
        Modifier
            .size(70.dp)
            .shadow(3.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Color.Black.copy(alpha = 0.10f), CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Add, contentDescription = "Capture", tint = colors.primary, modifier = Modifier.size(32.dp))
    }
}

/// 52 dp translucent circular icon button with an optional caption beneath
/// — the SlashScan floating-control look. Used for the Distance mode toggle.
@Composable
fun MeasureCircleButton(icon: ImageVector, caption: String? = null, dim: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .size(52.dp)
                .shadow(3.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                .clickableNoRipple(onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = caption, tint = Color.White.copy(alpha = if (dim) 0.55f else 1f), modifier = Modifier.size(19.dp))
        }
        caption?.let {
            Text(
                it,
                style = Forestix.type.dataSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
                color = Color.White.copy(alpha = if (dim) 0.55f else 0.95f),
            )
        }
    }
}

/// Distance Live / Two-point toggle — circular icon button with a caption.
@Composable
fun MeasurePill(title: String, onClick: () -> Unit) {
    MeasureCircleButton(icon = Icons.Filled.SwapHoriz, caption = title, onClick = onClick)
}

// MARK: - Centred, half-width status panel

@Composable
fun BoxScope.MeasureStatusPanel(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 28.dp)
            .widthIn(max = 340.dp)
            .shadow(3.dp, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
    }
}

@Composable
fun CenteredText(text: String, large: Boolean = false, dim: Boolean = false, color: Color = Color.White) {
    Text(
        text,
        style = if (large) Forestix.type.dataLarge else Forestix.type.body,
        color = color.copy(alpha = if (dim) 0.85f else 1f),
        textAlign = TextAlign.Center,
    )
}
