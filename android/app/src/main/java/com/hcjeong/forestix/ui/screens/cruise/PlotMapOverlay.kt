// The sampling plot ON THE MAP HOME (M1/M2) — the cruise-side half of the
// basemap's MapPlotOverlay: what to draw, in the cruiser's units, and what
// happens when the drawn plot is tapped.
//
// WHY: the plot used to be a bare accent circle. Standing in a stand, the
// two questions a cruiser actually asks of it are "where is the plot?" and
// "am I in it?" — so the circle now carries labelled range rings, compass
// badges on true bearings, a centre mark, a live distance from that centre,
// and an INSIDE / OUTSIDE state that switches the whole overlay to the
// warning tint and draws a dotted line back to the centre when they have
// walked out of it.
//
// NO POSITION is its own state, never a third guess: with no USABLE live
// position the plot still draws — greyed, dashed and labelled "No position"
// — and the banner says the distance is unknown rather than implying inside
// or outside. "Usable" means fresh: a fix older than PLOT_FIX_MAX_AGE_MS
// is not evidence of where anyone is standing now, and is treated exactly
// like no fix at all.
//
// The drawing itself lives in basemap/MapView.kt (it is a map layer, not a
// screen); this file owns the units, the ring interval, the state decision,
// the banner and the tap menu.

package com.hcjeong.forestix.ui.screens.cruise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hcjeong.forestix.basemap.MAP_ZOOM_MAX
import com.hcjeong.forestix.basemap.MAP_ZOOM_MIN
import com.hcjeong.forestix.basemap.MapCameraState
import com.hcjeong.forestix.basemap.MapPlotFix
import com.hcjeong.forestix.basemap.MapPlotOverlay
import com.hcjeong.forestix.basemap.MapPlotRing
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.hasCentre
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.geo.CoordinateConversions.LatLon
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.softDropShadow
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// MARK: - Units ---------------------------------------------------------------

/// Metres out, feet back for an imperial cruiser. The plot is a LENGTH on
/// the ground, so it follows the same unit system as every other length in
/// the app — nothing here is ever hard-coded to one system.
private fun lengthInDisplayUnit(metres: Double, system: UnitSystem): Double =
    if (system == UnitSystem.METRIC) metres else Units.metersToFeet(metres)

private fun displayUnitToMetres(value: Double, system: UnitSystem): Double =
    if (system == UnitSystem.METRIC) value else Units.feetToMeters(value)

private fun unitSuffix(system: UnitSystem): String =
    if (system == UnitSystem.METRIC) "m" else "ft"

/// A plot length for a cruiser to read at arm's length: one decimal, the
/// active unit ("7.2 m" / "23.6 ft").
internal fun plotLengthLabel(metres: Double, system: UnitSystem): String =
    String.format(
        Locale.US, "%.1f %s",
        lengthInDisplayUnit(metres, system), unitSuffix(system),
    )

/// The same plot length SPOKEN, for accessibility labels — where "m" and
/// "ft" are read out as bare letters. Rounded to a whole unit, because a
/// screen reader announcing "seven point two" adds nothing here.
///
/// It follows the unit system for the same reason every visible length
/// does: a cruiser who works in feet does not stop working in feet because
/// the label is spoken instead of drawn.
internal fun plotLengthSpoken(metres: Double, system: UnitSystem): String {
    val v = lengthInDisplayUnit(metres, system)
    // roundToInt throws on NaN, and a label is never worth a crash.
    if (!v.isFinite()) return "unknown"
    val n = v.roundToInt()
    val unit = if (system == UnitSystem.METRIC) {
        if (n == 1) "metre" else "metres"
    } else {
        if (n == 1) "foot" else "feet"
    }
    return "$n $unit"
}

/// The plot RADIUS as the scan screens' plot card and its enlarged view
/// DRAW it: whole units, active system ("7 m radius" / "24 ft radius").
///
/// The spoken label already followed the unit system; the drawn one did
/// not, so a cruiser working in feet was TOLD feet and SHOWN metres off the
/// same card. Both now come from here, because there is only one right
/// answer to "how big is this plot?" and it is the one the cruiser set.
internal fun plotRadiusBadge(metres: Double, system: UnitSystem): String =
    String.format(
        Locale.US, "%.0f %s radius",
        lengthInDisplayUnit(metres, system), unitSuffix(system),
    )

// MARK: - Framing a new plot --------------------------------------------------

