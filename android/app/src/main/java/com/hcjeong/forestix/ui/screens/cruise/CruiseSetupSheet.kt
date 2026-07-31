// SIMPLIFIED CRUISE SETUP (mock ⑥) — Phase B replacement for the old
// six-field CruiseDesignScreen. A bottom sheet with every field
// pre-defaulted: plot type (fixed radius / BAF), one plot-size field,
// one layout field (plot count OR grid spacing), and an optional
// stratum polygon drawn with the kept StratumDrawScreen. "Generate
// plots" runs the SAME generation engine the old screen used
// (geo.SamplingGenerator + the planned-plot / cruise-design repos):
//
//   • polygon drawn + Grid spacing → systematic grid inside the strata;
//   • polygon drawn + Count        → stratified random, `count` per
//                                    stratum (the engine's count-based
//                                    scheme);
//   • NO polygon                   → a centred square-ish grid anchored
//     on the current map centre: `count` plots (count mode) at the
//     default 150 m pitch, or 12 plots (spacing mode) at the chosen
//     spacing — ceil(√n) columns, row-major, exactly n plots. A small
//     woodlot rarely needs a boundary before pins are useful.
//
// Planned-plot numbering continues after the project's existing real
// plots (max plotNumber + 1) so informal plots started before setup
// never collide with the plan, and after the plans that survive the run
// so no two of them share a number. Generating replaces the previous
// UNVISITED planned plots — a VISITED plan has a measured plot standing
// on it and survives every re-run — and upserts the single CruiseDesign
// record. Generation never gates measuring: planned pins are
// suggestions.

package com.hcjeong.forestix.ui.screens.cruise

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.PositionSource
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.geo.CoordinateConversions
import com.hcjeong.forestix.geo.SamplingGenerator
import com.hcjeong.forestix.ui.pressableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import org.json.JSONObject

// MARK: - Radius ↔ acres (the design model stores acres)

private fun radiusMFromAcres(acres: Double): Double =
    sqrt(Units.acresToSquareMeters(acres) / PI)

private fun acresFromRadiusM(radiusM: Double): Double =
    Units.squareMetersToAcres(Units.circleAreaM2(radiusM))

