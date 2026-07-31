// Compose slippy-map view — Android stand-in for the iOS basemap map
// content (spec §3.1 REQ-PRJ-004: polygon + marker overlays, originally
// ported from the retired PlotMapScreen's MapKit `Map`) plus the offline
// tile overlay MapKit gets from TileCache+MapKit.
//
// A plain Canvas renderer: Web-Mercator tiles from two TileFetcher layers
// — the built-in Esri World Imagery satellite base (or a caller-supplied
// base template) with the user's AppSettings.tileURLTemplate drawn on top
// as an optional overlay (contour/forest tiles, often transparent PNG) —
// under stratum polygon + plot marker overlays, with drag-to-pan and
// pinch-to-zoom. The Esri credit bottom-left is required by the imagery
// terms and stays on whenever the built-in base is in use.
//
// Map-home extensions (design/forestix-redesign-v2-maphome.html): teardrop
// PIN markers with the title inside and D/H/C badge chips beneath, marker
// tap hit-testing, a pulsing "you" dot, and an observable MapCameraState
// whose visibleBounds() feeds the offline tile downloader.
//
// The SAMPLING PLOT overlay (MapPlotOverlay) draws the cruiser's plot at
// true ground scale — boundary, labelled range rings, true-bearing compass
// badges, centre cross, and the outside-the-plot warning state — between
// the app's other overlays and its pins.

package com.hcjeong.forestix.basemap

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixColors
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

// MARK: - Overlay models (mirror MapPolygon / Marker content on iOS)

/// The IMPORTED SURVEY BOUNDARY (Map settings → Survey boundary). Drawn
/// between the basemap layers and the app's own content, in WGS84 lon/lat.
/// `rings[0]` is the outer ring and the rest are holes for a polygon; a
/// polyline carries one open path and a point a single coordinate. It is
/// display-only — hit-testing never considers it, so it can't steal taps
/// meant for pins.
data class MapBoundaryOverlay(
    val rings: List<List<CoordinateConversions.LatLon>>,
    val closed: Boolean,
)

/// A CRUISE AREA the cruiser drew on this map — the outline a cruise is
/// laid out inside.
///
/// Separate from `MapBoundaryOverlay` because the two are different objects
/// to the cruiser and behave differently on screen: the imported boundary
/// is a file's geometry and is hit-test transparent, while an area is the
/// cruiser's own editable plan and TAKES TAPS — selecting it is how its
/// menu is reached. They also sit in different layers, areas above the
/// boundary, so an area drawn inside an imported stand reads as inside it.
///
/// `rings[0]` is the outer ring; the rest are holes. The ring may be open
/// or closed — the renderer closes it either way, so a stored polygon and a
/// half-drawn draft can be handed over unchanged. iOS `BasemapArea`.
data class MapAreaOverlay(
    /// Echoed back through MapView's onOverlayTap when this area is tapped.
    val id: String,
    val rings: List<List<CoordinateConversions.LatLon>>,
    /// Drawn heavier, with its corners marked — the cruiser has to be able
    /// to tell which of several outlines their next action applies to.
    val selected: Boolean = false,
)

/// An open polyline (v3 cruise mock `.guide`) — the dashed you-dot → plot
/// navigation guide. Screen-projected like the polygon rings; `dashed`
/// mirrors the mock's dotted 2/9 round-cap pattern (iOS BasemapGuideLine).
data class MapPolylineOverlay(
    val points: List<CoordinateConversions.LatLon>,
    val color: Color = Color(0xFF34C759),
    val dashed: Boolean = true,
)

// MARK: - Sampling plot overlay (map home)

/// One concentric RANGE RING inside the plot boundary: how far out it sits,
/// and the label to draw on it. The label arrives ready-made because the
/// renderer never converts units — the host owns "2 m" vs "20 ft".
data class MapPlotRing(val radiusM: Double, val label: String)

/// Where the cruiser stands relative to the plot boundary — and, crucially,
/// the third answer: we do not know.
///
/// UNKNOWN is not a shade of INSIDE. It is the state whenever there is no
/// usable live fix (none yet, or the last one is too old to stand for where
/// the cruiser is now), and it gets its own drawing: neutral grey instead
/// of a signal colour, a dashed boundary instead of a solid one, and the
/// words on the map. Reading a calm accent-tinted circle as "I'm in the
/// plot" when the app has no idea is exactly how trees end up tallied into
/// the wrong plot.
enum class MapPlotFix { INSIDE, OUTSIDE, UNKNOWN }

/// The cruiser's SAMPLING PLOT drawn at TRUE GEOGRAPHIC SCALE, so the
/// circle on screen is the circle on the ground: a translucent boundary
/// disc the imagery reads through, concentric labelled range rings, N/E/S/W
/// badges placed by TRUE BEARING (real offsets from the centre, projected
/// like every other coordinate, so they stay right whatever the projection
/// does), a centre cross, and — while the live fix is OUTSIDE — an
/// emphasised boundary plus a dotted connector from the centre to that fix
/// so the way back needs no thinking.
///
/// `state` UNKNOWN means NO USABLE LIVE FIX, and the drawing SAYS SO: the
/// whole overlay goes neutral grey, the boundary turns dashed and a
/// "No position" chip sits under the centre — the banner's own words — so
/// the map alone can never be mistaken for "you are inside". `cruiser` is
/// null in that state — there is no position to draw to, and the host is
/// responsible for handing over only a position it believes (one shared
/// freshness test decides that for the verdict and every mark alike).
/// Colours come from the app's own tokens inside the renderer,
/// so the plot matches the rest of the map furniture in both appearances.
data class MapPlotOverlay(
    val center: CoordinateConversions.LatLon,
    val radiusM: Double,
    val rings: List<MapPlotRing> = emptyList(),
    val cruiser: CoordinateConversions.LatLon? = null,
    val state: MapPlotFix = MapPlotFix.UNKNOWN,
    /// Echoed back through MapView's onPlotTap when the boundary is tapped.
    val id: String = "plot",
)

/// How a MapMarker renders. DOT is the original plot-map style (small
/// circle, halo label above); PIN is the map-home teardrop from the mock —
/// 30 dp drop with the title inside and badge chips ("D"/"H"/"C") beneath;
/// RING is the cruise-mode plot marker (v3 mock `.plotpin .ringdot`) — a
/// ~30 dp hollow circle CENTRE-anchored on the coordinate, title inside in
/// the tint, `dashed` stroke for planned-plot styling.
enum class MapMarkerShape { DOT, PIN, RING }

/// A labelled point, the analogue of `Marker("#5", coordinate:).tint(...)`.
/// `id` is echoed back through MapView's onMarkerTap when the user taps
/// within ~24 dp of the marker's screen point.
data class MapMarker(
    val coordinate: CoordinateConversions.LatLon,
    val title: String? = null,
    val tint: Color = Color(0xFF007AFF),
    val id: String = "",
    val shape: MapMarkerShape = MapMarkerShape.DOT,
    /// Small mono chips drawn under a PIN ("D"/"H"/"C" in the mock).
    val badges: List<String> = emptyList(),
    /// PIN/RING: soft tint halo around the body (mock `.pin.sel`/`.plotpin.sel`).
    val selected: Boolean = false,
    /// RING only: dashed stroke — the v3 planned-plot style
    /// (mock `.plotpin.planned`).
    val dashed: Boolean = false,
)

