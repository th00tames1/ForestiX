// Offline maps — the LAST group of the Map settings sheet (MapSettingsSheet).
// It was a sheet of its own until the map's scattered layer/offline controls
// were consolidated; the download engine here is unchanged, only its host
// moved.
//
// "Download visible area" plans the EXACT visible bbox at zoom 12–17 for the
// Satellite base plus the user overlay when it is on and acknowledged,
// drains the combined queue with a live failed count and a destructive
// cancel, and reports per-layer cache stats with a destructive clear. The
// combined tile cap is unchanged.
//
// NOT for the built-in Normal base: OpenStreetMap's Tile Usage Policy
// prohibits bulk/systematic downloading, and a correct User-Agent does not
// exempt it — doing it anyway gets the app blocked for every user. With
// Normal selected the download control is replaced by a plain explanation;
// a cruiser's OWN tile provider stays downloadable (their provider, their
// terms), and viewing OSM tiles live is unaffected.

package com.hcjeong.forestix.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.basemap.MapCameraState
import com.hcjeong.forestix.basemap.OfflineBasemap
import com.hcjeong.forestix.basemap.TileCache
import com.hcjeong.forestix.basemap.TileFetcher
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/// Refuse plans bigger than this — a zoomed-out viewport at zoom 12–17
/// explodes into millions of tiles; field areas stay well under the cap.
/// Applied to the COMBINED base + overlay count.
private const val MaxPlannedTiles = 4_000

/// Shown in place of the download control while the built-in Normal base
/// is selected — OSM's Tile Usage Policy forbids bulk downloading.
private const val NormalMapOfflineNotice =
    "Offline download isn't available for the Normal map — OpenStreetMap's " +
        "tile policy doesn't allow bulk downloads. Switch to Satellite, or " +
        "set your own tile provider in Settings › Basemap tiles."

/// Download lifecycle, mirroring the iOS OfflineTileDownloader.Phase
/// (idle / running / tooLarge / finished — `cancelled` flips the finished
/// title to the kept-tiles line).
private sealed interface DownloadPhase {
    data object Idle : DownloadPhase
    data class Running(val done: Int, val total: Int, val failed: Int) : DownloadPhase
    data class TooLarge(val planned: Int) : DownloadPhase
    data class Finished(
        val fetched: Int,
        val failed: Int,
        val alreadyCached: Int,
        val cancelled: Boolean = false,
    ) : DownloadPhase
}

