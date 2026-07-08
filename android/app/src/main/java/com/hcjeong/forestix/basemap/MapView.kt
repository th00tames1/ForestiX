// Compose slippy-map view — Android stand-in for the iOS PlotMapScreen's
// MapKit `Map { MapPolygon / Marker }` content (spec §3.1 REQ-PRJ-004)
// plus the offline tile overlay MapKit gets from TileCache+MapKit.
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    /// BASE layer override. Null/blank → the built-in Esri World Imagery
    /// satellite base (zero-setup imagery whenever online). Callers that
    /// pass the user template here keep their old single-layer behaviour.
    tileURLTemplate: String? = null,
    /// Optional OVERLAY drawn on top of the base — the map home passes
    /// AppSettings.tileURLTemplate here (often transparent PNG tiles).
    overlayURLTemplate: String? = null,
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

    // Base layer: caller override, else the built-in satellite provider.
    val baseIsBuiltin = tileURLTemplate.isNullOrBlank()
    val baseFetcher = remember(tileURLTemplate) {
        tileURLTemplate?.takeIf { it.isNotBlank() }?.let { TileFetcher(context, it) }
            ?: TileFetcher.esriWorldImagery(context)
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
                            // 22, not the native tile max (19): past 19 the
                            // renderer overzooms so dense stands separate.
                            camZoom = (camZoom + log2(gestureZoom.toDouble())).coerceIn(3.0, 22.0)
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
                    detectTapGestures(
                        // iOS BasemapMapView doubleTapZoom: one level in,
                        // keeping the tapped point stationary.
                        onDoubleTap = { tap ->
                            val oldZoom = camZoom
                            val newZoom = (oldZoom + 1.0).coerceIn(3.0, 22.0)
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
                            if (onMarkerTap == null && onMapTap == null) return@detectTapGestures
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
                            if (hit != null && onMarkerTap != null) onMarkerTap(hit) else onMapTap?.invoke()
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
            // native max (19, iOS `maxTileZoom`) — the camera zooms to 22
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
                            drawIntoCanvas { canvas ->
                                badgeText.color = colors.textSecondary.toArgb()
                                badgeFill.color = colors.surface.toArgb()
                                badgeStroke.color = colors.divider.toArgb()
                                badgeStroke.strokeWidth = 1f * density
                                val padH = 4.dp.toPx()
                                val gap = 2.dp.toPx()
                                val corner = 3.dp.toPx()
                                val widths = marker.badges.map { badgeText.measureText(it) + padH * 2 }
                                var x = pt.x - (widths.sum() + gap * (marker.badges.size - 1)) / 2f
                                val top = pt.y - chipH
                                marker.badges.forEachIndexed { i, badge ->
                                    val w = widths[i]
                                    canvas.nativeCanvas.drawRoundRect(
                                        x, top, x + w, top + chipH, corner, corner, badgeFill)
                                    canvas.nativeCanvas.drawRoundRect(
                                        x, top, x + w, top + chipH, corner, corner, badgeStroke)
                                    canvas.nativeCanvas.drawText(
                                        badge,
                                        x + w / 2f,
                                        top + chipH / 2f - (badgeFm.ascent + badgeFm.descent) / 2f,
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

        // Imagery credit for the built-in satellite base — required by the
        // Esri terms, so it stays on whenever that base is in use. Fixed
        // dark-glass colours on purpose: it sits on satellite imagery, not
        // on an app surface (iOS attributionBadge).
        if (baseIsBuiltin) {
            Text(
                TileFetcher.ESRI_WORLD_IMAGERY_ATTRIBUTION,
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