// MARK: - Camera state (map home / offline downloader)

/// Zoom the camera is clamped to, everywhere it can be changed — gesture,
/// double-tap, and a host's `moveTo`. Past the tile source's native maximum
/// the z-19 tiles simply draw scaled (overzoom), which is what lets a plot
/// ring a few tens of metres across be framed at all. iOS
/// `BasemapMapView.zoomRange` is the same 3…24.
const val MAP_ZOOM_MIN: Double = 3.0
const val MAP_ZOOM_MAX: Double = 24.0

/// Live camera holder a host can pass to MapView to observe the viewport.
/// MapView writes `center`/`zoom` after every gesture and the viewport size
/// on layout; the map home's offline sheet calls `visibleBounds()` when the
/// user taps "Download visible area".
@Stable
class MapCameraState {
    var center: CoordinateConversions.LatLon? by mutableStateOf(null)
        internal set
    var zoom: Double by mutableDoubleStateOf(14.0)
        internal set
    internal var viewportSizePx: IntSize by mutableStateOf(IntSize.Zero)
    internal var densityScale: Float = 1f

    /// Host-requested camera move — MapView applies whatever is here whenever
    /// [moveTick] changes.
    internal var pendingMove: Pair<CoordinateConversions.LatLon, Double>? by mutableStateOf(null)

    /// Bumped by every requested move. MapView keys its consumption on this
    /// COUNTER rather than on the value, so the same target can be asked for
    /// twice (my-location tapped again after a pan back) and so a per-frame
    /// glide costs one state write per frame instead of a write-then-clear
    /// round trip through the consumer.
    internal var moveTick: Int by mutableIntStateOf(0)

    /// Recentre on `target` at `zoom` — the same snap the map does when the
    /// host's `center` parameter changes, but usable when that parameter
    /// hasn't changed (my-location button after the user panned away).
    fun moveTo(target: CoordinateConversions.LatLon, zoom: Double) {
        pendingMove = target to zoom
        moveTick++
    }

    /// GLIDE to `target` at `zoom` instead of cutting there.
    ///
    /// [moveTo] jumps, which is right for my-location (the cruiser knows
    /// where they are) and wrong for "here is the plot you are walking to":
    /// a cut answers "where is it?" with a different picture rather than
    /// with a direction. Stepped on the frame clock, ease-out over ~0.6 s —
    /// quick off the mark, settling onto the target.
    ///
    /// Suspends, so cancelling is the caller's job and costs nothing: the
    /// coroutine a second tap launches replaces the first.
    suspend fun flyTo(target: CoordinateConversions.LatLon, zoom: Double) {
        val from = center
        val fromZoom = this.zoom
        val toZoom = zoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
        // No camera to fly FROM (the map has not laid out): nothing to
        // interpolate, so land on the target rather than animate from a
        // position we made up.
        if (from == null) {
            moveTo(target, toZoom)
            return
        }
        val steps = 36
        for (step in 1..steps) {
            withFrameMillis { }
            val eased = 1.0 - (1.0 - step.toDouble() / steps).pow(3)
            pendingMove = CoordinateConversions.LatLon(
                latitude = from.latitude + (target.latitude - from.latitude) * eased,
                longitude = from.longitude + (target.longitude - from.longitude) * eased,
            ) to (fromZoom + (toZoom - fromZoom) * eased)
            moveTick++
        }
    }

    /// Pure projection of a coordinate into viewport pixels for the
    /// current camera — the same maths the draw pass uses, callable by a
    /// host to float its own overlays over the map (the cruise mode's
    /// live distance chip rides the guide-line midpoint). Null until the
    /// map has laid out. iOS BasemapMapView exposes the same accessor.
    fun screenPoint(p: CoordinateConversions.LatLon): Offset? {
        val c = center ?: return null
        val size = viewportSizePx
        if (size.width <= 0 || size.height <= 0) return null
        val worldPx = 256.0 * densityScale * 2.0.pow(zoom)
        val originX = lonToXNorm(c.longitude) * worldPx - size.width / 2.0
        val originY = latToYNorm(c.latitude) * worldPx - size.height / 2.0
        return Offset(
            (lonToXNorm(p.longitude) * worldPx - originX).toFloat(),
            (latToYNorm(p.latitude) * worldPx - originY).toFloat(),
        )
    }

    /// The exact inverse of `screenPoint` — where on the ground a point on
    /// screen is. Null until the map has laid out and produced a camera.
    ///
    /// Needed by any host that lets the cruiser MOVE something with a
    /// finger rather than just look at it (the boundary editor's corner and
    /// edge handles). Derived from the same normalised-world maths as the
    /// forward projection, so a coordinate round-trips through both
    /// unchanged and a handle dragged one pixel moves one pixel's worth of
    /// ground. Clamped to one world copy: a drag past the antimeridian or
    /// into the Mercator pole cap has no sensible boundary meaning, and
    /// wrapping silently would fling a corner to the far side of the planet.
    /// iOS BasemapMapView.coordinate(at:camera:viewportSize:) is the same.
    fun coordinateAt(point: Offset): CoordinateConversions.LatLon? {
        val c = center ?: return null
        val size = viewportSizePx
        if (size.width <= 0 || size.height <= 0) return null
        val worldPx = 256.0 * densityScale * 2.0.pow(zoom)
        val xNorm = lonToXNorm(c.longitude) + (point.x - size.width / 2.0) / worldPx
        val yNorm = latToYNorm(c.latitude) + (point.y - size.height / 2.0) / worldPx
        return CoordinateConversions.LatLon(
            latitude = yNormToLat(yNorm.coerceIn(0.0, 1.0)),
            longitude = xNormToLon(xNorm.coerceIn(0.0, 1.0)),
        )
    }

    /// Lat/lon corners of what's on screen as an outer ring (NW, NE, SE,
    /// SW) — the shape OfflineBasemap.planJob consumes. Null until the map
    /// has laid out and produced a camera.
    fun visibleBounds(): List<CoordinateConversions.LatLon>? {
        val c = center ?: return null
        val size = viewportSizePx
        if (size.width <= 0 || size.height <= 0) return null
        val worldPx = 256.0 * densityScale * 2.0.pow(zoom)
        val halfW = size.width / 2.0 / worldPx
        val halfH = size.height / 2.0 / worldPx
        // Clamp to one world copy — good enough for a field-area download.
        val minX = (lonToXNorm(c.longitude) - halfW).coerceIn(0.0, 1.0)
        val maxX = (lonToXNorm(c.longitude) + halfW).coerceIn(0.0, 1.0)
        val minY = (latToYNorm(c.latitude) - halfH).coerceIn(0.0, 1.0)
        val maxY = (latToYNorm(c.latitude) + halfH).coerceIn(0.0, 1.0)
        return listOf(
            CoordinateConversions.LatLon(latitude = yNormToLat(minY), longitude = xNormToLon(minX)),
            CoordinateConversions.LatLon(latitude = yNormToLat(minY), longitude = xNormToLon(maxX)),
            CoordinateConversions.LatLon(latitude = yNormToLat(maxY), longitude = xNormToLon(maxX)),
            CoordinateConversions.LatLon(latitude = yNormToLat(maxY), longitude = xNormToLon(minX)),
        )
    }
}

