// The plot's own record: what the cruiser can say about the GROUND, next to
// what the app already knows about the plot. Port of iOS
// Screens/PlotDetailSheet.swift — same fields, same wording, same rules.
//
// One sheet, reached from more than one place (the field log and the cruise
// map both open it), so it takes a plot IDENTIFIER and loads its own data
// rather than being handed a Plot the host has already read. Two hosts each
// holding their own copy of the same plot is how one of them saves over the
// other's edit.
//
// The four site fields are slope, aspect, ground elevation and canopy cover.
// Slope and aspect are DEGREES, the units the rest of the app already prints
// them in (see the plot block in the PDF report). Elevation is stored in
// metres like every other length here and is typed in the cruiser's own unit —
// feet on a US cruise — through [MeasurementFormatter.elevationDisplay].
// Canopy cover is a percentage and is the same number in both systems.
//
// Nothing here validates on its own authority: the ranges live on
// `Plot.siteDescriptionRejection`, which is also what the repository refuses
// with. This screen asks the model the same question the store will ask, so
// Save is never offered for something the write would throw on.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.hasCentre
import com.hcjeong.forestix.data.cruise.siteDescriptionRejection
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/// Plot detail, presented as a bottom sheet.
///
/// ```
/// detailPlotId?.let { id ->
///     PlotDetailSheet(plotId = id, onDismiss = { detailPlotId = null })
/// }
/// ```
///
/// [onSaved] fires once, with the stored plot, after a successful write, and
/// the sheet then calls [onDismiss] itself. The host needs an
/// `AppEnvironment` in the composition, which every screen in this app has.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlotDetailSheet(
    plotId: UUID,
    onDismiss: () -> Unit,
    onSaved: (Plot) -> Unit = {},
) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val system = settings.unitSystem

    /// The plot as STORED. Every prefill is derived from this, and a save
    /// replaces it, so the fields re-seed from what actually landed rather
    /// than from what was typed.
    var plot by remember { mutableStateOf<Plot?>(null) }
    /// Live trees on the plot — the count a cruiser checks the sheet for.
    /// Soft-deleted trees are excluded because they are excluded from every
    /// tally; a sheet that counted them would disagree with the field log.
    var treeCount by remember { mutableStateOf(0) }
    var loadError by remember { mutableStateOf<String?>(null) }

    var slopeText by remember { mutableStateOf("") }
    var aspectText by remember { mutableStateOf("") }
    var elevationText by remember { mutableStateOf("") }
    var canopyText by remember { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    // Every prefill is the stored value ROUNDED for display, which is only
    // safe because `edited` restores the stored value verbatim whenever the
    // text still equals its prefill. Without that, opening the sheet and
    // pressing Save would quietly re-round every field — and on an imperial
    // cruise the elevation would also make a metres→feet→metres round trip
    // every time it was saved, walking a few centimetres per visit.
    val stored = plot
    val prefillSlope = stored?.let { slopePrefill(it) } ?: ""
    val prefillAspect = stored?.let { aspectPrefill(it) } ?: ""
    val prefillElevation = stored?.let { elevationPrefill(it, system) } ?: ""
    val prefillCanopy = stored?.let { canopyPrefill(it) } ?: ""

    fun seed(p: Plot) {
        plot = p
        slopeText = slopePrefill(p)
        aspectText = aspectPrefill(p)
        elevationText = elevationPrefill(p, system)
        canopyText = canopyPrefill(p)
    }

    LaunchedEffect(plotId) {
        try {
            val loaded = env.plotRepository.read(plotId)
            if (loaded == null) {
                loadError = "This plot is no longer in the project."
            } else {
                treeCount = runCatching {
                    env.treeRepository.listByPlot(loaded.id, includeDeleted = false).size
                }.getOrDefault(0)
                seed(loaded)
            }
        } catch (e: Exception) {
            loadError = "Couldn't open the plot: ${e.message ?: e}"
        }
    }

    // Per-row validity, so the warning sits under the field that is wrong
    // instead of one blanket message at the foot of the section.
    val slopeValid = stored == null || slopeText == prefillSlope ||
        TruthInput.parse(slopeText)?.isFinite() == true
    val aspectValid = stored == null || aspectText == prefillAspect ||
        PlotAspect.parse(aspectText) != null
    val elevationValid = stored == null || elevationText == prefillElevation ||
        TruthInput.normalized(elevationText).isEmpty() ||
        TruthInput.parse(elevationText)?.isFinite() == true
    val canopyValid = stored == null || canopyText == prefillCanopy ||
        TruthInput.normalized(canopyText).isEmpty() ||
        TruthInput.parse(canopyText)?.isFinite() == true

    /// The plot the Save button would store, or null while a field holds
    /// something that is not a site reading at all.
    ///
    /// Range checking is NOT done here — that is the model's job, and asking
    /// it once (in `siteDescriptionRejection`) is what keeps the sheet and the
    /// store from disagreeing. This only answers "did the cruiser type a
    /// number, a compass point, or nothing".
    fun edit(base: Plot): Plot? {
        val slope = when {
            slopeText == prefillSlope -> base.slopeDeg
            else -> TruthInput.parse(slopeText)?.takeIf { it.isFinite() }?.toFloat()
        } ?: return null

        val aspect = when {
            aspectText == prefillAspect -> base.aspectDeg
            else -> PlotAspect.parse(aspectText)
        } ?: return null

        // Blank clears an elevation: "I don't have one for this plot" is a
        // real answer, and it is not the same as sea level. Text that is not
        // a number is NOT a clear — it refuses the save, so a half-typed
        // figure cannot wipe the reading behind it.
        val elevation: Float? = when {
            elevationText == prefillElevation -> base.groundElevationM
            TruthInput.normalized(elevationText).isEmpty() -> null
            else -> TruthInput.parse(elevationText)?.takeIf { it.isFinite() }
                ?.let { MeasurementFormatter.elevationMetres(it, system).toFloat() }
                ?: return null
        }

        val canopy: Float? = when {
            canopyText == prefillCanopy -> base.canopyCoverPct
            TruthInput.normalized(canopyText).isEmpty() -> null
            else -> TruthInput.parse(canopyText)?.takeIf { it.isFinite() }?.toFloat()
                ?: return null
        }

        return base.copy(
            slopeDeg = slope,
            aspectDeg = aspect,
            groundElevationM = elevation,
            canopyCoverPct = canopy,
        )
    }

    val edited = stored?.let { edit(it) }
    val rejection = edited?.siteDescriptionRejection
    val canSave = !saving && edited != null && rejection == null

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
            // Title bar — inline centred title + trailing Cancel, mirroring
            // the iOS NavigationStack toolbar.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(
                    stored?.let { "Plot ${it.plotNumber}" } ?: "Plot",
                    style = type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Cancel")
                }
            }

            if (stored == null) {
                Box(
                    Modifier.fillMaxWidth().padding(ForestixSpace.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    val message = loadError
                    if (message != null) {
                        Text(message, style = type.body, color = colors.confidenceBad)
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@Column
            }

            // POSITION -----------------------------------------------------
            PlotDetailSection(header = "POSITION") {
                // `hasCentre` rather than a lat/lon test of our own — (0, 0)
                // is the app's "no centre" sentinel and printing it here
                // would put the plot in the Gulf of Guinea.
                PlotDetailLabelRow(
                    "Centre",
                    if (stored.hasCentre) {
                        String.format(Locale.US, "%.5f, %.5f", stored.centerLat, stored.centerLon)
                    } else {
                        "No centre recorded"
                    },
                )
            }

            // SITE ---------------------------------------------------------
            PlotDetailSection(
                header = "SITE",
                footer = "Aspect takes a bearing or a compass point — 45 and NE are the " +
                    "same entry. Leave elevation or canopy cover blank if you didn't " +
                    "record one.",
            ) {
                PlotDetailValueRow(
                    label = "Slope",
                    value = slopeText,
                    unit = "°",
                ) { slopeText = it }
                if (!slopeValid) {
                    PlotDetailWarning("Slope is degrees from 0 (flat) to 90 (vertical).")
                }

                PlotDetailValueRow(
                    label = "Aspect",
                    value = aspectText,
                    // The degree sign plus the compass point the current entry
                    // stands for — what tells the cruiser that a typed "NE"
                    // landed on 45, and that a typed 45 is the north-east they
                    // meant.
                    unit = PlotAspect.parse(aspectText)
                        ?.let { "° ${PlotAspect.pointName(it)}" } ?: "°",
                    // The aspect field takes letters ("NE"), so it cannot have
                    // the decimal keyboard — there would be no way to type the
                    // point.
                    numeric = false,
                ) { aspectText = it }
                if (!aspectValid) {
                    PlotDetailWarning(
                        "Aspect is a bearing from 0 to 360, or a compass point like NE.")
                }

                PlotDetailValueRow(
                    label = "Elevation",
                    value = elevationText,
                    unit = MeasurementFormatter.heightUnit(system),
                ) { elevationText = it }
                if (!elevationValid) {
                    PlotDetailWarning(
                        "Elevation is a number, or blank if you didn't record one.")
                }

                PlotDetailValueRow(
                    label = "Canopy cover",
                    value = canopyText,
                    unit = "%",
                ) { canopyText = it }
                if (!canopyValid) {
                    PlotDetailWarning(
                        "Canopy cover is 0 to 100 %, or blank if you didn't record one.")
                }

                // The MODEL's refusal, in the model's words — the same
                // sentence the repository would throw. Anything the sheet
                // allowed but storage will not surfaces here rather than under
                // a Save button that fails silently.
                rejection?.let { PlotDetailWarning(it) }
            }

            // THIS PLOT ----------------------------------------------------
            PlotDetailSection(header = "THIS PLOT") {
                PlotDetailLabelRow("Trees", treeCount.toString())
                val unit = system.areaUnit
                PlotDetailLabelRow(
                    "Area",
                    String.format(
                        Locale.US, "%.2f %s",
                        unit.fromAcres(stored.plotAreaAcres.toDouble()), unit.abbreviation),
                )
                PlotDetailLabelRow("Opened", plotDetailTimestamp(stored.startedAt))
                PlotDetailLabelRow(
                    "Closed",
                    stored.closedAt?.let { plotDetailTimestamp(it) } ?: "Still open")
            }

            ForestixProminentButton(
                label = "Save",
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val next = edited ?: return@ForestixProminentButton
                saving = true
                saveError = null
                scope.launch {
                    try {
                        val written = env.plotRepository.update(next)
                        // Re-seed from what was STORED, not from what was
                        // typed, so a sheet left open after a save shows the
                        // record and not a draft.
                        seed(written)
                        saving = false
                        onSaved(written)
                        onDismiss()
                    } catch (e: Exception) {
                        saving = false
                        saveError = "${e.message ?: e} Nothing was changed — try again."
                    }
                }
            }
        }
    }

    // Error alert — the iOS sheet's `.alert("Couldn't save the plot", ...)`.
    saveError?.let { message ->
        AlertDialog(
            onDismissRequest = { saveError = null },
            confirmButton = { TextButton(onClick = { saveError = null }) { Text("OK") } },
            title = { Text("Couldn't save the plot") },
            text = { Text(message) },
        )
    }
}

