// Plot mini-map — a small NORTH-UP schematic of the ACTIVE plot floating
// top-right over the AR measurement screens (locked design, matches iOS):
// a rounded-SQUARE dark-glass card (ForestixRadius.card) — a static
// north-up schematic rather than a live circular radar, and with no
// walked-trail dots — showing the plot ring in the AR-ring cyan, the
// cruiser's own position (YOU dot + heading wedge), and the plot's
// measured trees tinted by confidence.
//
// POSITION DATA (mirrors iOS PlotMiniMapWidget)
//  * YOU — camera offset from the plot's AR anchor whenever the shared
//    ArSessionHub still holds it AND the anchor belongs to the plot being
//    measured (hub.linkedCruisePlotId — an "Add tree" can target an OLDER
//    open plot than the last-placed ring), rotated from the arbitrary-yaw
//    ARCore world frame into north-up ENU. iOS gets that alignment for
//    free (ARKit `.gravityAndHeading` builds a north-aligned world); the
//    Android robust equivalent, reusing the pattern OffsetFlowScreen
//    established: pair a camera-forward compass azimuth (rotation vector
//    remapped for the upright AR posture + declination) with the
//    same-instant ArController.cameraWorldYawDeg(), then run the delta
//    through a circular EMA so a single noisy compass sample can't swing
//    the map. When the AR path is unavailable (no/foreign anchor,
//    tracking lost, compass dead) it falls back to the GPS-ENU offset of
//    the live fix against the plot centre lat/lon — through the SAME age
//    gate the map's inside/outside verdict uses (freshFixOrNull), because
//    a fix this card cannot vouch for is not a position it may draw. With
//    neither source the YOU dot is simply omitted (never drawn at a
//    made-up angle).
//  * TREES — each measured tree's position in the plot's OWN local frame.
//    Preferred source is the bearing + distance from centre stored on the
//    tree row at Accept (that IS the plot-local frame); when that pair is
//    missing it falls back to the ENU offset of the tree's Accept-time GPS
//    fix against the plot centre. A tree with NEITHER source is left out
//    rather than drawn at the centre — the enlarged view says how many
//    were left out. Quick-plot mode (ActiveSamplingPlot without a cruise
//    plot) shows ring + YOU only.
//
// NOTHING IS EVER MOVED TO MAKE IT FIT. Marks are drawn where they
// actually are; one that falls outside what the drawing can show becomes a
// HOLLOW EDGE MARK on the rim, pointing the true way. Both YOU and the
// tree dots used to CLAMP onto the ring instead, which drew a cruiser a
// valley away standing on the plot boundary — the card inventing the one
// fact it exists to report. A direction is less than a position; a wrong
// position is worse than either.
//
// TAPPING the card opens an ENLARGED, centred view of the same plot
// drawing (PlotPreviewDialog) — a cruiser tapping the plot preview
// normally just wants a better look at it. That happens on EVERY host with
// a plot to show; re-setup is a separate, clearly-labelled control INSIDE
// the view ("Edit plot"), present only where there is a saved plot to
// re-open. So the tap can no longer throw anybody straight into changing
// the plot they only meant to read, and a host that cannot offer re-setup
// still gets the bigger picture.
//
// CHEAP: one 5 Hz tick (the same cadence as the sampling boundary check)
// updates a single state holder, quantized to 5 cm / 2° so sub-jitter
// movement doesn't invalidate the card (iOS parity); tree offsets are
// derived only when the tree list changes; everything draws in one
// Canvas.

package com.hcjeong.forestix.ui.screens

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import com.hcjeong.forestix.ui.clickableNoRipple
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeLabel
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture
import com.hcjeong.forestix.ui.screens.cruise.freshFixOrNull
import com.hcjeong.forestix.ui.screens.cruise.plotLengthSpoken
import com.hcjeong.forestix.ui.screens.cruise.plotRadiusBadge
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
// Enlarged plot view (the mini-map's tap target).
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixSpace

// Locked geometry/palette (same values on iOS).
private val CARD_SIZE = 116.dp
private const val RING_FRACTION = 0.78f            // ring diameter vs card
private val RING_CYAN = Color(red = 0.2f, green = 0.85f, blue = 1f)
private val YOU_BLUE = Color(0xFF3B82C4)

/// True when the DBH/Height mini-map will be shown — lets those screens
/// push the developer HUD below it (both live top-right).
@Composable
fun scanPlotMiniMapVisible(): Boolean =
    CruiseCapture.target != null || ArSessionHub.activePlot != null