@Composable
fun rememberMapCameraState(): MapCameraState = remember { MapCameraState() }

// MARK: - MapView

@Composable
fun MapView(
    center: CoordinateConversions.LatLon,
    modifier: Modifier = Modifier,
    initialZoom: Double = 14.0,
    /// BASE layer override. Null/blank → the built-in Esri World Imagery
    /// satellite base (zero-setup imagery whenever online). Callers that
    /// pass the user template here keep their old single-layer behaviour.
    tileURLTemplate: String? = null,
    /// Which BUILT-IN base to draw when `tileURLTemplate` is null:
    /// AppSettings.mapType — "satellite" (Esri, the default) or "normal"
    /// (OpenStreetMap standard). Also picks the attribution line.
    mapType: String = "satellite",
    /// Optional OVERLAY drawn on top of the base — the map home passes
    /// AppSettings.tileURLTemplate here (often transparent PNG tiles).
    overlayURLTemplate: String? = null,
    /// Imported survey boundary — over the tile layers, UNDER everything
    /// the app itself draws (the plot, pins, guide, you-dot).
    boundary: List<MapBoundaryOverlay> = emptyList(),
    /// Cruise areas — above the imported boundary, below the sampling plot,
    /// and unlike the boundary they take taps (see `onOverlayTap`).
    areas: List<MapAreaOverlay> = emptyList(),
    polylines: List<MapPolylineOverlay> = emptyList(),
    /// The cruiser's sampling plot at true ground scale — over the survey
    /// boundary and the stratum/guide overlays, UNDER the you-dot and the
    /// pins, so a pin is never buried in the plot's fill.
    plot: MapPlotOverlay? = null,
    markers: List<MapMarker> = emptyList(),
    attribution: String? = null,
    /// Tap within ~24 dp of a marker's screen point → its `id`.
    onMarkerTap: ((String) -> Unit)? = null,
    /// A tap that missed every marker and landed on the PLOT's boundary
    /// ring (within ~24 dp — the ring is the target, not the whole disc:
    /// the plot's own pin owns the centre, and a disc-wide target would
    /// swallow every tap meant to dismiss a peek card), inside an AREA, or
    /// on both at once. Carries `(plotId, areaId)`.
    ///
    /// BOTH hits are reported, never just the topmost: an area and the
    /// plots laid inside it overlap by construction, and the map cannot
    /// know which one the cruiser meant on this particular tap — the host
    /// can, because it knows what is selected right now. A tap that hit
    /// neither goes to `onMapTap` instead. iOS `onOverlayTap`.
    onOverlayTap: ((String?, String?) -> Unit)? = null,
    /// Tap that hit no marker — the map home uses it to dismiss the peek card.
    onMapTap: (() -> Unit)? = null,
    /// Press-and-hold anywhere on the map, carrying the coordinate under the
    /// finger. The host raises its own menu; the map has no opinion about
    /// what a long press means. iOS BasemapMapView takes the same callback.
    onMapLongPress: ((CoordinateConversions.LatLon) -> Unit)? = null,
    /// Pulsing blue "you are here" dot (mock `.youdot`).
    youLocation: CoordinateConversions.LatLon? = null,
    /// Observable camera for hosts that need visibleBounds() on demand.
    cameraState: MapCameraState? = null,
    onCameraChange: ((CoordinateConversions.LatLon, Double) -> Unit)? = null,
) {
    val context = LocalContext.current
    val colors = Forestix.colors
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var camCenter by remember(center) { mutableStateOf(center) }
    var camZoom by remember { mutableDoubleStateOf(initialZoom) }

    // Mirror the camera into the host-observable state + change callback
    // (fires once on first composition, then after every gesture).
    LaunchedEffect(camCenter, camZoom) {
        cameraState?.let { it.center = camCenter; it.zoom = camZoom }
        onCameraChange?.invoke(camCenter, camZoom)
    }

    // Host-requested move (MapCameraState.moveTo / flyTo): same recentre as
    // a `center` change, plus a zoom. Keyed on the COUNTER so the same target
    // can be requested twice and so a glide's per-frame writes all land.
    LaunchedEffect(cameraState?.moveTick) {
        val move = cameraState?.pendingMove ?: return@LaunchedEffect
        camCenter = move.first
        camZoom = move.second.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
    }

    // Pulse phase for the "you" dot; only read during draw when a location
    // is supplied, so the animation doesn't invalidate plot-map callers.
    val youPulse: Float = if (youLocation != null) {
        rememberInfiniteTransition(label = "youDot").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "youDotPulse",
        ).value
    } else 0f

    // Base layer: caller override, else the built-in provider the user's
    // map type selects (satellite = Esri, normal = OpenStreetMap).
    val baseIsBuiltin = tileURLTemplate.isNullOrBlank()
    val baseFetcher = remember(tileURLTemplate, mapType) {
        tileURLTemplate?.takeIf { it.isNotBlank() }?.let { TileFetcher(context, it) }
            ?: TileFetcher.builtInBase(context, mapType)
    }
    val overlayFetcher = remember(overlayURLTemplate) {
        overlayURLTemplate?.takeIf { it.isNotBlank() }?.let { TileFetcher(context, it) }
    }
    // Recompose (and therefore redraw) whenever a new tile bitmap lands.
    val baseTick = baseFetcher.tilesVersion.collectAsStateWithLifecycle().value
    val overlayTick =
        if (overlayFetcher != null) overlayFetcher.tilesVersion.collectAsStateWithLifecycle().value else 0

    val labelFill = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    val labelHalo = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
        }
    }
    // PIN paints — mono like the mock's --font-mono pin glyphs. The label
    // is heavy/800 (iOS `.heavy`, mock 800); pre-P devices fall back to bold.
    val pinLabel = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                Typeface.create(Typeface.MONOSPACE, 800, false)
            } else {
                Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
        }
    }
    // iOS `.shadow(color: .black.opacity(0.3), radius: 4, y: 2)` on the
    // teardrop body — a blurred silhouette drawn under the drop.
    val pinShadow = remember(density) {
        Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(77, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(
                4f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
    }
    val badgeText = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }
    val badgeFill = remember { Paint().apply { isAntiAlias = true } }
    val badgeStroke = remember {
        Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { s ->
                    cameraState?.let { it.viewportSizePx = s; it.densityScale = density }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        if (gestureZoom != 1f && gestureZoom > 0f) {
                            // 24, not the native tile max (19): past 19 the
                            // renderer overzooms so dense stands separate.
                            camZoom = (camZoom + log2(gestureZoom.toDouble())).coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
                        }
                        if (pan != Offset.Zero) {
                            val worldPx = 256.0 * density * 2.0.pow(camZoom)
                            val cx = lonToXNorm(camCenter.longitude) * worldPx - pan.x
                            val cy = latToYNorm(camCenter.latitude) * worldPx - pan.y
                            val xNorm = (cx / worldPx).mod(1.0)
                            val yNorm = (cy / worldPx).coerceIn(0.0, 1.0)
                            camCenter = CoordinateConversions.LatLon(
                                latitude = yNormToLat(yNorm),
                                longitude = xNormToLon(xNorm),
                            )
                        }
                    }
                }
                .pointerInput(markers, plot, areas, onMarkerTap, onOverlayTap, onMapTap, onMapLongPress) {
                    detectTapGestures(
                        // PRESS AND HOLD → the ground under the finger.
                        // Compose's own long-press timing, so it agrees with
                        // every other hold in the app; the projection is the
                        // exact inverse of the draw pass below, evaluated
                        // with the camera as of the press, so the pin the
                        // host drops lands where the finger was.
                        onLongPress = onLongPress@{ press ->
                            val emit = onMapLongPress ?: return@onLongPress
                            val worldPx = 256.0 * density * 2.0.pow(camZoom)
                            val originX = lonToXNorm(camCenter.longitude) * worldPx - size.width / 2.0
                            val originY = latToYNorm(camCenter.latitude) * worldPx - size.height / 2.0
                            val xNorm = ((press.x + originX) / worldPx).mod(1.0)
                            val yNorm = ((press.y + originY) / worldPx).coerceIn(0.0, 1.0)
                            emit(
                                CoordinateConversions.LatLon(
                                    latitude = yNormToLat(yNorm),
                                    longitude = xNormToLon(xNorm),
                                ),
                            )
                        },
                        // iOS BasemapMapView doubleTapZoom: one level in,
                        // keeping the tapped point stationary.
                        onDoubleTap = { tap ->
                            val oldZoom = camZoom
                            val newZoom = (oldZoom + 1.0).coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
                            if (newZoom != oldZoom) {
                                val worldPx = 256.0 * density * 2.0.pow(oldZoom)
                                val scale = 2.0.pow(newZoom - oldZoom)
                                val cx = lonToXNorm(camCenter.longitude)
                                val cy = latToYNorm(camCenter.latitude)
                                val tx = cx + (tap.x - size.width / 2.0) / worldPx
                                val ty = cy + (tap.y - size.height / 2.0) / worldPx
                                val nx = tx + (cx - tx) / scale
                                val ny = ty + (cy - ty) / scale
                                camZoom = newZoom
                                camCenter = CoordinateConversions.LatLon(
                                    latitude = yNormToLat(ny.coerceIn(0.0, 1.0)),
                                    longitude = xNormToLon(nx.coerceIn(0.0, 1.0)),
                                )
                            }
                        },
                        onTap = { tap ->
                            if (onMarkerTap == null && onMapTap == null &&
                                onOverlayTap == null
                            ) {
                                return@detectTapGestures
                            }
                            // Same projection as the draw pass, evaluated with
                            // the camera as of the tap.
                            val worldPx = 256.0 * density * 2.0.pow(camZoom)
                            val originX = lonToXNorm(camCenter.longitude) * worldPx - size.width / 2.0
                            val originY = latToYNorm(camCenter.latitude) * worldPx - size.height / 2.0
                            val hitRadius = 24.dp.toPx()
                            var bestId: String? = null
                            var bestDist = hitRadius
                            for (m in markers) {
                                val px = (lonToXNorm(m.coordinate.longitude) * worldPx - originX).toFloat()
                                val py = (latToYNorm(m.coordinate.latitude) * worldPx - originY).toFloat()
                                var d = hypot(tap.x - px, tap.y - py)
                                if (m.shape == MapMarkerShape.PIN) {
                                    // The teardrop body floats above the
                                    // bottom-anchored block's badge row.
                                    val bodyDy = (if (m.badges.isEmpty()) 15.dp else 30.dp).toPx()
                                    d = minOf(d, hypot(tap.x - px, tap.y - (py - bodyDy)))
                                }
                                if (d <= bestDist) { bestDist = d; bestId = m.id }
                            }
                            val hit = bestId
                            // No pin under the finger? The PLOT's boundary
                            // ring is the next target — a band around the
                            // circle, so the tap that opens the plot's menu
                            // is the one that lands ON the drawn plot. Only
                            // once the circle is bigger on screen than the
                            // hit band itself; below that it is a blob under
                            // its own pin and every tap would be a plot tap.
                            val plotHit = if (hit == null && plot != null && onOverlayTap != null) {
                                val pc = Offset(
                                    (lonToXNorm(plot.center.longitude) * worldPx - originX).toFloat(),
                                    (latToYNorm(plot.center.latitude) * worldPx - originY).toFloat(),
                                )
                                val rPx = plot.radiusM.toFloat() *
                                    pxPerMetreAt(plot.center.latitude, worldPx)
                                val onRing = rPx.isFinite() && rPx > hitRadius &&
                                    abs(hypot(tap.x - pc.x, tap.y - pc.y) - rPx) <= hitRadius
                                if (onRing) plot.id else null
                            } else {
                                null
                            }
                            // Which AREA a tap landed in — the SMALLEST one
                            // by projected extent, so an area nested inside
                            // a larger one is still reachable. Whole
                            // interior rather than an outline band: an area
                            // is selected by tapping the ground it covers,
                            // which is how a cruiser points at it, and a
                            // band would be unfindable on a stand the size
                            // of the screen.
                            val areaHit = if (hit == null && onOverlayTap != null) {
                                areaHitTest(areas, tap) { p ->
                                    Offset(
                                        (lonToXNorm(p.longitude) * worldPx - originX).toFloat(),
                                        (latToYNorm(p.latitude) * worldPx - originY).toFloat(),
                                    )
                                }
                            } else {
                                null
                            }
                            when {
                                hit != null && onMarkerTap != null -> onMarkerTap(hit)
                                plotHit != null || areaHit != null ->
                                    onOverlayTap?.invoke(plotHit, areaHit)
                                else -> onMapTap?.invoke()
                            }
                        },
                    )
                },
        ) {
            // Ticks are read so freshly-downloaded tiles invalidate us.
            @Suppress("UNUSED_EXPRESSION") baseTick
            @Suppress("UNUSED_EXPRESSION") overlayTick

            drawRect(color = colors.canvas)

            // Nearest tile level (iOS `zoom.rounded()`) so fractional zooms
            // never upscale a whole level blurrier. Capped at the imagery's
            // native max (19, iOS `maxTileZoom`) — the camera zooms to 24
            // and the z-19 tiles simply draw scaled 2^(zoom−19) (overzoom).
            val tileZoom = camZoom.roundToInt().coerceIn(0, 19)
            val n = 1 shl tileZoom
            val tilePx = 256.0 * density * 2.0.pow(camZoom - tileZoom)
            val worldPx = tilePx * n
            val originX = lonToXNorm(camCenter.longitude) * worldPx - size.width / 2.0
            val originY = latToYNorm(camCenter.latitude) * worldPx - size.height / 2.0

            // MARK: Tiles — base layer first, then the optional overlay.
            val tx0 = floor(originX / tilePx).toInt()
            val tx1 = floor((originX + size.width) / tilePx).toInt()
            val ty0 = floor(originY / tilePx).toInt().coerceAtLeast(0)
            val ty1 = floor((originY + size.height) / tilePx).toInt().coerceAtMost(n - 1)

            // Faint tile-boundary grid under the tiles (iOS gridPath) —
            // shows through only where a base tile hasn't arrived, and
            // gives pan feedback over the bare canvas.
            val gridColor = colors.divider.copy(alpha = 0.55f)
            val gridStroke = 0.5.dp.toPx()
            for (gx in tx0..(tx1 + 1)) {
                val sx = (gx * tilePx - originX).toFloat()
                drawLine(gridColor, Offset(sx, 0f), Offset(sx, size.height), gridStroke)
            }
            val gy0 = floor(originY / tilePx).toInt()
            val gy1 = floor((originY + size.height) / tilePx).toInt()
            for (gy in gy0..(gy1 + 1)) {
                val sy = (gy * tilePx - originY).toFloat()
                drawLine(gridColor, Offset(0f, sy), Offset(size.width, sy), gridStroke)
            }
            for (layer in listOfNotNull(baseFetcher, overlayFetcher)) {
                for (ty in ty0..ty1) {
                    for (tx in tx0..tx1) {
                        val wrappedX = ((tx % n) + n) % n
                        val key = TileCache.Key(z = tileZoom, x = wrappedX, y = ty)
                        val bitmap = layer.bitmapFor(key, scope) ?: continue
                        val dstX = (tx * tilePx - originX).roundToInt()
                        val dstY = (ty * tilePx - originY).roundToInt()
                        // +1 px overlap hides seams from fractional scaling.
                        val dstSize = ceil(tilePx).toInt() + 1
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            dstOffset = androidx.compose.ui.unit.IntOffset(dstX, dstY),
                            dstSize = androidx.compose.ui.unit.IntSize(dstSize, dstSize),
                        )
                    }
                }
            }

            fun screenPoint(p: CoordinateConversions.LatLon): Offset = Offset(
                (lonToXNorm(p.longitude) * worldPx - originX).toFloat(),
                (latToYNorm(p.latitude) * worldPx - originY).toFloat(),
            )

            // MARK: Imported survey boundary — ABOVE both tile layers,
            // BELOW every piece of app content. A dark casing under the
            // amber stroke keeps it legible on bright satellite imagery and
            // on the pale OSM street base alike; polygons get a light fill
            // with holes punched out (even-odd).
            if (boundary.isNotEmpty()) {
                val boundaryTint = Color(0xFFFFB454)
                val casing = Color.Black.copy(alpha = 0.45f)
                for (feature in boundary) {
                    val paths = feature.rings.filter { it.size >= 2 }
                    if (feature.closed && paths.isNotEmpty()) {
                        val path = Path()
                        path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        for (ring in paths) {
                            ring.forEachIndexed { i, p ->
                                val pt = screenPoint(p)
                                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                            }
                            path.close()
                        }
                        drawPath(path, color = boundaryTint.copy(alpha = 0.16f))
                        drawPath(path, color = casing, style = Stroke(width = 5.dp.toPx()))
                        drawPath(path, color = boundaryTint, style = Stroke(width = 2.5.dp.toPx()))
                    } else {
                        for (ring in paths) {
                            val path = Path()
                            ring.forEachIndexed { i, p ->
                                val pt = screenPoint(p)
                                if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                            }
                            drawPath(path, color = casing, style = Stroke(width = 5.dp.toPx()))
                            drawPath(
                                path,
                                color = boundaryTint,
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                    }
                    // Single-coordinate features (imported survey points).
                    for (ring in feature.rings.filter { it.size == 1 }) {
                        val pt = screenPoint(ring[0])
                        drawCircle(color = casing, radius = 5.dp.toPx(), center = pt)
                        drawCircle(color = boundaryTint, radius = 3.5.dp.toPx(), center = pt)
                    }
                }
            }

            // MARK: Cruise areas — between the imported boundary and the
            // sampling plot, so an area drawn inside an imported stand
            // reads as inside it and a plot laid inside an area reads as
            // inside that. Selected areas draw LAST so a smaller area
            // sitting inside a bigger one is never buried by the one the
            // cruiser did not pick.
            if (areas.isNotEmpty()) {
                val areaTint = colors.cruiseAccent
                val casing = Color.Black.copy(alpha = 0.45f)
                for (area in areas.sortedBy { it.selected }) {
                    val rings = area.rings.filter { it.size >= 3 }
                    if (rings.isEmpty()) continue
                    val path = Path()
                    path.fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    for (ring in rings) {
                        ring.forEachIndexed { i, p ->
                            val pt = screenPoint(p)
                            if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                        }
                        path.close()
                    }
                    val width = if (area.selected) 4.dp.toPx() else 2.5.dp.toPx()
                    drawPath(
                        path,
                        color = areaTint.copy(alpha = if (area.selected) 0.22f else 0.14f),
                    )
                    drawPath(path, color = casing, style = Stroke(width = width + 2.5.dp.toPx()))
                    drawPath(path, color = areaTint, style = Stroke(width = width))
                    if (!area.selected) continue
                    for (corner in rings[0]) {
                        val pt = screenPoint(corner)
                        drawCircle(color = areaTint, radius = 3.5.dp.toPx(), center = pt)
                        drawCircle(
                            color = colors.surface,
                            radius = 3.5.dp.toPx(),
                            center = pt,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                    }
                }
            }

            // MARK: Polylines (navigation guide) — over the boundary, under pins
            for (line in polylines) {
                if (line.points.size < 2) continue
                val path = Path()
                line.points.forEachIndexed { i, p ->
                    val pt = screenPoint(p)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(
                    path,
                    color = line.color,
                    style = Stroke(
                        width = 2.5.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        pathEffect = if (line.dashed) {
                            // Mock `.guide` / iOS guide: dotted 2 9, round caps.
                            PathEffect.dashPathEffect(
                                floatArrayOf(2.dp.toPx(), 9.dp.toPx()))
                        } else {
                            null
                        },
                    ),
                )
            }

            // MARK: Sampling plot — over the survey boundary, the stratum
            // polygons and the guide line, UNDER the you-dot and the pins.
            if (plot != null) {
                drawPlotOverlay(
                    plot = plot,
                    centre = screenPoint(plot.center),
                    pxPerMetre = pxPerMetreAt(plot.center.latitude, worldPx),
                    cruiser = plot.cruiser?.let { screenPoint(it) },
                    pointAt = { metres, bearingDeg ->
                        screenPoint(offsetLatLon(plot.center, metres, bearingDeg))
                    },
                    colors = colors,
                    text = badgeText,
                    fill = badgeFill,
                    stroke = badgeStroke,
                )
            }

            // MARK: You-dot (map home) — pulsing blue fix under the pins
            if (youLocation != null) {
                val pt = screenPoint(youLocation)
                val blue = Color(0xFF3B82C4)
                drawCircle(
                    color = blue.copy(alpha = 0.30f * (1f - youPulse)),
                    radius = 8.dp.toPx() + 10.dp.toPx() * youPulse,
                    center = pt,
                )
                drawCircle(color = blue.copy(alpha = 0.18f), radius = 13.dp.toPx(), center = pt)
                drawCircle(color = blue, radius = 8.dp.toPx(), center = pt)
                drawCircle(
                    color = colors.surface,
                    radius = 8.dp.toPx(),
                    center = pt,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }

            // MARK: Markers (planned-plot dots + map-home teardrop pins)
            val markerRadius = 6.dp.toPx()
            // Selected pin draws last so it can never hide under a
            // neighbour (iOS zIndex 2 on the selected marker).
            val orderedMarkers =
                if (markers.any { it.selected }) markers.sortedBy { it.selected } else markers
            for (marker in orderedMarkers) {
                val pt = screenPoint(marker.coordinate)
                when (marker.shape) {
                    MapMarkerShape.DOT -> {
                        drawCircle(color = marker.tint, radius = markerRadius, center = pt)
                        drawCircle(
                            color = Color.White,
                            radius = markerRadius,
                            center = pt,
                            style = Stroke(width = 1.5.dp.toPx()),
                        )
                        val title = marker.title ?: continue
                        drawIntoCanvas { canvas ->
                            val textSize = 11f * density
                            labelHalo.textSize = textSize
                            labelHalo.strokeWidth = 3f * density / 2f
                            labelHalo.color = android.graphics.Color.WHITE
                            labelFill.textSize = textSize
                            labelFill.color = marker.tint.toArgb()
                            val ty = pt.y - markerRadius - 4f * density
                            canvas.nativeCanvas.drawText(title, pt.x, ty, labelHalo)
                            canvas.nativeCanvas.drawText(title, pt.x, ty, labelFill)
                        }
                    }

                    MapMarkerShape.PIN -> {
                        // Mock `.pin .dot`: a 30 dp round rect with one sharp
                        // corner (radius 4), rotated -45° so the sharp corner
                        // becomes the downward tip. The whole block is
                        // BOTTOM-anchored on the coordinate like iOS / the
                        // mock's translate(-50%, -100%): the badge row's
                        // bottom sits ON the point, the 30 dp pin frame
                        // stacks 3 dp above it, and the rotated drop
                        // overflows that frame just like the SwiftUI layout.
                        val side = 30.dp.toPx()
                        badgeText.textSize = 8.5f * density
                        val badgeFm = badgeText.fontMetrics
                        // Content-driven chip height: text + 2 × 1 dp pad.
                        val chipH = (badgeFm.descent - badgeFm.ascent) + 2f * density
                        val hasBadges = marker.badges.isNotEmpty()
                        val pinFrameBottom =
                            if (hasBadges) pt.y - chipH - 3.dp.toPx() else pt.y
                        val centre = Offset(pt.x, pinFrameBottom - side / 2f)
                        val body = Path().apply {
                            addRoundRect(
                                RoundRect(
                                    rect = Rect(
                                        centre.x - side / 2f, centre.y - side / 2f,
                                        centre.x + side / 2f, centre.y + side / 2f,
                                    ),
                                    topLeft = CornerRadius(side / 2f),
                                    topRight = CornerRadius(side / 2f),
                                    bottomRight = CornerRadius(side / 2f),
                                    bottomLeft = CornerRadius(4.dp.toPx()),
                                ),
                            )
                        }
                        // Drop shadow (black 0.3, r4, y2) — blurred rotated
                        // silhouette, offset downward in SCREEN space.
                        drawIntoCanvas { canvas ->
                            val bodyAndroid = body.asAndroidPath()
                            val matrix = android.graphics.Matrix().apply {
                                setRotate(-45f, centre.x, centre.y)
                                postTranslate(0f, 2.dp.toPx())
                            }
                            val shadowPath = android.graphics.Path()
                            bodyAndroid.transform(matrix, shadowPath)
                            canvas.nativeCanvas.drawPath(shadowPath, pinShadow)
                        }
                        rotate(degrees = -45f, pivot = centre) {
                            if (marker.selected) {
                                // Mock `.pin.sel`: 3-wide primaryMuted
                                // outline following the teardrop shape.
                                drawPath(
                                    body,
                                    color = colors.primaryMuted,
                                    style = Stroke(width = 3.dp.toPx()),
                                )
                            }
                            drawPath(body, color = marker.tint)
                            drawPath(
                                body,
                                color = colors.surface,
                                style = Stroke(width = 2.5.dp.toPx()),
                            )
                        }
                        // Title INSIDE the drop — dark ink like text-on-primary
                        // (mock --primary-ink, legible on primary and warn).
                        marker.title?.let { title ->
                            drawIntoCanvas { canvas ->
                                pinLabel.textSize = 10.5f * density
                                pinLabel.color = android.graphics.Color.rgb(0x06, 0x13, 0x0A)
                                val fm = pinLabel.fontMetrics
                                canvas.nativeCanvas.drawText(
                                    title,
                                    centre.x,
                                    centre.y - (fm.ascent + fm.descent) / 2f,
                                    pinLabel,
                                )
                            }
                        }
                        // Badge chips — bottom row of the block, sitting on
                        // the coordinate (mock `.pin .badges`).
                        if (hasBadges) {
                            drawBadgeRow(
                                marker, pt, chipH, badgeFm, badgeText, badgeFill, badgeStroke,
                                textColor = colors.textSecondary.toArgb(),
                                fillColor = colors.surface.toArgb(),
                                strokeColor = colors.divider.toArgb(),
                            )
                        }
                    }

                    MapMarkerShape.RING -> {
                        // Cruise plot marker (v3 mock `.plotpin .ringdot`):
                        // a 34 dp hollow circle CENTRE-anchored on the
                        // coordinate — 3 dp tint ring over a surface disc,
                        // title inside in the tint. `dashed` = planned.
                        val radius = 17.dp.toPx()
                        // box-shadow: 0 2px 8px rgba(0,0,0,.25) analogue.
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawCircle(
                                pt.x, pt.y + 2.dp.toPx(), radius, pinShadow)
                        }
                        if (marker.selected) {
                            // Mock `.plotpin.sel`: 3-wide soft-tint outline
                            // 2 dp outside the ring.
                            drawCircle(
                                color = marker.tint.copy(alpha = 0.28f),
                                radius = radius + 3.5.dp.toPx(),
                                center = pt,
                                style = Stroke(width = 3.dp.toPx()),
                            )
                        }
                        drawCircle(
                            color = if (marker.dashed) {
                                colors.surface.copy(alpha = 0.8f)
                            } else {
                                colors.surface
                            },
                            radius = radius,
                            center = pt,
                        )
                        drawCircle(
                            color = marker.tint,
                            radius = radius,
                            center = pt,
                            style = Stroke(
                                width = 3.dp.toPx(),
                                pathEffect = if (marker.dashed) {
                                    PathEffect.dashPathEffect(
                                        floatArrayOf(4.dp.toPx(), 3.dp.toPx()))
                                } else {
                                    null
                                },
                            ),
                        )
                        marker.title?.let { title ->
                            drawIntoCanvas { canvas ->
                                pinLabel.textSize = 10.5f * density
                                pinLabel.color = marker.tint.toArgb()
                                val fm = pinLabel.fontMetrics
                                canvas.nativeCanvas.drawText(
                                    title,
                                    pt.x,
                                    pt.y - (fm.ascent + fm.descent) / 2f,
                                    pinLabel,
                                )
                            }
                        }
                        // Badge chips (e.g. tree tally) — below the ring.
                        if (marker.badges.isNotEmpty()) {
                            badgeText.textSize = 8.5f * density
                            val badgeFm2 = badgeText.fontMetrics
                            val chipH2 = (badgeFm2.descent - badgeFm2.ascent) + 2f * density
                            drawBadgeRow(
                                marker,
                                Offset(pt.x, pt.y + radius + 3.dp.toPx() + chipH2),
                                chipH2, badgeFm2, badgeText, badgeFill, badgeStroke,
                                textColor = colors.textSecondary.toArgb(),
                                fillColor = colors.surface.toArgb(),
                                strokeColor = colors.divider.toArgb(),
                            )
                        }
                    }
                }
            }
        }

        // Credit for the built-in base — required by the Esri imagery terms
        // and by the ODbL for OpenStreetMap, so it stays on whenever a
        // built-in base is in use and SWITCHES with the map type. Fixed
        // dark-glass colours on purpose: it sits on map tiles, not on an
        // app surface (iOS attributionBadge).
        if (baseIsBuiltin) {
            Text(
                TileFetcher.builtInAttribution(mapType),
                style = Forestix.type.dataSmall.copy(
                    fontSize = 9.sp, fontWeight = FontWeight.Normal),
                color = Color.White.copy(alpha = 0.78f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(start = 10.dp, bottom = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .padding(horizontal = 6.dp, vertical = 2.5.dp),
            )
        }

        if (attribution != null) {
            Text(
                attribution,
                style = Forestix.type.caption,
                color = colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(ForestixSpace.xs),
            )
        }
    }
}

/// One bottom-anchored row of badge chips ("D"/"H" under a PIN, tallies
/// under a RING): `anchor.y` is the row's BOTTOM edge and the row centres
/// horizontally on `anchor.x`. The shared marker paints are (re)coloured
/// here so both call sites stay in lockstep.
private fun DrawScope.drawBadgeRow(
    marker: MapMarker,
    anchor: Offset,
    chipH: Float,
    fm: Paint.FontMetrics,
    text: Paint,
    fill: Paint,
    stroke: Paint,
    textColor: Int,
    fillColor: Int,
    strokeColor: Int,
) {
    drawIntoCanvas { canvas ->
        text.color = textColor
        fill.color = fillColor
        stroke.color = strokeColor
        stroke.strokeWidth = 1.dp.toPx()
        val padH = 4.dp.toPx()
        val gap = 2.dp.toPx()
        val corner = 3.dp.toPx()
        val widths = marker.badges.map { text.measureText(it) + padH * 2 }
        var x = anchor.x - (widths.sum() + gap * (marker.badges.size - 1)) / 2f
        val top = anchor.y - chipH
        marker.badges.forEachIndexed { i, badge ->
            val w = widths[i]
            canvas.nativeCanvas.drawRoundRect(
                x, top, x + w, top + chipH, corner, corner, fill)
            canvas.nativeCanvas.drawRoundRect(
                x, top, x + w, top + chipH, corner, corner, stroke)
            canvas.nativeCanvas.drawText(
                badge,
                x + w / 2f,
                top + chipH / 2f - (fm.ascent + fm.descent) / 2f,
                text,
            )
            x += w + gap
        }
    }
}

// MARK: - Sampling plot drawing

/// Equatorial circumference used to turn the Web-Mercator world width into
/// ground metres. Paired with the 1/cos(latitude) scale factor below it
/// gives the plot TRUE GEOGRAPHIC SCALE at any zoom.
private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

/// Which area a tap landed in, or null. Smallest projected extent wins so a
/// nested area is reachable; a tap in a HOLE is a tap on whatever is under
/// the area, exactly as the even-odd fill draws it. iOS
/// `BasemapMapView.areaHitTest`.
private fun areaHitTest(
    areas: List<MapAreaOverlay>,
    tap: Offset,
    project: (CoordinateConversions.LatLon) -> Offset,
): String? {
    var bestId: String? = null
    var bestExtent = Float.MAX_VALUE
    for (area in areas) {
        val outer = area.rings.firstOrNull() ?: continue
        if (outer.size < 3) continue
        val projected = outer.map(project)
        if (!pointInScreenRing(tap, projected)) continue
        val inHole = area.rings.drop(1).any { hole ->
            hole.size >= 3 && pointInScreenRing(tap, hole.map(project))
        }
        if (inHole) continue
        val xs = projected.map { it.x }
        val ys = projected.map { it.y }
        val extent = (xs.max() - xs.min()) * (ys.max() - ys.min())
        if (extent < bestExtent) {
            bestExtent = extent
            bestId = area.id
        }
    }
    return bestId
}

/// Crossing-number point-in-polygon in viewport pixels. Screen space rather
/// than lat/lon so the answer matches what was drawn.
private fun pointInScreenRing(p: Offset, ring: List<Offset>): Boolean {
    if (ring.size < 3) return false
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.y > p.y) != (b.y > p.y) &&
            p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
        ) {
            inside = !inside
        }
        j = i
    }
    return inside
}

