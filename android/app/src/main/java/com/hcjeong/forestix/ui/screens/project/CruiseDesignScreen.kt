// Port of iOS Screens/CruiseDesignScreen.swift.
// Spec §3.1 REQ-PRJ-003 + REQ-PRJ-004. Configure cruise design and generate
// planned plots. Includes the Phase 7.3 read-only species + volume-equation
// catalogue section so the cruiser can confirm which species are available
// for this project before field work.

package com.hcjeong.forestix.ui.screens.project

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.data.cruise.VolumeEquation
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

@Composable
fun CruiseDesignScreen(nav: NavController, projectId: String) {
    val env = LocalAppEnvironment.current
    var project by remember { mutableStateOf<Project?>(null) }
    LaunchedEffect(projectId) {
        project = env.projectRepository.read(UUID.fromString(projectId))
    }
    val loaded = project
    if (loaded == null) {
        ForestixScaffold(nav, title = "Cruise Design") { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    CruiseDesignContent(nav, loaded)
}

@Composable
private fun CruiseDesignContent(nav: NavController, project: Project) {
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type
    val viewModel = remember(project.id) { CruiseDesignViewModel(project) }

    val plotType by viewModel.plotType.collectAsState()
    val plotAreaAcresString by viewModel.plotAreaAcresString.collectAsState()
    val bafString by viewModel.bafString.collectAsState()
    val samplingScheme by viewModel.samplingScheme.collectAsState()
    val gridSpacingMetersString by viewModel.gridSpacingMetersString.collectAsState()
    val nPerStratumString by viewModel.nPerStratumString.collectAsState()
    val seedString by viewModel.seedString.collectAsState()
    val plannedCount by viewModel.plannedCount.collectAsState()
    val availableSpecies by viewModel.availableSpecies.collectAsState()
    val volumeEquationsById by viewModel.volumeEquationsById.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    // iOS `.task` — configure repositories and load the saved design once.
    LaunchedEffect(Unit) {
        viewModel.configure(env)
        viewModel.refresh()
    }

    LaunchedEffect(toastMessage) {
        val message = toastMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.toastMessage.value = null
    }

    ForestixScaffold(nav, title = "Cruise Design") { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // MARK: - Plot Type
            FormSection(header = "Plot Type") {
                Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                    FilterChip(
                        selected = plotType == PlotType.FIXED_AREA,
                        onClick = { viewModel.plotType.value = PlotType.FIXED_AREA },
                        label = { Text("Fixed-area") },
                    )
                    FilterChip(
                        selected = plotType == PlotType.VARIABLE_RADIUS,
                        onClick = { viewModel.plotType.value = PlotType.VARIABLE_RADIUS },
                        label = { Text("Variable-radius (prism)") },
                    )
                }
                if (plotType == PlotType.FIXED_AREA) {
                    LabeledField(
                        title = "Plot area (acres)",
                        text = plotAreaAcresString,
                        onTextChange = { viewModel.plotAreaAcresString.value = it },
                    )
                } else {
                    LabeledField(
                        title = "Basal area factor (ft²/ac)",
                        text = bafString,
                        onTextChange = { viewModel.bafString.value = it },
                    )
                }
            }

            // MARK: - Sampling
            FormSection(header = "Sampling") {
                Text("Scheme", style = type.body, color = colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                    FilterChip(
                        selected = samplingScheme == SamplingScheme.SYSTEMATIC_GRID,
                        onClick = { viewModel.samplingScheme.value = SamplingScheme.SYSTEMATIC_GRID },
                        label = { Text("Systematic grid") },
                    )
                    FilterChip(
                        selected = samplingScheme == SamplingScheme.STRATIFIED_RANDOM,
                        onClick = { viewModel.samplingScheme.value = SamplingScheme.STRATIFIED_RANDOM },
                        label = { Text("Stratified random") },
                    )
                    FilterChip(
                        selected = samplingScheme == SamplingScheme.MANUAL,
                        onClick = { viewModel.samplingScheme.value = SamplingScheme.MANUAL },
                        label = { Text("Manual") },
                    )
                }
                when (samplingScheme) {
                    SamplingScheme.SYSTEMATIC_GRID ->
                        LabeledField(
                            title = "Grid spacing (m)",
                            text = gridSpacingMetersString,
                            onTextChange = { viewModel.gridSpacingMetersString.value = it },
                        )
                    SamplingScheme.STRATIFIED_RANDOM ->
                        LabeledField(
                            title = "Plots per stratum",
                            text = nPerStratumString,
                            onTextChange = { viewModel.nPerStratumString.value = it },
                        )
                    SamplingScheme.MANUAL ->
                        Text(
                            "Manual mode lets you tap the map to place plots. Generation is a no-op.",
                            style = type.caption, color = colors.textSecondary)
                }
                LabeledField(
                    title = "Random seed",
                    text = seedString,
                    onTextChange = { viewModel.seedString.value = it },
                )
            }

            // MARK: - Plan
            FormSection(header = "Plan") {
                LabeledContentRow("Planned plots", "$plannedCount")
                Button(
                    onClick = { scope.launch { viewModel.generatePlannedPlots() } },
                    enabled = viewModel.isValid,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                ) {
                    Text("Generate planned plots", style = type.bodyBold)
                }
            }

            // MARK: - Species & equations (Phase 7.3, read-only)
            FormSection(
                header = "Species & volume equations",
                footer = if (availableSpecies.isEmpty()) {
                    "No species loaded. Re-install the app so the Pacific Northwest seed (DF, WH, RC, RA) is re-imported."
                } else {
                    "These species + volume equations are available for this project. Coefficients marked PLACEHOLDER in the source citation should be replaced with locally-calibrated values before production cruising — see docs/VOLUME_EQUATIONS.md."
                },
            ) {
                if (availableSpecies.isEmpty()) {
                    Text("No species configured.", style = type.body, color = colors.textSecondary)
                } else {
                    availableSpecies.forEach { sp ->
                        SpeciesRow(sp, volumeEquationsById[sp.volumeEquationId])
                    }
                }
            }

            // MARK: - Validation banner
            val message = viewModel.validationMessage
            if (message != null) {
                FormSection {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                    ) {
                        Icon(Icons.Filled.Warning, contentDescription = null,
                            tint = colors.confidenceWarn, modifier = Modifier.size(18.dp))
                        Text(message, style = type.body, color = colors.confidenceWarn)
                    }
                }
            }

            Spacer(Modifier.height(ForestixSpace.xl))
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.errorMessage.value = null },
            title = { Text("Something went wrong") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.errorMessage.value = null }) { Text("OK") }
            },
        )
    }
}

