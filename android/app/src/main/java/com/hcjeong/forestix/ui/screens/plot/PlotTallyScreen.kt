// Port of iOS Screens/PlotTallyScreen.swift.
// Phase 5 §5.1 PlotTallyScreen. REQ-TAL-005/006.
//
// Live tree-tally UI for an open plot:
//   • Header stats strip: #live, TPA, BA/ac, QMD, V/ac.
//   • List of tallied trees (delete icon replaces the iOS swipe-to-
//     soft-delete; tap → TreeDetail).
//   • "Add Tree" primary button; "Close Plot" lives in the top bar as a
//     destructive action so a glove brush on a big bottom button can't
//     terminate the plot by accident.
//
// Live stats update within the REQ-TAL-005 300 ms budget because
// PlotTallyViewModel.recomputeStats() is pure and O(n_live).

package com.hcjeong.forestix.ui.screens.plot

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.ForestixScaffold
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

/// Suggested route: PlotFlowRoutes.PLOT_TALLY ("plotTally/{plotId}").
/// The optional callbacks mirror the iOS `onAddTree` / `onOpenTree` /
/// `onClosePlot` closures; defaults navigate via PlotFlowRoutes so a
/// bare route wiring still produces the full flow. `onArBoundary` mirrors
/// the iOS "AR Boundary" toolbar button that CruiseFlowScreen attaches to
/// the tally step (CruiseFlowScreen.swift:228-236) — only the cruise TALLY
/// registration supplies it, so the standalone flow matches iOS (no button).
@Composable
fun PlotTallyScreen(
    nav: NavController,
    plotId: String,
    onAddTree: () -> Unit = { nav.navigate(PlotFlowRoutes.addTreeFlow(plotId)) },
    onOpenTree: (Tree) -> Unit = { tree ->
        nav.navigate(PlotFlowRoutes.treeDetail(tree.id.toString()))
    },
    onClosePlot: () -> Unit = { nav.navigate(PlotFlowRoutes.plotSummary(plotId)) },
    onArBoundary: (() -> Unit)? = null,
) {
    val env = LocalAppEnvironment.current
    val scope = rememberCoroutineScope()
    val colors = Forestix.colors
    val type = Forestix.type

    var viewModel by remember { mutableStateOf<PlotTallyViewModel?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var closingPlot by remember { mutableStateOf(false) }

    LaunchedEffect(plotId) {
        val plot = env.plotRepository.read(UUID.fromString(plotId))
        val project = plot?.let { env.projectRepository.read(it.projectId) }
        val design = project?.let {
            env.cruiseDesignRepository.forProject(it.id).firstOrNull()
        }
        if (plot == null || project == null || design == null) {
            loadError = "Plot, project, or cruise design not found."
        } else {
            val vm = PlotTallyViewModel(
                project = project,
                design = design,
                plot = plot,
                plotRepo = env.plotRepository,
                treeRepo = env.treeRepository,
                speciesRepo = env.speciesConfigRepository,
                volRepo = env.volumeEquationRepository,
                hdFitRepo = env.heightDiameterFitRepository)
            vm.refresh()   // iOS `.onAppear { viewModel.refresh() }`
            viewModel = vm
        }
    }

    val vm = viewModel
    if (vm == null) {
        ForestixScaffold(nav, title = "Plot") { padding ->
            Box(
                Modifier.padding(padding).fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (loadError != null) {
                    Text(loadError ?: "", style = type.body, color = colors.textSecondary)
                } else {
                    CircularProgressIndicator()
                }
            }
        }
        return
    }

    val trees by vm.trees.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val errorMessage by vm.errorMessage.collectAsStateWithLifecycle()

    // Derived on every trees change — same as the iOS computed vars.
    val liveTrees = remember(trees) { trees.filter { it.deletedAt == null } }
    val softDeletedTrees = remember(trees) { trees.filter { it.deletedAt != null } }

    ForestixScaffold(
        nav,
        title = "Plot ${vm.plot.plotNumber}",
        actions = {
            // iOS CruiseFlowScreen toolbar: "AR Boundary" (circle.dashed)
            // pushes the AR plot-boundary walk for the current plot.
            if (onArBoundary != null) {
                IconButton(onClick = onArBoundary) {
                    Icon(
                        Icons.Filled.RadioButtonUnchecked,
                        contentDescription = "AR Boundary",
                        tint = colors.primary)
                }
            }
            // Close Plot is consequential (stamps closedAt, runs HD rollup,
            // ends the plot) — kept in the top bar per the iOS toolbar.
            IconButton(onClick = { closingPlot = true }) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Close Plot",
                    tint = colors.confidenceBad)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // MARK: - Stats strip
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceRaised)
                    .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
            ) {
                StatCell(label = "Live", value = "${stats.liveTreeCount}",
                    modifier = Modifier.weight(1f))
                StatCell(label = "Trees/ac",
                    value = String.format(Locale.US, "%.1f", stats.tpa),
                    modifier = Modifier.weight(1f))
                StatCell(label = "Basal m²/ac",
                    value = String.format(Locale.US, "%.2f", stats.baPerAcreM2),
                    modifier = Modifier.weight(1f))
                StatCell(label = "Mean DBH cm",
                    value = String.format(Locale.US, "%.1f", stats.qmdCm),
                    modifier = Modifier.weight(1f))
                StatCell(label = "Volume m³/ac",
                    value = String.format(Locale.US, "%.1f", stats.grossVolumePerAcreM3),
                    modifier = Modifier.weight(1f))
            }

            // MARK: - List
            LazyColumn(Modifier.weight(1f)) {
                item {
                    ListHeader("Trees (${liveTrees.size})")
                }
                items(liveTrees, key = { it.id }) { tree ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableNoRipple { onOpenTree(tree) }
                            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TreeRow(tree, Modifier.weight(1f))
                        // Android stand-in for the iOS trailing swipe action.
                        IconButton(onClick = { scope.launch { vm.softDelete(tree.id) } }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete",
                                tint = colors.confidenceBad,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                }

                if (softDeletedTrees.isNotEmpty()) {
                    item {
                        ListHeader("Deleted (${softDeletedTrees.size})")
                    }
                    items(softDeletedTrees, key = { it.id }) { tree ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.xs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TreeRow(tree, Modifier.weight(1f).alpha(0.5f))
                            TextButton(onClick = { scope.launch { vm.undelete(tree.id) } }) {
                                Text("Undo", style = type.caption, color = colors.primary)
                            }
                        }
                        HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
                    }
                }
            }

            HorizontalDivider(color = colors.divider, thickness = 0.5.dp)

            // MARK: - Action row
            //
            // Only "Add Tree" lives here — the action you press 30+ times
            // per plot deserves the big bottom button; the terminal action
            // shouldn't share the same tap zone.
            Row(
                Modifier.fillMaxWidth().padding(ForestixSpace.md),
                horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
            ) {
                Button(
                    onClick = onAddTree,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) {
                    Icon(
                        Icons.Filled.AddCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(ForestixSpace.xs))
                    Text("Add Tree")
                }
            }
        }
    }

    // MARK: - Close confirmation (iOS confirmationDialog)
    if (closingPlot) {
        val n = liveTrees.size
        AlertDialog(
            onDismissRequest = { closingPlot = false },
            title = { Text("Close this plot?") },
            text = {
                Text("$n live tree${if (n == 1) "" else "s"} tallied. " +
                    "Closing pushes to Summary where you can confirm or re-open.")
            },
            confirmButton = {
                TextButton(onClick = {
                    closingPlot = false
                    onClosePlot()
                }) {
                    Text("Review + close", color = colors.confidenceBad)
                }
            },
            dismissButton = {
                TextButton(onClick = { closingPlot = false }) {
                    Text("Keep tallying")
                }
            },
        )
    }

    // MARK: - Error alert
    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { vm.clearError() },
            title = { Text("Error") },
            text = { Text(errorMessage ?: "") },
            confirmButton = {
                TextButton(onClick = { vm.clearError() }) { Text("OK") }
            },
        )
    }
}

