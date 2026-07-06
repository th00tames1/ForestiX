// Port of iOS Screens/AddTreeFlowScreen.swift.
// Phase 5 §5.2 AddTreeFlowScreen. REQ-TAL-001..004, REQ-HGT-007.
//
// 5-step stepper UI driven by AddTreeFlowViewModel:
//   1. Species quick-tap (recent 5 species, 3-col grid, 56dp buttons) +
//      alphabetical search list fallback + push-to-talk voice picker.
//   2. DBH number entry + method picker + irregular toggle + AR scan.
//   3. Height (only shown when subsample rule demands it; skippable).
//   4. Extras (status, crown class, notes, bearing/distance).
//   5. Review (confidence tiers, red-tier warning, Save + Save & add stem).
//
// Phase 7.1 fix (Android shape): the "Scan" buttons in the DBH and Height
// steps navigate to DBHScanScreen / HeightScanScreen; because those screens
// record their accepted result into the quick-measure history rather than
// invoking a callback, this screen watches `env.history.entries` for a new
// entry appended after the scan was launched and writes it back into the
// stepper VM, so the cruiser doesn't have to type measurements by hand.

package com.hcjeong.forestix.ui.screens.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.DBHMethod
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.sensors.HeightMethod
import com.hcjeong.forestix.sensors.ProjectCalibration
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

// MARK: - Retained state

/// Retains the flow VM + scan-await flags across navigation to the scan
/// screens. A plain `remember` would be dropped when this destination
/// leaves composition while sitting on the back stack; this holder is
/// scoped to the destination's NavBackStackEntry, so it survives the
/// push/pop and is cleared when the flow itself is popped.
class AddTreeFlowRetained : androidx.lifecycle.ViewModel() {
    var flow: AddTreeFlowViewModel? = null
    val awaitingScanKind = mutableStateOf<MeasureKind?>(null)
    var scanLaunchedAt: Long = 0L

    /// Environment whose `activeScanCalibration` this flow published the
    /// project calibration into (iOS injects ProjectCalibration into the
    /// scan view models — AddTreeFlowScreen.swift `calibration(from:)`).
    /// Restored to identity when the flow's back-stack entry is cleared,
    /// so the reset survives the push to the shared scan screens.
    var scanCalibrationEnv: AppEnvironment? = null

    override fun onCleared() {
        scanCalibrationEnv?.activeScanCalibration?.value = ProjectCalibration.identity
    }
}

// MARK: - Loader

/// Loads the plot + project + design + species catalogue, then hands off to
/// the stepper content. iOS receives all of these via the initializer; on
/// Android the route only carries the plot id.
@Composable
fun AddTreeFlowScreen(
    nav: NavController,
    plotId: String,
    onSaved: (Tree) -> Unit = {},
) {
    val env = LocalAppEnvironment.current
    val retained: AddTreeFlowRetained = viewModel()
    var flowViewModel by remember { mutableStateOf(retained.flow) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(plotId) {
        if (retained.flow == null) {
            try {
                val plot = env.plotRepository.read(UUID.fromString(plotId))
                    ?: throw IllegalStateException("Plot not found")
                val project = env.projectRepository.read(plot.projectId)
                    ?: throw IllegalStateException("Project not found")
                val design = env.cruiseDesignRepository.forProject(plot.projectId).firstOrNull()
                    ?: CruiseDesign(
                        // Fallback design so the flow still works for plots created
                        // outside the full design pipeline (default EveryKth(5)
                        // height subsample matches the model default).
                        id = UUID.randomUUID(),
                        projectId = plot.projectId,
                        plotType = PlotType.FIXED_AREA,
                        plotAreaAcres = plot.plotAreaAcres,
                        baf = null,
                        samplingScheme = SamplingScheme.MANUAL,
                        gridSpacingMeters = null)
                val existingTrees = env.treeRepository.listByPlot(plot.id)
                val speciesByCode = env.speciesConfigRepository.list().associateBy { it.code }
                val recent = env.treeRepository.recentSpeciesCodes(plot.projectId, 5)
                retained.flow = AddTreeFlowViewModel(
                    project = project,
                    design = design,
                    plot = plot,
                    existingTrees = existingTrees,
                    speciesByCode = speciesByCode,
                    treeRepo = env.treeRepository,
                    recentSpeciesCodes = recent)
            } catch (e: Exception) {
                loadError = "Failed to load plot: ${e.message ?: e}"
            }
        }
        flowViewModel = retained.flow
        // Publish the project's calibration for the shared scan screens
        // (iOS: ProjectCalibration built from viewModel.project and injected
        // into DBHScanViewModel / HeightScanViewModel). Identity is restored
        // by AddTreeFlowRetained.onCleared when the flow is popped.
        retained.flow?.let { f ->
            retained.scanCalibrationEnv = env
            env.activeScanCalibration.value = ProjectCalibration(
                depthNoiseMm = f.project.depthNoiseMm,
                dbhCorrectionAlpha = f.project.dbhCorrectionAlpha,
                dbhCorrectionBeta = f.project.dbhCorrectionBeta,
                vioDriftFraction = f.project.vioDriftFraction)
        }
    }

    val vm = flowViewModel
    if (vm == null) {
        ForestixScaffold(nav, title = "Add tree") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                loadError?.let { Text(it, style = Forestix.type.body, color = Forestix.colors.confidenceBad) }
                    ?: CircularProgressIndicator()
            }
        }
        return
    }
    AddTreeFlowContent(nav, vm, retained, onSaved)
}

