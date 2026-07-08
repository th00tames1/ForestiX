// Port of iOS Screens/TreeDetailScreen.swift.
// Phase 5 §5.3 TreeDetailScreen. REQ-TAL-006.
//
// Single-tree inspector: editable primary fields, soft-delete / undelete via
// button, and a read-only "Raw metadata" section auditors can inspect.

package com.hcjeong.forestix.ui.screens.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

// MARK: - Loader

@Composable
fun TreeDetailScreen(nav: NavController, treeId: String) {
    val env = LocalAppEnvironment.current
    var viewModel by remember { mutableStateOf<TreeDetailViewModel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(treeId) {
        try {
            val tree = env.treeRepository.read(UUID.fromString(treeId), includeDeleted = true)
                ?: throw IllegalStateException("Tree not found")
            viewModel = TreeDetailViewModel(tree, env.treeRepository)
        } catch (e: Exception) {
            loadError = "Failed to load tree: ${e.message ?: e}"
        }
    }

    val vm = viewModel
    if (vm == null) {
        ForestixScaffold(nav, title = "Tree") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                loadError?.let { Text(it, style = Forestix.type.body, color = Forestix.colors.confidenceBad) }
                    ?: CircularProgressIndicator()
            }
        }
        return
    }
    TreeDetailContent(nav, vm)
}

// MARK: - Content

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TreeDetailContent(nav: NavController, viewModel: TreeDetailViewModel) {
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    val tree by viewModel.tree.collectAsState()
    val speciesCode by viewModel.speciesCode.collectAsState()
    val status by viewModel.status.collectAsState()
    val dbhCm by viewModel.dbhCm.collectAsState()
    val dbhIsIrregular by viewModel.dbhIsIrregular.collectAsState()
    val heightM by viewModel.heightM.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val bearing by viewModel.bearingFromCenterDeg.collectAsState()
    val distance by viewModel.distanceFromCenterM.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val dirty by viewModel.dirty.collectAsState()

    val isDeleted = tree.deletedAt != null

    ForestixScaffold(nav, title = "Tree #${tree.treeNumber}") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            if (isDeleted) {
                DetailSection {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Delete, contentDescription = null,
                            tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "This tree is soft-deleted — it is excluded from all statistics.",
                            style = type.body,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // MARK: Identity
            DetailSection(header = "Identity") {
                OutlinedTextField(
                    value = speciesCode,
                    onValueChange = {
                        viewModel.speciesCode.value = it
                        viewModel.markDirty()
                    },
                    label = { Text("Species code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TreeStatusOptions.forEach { (label, value) ->
                        FilterChip(
                            selected = status == value,
                            onClick = {
                                viewModel.status.value = value
                                viewModel.markDirty()
                            },
                            label = { Text(label) },
                        )
                    }
                }
                if (tree.isMultistem) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CallSplit, contentDescription = null,
                            tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Multistem child", style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            // MARK: DBH
            DetailSection(header = "DBH") {
                DetailNumberField(
                    value = dbhCm.takeIf { it > 0f },
                    onValue = {
                        viewModel.dbhCm.value = it ?: 0f
                        viewModel.markDirty()
                    },
                    placeholder = "0.0",
                    unit = "cm",
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Irregular", style = type.body, color = colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    Switch(
                        checked = dbhIsIrregular,
                        onCheckedChange = {
                            viewModel.dbhIsIrregular.value = it
                            viewModel.markDirty()
                        })
                }
                DetailLabelRow("Method", tree.dbhMethod.raw)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Confidence", style = type.body, color = colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    DetailTierBadge(tree.dbhConfidence)
                }
            }

            // MARK: Height
            DetailSection(header = "Height") {
                DetailNumberField(
                    value = heightM,
                    onValue = {
                        viewModel.heightM.value = if (it != null && it > 0f) it else null
                        viewModel.markDirty()
                    },
                    placeholder = "-",
                    unit = "m",
                )
                tree.heightSource?.let { src ->
                    DetailLabelRow("Source", src)
                }
                tree.heightConfidence?.let { tier ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Confidence", style = type.body, color = colors.textPrimary)
                        Spacer(Modifier.weight(1f))
                        DetailTierBadge(tier)
                    }
                }
            }

            // MARK: Placement
            DetailSection(header = "Placement") {
                DetailNumberField(
                    value = bearing,
                    onValue = {
                        viewModel.bearingFromCenterDeg.value = it
                        viewModel.markDirty()
                    },
                    placeholder = "Bearing",
                    unit = "°",
                )
                DetailNumberField(
                    value = distance,
                    onValue = {
                        viewModel.distanceFromCenterM.value = it
                        viewModel.markDirty()
                    },
                    placeholder = "Distance",
                    unit = "m",
                )
            }

            // MARK: Notes
            DetailSection(header = "Notes") {
                OutlinedTextField(
                    value = notes,
                    onValueChange = {
                        viewModel.notes.value = it
                        viewModel.markDirty()
                    },
                    placeholder = { Text("Notes") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // MARK: Raw metadata
            DetailSection(header = "Raw metadata (read-only)") {
                MetaRow("dbhSigmaMm", tree.dbhSigmaMm?.let { String.format(Locale.US, "%.2f", it) })
                MetaRow("dbhRmseMm", tree.dbhRmseMm?.let { String.format(Locale.US, "%.2f", it) })
                MetaRow("dbhCoverageDeg", tree.dbhCoverageDeg?.let { String.format(Locale.US, "%.1f", it) })
                MetaRow("dbhNInliers", tree.dbhNInliers?.toString())
                MetaRow("heightSigmaM", tree.heightSigmaM?.let { String.format(Locale.US, "%.2f", it) })
                MetaRow("heightDHM", tree.heightDHM?.let { String.format(Locale.US, "%.2f", it) })
                MetaRow("heightAlphaTopDeg", tree.heightAlphaTopDeg?.let { String.format(Locale.US, "%.2f", it) })
                MetaRow("heightAlphaBaseDeg", tree.heightAlphaBaseDeg?.let { String.format(Locale.US, "%.2f", it) })
                MetaRow("createdAt", Instant.ofEpochMilli(tree.createdAt).toString())
                MetaRow("updatedAt", Instant.ofEpochMilli(tree.updatedAt).toString())
            }

            // MARK: Actions
            ForestixProminentButton(
                label = "Save changes",
                enabled = dirty && !isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                scope.launch {
                    viewModel.save()
                    if (viewModel.errorMessage.value == null && !viewModel.dirty.value) {
                        nav.popBackStack()
                    }
                }
            }

            if (isDeleted) {
                ForestixBorderedButton(
                    label = "Undelete",
                    icon = Icons.AutoMirrored.Filled.Undo,
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { viewModel.undelete() } }
            } else {
                // iOS `role: .destructive` bordered button — red label.
                ForestixBorderedButton(
                    label = "Soft delete",
                    icon = Icons.Filled.Delete,
                    tint = colors.confidenceBad,
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { viewModel.softDelete() } }
            }
        }
    }

    // Error alert (iOS `.alert("Error", ...)`).
    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            confirmButton = { TextButton(onClick = { viewModel.clearError() }) { Text("OK") } },
            title = { Text("Error") },
            text = { Text(message) },
        )
    }
}

