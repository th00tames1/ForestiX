// Compose slippy-map view — Android stand-in for the iOS PlotMapScreen's
// MapKit `Map { MapPolygon / Marker }` content (spec §3.1 REQ-PRJ-004)
// plus the offline tile overlay MapKit gets from TileCache+MapKit.
//
// A plain Canvas renderer: Web-Mercator tiles from TileFetcher (which
// honours AppSettings.tileURLTemplate) drawn under stratum polygon +
// plot marker overlays, with drag-to-pan and pinch-to-zoom. When no
// tile template is configured the overlays render on a bare background
// (iOS shows Apple's basemap there; Android has no bundled provider).
//
// Map-home extensions (design/forestix-redesign-v2-maphome.html): teardrop
// PIN markers with the title inside and D/H/C badge chips beneath, marker
// tap hit-testing, a pulsing "you" dot, and an observable MapCameraState
// whose visibleBounds() feeds the offline tile downloader.

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

// MARK: - Overlay models (mirror MapPolygon / Marker content on iOS)

/// One closed outer ring. Defaults mirror the iOS PlotMapScreen styling:
/// `.green.opacity(0.18)` fill with a 2 pt green stroke.
data class MapPolygonOverlay(
    val ring: List<CoordinateConversions.LatLon>,
    val fillColor: Color = Color(0xFF34C759).copy(alpha = 0.18f),
    val strokeColor: Color = Color(0xFF34C759),
)

/// How a MapMarker renders. DOT is the original plot-map style (small
/// circle, halo label above); PIN is the map-home teardrop from the mock —
/// 30 dp drop with the title inside and badge chips ("D"/"H"/"C") beneath.
enum class MapMarkerShape { DOT, PIN }

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
    /// PIN only: soft tint halo around the drop (mock `.pin.sel`).
    val selected: Boolean = false,
)