// MARK: - Sheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CruiseSetupSheet(
    project: Project,
    /// Anchor for the no-polygon fallback grid — the map centre the
    /// cruiser has framed (they are looking at their woodlot).
    mapCentre: CoordinateConversions.LatLon,
    onDismiss: () -> Unit,
    /// "Draw boundary" → the KEPT StratumDrawScreen route.
    onDrawBoundary: () -> Unit,
    /// The AREA this setup is for, when the sheet was opened from an
    /// outline the cruiser selected on the map ("Cruise this area").
    ///
    /// It changes two things, and nothing else. The stratum row stops
    /// offering to draw a boundary — one is already chosen, and its name
    /// and size are shown instead — and generation lays plots into THIS
    /// stratum only, replacing this area's previous plan and leaving every
    /// other area's alone. Opened from the project strip it is null and the
    /// sheet behaves exactly as it always has. iOS `CruiseSetupSheet.area`.
    area: Stratum? = null,
) {
    val env = LocalAppEnvironment.current
    val settings by env.settings.state.collectAsStateWithLifecycle()
    val areaUnit = settings.unitSystem.areaUnit
    val colors = Forestix.colors
    val type = Forestix.type
    val scope = rememberCoroutineScope()

    // Defaults from the existing design model (CruiseDesign record when
    // present; otherwise the long-standing 0.1 ac / BAF 20 / 150 m grid /
    // 10-count defaults the old screen shipped with). Metric countries start
    // from a round 10 m radius instead of the odd metre value 0.1 ac maps to.
    var fixedRadius by remember { mutableStateOf(true) }
    var radiusText by remember {
        mutableStateOf(
            if (areaUnit == AreaUnit.HECTARE) "10.0"
            else String.format(Locale.US, "%.1f", radiusMFromAcres(0.1)),
        )
    }
    var bafText by remember { mutableStateOf("20") }
    var bySpacing by remember { mutableStateOf(true) }
    var countText by remember { mutableStateOf("10") }
    var spacingText by remember { mutableStateOf("150") }
    var strataCount by remember { mutableIntStateOf(0) }
    var generating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /// Non-null while the "this run takes work you can't get back" question
    /// is up; the string IS the sentence it asks. Its own channel beside
    /// `error` because that one is a refusal and this one is a choice, and a
    /// cruiser must never have to read which it is. (iOS `discardWarning`.)
    var discardWarning by remember { mutableStateOf<String?>(null) }

    // Re-opening setup shows what was generated last time — same prefill
    // behaviour the old screen's refresh() had.
    LaunchedEffect(project.id) {
        strataCount = try {
            env.stratumRepository.listByProject(project.id).size
        } catch (_: Exception) {
            0
        }
        val design = try {
            env.cruiseDesignRepository.forProject(project.id).firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return@LaunchedEffect
        fixedRadius = design.plotType == PlotType.FIXED_AREA
        design.plotAreaAcres?.let {
            radiusText = String.format(Locale.US, "%.1f", radiusMFromAcres(it.toDouble()))
        }
        design.baf?.let { bafText = String.format(Locale.US, "%.0f", it) }
        when (design.samplingScheme) {
            SamplingScheme.SYSTEMATIC_GRID -> {
                bySpacing = true
                design.gridSpacingMeters?.let {
                    spacingText = String.format(Locale.US, "%.0f", it)
                }
            }
            SamplingScheme.STRATIFIED_RANDOM -> bySpacing = false
            SamplingScheme.MANUAL -> Unit
        }
    }

    /// `confirmedDiscard` is the answer to the question `generateCruisePlan`
    /// asks: false on the press of "Generate plots", true only after the
    /// cruiser has read what this run takes and said replace it anyway.
    /// Running generation again after the answer costs nothing — the
    /// generator is deterministic — and it keeps the question in front of
    /// the writes rather than in front of a plan that might not generate.
    fun generate(confirmedDiscard: Boolean = false) {
        if (generating) return
        val radiusM = radiusText.toDoubleOrNull() ?: 0.0
        val baf = bafText.toDoubleOrNull() ?: 0.0
        val count = countText.toIntOrNull() ?: 0
        val spacing = spacingText.toDoubleOrNull() ?: 0.0
        if (fixedRadius && radiusM <= 0) {
            error = "Plot radius must be a positive number of metres."
            return
        }
        if (!fixedRadius && baf <= 0) {
            error = "Basal area factor must be a positive number."
            return
        }
        if (!bySpacing && count <= 0) {
            error = "Plot count must be a positive whole number."
            return
        }
        if (bySpacing && spacing <= 0) {
            error = "Grid spacing must be a positive number of metres."
            return
        }
        generating = true
        error = null
        scope.launch {
            try {
                val warning = generateCruisePlan(
                    env = env,
                    project = project,
                    fixedRadius = fixedRadius,
                    radiusM = radiusM,
                    baf = baf,
                    bySpacing = bySpacing,
                    count = count,
                    spacingM = spacing,
                    mapCentre = mapCentre,
                    area = area,
                    confirmedDiscard = confirmedDiscard,
                )
                if (warning != null) {
                    // Nothing was written — the run is waiting on an answer,
                    // so the button comes back rather than sitting spinning
                    // behind a dialog.
                    discardWarning = warning
                    generating = false
                } else {
                    onDismiss()
                }
            } catch (e: Exception) {
                error = e.message ?: "Plot generation failed: $e"
                generating = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.surface) {
        Column(
            Modifier
                .padding(horizontal = ForestixSpace.md)
                .padding(bottom = ForestixSpace.xl)
                .verticalScroll(rememberScrollState()),
        ) {
            // LOCKED sheet "Cruise setup" (section-head voice, iOS parity).
            Text(
                "CRUISE SETUP",
                style = type.sectionHead.copy(
                    fontWeight = FontWeight.ExtraBold, letterSpacing = 1.0.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = ForestixSpace.sm),
            )

            // LOCKED "Plot type" — [Fixed radius | Variable (BAF)].
            FieldHeader("Plot type")
            SetupSegmented(
                options = listOf("Fixed radius", "Variable radius (prism)"),
                selectedIndex = if (fixedRadius) 0 else 1,
            ) { fixedRadius = it == 0 }
            Spacer(Modifier.size(ForestixSpace.sm))

            // LOCKED "Plot size" — radius m (fixed) / BAF (variable).
            FieldHeader("Plot size")
            NumericRow(
                value = if (fixedRadius) radiusText else bafText,
                onValueChange = { if (fixedRadius) radiusText = it else bafText = it },
                unit = if (fixedRadius) "m radius" else "BAF",
                caption = if (fixedRadius) {
                    radiusText.toDoubleOrNull()?.takeIf { it > 0 }?.let { r ->
                        val ac = acresFromRadiusM(r)
                        // Metric countries read hectares only; the US keeps the
                        // dual ha · ac readout it has always shown.
                        if (areaUnit == AreaUnit.HECTARE) {
                            String.format(Locale.US, "≈ %.3f ha", ac * 0.404685642)
                        } else {
                            String.format(Locale.US, "≈ %.2f ha · %.2f ac",
                                ac * 0.404685642, ac)
                        }
                    } ?: "Circular fixed-area plot"
                } else {
                    "Prism basal-area factor"
                },
            )
            Spacer(Modifier.size(ForestixSpace.sm))

            // Layout: [Count | Grid spacing] drives ONE numeric field.
            SetupSegmented(
                options = listOf("Count", "Grid spacing"),
                selectedIndex = if (bySpacing) 1 else 0,
            ) { bySpacing = it == 1 }
            Spacer(Modifier.size(ForestixSpace.xs))
            // LOCKED field titles "Plots" / "Spacing".
            FieldHeader(if (bySpacing) "Spacing" else "Plots")
            NumericRow(
                value = if (bySpacing) spacingText else countText,
                onValueChange = { if (bySpacing) spacingText = it else countText = it },
                unit = if (bySpacing) "m" else "plots",
                caption = if (bySpacing) {
                    "Distance between plot centres"
                } else {
                    "How many plot centres to lay out"
                },
            )
            Spacer(Modifier.size(ForestixSpace.sm))

            // Optional "Stratum boundary" row → KEPT StratumDrawScreen.
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = ForestixSpace.xs),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (area == null) "Stratum boundary" else "Area",
                        style = type.bodyBold.copy(fontSize = 14.5.sp),
                        color = colors.textPrimary,
                    )
                    // FIELD REPORT F7 — the field is named, not qualified. The
                    // word "Optional" told the cruiser nothing they could act
                    // on; "None drawn" says what the state actually is.
                    Text(
                        if (area != null) {
                            String.format(
                                Locale.US, "%s · %.2f ha · %.2f ac",
                                area.name,
                                area.areaAcres.toDouble() * 0.404685642,
                                area.areaAcres.toDouble(),
                            )
                        } else if (strataCount == 0) {
                            "None drawn — whole area"
                        } else {
                            "$strataCount boundar${if (strataCount == 1) "y" else "ies"} drawn"
                        },
                        style = type.caption.copy(fontSize = 11.5.sp),
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                // Opened from an area the row has nothing to offer: the
                // outline is already chosen, so a "Draw boundary" button
                // here would offer to change the very thing the cruiser
                // opened this sheet to cruise.
                if (area == null) {
                    Box(
                        Modifier
                            .pressableNoRipple(onClick = onDrawBoundary)
                            .heightIn(min = 38.dp)
                            .clip(ForestixRadius.control)
                            .border(1.dp, colors.divider, ForestixRadius.control)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Draw boundary",
                            style = type.caption.copy(
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.textPrimary,
                        )
                    }
                }
            }

            if (error != null) {
                Text(
                    error ?: "",
                    style = type.caption,
                    color = colors.confidenceBad,
                    modifier = Modifier.padding(top = ForestixSpace.xs),
                )
            }

            // LOCKED caption.
            Text(
                "Defaults work for most woodlots.",
                style = type.caption,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ForestixSpace.sm, bottom = ForestixSpace.xs),
            )

            // 54 dp primary — LOCKED "Generate plots".
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 54.dp)
                    .alpha(if (generating) 0.6f else 1f)
                    .pressableNoRipple(enabled = !generating, onClick = { generate() })
                    .clip(ForestixRadius.card)
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                if (generating) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                } else {
                    Text(
                        "Generate plots",
                        style = type.bodyBold.copy(fontSize = 15.sp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
            Text(
                "Shown as hollow pins. You can measure them any time.",
                style = type.caption.copy(fontSize = 11.5.sp),
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = ForestixSpace.xs),
            )
        }
    }

    // GENERATING OVER A PLAN, step two. The count is the whole point of the
    // sentence — same reason `areaDeletionMessage` carries one: a cruiser
    // about to lose a morning's planning has to know how much before they
    // answer. Raised only when the run would take something generating again
    // cannot put back (see `discardWarning`), and a sibling of the sheet
    // rather than a child of its Column so it sits over the sheet.
    val warning = discardWarning
    if (warning != null) {
        AlertDialog(
            onDismissRequest = { discardWarning = null },
            containerColor = colors.surface,
            title = {
                Text(
                    if (area != null) {
                        "Replace the plan in ${area.name}?"
                    } else {
                        "Replace the cruise plan?"
                    },
                    style = type.bodyBold,
                    color = colors.textPrimary,
                )
            },
            text = { Text(warning, style = type.caption, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    discardWarning = null
                    generate(confirmedDiscard = true)
                }) {
                    Text("Replace plan", style = type.body, color = colors.confidenceBad)
                }
            },
            dismissButton = {
                TextButton(onClick = { discardWarning = null }) {
                    Text("Cancel", style = type.body, color = colors.textPrimary)
                }
            },
        )
    }
}