private val TreeStatusOptions = listOf(
    "Live" to TreeStatus.LIVE,
    "Dead standing" to TreeStatus.DEAD_STANDING,
    "Dead down" to TreeStatus.DEAD_DOWN,
    "Cull" to TreeStatus.CULL,
)

// MARK: - Section + row helpers

@Composable
private fun DetailSection(header: String? = null, content: @Composable () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        header?.let {
            Text(
                it.uppercase(Locale.US),
                style = type.sectionHead,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = ForestixSpace.xs),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            content()
        }
    }
}

@Composable
private fun DetailLabelRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.caption, color = colors.textSecondary)
    }
}

@Composable
private fun MetaRow(label: String, value: String?) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.dataSmall, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value ?: "—", style = type.dataSmall, color = colors.textSecondary)
    }
}

@Composable
private fun DetailTierBadge(tier: ConfidenceTier) {
    val descriptor = confidenceDescriptor(tier.raw)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(descriptor.color))
        Text(
            tier.raw.replaceFirstChar { it.uppercase() },
            style = Forestix.type.caption,
            color = descriptor.color,
        )
    }
}

/// Numeric entry with trailing unit label (same local-text pattern as
/// AddTreeFlowScreen.NumberFieldRow).
@Composable
private fun DetailNumberField(
    value: Float?,
    onValue: (Float?) -> Unit,
    placeholder: String,
    unit: String,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    var text by remember { mutableStateOf(value?.let { detailFormatNumber(it) } ?: "") }
    LaunchedEffect(value) {
        val parsed = text.toFloatOrNull()
        val matches = when {
            value == null -> parsed == null || parsed == 0f
            else -> parsed != null && kotlin.math.abs(parsed - value) < 1e-4f
        }
        if (!matches) {
            text = value?.let { detailFormatNumber(it) } ?: ""
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValue(it.toFloatOrNull())
            },
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = type.data,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(unit, style = type.body, color = colors.textSecondary)
    }
}

private fun detailFormatNumber(v: Float): String =
    if (v == v.toLong().toFloat()) String.format(Locale.US, "%d", v.toLong())
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
