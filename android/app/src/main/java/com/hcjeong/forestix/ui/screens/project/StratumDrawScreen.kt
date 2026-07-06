// Port of iOS Screens/StratumDrawScreen.swift.
// Phase 7.4 — draw a stratum on the map: open the map, tap the corners of
// the harvest block, type a name, save.
//
// iOS uses MapKit's `MapReader` to convert screen taps back to WGS84; here
// the map is a self-contained Web-Mercator canvas (same math as
// basemap/MapView.kt) extended with tap-to-coordinate support, drawing the
// tapped vertices as numbered red pins and a green closed-polygon preview
// once ≥3 points are placed. Tiles honour AppSettings.tileURLTemplate; with
// no template configured the overlays render on a bare background.

package com.hcjeong.forestix.ui.screens.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.TileCache
import com.hcjeong.forestix.basemap.TileFetcher
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
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
import kotlinx.coroutines.launch

@Composable
fun StratumDrawScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        project = env.projectRepository.read(UUID.fromString(projectId))
    }
    val loaded = project
    if (loaded == null) {
        ForestixScaffold(nav, title = "Draw stratum") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    StratumDrawContent(nav, loaded)
}

@Composable
private fun StratumDrawContent(nav: NavController, project: Project) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val viewModel = remember(project.id) { StratumDrawViewModel(project) }
    val settings by env.settings.state.collectAsStateWithLifecycle()

    val vertices by viewModel.vertices.collectAsState()
    val name by viewModel.name.collectAsState()
    val areaAcres by viewModel.areaAcres.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val didSave by viewModel.didSave.collectAsState()

    LaunchedEffect(Unit) { viewModel.configure(env) }

    // iOS `.onChange(of: viewModel.didSave)` → dismiss.
    LaunchedEffect(didSave) {
        if (didSave) nav.popBackStack()
    }

    // Centre the map on the first GPS fix (iOS Map does this automatically
    // when location is authorized). Falls back to the CONUS centroid.
    var camCenter by remember {
        mutableStateOf(CoordinateConversions.LatLon(latitude = 39.8283, longitude = -98.5795))
    }
    var camZoom by remember { mutableDoubleStateOf(4.0) }
    var centeredOnFix by remember { mutableStateOf(false) }
    val location = remember { LocationService(context) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // FINE + COARSE requested together (Android 12+ requirement);
        // either grant is enough to run, coarse just degrades the tier.
        val granted = results.values.any { it }
        location.onPermissionResult(granted)
        if (granted) location.start()
    }
    LaunchedEffect(Unit) {
        if (LocationService.hasLocationPermission(context)) {
            location.start()
        } else {
            permissionLauncher.launch(LocationService.PERMISSIONS)
        }
    }
    // Release GPS on every exit path — the early stop() after the first
    // auto-centre fix never runs when the user pans first or leaves before
    // a fix arrives (same pattern as NavigationScreen/PlotCenterScreen).
    DisposableEffect(Unit) {
        onDispose { location.stop() }
    }
    val latestSnapshot by location.latestSnapshot.collectAsStateWithLifecycle()
    LaunchedEffect(latestSnapshot) {
        val s = latestSnapshot ?: return@LaunchedEffect
        if (!centeredOnFix && vertices.isEmpty()) {
            camCenter = CoordinateConversions.LatLon(latitude = s.latitude, longitude = s.longitude)
            camZoom = 15.0
            centeredOnFix = true
            location.stop()
        }
    }

    ForestixScaffold(nav, title = "Draw stratum") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // MARK: - Help banner
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.primaryMuted)
                    .padding(ForestixSpace.sm),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                ) {
                    Icon(Icons.Filled.TouchApp, contentDescription = null,
                        tint = colors.textPrimary, modifier = Modifier.size(16.dp))
                    Text("How to use", style = type.bodyBold, color = colors.textPrimary)
                }
                Text(
                    "Tap the corners of the cutting block in order on the map. Area is computed once you've placed at least 3 points. Tap Undo to remove the last point if you mis-tapped.",
                    style = type.caption, color = colors.textSecondary)
            }

            // MARK: - Map area
            StratumDrawMap(
                camCenter = camCenter,
                camZoom = camZoom,
                onCamera = { center, zoom ->
                    camCenter = center
                    camZoom = zoom
                    centeredOnFix = true    // user took control; stop auto-centring
                },
                vertices = vertices,
                tileURLTemplate = settings.tileURLTemplate,
                onTap = { viewModel.addVertex(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // MARK: - Control panel
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface)
                    .padding(ForestixSpace.md),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(viewModel.vertexCountLabel, style = type.bodyBold,
                            color = colors.textPrimary)
                        if (areaAcres > 0) {
                            Text(
                                String.format(Locale.US, "Area: %.3f acres", areaAcres),
                                style = type.caption, color = colors.textSecondary)
                        }
                    }
                    TextButton(
                        onClick = { viewModel.removeLast() },
                        enabled = vertices.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Undo")
                    }
                    TextButton(
                        onClick = { viewModel.clear() },
                        enabled = vertices.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null,
                            tint = colors.confidenceBad, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Clear", color = colors.confidenceBad)
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.name.value = it },
                    placeholder = { Text("Stratum name (e.g. Block 1, North block)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = { scope.launch { viewModel.save() } },
                    enabled = viewModel.canSave,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(ForestixSpace.xs))
                    }
                    Text(if (isSaving) "Saving…" else "Save stratum", style = type.bodyBold)
                }
            }
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.errorMessage.value = null },
            title = { Text("Error") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.errorMessage.value = null }) { Text("OK") }
            },
        )
    }
}

