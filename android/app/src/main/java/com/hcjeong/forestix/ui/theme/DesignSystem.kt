// Forestix design system — single source of truth for palette,
// typography, spacing, and shape tokens. Direct port of the iOS
// `DesignSystem.swift` (ForestixPalette / ForestixType / ForestixSpace /
// ForestixRadius). RGB values are copied verbatim from the SwiftUI
// `Color(red:green:blue:)` literals so the two apps render identically.
//
// Design philosophy (unchanged from iOS): a professional instrument,
// not a consumer app. One primary + neutrals + muted tier colours, a
// typographic hierarchy, data-forward layouts. No saturated gradients,
// no decorative flourishes.

package com.hcjeong.forestix.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Palette ---------------------------------------------------------

/// Light / dark variants of the system-semantic surface + text colours.
/// On iOS these come from `UIColor.systemBackground` etc.; here we hand
/// pick the matching Material neutral ramp so dark mode "just works".
data class ForestixColors(
    val primary: Color,
    val primaryMuted: Color,
    val accent: Color,
    val confidenceOk: Color,
    val confidenceWarn: Color,
    val confidenceBad: Color,
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
)

private val PrimaryGreen = Color(0.176f, 0.373f, 0.290f)
private val AccentBeige = Color(0.788f, 0.655f, 0.420f)
private val OkGreen = Color(0.290f, 0.541f, 0.361f)
private val WarnAmber = Color(0.722f, 0.537f, 0.290f)
private val BadRed = Color(0.690f, 0.337f, 0.337f)

private val LightColors = ForestixColors(
    primary = PrimaryGreen,
    primaryMuted = PrimaryGreen.copy(alpha = 0.15f),
    accent = AccentBeige,
    confidenceOk = OkGreen,
    confidenceWarn = WarnAmber,
    confidenceBad = BadRed,
    canvas = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F7),        // iOS secondarySystemBackground
    surfaceRaised = Color(0xFFFFFFFF),  // iOS tertiarySystemBackground (light)
    divider = Color(0x333C3C43),        // iOS separator
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0x993C3C43),
    textTertiary = Color(0x4D3C3C43),
)

private val DarkColors = ForestixColors(
    primary = PrimaryGreen,
    primaryMuted = PrimaryGreen.copy(alpha = 0.22f),
    accent = AccentBeige,
    confidenceOk = OkGreen,
    confidenceWarn = WarnAmber,
    confidenceBad = BadRed,
    canvas = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceRaised = Color(0xFF2C2C2E),
    divider = Color(0x5C545458),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0x99EBEBF5),
    textTertiary = Color(0x4DEBEBF5),
)

val LocalForestixColors: ProvidableCompositionLocal<ForestixColors> =
    staticCompositionLocalOf { LightColors }

// MARK: - Typography ------------------------------------------------------

/// Mirrors ForestixType. Monospaced "data" styles use FontFamily.Monospace
/// so measurement columns line up like a log, matching `.monospaced` on iOS.
data class ForestixTypography(
    val title: TextStyle,
    val sectionHead: TextStyle,
    val body: TextStyle,
    val bodyBold: TextStyle,
    val caption: TextStyle,
    val dataLarge: TextStyle,
    val data: TextStyle,
    val dataSmall: TextStyle,
)

private val Typography = ForestixTypography(
    title = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    sectionHead = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
    body = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal),
    bodyBold = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    caption = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal),
    dataLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
    data = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),
    dataSmall = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),
)

val LocalForestixTypography: ProvidableCompositionLocal<ForestixTypography> =
    staticCompositionLocalOf { Typography }

// MARK: - Spacing ---------------------------------------------------------

object ForestixSpace {
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

// MARK: - Shape -----------------------------------------------------------

object ForestixRadius {
    val chip = RoundedCornerShape(6.dp)
    val control = RoundedCornerShape(10.dp)
    val card = RoundedCornerShape(12.dp)
    val chipDp: Dp = 6.dp
    val controlDp: Dp = 10.dp
    val cardDp: Dp = 12.dp
}

// MARK: - Confidence tier helper -----------------------------------------

/// Port of ConfidenceStyle.descriptor — raw tier string → label + colour.
data class TierDescriptor(val label: String, val color: Color)

@Composable
fun confidenceDescriptor(rawTier: String): TierDescriptor {
    val c = LocalForestixColors.current
    return when (rawTier) {
        "green" -> TierDescriptor("Good", c.confidenceOk)
        "yellow" -> TierDescriptor("Fair", c.confidenceWarn)
        "red" -> TierDescriptor("Check", c.confidenceBad)
        else -> TierDescriptor(rawTier.replaceFirstChar { it.uppercase() }, c.textSecondary)
    }
}

// MARK: - Theme entry point ----------------------------------------------

/// Convenience accessors so screens read `Forestix.colors.primary` and
/// `Forestix.type.dataLarge`, paralleling `ForestixPalette` / `ForestixType`.
object Forestix {
    val colors: ForestixColors
        @Composable get() = LocalForestixColors.current
    val type: ForestixTypography
        @Composable get() = LocalForestixTypography.current
}

@Composable
fun ForestixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    CompositionLocalProvider(
        LocalForestixColors provides colors,
        LocalForestixTypography provides Typography,
    ) {
        // Wrap in Material3 so material components (sliders, etc.) inherit
        // a matching scheme; our own tokens override visible surfaces.
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = colors.primary,
                background = colors.canvas,
                surface = colors.surface,
            ),
            content = content,
        )
    }
}