/// A plot that has just been created, waiting for the map to frame it.
///
/// The map home owns the camera, but a plot is created on OTHER surfaces —
/// the AR "Start plot" screen (its own nav destination) and the planned-pin
/// conversion (a sheet, and a peek button). Rather than thread a camera
/// through all three, each creation site leaves the ring's centre and radius
/// here and the map picks it up on its next composition. Compose snapshot
/// state, so the map recomposes when it is set.
///
/// ONE SHOT: the map clears it after moving. It is a request to look at a new
/// plot, not a standing instruction, and re-framing on every recomposition
/// would fight the cruiser's own panning.
object PendingPlotFraming {

    data class Request(val centre: LatLon, val radiusM: Double)

    var request: Request? by mutableStateOf(null)
        private set

    /// Ask the map to frame a plot. Silently ignores a radius that is not a
    /// real length — a frame computed from nothing is a guess, and the map's
    /// existing camera is a better answer than one.
    fun requestFraming(centre: LatLon, radiusM: Double) {
        if (!radiusM.isFinite() || radiusM <= 0.0) return
        request = Request(centre, radiusM)
    }

    fun consume() { request = null }
}

/// How much wider than the plot's DIAMETER the viewport should be when the
/// map is asked to frame a plot. 1.6 leaves ~30 % of the plot's radius of
/// ground visible outside the ring on the short side — enough to see which
/// side of the boundary the you-dot is on, and to see the ring's own labels,
/// without shrinking the circle to a dot. iOS uses the same number.
private const val PLOT_FRAMING_HEADROOM = 1.6

/// The zoom at which a plot of [radiusM] fills the viewport with headroom.
///
/// Derived from the RADIUS, never from a hardcoded level: a 1/10-acre fixed
/// plot is ~11.3 m radius, a variable-radius or a large plot is not, and a
/// level that framed one would lose the other off-screen or leave it a dot.
///
/// The viewport's size in pixels is not needed — only how much GROUND it
/// currently shows. [camera] reports the bounds it is displaying at its
/// current zoom, so the SHORT side's ground span against the span we want is
/// exactly the scale change to apply, and zoom is log2 of scale. Returns null
/// rather than a guess when the map has not laid out yet; the caller then
/// leaves the zoom alone. iOS `plotFramingZoom` is the same computation.
internal fun plotFramingZoom(camera: MapCameraState, radiusM: Double): Double? {
    if (!radiusM.isFinite() || radiusM <= 0.0) return null
    val bounds = camera.visibleBounds() ?: return null
    val minLat = bounds.minOf { it.latitude }
    val maxLat = bounds.maxOf { it.latitude }
    val minLon = bounds.minOf { it.longitude }
    val maxLon = bounds.maxOf { it.longitude }
    val midLat = (minLat + maxLat) / 2
    val widthM = CoordinateConversions.haversineMeters(
        LatLon(latitude = midLat, longitude = minLon),
        LatLon(latitude = midLat, longitude = maxLon),
    )
    val heightM = CoordinateConversions.haversineMeters(
        LatLon(latitude = minLat, longitude = minLon),
        LatLon(latitude = maxLat, longitude = minLon),
    )
    val shownM = min(widthM, heightM)
    val wantM = 2 * radiusM * PLOT_FRAMING_HEADROOM
    if (!shownM.isFinite() || shownM <= 0.0) return null
    val zoom = camera.zoom + log2(shownM / wantM)
    if (!zoom.isFinite()) return null
    return zoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
}

// MARK: - Range rings ---------------------------------------------------------

/// Round intervals a distance label may use, IN THE ACTIVE UNIT. The same
/// ladder serves metres and feet — which is the point: a 5 is a 5 whether
/// the cruiser reads metres or feet, and nothing in here knows about feet
/// specifically.
private val RING_LADDER = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0)

/// About this many rings inside the boundary. Fewer and the plot has no
/// sense of scale; more and the labels crowd each other.
private const val RING_TARGET = 4.0

