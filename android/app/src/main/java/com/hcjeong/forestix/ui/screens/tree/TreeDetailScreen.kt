// Port of iOS Screens/TreeDetailScreen.swift.
// Phase 5 §5.3 TreeDetailScreen. REQ-TAL-006.
//
// Single-tree inspector.
//
// FIELD REPORT F8 — rebuilt for readability. What changed and why:
//   • DBH and Height are no longer two sections with their own headers and
//     their own sub-rows. They are two rows in ONE "Measurements" group:
//     label left, value + unit right. Every other measured quantity in the
//     group gets the same row treatment, so the eye reads one column of
//     labels and one column of numbers.
//   • The "Method" readout is gone. `lidarDepth` / `vioWalkoffTangent`
//     told a cruiser nothing; it is still on the record and in exports.
//   • Confidence stays, and the tier chip is now TAPPABLE — it opens a
//     plain-language explanation of what the tiers mean and what actually
//     drives them, so the criteria are learnable in the field instead of
//     being folklore.
//   • "Irregular" is gone (nobody set it; the flag is untouched on the model).
//   • "Placement" (bearing / distance from plot centre) is gone. Both were
//     hand-typed number fields, and a cruiser standing at a tree has no way
//     to type a meaningful bearing. The FIELDS SURVIVE on `Tree` and in every
//     export — the cruise chain still auto-computes `distanceFromCenterM`
//     from the capture fix. Only the manual UI went.
//   • Species is a PICKER over the active country/region list (name-first),
//     with a free-typed escape hatch for a code that isn't in the list, and
//     the resolved common name shown next to whatever is chosen.
//
// FIELD REPORT F6 — the read-only raw-metadata block is developer-mode only.
// A normal cruiser never sees `dbhCoverageDeg` / `heightAlphaTopDeg`.

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.CountrySpecies
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.screens.speciesPickerLabel
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

    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()

    val tree by viewModel.tree.collectAsState()
    val speciesCode by viewModel.speciesCode.collectAsState()
    val status by viewModel.status.collectAsState()
    val dbhCm by viewModel.dbhCm.collectAsState()
    val heightM by viewModel.heightM.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val dirty by viewModel.dirty.collectAsState()

    val isDeleted = tree.deletedAt != null

    // Which measurement's tier explainer is open. null = closed.
    var explaining by remember { mutableStateOf<TierExplainerKind?>(null) }

    // Species offered by the ACTIVE country (and, for the US, region), plus
    // the same "OT · Other" catch-all the scan-time sheet carries.
    val speciesOptions = remember(settings.country, settings.region) {
        val country = settings.country
        val base = if (country.hasRegions) {
            RegionalSpecies.defaultSpecies(Region.fromRaw(settings.region) ?: Region.ALL)
        } else {
            CountrySpecies.defaultSpecies(country)
        }
        base + ("OT" to "Other")
    }
    val trimmedCode = speciesCode.trim()
    // The preset entry whose code matches case-insensitively, so a stored
    // "df" still selects "Douglas-fir · DF".
    val presetCode = remember(speciesOptions, trimmedCode) {
        speciesOptions.firstOrNull { it.first.equals(trimmedCode, ignoreCase = true) }?.first
    }
    // True once the cruiser has chosen "Other code…", or on first composition
    // when the stored code isn't in the active country/region list — imported
    // data and codes typed under another region must stay editable rather
    // than silently snapping to the first preset.
    var freeTextCode by remember {
        mutableStateOf(trimmedCode.isNotEmpty() && presetCode == null)
    }

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
                            "Removed from the tally — this tree is left out of every total. " +
                                "You can put it back below.",
                            style = type.body,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // MARK: Identity
            DetailSection(header = "Identity") {
                // Species is PICKED, not typed. A cruiser knows the tree by
                // name, not by its FIA code, so the list reads name-first
                // with the code trailing dim.
                var speciesMenuOpen by remember { mutableStateOf(false) }
                val selectedLabel: AnnotatedString = when {
                    freeTextCode -> AnnotatedString("Other code…")
                    trimmedCode.isEmpty() -> AnnotatedString("— Unspecified —")
                    else -> speciesOptions.firstOrNull { it.first == presetCode }
                        ?.let { speciesPickerLabel(it.second, it.first, colors.textSecondary) }
                        ?: AnnotatedString(trimmedCode)
                }
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { speciesMenuOpen = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Species", style = type.body, color = colors.textPrimary)
                        Spacer(Modifier.weight(1f))
                        Text(selectedLabel, style = type.body, color = colors.textSecondary)
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            tint = colors.textSecondary,
                        )
                    }
                    DropdownMenu(
                        expanded = speciesMenuOpen,
                        onDismissRequest = { speciesMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("— Unspecified —") },
                            onClick = {
                                speciesMenuOpen = false
                                freeTextCode = false
                                viewModel.speciesCode.value = ""
                                viewModel.markDirty()
                            })
                        speciesOptions.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = {
                                    Text(speciesPickerLabel(name, code, colors.textSecondary))
                                },
                                onClick = {
                                    speciesMenuOpen = false
                                    freeTextCode = false
                                    viewModel.speciesCode.value = code
                                    viewModel.markDirty()
                                })
                        }
                        DropdownMenuItem(
                            text = { Text("Other code…") },
                            onClick = {
                                speciesMenuOpen = false
                                // Hand the field over to the cruiser; don't
                                // keep a preset code sitting in it that they'd
                                // have to clear first.
                                freeTextCode = true
                                if (presetCode != null) viewModel.speciesCode.value = ""
                                viewModel.markDirty()
                            })
                    }
                }

                // Escape hatch: a code the country/region list doesn't carry.
                // Typing here stores the code verbatim.
                if (freeTextCode) {
                    OutlinedTextField(
                        value = speciesCode,
                        onValueChange = {
                            viewModel.speciesCode.value = it
                            viewModel.markDirty()
                        },
                        label = { Text("Code") },
                        placeholder = { Text("e.g. ABCO") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // The resolved common name for whatever code is set — the
                // whole point of picking by name is knowing "DF" is a
                // Douglas-fir. `nameForCode` echoes the code back for
                // unknowns, which is how "not in this list" is detected.
                if (trimmedCode.isNotEmpty()) {
                    val resolvedName = RegionalSpecies.nameForCode(trimmedCode)
                    DetailLabelRow(
                        "Name",
                        if (resolvedName.equals(trimmedCode, ignoreCase = true)) {
                            "Not in this region's list"
                        } else {
                            resolvedName
                        },
                    )
                }
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
                        Text("One stem of a multi-stem tree", style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            // MARK: Measurements — ONE group, label left / value + unit right.
            // No Method row (F8), no Irregular switch (F8), no Placement
            // editor (F8 — the fields live on and export unchanged).
            DetailSection(header = "Measurements") {
                DetailValueRow(
                    label = "DBH",
                    value = dbhCm.takeIf { it > 0f },
                    onValue = {
                        viewModel.dbhCm.value = it ?: 0f
                        viewModel.markDirty()
                    },
                    placeholder = "0.0",
                    unit = "cm",
                )
                DetailConfidenceRow(
                    label = "DBH confidence",
                    tier = tree.dbhConfidence,
                ) { explaining = TierExplainerKind.DIAMETER }

                DetailValueRow(
                    label = "Height",
                    value = heightM,
                    onValue = {
                        viewModel.heightM.value = if (it != null && it > 0f) it else null
                        viewModel.markDirty()
                    },
                    placeholder = "—",
                    unit = "m",
                )
                tree.heightConfidence?.let { tier ->
                    DetailConfidenceRow(
                        label = "Height confidence",
                        tier = tier,
                    ) { explaining = TierExplainerKind.HEIGHT }
                }
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

            // MARK: Raw metadata — developer mode only (F6). Raw internals
            // are a developer/audit surface, not a cruiser one.
            if (settings.developerMode) {
                DetailSection(header = "Raw metadata (read-only)") {
                    MetaRow("dbhMethod", tree.dbhMethod.raw)
                    MetaRow("dbhIsIrregular", if (tree.dbhIsIrregular) "true" else "false")
                    MetaRow("dbhSigmaMm", tree.dbhSigmaMm?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("dbhRmseMm", tree.dbhRmseMm?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("dbhCoverageDeg", tree.dbhCoverageDeg?.let { String.format(Locale.US, "%.1f", it) })
                    MetaRow("dbhNInliers", tree.dbhNInliers?.toString())
                    MetaRow("heightSource", tree.heightSource)
                    MetaRow("heightSigmaM", tree.heightSigmaM?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("heightDHM", tree.heightDHM?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("heightAlphaTopDeg", tree.heightAlphaTopDeg?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("heightAlphaBaseDeg", tree.heightAlphaBaseDeg?.let { String.format(Locale.US, "%.2f", it) })
                    // Placement lost its editor (F8) but not its data —
                    // surface it here so an auditor can still read what was
                    // recorded.
                    MetaRow("bearingFromCenterDeg", tree.bearingFromCenterDeg?.let { String.format(Locale.US, "%.1f", it) })
                    MetaRow("distanceFromCenterM", tree.distanceFromCenterM?.let { String.format(Locale.US, "%.2f", it) })
                    MetaRow("createdAt", Instant.ofEpochMilli(tree.createdAt).toString())
                    MetaRow("updatedAt", Instant.ofEpochMilli(tree.updatedAt).toString())
                }
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
                    label = "Put back in tally",
                    icon = Icons.AutoMirrored.Filled.Undo,
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { viewModel.undelete() } }
            } else {
                // iOS `role: .destructive` bordered button — red label.
                ForestixBorderedButton(
                    label = "Remove from tally",
                    icon = Icons.Filled.Delete,
                    tint = colors.confidenceBad,
                    modifier = Modifier.fillMaxWidth(),
                ) { scope.launch { viewModel.softDelete() } }
            }
        }
    }

    // Tier explainer — opened by tapping a confidence chip (F8).
    explaining?.let { kind ->
        TierExplainerSheet(kind) { explaining = null }
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

/// One measured quantity: label left, editable value + unit right (F8).
/// Every row in the Measurements group gets this treatment, so the eye reads
/// one column of labels and one column of numbers.
@Composable
private fun DetailValueRow(
    label: String,
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
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onValue(it.toFloatOrNull())
            },
            placeholder = { Text(placeholder, textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth()) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            textStyle = type.data.copy(textAlign = TextAlign.End),
            modifier = Modifier.width(110.dp),
        )
        Text(unit, style = type.body, color = colors.textSecondary,
            modifier = Modifier.width(22.dp))
    }
}

/// Confidence row. The chip is a BUTTON: tapping it explains the tiers and
/// what drives them for this measurement (F8).
@Composable
private fun DetailConfidenceRow(
    label: String,
    tier: ConfidenceTier,
    onExplain: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onExplain)
            // Names the words the chip actually shows, so what a screen reader
            // announces matches what a sighted cruiser taps.
            .semantics { contentDescription = "$label. Explains what Good, Fair and Check mean" }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        DetailTierBadge(tier)
        // The app's standard "there is more behind this" affordance.
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/// The chip's WORD is the one word the app uses for that grade
/// everywhere else — Good / Fair / Check, from `confidenceDescriptor`.
/// It used to print the stored enum ("Green" / "Yellow" / "Red"), so this
/// chip, the field log's quality column and the sheet this chip opens
/// were three different vocabularies for one reading. The colour still
/// carries the grade too; only the naming is unified.
@Composable
private fun DetailTierBadge(tier: ConfidenceTier) {
    val descriptor = confidenceDescriptor(tier.raw)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(descriptor.color))
        Text(
            descriptor.label,
            style = Forestix.type.caption,
            color = descriptor.color,
        )
    }
}

private fun detailFormatNumber(v: Float): String =
    if (v == v.toLong().toFloat()) String.format(Locale.US, "%d", v.toLong())
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
