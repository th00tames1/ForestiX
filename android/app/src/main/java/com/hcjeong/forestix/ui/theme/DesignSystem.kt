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

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - Palette ---------------------------------------------------------

/// Light / dark variants of the system-semantic surface + text colours.
/// On iOS these come from `UIColor.systemBackground` etc.; here we hand
/// pick the matching Material neutral ramp so dark mode "just works".
data class ForestixColors(
    val primary: Color,
    /// Dark ink drawn ON primary fills (same value in both appearances) —
    /// public like iOS `ForestixPalette.primaryInk` so custom CTAs can
    /// use it outside the Material colorScheme.
    val primaryInk: Color,
    val primaryMuted: Color,
    val accent: Color,
    /// Cruise-mode identity (map-mode toggle, v3.1): the cruise (+) capture
    /// button fill and the toggle icon tint while cruise mode is on.
    /// Content on it is plain white in BOTH palettes (LOCKED spec values).
    val cruiseAccent: Color,
    val confidenceOk: Color,
    val confidenceWarn: Color,
    val confidenceBad: Color,
    /// GPS-fix status dots. A separate pair from the confidence family on
    /// purpose: [confidenceWarn] is deepened to #9A6414 so it can carry
    /// TEXT on paper, and at the size of a 7 dp dot that deepening reads as
    /// BROWN rather than as a warning — field-reported. These two are never
    /// used for text, so they can stay in the hue a glance actually names.
    /// The dot carries a hairline ring (see GpsFixChip) which is what keeps
    /// the amber legible on a white surface. iOS values 1:1.
    val fixStale: Color,
    val fixLost: Color,
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
    primaryInk = PrimaryInk,
    primaryMuted = Color(0xFF55D07A).copy(alpha = 0.16f),
    accent = Color(0xFFFFB454),
    cruiseAccent = Color(0xFF6AA8DE),
    confidenceOk = Color(0xFF55D07A),
    confidenceWarn = Color(0xFFFFB454),
    confidenceBad = Color(0xFFFF7A6B),
    fixStale = Color(0xFFFFC65C),
    fixLost = Color(0xFFFF7A6B),
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
    primaryInk = PrimaryInk,
    primaryMuted = Color(0xFF2FA45B).copy(alpha = 0.14f),
    accent = Color(0xFFB57614),
    cruiseAccent = Color(0xFF2F6DB2),
    confidenceOk = Color(0xFF1D7A43),
    confidenceWarn = Color(0xFF9A6414),
    confidenceBad = Color(0xFFB03A2E),
    fixStale = Color(0xFFE8A020),
    fixLost = Color(0xFFD93025),
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

// MARK: - Dense-table text scaling ----------------------------------------

/// Upper bound on the SYSTEM font scale inside dense tabular layouts.
///
/// A field log is a four-column table of numbers. On a 360 dp phone those
/// columns and their gutters already spend ~93 % of the row, so an
/// accessibility text size of 1.5–2× does not make the table more
/// readable — it makes headers break mid-word ("PRECISI/ON"), quality
/// chips split ("GOO/D") and values ellipsise. Bounding the scale keeps
/// the table intact while still honouring the first slice of the user's
/// preference. Everything OUTSIDE a table — titles, buttons, body copy,
/// empty states, dialogs — keeps scaling without any limit.
const val ForestixDenseMaxFontScale = 1.15f

/// Runs `content` with the system font scale clamped to
/// [ForestixDenseMaxFontScale]. Only `fontScale` (sp → dp) is clamped;
/// `density` (dp → px) is passed through untouched, so icons, spacing and
/// every dp dimension in the subtree are unaffected.
@Composable
fun ForestixDenseTextScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val bounded = remember(current) {
        if (current.fontScale <= ForestixDenseMaxFontScale) current
        else Density(current.density, ForestixDenseMaxFontScale)
    }
    CompositionLocalProvider(LocalDensity provides bounded, content = content)
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
        else -> TierDescriptor(capitalizedWords(rawTier), c.textSecondary)
    }
}