/// Concentric range rings for a plot of `radiusM`, labelled in the active
/// unit.
///
/// RULE: take the ideal interval (radius ÷ 4), snap it to the nearest rung
/// of the 1 / 2 / 5 / 10 / 20 / 25 / 50 / 100 ladder ON A LOG SCALE — so
/// "nearest" means nearest by ratio, which is how a reader perceives it —
/// then draw a ring at every whole multiple of that interval that lands
/// strictly inside the boundary. A multiple within 2 % of the boundary is
/// dropped: the boundary is already drawn, and already labelled with the
/// radius in the banner.
///
/// Worked, metric, radius 7.2 m: ideal 1.8 → the ladder rungs either side
/// are 1 (ratio 1.80) and 2 (ratio 1.11), so 2 m wins → rings at 2, 4 and
/// 6 m. Worked, the same plot in feet (23.6 ft): ideal 5.9 → 5 (ratio 1.18)
/// beats 10 (ratio 1.69) → rings at 5, 10, 15 and 20 ft.
internal fun plotRangeRings(radiusM: Double, system: UnitSystem): List<MapPlotRing> {
    val radius = lengthInDisplayUnit(radiusM, system)
    if (!radius.isFinite() || radius <= 0.0) return emptyList()
    val ideal = radius / RING_TARGET
    val step = RING_LADDER.minByOrNull { abs(ln(it / ideal)) } ?: return emptyList()
    val count = floor(radius / step).toInt()
    if (count < 1) return emptyList()
    return (1..count).mapNotNull { i ->
        val at = step * i
        // Never draw a ring on top of the boundary itself.
        if (at >= radius * 0.98) return@mapNotNull null
        MapPlotRing(
            radiusM = displayUnitToMetres(at, system),
            label = String.format(Locale.US, "%.0f %s", at, unitSuffix(system)),
        )
    }
}

// MARK: - How old a fix may be ------------------------------------------------

/// HOW OLD THE LAST FIX MAY BE and still be allowed to answer "am I in the
/// plot?".
///
/// The fix carries the instant it was taken, and nothing else in the app
/// re-dates it: the shared LocationService keeps its last snapshot for the
/// whole life of the process, is never cleared on stop(), and hands it to
/// the next screen that asks. So without this gate, walking under canopy
/// until GPS drops leaves the banner asserting Inside from a fix minutes
/// old, and re-entering the map shows the PREVIOUS session's fix — possibly
/// hours old — as if it were where the cruiser is standing.
///
/// FIVE SECONDS. The unit that matters is not time, it is DISTANCE MOVED:
/// a cruiser walking a stand covers roughly a metre a second, so five
/// seconds is about five metres of uncertainty — already the order of a
/// whole plot radius (a 1/20-acre fixed plot is 8.0 m). Anything longer and
/// the claim outruns the evidence; anything much shorter and a normal 1 Hz
/// fix cadence would flicker the banner for no gain. It is also the line
/// the app already draws: the GPS chip's freshness dot leaves green at
/// exactly 5 s, on both platforms.
///
/// Older than this is not "probably still fine" — it is UNKNOWN, and lands
/// in the same branch as no fix at all.
internal const val PLOT_FIX_MAX_AGE_MS: Long = 5_000L

/// Past this, the fix is not merely stale — the phone has had no contact
/// with the sky for a minute.
///
/// This is the line the GPS chip draws between AMBER and RED, and the
/// distinction is a real one in the field: under a closed canopy a fix drops
/// for a few seconds constantly and comes straight back, which is worth
/// showing but not worth alarming about. A whole minute with nothing is a
/// different situation — the cruiser should stop trusting any position on
/// screen and step out for sky. iOS `FixFreshness.lostAge`.
internal const val PLOT_FIX_LOST_AGE_MS: Long = 60_000L

/// THE ONE FRESHNESS TEST. Every fact AND every MARK the app derives from a
/// live position goes through this function — the inside/outside verdict,
/// the banner, the you-dot on the map, the navigate guide line and its
/// distance chip, the cruiser mark on the scan screens' plot card. One
/// rule, in one place, so the words and the picture can never disagree
/// about whether the app trusts the fix. A second copy of the rule is how
/// they came to disagree in the first place.
///
/// The fix, but only if it is young enough to stand for where the cruiser
/// is NOW; null otherwise.
///
/// THE TEST IS ON THE MAGNITUDE OF THE AGE, NOT ITS SIGN. The stamp comes
/// from the platform fix — `Location.time` on Android — which for the GPS
/// provider is SATELLITE UTC, not the device clock. A phone whose clock
/// lags satellite time (no network time, a hand-set clock, a boot before
/// NTP has run) computes `nowMs - timestamp` NEGATIVE for every fix it will
/// ever receive, so a one-sided `<=` test waves through a fix of any age at
/// all: precisely the failure this gate exists to prevent, on precisely the
/// devices least able to notice it.
///
/// `abs` makes a clock offset in EITHER direction fail the test, which is
/// the safe way to be wrong: a stamp the app cannot reconcile with its own
/// clock yields "no position" — the honest answer — instead of a confident
/// claim resting on a clock nothing has vouched for. iOS applies the same
/// rule at the same threshold.
internal fun freshFixOrNull(fix: CLLocationSnapshot?, nowMs: Long): CLLocationSnapshot? =
    fix?.takeIf { abs(nowMs - it.timestamp) <= PLOT_FIX_MAX_AGE_MS }

