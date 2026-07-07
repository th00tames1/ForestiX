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

// Direction B — "Field High-Contrast". Dark-only outdoor instrument:
// deep slate canvas, big mono numerals, high-chroma signal colours.
// ForestixTheme forces dark below; mirror of iOS DesignSystem.swift.
private val PrimaryGreen = Color(0xFF55D07A)   // signal green
private val PrimaryInk = Color(0xFF06130A)     // dark ink ON primary
private val AccentAmber = Color(0xFFFFB454)
private val OkGreen = Color(0xFF55D07A)
private val WarnAmber = Color(0xFFFFB454)
private val BadRed = Color(0xFFFF7A6B)

private val FieldDark = ForestixColors(
    primary = PrimaryGreen,
    primaryMuted = PrimaryGreen.copy(alpha = 0.16f),
    accent = AccentAmber,
    confidenceOk = OkGreen,
    confidenceWarn = WarnAmber,
    confidenceBad = BadRed,
    canvas = Color(0xFF0C0F10),
    surface = Color(0xFF171B1D),
    surfaceRaised = Color(0xFF21272A),
    divider = Color(0xFF333B3F),
    textPrimary = Color(0xFFF2F5F3),
    textSecondary = Color(0xFFB7C0BA),
    textTertiary = Color(0xFF79837D),
)

private val LightColors = FieldDark
private val DarkColors = FieldDark

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
    // Direction B is dark-only — the parameter is kept for API stability
    // but ignored; the outdoor instrument always renders on the dark canvas.
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = FieldDark
    CompositionLocalProvider(
        LocalForestixColors provides colors,
        LocalForestixTypography provides Typography,
    ) {
        // darkColorScheme base so Material components (dialogs, menus,
        // text fields) render dark; onPrimary is the dark ink because
        // white on the bright signal green fails 4.5:1.
        MaterialTheme(
            colorScheme = darkColorScheme(
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