/// Mirror of Swift's `.capitalized` — uppercase the first letter of every
/// word (any non-letter is a boundary), lowercase the rest, so non-spec
/// tiers render identically on both platforms ("very low" → "Very Low").
private fun capitalizedWords(raw: String): String =
    Regex("\\p{L}+").replace(raw.lowercase(java.util.Locale.US)) { match ->
        match.value.replaceFirstChar { it.titlecase(java.util.Locale.US) }
    }

// MARK: - Primary button (Direction B) ------------------------------------

/// Port of iOS `ForestixProminentButtonStyle` — the app-wide filled CTA:
/// control radius 8, ≈46 dp tall (min 30 content + 8 dp vertical padding),
/// horizontal padding 14, bodyBold 15 label in the dark primary ink, and a
/// 0.78-alpha pressed state over 0.15 s ease-out. No M3 pills anywhere.
@Composable
fun ForestixProminentButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = LocalForestixColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fillAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseOut),
        label = "prominentPressed",
    )
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clip(ForestixRadius.control)
            .background(colors.primary.copy(alpha = fillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null,
                    tint = colors.primaryInk, modifier = Modifier.size(18.dp))
            }
            Text(label, style = LocalForestixTypography.current.bodyBold,
                color = colors.primaryInk)
        }
    }
}

/// Outlined sibling of ForestixProminentButton — same geometry and pressed
/// timing, token divider border + primary-text label instead of the fill.
/// `tint` recolours the label/icon (e.g. confidenceBad for iOS
/// `role: .destructive` bordered buttons); default stays textPrimary.
@Composable
fun ForestixBorderedButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color? = null,
    onClick: () -> Unit,
) {
    val colors = LocalForestixColors.current
    val contentColor = tint ?: colors.textPrimary
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseOut),
        label = "borderedPressed",
    )
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clip(ForestixRadius.control)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) pressAlpha else 0.45f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null,
                    tint = contentColor, modifier = Modifier.size(18.dp))
            }
            Text(label, style = LocalForestixTypography.current.bodyBold,
                color = contentColor)
        }
    }
}

/// AR-screen sibling of ForestixBorderedButton — same geometry and pressed
/// timing, but a SOLID WHITE fill with the primaryInk label (mirroring the
/// white capture "+" aesthetic). Field fix: over bright sky the outlined
/// buttons were invisible in the camera view; the solid fill reads in any
/// light. AR action rows only — non-AR screens keep the bordered style.
@Composable
fun ForestixWhiteButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val colors = LocalForestixColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fillAlpha by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = tween(durationMillis = 150, easing = EaseOut),
        label = "whitePressed",
    )
    Box(
        modifier
            .heightIn(min = 46.dp)
            .clip(ForestixRadius.control)
            .background(Color.White.copy(alpha = fillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null,
                    tint = colors.primaryInk, modifier = Modifier.size(18.dp))
            }
            Text(label, style = LocalForestixTypography.current.bodyBold,
                color = colors.primaryInk)
        }
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
                onPrimary = colors.primaryInk,
                secondary = colors.accent,
                onSecondary = colors.primaryInk,
                // Selected FilterChips (multi-select damage tags) — keep
                // them on the Forestix palette instead of M3 lavender.
                secondaryContainer = colors.primaryMuted,
                onSecondaryContainer = colors.textPrimary,
                background = colors.canvas,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
                surfaceVariant = colors.surfaceRaised,
                onSurfaceVariant = colors.textSecondary,
                // Tonal surface family used by dialogs / menus / sheets —
                // mapped onto the Forestix surface ramp so no component
                // ever renders the M3 purple-tinted neutrals.
                surfaceContainerLowest = colors.surface,
                surfaceContainerLow = colors.surface,
                surfaceContainer = colors.surface,
                surfaceContainerHigh = colors.surfaceRaised,
                surfaceContainerHighest = colors.surfaceRaised,
                outline = colors.divider,
                error = colors.confidenceBad,
            ),
            content = content,
        )
    }
}