/// DBH/Height entry: full widget while a cruise plot is ACTIVE (ring +
/// YOU + measured-tree dots); quick ActiveSamplingPlot-only sessions get
/// ring + YOU with no tree dots. Emits nothing when neither exists.
/// Callers hide it during the accept-snapshot chrome blackout.
///
/// The card is TAPPABLE wherever it appears — the tap opens the enlarged
/// plot view, which is what somebody tapping a small picture of their plot
/// is asking for. `onEditPlot` decides whether that view also offers Edit,
/// and the host answers it: a cruise session re-opens the saved plot, a
/// quick session re-opens the sampling screen. FIELD REPORT 12 — this used
/// to be re-gated HERE on `cruise != null`, so a cruiser measuring into a
/// quick sampling ring could look at their plot but never change its radius
/// without walking the whole flow back to the map.
@Composable
fun BoxScope.ScanPlotMiniMap(onEditPlot: (() -> Unit)? = null) {
    // The cruise target is armed before navigation and cleared after the
    // chain pops back to the map — constant for this screen's lifetime.
    val cruise = remember { CruiseCapture.target }
    val quickPlot = ArSessionHub.activePlot
    if (cruise == null && quickPlot == null) return
    val env = LocalAppEnvironment.current
    val treesFlow = remember {
        cruise?.let { env.treeRepository.observeByPlot(it.plotId) } ?: flowOf(emptyList<Tree>())
    }
    val trees by treesFlow.collectAsStateWithLifecycle(emptyList())
    // Cruise plots know their real radius (denormalized area) — the hub's
    // slider radius is only authoritative for the quick sampling plot.
    var radiusOverrideM by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        val c = cruise ?: return@LaunchedEffect
        radiusOverrideM = runCatching { env.plotRepository.read(c.plotId) }.getOrNull()
            ?.plotAreaAcres?.toDouble()
            ?.let { sqrt(Units.acresToSquareMeters(it) / Math.PI) }
            ?.takeIf { it.isFinite() && it > 0.5 }
    }
    // (0,0) is the "no fix at plot save" sentinel — same guard as
    // CruiseCapture.recordDbh's bearing/distance computation.
    val hasCentre = cruise != null && (cruise.plotCenterLat != 0.0 || cruise.plotCenterLon != 0.0)
    PlotMiniMapCard(
        plotNumber = cruise?.plotNumber,
        trees = if (cruise != null) trees else emptyList(),
        plotCenterLat = if (hasCentre) cruise?.plotCenterLat else null,
        plotCenterLon = if (hasCentre) cruise?.plotCenterLon else null,
        radiusOverrideM = radiusOverrideM,
        // The anchor may only stand in for THIS plot's centre when the
        // cruise Start-plot save linked them (quick mode: any anchor).
        requireLinkedPlotId = cruise?.plotId,
        modifier = Modifier
            .align(Alignment.TopEnd)
            // Below the system status bar, then onto the GPS-badge row —
            // the same inset + offset MeasureTopStrip uses, so the card
            // and the strip share one baseline.
            .statusBarsPadding()
            .padding(top = MeasureTopStripTop, end = 16.dp),
        onEditPlot = onEditPlot,
    )
}

/// Sampling/plot-creation entry (callers gate on centre placement): ring +
/// YOU only. Sits BELOW the full-width radius-slider card those screens
/// keep top-centre — the DBH/Height top-22 slot is occupied there. With no
/// `plotNumber` the header renders a plain "PLOT" (iOS parity — the
/// creation screens don't show a number either).
@Composable
fun BoxScope.SamplingPlotMiniMap(plotNumber: Int? = null) {
    PlotMiniMapCard(
        plotNumber = plotNumber,
        trees = emptyList(),
        plotCenterLat = null,
        plotCenterLon = null,
        radiusOverrideM = null,
        requireLinkedPlotId = null,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .statusBarsPadding()
            .padding(top = 172.dp, end = 16.dp),
    )
}

// MARK: - Card

private data class MiniYou(val eastM: Float, val northM: Float, val facingDeg: Float?)

/// One measured tree, placed in the plot's own north-up local frame.
/// `label` is what the enlarged view draws under the dot — the tree's name
/// when it has one, else its number, through the same `TreeLabel.pinTitle`
/// rule the map pins use, so one stem is not called two things on two maps.
/// No coordinates, no row ids.
private data class MiniTreeDot(
    val eastM: Float,
    val northM: Float,
    val warn: Boolean,
    val label: String,
)

/// Placed trees plus the count that could NOT be placed, so the enlarged
/// view can say so instead of quietly showing fewer dots than trees.
private data class MiniTreeDots(val dots: List<MiniTreeDot>, val omitted: Int)

