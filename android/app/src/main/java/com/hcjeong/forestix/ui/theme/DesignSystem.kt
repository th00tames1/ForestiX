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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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

// Direction B — "Field High-Contrast" in TWO appearances sharing one
// identity (signal colours, big mono numerals, squarer shapes): a light
// "paper" set (the default) and the dark slate set. Signal hues deepen
// in light mode ON PURPOSE — the bright pair reads on slate but fails
// contrast on paper. Mirror of iOS DesignSystem.swift.
private val PrimaryInk = Color(0xFF06130A)     // dark ink ON primary (both modes)

private val FieldDark = ForestixColors(
    primary = Color(0xFF55D07A),
    primaryMuted = Color(0xFF55D07A).copy(alpha = 0.16f),
    accent = Color(0xFFFFB454),
    confidenceOk = Color(0xFF55D07A),
    confidenceWarn = Color(0xFFFFB454),
    confidenceBad = Color(0xFFFF7A6B),
    canvas = Color(0xFF0C0F10),
    surface = Color(0xFF171B1D),
    surfaceRaised = Color(0xFF21272A),
    divider = Color(0xFF333B3F),
    textPrimary = Color(0xFFF2F5F3),
    textSecondary = Color(0xFFB7C0BA),
    textTertiary = Color(0xFF79837D),
)

private val FieldLight = ForestixColors(
    primary = Color(0xFF2FA45B),
    primaryMuted = Color(0xFF2FA45B).copy(alpha = 0.14f),
    accent = Color(0xFFB57614),
    confidenceOk = Color(0xFF1D7A43),
    confidenceWarn = Color(0xFF9A6414),
    confidenceBad = Color(0xFFB03A2E),
    canvas = Color(0xFFF4F6F4),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFE9EDE9),
    divider = Color(0xFFD3D9D3),
    textPrimary = Color(0xFF171C19),
    textSecondary = Color(0xFF4E5852),
    textTertiary = Color(0xFF7E8781),
)

val LocalForestixColors: ProvidableCompositionLocal<ForestixColors> =
    staticCompositionLocalOf { FieldLight }

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
    dataLarge = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
    data = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),
    dataSmall = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace),
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
    val chip = RoundedCornerShape(5.dp)
    val control = RoundedCornerShape(8.dp)
    val card = RoundedCornerShape(10.dp)
    val chipDp: Dp = 5.dp
    val controlDp: Dp = 8.dp
    val cardDp: Dp = 10.dp
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
    // "light" is the app default; MainActivity passes the persisted
    // AppSettings.appearance so the whole tree flips together.
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) FieldDark else FieldLight
    CompositionLocalProvider(
        LocalForestixColors provides colors,
        LocalForestixTypography provides Typography,
    ) {
        // Matching Material scheme so dialogs/menus/text fields follow;
        // onPrimary is the dark ink in BOTH modes (≥5.9:1 on either green).
        val base = if (darkTheme) darkColorScheme() else lightColorScheme()
        MaterialTheme(
            colorScheme = base.copy(
                primary = colors.primary,
                onPrimary = PrimaryInk,
                secondary = colors.accent,
                onSecondary = PrimaryInk,
                background = colors.canvas,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.surfaceRaised,
                onSurfaceVariant = colors.textSecondary,
                outline = colors.divider,
                error = colors.confidenceBad,
            ),
            content = content,
        )
    }
}
