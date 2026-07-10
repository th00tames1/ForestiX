package com.hcjeong.forestix.ui

import android.content.Context
import android.content.Intent
import android.graphics.BlurMaskFilter
import android.net.Uri
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/// Tap with no ripple — matches the flat, instrument-like tap feedback of
/// the iOS `.buttonStyle(.plain)` cards.
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(interactionSource = interaction, indication = null, onClick = onClick)
}

/// Pressed feedback matching iOS `MapPressableStyle` (and the prominent
/// button's timing language): opacity 0.78 + scale 0.97 while pressed,
/// animated over 0.15 s ease-out. Applied to every map chrome button/row.
fun Modifier.pressableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseOut),
        label = "pressAlpha",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseOut),
        label = "pressScale",
    )
    graphicsLayer {
        this.alpha = alpha
        scaleX = scale
        scaleY = scale
    }.clickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClick = onClick,
    )
}

/// Explicit drop shadow with iOS `.shadow(color:radius:y:)` semantics —
/// a blurred silhouette of the (rounded-rect or circular) bounds, drawn
/// behind the content. Compose's elevation shadow can't reproduce the
/// exact colour/blur/offset triplets the mock specifies, this can.
/// `cornerRadius = null` → capsule/circle (half the short side).
fun Modifier.softDropShadow(
    color: Color,
    blurRadius: Dp,
    offsetY: Dp,
    cornerRadius: Dp? = null,
): Modifier = drawBehind {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        if (blurRadius > 0.dp) {
            maskFilter = BlurMaskFilter(blurRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
    }
    val radius = cornerRadius?.toPx() ?: (size.minDimension / 2f)
    val dy = offsetY.toPx()
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawRoundRect(
            0f, dy, size.width, size.height + dy, radius, radius, paint)
    }
}

/// Android equivalent of UIActivityViewController for sharing an exported
/// CSV / ZIP content:// URI.
fun shareFile(context: Context, uri: Uri, mime: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export"))
}
