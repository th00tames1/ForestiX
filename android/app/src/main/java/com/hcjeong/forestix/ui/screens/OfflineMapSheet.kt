// Offline basemap sheet — target of the map home's layers button (mock
// design/forestix-redesign-v2-maphome.html ① `.roundbtn` layers).
//
// Two layers: the built-in Esri World Imagery satellite base (always on,
// zero setup) and the user's optional XYZ overlay template from Settings,
// whose visibility toggle persists as tc.overlayEnabled. "Download visible
// area" plans OfflineBasemap.planJob for BOTH layers over the camera's
// visibleBounds() and drains the combined queue sequentially through
// TileFetcher.prefetch with x/total progress + cancel (4 000-tile guard on
// the combined count), and offers combined TileCache stats / clear.
// Dismissing the sheet cancels an in-flight download.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.MapCameraState
import com.hcjeong.forestix.basemap.OfflineBasemap
import com.hcjeong.forestix.basemap.TileCache
import com.hcjeong.forestix.basemap.TileFetcher
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Refuse plans bigger than this — a zoomed-out viewport at zoom 12–17
/// explodes into millions of tiles; field areas stay well under the cap.
/// Applied to the COMBINED base + overlay count.
private const val MaxPlannedTiles = 4_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapSheet(
    camera: MapCameraState,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            Text(
                "OFFLINE MAP",
                style = type.sectionHead.copy(letterSpacing = 1.2.sp),
                color = colors.textTertiary,
            )
            LayersContent(
                template = settings.tileURLTemplate,
                label = settings.tileProviderLabel,
                overlayEnabled = settings.overlayEnabled,
                onOverlayEnabled = { env.settings.setOverlayEnabled(it) },
                camera = camera,
                onOpenSettings = onOpenSettings,
            )
        }
    }
}

// MARK: - Layers (base = built-in satellite, overlay = user template)

@Composable
private fun LayersContent(
    template: String?,
    label: String?,
    overlayEnabled: Boolean,
    onOverlayEnabled: (Boolean) -> Unit,
    camera: MapCameraState,
    onOpenSettings: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val baseFetcher = remember { TileFetcher.esriWorldImagery(context) }
    val overlayFetcher = remember(template) { template?.let { TileFetcher(context, it) } }

    var stats by remember { mutableStateOf<TileCache.Stats?>(null) }
    var statsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(overlayFetcher, statsTick) {
        stats = withContext(Dispatchers.IO) {
            val base = baseFetcher.cache.stats()
            val overlay = overlayFetcher?.cache?.stats()
            TileCache.Stats(
                fileCount = base.fileCount + (overlay?.fileCount ?: 0),
                byteCount = base.byteCount + (overlay?.byteCount ?: 0),
            )
        }
    }

    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var queued by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // Base layer status — built-in satellite, nothing to configure.
    Text("Satellite base · built-in", style = type.bodyBold, color = colors.textPrimary)
    Text(
        "Esri World Imagery — shows whenever the device is online; " +
            "downloaded tiles keep working offline.",
        style = type.caption,
        color = colors.textSecondary,
    )
    HorizontalDivider(color = colors.divider)

    // Overlay layer status — the user's XYZ template drawn on top.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Overlay · your template", style = type.bodyBold, color = colors.textPrimary)
            if (template == null) {
                Text(
                    "None configured — paste an XYZ template (contour or " +
                        "forest tiles) under Settings → Basemap tiles.",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            } else {
                Text(
                    label ?: template,
                    style = type.dataSmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (template != null) {
            Switch(checked = overlayEnabled, onCheckedChange = onOverlayEnabled)
        }
    }
    if (template == null) {
        SheetButton(
            "Open Settings",
            primary = false,
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenSettings,
        )
    }
    HorizontalDivider(color = colors.divider)

    if (running) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Downloading $done / $queued",
                style = type.data,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            SheetButton("Cancel", primary = false) { downloadJob?.cancel() }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(ForestixRadius.chip)
                .background(colors.surfaceRaised),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(if (queued > 0) done / queued.toFloat() else 0f)
                    .fillMaxHeight()
                    .background(colors.primary),
            )
        }
    } else {
        SheetButton(
            "Download visible area",
            primary = true,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val bounds = camera.visibleBounds()
            if (bounds == null) {
                note = "Map not laid out yet — try again."
            } else {
                note = null
                downloadJob = scope.launch {
                    running = true
                    var saved = 0
                    var failed = 0
                    try {
                        // Plan BOTH layers so one tap covers the whole map.
                        val plans = withContext(Dispatchers.IO) {
                            listOfNotNull(baseFetcher, overlayFetcher).map { fetcher ->
                                fetcher to OfflineBasemap.planJob(
                                    aoiRings = listOf(bounds), cache = fetcher.cache)
                            }
                        }
                        val remaining = plans.sumOf { it.second.remaining }
                        val totalTiles = plans.sumOf { it.second.totalTiles }
                        val alreadyCached = plans.sumOf { it.second.alreadyCached }
                        when {
                            remaining == 0 -> note =
                                "All $totalTiles tiles for this area are already cached."

                            remaining > MaxPlannedTiles -> note =
                                "Area too large ($remaining tiles) — zoom in and try again."

                            else -> {
                                queued = remaining
                                done = 0
                                for ((fetcher, plan) in plans) {
                                    for (key in plan.tiles) {
                                        if (fetcher.prefetch(key)) saved++ else failed++
                                        done++
                                    }
                                }
                                note = buildString {
                                    append("Saved $saved of $remaining tiles")
                                    if (alreadyCached > 0) append(" · $alreadyCached already cached")
                                    if (failed > 0) append(" · $failed failed")
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        note = "Cancelled — kept $saved downloaded tiles."
                        throw e
                    } finally {
                        running = false
                        statsTick++
                    }
                }
            }
        }
        Text(
            "Covers the visible map — satellite base plus overlay when set — " +
                "at zoom ${OfflineBasemap.defaultZoomRange.first}–" +
                "${OfflineBasemap.defaultZoomRange.last} plus a 1 km buffer.",
            style = type.caption,
            color = colors.textSecondary,
        )
        note?.let { Text(it, style = type.caption, color = colors.textSecondary) }
    }

    HorizontalDivider(color = colors.divider)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Tile cache", style = type.bodyBold, color = colors.textPrimary)
            Text(
                stats?.let { "${it.fileCount} tiles · ${formatBytes(it.byteCount)} · both layers" } ?: "…",
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        if (!running) {
            SheetButton("Clear", primary = false) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        baseFetcher.cache.clear()
                        overlayFetcher?.cache?.clear()
                    }
                    statsTick++
                    note = null
                }
            }
        }
    }
}

// MARK: - Pieces

@Composable
private fun SheetButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val shape = ForestixRadius.control
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(
                if (primary) Modifier.background(colors.primary)
                else Modifier.border(1.dp, colors.divider, shape)
            )
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Forestix.type.bodyBold.copy(fontSize = 14.sp),
            color = if (primary) MaterialTheme.colorScheme.onPrimary else colors.textPrimary,
            modifier = Modifier.padding(horizontal = ForestixSpace.md),
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024 -> String.format(Locale.US, "%.0f KB", bytes / 1_024.0)
    else -> "$bytes B"
}