@Composable
internal fun OfflineMapsSection(camera: MapCameraState) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()

    val template = settings.tileURLTemplate
    val acknowledged = settings.providerUsageAcknowledged
    // The base the map is actually drawing — download and cache stats must
    // follow the map type, or the cruiser caches tiles they never see.
    val baseFetcher = remember(settings.mapType) {
        TileFetcher.builtInBase(context, settings.mapType)
    }
    // Overlay cache exists only once the cruiser pasted a template AND
    // acknowledged the provider's usage policy (iOS makeOverlayTileCache).
    // The on/off toggle is applied where tiles are fetched, not here, so
    // the section still shows stats for a toggled-off overlay.
    val overlayFetcher = remember(template, acknowledged) {
        if (acknowledged) template?.let { TileFetcher(context, it) } else null
    }

    var baseStats by remember { mutableStateOf<TileCache.Stats?>(null) }
    var overlayStats by remember { mutableStateOf<TileCache.Stats?>(null) }
    var statsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(baseFetcher, overlayFetcher, statsTick) {
        withContext(Dispatchers.IO) {
            val base = baseFetcher.cache.stats()
            val overlay = overlayFetcher?.cache?.stats()
            baseStats = base
            overlayStats = overlay
        }
    }

    var phase by remember { mutableStateOf<DownloadPhase>(DownloadPhase.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    // The built-in Normal base is OpenStreetMap, whose Tile Usage Policy
    // prohibits bulk downloading — so it is never queued. Satellite is,
    // and so is the cruiser's OWN provider when it is configured, on and
    // acknowledged: their provider, their terms.
    val baseDownloadable = settings.mapType != "normal"
    val overlayDownloadable = overlayFetcher != null && settings.overlayEnabled
    val canDownload = baseDownloadable || overlayDownloadable

    MapSheetGroup(
        header = "Offline maps",
        footer = "Downloads the visible area (the selected base plus any " +
            "overlay) for offline use. Max $MaxPlannedTiles tiles — zoom in " +
            "if the area is too large.",
    ) {
        val launchDownload: () -> Unit = {
            startDownload(
                scope = scope,
                camera = camera,
                baseFetcher = if (baseDownloadable) baseFetcher else null,
                overlayFetcher = if (overlayDownloadable) overlayFetcher else null,
                setPhase = { phase = it },
                setJob = { downloadJob = it },
                bumpStats = { statsTick++ },
            )
        }

        // OSM policy: no download control for the built-in Normal base —
        // a plain explanation stands in its place.
        if (!baseDownloadable) {
            Text(
                NormalMapOfflineNotice,
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(ForestixSpace.sm),
            )
            if (canDownload) HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        }

        when (val p = phase) {
            is DownloadPhase.Running -> {
                Column(Modifier.fillMaxWidth().padding(ForestixSpace.sm)) {
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.done / p.total.toFloat() else 0f },
                        color = colors.primary,
                        trackColor = colors.surfaceRaised,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.padding(top = ForestixSpace.xxs)) {
                        Text(
                            "${p.done} / ${p.total} tiles",
                            style = type.dataSmall,
                            color = colors.textSecondary,
                        )
                        if (p.failed > 0) {
                            Text(
                                " · ${p.failed} failed",
                                style = type.dataSmall,
                                color = colors.confidenceWarn,
                            )
                        }
                    }
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                Text(
                    "Cancel download",
                    style = type.body,
                    color = colors.confidenceBad,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { downloadJob?.cancel() }
                        .padding(ForestixSpace.sm),
                )
            }

            is DownloadPhase.TooLarge -> {
                Text(
                    "Area too large (${p.planned} tiles) — zoom in and try again.",
                    style = type.caption,
                    color = colors.confidenceWarn,
                    modifier = Modifier.fillMaxWidth().padding(ForestixSpace.sm),
                )
                if (canDownload) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    DownloadRow(camera, launchDownload)
                }
            }

            is DownloadPhase.Finished -> {
                Column(Modifier.fillMaxWidth().padding(ForestixSpace.sm)) {
                    Text(
                        when {
                            p.cancelled -> "Cancelled — kept ${p.fetched} downloaded tiles."
                            p.fetched == 0 && p.failed == 0 ->
                                "Nothing to fetch — area already cached"
                            else -> "Downloaded ${p.fetched} tiles"
                        },
                        style = type.bodyBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        finishDetail(failed = p.failed, alreadyCached = p.alreadyCached),
                        style = type.caption,
                        color = if (p.failed > 0) colors.confidenceWarn else colors.textSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (canDownload) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    DownloadRow(camera, launchDownload)
                }
            }

            DownloadPhase.Idle -> if (canDownload) DownloadRow(camera, launchDownload)
        }

        // Cache — per-layer stats plus the destructive clear.
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        CacheRow("Base map", baseStats)
        if (overlayFetcher != null) {
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            CacheRow("Overlay", overlayStats)
        }
        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
        val storedTiles = (baseStats?.fileCount ?: 0) + (overlayStats?.fileCount ?: 0)
        val clearEnabled = storedTiles > 0
        Text(
            "Clear cache",
            style = type.body,
            color = colors.confidenceBad,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (clearEnabled) 1f else 0.45f)
                .then(
                    if (clearEnabled) {
                        Modifier.clickableNoRipple {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    baseFetcher.cache.clear()
                                    overlayFetcher?.cache?.clear()
                                }
                                statsTick++
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                .padding(ForestixSpace.sm),
        )
    }
}

// MARK: - Download plumbing

/// "Download visible area" list-row button (iOS Label + arrow.down.circle),
/// disabled until the map has laid out and produced a camera.
@Composable
private fun DownloadRow(camera: MapCameraState, onClick: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    val enabled = camera.visibleBounds() != null
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (enabled) Modifier.clickableNoRipple(onClick) else Modifier)
            .padding(ForestixSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        Icon(
            Icons.Outlined.ArrowCircleDown,
            contentDescription = null,
            tint = colors.primary,
            modifier = Modifier.size(20.dp),
        )
        Text("Download visible area", style = type.body, color = colors.primary)
    }
}