/// Screen pixels per ground metre at `latitude` for a world `worldPx` wide.
private fun pxPerMetreAt(latitude: Double, worldPx: Double): Float {
    val lat = latitude.coerceIn(-85.0, 85.0) * PI / 180.0
    return (worldPx / (EARTH_CIRCUMFERENCE_M * cos(lat))).toFloat()
}

/// `distanceM` out from `from` on a TRUE bearing (0° = north, clockwise),
/// in local ENU. At plot scale the flat-earth approximation is good to well
/// under a pixel, and it is the same one the cruise plot ring has always
/// used. Projecting the RESULT is what keeps N/E/S/W on true bearings
/// instead of assuming screen-up is north.
private fun offsetLatLon(
    from: CoordinateConversions.LatLon,
    distanceM: Double,
    bearingDeg: Double,
): CoordinateConversions.LatLon {
    val b = bearingDeg * PI / 180.0
    val mPerDegLat = 111_132.0
    val mPerDegLon = (111_320.0 * cos(from.latitude * PI / 180.0)).coerceAtLeast(1.0)
    return CoordinateConversions.LatLon(
        latitude = from.latitude + distanceM * cos(b) / mPerDegLat,
        longitude = from.longitude + distanceM * sin(b) / mPerDegLon,
    )
}