/// Tree positions in the plot's own local frame (north-up metres from the
/// centre), from whichever recorded source is actually populated:
///
///  1. `bearingFromCenterDeg` + `distanceFromCenterM` on the tree row.
///     These are written automatically at Accept (the manual editor was
///     removed, the fields were not) and ARE the plot-local frame, so they
///     are the first choice and the only one that works when the plot
///     centre was never fixed to a real lat/lon.
///  2. The tree's Accept-time GPS fix against the plot centre lat/lon —
///     the source this widget used before, still the one that places a row
///     saved while the plot centre was the (0,0) sentinel and given a real
///     centre afterwards.
///
/// A tree with neither is COUNTED, not drawn: putting it at the centre
/// would invent a position, and reading a gap that isn't there is worse
/// than being told a tree is missing from the picture.
private fun miniTreeDots(
    trees: List<Tree>,
    plotCenterLat: Double?,
    plotCenterLon: Double?,
): MiniTreeDots {
    var omitted = 0
    val dots = trees.mapNotNull { t ->
        val local = treeLocalOffset(t, plotCenterLat, plotCenterLon)
        if (local == null) {
            omitted++
            null
        } else {
            MiniTreeDot(
                eastM = local.first,
                northM = local.second,
                warn = t.dbhConfidence != ConfidenceTier.GREEN ||
                    (t.heightConfidence != null && t.heightConfidence != ConfidenceTier.GREEN),
                label = TreeLabel.pinTitle(t.treeName, t.treeNumber),
            )
        }
    }
    return MiniTreeDots(dots, omitted)
}

/// (east, north) metres from the plot centre, or null when the row carries
/// no usable position. See [miniTreeDots] for the source order.
private fun treeLocalOffset(
    t: Tree,
    plotCenterLat: Double?,
    plotCenterLon: Double?,
): Pair<Float, Float>? {
    val d = t.distanceFromCenterM
    val b = t.bearingFromCenterDeg
    if (d != null && b != null && d.isFinite() && b.isFinite() && d >= 0f) {
        val th = Math.toRadians(b.toDouble())
        return Pair((d * sin(th)).toFloat(), (d * cos(th)).toFloat())
    }
    val lat = t.latitude
    val lon = t.longitude
    if (lat != null && lon != null && plotCenterLat != null && plotCenterLon != null) {
        val dist = GeoMath.distanceM(plotCenterLat, plotCenterLon, lat, lon)
        val bear = Math.toRadians(GeoMath.bearingDeg(plotCenterLat, plotCenterLon, lat, lon))
        if (dist.isFinite()) return Pair((dist * sin(bear)).toFloat(), (dist * cos(bear)).toFloat())
    }
    return null
}