// MARK: - Camera state (map home / offline downloader)

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
    tileURLTemplate: String? = null,
    polygons: List<MapPolygonOverlay> = emptyList(),
    markers: List<MapMarker> = emptyList(),
    attribution: String? = null,
    /// Tap within ~24 dp of a marker's screen point → its `id`.
    onMarkerTap: ((String) -> Unit)? = null,
    /// Tap that hit no marker — the map home uses it to dismiss the peek card.
    onMapTap: (() -> Unit)? = null,
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

    val fetcher = remember(tileURLTemplate) {
        tileURLTemplate?.takeIf { it.isNotBlank() }?.let { TileFetcher(context, it) }
    }
    // Recompose (and therefore redraw) whenever a new tile bitmap lands.
    val tileTick = if (fetcher != null) fetcher.tilesVersion.collectAsStateWithLifecycle().value else 0

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
    // PIN paints — mono like the mock's --font-mono pin glyphs.
    val pinLabel = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
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
                            camZoom = (camZoom + log2(gestureZoom.toDouble())).coerceIn(1.0, 19.0)
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
                .pointerInput(markers, onMarkerTap, onMapTap) {
                    if (onMarkerTap == null && onMapTap == null) return@pointerInput
                    detectTapGestures { tap ->
                        // Same projection as the draw pass, evaluated with the
                        // camera as of the tap.
                        val worldPx = 256.0 * density * 2.0.pow(camZoom)
                        val originX = lonToXNorm(camCenter.longitude) * worldPx - size.width / 2.0
                        val originY = latToYNorm(camCenter.latitude) * worldPx - size.height / 2.0
                        val hitRadius = 24.dp.toPx()
                        val tipToBody = (30.dp.toPx() / 2f) * sqrt(2f)
                        var bestId: String? = null
                        var bestDist = hitRadius
                        for (m in markers) {
                            val px = (lonToXNorm(m.coordinate.longitude) * worldPx - originX).toFloat()
                            val py = (latToYNorm(m.coordinate.latitude) * worldPx - originY).toFloat()
                            var d = hypot(tap.x - px, tap.y - py)
                            if (m.shape == MapMarkerShape.PIN) {
                                // The teardrop body floats above the tip point.
                                d = minOf(d, hypot(tap.x - px, tap.y - (py - tipToBody)))
                            }
                            if (d <= bestDist) { bestDist = d; bestId = m.id }
                        }
                        val hit = bestId
                        if (hit != null && onMarkerTap != null) onMarkerTap(hit) else onMapTap?.invoke()
                    }
                },
        ) {
            // tileTick is read so a freshly-downloaded tile invalidates us.
            @Suppress("UNUSED_EXPRESSION") tileTick

            drawRect(color = colors.surface)

            val tileZoom = floor(camZoom).toInt().coerceIn(0, 19)
            val n = 1 shl tileZoom
            val tilePx = 256.0 * density * 2.0.pow(camZoom - tileZoom)
            val worldPx = tilePx * n
            val originX = lonToXNorm(camCenter.longitude) * worldPx - size.width / 2.0
            val originY = latToYNorm(camCenter.latitude) * worldPx - size.height / 2.0

            // MARK: Tiles
            if (fetcher != null) {
                val tx0 = floor(originX / tilePx).toInt()
                val tx1 = floor((originX + size.width) / tilePx).toInt()
                val ty0 = floor(originY / tilePx).toInt().coerceAtLeast(0)
                val ty1 = floor((originY + size.height) / tilePx).toInt().coerceAtMost(n - 1)
                for (ty in ty0..ty1) {
                    for (tx in tx0..tx1) {
                        val wrappedX = ((tx % n) + n) % n
                        val key = TileCache.Key(z = tileZoom, x = wrappedX, y = ty)
                        val bitmap = fetcher.bitmapFor(key, scope) ?: continue
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

            // MARK: Polygons (stratum outer rings)
            for (polygon in polygons) {
                if (polygon.ring.size < 3) continue
                val path = Path()
                polygon.ring.forEachIndexed { i, p ->
                    val pt = screenPoint(p)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                path.close()
                drawPath(path, color = polygon.fillColor)
                drawPath(path, color = polygon.strokeColor, style = Stroke(width = 2.dp.toPx()))
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
            for (marker in markers) {
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
                        // corner, rotated -45° so the sharp corner becomes the
                        // tip sitting exactly on the coordinate.
                        val side = 30.dp.toPx()
                        val tipToCentre = (side / 2f) * sqrt(2f)
                        val centre = Offset(pt.x, pt.y - tipToCentre)
                        // Ground shadow so the drop reads as "planted".
                        drawOval(
                            color = Color.Black.copy(alpha = 0.18f),
                            topLeft = Offset(pt.x - 7.dp.toPx(), pt.y - 2.dp.toPx()),
                            size = Size(14.dp.toPx(), 4.dp.toPx()),
                        )
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
                                    bottomLeft = CornerRadius(2.dp.toPx()),
                                ),
                            )
                        }
                        rotate(degrees = -45f, pivot = centre) {
                            if (marker.selected) {
                                // Mock `.pin.sel`: soft tint outline.
                                drawPath(
                                    body,
                                    color = marker.tint.copy(alpha = 0.35f),
                                    style = Stroke(width = 7.dp.toPx()),
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
                        // Badge chips beneath the tip (mock `.pin .badges`).
                        if (marker.badges.isNotEmpty()) {
                            drawIntoCanvas { canvas ->
                                badgeText.textSize = 8.5f * density
                                badgeText.color = colors.textSecondary.toArgb()
                                badgeFill.color = colors.surface.toArgb()
                                badgeStroke.color = colors.divider.toArgb()
                                badgeStroke.strokeWidth = 1f * density
                                val chipH = 12.dp.toPx()
                                val padH = 4.dp.toPx()
                                val gap = 2.dp.toPx()
                                val corner = 3.dp.toPx()
                                val widths = marker.badges.map { badgeText.measureText(it) + padH * 2 }
                                var x = pt.x - (widths.sum() + gap * (marker.badges.size - 1)) / 2f
                                val top = pt.y + 3.dp.toPx()
                                val fm = badgeText.fontMetrics
                                marker.badges.forEachIndexed { i, badge ->
                                    val w = widths[i]
                                    canvas.nativeCanvas.drawRoundRect(
                                        x, top, x + w, top + chipH, corner, corner, badgeFill)
                                    canvas.nativeCanvas.drawRoundRect(
                                        x, top, x + w, top + chipH, corner, corner, badgeStroke)
                                    canvas.nativeCanvas.drawText(
                                        badge,
                                        x + w / 2f,
                                        top + chipH / 2f - (fm.ascent + fm.descent) / 2f,
                                        badgeText,
                                    )
                                    x += w + gap
                                }
                            }
                        }
                    }
                }
            }
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