// Prefills — the stored values at the precision the fields show them. One
// definition each, because the sheet both SEEDS from them and compares the
// typed text against them, and two spellings of "what is in this field" is how
// an untouched field starts reading as an edit.

private fun slopePrefill(p: Plot): String =
    MeasurementFormatter.entryText(p.slopeDeg.toDouble(), 1)

private fun aspectPrefill(p: Plot): String =
    MeasurementFormatter.entryText(p.aspectDeg.toDouble(), 0)

private fun elevationPrefill(p: Plot, system: UnitSystem): String =
    p.groundElevationM?.let {
        MeasurementFormatter.entryText(
            MeasurementFormatter.elevationDisplay(it.toDouble(), system), 0)
    } ?: ""

private fun canopyPrefill(p: Plot): String =
    p.canopyCoverPct?.let { MeasurementFormatter.entryText(it.toDouble(), 0) } ?: ""

private fun plotDetailTimestamp(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.US).format(Date(epochMillis))

/// Grouped form section — uppercase sectionHead header, surface card body,
/// optional caption footer (the iOS insetGrouped Form look).
@Composable
private fun PlotDetailSection(
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
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
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

/// One editable site field: label left, value right, unit trailing. Same
/// geometry as the tree inspector's measurement rows, so the two detail forms
/// read as one app.
@Composable
private fun PlotDetailValueRow(
    label: String,
    value: String,
    unit: String,
    numeric: Boolean = true,
    onValue: (String) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            placeholder = {
                Text("—", textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                capitalization = if (numeric) {
                    KeyboardCapitalization.None
                } else {
                    KeyboardCapitalization.Characters
                },
            ),
            textStyle = type.data.copy(textAlign = TextAlign.End, color = colors.textPrimary),
            modifier = Modifier.width(110.dp),
        )
        Text(unit, style = type.body, color = colors.textSecondary,
            modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun PlotDetailLabelRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.dataSmall, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun PlotDetailWarning(text: String) {
    Text(text, style = Forestix.type.caption, color = Forestix.colors.confidenceBad)
}

// MARK: - Aspect: a bearing or a compass point

/// Aspect is a compass bearing, and a cruiser says "NE" faster than they type
/// "045" with cold hands. Both spellings land on the same stored degrees, and
/// the row echoes the point back so a typed number is confirmed by name.
///
/// Sixteen points, because that is the resolution a hand compass and a
/// forester's vocabulary share. The iOS sibling carries the identical table
/// and the identical rounding.
object PlotAspect {

    /// Point name → bearing. Ordered north-clockwise so the index doubles as
    /// the 22.5° step used by [pointName].
    val points = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /// The bearing this entry stands for, or null when it is neither a
    /// compass point nor a bearing on the compass.
    ///
    /// 360 is accepted and stored as 0 — a cruiser writing "360" means north,
    /// and storage keeps one spelling of north. 400, though, is REFUSED
    /// rather than wrapped to 40: wrapping would turn a typo into a
    /// north-east-facing plot that nobody ever stood on.
    fun parse(raw: String): Float? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val index = points.indexOf(trimmed.uppercase(Locale.US))
        if (index >= 0) return index * 22.5f
        val v = TruthInput.parse(trimmed) ?: return null
        if (!v.isFinite() || v < 0 || v > 360) return null
        return if (v == 360.0) 0f else v.toFloat()
    }

    /// The nearest of the sixteen points to a stored bearing.
    fun pointName(deg: Float): String {
        val wrapped = deg % 360f
        val positive = if (wrapped < 0f) wrapped + 360f else wrapped
        return points[(Math.round(positive / 22.5f)) % points.size]
    }
}
