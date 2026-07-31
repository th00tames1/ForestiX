// Post-scan metadata sheet — attaches species + position + damage + note
// to a freshly-fitted scan before the cruiser hits Accept. Port of the iOS
// ScanMetadataSheet (Screens/ScanMetadataSheet.swift): same presentation
// (bottom sheet titled "Reading details" with a Done affordance), same
// fields, same damage-code vocabulary, so the recorded QuickMeasureEntry
// rows join across platforms.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.CountrySpecies
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.data.StemPosition
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale

/// Damage-code vocabulary — identical to iOS ScanMetadataSheet.damageOptions.
val ScanDamageOptions = listOf("sweep", "fork", "broken-top", "rot", "scar", "lean")

/// Metadata editor shown from the scan screens' "Details" action. All state
/// is hoisted so the caller owns the values when it records the entry.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanMetadataSheet(
    speciesCode: String?,
    onSpeciesCode: (String?) -> Unit,
    position: StemPosition?,
    onPosition: (StemPosition?) -> Unit,
    damageCodes: List<String>,
    onDamageCodes: (List<String>) -> Unit,
    note: String,
    onNote: (String) -> Unit,
    showPosition: Boolean = true,
    onDismiss: () -> Unit,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = ForestixSpace.lg),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // Title bar — inline centred title + trailing Done, mirroring
            // the iOS NavigationStack toolbar.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(
                    "Reading details",
                    style = type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Done")
                }
            }

            // SPECIES ------------------------------------------------------
            MetadataSection(header = "SPECIES") {
                SpeciesPickerField(
                    speciesCode = speciesCode,
                    onSpeciesCode = onSpeciesCode,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // POSITION -----------------------------------------------------
            if (showPosition) {
                MetadataSection(
                    header = "POSITION",
                    footer = "Default DBH = 1.3 m. Mark butt / upper / stump if you measured elsewhere.",
                ) {
                    // Single-choice segmented control, default DBH, never
                    // deselectable (tap-again keeps the selection) — iOS
                    // `.segmented` Picker parity.
                    val current = position ?: StemPosition.DBH
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        StemPosition.entries.forEachIndexed { index, p ->
                            SegmentedButton(
                                selected = current == p,
                                onClick = { onPosition(p) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = StemPosition.entries.size,
                                ),
                            ) { Text(p.displayName, style = type.caption, maxLines = 1) }
                        }
                    }
                }
            }

            // DAMAGE -------------------------------------------------------
            MetadataSection(
                header = "DAMAGE",
                footer = "Drives cull deductions in stand-and-stock reports.",
            ) {
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanDamageOptions.forEach { tag ->
                        FilterChip(
                            selected = damageCodes.contains(tag),
                            onClick = {
                                onDamageCodes(
                                    if (damageCodes.contains(tag)) damageCodes - tag
                                    else damageCodes + tag,
                                )
                            },
                            label = { Text(capitalizedTag(tag)) },
                        )
                    }
                }
            }

            // NOTE ---------------------------------------------------------
            MetadataSection(header = "NOTE") {
                OutlinedTextField(
                    value = note,
                    onValueChange = onNote,
                    placeholder = { Text("Free-text note (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// MARK: - Species picker ------------------------------------------------------

/// The active region's curated species list plus the permanent "OT · Other"
/// escape — cruisers occasionally measure non-regional trees. iOS
/// ScanMetadataSheet.speciesOptions.
@Composable
internal fun rememberSpeciesOptions(): List<Pair<String, String>> {
    val settings by LocalAppEnvironment.current.settings.state.collectAsStateWithLifecycle()
    return remember(settings.country, settings.region) {
        val country = settings.country
        val base = if (country.hasRegions) {
            // US: scoped by the selected timber region.
            val region = Region.fromRaw(settings.region) ?: Region.ALL
            RegionalSpecies.defaultSpecies(region)
        } else {
            // Metric countries (incl. Korea scaffold): single national preset.
            CountrySpecies.defaultSpecies(country)
        }
        base + ("OT" to "Other")
    }
}

/// THE species control. Both places a cruiser picks a species go through this
/// one composable — the reading-details sheet and the measure chooser — so the
/// list, the ordering and the typed-code escape cannot drift apart.
///
/// The regional presets are a convenience, not a boundary: a cruiser standing
/// in front of a tree the preset does not carry can type its code rather than
/// filing it under "Other" and losing which species it actually was.
///
/// [bordered] is the compact pill the measure chooser wants; the sheet's row
/// sits bare inside its section card.
///
/// [provisional] says the code showing was filled in by the app, not chosen by
/// the cruiser: it is drawn in the same dim tertiary the empty control uses, so
/// a species the app guessed never looks like one somebody confirmed.
/// [onSpeciesCode] already fires on EVERY selection — including re-picking the
/// code already showing, which is exactly how a cruiser confirms a guess — so
/// the host can clear [provisional] from it rather than watching the value.
@Composable
internal fun SpeciesPickerField(
    speciesCode: String?,
    onSpeciesCode: (String?) -> Unit,
    modifier: Modifier = Modifier,
    unspecifiedLabel: String = "— Unspecified —",
    bordered: Boolean = false,
    provisional: Boolean = false,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    val options = rememberSpeciesOptions()
    var menuOpen by remember { mutableStateOf(false) }
    var typedCodeOpen by remember { mutableStateOf(false) }

    val selectedLabel: AnnotatedString = speciesCode?.let { code ->
        options.firstOrNull { it.first == code }
            ?.let { speciesPickerLabel(it.second, it.first, colors.textSecondary) }
            // A code the list does not carry is shown as typed, not silently
            // relabelled to something the cruiser did not choose — and not
            // decorated here either, because a control that reads back the
            // codes it was given has no room to explain one. WHERE IT IS
            // EXPLAINED is the resolved-name row the tree form draws under
            // this control (`TreeFormSpeciesRows`): a code nothing answers to
            // reads "Not in this region's list" there. The capture sheets show
            // the bare code on purpose — the cruiser typed it a moment ago.
            ?: AnnotatedString(code)
    } ?: AnnotatedString(unspecifiedLabel)

    Box(modifier) {
        Row(
            Modifier
                .then(
                    if (bordered) {
                        Modifier
                            .clip(ForestixRadius.control)
                            .border(1.dp, colors.divider, ForestixRadius.control)
                            .padding(horizontal = ForestixSpace.sm, vertical = 10.dp)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                )
                .clickableNoRipple { menuOpen = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Default label colour (no primary tint) — iOS Form Picker row
            // parity.
            Text(
                selectedLabel,
                style = type.body,
                color = if (speciesCode == null || provisional) {
                    colors.textTertiary
                } else {
                    colors.textPrimary
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (bordered) Modifier else Modifier.weight(1f, fill = false),
            )
            if (!bordered) Spacer(Modifier.weight(1f))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = colors.textSecondary,
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("— Unspecified —") },
                onClick = {
                    menuOpen = false
                    onSpeciesCode(null)
                })
            options.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(speciesPickerLabel(name, code, colors.textSecondary)) },
                    onClick = {
                        menuOpen = false
                        onSpeciesCode(code)
                    })
            }
            DropdownMenuItem(
                text = { Text("Type a code…") },
                onClick = {
                    menuOpen = false
                    typedCodeOpen = true
                })
        }
    }

    if (typedCodeOpen) {
        var typed by remember { mutableStateOf(speciesCode.orEmpty()) }
        AlertDialog(
            onDismissRequest = { typedCodeOpen = false },
            title = { Text("Species code") },
            text = {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("Code not in the list") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    // An empty box means "no species", not a species whose
                    // code is the empty string.
                    onClick = {
                        typedCodeOpen = false
                        onSpeciesCode(typed.trim().uppercase(Locale.US).ifEmpty { null })
                    },
                ) { Text("Use") }
            },
            dismissButton = {
                TextButton(onClick = { typedCodeOpen = false }) { Text("Cancel") }
            },
        )
    }
}

/// Grouped form section — uppercase sectionHead header, surface card body,
/// optional caption footer (the iOS insetGrouped Form look).
@Composable
private fun MetadataSection(
    header: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            header,
            style = type.sectionHead,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = ForestixSpace.sm),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.sm),
        ) {
            content()
        }
        footer?.let {
            Text(
                it,
                style = type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = ForestixSpace.sm),
            )
        }
    }
}

/// Mirror of Swift's `.capitalized` for the damage tags — uppercase the
/// first letter of every word (any non-letter is a boundary), so
/// "broken-top" renders "Broken-Top" on both platforms.
private fun capitalizedTag(raw: String): String =
    Regex("\\p{L}+").replace(raw.lowercase(Locale.US)) { match ->
        match.value.replaceFirstChar { it.titlecase(Locale.US) }
    }

/// Name-first option label: the common name reads prominently, the FIA
/// code trails as a dim secondary suffix (" · DF"). Selecting the row
/// still stores the code — this only reshapes what's shown. Mirrors the
/// iOS ScanMetadataSheet.pickerLabel AttributedString.
///
/// `internal` because the per-tree report's species picker (F8) uses the
/// identical treatment — one definition, not two that can drift.
internal fun speciesPickerLabel(name: String, code: String, dim: Color): AnnotatedString =
    buildAnnotatedString {
        append(name)
        withStyle(SpanStyle(color = dim, fontSize = 12.sp)) {
            append(" · $code")
        }
    }