// MARK: - Content

@Composable
private fun AddTreeFlowContent(
    nav: NavController,
    viewModel: AddTreeFlowViewModel,
    retained: AddTreeFlowRetained,
    onSaved: (Tree) -> Unit,
) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors

    val currentStep by viewModel.currentStep.collectAsState()
    val stepHistory by viewModel.history.collectAsState()
    val speciesCode by viewModel.speciesCode.collectAsState()
    val dbhCm by viewModel.dbhCm.collectAsState()
    val heightM by viewModel.heightM.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val savedTree by viewModel.savedTree.collectAsState()

    // Recomputed whenever a gating input changes (iOS reads the @Published
    // values directly, so SwiftUI re-evaluates for free).
    val canAdvance = remember(currentStep, speciesCode, dbhCm, heightM) { viewModel.canAdvance() }

    // iOS `.onChange(of: viewModel.savedTree?.id)` → onSaved callback.
    LaunchedEffect(savedTree?.id) {
        savedTree?.let(onSaved)
    }

    // Scan write-back: DBHScanScreen / HeightScanScreen append their accepted
    // reading to env.history; watch for an entry newer than the launch.
    val historyEntries by env.history.entries.collectAsState()
    var awaitingScanKind by retained.awaitingScanKind
    LaunchedEffect(historyEntries, awaitingScanKind) {
        val kind = awaitingScanKind ?: return@LaunchedEffect
        val entry = historyEntries.firstOrNull { it.kind == kind && it.createdAt >= retained.scanLaunchedAt }
            ?: return@LaunchedEffect
        val tier = ConfidenceTier.entries.firstOrNull { it.raw == entry.confidenceRaw }
            ?: ConfidenceTier.YELLOW
        when (kind) {
            MeasureKind.DBH -> viewModel.applyDBHScan(entry.value.toFloat(), tier)
            MeasureKind.HEIGHT -> viewModel.applyHeightScan(entry.value.toFloat(), tier)
            else -> Unit
        }
        awaitingScanKind = null
    }

    ForestixScaffold(nav, title = "Tree #${viewModel.nextTreeNumber}") { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // MARK: Progress strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AddTreeFlowViewModel.Step.entries.forEach { step ->
                    val fill = when {
                        step.ordinal < currentStep.ordinal -> colors.primary
                        step == currentStep -> colors.primary.copy(alpha = 0.6f)
                        else -> colors.divider
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(fill),
                    )
                }
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // MARK: Step content
            Box(Modifier.weight(1f)) {
                when (currentStep) {
                    AddTreeFlowViewModel.Step.SPECIES -> SpeciesStep(viewModel)
                    AddTreeFlowViewModel.Step.DBH -> DbhStep(viewModel) {
                        awaitingScanKind = MeasureKind.DBH
                        retained.scanLaunchedAt = System.currentTimeMillis()
                        nav.navigate(Routes.DBH)
                    }
                    AddTreeFlowViewModel.Step.HEIGHT -> HeightStep(viewModel) {
                        awaitingScanKind = MeasureKind.HEIGHT
                        retained.scanLaunchedAt = System.currentTimeMillis()
                        nav.navigate("height?tree=${viewModel.nextTreeNumber}")
                    }
                    AddTreeFlowViewModel.Step.EXTRAS -> ExtrasStep(viewModel)
                    AddTreeFlowViewModel.Step.REVIEW -> ReviewStep(viewModel)
                }
            }
            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // MARK: Action bar
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stepHistory.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.back() }) { Text("Back") }
                }
                Spacer(Modifier.weight(1f))
                if (currentStep == AddTreeFlowViewModel.Step.HEIGHT) {
                    OutlinedButton(onClick = { viewModel.skipHeight() }) { Text("Skip") }
                }
                if (currentStep == AddTreeFlowViewModel.Step.REVIEW) {
                    // Three save paths so the cruiser doesn't get dumped back
                    // to the plot list after every tree:
                    //   • Save & add stem    (multi-stem child of this tree)
                    //   • Save & next tree   (stay in flow, fresh tree)
                    //   • Save & close       (close flow, go to plot)
                    var saveMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        Button(onClick = { saveMenuOpen = true }, enabled = !isSaving) {
                            Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                        DropdownMenu(expanded = saveMenuOpen, onDismissRequest = { saveMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Save & add stem") },
                                onClick = {
                                    saveMenuOpen = false
                                    scope.launch {
                                        viewModel.save()
                                        if (viewModel.errorMessage.value == null) {
                                            viewModel.prepareMultistemChild()
                                        }
                                    }
                                })
                            DropdownMenuItem(
                                text = { Text("Save & next tree") },
                                onClick = {
                                    saveMenuOpen = false
                                    scope.launch {
                                        viewModel.save()
                                        if (viewModel.errorMessage.value == null) {
                                            viewModel.resetForNextTree()
                                        }
                                    }
                                })
                            DropdownMenuItem(
                                text = { Text("Save & close") },
                                onClick = {
                                    saveMenuOpen = false
                                    scope.launch {
                                        viewModel.save()
                                        if (viewModel.errorMessage.value == null) {
                                            nav.popBackStack()
                                        }
                                    }
                                })
                        }
                    }
                } else {
                    Button(onClick = { viewModel.advance() }, enabled = canAdvance) {
                        Text("Next")
                    }
                }
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