// MARK: - Pieces

@Composable
private fun FieldHeader(title: String) {
    Text(
        title,
        style = Forestix.type.bodyBold.copy(fontSize = 14.5.sp),
        color = Forestix.colors.textPrimary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/// Two-option segmented control in the Forestix token language (the mock's
/// pill segments — Material's SegmentedButton doesn't match the map sheets).
@Composable
private fun SetupSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.control)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .pressableNoRipple(onClick = { onSelect(i) })
                    .clip(ForestixRadius.chip)
                    .then(
                        if (selected) {
                            Modifier
                                .background(colors.surface)
                                .border(1.dp, colors.divider, ForestixRadius.chip)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = type.caption.copy(
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    ),
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

/// Caption on the left, a compact mono numeric field + unit on the right
/// (iOS numericRow 1:1).
@Composable
private fun NumericRow(
    value: String,
    onValueChange: (String) -> Unit,
    unit: String,
    caption: String,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Text(
            caption,
            style = type.caption.copy(fontSize = 11.5.sp),
            color = colors.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .background(colors.surfaceRaised)
                .border(1.dp, colors.divider, RoundedCornerShape(9.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = type.data.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                    textAlign = TextAlign.End,
                ),
                modifier = Modifier.width(76.dp),
            )
            Text(
                unit,
                style = type.dataSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = colors.textTertiary,
            )
        }
    }
}

// MARK: - Generation (the existing engine)

/// Runs SamplingGenerator over the drawn strata — or, with none drawn, the
/// centred square-ish grid around `mapCentre` — then REPLACES the
/// project's unvisited planned plots and upserts the CruiseDesign row.
/// Throws with a user-facing message on failure; the sheet surfaces it.
///
/// Returns null when the plan was written, and the confirmation sentence
/// when it stopped short of writing because the run would destroy work the
/// cruiser cannot get back — nothing has been touched in that case, and the
/// sheet asks before calling again with `confirmedDiscard = true`.
private suspend fun generateCruisePlan(
    env: AppEnvironment,
    project: Project,
    fixedRadius: Boolean,
    radiusM: Double,
    baf: Double,
    bySpacing: Boolean,
    count: Int,
    spacingM: Double,
    mapCentre: CoordinateConversions.LatLon,
    area: Stratum?,
    confirmedDiscard: Boolean = false,
): String? {
    // Opened from an area, that area is the only stratum in play.
    val strata = area?.let { listOf(it) } ?: env.stratumRepository.listByProject(project.id)
    // Which plans this run REPLACES. From an area it is that area's own
    // plans and nobody else's: generating into one outline must not wipe
    // the plan laid inside another, which is the whole-project rule and
    // would be a silent loss of work the cruiser never asked to touch.
    //
    // A VISITED plan is never in it. The moment a planned plot is opened a
    // real Plot stands on it with a tally inside, pointing back here through
    // `Plot.plannedPlotId` — and this path does not merely orphan that
    // reference the way deleting an area does, it destroys the row the
    // reference names. `deleteArea` and `relayPlots` both carry `!visited`
    // for that reason; all three paths agree, and this is the same predicate.
    val allPlanned = env.plannedPlotRepository.listByProject(project.id)
    val replacing = if (area != null) {
        allPlanned.filter { it.stratumId == area.id && !it.visited }
    } else {
        allPlanned.filter { !it.visited }
    }
    val replacingIds = replacing.map { it.id }.toSet()
    // Continue numbering after every number already spoken for — the
    // project's REAL plots, so informal plots and the plan never share a
    // number, and the plans surviving this run, so two areas never both
    // hold a "Plot 4". The visited plans this run now leaves standing are
    // among the survivors, which is what keeps a new pin off a measured
    // plot's number.
    val startNumber = maxOf(
        env.plotRepository.listByProject(project.id).maxOfOrNull { it.plotNumber } ?: 0,
        allPlanned.filter { it.id !in replacingIds }.maxOfOrNull { it.plotNumber } ?: 0,
    ) + 1

    val planned: List<PlannedPlot> = if (strata.isEmpty()) {
        centredGrid(
            projectId = project.id,
            anchor = mapCentre,
            count = if (bySpacing) 12 else count,
            spacingM = if (bySpacing) spacingM else 150.0,
            startingPlotNumber = startNumber,
        )
    } else {
        val inputs = strata.map { s ->
            SamplingGenerator.StratumInput(
                stratumId = s.id,
                rings = parseRings(s.polygonGeoJSON),
            )
        }
        SamplingGenerator.generate(
            strata = inputs,
            options = SamplingGenerator.GenerationOptions(
                projectId = project.id,
                scheme = if (bySpacing) {
                    SamplingScheme.SYSTEMATIC_GRID
                } else {
                    SamplingScheme.STRATIFIED_RANDOM
                },
                gridSpacingMeters = if (bySpacing) spacingM else null,
                nPerStratum = if (!bySpacing) count else null,
                seed = 1uL,
            ),
            startingPlotNumber = startNumber,
        )
    }

    if (planned.isEmpty()) {
        throw IllegalStateException(
            if (area == null) {
                "No plot centres fell inside the boundary — try a tighter spacing or a bigger area."
            } else {
                "No plot centres fell inside this area — try a tighter spacing, or make the area bigger."
            },
        )
    }

    // Everything above this line is a question; everything below it writes.
    // The last question is what the run costs.
    if (!confirmedDiscard) {
        val warning = discardWarning(replacing)
        if (warning != null) return warning
    }

    // Replace the plans this run is for (whole project, or just this
    // area's, and never a visited one — see `replacing`).
    for (p in replacing) env.plannedPlotRepository.delete(p.id)
    for (p in planned) env.plannedPlotRepository.create(p)

    // Upsert the single CruiseDesign record for the project.
    val existingDesign = env.cruiseDesignRepository.forProject(project.id).firstOrNull()
    val design = CruiseDesign(
        id = existingDesign?.id ?: UUID.randomUUID(),
        projectId = project.id,
        plotType = if (fixedRadius) PlotType.FIXED_AREA else PlotType.VARIABLE_RADIUS,
        plotAreaAcres = if (fixedRadius) acresFromRadiusM(radiusM).toFloat() else null,
        baf = if (!fixedRadius) baf.toFloat() else null,
        samplingScheme = if (bySpacing) {
            SamplingScheme.SYSTEMATIC_GRID
        } else {
            SamplingScheme.STRATIFIED_RANDOM
        },
        gridSpacingMeters = if (bySpacing) spacingM.toFloat() else null,
    )
    if (existingDesign != null) {
        env.cruiseDesignRepository.update(design)
    } else {
        env.cruiseDesignRepository.create(design)
    }
    return null
}

/// What a re-run takes that pressing "Generate plots" again cannot give
/// back — or null, when it takes nothing of the kind and no question is
/// worth asking. (Cross-platform: iOS `CruiseSetupSheet.discardWarning`.)
///
/// A generated pin is CHEAP to lose but not free, and the difference decides
/// what this sentence has to say.
///
/// It is cheap because replacing it is the thing the cruiser pressed the
/// button to do. It is not free because "reproducible from the design that
/// laid it" — the reason this warning used to give itself for staying quiet —
/// is not true of this code: the same run overwrites the project's single
/// CruiseDesign row with the new parameters before it returns, and
/// CruiseDesign carries no plot COUNT at all (plotType, plotAreaAcres, baf,
/// samplingScheme, gridSpacingMeters, heightSubsampleRule). For a stratified
/// design the old n therefore lived only in the plan rows this run is
/// deleting, and nothing left on disk can say what it was. So the count of
/// what goes is stated whenever anything goes, and the two kinds below are
/// called out by name on top of it, because they are gone in a way even a
/// rerun with the old numbers could not undo:
///
///   • DRAWN (`plannedSource == MANUAL`) — the cruiser put that point on the
///     map with a finger. Nothing else in the project knows where it was, so
///     a re-run is the end of it.
///   • SKIPPED — a documented decision about ground somebody went and looked
///     at (cliff, water, private land). Regenerating produces a fresh pin
///     that says nothing, and the walk gets made twice.
///
/// A drawn plot that was also skipped is counted once, as drawn: two counts
/// that add up to more plots than the plan holds would read as the run
/// destroying more than it can.
private fun discardWarning(replacing: List<PlannedPlot>): String? {
    val drawn = replacing.count { it.plannedSource == PositionSource.MANUAL }
    val skipped = replacing.count {
        it.skipped && it.plannedSource != PositionSource.MANUAL
    }
    val clauses = mutableListOf<String>()
    if (drawn > 0) clauses.add("$drawn plot${if (drawn == 1) "" else "s"} you placed by hand")
    if (skipped > 0) {
        clauses.add("$skipped plot${if (skipped == 1) "" else "s"} you marked skipped")
    }
    if (replacing.isEmpty()) return null
    val n = replacing.size
    var out = "$n planned plot${if (n == 1) "" else "s"} " +
        "${if (n == 1) "is" else "are"} replaced by the new layout"
    if (clauses.isNotEmpty()) {
        val total = drawn + skipped
        out += ", including " + clauses.joinToString(" and ") +
            " that generating again cannot put ${if (total == 1) "it" else "them"} back"
    }
    // The design goes with them: this run writes the new parameters over the
    // project's only CruiseDesign row, so the settings that produced the plan
    // being replaced are not on disk afterwards either.
    return out + ". The settings that laid the old plan are overwritten too. " +
        "Plots you have already opened are kept — this only replaces the plan."
}

/// No-polygon default pattern: a centred, walkable square-ish grid around
/// the anchor — ceil(√n) columns, rows top-to-bottom, exactly n plots at
/// `spacingM` pitch. (Cross-platform: iOS CruiseSetupSheet.centredGrid.)
internal fun centredGrid(
    projectId: UUID,
    anchor: CoordinateConversions.LatLon,
    count: Int,
    spacingM: Double,
    startingPlotNumber: Int,
): List<PlannedPlot> {
    if (count <= 0 || spacingM <= 0) return emptyList()
    val cols = ceil(sqrt(count.toDouble())).toInt()
    val rows = ceil(count.toDouble() / cols).toInt()
    val out = ArrayList<PlannedPlot>(count)
    for (i in 0 until count) {
        val row = i / cols
        val col = i % cols
        val east = (col - (cols - 1) / 2.0) * spacingM
        val north = ((rows - 1) / 2.0 - row) * spacingM
        val ll = CoordinateConversions.toLatLon(
            enu = CoordinateConversions.ENU(east = east, north = north),
            origin = anchor,
        )
        out.add(
            PlannedPlot(
                id = UUID.randomUUID(),
                projectId = projectId,
                stratumId = null,
                plotNumber = startingPlotNumber + i,
                plannedLat = ll.latitude,
                plannedLon = ll.longitude,
                visited = false,
            ),
        )
    }
    return out
}

/// Stored stratum GeoJSON → rings (extracted from the retired
/// CruiseDesignViewModel — the generator's polygon input format).
internal fun parseRings(geojson: String): List<List<CoordinateConversions.LatLon>> {
    val obj = try {
        JSONObject(geojson)
    } catch (_: Exception) {
        throw IllegalArgumentException(
            "The saved boundary couldn't be read. Draw it again, then generate the plots.")
    }
    if (obj.optString("type") != "Polygon") {
        throw IllegalArgumentException(
            "The saved boundary isn't a closed area. Draw it again, then generate the plots.")
    }
    val coords = obj.optJSONArray("coordinates")
        ?: throw IllegalArgumentException(
            "The saved boundary isn't a closed area. Draw it again, then generate the plots.")
    val rings = mutableListOf<List<CoordinateConversions.LatLon>>()
    for (ri in 0 until coords.length()) {
        val ringArray = coords.getJSONArray(ri)
        val ring = mutableListOf<CoordinateConversions.LatLon>()
        for (pi in 0 until ringArray.length()) {
            val pair = ringArray.getJSONArray(pi)
            if (pair.length() >= 2) {
                ring.add(
                    CoordinateConversions.LatLon(
                        latitude = pair.getDouble(1),
                        longitude = pair.getDouble(0),
                    ),
                )
            }
        }
        rings.add(ring)
    }
    return rings
}
