// Map settings sheet — the ONE place the map's layer and offline controls
// live, opened from the map's top-right chrome button. Three groups, in
// the order the cruiser sketched them:
//
//   Map type          two selectable cards, Normal (OpenStreetMap) /
//                     Satellite (Esri) — persisted as tc.mapType, default
//                     satellite so nothing changes for existing installs.
//   Survey boundary   current-state row + "Import boundary file"; SHP
//                     (.shp + .prj, or .zip), KML/KMZ, GeoJSON. WGS84 only
//                     — a refusal is shown inline, never swallowed.
//   Offline maps      the existing download + cache UI, moved in whole
//                     (OfflineMapSheet.OfflineMapsSection).
//
// The custom XYZ overlay template stays in Settings → Advanced → Basemap
// tiles; it is a provider configuration, not a per-trip map control.

package com.hcjeong.forestix.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.hcjeong.forestix.geo.BoundaryFilePicker
import com.hcjeong.forestix.geo.BoundaryImportError
import com.hcjeong.forestix.geo.BoundaryImporter
import com.hcjeong.forestix.geo.SurveyBoundaryStore
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapSettingsSheet(
    camera: MapCameraState,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Full-height, fully expanded from the start — field report:
        // opening half-collapsed hid the download button.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvas,
    ) {
        Column(Modifier.fillMaxHeight()) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "Map settings",
                    style = type.bodyBold.copy(fontSize = 17.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Text("Close", style = type.body, color = colors.primary)
                }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ForestixSpace.md)
                    .padding(top = ForestixSpace.xs, bottom = ForestixSpace.xl),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
            ) {
                MapTypeGroup()
                SurveyBoundaryGroup()
                OfflineMapsSection(camera = camera)
            }
        }
    }
}

// MARK: - Map type

@Composable
private fun MapTypeGroup() {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()

    MapSheetGroup(header = "Map type", footer = null, card = false) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) {
            MapTypeCard(
                label = "Normal",
                icon = Icons.Filled.Map,
                selected = settings.mapType == "normal",
                modifier = Modifier.weight(1f),
            ) { env.settings.setMapType("normal") }
            MapTypeCard(
                label = "Satellite",
                icon = Icons.Filled.Public,
                selected = settings.mapType != "normal",
                modifier = Modifier.weight(1f),
            ) { env.settings.setMapType("satellite") }
        }
    }
}

@Composable
private fun MapTypeCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier
            .clip(ForestixRadius.card)
            .background(if (selected) colors.primaryMuted else colors.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.divider,
                shape = ForestixRadius.card,
            )
            .clickableNoRipple(onClick)
            .padding(vertical = ForestixSpace.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xxs),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) colors.primary else colors.textSecondary,
            modifier = Modifier.size(26.dp),
        )
        Text(
            label,
            style = if (selected) type.bodyBold else type.body,
            color = if (selected) colors.textPrimary else colors.textSecondary,
        )
    }
}

// MARK: - Survey boundary

@Composable
private fun SurveyBoundaryGroup() {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffectLoad(context)
    val stored by SurveyBoundaryStore.state.collectAsStateWithLifecycle()
    var failure by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        failure = null
        importing = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val picked = BoundaryFilePicker.read(context, uri)
                    val boundary = BoundaryImporter.import(
                        fileName = picked.fileName,
                        bytes = picked.bytes,
                        sidecarPrj = picked.sidecarPrj,
                    )
                    SurveyBoundaryStore.save(
                        context = context,
                        boundary = boundary,
                        sourceFormat = picked.fileName.substringAfterLast('.', "").lowercase(),
                    )
                    null
                } catch (e: BoundaryImportError) {
                    e.message ?: "That boundary could not be imported."
                } catch (e: Exception) {
                    "That boundary could not be imported (${e.message ?: "unknown error"})."
                }
            }
            failure = outcome
            importing = false
        }
    }

    MapSheetGroup(header = "Survey boundary", footer = BoundaryImporter.FORMAT_HINT) {
        // Current-state row: what is loaded right now, or that nothing is.
        Row(
            Modifier.fillMaxWidth().padding(ForestixSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            Icon(
                Icons.Filled.Layers,
                contentDescription = null,
                tint = if (stored == null) colors.textTertiary else colors.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    stored?.displayName ?: "No boundary loaded",
                    style = if (stored == null) type.body else type.bodyBold,
                    color = if (stored == null) colors.textSecondary else colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                stored?.let { boundary ->
                    Text(
                        featureSummary(boundary.featureCount, boundary.importedAtMillis),
                        style = type.dataSmall,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (stored != null) {
                Text(
                    "Remove",
                    style = type.body,
                    color = colors.confidenceBad,
                    modifier = Modifier
                        .clickableNoRipple {
                            failure = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    SurveyBoundaryStore.clear(context)
                                }
                            }
                        }
                        .padding(horizontal = ForestixSpace.xxs),
                )
            }
        }

        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

        // Primary action.
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple {
                    if (!importing) picker.launch(BoundaryFilePicker.OPEN_DOCUMENT_TYPES)
                }
                .padding(ForestixSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            Icon(
                Icons.Outlined.FileOpen,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                if (importing) "Reading file…" else "Import boundary file",
                style = type.body,
                color = colors.primary,
            )
        }

        // A refusal is never swallowed — it stays until the next attempt.
        failure?.let { message ->
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            Text(
                message,
                style = type.caption,
                color = colors.confidenceBad,
                modifier = Modifier.fillMaxWidth().padding(ForestixSpace.sm),
            )
        }
    }
}

/// One-shot load of whatever survived the last run.
@Composable
private fun LaunchedEffectLoad(context: android.content.Context) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { SurveyBoundaryStore.loadIfNeeded(context) }
    }
}

private fun featureSummary(count: Int, importedAtMillis: Long): String {
    val features = if (count == 1) "1 feature" else "$count features"
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(importedAtMillis))
    return "$features · imported $date"
}

// MARK: - Shared group chrome

/// Grouped card section — sectionHead header, surface card with
/// hairline-divided rows, caption footer (the iOS insetGrouped Form look).
/// `card = false` drops the surface so a group can lay out its own tiles.
@Composable
internal fun MapSheetGroup(
    header: String,
    footer: String?,
    card: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(Modifier.fillMaxWidth()) {
        Text(
            header,
            style = type.sectionHead,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = ForestixSpace.sm, bottom = 6.dp),
        )
        if (card) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ForestixRadius.card)
                    .background(colors.surface),
            ) {
                content()
            }
        } else {
            Column(Modifier.fillMaxWidth()) { content() }
        }
        footer?.let {
            Text(
                it,
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(
                    start = ForestixSpace.sm, top = 6.dp, end = ForestixSpace.sm),
            )
        }
    }
}
