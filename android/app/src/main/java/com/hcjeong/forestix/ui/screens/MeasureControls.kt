// Shared AR-measurement control chrome — Android mirror of iOS
// MeasurementControls.swift. Camera-style layout (field-benchmark
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixWhiteButton

// MARK: - Top-strip geometry (shared by every AR screen)

// Android draws edge-to-edge, so every top-anchored overlay has to inset
// itself past the system status bar (clock / battery / notch) — the field
// build put the GPS pill and the plot mini-map straight under the clock.
// These constants are the offsets BELOW that inset, and match the iOS
// safe-area offsets one for one.

/// Back button: 16 below the status bar, 44 dp circle.
val MeasureBackTop = 16.dp

/// The GPS / title / mini-map row.
val MeasureTopStripTop = 22.dp

/// Horizontal space the back button claims on the leading edge
/// (16 lead + 44 button + 12 gap).
val MeasureBackSlot = 72.dp

/// Horizontal space the plot mini-map claims on the trailing edge
/// (116 dp card + 16 trailing). Callers reserve it on the top strip only
/// while the card is actually up.
val MeasureMiniMapSlot = 132.dp

/// The AR screens' top strip, laid out as ONE row instead of a pile of
/// independently-aligned overlays. The leading slot (GPS pill) and the
/// centre slot (the cruise "Tree N" title) are siblings in the same Row,
/// so they cannot overlap at any screen width: the centre is centred in
/// whatever is left between the GPS pill and the reserved trailing slot,
/// and gives way rather than colliding when the width runs out.
///
/// The Row registers no pointer input, so it stays transparent to the AR
/// view's own gestures.
@Composable
fun BoxScope.MeasureTopStrip(
    reserveTrailing: Dp = 0.dp,
    leading: @Composable () -> Unit = {},
    centre: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = MeasureTopStripTop, start = MeasureBackSlot, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading()
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { centre() }
        if (reserveTrailing > 0.dp) Spacer(Modifier.width(reserveTrailing))
    }
}

// MARK: - Back button (top-left, every AR screen)

/// Circular translucent back button pinned top-left so every AR screen has
/// a consistent, always-visible exit affordance over the camera feed.
/// No elevation shadow — same reason as MeasureCircleButton: through a
/// translucent fill the platform's tessellated shadow reads as a grey
/// polygon behind the glyph.
@Composable
fun BoxScope.MeasureBackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .align(Alignment.TopStart)
            .statusBarsPadding()
            .padding(start = 16.dp, top = MeasureBackTop)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

// MARK: - Sampling-plot tracking-loss wording

/// What the plot screens say once ArSessionHub has hidden the ring because
/// its anchor pose stopped being corrected. Two strings, one meaning, used
/// by both plot-placement screens; byte-identical to the iOS
/// `MeasurementCopy.plotTrackingLost*` pair. The remedy clause is lifted
/// verbatim from the height screen's TRACKING_LOST_NOW — one tracking
/// dropout, one thing to do about it.
const val PLOT_TRACKING_LOST_HINT =
    "Tracking lost — the plot is hidden rather than drawn in the wrong place. Hold still until the camera picks the scene back up."
const val PLOT_TRACKING_LOST_STATUS = "TRACKING LOST — inside or outside is unknown"

/// Every screen that plants a plot centre by raycasting the crosshair says
/// this when the ray finds nothing. Hoisted out of the two plot screens
/// because the scan screens' "Pin centre" now fires the SAME raycast and
/// must fail in the same words. Byte-identical to iOS
/// `MeasurementCopy.plotGroundNotSeen`.
const val PLOT_GROUND_NOT_SEEN =
    "Couldn't see the ground here. Aim at the ground and try again."

// MARK: - Plot centre known only as GPS (field report 14 × 17)

/// FIELD REPORT 14 vs 17. The subdued ring + pillar are drawn from an AR
/// ANCHOR, and only the AR "Start plot" route creates one. A plot opened
/// from a planned pin ("Start plot now" / "Set plot centre (GPS)" — the
/// one-tap route report 17 made the recommended one) and any plot carried
/// across an app restart have a centre that is a LAT/LON and nothing else,
/// so the scan screens showed a bare camera feed with a plot active.
///
/// The ring is NOT synthesised from the GPS centre. A fix under canopy is
/// worth several metres and an ARCore anchor is worth centimetres; drawing
/// one as the other would put a boundary on screen that is not where the
/// boundary is, and the ring's whole job is answering "am I inside?". So the
/// screen says what it has and offers the one act that produces a
/// centimetre-grade centre — the cruiser standing at the centre and pinning
/// it, exactly what the AR route does.
///
/// Byte-identical to the iOS `MeasurementCopy.plotCentreNotPinned*` set.
const val PLOT_CENTRE_NOT_PINNED_HINT =
    "No ring: this plot's centre is a GPS position, not an AR pin. Stand at the plot centre, aim at the ground, and tap Pin centre."

/// Said on the same card, because a control that quietly rewrote the
/// recorded centre would be the invisible data loss the plot-edit path
/// already refuses. Pinning is a DRAWING act only.
const val PLOT_PIN_CENTRE_NOTE =
    "Pinning draws the ring only — the plot's recorded centre does not change."
