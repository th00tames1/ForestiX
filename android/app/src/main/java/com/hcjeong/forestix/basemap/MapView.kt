// Compose slippy-map view — Android stand-in for the iOS PlotMapScreen's
// MapKit `Map { MapPolygon / Marker }` content (spec §3.1 REQ-PRJ-004)
// plus the offline tile overlay MapKit gets from TileCache+MapKit.
//
// A plain Canvas renderer: Web-Mercator tiles from TileFetcher (which
// honours AppSettings.tileURLTemplate) drawn under stratum polygon +
// plot marker overlays, with drag-to-pan and pinch-to-zoom. When no
// tile template is configured the overlays render on a bare background
// (iOS shows Apple's basemap there; Android has no bundled provider).

package com.hcjeong.forestix.basemap

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/// A labelled point, the analogue of `Marker("#5", coordinate:).tint(...)`.
data class MapMarker(
    val coordinate: CoordinateConversions.LatLon,
    val title: String? = null,
    val tint: Color = Color(0xFF007AFF),
)

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
) {
    val context = LocalContext.current
    val colors = Forestix.colors
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    var camCenter by remember(center) { mutableStateOf(center) }
    var camZoom by remember { mutableDoubleStateOf(initialZoom) }

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

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
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

            // MARK: Markers (planned plots)
            val markerRadius = 6.dp.toPx()
            for (marker in markers) {
                val pt = screenPoint(marker.coordinate)
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