/// How long until [freshFixOrNull] would answer DIFFERENTLY about this fix,
/// or null when it never will (no fix, or one already past believing).
///
/// Freshness changes with TIME, not with the arrival of a fix: a cruiser
/// who stands still while GPS drops gets no new snapshot, so nothing would
/// recompose and the screen would go on drawing a position the rule has
/// already rejected. Callers schedule ONE wake-up off this instead of
/// running a 1 Hz clock that would repaint the whole map every second for
/// the sake of a value that changes twice an hour.
internal fun msUntilFreshnessChanges(fix: CLLocationSnapshot?, nowMs: Long): Long? {
    val age = nowMs - (fix ?: return null).timestamp
    return when {
        // Believed now: it ages out when the age reaches the threshold.
        abs(age) <= PLOT_FIX_MAX_AGE_MS -> PLOT_FIX_MAX_AGE_MS - age
        // Stamped so far ahead of the device clock that it is refused. The
        // age climbs with the clock, so it becomes believable at -threshold.
        age < -PLOT_FIX_MAX_AGE_MS -> -PLOT_FIX_MAX_AGE_MS - age
        // Simply too old. Nothing further to wait for.
        else -> null
    }
}

// MARK: - Overlay + state -----------------------------------------------------

/// Everything the map home needs about the drawn plot: the overlay for the
/// renderer, plus the banner's own facts. `distanceM` is null when there is
/// no USABLE fix — none yet, or the last one too old — which is the ONE
/// case where inside/outside is unknown.
internal data class CruisePlotOverlay(
    val plot: Plot,
    val overlay: MapPlotOverlay,
    val radiusM: Double,
    val distanceM: Double?,
) {
    /// INSIDE is distance-from-centre ≤ radius, decided on a fresh live fix
    /// and nothing else. With no usable fix it is neither inside nor
    /// outside — null, never a guess.
    val inside: Boolean? get() = distanceM?.let { it <= radiusM }
}

/// Fixed-plot radius in metres, back out of the denormalized acre area the
/// Plot row carries.
internal fun plotRadiusMetres(plot: Plot): Double =
    sqrt(Units.acresToSquareMeters(plot.plotAreaAcres.toDouble()) / PI)

/// The ACTIVE plot, drawn on the map at true ground scale — or null when
/// there is no open plot, or the open plot has no recorded centre (the
/// (0,0) sentinel; see `Plot.hasCentre`).
///
/// `nowMs` is the caller's clock, ticking once a second. It is a parameter
/// and not a `System.currentTimeMillis()` read inside because staleness
/// changes WITH TIME, not with the arrival of a fix: a cruiser who stands
/// still while GPS drops gets no new snapshot, so only a ticking clock can
/// move the overlay from Inside to unknown. Passing it in also makes the
/// whole decision a pure function that can be reasoned about (and tested)
/// at any instant.
internal fun cruiseModePlotOverlay(
    state: CruiseModeState,
    settings: SettingsSnapshot,
    fix: CLLocationSnapshot?,
    nowMs: Long,
): CruisePlotOverlay? {
    val plot = state.activePlot(settings) ?: return null
    if (!plot.hasCentre) return null
    val radiusM = plotRadiusMetres(plot)
    if (!radiusM.isFinite() || radiusM <= 0.0) return null
    val system = state.unitSystem(settings)
    val centre = CoordinateConversions.LatLon(
        latitude = plot.centerLat, longitude = plot.centerLon)
    // THE AGE GATE. Everything downstream — the distance, the inside/outside
    // verdict, the banner, the drawing — is computed from `usable` alone, so
    // a fix too old to be believed is indistinguishable from no fix at all.
    val usable = freshFixOrNull(fix, nowMs)
    val distanceM = usable?.let {
        GeoMath.distanceM(plot.centerLat, plot.centerLon, it.latitude, it.longitude)
    }?.takeIf { it.isFinite() }
    val plotState = when {
        distanceM == null -> MapPlotFix.UNKNOWN
        distanceM > radiusM -> MapPlotFix.OUTSIDE
        else -> MapPlotFix.INSIDE
    }
    return CruisePlotOverlay(
        plot = plot,
        overlay = MapPlotOverlay(
            center = centre,
            radiusM = radiusM,
            rings = plotRangeRings(radiusM, system),
            // Only a fix fresh enough to believe goes over: with none the
            // renderer has nothing to connect to, and draws the unknown
            // state rather than the calm one.
            cruiser = usable?.let {
                CoordinateConversions.LatLon(
                    latitude = it.latitude, longitude = it.longitude)
            },
            state = plotState,
            id = plot.id.toString(),
        ),
        radiusM = radiusM,
        distanceM = distanceM,
    )
}