const val PLOT_PIN_CENTRE_BUTTON = "Pin centre"
const val PLOT_PIN_CENTRE_DISMISS = "Not now"

// MARK: - Top instruction banner (U1 — all four AR screens)

/// Stage-guidance banner, top-centre: black 0.65 fill, white 14 sp medium,
/// radius 10, max-width 340 dp, top offset 150 below the status bar so it
/// clears the GPS-badge / mini-map row (locked spec, identical on iOS).
/// `failure` renders the shared amber banner directly beneath it, so the
/// AIMING states (which no longer have a bottom panel) keep a failure
/// surface. Callers hide the whole thing during the capture blackout.
///
/// `below` is the mirror of the iOS `MeasureTopBanner`'s `extra` slot: an
/// INTERACTIVE card rendered in the same 340 dp column under the guidance,
/// so an offer the cruiser can act on travels with the guidance instead of
/// fighting the bottom block for room. Used by the scan screens' "Pin
/// centre" card; it alone is enough to make the column render, because a
/// card with nothing to say above it still has to be reachable.
@Composable
fun BoxScope.MeasureTopChrome(
    instruction: String?,
    failure: String? = null,
    onDismissFailure: (() -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    if (instruction == null && failure == null && below == null) return
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
        if (below != null) below()
    }
}

// MARK: - "Pin centre" offer (plot centre known only as GPS)

/// The offer the DBH / Height screens make when a plot is being tallied but
/// no AR anchor marks its centre, so there is no ring to draw. Rendered in
/// `MeasureTopChrome`'s `below` slot.
///
/// Both scan screens use this ONE composable so the offer cannot drift
/// between them, and it is byte-identical to the iOS `PlotPinCentreCard`.
///
/// `failure` carries the raycast refusal ([PLOT_GROUND_NOT_SEEN]) so a tap
/// that found no ground says so here rather than leaving the button looking
/// broken. A mis-aimed pin is not trapped: the ring appears immediately, and
/// the mini-map's enlarged view → "Edit plot" re-opens the full placement
/// screen (ghost preview + Reset) to put it right.
@Composable
fun PlotPinCentreCard(
    failure: String? = null,
    onPin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warn = Forestix.colors.confidenceWarn
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(3.dp, RoundedCornerShape(10.dp), clip = false)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            PLOT_CENTRE_NOT_PINNED_HINT,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Text(
            PLOT_PIN_CENTRE_NOTE,
            style = TextStyle(fontSize = 12.sp),
            color = Color.White.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
        if (failure != null) {
            Text(
                failure,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = warn,
                textAlign = TextAlign.Center,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // White / prominent, the same pair the plot screens' Reset and
            // Save wear — iOS `.forestixARSecondary` and `.forestixProminent`.
            ForestixWhiteButton(
                PLOT_PIN_CENTRE_DISMISS,
                modifier = Modifier.weight(1f),
                onClick = onDismiss,
            )
            ForestixProminentButton(
                PLOT_PIN_CENTRE_BUTTON,
                modifier = Modifier.weight(1f),
                onClick = onPin,
            )
        }
    }
}

// MARK: - Bottom-centre shutter row (U2 — all four AR screens)

/// Camera-style capture chrome: a 70 dp white shutter bottom-centre
/// (16 above the nav bars) flanked by FIXED 68×70 secondary slots (the
/// 52 dp flank circle top-padded 9 so its centre aligns with the
/// shutter's; the caption sits below it and the slot grows to fit — iOS
/// MeasureShutterRow geometry). A compact live-value strip renders directly
/// above the row; `above` renders 12 above the whole block (ADJUST exit pill
/// / Undo toast placement semantics unchanged).
///
/// `onCapture` used to be nullable so the AR-caliper arm could keep the row
/// without a shutter (it captured via screen taps). That arm is gone and
/// every screen has a shutter, so the parameter is required again.
@Composable
fun BoxScope.MeasureShutterBar(
    onCapture: () -> Unit,
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
            CaptureButton(onCapture)
            ShutterFlankSlot(right)
        }
    }
}

