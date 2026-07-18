// Shared AR-measurement control chrome — Android mirror of iOS
// MeasurementControls.swift. Camera-style layout (Arboreal-benchmark
// batch, locked on both platforms): stage guidance in a top-centre banner
// (U1), a bottom-centre 70 dp shutter flanked by 52 dp secondaries with a
// live-value strip above (U2), plus the floating back button and the
// centred bottom panel RESULT states keep.
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Height
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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

// MARK: - Top instruction banner (U1 — all four AR screens)

/// Stage-guidance banner, top-centre: black 0.65 fill, white 14 sp medium,
/// radius 10, max-width 340 dp, top offset 150 below the status bar so it
/// clears the GPS-badge / mini-map row (locked spec, identical on iOS).
/// `failure` renders the shared amber banner directly beneath it, so the
/// AIMING states (which no longer have a bottom panel) keep a failure
/// surface. Callers hide the whole thing during the capture blackout.
@Composable
fun BoxScope.MeasureTopChrome(
    instruction: String?,
    failure: String? = null,
    onDismissFailure: (() -> Unit)? = null,
) {
    if (instruction == null && failure == null) return
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 150.dp)
            .padding(horizontal = 16.dp)
            .widthIn(max = 340.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (instruction != null) {
            Text(
                instruction,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .shadow(3.dp, RoundedCornerShape(10.dp), clip = false)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        failure?.let { MeasureFailureBanner(it, onDismissFailure) }
    }
}

// MARK: - Bottom-centre shutter row (U2 — all four AR screens)

/// Camera-style capture chrome: a 70 dp white shutter bottom-centre
/// (16 above the nav bars) flanked by FIXED 68×70 secondary slots (the
/// 52 dp flank circle top-padded 9 so its centre aligns with the
/// shutter's; captions hang below without affecting the row — iOS
/// MeasureShutterRow geometry 1:1). A compact live-value strip renders
/// directly above the row; `above` renders 12 above the whole block
/// (DbhMethodSelector / ADJUST exit pill placement semantics unchanged).
/// `onCapture == null` keeps the row (and its slots) without the shutter
/// circle — the AR-caliper arm captures via screen taps.
@Composable
fun BoxScope.MeasureShutterBar(
    onCapture: (() -> Unit)?,
    left: (@Composable () -> Unit)? = null,
    right: (@Composable () -> Unit)? = null,
    valueStrip: (@Composable () -> Unit)? = null,
    above: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (above != null) above()
        if (valueStrip != null) valueStrip()
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ShutterFlankSlot(left)
            if (onCapture != null) {
                CaptureButton(onCapture)
            } else {
                Box(Modifier.size(70.dp))
            }
            ShutterFlankSlot(right)
        }
    }
}

/// Fixed 68×70 flank slot; the 52 dp circle is top-padded 9 so its centre
/// sits at the shutter centre, and the caption overflows below the slot.
@Composable
private fun ShutterFlankSlot(content: (@Composable () -> Unit)?) {
    Box(
        Modifier.size(width = 68.dp, height = 70.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (content != null) {
            Box(Modifier.padding(top = 9.dp)) { content() }
        }
    }
}

// MARK: - Live-value pill (U2 value strip)

/// One line of the compact live-value strip directly above the shutter
/// row — same dark-glass capsule language as the under-crosshair pills
/// (iOS MeasureValuePill 1:1). Emphasised lines pass `large = true`
/// (dataLarge); secondary lines default to dataSmall, `dimmed` on a
/// lighter scrim.
@Composable
fun MeasureValuePill(text: String, large: Boolean = false, dimmed: Boolean = false) {
    Text(
        text,
        style = if (large) Forestix.type.dataLarge else Forestix.type.dataSmall,
        color = Color.White.copy(alpha = if (dimmed) 0.85f else 1f),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (dimmed) 0.45f else 0.65f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
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
        // 30 pt "+" glyph — same as the iOS
        // `font(.system(size: 30, weight: .semibold))` capture icon.
        Icon(Icons.Filled.Add, contentDescription = "Capture", tint = colors.primary, modifier = Modifier.size(30.dp))
    }
}

/// 52 dp translucent circular icon button with an optional caption beneath
/// — the SlashScan floating-control look. Used for the Distance mode toggle.
@Composable
fun MeasureCircleButton(
    icon: ImageVector,
    caption: String? = null,
    dim: Boolean = false,
    iconRotation: Float = 0f,
    onClick: () -> Unit,
) {
    // Caption shadow (black 0.5, radius 1) for sun-glare legibility —
    // mirrors the iOS `.shadow(color: .black.opacity(0.5), radius: 1)`.
    val captionBlur = with(LocalDensity.current) { 1.dp.toPx() }
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
            Icon(
                icon,
                contentDescription = caption,
                tint = Color.White.copy(alpha = if (dim) 0.55f else 1f),
                modifier = Modifier.size(19.dp).rotate(iconRotation),
            )
        }
        caption?.let {
            Text(
                it,
                style = Forestix.type.dataSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset.Zero, captionBlur),
                ),
                color = Color.White.copy(alpha = if (dim) 0.55f else 0.95f),
            )
        }
    }
}

