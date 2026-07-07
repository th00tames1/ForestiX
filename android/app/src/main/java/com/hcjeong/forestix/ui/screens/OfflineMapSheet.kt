// Offline basemap sheet — target of the map home's layers button (mock
// design/forestix-redesign-v2-maphome.html ① `.roundbtn` layers).
//
// With a provider configured: shows its status, plans OfflineBasemap.planJob
// over the camera's visibleBounds() and drains the queue sequentially
// through TileFetcher.prefetch with x/total progress + cancel, and offers
// TileCache stats / clear. Without one: the no-tile state is first-class —
// it explains the no-default-provider policy and points at Settings'
// basemap section. Dismissing the sheet cancels an in-flight download.

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
    val template = settings.tileURLTemplate

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
            if (template == null) {
                NoProviderContent(onOpenSettings)
            } else {
                ProviderContent(
                    template = template,
                    label = settings.tileProviderLabel,
                    camera = camera,
                )
            }
        }
    }
}

// MARK: - No provider (policy explainer)

@Composable
private fun NoProviderContent(onOpenSettings: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Text("No tile provider configured", style = type.bodyBold, color = colors.textPrimary)
    Text(
        "ForestiX ships no default basemap — tile providers set their own " +
            "usage policies. Pins and measurements work fine on the plain " +
            "canvas; to see and download real tiles, paste an XYZ template " +
            "under Settings → Basemap tiles.",
        style = type.caption,
        color = colors.textSecondary,
    )
    SheetButton(
        "Open Settings",
        primary = true,
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenSettings,
    )
}

// MARK: - Provider configured (status + download + cache)

@Composable
private fun ProviderContent(template: String, label: String?, camera: MapCameraState) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val fetcher = remember(template) { TileFetcher(context, template) }

    var stats by remember { mutableStateOf<TileCache.Stats?>(null) }
    var statsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(fetcher, statsTick) {
        stats = withContext(Dispatchers.IO) { fetcher.cache.stats() }
    }

    var running by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var queued by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // Provider status (template/label straight from settings).
    Text(label ?: "Custom provider", style = type.bodyBold, color = colors.textPrimary)
    Text(
        template,
        style = type.dataSmall,
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
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
                        val job = withContext(Dispatchers.IO) {
                            OfflineBasemap.planJob(aoiRings = listOf(bounds), cache = fetcher.cache)
                        }
                        when {
                            job.isEmpty -> note =
                                "All ${job.totalTiles} tiles for this area are already cached."

                            job.remaining > MaxPlannedTiles -> note =
                                "Area too large (${job.remaining} tiles) — zoom in and try again."

                            else -> {
                                queued = job.remaining
                                done = 0
                                for (key in job.tiles) {
                                    if (fetcher.prefetch(key)) saved++ else failed++
                                    done++
                                }
                                note = buildString {
                                    append("Saved $saved of ${job.remaining} tiles")
                                    if (job.alreadyCached > 0) append(" · ${job.alreadyCached} already cached")
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
            "Covers the visible map at zoom " +
                "${OfflineBasemap.defaultZoomRange.first}–${OfflineBasemap.defaultZoomRange.last} " +
                "plus a 1 km buffer.",
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
                stats?.let { "${it.fileCount} tiles · ${formatBytes(it.byteCount)}" } ?: "…",
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        if (!running) {
            SheetButton("Clear", primary = false) {
                scope.launch {
                    withContext(Dispatchers.IO) { fetcher.cache.clear() }
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