/// 68 dp-wide flank slot, at LEAST as tall as the 70 dp shutter; the 52 dp
/// circle is top-padded 9 so its centre sits at the shutter centre.
///
/// Field fix: the slot used to be a hard `size(68.dp, 70.dp)`. The circle
/// alone eats 9 + 52 of that, so the Column handed its caption a ~6 dp
/// height budget — and a Text whose paragraph overflows its box clips to
/// that box, which is why only the tops of the caption letters rendered.
/// `heightIn(min = …)` lets the slot grow to the caption instead (and keeps
/// growing at large system font scales), while the Row's Top alignment
/// keeps every circle centre on the shutter centre.
@Composable
private fun ShutterFlankSlot(content: (@Composable () -> Unit)?) {
    Box(
        Modifier.width(68.dp).heightIn(min = 70.dp),
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
/// — a floating-control look. Used for the Distance mode toggle.
///
/// Field fix: this used to carry `shadow(3.dp, CircleShape, clip = false)`.
/// An elevation shadow is drawn UNDER the whole casting outline, and the
/// platform tessellates that outline into a coarse polygon at this size —
/// so through a 0.55-alpha fill it read as a grey octagon sitting behind
/// the icon, a second shape on top of the circle. The hairline white border
/// already separates the button from the camera feed, so the shadow goes.
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
            // ...and out from under the keyboard. This panel carries the
            // Retake / Details / ACCEPT row, and developer mode adds the
            // "True H (m)" research field right above it — tapping that field
            // raised the IME straight over Accept, so a measured height had no
            // reachable save button and was silently never recorded.
            .imePadding()
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

// MARK: - Developer research field (hand-measured true value)

/// Compact fixed-width research capture row — iOS parity: caption label at
/// white 0.8 and a 90 dp true-value field with a decimal keyboard, spacing 6.
///
/// The "Target" id box that used to lead this row is gone. It meant nothing to
/// a cruiser, and it was a free-text field they had to keep in step by hand
/// with the tree they were actually standing at — when it drifted, the
/// research CSV's `tree_id` pointed at the wrong tree. That column is now
/// filled from the tree number the capture is already locked to, which cannot
/// drift.
///
/// `trueLabel` MUST come from `TruthInput.fieldLabel(quantity, unit)` and
/// `truthUnit` MUST be the same unit that label was built from — the two are
/// separate parameters only because the label is a plain string here. The
/// toggle changes the unit for THIS entry; the caller re-derives the label
/// from it, so the field can never be labelled in one system while the value
/// is read as another.
@Composable
fun ResearchFieldsRow(
    trueLabel: String,
    trueValue: String,
    onTrueChange: (String) -> Unit,
    truePlaceholder: String,
    truthUnit: TruthInput.Unit,
    onToggleTruthUnit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(trueLabel, style = Forestix.type.caption, color = Color.White.copy(alpha = 0.8f))
        ResearchField(trueValue, onTrueChange, truePlaceholder, width = 90.dp, decimal = true)
        TruthUnitToggle(truthUnit, onToggle = onToggleTruthUnit)
    }
}

/// Per-entry unit switch that sits next to a typed ground-truth field.
///
/// The field opens in the cruiser's ACTIVE unit system; this changes it for
/// THIS entry only, and the field's label is driven from the same value, so
/// what is typed and what is read can never disagree. The button shows the
/// unit currently in force — the cruiser reads the state, not the action.
///
/// CROSS-PLATFORM: same square, same unit text, same accessibility wording as the
/// iOS `TruthUnitToggle`.
///
/// `onDarkPanel` picks the chrome: the scan screens are a dark camera overlay,
/// the field log is a standard light sheet, and the white-on-white that once
/// made the scan-screen truth fields invisible would happen here if one
/// styling served both.
///
/// The 32 dp square is what is DRAWN; the tappable box around it is 48 dp,
/// Android's minimum touch target — the visual size is a matter of fitting the
/// row, the touch target is a matter of hitting it with a glove on. It also
/// carries `Role.Button`, without which TalkBack announces the control as
/// plain text while VoiceOver announces the iOS twin as a button.
@Composable
fun TruthUnitToggle(
    unit: TruthInput.Unit,
    onDarkPanel: Boolean = true,
    onToggle: () -> Unit,
) {
    val colors = Forestix.colors
    val ink = if (onDarkPanel) Color.White else colors.textPrimary
    val fill = if (onDarkPanel) Color.White.copy(alpha = 0.12f) else colors.surfaceRaised
    val stroke = if (onDarkPanel) Color.White.copy(alpha = 0.4f) else colors.divider
    Box(
        Modifier
            .size(48.dp)
            .clickableNoRipple(onToggle)
            .semantics {
                role = Role.Button
                contentDescription = "Unit for this entry: ${unit.raw}. Tap to switch."
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(fill)
                .border(0.5.dp, stroke, RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(unit.raw, style = Forestix.type.caption, color = ink)
        }
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

/// Colours for a text field the cruiser TYPES INTO on one of the scan
/// screens' dark result panels.
///
/// FIELD REPORT — Material's OutlinedTextField defaults its text to
/// `onSurface`, which in this app's palette is near-black. On the scan
/// panels (black 0.55 over the camera feed) that is black on black: the
/// manual-entry field looked empty however much was typed into it, so the
/// cruiser could not read back the diameter or height they had just entered.
/// The PLACEHOLDER looked fine, which is what made it easy to miss — it is
/// drawn by the control at a different alpha.
///
/// Mirror of the iOS `scanPanelTextField()` modifier. Use this on EVERY
/// typed field on a dark Compose panel rather than fixing them one at a
/// time: the defect is a default, so it comes back on the next field
/// somebody adds.
@Composable
fun scanPanelTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color.White.copy(alpha = 0.6f),
    cursorColor = Color.White,
    focusedBorderColor = Color.White.copy(alpha = 0.7f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.4f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.6f),
    focusedLabelColor = Color.White.copy(alpha = 0.7f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
)