/// The sampling plot (see [MapPlotOverlay]).
///
/// LEGIBILITY: every tinted stroke is laid down twice — a dark casing under
/// the tint — which is the treatment the imported survey boundary already
/// uses, and the reason the plot stays readable on bright satellite imagery
/// AND on the pale OpenStreetMap base. Labels ride the same surface chip as
/// the marker badges rather than sitting as bare text on tiles.
///
/// THREE STATES, THREE PICTURES, so "am I in the plot?" is answered by the
/// drawing before anybody reads the banner — and is never answered when it
/// is not known:
///   * INSIDE  — accent tint, solid boundary. The calm state.
///   * OUTSIDE — warning tint, thicker solid boundary, dotted connector to
///               the fix so the way back needs no thinking.
///   * UNKNOWN — NEUTRAL GREY, DASHED boundary and a "No position" chip
///               under the centre. It used to be drawn identically to INSIDE,
///               which meant a glance at the map read as "you're in the
///               plot" at the exact moment the app had no idea where the
///               cruiser was.
private fun DrawScope.drawPlotOverlay(
    plot: MapPlotOverlay,
    centre: Offset,
    pxPerMetre: Float,
    cruiser: Offset?,
    /// (metres from centre, TRUE bearing°) → screen point.
    pointAt: (Double, Double) -> Offset,
    colors: ForestixColors,
    text: Paint,
    fill: Paint,
    stroke: Paint,
) {
    val radiusPx = (plot.radiusM * pxPerMetre).toFloat()
    if (!radiusPx.isFinite() || radiusPx <= 0f) return
    val unknown = plot.state == MapPlotFix.UNKNOWN
    // Grey is deliberately NOT one of the app's signal colours: the eye
    // reads it as "no reading", the same way the GPS chip's tertiary text
    // does, and it can't be confused with either the accent or the warning.
    val tint = when (plot.state) {
        MapPlotFix.OUTSIDE -> colors.confidenceBad
        MapPlotFix.INSIDE -> colors.accent
        MapPlotFix.UNKNOWN -> colors.textTertiary
    }
    val casing = Color.Black.copy(alpha = 0.45f)
    val ringDash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
    // A ring smaller than this is closer to its neighbours than a fingertip
    // is wide — drawing it adds ink, not information.
    val minRingPx = 10.dp.toPx()

    // Translucent fill — the imagery has to read straight through it.
    drawCircle(tint.copy(alpha = 0.10f), radius = radiusPx, center = centre)

    // Concentric range rings, inside the boundary only.
    for (ring in plot.rings) {
        val rPx = (ring.radiusM * pxPerMetre).toFloat()
        if (!rPx.isFinite() || rPx < minRingPx || rPx >= radiusPx) continue
        drawCircle(
            casing, radius = rPx, center = centre,
            style = Stroke(width = 2.5.dp.toPx(), pathEffect = ringDash),
        )
        drawCircle(
            tint.copy(alpha = 0.85f), radius = rPx, center = centre,
            style = Stroke(width = 1.dp.toPx(), pathEffect = ringDash),
        )
    }

    // Boundary — emphasised while the cruiser is outside it, and BROKEN
    // while the state is unknown. A dashed outline is the oldest
    // cartographic convention there is for "this line is not asserted", and
    // it survives at a glance, in sunlight, at any zoom: the one thing this
    // circle must never do is look settled when nothing is known.
    val boundaryWidth = if (plot.state == MapPlotFix.OUTSIDE) 3.5.dp.toPx() else 2.5.dp.toPx()
    val boundaryDash =
        if (unknown) PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 6.dp.toPx())) else null
    drawCircle(
        casing, radius = radiusPx, center = centre,
        style = Stroke(width = boundaryWidth + 2.5.dp.toPx(), pathEffect = boundaryDash),
    )
    drawCircle(
        tint, radius = radiusPx, center = centre,
        style = Stroke(width = boundaryWidth, pathEffect = boundaryDash),
    )

    // Outside: a dotted connector from the centre to the cruiser, so the
    // direction back is obvious. Same dotted 2/9 round-cap line as the
    // navigation guide, cased so it survives both bases.
    if (plot.state == MapPlotFix.OUTSIDE && cruiser != null) {
        val connector = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 9.dp.toPx()))
        drawLine(
            casing, centre, cruiser, strokeWidth = 5.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = connector,
        )
        drawLine(
            tint, centre, cruiser, strokeWidth = 2.5.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round, pathEffect = connector,
        )
    }

    // Centre mark — a CROSS, so it can never be read as one of the round
    // pins; the same mark the enlarged plot view draws.
    val arm = 9.dp.toPx()
    listOf(colors.surface to 5.dp.toPx(), colors.textPrimary to 2.dp.toPx())
        .forEach { (colour, w) ->
            drawLine(
                colour, Offset(centre.x - arm, centre.y), Offset(centre.x + arm, centre.y),
                strokeWidth = w,
            )
            drawLine(
                colour, Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y + arm),
                strokeWidth = w,
            )
        }

    // UNKNOWN says so IN WORDS, on the drawing itself. Tint and dash carry
    // it at a glance; this removes the last shred of ambiguity for anyone
    // who has not learned the convention (or cannot separate the hues).
    // It sits just below the plot's own centre pin, the one place inside
    // the circle that no ring label or compass badge ever occupies.
    if (unknown) {
        drawPlotPill(
            // "No position", not "No fix": the same words the banner uses
            // an inch above it, and plain English rather than radio jargon
            // a cruiser has to already know. iOS draws the same string.
            Offset(centre.x, centre.y + 26.dp.toPx()), "No position", text, fill, stroke,
            textColor = colors.textSecondary.toArgb(),
            fillColor = colors.surface.toArgb(),
            strokeColor = colors.divider.toArgb(),
            textSizePx = 10f * density,
        )
    }

    // Ring distance labels — on the NE diagonal, clear of the four compass
    // badges. Dropped when the circle is too small to hold them.
    for (ring in plot.rings) {
        val rPx = (ring.radiusM * pxPerMetre).toFloat()
        if (!rPx.isFinite() || rPx < 24.dp.toPx() || rPx >= radiusPx) continue
        drawPlotPill(
            pointAt(ring.radiusM, 45.0), ring.label, text, fill, stroke,
            textColor = colors.textSecondary.toArgb(),
            fillColor = colors.surface.toArgb(),
            strokeColor = colors.divider.toArgb(),
            textSizePx = 9f * density,
        )
    }

    // N / E / S / W on the boundary, placed by TRUE bearing so they stay
    // correct however the map is turned or the phone is held.
    if (radiusPx >= 26.dp.toPx()) {
        listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, bearing) ->
            drawPlotPill(
                pointAt(plot.radiusM, bearing), label, text, fill, stroke,
                textColor = colors.textPrimary.toArgb(),
                fillColor = colors.surface.toArgb(),
                strokeColor = colors.divider.toArgb(),
                textSizePx = 10f * density,
            )
        }
    }
}