/// Distance Live / Two-point toggle — circular icon button with a caption.
/// Icon = the vertical ↕ glyph rotated 90° (a single ↔), matching the iOS
/// `arrow.left.and.right` symbol.
@Composable
fun MeasurePill(title: String, onClick: () -> Unit) {
    MeasureCircleButton(icon = Icons.Filled.Height, caption = title, iconRotation = 90f, onClick = onClick)
}

// MARK: - Centred, half-width status panel

/// Bottom-centre status panel. `above` renders floating content (e.g. the
/// developer DBH method picker) 12 dp above the panel card — the iOS
/// `VStack(spacing: 12) { picker; bottomPanel }` construction.
@Composable
fun BoxScope.MeasureStatusPanel(
    above: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            // Keep clear of the system navigation bar (edge-to-edge is on):
            // inset first, then a 16dp visual gap above it.
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (above != null) above()
        Column(
            modifier = Modifier
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

// MARK: - Failure banner

/// Amber failure/unsupported banner — port of the iOS `bannerView(_:tint:)`
/// (bold callout row on an orange 0.8 fill, radius 8, leading-aligned).
/// `onDismiss` makes it tappable-to-clear where iOS is (Height anchor
/// failures); nil renders a static banner (DBH unsupported-method style).
@Composable
fun MeasureFailureBanner(text: String, onDismiss: (() -> Unit)? = null) {
    val warn = Forestix.colors.confidenceWarn
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(warn.copy(alpha = 0.8f))
    Text(
        text,
        style = Forestix.type.bodyBold.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
        color = Color.White,
        textAlign = TextAlign.Start,
        modifier = (if (onDismiss != null) base.clickableNoRipple(onDismiss) else base)
            .padding(16.dp),
    )
}

// MARK: - Developer research fields (Target / True value)

/// Compact fixed-width research capture row — iOS parity: caption labels at
/// white 0.8, a 70 dp Target-id field and a 90 dp true-value field with a
/// decimal keyboard, HStack spacing 6.
@Composable
fun ResearchFieldsRow(
    targetValue: String,
    onTargetChange: (String) -> Unit,
    targetPlaceholder: String,
    trueLabel: String,
    trueValue: String,
    onTrueChange: (String) -> Unit,
    truePlaceholder: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Target", style = Forestix.type.caption, color = Color.White.copy(alpha = 0.8f))
        ResearchField(targetValue, onTargetChange, targetPlaceholder, width = 70.dp, decimal = false)
        Text(trueLabel, style = Forestix.type.caption, color = Color.White.copy(alpha = 0.8f))
        ResearchField(trueValue, onTrueChange, truePlaceholder, width = 90.dp, decimal = true)
    }
}

/// Single compact field styled like the iOS `.roundedBorder` TextField:
/// white fill, hairline border, radius 5, single line.
@Composable
private fun ResearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    width: Dp,
    decimal: Boolean,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 17.sp, color = Color.Black),
        keyboardOptions = if (decimal) {
            KeyboardOptions(keyboardType = KeyboardType.Decimal)
        } else {
            KeyboardOptions.Default
        },
        cursorBrush = SolidColor(Color.Black),
        modifier = Modifier.width(width),
        decorationBox = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color.White)
                    .border(0.5.dp, Color.Black.copy(alpha = 0.2f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 6.dp, vertical = 7.dp),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 17.sp, color = Color.Black.copy(alpha = 0.3f), maxLines = 1)
                }
                inner()
            }
        },
    )
}
