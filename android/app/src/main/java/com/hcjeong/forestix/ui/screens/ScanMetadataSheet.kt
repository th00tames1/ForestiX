// Post-scan metadata sheet — attaches species + position + damage + note
// to a freshly-fitted scan before the cruiser hits Accept. Port of the iOS
// ScanMetadataSheet (Screens/ScanMetadataSheet.swift): same presentation
// (bottom sheet titled "Reading details" with a Done affordance), same
// fields, same damage-code vocabulary, so the recorded QuickMeasureEntry
// rows join across platforms.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
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
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    // iOS ScanMetadataSheet.speciesOptions: the curated regional list for
    // settings.region (nil → .all) plus a permanent "OT · Other" escape
    // hatch — cruisers occasionally measure non-regional trees.
    val speciesOptions = remember(settings.region) {
        val region = Region.fromRaw(settings.region) ?: Region.ALL
        RegionalSpecies.defaultSpecies(region) + ("OT" to "Other")
    }
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
                var speciesMenuOpen by remember { mutableStateOf(false) }
                val selectedLabel: AnnotatedString = speciesCode?.let { code ->
                    speciesOptions.firstOrNull { it.first == code }
                        ?.let { speciesPickerLabel(it.second, it.first, colors.textSecondary) }
                        ?: AnnotatedString(code)
                } ?: AnnotatedString("— Unspecified —")
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { speciesMenuOpen = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Default label colour (no primary tint) — iOS Form
                        // Picker row parity.
                        Text(selectedLabel, style = type.body, color = colors.textPrimary)
                        Spacer(Modifier.weight(1f))
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
                                onSpeciesCode(null)
                            })
                        speciesOptions.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = {
                                    Text(speciesPickerLabel(name, code, colors.textSecondary))
                                },
                                onClick = {
                                    speciesMenuOpen = false
                                    onSpeciesCode(code)
                                })
                        }
                    }
                }
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
private fun speciesPickerLabel(name: String, code: String, dim: Color): AnnotatedString =
    buildAnnotatedString {
        append(name)
        withStyle(SpanStyle(color = dim, fontSize = 12.sp)) {
            append(" · $code")
        }
    }