// MARK: - Banner --------------------------------------------------------------

/// The plot's state in one small pill, top-centre under the map chrome:
/// "Inside · Radius 7.2 m" over "3.1 m from centre". Outside turns the
/// state line the warning colour, matching the boundary on the map; with no
/// fix it says so instead of guessing.
@Composable
internal fun BoxScope.CruisePlotBanner(
    plot: CruisePlotOverlay,
    settings: SettingsSnapshot,
    state: CruiseModeState,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val system = state.unitSystem(settings)
    val radiusLabel = "Radius ${plotLengthLabel(plot.radiusM, system)}"
    val inside = plot.inside
    val stateLabel = when (inside) {
        true -> "Inside · $radiusLabel"
        false -> "Outside · $radiusLabel"
        null -> "No position · $radiusLabel"
    }
    val distanceLabel = plot.distanceM
        ?.let { "${plotLengthLabel(it, system)} from centre" }
        ?: "Distance from centre unknown"

    Column(
        Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            // Clear of the GPS chip / round-button row above it.
            .padding(top = 56.dp, start = ForestixSpace.md, end = ForestixSpace.md)
            .widthIn(max = 320.dp)
            .softDropShadow(Color.Black.copy(alpha = 0.18f), 8.dp, 2.dp, cornerRadius = 10.dp)
            .clip(ForestixRadius.card)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.card)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stateLabel,
            style = type.dataSmall.copy(fontSize = 12.sp),
            color = if (inside == false) colors.confidenceBad else colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            distanceLabel,
            style = type.caption.copy(fontSize = 11.sp),
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// MARK: - Tap menu (M2) -------------------------------------------------------

/// What a tap on the drawn plot opens: Edit (straight into the plot setup
/// that placed it) and Remove.
///
/// REMOVE IS THE DANGEROUS ONE, so it is two taps and the second one spells
/// out what survives. It clears the plot's recorded CENTRE — the thing that
/// makes it a plot on the map — and nothing else: the plot itself, its
/// radius and every tree measured in it stay exactly where they are. A
/// cruiser who mis-taps loses the circle, never the day's tally.
@Composable
internal fun PlotOverlayMenu(
    plot: Plot,
    radiusM: Double,
    treeCount: Int,
    system: UnitSystem,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    var confirmRemove by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .widthIn(max = 340.dp)
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            Text(
                "Plot ${plot.plotNumber}",
                style = type.bodyBold,
                color = colors.textPrimary,
            )
            Text(
                "Radius ${plotLengthLabel(radiusM, system)} · " +
                    "$treeCount ${if (treeCount == 1) "tree" else "trees"} measured",
                style = type.caption,
                color = colors.textSecondary,
            )
            ForestixProminentButton(
                label = "Edit plot",
                modifier = Modifier.fillMaxWidth(),
                onClick = onEdit,
            )
            // Destructive row in the app's own outline-danger treatment —
            // the same shape the plot and tree peeks use for their deletes.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .pressableNoRipple(onClick = { confirmRemove = true })
                    .clip(ForestixRadius.control)
                    .border(1.dp, colors.confidenceBad.copy(alpha = 0.5f), ForestixRadius.control),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Remove plot",
                    style = type.bodyBold.copy(fontSize = 14.sp),
                    color = colors.confidenceBad,
                )
            }
            ForestixBorderedButton(
                label = "Cancel",
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove Plot ${plot.plotNumber} from the map?") },
            text = {
                Text(
                    "This clears the plot's centre, so the plot stops being drawn " +
                        "on the map. The $treeCount " +
                        (if (treeCount == 1) "tree" else "trees") +
                        " measured in it " + (if (treeCount == 1) "is" else "are") +
                        " kept — nothing you tallied is deleted, and you can set " +
                        "the centre again from Edit plot.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            },
        )
    }
}