// MARK: - Species step

@Composable
private fun SpeciesStep(viewModel: AddTreeFlowViewModel) {
    val colors = Forestix.colors
    val type = Forestix.type
    val speciesCode by viewModel.speciesCode.collectAsState()
    val recentSpeciesCodes by viewModel.recentSpeciesCodes.collectAsState()
    val allSpecies = remember(viewModel) {
        viewModel.speciesByCode.values.sortedBy { it.code }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        VoiceSpeciesPicker(
            candidates = allSpecies.map {
                SpeciesVoiceCandidate(it.code, it.commonName, it.scientificName)
            },
            onMatch = { viewModel.speciesCode.value = it },
        )
        if (recentSpeciesCodes.isNotEmpty()) {
            Text("Recent", style = type.bodyBold, color = colors.textPrimary)
            recentSpeciesCodes.chunked(3).forEach { rowCodes ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCodes.forEach { code ->
                        SpeciesButton(
                            code = code,
                            species = viewModel.speciesByCode[code],
                            selected = speciesCode == code,
                            modifier = Modifier.weight(1f),
                        ) { viewModel.speciesCode.value = code }
                    }
                    repeat(3 - rowCodes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        Text(
            "All species",
            style = type.bodyBold,
            color = colors.textPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column {
            allSpecies.forEach { sp ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { viewModel.speciesCode.value = sp.code }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(sp.code, style = type.data, color = colors.textPrimary, modifier = Modifier.width(56.dp))
                    Text(sp.commonName, style = type.body, color = colors.textPrimary)
                    Spacer(Modifier.weight(1f))
                    if (speciesCode == sp.code) {
                        Icon(Icons.Filled.Check, contentDescription = "Selected",
                            tint = colors.primary, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
            }
        }
    }
}

@Composable
private fun SpeciesButton(
    code: String,
    species: SpeciesConfig?,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier
            .heightIn(min = 56.dp)
            .clip(ForestixRadius.control)
            .background(if (selected) colors.primary else colors.surface)
            .clickableNoRipple(onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(code, style = type.dataLarge, color = if (selected) Color.White else colors.textPrimary)
        species?.let {
            Text(
                it.commonName,
                style = type.caption,
                color = if (selected) Color.White.copy(alpha = 0.85f) else colors.textSecondary,
                maxLines = 1,
            )
        }
    }
}

// MARK: - DBH step

@Composable
private fun DbhStep(viewModel: AddTreeFlowViewModel, onScan: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    val dbhCm by viewModel.dbhCm.collectAsState()
    val dbhMethod by viewModel.dbhMethod.collectAsState()
    val dbhIsIrregular by viewModel.dbhIsIrregular.collectAsState()
    val speciesCode by viewModel.speciesCode.collectAsState()

    FormColumn {
        FormSection {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.Sensors, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan with AR", style = type.bodyBold)
            }
        }
        FormSection(header = "DBH (cm)") {
            NumberFieldRow(
                value = dbhCm.takeIf { it > 0f },
                onValue = { viewModel.dbhCm.value = it ?: 0f },
                placeholder = "0.0",
                unit = "cm",
            )
        }
        FormSection(header = "Method") {
            DropdownRow(
                title = "Method",
                options = DbhMethodOptions.map { it.first },
                selectedLabel = DbhMethodOptions.firstOrNull { it.second == dbhMethod }?.first
                    ?: dbhMethod.raw,
                onSelect = { idx -> viewModel.dbhMethod.value = DbhMethodOptions[idx].second },
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Irregular cross-section", style = type.body, color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                Switch(checked = dbhIsIrregular, onCheckedChange = { viewModel.dbhIsIrregular.value = it })
            }
        }
        viewModel.speciesByCode[speciesCode]?.let { sp ->
            FormSection(header = "Species range") {
                Text(
                    String.format(Locale.US, "%.1f–%.1f cm", sp.expectedDbhMinCm, sp.expectedDbhMaxCm),
                    style = type.dataSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/// DBH method picker choices. The first five rows mirror the iOS picker
/// (AddTreeFlowScreen.swift Caliper / Visual / LiDAR — single / dual /
/// irregular); the remaining rows are the Android-available capture
/// methods, whose raw values also exist in the iOS enum.
private val DbhMethodOptions = listOf(
    "Caliper" to DBHMethod.MANUAL_CALIPER,
    "Visual" to DBHMethod.MANUAL_VISUAL,
    "LiDAR — single" to DBHMethod.LIDAR_PARTIAL_ARC_SINGLE_VIEW,
    "LiDAR — dual" to DBHMethod.LIDAR_PARTIAL_ARC_DUAL_VIEW,
    "LiDAR — irregular" to DBHMethod.LIDAR_IRREGULAR,
    "LiDAR — chord" to DBHMethod.LIDAR_CHORD_SILHOUETTE,
    "AR caliper" to DBHMethod.AR_CALIPER,
    "AR motion" to DBHMethod.AR_VIO_CIRCLE_FIT,
)

// MARK: - Height step

@Composable
private fun HeightStep(viewModel: AddTreeFlowViewModel, onScan: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    val heightM by viewModel.heightM.collectAsState()
    val heightMethod by viewModel.heightMethod.collectAsState()
    val speciesCode by viewModel.speciesCode.collectAsState()

    FormColumn {
        FormSection {
            Text(
                "Subsample rule requires a measured height for this tree.",
                style = type.body,
                color = colors.textSecondary,
            )
        }
        FormSection {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Filled.Sensors, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Scan with VIO walk-off", style = type.bodyBold)
            }
        }
        FormSection(header = "Height (m)") {
            NumberFieldRow(
                value = heightM,
                onValue = { viewModel.heightM.value = if (it != null && it > 0f) it else null },
                placeholder = "0.0",
                unit = "m",
            )
        }
        FormSection(header = "Method") {
            DropdownRow(
                title = "Method",
                options = HeightMethodOptions.map { it.first },
                selectedLabel = HeightMethodOptions
                    .firstOrNull { it.second == (heightMethod ?: HeightMethod.MANUAL_ENTRY) }?.first
                    ?: (heightMethod ?: HeightMethod.MANUAL_ENTRY).raw,
                onSelect = { idx -> viewModel.heightMethod.value = HeightMethodOptions[idx].second },
            )
        }
        viewModel.speciesByCode[speciesCode]?.let { sp ->
            FormSection(header = "Species range") {
                Text(
                    String.format(Locale.US, "%.1f–%.1f m", sp.expectedHeightMinM, sp.expectedHeightMaxM),
                    style = type.dataSmall,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

private val HeightMethodOptions = listOf(
    "Manual entry" to HeightMethod.MANUAL_ENTRY,
    "Tape + tangent" to HeightMethod.TAPE_TANGENT,
    "VIO walk-off" to HeightMethod.VIO_WALKOFF_TANGENT,
)

// MARK: - Extras step

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ExtrasStep(viewModel: AddTreeFlowViewModel) {
    val status by viewModel.status.collectAsState()
    val crownClass by viewModel.crownClass.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val bearing by viewModel.bearingFromCenterDeg.collectAsState()
    val distance by viewModel.distanceFromCenterM.collectAsState()

    FormColumn {
        FormSection(header = "Status") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusOptions.forEach { (label, value) ->
                    FilterChip(
                        selected = status == value,
                        onClick = { viewModel.status.value = value },
                        label = { Text(label) },
                    )
                }
            }
        }
        FormSection(header = "Crown class") {
            OutlinedTextField(
                value = crownClass ?: "",
                onValueChange = { viewModel.crownClass.value = it.ifEmpty { null } },
                placeholder = { Text("e.g. dominant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        FormSection(header = "Placement") {
            NumberFieldRow(
                value = bearing,
                onValue = { viewModel.bearingFromCenterDeg.value = it },
                placeholder = "Bearing °",
                unit = "°",
            )
            NumberFieldRow(
                value = distance,
                onValue = { viewModel.distanceFromCenterM.value = it },
                placeholder = "Distance m",
                unit = "m",
            )
        }
        FormSection(header = "Notes") {
            OutlinedTextField(
                value = notes,
                onValueChange = { viewModel.notes.value = it },
                placeholder = { Text("Optional notes") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val StatusOptions = listOf(
    "Live" to TreeStatus.LIVE,
    "Dead standing" to TreeStatus.DEAD_STANDING,
    "Dead down" to TreeStatus.DEAD_DOWN,
    "Cull" to TreeStatus.CULL,
)

// MARK: - Review step

@Composable
private fun ReviewStep(viewModel: AddTreeFlowViewModel) {
    val colors = Forestix.colors
    val type = Forestix.type
    val speciesCode by viewModel.speciesCode.collectAsState()
    val dbhCm by viewModel.dbhCm.collectAsState()
    val dbhConfidence by viewModel.dbhConfidence.collectAsState()
    val heightM by viewModel.heightM.collectAsState()
    val heightConfidence by viewModel.heightConfidence.collectAsState()
    val redTierWarning by viewModel.redTierWarning.collectAsState()
    val isMultistem by viewModel.isMultistem.collectAsState()

    FormColumn {
        FormSection(header = "Species") {
            LabeledRow("Code", speciesCode)
            viewModel.speciesByCode[speciesCode]?.let { sp ->
                LabeledRow("Common", sp.commonName)
            }
        }
        FormSection(header = "DBH") {
            LabeledRow("Value", String.format(Locale.US, "%.1f cm", dbhCm))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Confidence", style = type.body, color = colors.textPrimary)
                Spacer(Modifier.weight(1f))
                TierBadge(dbhConfidence)
            }
        }
        FormSection(header = "Height") {
            val h = heightM
            if (h != null) {
                LabeledRow("Value", String.format(Locale.US, "%.1f m", h))
                heightConfidence?.let { tier ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Confidence", style = type.body, color = colors.textPrimary)
                        Spacer(Modifier.weight(1f))
                        TierBadge(tier)
                    }
                }
            } else {
                Text(
                    "Not measured — will be imputed from H–D model.",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
        redTierWarning?.let { warn ->
            FormSection(header = "Red-tier warning") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null,
                        tint = colors.confidenceBad, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(warn, style = type.body, color = colors.confidenceBad)
                }
            }
        }
        if (isMultistem) {
            FormSection {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CallSplit, contentDescription = null,
                        tint = colors.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Multistem child stem", style = type.body, color = colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun TierBadge(tier: ConfidenceTier) {
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

// MARK: - Form helpers (iOS `Form`/`Section` analogue)

@Composable
private fun FormColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        content()
    }
}

@Composable
private fun FormSection(header: String? = null, content: @Composable () -> Unit) {
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
private fun LabeledRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.body, color = colors.textSecondary)
    }
}

/// Numeric entry with a trailing unit label. The text is locally owned so
/// typing doesn't fight the Float round-trip; external VM updates (scan
/// write-back, multistem reset) re-seed the text via the LaunchedEffect.
@Composable
private fun NumberFieldRow(
    value: Float?,
    onValue: (Float?) -> Unit,
    placeholder: String,
    unit: String,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    var text by remember { mutableStateOf(value?.let { formatNumber(it) } ?: "") }
    LaunchedEffect(value) {
        val parsed = text.toFloatOrNull()
        val matches = when {
            value == null -> parsed == null || parsed == 0f
            else -> parsed != null && kotlin.math.abs(parsed - value) < 1e-4f
        }
        if (!matches) {
            text = value?.let { formatNumber(it) } ?: ""
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

private fun formatNumber(v: Float): String =
    if (v == v.toLong().toFloat()) String.format(Locale.US, "%d", v.toLong())
    else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

/// Menu-style picker row (iOS `Picker(...).pickerStyle(.menu)` analogue).
@Composable
private fun DropdownRow(
    title: String,
    options: List<String>,
    selectedLabel: String,
    onSelect: (Int) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.body, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Box {
            Row(
                Modifier.clickableNoRipple { open = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selectedLabel, style = type.body, color = colors.primary)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = colors.primary)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { idx, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSelect(idx)
                        })
                }
            }
        }
    }
}