/// The card's tap always opens the ENLARGED plot view. `onEditPlot` only
/// decides whether that view also offers "Edit plot": null means this host
/// has no plot setup to re-open (the quick sampling ring), so the control is
/// absent rather than present and dead. It used to gate the enlarged view
/// itself, which meant hosts that could not offer re-setup showed no bigger
/// picture at all.
@Composable
private fun PlotMiniMapCard(
    plotNumber: Int?,
    trees: List<Tree>,
    plotCenterLat: Double?,
    plotCenterLon: Double?,
    radiusOverrideM: Double?,
    requireLinkedPlotId: UUID?,
    modifier: Modifier,
    onEditPlot: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val controller = ArSessionHub.controller
    val radiusM = radiusOverrideM ?: ArSessionHub.plotRadiusM
    // The spoken radius follows the unit system, like every other length on
    // these screens (they all read settings.unitSystem). It used to be
    // hard-coded to metres, so a cruiser working in feet heard the plot
    // described in a unit they had told the app they do not use.
    val unitSystem =
        LocalAppEnvironment.current.settings.state.collectAsStateWithLifecycle().value.unitSystem

    // Shared GPS (ref-counted subscriber) + camera-forward compass. No
    // permission launcher here: the scan screens' GPS badge / the cruise
    // screens already own the request; without a grant the widget just
    // loses the GPS fallback + declination.
    val location = remember { LocationService.shared(context) }
    val compass = remember { CameraFacingCompass(context, location) }
    val aligner = remember { ArNorthAligner() }
    DisposableEffect(Unit) {
        location.acquire()
        compass.start()
        onDispose {
            compass.stop()
            location.release()
        }
    }

    var you by remember { mutableStateOf<MiniYou?>(null) }

    // 5 Hz tick — the same cadence as the sampling screens' boundary check.
    // Updates ONE state holder; quantized to 5 cm / 2° so sub-jitter
    // movement doesn't recompose the card (snapshot equality drops
    // no-change assignments). The Canvas below is the only reader.
    LaunchedEffect(plotCenterLat, plotCenterLon, requireLinkedPlotId) {
        while (true) {
            val camYaw = controller.cameraWorldYawDeg()
            aligner.update(compass.azimuthTrueDeg, camYaw)
            val deltaDeg = aligner.deltaDeg
            // The anchor may only stand in for the plot centre when it
            // actually marks THIS plot (iOS anchorMatchesPlot rule).
            val anchorMatchesPlot = requireLinkedPlotId == null ||
                ArSessionHub.linkedCruisePlotId == requireLinkedPlotId
            val anchor = if (anchorMatchesPlot) ArSessionHub.plotCenterWorld() else null
            val cam = controller.currentCameraPosition()
            val next = if (anchor != null && cam != null && deltaDeg != null) {
                // AR path: rotate the AR-frame displacement (X≈E0, −Z≈N0)
                // into true ENU — same math as OffsetFlow confirmPlotCenter.
                val east0 = (cam.x - anchor.x).toDouble()
                val north0 = (-(cam.z - anchor.z)).toDouble()
                val th = Math.toRadians(deltaDeg)
                val east = east0 * cos(th) + north0 * sin(th)
                val north = north0 * cos(th) - east0 * sin(th)
                // Facing = AR yaw re-aligned by the smoothed delta (the AR
                // yaw is the low-noise term); raw compass when the aim is
                // too vertical for a stable AR yaw this tick.
                val facing = if (camYaw != null) camYaw + deltaDeg else compass.azimuthTrueDeg
                MiniYou(quantizeM(east), quantizeM(north), facing?.let { quantizeDeg(it) })
            } else {
                // GPS-ENU fallback: the live fix against the plot centre —
                // through THE SAME age gate the map's inside/outside verdict
                // uses, evaluated fresh on every 5 Hz tick so the mark
                // vanishes within 200 ms of the fix ageing out.
                //
                // This path used to take whatever the shared location
                // service still held, with no test at all — the very fallback
                // the verdict deliberately refuses. The service keeps its
                // last snapshot for the life of the process and hands it to
                // the next screen that asks, so a fix from the previous
                // plot, a valley away and hours old, was drawn as the
                // cruiser's position on a card that sits permanently on the
                // AR screens.
                val fix = freshFixOrNull(
                    location.latestSnapshot.value, System.currentTimeMillis())
                if (fix != null && plotCenterLat != null && plotCenterLon != null) {
                    val d = GeoMath.distanceM(plotCenterLat, plotCenterLon, fix.latitude, fix.longitude)
                    val b = Math.toRadians(GeoMath.bearingDeg(plotCenterLat, plotCenterLon, fix.latitude, fix.longitude))
                    MiniYou(
                        quantizeM(d * sin(b)),
                        quantizeM(d * cos(b)),
                        compass.azimuthTrueDeg?.let { quantizeDeg(it) },
                    )
                } else {
                    null
                }
            }
            you = next
            delay(200)
        }
    }

    // Tree offsets in the plot's local frame — derived only when the tree
    // list / centre change (Accept-time events), never on the tick.
    val treeDots = remember(trees, plotCenterLat, plotCenterLon) {
        miniTreeDots(trees, plotCenterLat, plotCenterLon)
    }

    val okColor = Forestix.colors.confidenceOk
    val warnColor = Forestix.colors.confidenceWarn

    val headerText =
        if (plotNumber != null) "PLOT $plotNumber · ${trees.size}" else "PLOT"

    // The enlarged view the tap opens. Lives here so it reads the same live
    // YOU tick and the same tree offsets the card already has.
    var enlarged by remember { mutableStateOf(false) }

    Box(
        modifier
            .size(CARD_SIZE)
            .clip(ForestixRadius.card)
            .background(Color.Black.copy(alpha = 0.55f))
            // The card carries the brighter AR-cyan edge the rest of the
            // plot chrome uses for "this is the plot, and you can touch it".
            .border(1.dp, RING_CYAN.copy(alpha = 0.75f), ForestixRadius.card)
            // ALWAYS tappable: the tap opens a bigger picture of the plot,
            // which is worth having whether or not this host can also offer
            // re-setup. It used to be gated on the edit callback, so during
            // a height measurement the enlarged view did not open at all.
            .clickableNoRipple { enlarged = true }
            .semantics {
                contentDescription =
                    "Show a bigger plot view. ${headerText.lowercase(Locale.US)}, " +
                        "radius ${plotLengthSpoken(radiusM, unitSystem)}"
                onClick(label = "Opens a larger view of the plot and the trees measured so far") {
                    enlarged = true; true
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringR = size.minDimension * RING_FRACTION / 2f
            val mToPx = (ringR / radiusM.coerceAtLeast(0.5)).toFloat()
            // How far from the centre this card can honestly place a mark:
            // the drawing box, less room for the mark itself. It is WIDER
            // than the ring, so a position just outside the plot still draws
            // where it really is. Past it a mark becomes an edge arrow —
            // never a mark pulled back onto the boundary.
            val extent = size.minDimension / 2f - 6.dp.toPx()

            // Plot boundary ring (AR-ring cyan) + centre dot — the map is
            // plot-relative, not user-centred.
            drawCircle(RING_CYAN.copy(alpha = 0.9f), radius = ringR, center = c, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(Color.White, radius = 1.5.dp.toPx(), center = c)

            // Measured trees — confidence-tinted, drawn where they are.
            // One further out than the card reaches becomes a hollow mark
            // on the rim: still that colour, still that direction, no
            // longer claiming a spot on the boundary.
            treeDots.dots.forEach { t ->
                val p = enToPoint(t.eastM, t.northM, mToPx, extent, c) ?: return@forEach
                val tint = if (t.warn) warnColor else okColor
                if (p.beyond) {
                    drawCircle(
                        tint, radius = 2.5.dp.toPx(), center = p.at,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                } else {
                    drawCircle(tint, radius = 2.5.dp.toPx(), center = p.at)
                }
            }

            // YOU — 7 dp you-blue dot + heading wedge (iOS youMark: a
            // 7×5 triangle orbiting 6.5 dp out at the heading angle).
            // Beyond the card's reach it is an edge arrow instead: the
            // cruiser is off the picture in that direction, and the solid
            // "you are here" dot would be a claim about a place they are
            // demonstrably not standing.
            you?.let { u ->
                val p = enToPoint(u.eastM, u.northM, mToPx, extent, c) ?: return@let
                if (p.beyond) {
                    drawEdgeArrow(
                        p.at, c, YOU_BLUE, Color.White.copy(alpha = 0.85f),
                        sizePx = 11.dp.toPx(), strokePx = 1.5.dp.toPx(),
                    )
                    return@let
                }
                u.facingDeg?.let { f ->
                    val a = Math.toRadians(f.toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val perp = Offset(-dir.y, dir.x)
                    val tip = p.at + dir * 9.dp.toPx()
                    val base = p.at + dir * 4.dp.toPx()
                    val b1 = base + perp * 3.5.dp.toPx()
                    val b2 = base - perp * 3.5.dp.toPx()
                    val wedge = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(b1.x, b1.y)
                        lineTo(b2.x, b2.y)
                        close()
                    }
                    drawPath(wedge, YOU_BLUE)
                }
                drawCircle(YOU_BLUE, radius = 3.5.dp.toPx(), center = p.at)
                drawCircle(Color.White.copy(alpha = 0.85f), radius = 3.5.dp.toPx(), center = p.at, style = Stroke(width = 1.dp.toPx()))
            }
        }

        // Header: "PLOT 2 · 5" (plot number · tree count); plain "PLOT"
        // for the number-less quick ring (iOS headerText).
        Text(
            headerText,
            style = MiniMapHeaderStyle,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 6.dp),
        )
        // The affordance: the standard "make this bigger" glyph, in the one
        // corner the card's content never occupies.
        Icon(
            Icons.Filled.Fullscreen,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 6.dp, top = 5.dp)
                .size(13.dp),
        )
        // North tick — just inside the ring's top point (ring top sits at
        // 58 − 45.2 ≈ 13 dp; iOS centres the glyph 7 pt below that).
        Text(
            "N",
            style = MiniMapLabelStyle,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )
        // The DRAWN radius follows the unit system, exactly as the spoken
        // one above it does. It was hard-coded to metres, so a cruiser
        // working in feet was told feet and shown metres off one card.
        Text(
            plotRadiusBadge(radiusM, unitSystem),
            style = MiniMapLabelStyle,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp),
        )
    }

    // What the tap opens: the same plot, big enough to read, over whatever
    // screen the card is floating on. Opening it needs a PLOT, not an edit
    // destination — `canEditPlot` decides only whether the Edit control is
    // in there with it.
    if (enlarged) {
        PlotPreviewDialog(
            plotNumber = plotNumber,
            radiusM = radiusM,
            unitSystem = unitSystem,
            treeCount = trees.size,
            treeDots = treeDots,
            you = you,
            canEditPlot = onEditPlot != null,
            onEditPlot = {
                enlarged = false
                onEditPlot?.invoke()
            },
            onDismiss = { enlarged = false },
        )
    }
}