// MARK: - Stats strip cell

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = type.data, color = colors.textPrimary, maxLines = 1)
        Text(label, style = type.caption, color = colors.textSecondary, maxLines = 1)
    }
}

// MARK: - List section header

@Composable
private fun ListHeader(text: String) {
    Text(
        text,
        style = Forestix.type.sectionHead,
        color = Forestix.colors.textTertiary,
        modifier = Modifier.padding(
            start = ForestixSpace.md,
            top = ForestixSpace.sm,
            bottom = ForestixSpace.xxs))
}

// MARK: - Tree row

@Composable
private fun TreeRow(tree: Tree, modifier: Modifier = Modifier) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Text(
            "#${tree.treeNumber}",
            style = type.data,
            color = colors.textPrimary,
            modifier = Modifier.width(44.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tree.speciesCode, style = type.body, color = colors.textPrimary)
            Text(tree.status.raw, style = type.caption, color = colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                String.format(Locale.US, "%.1f cm", tree.dbhCm),
                style = type.data,
                color = colors.textPrimary)
            val h = tree.heightM
            if (h != null) {
                Text(
                    String.format(Locale.US, "%.1f m", h),
                    style = type.dataSmall,
                    color = colors.textSecondary)
            }
        }
        ConfidenceDot(tree.dbhConfidence)
        if (tree.isMultistem) {
            Icon(
                Icons.Filled.CallSplit,
                contentDescription = "Multistem",
                tint = colors.textSecondary,
                modifier = Modifier.size(12.dp))
        }
    }
}

/// Route through confidenceDescriptor so every surface shows the same
/// muted instrument-grade tier hue (matches iOS ConfidenceStyle).
@Composable
private fun ConfidenceDot(tier: ConfidenceTier) {
    Box(
        Modifier
            .size(10.dp)
            .background(confidenceDescriptor(tier.raw).color, CircleShape))
}