/// Plan the DOWNLOADABLE layers over the EXACT visible bbox (buffer 0, iOS
/// semantics) and drain the combined queue sequentially with live x/total +
/// failed progress. Guarded at MaxPlannedTiles combined. A null fetcher is a
/// layer that must not be bulk-downloaded (the built-in OSM base) — it is
/// simply absent from the plan.
private fun startDownload(
    scope: kotlinx.coroutines.CoroutineScope,
    camera: MapCameraState,
    baseFetcher: TileFetcher?,
    overlayFetcher: TileFetcher?,
    setPhase: (DownloadPhase) -> Unit,
    setJob: (Job?) -> Unit,
    bumpStats: () -> Unit,
) {
    val bounds = camera.visibleBounds() ?: return
    setJob(scope.launch {
        var saved = 0
        var failed = 0
        var cancelled = false
        var cached = 0
        try {
            val plans = withContext(Dispatchers.IO) {
                listOfNotNull(baseFetcher, overlayFetcher).map { fetcher ->
                    fetcher to OfflineBasemap.planJob(
                        aoiRings = listOf(bounds),
                        bufferMeters = 0.0,
                        cache = fetcher.cache,
                    )
                }
            }
            val remaining = plans.sumOf { it.second.remaining }
            cached = plans.sumOf { it.second.alreadyCached }
            when {
                remaining == 0 -> {
                    setPhase(DownloadPhase.Finished(
                        fetched = 0, failed = 0, alreadyCached = cached))
                }

                remaining > MaxPlannedTiles -> {
                    setPhase(DownloadPhase.TooLarge(planned = remaining))
                }

                else -> {
                    var done = 0
                    setPhase(DownloadPhase.Running(done = 0, total = remaining, failed = 0))
                    for ((fetcher, plan) in plans) {
                        for (key in plan.tiles) {
                            if (fetcher.prefetch(key)) saved++ else failed++
                            done++
                            setPhase(DownloadPhase.Running(
                                done = done, total = remaining, failed = failed))
                        }
                    }
                    setPhase(DownloadPhase.Finished(
                        fetched = saved, failed = failed, alreadyCached = cached))
                }
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } finally {
            if (cancelled) {
                setPhase(DownloadPhase.Finished(
                    fetched = saved, failed = failed,
                    alreadyCached = cached, cancelled = true))
            }
            bumpStats()
        }
    })
}

/// iOS finishDetail: "K failed — try again in coverage · M were already
/// cached".
private fun finishDetail(failed: Int, alreadyCached: Int): String {
    val parts = mutableListOf<String>()
    if (failed > 0) parts.add("$failed failed — try again in coverage")
    parts.add("$alreadyCached were already cached")
    return parts.joinToString(" · ")
}

@Composable
private fun CacheRow(label: String, stats: TileCache.Stats?) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    Row(
        Modifier.fillMaxWidth().padding(ForestixSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            stats?.let {
                "${it.fileCount} tiles · ${Formatter.formatShortFileSize(context, it.byteCount)}"
            } ?: "—",
            style = type.dataSmall,
            color = colors.textSecondary,
        )
    }
}