// MARK: - Species row

@Composable
private fun SpeciesRow(sp: SpeciesConfig, eq: VolumeEquation?) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier.padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                sp.code,
                style = type.data,
                color = colors.textPrimary,
                modifier = Modifier.width(44.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sp.commonName, style = type.body, color = colors.textPrimary)
                Text(
                    sp.scientificName,
                    style = type.caption.copy(fontStyle = FontStyle.Italic),
                    color = colors.textSecondary)
            }
        }
        if (eq != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    eq.form,
                    style = type.dataSmall,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .clip(ForestixRadius.chip)
                        .background(colors.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (eq.sourceCitation.uppercase(Locale.US).contains("PLACEHOLDER")) {
                    Icon(Icons.Filled.Warning, contentDescription = null,
                        tint = colors.confidenceWarn, modifier = Modifier.size(14.dp))
                    Text("placeholder", style = type.caption, color = colors.confidenceWarn)
                } else {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null,
                        tint = colors.confidenceOk, modifier = Modifier.size(14.dp))
                    Text("verified", style = type.caption, color = colors.confidenceOk)
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.Dangerous, contentDescription = null,
                    tint = colors.confidenceBad, modifier = Modifier.size(14.dp))
                Text(
                    "Missing equation ${sp.volumeEquationId}",
                    style = type.caption, color = colors.confidenceBad)
            }
        }
    }
}

// MARK: - Labeled numeric field (iOS LabeledField)

@Composable
private fun LabeledField(
    title: String,
    text: String,
    onTextChange: (String) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = type.data.fontSize,
                fontWeight = type.data.fontWeight,
                fontFamily = type.data.fontFamily,
                color = colors.textPrimary,
                textAlign = TextAlign.End,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .width(120.dp)
                .clip(ForestixRadius.chip)
                .background(colors.surfaceRaised)
                .padding(horizontal = ForestixSpace.xs, vertical = 6.dp),
        )
    }
}