// MARK: - Tappable slippy map
//
// Same Web-Mercator tile + overlay renderer as basemap/MapView.kt, with the
// camera hoisted (so the screen can auto-centre on the first GPS fix) and a
// tap detector that converts a screen point back to WGS84 — the Android
// analogue of MapKit's `MapProxy.convert(_:from:)`.

@Composable
private fun StratumDrawMap(
    camCenter: CoordinateConversions.LatLon,
    camZoom: Double,
    onCamera: (CoordinateConversions.LatLon, Double) -> Unit,
    vertices: List<CoordinateConversions.LatLon>,
    tileURLTemplate: String?,
    onTap: (CoordinateConversions.LatLon) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = Forestix.colors
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()

    // The gesture detectors below live in pointerInput(Unit) coroutines that
    // outlast recompositions — read the camera through rememberUpdatedState
    // so they never act on a stale centre/zoom.
    val centerState = rememberUpdatedState(camCenter)
    val zoomState = rememberUpdatedState(camZoom)

    val fetcher = remember(tileURLTemplate) {
        tileURLTemplate?.takeIf { it.isNotBlank() }?.let { TileFetcher(context, it) }
    }
    val tileTick = if (fetcher != null) {
        fetcher.tilesVersion.collectAsStateWithLifecycle().value
    } else 0

    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            color = android.graphics.Color.WHITE
            isFakeBoldText = true
        }
    }

    val vertexRed = Color(0xFFFF3B30)       // iOS `.red`
    val polygonGreen = Color(0xFF34C759)    // iOS `.green`

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val center = centerState.value
                        val worldPx = 256.0 * density * 2.0.pow(zoomState.value)
                        val originX = lonToXNorm(center.longitude) * worldPx - size.width / 2.0
                        val originY = latToYNorm(center.latitude) * worldPx - size.height / 2.0
                        val xNorm = ((originX + tapOffset.x) / worldPx).mod(1.0)
                        val yNorm = ((originY + tapOffset.y) / worldPx).coerceIn(0.0, 1.0)
                        onTap(
                            CoordinateConversions.LatLon(
                                latitude = yNormToLat(yNorm),
                                longitude = xNormToLon(xNorm),
                            )
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        var zoom = zoomState.value
                        if (gestureZoom != 1f && gestureZoom > 0f) {
                            zoom = (zoomState.value + log2(gestureZoom.toDouble()))
                                .coerceIn(1.0, 19.0)
                        }
                        var center = centerState.value
                        if (pan != Offset.Zero) {
                            val worldPx = 256.0 * density * 2.0.pow(zoom)
                            val cx = lonToXNorm(center.longitude) * worldPx - pan.x
                            val cy = latToYNorm(center.latitude) * worldPx - pan.y
                            val xNorm = (cx / worldPx).mod(1.0)
                            val yNorm = (cy / worldPx).coerceIn(0.0, 1.0)
                            center = CoordinateConversions.LatLon(
                                latitude = yNormToLat(yNorm),
                                longitude = xNormToLon(xNorm),
                            )
                        }
                        onCamera(center, zoom)
                    }
                },
        ) {
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
                        val dstSize = ceil(tilePx).toInt() + 1
                        drawImage(
                            image = bitmap.asImageBitmap(),
                            dstOffset = IntOffset(dstX, dstY),
                            dstSize = IntSize(dstSize, dstSize),
                        )
                    }
                }
            }

            fun screenPoint(p: CoordinateConversions.LatLon): Offset = Offset(
                (lonToXNorm(p.longitude) * worldPx - originX).toFloat(),
                (latToYNorm(p.latitude) * worldPx - originY).toFloat(),
            )

            // MARK: Closed polygon preview (fill + stroke) if ≥3 points.
            if (vertices.size >= 3) {
                val path = Path()
                vertices.forEachIndexed { i, p ->
                    val pt = screenPoint(p)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                path.close()
                drawPath(path, color = polygonGreen.copy(alpha = 0.20f))
                drawPath(path, color = polygonGreen, style = Stroke(width = 2.dp.toPx()))
            }

            // MARK: Numbered vertex pins.
            val pinRadius = 11.dp.toPx()
            vertices.forEachIndexed { idx, v ->
                val pt = screenPoint(v)
                drawCircle(color = vertexRed, radius = pinRadius, center = pt)
                drawIntoCanvas { canvas ->
                    labelPaint.textSize = 11f * density
                    val textY = pt.y - (labelPaint.ascent() + labelPaint.descent()) / 2f
                    canvas.nativeCanvas.drawText("${idx + 1}", pt.x, textY, labelPaint)
                }
            }
        }

        if (fetcher == null) {
            Text(
                "No basemap tiles configured — set a tile URL in Settings",
                style = Forestix.type.caption,
                color = colors.textSecondary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
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