// MARK: - Enlarged plot view

/// Panel width cap — wide enough to read a 30 m plot's tree spread on a
/// phone, narrow enough to stay a panel rather than a screen.
private val PREVIEW_MAX_WIDTH = 380.dp

/// Ring diameter vs the drawing box. Larger than the card's: the enlarged
/// view has no header furniture crowding the corners.
private const val PREVIEW_RING_FRACTION = 0.84f

/// The AR ring's dark halo, in 2D. A wider dark stroke UNDER the bright
/// cyan rim keeps the boundary legible on a light panel as well as a dark
/// one — the same trick ArSessionHub uses in the AR scene, where the ring
/// has to read on sunlit litter and in deep shade.
private val RING_HALO = Color(red = 0.03f, green = 0.06f, blue = 0.08f, alpha = 0.55f)

/// Above this many trees the numbers are dropped and only the dots are
/// drawn — past it the labels collide and the picture reads worse, not
/// better. Coverage (which is what the view is for) survives either way.
private const val PREVIEW_MAX_LABELS = 30

/// The enlarged plot view. The SAME drawing as the mini-map, at a size a
/// cruiser can read at arm's length, with every measured tree that has a
/// recorded position on it: coverage and gaps at a glance. Re-setup is one
/// clearly-labelled control in here, so tapping the preview can no longer
/// drop anybody into changing the plot they only meant to look at.
@Composable
private fun PlotPreviewDialog(
    plotNumber: Int?,
    radiusM: Double,
    /// The cruiser's units — the drawn radius follows them here for the
    /// same reason it does on the card that opened this view.
    unitSystem: UnitSystem,
    treeCount: Int,
    treeDots: MiniTreeDots,
    you: MiniYou?,
    /// False on hosts with no re-setup destination (the quick sampling ring,
    /// and any host that has no saved plot to re-open); the Edit plot button
    /// is then simply absent rather than present and dead. Mirrors iOS
    /// PlotMapEnlargedView.canEditPlot.
    canEditPlot: Boolean,
    onEditPlot: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = PREVIEW_MAX_WIDTH)
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (plotNumber != null) "Plot $plotNumber" else "Plot",
                    style = type.bodyBold,
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                // Obvious dismiss, on a full-size tap target (gloves).
                Box(
                    Modifier
                        .size(44.dp)
                        .clickableNoRipple(onDismiss)
                        .semantics {
                            contentDescription = "Close the plot view"
                            onClick(label = "Closes the plot view") { onDismiss(); true }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            PlotPreviewDiagram(
                radiusM = radiusM,
                dots = treeDots.dots,
                you = you,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )

            val treeWord = if (treeCount == 1) "tree" else "trees"
            Text(
                "$treeCount $treeWord measured · " + plotRadiusBadge(radiusM, unitSystem),
                style = type.caption,
                color = colors.textSecondary,
            )
            // Never let the picture quietly show fewer trees than were
            // measured: a gap the cruiser walks back to fill has to be a
            // real gap.
            if (treeDots.omitted > 0) {
                val n = treeDots.omitted
                Text(
                    "$n ${if (n == 1) "tree isn't" else "trees aren't"} shown — " +
                        "no position was recorded",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }

            Spacer(Modifier.height(ForestixSpace.xxs))
            if (canEditPlot) {
                ForestixProminentButton(
                    label = "Edit plot",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onEditPlot,
                )
            }
            ForestixBorderedButton(
                label = "Close",
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

/// The enlarged drawing itself: north-up, plot-relative, everything in the
/// plot's own local frame. Marks carry a casing in the panel colour so the
/// picture stays legible whichever appearance the phone is in.
@Composable
private fun PlotPreviewDiagram(
    radiusM: Double,
    dots: List<MiniTreeDot>,
    you: MiniYou?,
    modifier: Modifier,
) {
    val colors = Forestix.colors
    val okColor = colors.confidenceOk
    val warnColor = colors.confidenceWarn
    val ink = colors.textPrimary
    val casing = colors.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(ink, casing) {
        TextStyle(
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            color = ink,
            shadow = Shadow(casing, Offset.Zero, 4f),
        )
    }
    val showLabels = dots.size <= PREVIEW_MAX_LABELS

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringR = size.minDimension * PREVIEW_RING_FRACTION / 2f
            val mToPx = (ringR / radiusM.coerceAtLeast(0.5)).toFloat()
            // The furthest this panel can honestly place a mark (see the
            // card's own `extent`): wider than the ring, so a position just
            // outside the plot draws where it really is.
            val extent = size.minDimension / 2f - 10.dp.toPx()

            // Plot boundary: dark halo under the AR-ring cyan.
            drawCircle(RING_HALO, radius = ringR, center = c, style = Stroke(width = 4.dp.toPx()))
            drawCircle(RING_CYAN, radius = ringR, center = c, style = Stroke(width = 2.dp.toPx()))

            // Plot centre — a CROSS, so it can never be read as one of the
            // round tree dots.
            val arm = 8.dp.toPx()
            listOf(casing to 5.dp.toPx(), ink to 2.dp.toPx()).forEach { (colour, w) ->
                drawLine(colour, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
                drawLine(colour, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = w)
            }

            // Measured trees — confidence-tinted, casing-ringed, drawn where
            // they are. One beyond the panel's reach becomes a hollow rim
            // mark, and loses its number: a label half-cut by the edge reads
            // as a DIFFERENT tree, which is its own small lie.
            val dotR = 4.dp.toPx()
            dots.forEach { t ->
                val p = enToPoint(t.eastM, t.northM, mToPx, extent, c) ?: return@forEach
                val tint = if (t.warn) warnColor else okColor
                if (p.beyond) {
                    drawCircle(casing, radius = dotR + 1.5.dp.toPx(), center = p.at, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(tint, radius = dotR, center = p.at, style = Stroke(width = 1.5.dp.toPx()))
                    return@forEach
                }
                drawCircle(casing, radius = dotR + 1.5.dp.toPx(), center = p.at)
                drawCircle(tint, radius = dotR, center = p.at)
                if (showLabels) {
                    val layout = measurer.measure(AnnotatedString(t.label), labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(
                            p.at.x - layout.size.width / 2f,
                            p.at.y + dotR + 2.dp.toPx(),
                        ),
                    )
                }
            }

            // YOU — same mark as the card, scaled up and casing-ringed;
            // the same edge arrow when the cruiser is off the picture.
            you?.let { u ->
                val p = enToPoint(u.eastM, u.northM, mToPx, extent, c) ?: return@let
                if (p.beyond) {
                    drawEdgeArrow(
                        p.at, c, YOU_BLUE, casing,
                        sizePx = 18.dp.toPx(), strokePx = 2.dp.toPx(),
                    )
                    return@let
                }
                u.facingDeg?.let { f ->
                    val a = Math.toRadians(f.toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val perp = Offset(-dir.y, dir.x)
                    val tip = p.at + dir * 15.dp.toPx()
                    val base = p.at + dir * 6.dp.toPx()
                    val b1 = base + perp * 5.dp.toPx()
                    val b2 = base - perp * 5.dp.toPx()
                    val wedge = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(b1.x, b1.y)
                        lineTo(b2.x, b2.y)
                        close()
                    }
                    drawPath(wedge, YOU_BLUE)
                }
                drawCircle(YOU_BLUE, radius = 5.5.dp.toPx(), center = p.at)
                drawCircle(casing, radius = 5.5.dp.toPx(), center = p.at, style = Stroke(width = 1.5.dp.toPx()))
            }
        }
        // North tick, same convention as the card.
        Text(
            "N",
            style = MiniMapLabelStyle,
            color = colors.textSecondary,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

private val MiniMapHeaderStyle = TextStyle(
    fontSize = 9.sp,
    fontWeight = FontWeight.SemiBold,
    fontFamily = FontFamily.Monospace,
    letterSpacing = 0.5.sp,
)

private val MiniMapLabelStyle = TextStyle(
    fontSize = 8.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = FontFamily.Monospace,
)

/// Quantize a metre offset to 5 cm (iOS parity) so sub-jitter movement
/// doesn't invalidate the card.
private fun quantizeM(v: Double): Float = (Math.round(v * 20.0) / 20.0).toFloat()

/// Quantize a heading to 2°, normalized to 0…360 (iOS parity).
private fun quantizeDeg(v: Double): Float {
    val n = ((v % 360.0) + 360.0) % 360.0
    return (Math.round(n / 2.0) * 2.0).toFloat()
}

/// A placed mark: WHERE it goes, and whether that is its real position or
/// the edge of what the drawing can show.
private data class MiniPoint(val at: Offset, val beyond: Boolean)

/// North-up ENU metres → drawing pixels (+E right, +N up).
///
/// NOTHING IS RELOCATED TO MAKE IT FIT. The returned point is the mark's
/// TRUE scaled position whenever that lands inside `extentPx` (the largest
/// radius this drawing can hold). Past that the point sits ON the extent's
/// rim in the true direction and `beyond` is true, so the caller draws a
/// hollow EDGE MARK — "that way, off the picture" — instead of a normal one.
///
/// It used to clamp to the RING, the one place it must never put anything:
/// an offset of 800 m came back as a mark standing exactly ON the plot
/// boundary, so a cruiser a valley away read as a cruiser at the plot edge.
/// That is not a rounding error, it is a fabricated fact — and it was the
/// fact the card exists to report. Null for a non-finite offset: no
/// position at all beats a mark at an arbitrary spot.
private fun enToPoint(
    eastM: Float,
    northM: Float,
    mToPx: Float,
    extentPx: Float,
    c: Offset,
): MiniPoint? {
    val x = eastM * mToPx
    val y = -northM * mToPx
    if (!x.isFinite() || !y.isFinite()) return null
    val r = sqrt(x * x + y * y)
    if (r > extentPx && r > 0f) {
        val s = extentPx / r
        return MiniPoint(Offset(c.x + x * s, c.y + y * s), beyond = true)
    }
    return MiniPoint(Offset(c.x + x, c.y + y), beyond = false)
}

/// The "off the picture, that way" mark: a HOLLOW arrowhead on the rim of
/// the drawable extent, pointing outward along the true bearing.
///
/// Hollow, and on the rim rather than on the ring, so it can never be read
/// as the thing itself standing at that spot. It reports a DIRECTION and
/// withholds a POSITION, which is exactly what is known about something
/// further out than the drawing reaches.
private fun DrawScope.drawEdgeArrow(
    at: Offset,
    centre: Offset,
    tint: Color,
    casing: Color,
    sizePx: Float,
    strokePx: Float,
) {
    val d = at - centre
    val len = sqrt(d.x * d.x + d.y * d.y)
    if (!len.isFinite() || len <= 0f) return
    val dir = Offset(d.x / len, d.y / len)
    val perp = Offset(-dir.y, dir.x)
    val tip = at + dir * (sizePx * 0.55f)
    val base = at - dir * (sizePx * 0.45f)
    val b1 = base + perp * (sizePx * 0.45f)
    val b2 = base - perp * (sizePx * 0.45f)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(b1.x, b1.y)
        lineTo(b2.x, b2.y)
        close()
    }
    drawPath(head, casing, style = Stroke(width = strokePx * 2.2f))
    drawPath(head, tint, style = Stroke(width = strokePx))
}

// MARK: - AR→north alignment

/// Circular EMA over (compass azimuth − AR camera yaw) samples. One noisy
/// compass reading can't swing the schematic; the smoothed delta converges
/// in ~1 s at the 5 Hz tick (α 0.15). Vector-averaged so the 0°/360° wrap
/// is handled correctly.
private class ArNorthAligner {
    private var s = 0.0
    private var c = 0.0
    private var primed = false

    fun update(azimuthTrueDeg: Double?, camYawDeg: Double?) {
        if (azimuthTrueDeg == null || camYawDeg == null) return
        val v = Math.toRadians(azimuthTrueDeg - camYawDeg)
        if (!primed) {
            s = sin(v)
            c = cos(v)
            primed = true
        } else {
            s = s * (1 - ALPHA) + sin(v) * ALPHA
            c = c * (1 - ALPHA) + cos(v) * ALPHA
        }
    }

    val deltaDeg: Double?
        get() = if (primed) Math.toDegrees(atan2(s, c)) else null

    private companion object {
        const val ALPHA = 0.15
    }
}

/// Camera-forward compass for the upright AR posture — the same rotation-
/// vector remap + declination rules as OffsetFlowScreen's AnchorCompass
/// (LocationService.headingTrueDeg skips the remap because the cruise map
/// holds the phone flat; un-remapped azimuth is ill-conditioned upright).
private class CameraFacingCompass(
    context: Context,
    private val location: LocationService,
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /// Latest camera-forward azimuth in degrees true north (0…360);
    /// null before the first sensor sample.
    @Volatile
    var azimuthTrueDeg: Double? = null
        private set

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.remapCoordinateSystem(
            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble())
        location.latestSnapshot.value?.let { snap ->
            deg += GeomagneticField(
                snap.latitude.toFloat(),
                snap.longitude.toFloat(),
                0f,
                System.currentTimeMillis(),
            ).declination
        }
        azimuthTrueDeg = (deg + 360.0) % 360.0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
