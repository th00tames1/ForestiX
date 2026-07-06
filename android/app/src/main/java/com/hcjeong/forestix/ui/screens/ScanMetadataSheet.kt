// Post-scan metadata dialog — attaches species + position + damage + note
// to a freshly-fitted scan before the cruiser hits Accept. Port of the iOS
// ScanMetadataSheet (Screens/ScanMetadataSheet.swift): same fields, same
// damage-code vocabulary, so the recorded QuickMeasureEntry rows join
// across platforms.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.Region
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.data.StemPosition
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix

/// Damage-code vocabulary — identical to iOS ScanMetadataSheet.damageOptions.
val ScanDamageOptions = listOf("sweep", "fork", "broken-top", "rot", "scar", "lean")

/// Metadata editor shown from the scan screens' "Details" action. All state
/// is hoisted so the caller owns the values when it records the entry.
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ScanMetadataDialog(
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
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Scan details") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("SPECIES", style = type.sectionHead, color = colors.textSecondary)
                // Regional species picker — mirrors the iOS Picker of
                // "CODE · Common name" rows plus an Unspecified entry
                // (ScanMetadataSheet.swift speciesSection). No free-text:
                // codes always come from the curated vocabulary.
                var speciesMenuOpen by remember { mutableStateOf(false) }
                val selectedLabel = speciesCode?.let { code ->
                    speciesOptions.firstOrNull { it.first == code }
                        ?.let { "${it.first} · ${it.second}" } ?: code
                } ?: "— Unspecified —"
                Box(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { speciesMenuOpen = true }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(selectedLabel, style = type.body, color = colors.primary)
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.primary)
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
                                text = { Text("$code · $name") },
                                onClick = {
                                    speciesMenuOpen = false
                                    onSpeciesCode(code)
                                })
                        }
                    }
                }

                if (showPosition) {
                    Text("MEASURED AT", style = type.sectionHead, color = colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StemPosition.entries.forEach { p ->
                            FilterChip(
                                selected = position == p,
                                onClick = { onPosition(if (position == p) null else p) },
                                label = { Text(p.displayName) },
                            )
                        }
                    }
                }

                Text("DAMAGE / DEFECT", style = type.sectionHead, color = colors.textSecondary)
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
                            label = { Text(tag) },
                        )
                    }
                }

                Text("NOTE", style = type.sectionHead, color = colors.textSecondary)
                OutlinedTextField(
                    value = note,
                    onValueChange = onNote,
                    placeholder = { Text("Free-text note (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
        },
    )
}