/// One small CENTRED chip — the surface fill + divider hairline the marker
/// badge chips use, so the plot's labels read as the same family of map
/// furniture instead of loose text on tiles.
private fun DrawScope.drawPlotPill(
    at: Offset,
    label: String,
    text: Paint,
    fill: Paint,
    stroke: Paint,
    textColor: Int,
    fillColor: Int,
    strokeColor: Int,
    textSizePx: Float,
) {
    drawIntoCanvas { canvas ->
        text.textSize = textSizePx
        text.color = textColor
        fill.color = fillColor
        stroke.color = strokeColor
        stroke.strokeWidth = 1.dp.toPx()
        val fm = text.fontMetrics
        val w = text.measureText(label) + 4.dp.toPx() * 2
        val h = (fm.descent - fm.ascent) + 1.5.dp.toPx() * 2
        val corner = 3.dp.toPx()
        val left = at.x - w / 2f
        val top = at.y - h / 2f
        canvas.nativeCanvas.drawRoundRect(
            left, top, left + w, top + h, corner, corner, fill)
        canvas.nativeCanvas.drawRoundRect(
            left, top, left + w, top + h, corner, corner, stroke)
        canvas.nativeCanvas.drawText(
            label, at.x, at.y - (fm.ascent + fm.descent) / 2f, text)
    }
}

// MARK: - Web-Mercator helpers (normalized 0..1 world coordinates)

private fun lonToXNorm(lon: Double): Double = (lon + 180.0) / 360.0

private fun latToYNorm(lat: Double): Double {
    val clamped = lat.coerceIn(-85.05112878, 85.05112878)
    val latRad = clamped * PI / 180.0
    return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
}

private fun xNormToLon(xNorm: Double): Double = xNorm * 360.0 - 180.0

private fun yNormToLat(yNorm: Double): Double =
    atan(sinh(PI * (1.0 - 2.0 * yNorm))) * 180.0 / PI
