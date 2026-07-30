// Field log — port of iOS FieldLogScreen.
//
// FIELD REPORT 5 — the log is now ONE ROW PER TREE.
//
// It used to be one row per MEASUREMENT, so a tree the cruiser had
// diametered and then measured the height of appeared twice, in two places
// in the list, joined only by a small "#12" on each row's meta line.
// Reading back a plot meant scanning for pairs. The table now leads with the
// tree number and puts that tree's diameter and height beside it, which is
// the shape of the paper tally sheet this replaces.
//
// The RANGE and QUAL columns are gone with it. Four columns on a phone had
// every cell scaling to fit, and the two that were dropped were the two a
// cruiser was not reading in the field. Neither number is lost: sigma is
// still recorded on the entry, still exported, and now shown in the detail
// sheet — which is what a tap on a row opens, and which carries the whole
// record (species, stem position, damage, note, position, photo, and in
// developer mode the ground truth typed against that tree).
//
// Readings that were never attached to a tree — a sampling-plot record, a
// standalone crown or distance — still get their own row. They are real
// records; grouping by tree must not make them disappear.
//
// FIELD REPORT 5 (second half) — the log is now READ PER PROJECT AND PER
// PLOT. See FieldLogScope.kt for why that needed a sealed hierarchy: cruise
// trees and quick measurements live in two separate stores whose plot ids
// are not interchangeable, so the screen shows them in separate sections
// under a heading naming the project and the plot, and never interleaves
// them. Quick rows keep every edit path they had; cruise rows are read-only
// here, because TreeDetailScreen owns that store's writes.
//
// The three-column table did NOT grow two more columns for project and
// plot: the whole point of the one-row-per-tree change above was that four
// columns on a phone had every cell scaling to fit. The section heading
// carries the project and the plot for every row beneath it instead.
//
// The screen otherwise keeps its shape: plot summary card for the active
// plot, grouped summary card (total / today / last + capacity banner), then
// one grouped surface card of rows with hairline dividers and trailing
// swipe-to-delete, plus an Export menu (single CSV or 5-file ZIP bundle).

package com.hcjeong.forestix.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.common.CoordinateInput
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.sensors.HeightEstimator
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryCard
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixColors
import com.hcjeong.forestix.ui.theme.ForestixDenseTextScale
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
/// [initialScope] is what the log opens on. The cruise project sheet passes
/// the plot in hand; everywhere else it stays [FieldLogScope.Everything].
@Composable
fun FieldLogScreen(
    nav: NavController,
    initialScope: FieldLogScope = FieldLogScope.Everything,
) {
    val colors = Forestix.colors
    val env = LocalAppEnvironment.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries by env.history.entries.collectAsStateWithLifecycle()
    val plots by env.history.plots.collectAsStateWithLifecycle()
    val activePlotID by env.history.activePlotID.collectAsStateWithLifecycle()
    val nearCapacity by env.history.isNearCapacity.collectAsStateWithLifecycle()
    val settings by env.settings.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    /// The row whose detail sheet is open, held by ID rather than by value:
    /// an edit made in the sheet changes the store, and a snapshot taken
    /// when the sheet opened would keep showing the number just replaced.
    /// Null = closed.
    var inspectingId by remember { mutableStateOf<String?>(null) }
    /// The row a swipe asked to delete, held until the cruiser confirms.
    /// Only multi-reading rows go through here (see onDelete below).
    var pendingDelete by remember { mutableStateOf<FieldLogRowModel?>(null) }
    /// The "add a tree" the toolbar or the empty state raised. Null = closed.
    var newTree by remember { mutableStateOf<FieldLogNewTree?>(null) }
    /// What the log is showing, and whether the filter sheet is open.
    var logScope by remember { mutableStateOf(initialScope) }
    var choosingScope by remember { mutableStateOf(false) }
    /// Cruise trees, read ONCE per entry to this screen. A read-back surface
    /// must not put a Room query inside recomposition.
    var cruiseData by remember { mutableStateOf(FieldLogCruiseData()) }
    LaunchedEffect(Unit) { cruiseData = loadFieldLogCruiseData(env) }

    // Quick-measure sections. `Everything` is every plot, a quick plot is
    // that plot, and a CRUISE scope is none of them — a cruise project has
    // no quick plots under it, and pretending otherwise is exactly the
    // conflation this feature exists to avoid.
    val defaultPlotID = plots.firstOrNull { it.isDefault }?.id
    val quickSections = remember(entries, plots, logScope, defaultPlotID) {
        val current = logScope
        val scoped = when (current) {
            is FieldLogScope.Everything -> entries
            is FieldLogScope.QuickPlot ->
                entries.filter { (it.plotID ?: defaultPlotID) == current.id }
            else -> emptyList()
        }
        // Entries whose plot row is missing (a plot deleted out from under
        // them) keep their own section, labelled for what is known, never
        // silently re-homed under another plot's heading.
        scoped.groupBy { it.plotID }
            .map { (plotID, group) ->
                FieldLogSection(
                    id = "q|${plotID ?: "-"}",
                    projectLabel = FieldLogWords.NO_PROJECT,
                    plotLabel = plots.firstOrNull { it.id == plotID }?.name
                        ?: FieldLogWords.UNKNOWN_PLOT,
                    quickRows = fieldLogRows(group),
                    latest = group.maxOf { it.createdAt },
                )
            }
    }
    val sections = remember(quickSections, cruiseData, logScope) {
        (quickSections + cruiseData.sections(logScope)).sortedByDescending { it.latest }
    }
    /// True while quick-measure rows can appear under the current scope.
    val quickWorldVisible = logScope is FieldLogScope.Everything ||
        logScope is FieldLogScope.QuickPlot
    /// The whole log — both worlds — holds nothing at all. That is the only
    /// case the "no readings yet" empty state describes; a scope that merely
    /// happens to be empty gets EMPTY_SCOPE instead, so "you have measured
    /// nothing" and "nothing in THIS plot" never read the same.
    val logIsEmpty = entries.isEmpty() &&
        cruiseData.rowsByPlot.values.all { it.isEmpty() } &&
        cruiseData.failure == null

    val rows = remember(entries) { fieldLogRows(entries) }

    // The plot this screen is reporting on — the one whose summary card sits
    // at the top of the log — resolved through the default plot the same way
    // every entry's plotID is read. A hand-entered stem joins the plot the
    // cruiser is LOOKING at, and it is captured when the sheet opens rather
    // than read again on Create, so a plot switched elsewhere in the app
    // mid-typing cannot claim the tree.
    val shownPlotID = activePlotID ?: plots.firstOrNull { it.isDefault }?.id
    val startNewTree = {
        newTree = FieldLogNewTree(
            // The measure chooser's own rule — max(existing) + 1 across the
            // log — so a hand-entered stem cannot land on a number a scan has
            // already used, in this plot or any other.
            treeNumber = env.history.suggestedNextTreeNumber,
            plotID = shownPlotID,
            plotName = shownPlotID?.let { id -> plots.firstOrNull { it.id == id }?.name },
            // Same suggestion the chooser offers: the successor of the highest
            // name in the series the cruiser is using, or blank if they have
            // never named a tree.
            suggestedName = env.history.suggestedNextTreeName.orEmpty(),
        )
    }

    ForestixScaffold(
        nav, title = "Field log",
        actions = {
            // Ungated, unlike Export: the case this exists for is a log with
            // nothing in it yet and a stem the scan would not lock.
            IconButton(onClick = startNewTree) {
                Icon(Icons.Filled.Add, contentDescription = "New tree", tint = colors.primary)
            }
            // Export writes the QUICK-MEASURE tables. Under a cruise scope
            // none of the rows on screen are in them, so the button is not
            // offered: a file that silently holds different trees than the
            // list above it is worse than no button. The cruise bundle has
            // its own "Export all" on the project screen.
            if (quickWorldVisible && entries.isNotEmpty()) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.IosShare, contentDescription = "Export", tint = colors.primary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("CSV (single file)") },
                        leadingIcon = {
                            Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            menuOpen = false
                            scope.launch { env.history.exportCSV()?.let { shareFile(context, it, "text/csv") } }
                        })
                    DropdownMenuItem(
                        text = { Text("All tables (zip of 5 CSVs)") },
                        leadingIcon = {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        onClick = {
                            menuOpen = false
                            scope.launch { env.history.exportBundle(settings.logRule)?.let { shareFile(context, it, "application/zip") } }
                        })
                }
            }
        },
    ) { padding ->
        if (logIsEmpty) {
            EmptyState(Modifier.padding(padding), onNewTree = startNewTree)
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(ForestixSpace.md),
            ) {
                // What the log is showing, and the way to change it. The
                // caption under it answers the cruiser's own question —
                // "서브플롯? 스탠드?? 플롯??" — where they will be looking.
                item(key = "scopeBar") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(ForestixRadius.card)
                            .background(colors.surface)
                            .clickableNoRipple { choosingScope = true }
                            .padding(ForestixSpace.md),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.FilterList, contentDescription = null,
                                tint = colors.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(ForestixSpace.xs))
                            Text(
                                fieldLogScopeLabel(logScope, plots, cruiseData),
                                style = Forestix.type.bodyBold, color = colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Filled.ExpandMore, contentDescription = null,
                                tint = colors.textTertiary, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            FieldLogWords.WORLDS_CAPTION,
                            style = Forestix.type.caption, color = colors.textTertiary,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.size(ForestixSpace.md))
                }

                // Plot summary card — BA / TPA / QMD + species mix for the
                // active QUICK plot. It reads the
                // quick-measure store, so it is shown only while the quick
                // world is on screen: under a cruise scope it would be a
                // card about a different plot than every row beneath it.
                val plotID = activePlotID
                val plot = plotID?.let { id -> plots.firstOrNull { it.id == id } }
                if (quickWorldVisible && plotID != null && plot != null) {
                    val plotEntries = entries.filter { (it.plotID ?: defaultPlotID) == plotID }
                    if (plotEntries.isNotEmpty()) {
                        item(key = "plotSummary") {
                            Box(Modifier.padding(bottom = ForestixSpace.md)) {
                                PlotSummaryCard(
                                    plot = plot,
                                    entries = plotEntries,
                                    unitSystem = settings.unitSystem,
                                    logRule = settings.logRule,
                                    areaUnit = settings.unitSystem.areaUnit)
                            }
                        }
                    }
                }

                // Summary + capacity — one surface-backed grouped card. Same
                // reason as the card above: it counts quick readings only.
                if (quickWorldVisible) {
                    item(key = "summary") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(ForestixRadius.card)
                                .background(colors.surface),
                        ) {
                            SummaryHeader(entries)
                            if (nearCapacity) {
                                HorizontalDivider(
                                    color = colors.divider, thickness = 0.5.dp,
                                    modifier = Modifier.padding(start = ForestixSpace.md))
                                CapacityBanner()
                            }
                        }
                    }
                }

                // The cruise store refused to read. Say so where the missing
                // rows would have been — an empty cruise side and an
                // unreadable database look identical, and only one of them
                // means "no trees".
                cruiseData.failure?.let { failure ->
                    item(key = "cruiseFailure") {
                        Row(
                            Modifier
                                .padding(top = ForestixSpace.md)
                                .fillMaxWidth()
                                .clip(ForestixRadius.card)
                                .background(colors.surface)
                                .padding(ForestixSpace.md),
                        ) {
                            Icon(
                                Icons.Filled.Warning, contentDescription = null,
                                tint = colors.confidenceWarn, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(ForestixSpace.xs))
                            Text(
                                failure, style = Forestix.type.caption,
                                color = colors.textSecondary)
                        }
                    }
                }

                if (sections.isEmpty()) {
                    item(key = "emptyScope") {
                        Text(
                            FieldLogWords.EMPTY_SCOPE,
                            style = Forestix.type.caption, color = colors.textSecondary,
                            modifier = Modifier.padding(
                                start = ForestixSpace.md, top = ForestixSpace.md))
                    }
                }

                sections.forEach { section ->
                    // The heading is where a row says which project and which
                    // plot it belongs to — it is true of every row beneath it,
                    // and it costs the table no column width.
                    item(key = "h|${section.id}") {
                        Text(
                            section.heading,
                            style = Forestix.type.sectionHead, color = colors.textSecondary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(
                                start = ForestixSpace.md, top = ForestixSpace.md))
                        ColumnHeader()
                    }

                    // Quick rows keep every path they had: tap to inspect,
                    // swipe to delete, re-measure from the detail sheet.
                    val total = section.quickRows.size + section.cruiseRows.size
                    itemsIndexed(section.quickRows, key = { _, r -> "${section.id}|${r.id}" }) { index, row ->
                        FieldLogGroupedRow(
                            index = index, total = total, colors = colors,
                        ) {
                            SwipeToDeleteRow(
                                // EVERY swipe asks first — see the dialog
                                // below for why a single-reading row is the
                                // dangerous case, not the safe one.
                                onDelete = { pendingDelete = row },
                            ) {
                                FieldLogRow(
                                    row, settings.unitSystem,
                                    onClick = { inspectingId = row.id })
                            }
                        }
                    }
                    // Cruise rows are READ-ONLY: no tap target, no swipe. The
                    // cruise flow owns that store's writes, and a second save
                    // path onto the same row is how two numbers for one tree
                    // get created.
                    itemsIndexed(section.cruiseRows, key = { _, r -> "${section.id}|${r.id}" }) { index, row ->
                        FieldLogGroupedRow(
                            index = section.quickRows.size + index, total = total, colors = colors,
                        ) {
                            FieldLogCruiseRowView(row, settings.unitSystem)
                        }
                    }
                    if (section.isEmpty) {
                        item(key = "e|${section.id}") {
                            Text(
                                FieldLogWords.EMPTY_SCOPE,
                                style = Forestix.type.caption, color = colors.textSecondary,
                                modifier = Modifier.padding(start = ForestixSpace.md))
                        }
                    }
                }

                item(key = "swipeHint") {
                    Text(
                        "Tap a tree for the full record. Swipe left to delete.",
                        style = Forestix.type.caption, color = colors.textTertiary,
                        modifier = Modifier.padding(start = ForestixSpace.md, top = ForestixSpace.xs))
                }
            }
        }
    }

    if (choosingScope) {
        FieldLogScopeSheet(
            current = logScope,
            quickPlots = plots,
            data = cruiseData,
            onPick = { logScope = it; choosingScope = false },
            onDismiss = { choosingScope = false },
        )
    }

    newTree?.let { request ->
        FieldLogNewTreeSheet(
            request = request,
            unitSystem = settings.unitSystem,
            onDismiss = { newTree = null },
            onCreate = { name, species, dbhCm, heightM ->
                // Both readings go through QuickMeasureEntry.typed — the SAME
                // factory the row editor's "this kind has no reading yet"
                // branch calls — so sigma is null, capture_mode is "typed" and
                // the method is the manual arm. A stem entered here is stamped
                // exactly like a number typed into an existing row, and
                // neither can be read back as a scan.
                listOf(MeasureKind.DBH to dbhCm, MeasureKind.HEIGHT to heightM)
                    .forEach { (kind, value) ->
                        if (value != null) {
                            env.history.append(
                                QuickMeasureEntry.typed(
                                    kind = kind, value = value,
                                    treeNumber = request.treeNumber,
                                    treeName = name,
                                    plotID = request.plotID,
                                    speciesCode = species))
                        }
                    }
                newTree = null
            },
        )
    }

    inspectingId?.let { id ->
        val live = rows.firstOrNull { it.id == id }
        ModalBottomSheet(
            onDismissRequest = { inspectingId = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = colors.canvas,
        ) {
            if (live == null) {
                // Every reading behind this row went away while the sheet was
                // open. An empty sheet would read as "this tree has nothing
                // on it" — say what happened instead.
                Text(
                    "Every reading on this row has been deleted.",
                    style = Forestix.type.caption, color = colors.textTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(ForestixSpace.lg),
                )
            } else {
                FieldLogDetailSheet(
                    row = live,
                    unitSystem = settings.unitSystem,
                    developerMode = settings.developerMode,
                    onSave = { env.history.update(it) },
                    onAdd = { env.history.append(it) },
                    onRemeasure = { kind, tree, name, species, truth ->
                        // Close the sheet before leaving the screen, and hand
                        // the scan the tree it must land on plus the truth it
                        // must carry across (PendingTreeNumber is the same
                        // slot the map home's tree lock uses). The plot is
                        // named explicitly: this row can belong to a plot
                        // that is not the active one.
                        inspectingId = null
                        PendingTreeNumber.set(
                            number = tree, name = name, speciesCode = species,
                            replaceExisting = true, truth = truth,
                            plotID = live.entries.firstOrNull()?.plotID)
                        nav.navigate(
                            if (kind == MeasureKind.DBH) Routes.DBH else Routes.HEIGHT)
                    },
                )
            }
        }
    }

    // EVERY swipe asks, and names what it is about to take.
    //
    // This used to confirm only for a row carrying several readings and
    // delete a single-reading row on the spot. Most rows ARE a single
    // reading, so in practice a swipe — a cheap, easily-misfired gesture on a
    // list the cruiser scrolls one-handed — destroyed a measurement with
    // nothing in the way. What it destroys is field data: getting it back
    // means walking to the tree again, and in an accuracy-validation set the
    // tree that gets re-measured is not the same observation. One tap of
    // friction is nothing against that.
    pendingDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${row.title}?") },
            text = { Text(row.deleteWarning) },
            confirmButton = {
                TextButton(onClick = {
                    row.entries.forEach { env.history.delete(it.id) }
                    pendingDelete = null
                }) { Text(deleteActionTitle(row.entries.size), color = colors.confidenceBad) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            containerColor = colors.surface,
        )
    }
}

// MARK: - Scope bar / sheet -----------------------------------------------

/// What the scope bar reads. Falls back to EVERYTHING when the scope names a
/// project or plot that is no longer in the store — the label must never
/// claim a filter that is not actually being applied.
internal fun fieldLogScopeLabel(
    scope: FieldLogScope,
    quickPlots: List<com.hcjeong.forestix.data.QuickMeasurePlot>,
    data: FieldLogCruiseData,
): String = when (scope) {
    is FieldLogScope.Everything -> FieldLogWords.EVERYTHING
    is FieldLogScope.CruiseProject ->
        data.projects.firstOrNull { it.id == scope.id }?.name ?: FieldLogWords.EVERYTHING
    is FieldLogScope.CruisePlot -> {
        val plot = data.plot(scope.id)
        if (plot == null) {
            FieldLogWords.EVERYTHING
        } else {
            val plotName = FieldLogWords.plotName(plot.plotNumber)
            data.project(scope.id)?.name
                ?.let { it + FieldLogWords.HEADING_SEPARATOR + plotName }
                ?: plotName
        }
    }
    is FieldLogScope.QuickPlot ->
        quickPlots.firstOrNull { it.id == scope.id }?.name ?: FieldLogWords.UNKNOWN_PLOT
}

/// The filter. Lists every scope the two stores can actually answer for — no
/// free text, so the cruiser cannot ask for a project a quick reading could
/// never be under.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldLogScopeSheet(
    current: FieldLogScope,
    quickPlots: List<com.hcjeong.forestix.data.QuickMeasurePlot>,
    data: FieldLogCruiseData,
    onPick: (FieldLogScope) -> Unit,
    onDismiss: () -> Unit,
) {
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
                .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        ) {
            Text(
                FieldLogWords.FILTER_TITLE,
                style = Forestix.type.bodyBold, color = colors.textPrimary,
                modifier = Modifier.padding(bottom = ForestixSpace.sm))

            ScopeChoice(FieldLogWords.EVERYTHING, null,
                selected = current is FieldLogScope.Everything, indented = false,
                colors = colors) { onPick(FieldLogScope.Everything) }

            if (data.projects.isNotEmpty()) {
                ScopeGroupHeader(FieldLogWords.CRUISE_PROJECTS_HEADER, colors)
                data.projects.forEach { project ->
                    ScopeChoice(
                        project.name, FieldLogWords.ALL_PLOTS,
                        selected = current == FieldLogScope.CruiseProject(project.id),
                        indented = false, colors = colors,
                    ) { onPick(FieldLogScope.CruiseProject(project.id)) }
                    data.plotsByProject[project.id].orEmpty().forEach { plot ->
                        ScopeChoice(
                            FieldLogWords.plotName(plot.plotNumber), null,
                            selected = current == FieldLogScope.CruisePlot(plot.id),
                            indented = true, colors = colors,
                        ) { onPick(FieldLogScope.CruisePlot(plot.id)) }
                    }
                }
            }

            if (quickPlots.isNotEmpty()) {
                ScopeGroupHeader(FieldLogWords.QUICK_PLOTS_HEADER, colors)
                quickPlots.forEach { plot ->
                    ScopeChoice(
                        plot.name, null,
                        selected = current == FieldLogScope.QuickPlot(plot.id),
                        indented = false, colors = colors,
                    ) { onPick(FieldLogScope.QuickPlot(plot.id)) }
                }
            }
            Spacer(Modifier.size(ForestixSpace.lg))
        }
    }
}

@Composable
private fun ScopeGroupHeader(text: String, colors: ForestixColors) {
    Text(
        text, style = Forestix.type.sectionHead, color = colors.textTertiary,
        modifier = Modifier.padding(top = ForestixSpace.md, bottom = 4.dp))
}

@Composable
private fun ScopeChoice(
    title: String,
    detail: String?,
    selected: Boolean,
    indented: Boolean,
    colors: ForestixColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick)
            .padding(
                start = if (indented) ForestixSpace.md else 0.dp,
                top = ForestixSpace.sm, bottom = ForestixSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = Forestix.type.body, color = colors.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (detail != null) {
            Spacer(Modifier.size(ForestixSpace.xs))
            Text(detail, style = Forestix.type.caption, color = colors.textTertiary)
        }
        if (selected) {
            Icon(
                Icons.Filled.Check, contentDescription = null,
                tint = colors.primary, modifier = Modifier.size(18.dp))
        }
    }
}

// MARK: - Grouped row shell + cruise row -----------------------------------

/// The insetGrouped card shell every row sits in: rounded at the ends of its
/// section, square in the middle, hairline divider except on the last.
@Composable
private fun FieldLogGroupedRow(
    index: Int,
    total: Int,
    colors: ForestixColors,
    content: @Composable () -> Unit,
) {
    val last = index == total - 1
    val shape = when {
        total == 1 -> ForestixRadius.card
        index == 0 -> RoundedCornerShape(
            topStart = ForestixRadius.cardDp, topEnd = ForestixRadius.cardDp)
        last -> RoundedCornerShape(
            bottomStart = ForestixRadius.cardDp, bottomEnd = ForestixRadius.cardDp)
        else -> RectangleShape
    }
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.surface)) {
        content()
        if (!last) {
            HorizontalDivider(
                color = colors.divider, thickness = 0.5.dp,
                modifier = Modifier.padding(start = ForestixSpace.md))
        }
    }
}

/// A cruise tree in the log's table. Same three columns as a quick row so the
/// two read as one sheet of paper, but with no click and no swipe: this store
/// is written by the cruise flow and edited on TreeDetailScreen.
@Composable
private fun FieldLogCruiseRowView(row: FieldLogCruiseRow, system: UnitSystem) {
    val colors = Forestix.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
    ) {
        FieldLogColumns(
            treeSlot = {
                Text(
                    row.treeLabel, style = Forestix.type.data, color = colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            dbhSlot = {
                Text(
                    MeasurementFormatter.diameter(row.dbhCm, system),
                    style = Forestix.type.data, color = colors.textPrimary,
                    textAlign = TextAlign.End, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            },
            heightSlot = {
                Text(
                    row.heightM?.let { MeasurementFormatter.height(it, system) } ?: "—",
                    style = Forestix.type.data,
                    color = if (row.heightM == null) colors.textTertiary else colors.textPrimary,
                    textAlign = TextAlign.End, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            },
        )
        Row(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (row.speciesCode.isNotEmpty()) {
                Text(
                    RegionalSpecies.nameForCode(row.speciesCode),
                    style = Forestix.type.dataSmall, color = colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                relativeAgo(row.recordedAt),
                style = Forestix.type.dataSmall, color = colors.textTertiary, maxLines = 1)
        }
    }
}

// MARK: - Row model -------------------------------------------------------

/// One row of the log: everything recorded against one tree, or a single
/// reading that was never attached to one.
///
/// GROUPING IS BY (plot, tree number), not by tree number alone. Tree
/// numbering restarts on each plot, so keying on the number by itself would
/// have merged plot 1's tree 4 with plot 2's tree 4 into a row claiming a
/// diameter and a height that came off two different trees.
data class FieldLogRowModel(
    val id: String,
    /// Null for a reading with no tree number — a sampling-plot record, or
    /// a standalone crown / distance measurement.
    val treeNumber: Int?,
    /// The cruiser's name for this tree, when they gave it one. Null falls
    /// back to "#<treeNumber>" through [treeLabel].
    val treeName: String?,
    /// Newest diameter and height on this tree. Earlier re-measurements stay
    /// in [entries] and are listed in the detail sheet.
    val dbh: QuickMeasureEntry?,
    val height: QuickMeasureEntry?,
    /// Every reading behind this row, newest first.
    val entries: List<QuickMeasureEntry>,
    /// Sort key — the most recent reading in the group.
    val latest: Long,
) {
    /// What the TREE column shows. Null for a row that belongs to no tree.
    val treeLabel: String?
        get() = treeName ?: treeNumber?.let { "#$it" }

    val title: String
        get() = treeName ?: treeNumber?.let { "Tree #$it" } ?: kindWord(entries.first().kind)

    /// What a destructive swipe is actually about to remove.
    val deleteWarning: String
        get() {
            val listed = entries.map { kindWord(it.kind).lowercase(Locale.US) }
                .distinct().sorted().joinToString(" and ")
            return "This removes the $listed recorded against it. It cannot be undone."
        }
}

/// The destructive button, counting what it will take. Singular when there is
/// one — "Delete 1 readings" is the sentence a cruiser reads while deciding
/// whether to destroy a measurement. iOS `FieldLogRowModel.deleteActionTitle`
/// returns the same strings.
internal fun deleteActionTitle(count: Int): String =
    if (count == 1) "Delete 1 reading" else "Delete $count readings"

internal fun kindWord(kind: MeasureKind): String = when (kind) {
    MeasureKind.DBH -> "DBH"
    MeasureKind.HEIGHT -> "Height"
    MeasureKind.CROWN -> "Crown"
    MeasureKind.DISTANCE -> "Dist"
    MeasureKind.SAMPLING_PLOT -> "Plot"
}

/// Collapses the flat entry list into rows, newest tree first.
///
/// [entries] arrives newest-first from the history, and that order is
/// preserved inside each group, so `dbh` / `height` pick up the latest
/// reading of each kind without a second sort.
internal fun fieldLogRows(entries: List<QuickMeasureEntry>): List<FieldLogRowModel> {
    val order = mutableListOf<String>()
    val grouped = LinkedHashMap<String, MutableList<QuickMeasureEntry>>()
    for (e in entries) {
        val key = e.treeNumber?.let { "t|${e.plotID?.toString() ?: "-"}|$it" }
        // Never merged with anything: one row, this reading.
            ?: "e|${e.id}"
        if (!grouped.containsKey(key)) order.add(key)
        grouped.getOrPut(key) { mutableListOf() }.add(e)
    }
    return order.mapNotNull { key ->
        val group = grouped[key] ?: return@mapNotNull null
        val first = group.firstOrNull() ?: return@mapNotNull null
        FieldLogRowModel(
            id = key,
            treeNumber = first.treeNumber,
            // Any reading on the tree carries the name; take the first that
            // has one rather than `first`'s, which is the newest and may be a
            // re-measurement recorded before the tree was named.
            treeName = group.firstNotNullOfOrNull { it.treeName },
            dbh = group.firstOrNull { it.kind == MeasureKind.DBH },
            height = group.firstOrNull { it.kind == MeasureKind.HEIGHT },
            entries = group,
            latest = group.maxOf { it.createdAt },
        )
    }
}

// MARK: - Summary / capacity / empty --------------------------------------

@Composable
private fun SummaryHeader(entries: List<QuickMeasureEntry>) {
    fun sameDay(t: Long): Boolean {
        val a = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val b = java.util.Calendar.getInstance()
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    }
    val todayCount = entries.count { sameDay(it.createdAt) }
    val last = entries.firstOrNull()?.let { relativeAgo(it.createdAt) } ?: "—"
    ForestixDenseTextScale {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.lg),
        ) {
            Cell("${entries.size}", "TOTAL")
            Cell("$todayCount", "TODAY")
            Cell(last, "LAST")
        }
    }
}

@Composable
private fun Cell(value: String, label: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Both single-line and unwrappable: these cells size to their own
        // content, and a count split across two lines is the same defect as
        // "PRECISI/ON" one card down (G3).
        //
        // FIELD REPORT — the values were `dataLarge` (26 sp) and read as
        // oversized beside everything else on the screen. `data` (17 sp) is
        // the next step of the same scale and the one the log's row values
        // and the plot-summary card already use. iOS matches.
        Text(value, style = type.data, color = colors.textPrimary,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        Text(label, style = type.sectionHead.copy(letterSpacing = 1.2.sp), color = colors.textTertiary,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CapacityBanner() {
    val colors = Forestix.colors
    val type = Forestix.type
    Box(Modifier.padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.xs)) {
        Row(
            Modifier.fillMaxWidth().clip(ForestixRadius.control).background(colors.confidenceWarn.copy(alpha = 0.12f)).padding(ForestixSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = colors.confidenceWarn, modifier = Modifier.size(16.dp))
            Text("Log nearly full. Export soon to free space.", style = type.caption, color = colors.textSecondary)
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onNewTree: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    // iOS: uniform 16 gaps, content ABOVE centre — one flexible spacer on
    // top, two on the bottom (1 : 2 split).
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.Inbox, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(34.dp))
        Text("No readings yet", style = type.bodyBold, color = colors.textPrimary)
        Text(
            "Accept a scan in a measurement tool and it'll land here.",
            style = type.caption, color = colors.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = ForestixSpace.xl),
        )
        // A scan is not the only way in. The stem the sensors refused is still
        // a stem, and this is the door for it — repeated here because an empty
        // log is exactly where a cruiser looks for one.
        ForestixProminentButton(
            "Add a tree by hand",
            modifier = Modifier.padding(horizontal = ForestixSpace.xl),
            onClick = onNewTree,
        )
        Spacer(Modifier.weight(2f))
    }
}

// MARK: - Column geometry -------------------------------------------------
//
// THREE columns now, not four. Dropping RANGE and QUAL gave back roughly
// 128 dp on a 360 dp phone, which is why every cell here sits well inside
// its column instead of scaling to fit as the four-column table did. A row
// has screen − 32 (list inset) − 32 (row padding) − 12 (two 6 dp gutters)
// to share:
//
//   column   widest content        needs    360 dp   411 dp   320 dp
//   TREE     "Plot3-T08"            68 dp    77 dp    93 dp    66 dp
//   DBH      "150.0 cm"             82 dp   103 dp   124 dp    89 dp
//   HEIGHT   "150.00 ft"            88 dp   103 dp   124 dp    89 dp
//
// TREE carries a NAME now, not just "#128", so it holds a quarter of the row
// rather than a fifth. The 8 dp it took came off the two measurement columns,
// which were still 20 dp clear of their widest reading.
//
// Every heading is a single unwrappable word, so nothing can break
// mid-word the way "PRECISI/ON" and "QUALI/TY" did. The two numeric cells
// may take a second line instead — a sampling-plot reading is wider than
// any phone column, and a taller row is better than a measurement the
// cruiser cannot read in full.
private const val ColTreeWeight = 1.3f
private const val ColDbhWeight = 1.75f
private const val ColHeightWeight = 1.75f
private val ColGap = 6.dp

/// The ONE definition of the field-log grid. The header and every row go
/// through it, so the columns cannot drift apart again.
@Composable
private fun FieldLogColumns(
    modifier: Modifier = Modifier,
    treeSlot: @Composable () -> Unit,
    dbhSlot: @Composable () -> Unit,
    heightSlot: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ColGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(ColTreeWeight), contentAlignment = Alignment.CenterStart) { treeSlot() }
        Box(Modifier.weight(ColDbhWeight), contentAlignment = Alignment.CenterEnd) { dbhSlot() }
        Box(Modifier.weight(ColHeightWeight), contentAlignment = Alignment.CenterEnd) { heightSlot() }
    }
}

/// A column heading: one line, never wrapped, never hyphenated.
@Composable
private fun HeaderLabel(text: String) {
    Text(
        text,
        // 0.6 tracking, not 1.2: across a six-letter heading the extra
        // 0.6 sp per character is ~4 dp, which is most of the margin that
        // decides whether the word survives at a large system font size.
        style = Forestix.type.sectionHead.copy(letterSpacing = 0.6.sp),
        color = Forestix.colors.textTertiary,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ColumnHeader() = ForestixDenseTextScale {
    FieldLogColumns(
        Modifier.padding(
            start = ForestixSpace.md, end = ForestixSpace.md,
            top = ForestixSpace.md, bottom = ForestixSpace.xs),
        // Same three strings on iOS.
        treeSlot = { HeaderLabel("TREE") },
        dbhSlot = { HeaderLabel("DBH") },
        heightSlot = { HeaderLabel("HEIGHT") },
    )
}

// MARK: - Row -------------------------------------------------------------

// The header and the rows carry their own scale bound, so the grid cannot
// be composed anywhere without it.
@Composable
private fun FieldLogRow(
    row: FieldLogRowModel,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
) = ForestixDenseTextScale {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FieldLogColumns(
            treeSlot = {
                Text(
                    row.treeLabel ?: kindWord(row.entries.first().kind),
                    style = type.data, color = colors.textPrimary,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            },
            dbhSlot = {
                if (row.treeNumber == null) {
                    // A plot record or a standalone crown / distance has no
                    // diameter-and-height shape to fill. Its reading spans
                    // the two measurement columns rather than being forced
                    // into one of them under a heading it does not match.
                    Text(
                        looseValue(row.entries.first(), unitSystem),
                        style = type.data, color = colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        row.dbh?.let { MeasurementFormatter.diameter(it.value, unitSystem) } ?: "—",
                        style = type.data,
                        color = if (row.dbh == null) colors.textTertiary else colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            },
            heightSlot = {
                if (row.treeNumber != null) {
                    Text(
                        row.height?.let { MeasurementFormatter.height(it.value, unitSystem) } ?: "—",
                        style = type.data,
                        color = if (row.height == null) colors.textTertiary else colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            },
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The kind words that used to be the TYPE column, kept here only
            // when a row carries something other than the two named columns
            // (a crown on the same tree).
            if (row.treeNumber != null) {
                row.entries
                    .filter { it.kind != MeasureKind.DBH && it.kind != MeasureKind.HEIGHT }
                    .map { kindWord(it.kind) }.distinct().sorted()
                    .forEach {
                        Text(it, style = type.dataSmall, color = colors.textTertiary,
                            maxLines = 1, softWrap = false)
                    }
            }
            speciesName(row)?.let {
                Text(it, style = type.dataSmall, color = colors.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(relativeAgo(row.latest), style = type.dataSmall, color = colors.textTertiary,
                maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            // The standard "there is more behind this" affordance — the row
            // is tappable and this says so.
            Icon(
                Icons.Filled.ChevronRight, contentDescription = null,
                tint = colors.textTertiary, modifier = Modifier.size(14.dp))
        }
    }
}

private fun speciesName(row: FieldLogRowModel): String? =
    row.entries.firstNotNullOfOrNull { it.speciesCode?.takeIf(String::isNotEmpty) }
        ?.let { RegionalSpecies.nameForCode(it) }

private fun looseValue(e: QuickMeasureEntry, system: UnitSystem): String = when (e.kind) {
    MeasureKind.DBH -> MeasurementFormatter.diameter(e.value, system)
    MeasureKind.HEIGHT -> MeasurementFormatter.height(e.value, system)
    MeasureKind.CROWN -> String.format(Locale.US, "%.1f × %.1f m", e.value, e.secondaryValue ?: 0.0)
    MeasureKind.DISTANCE -> MeasurementFormatter.distance(e.value, system)
    MeasureKind.SAMPLING_PLOT -> {
        val area = e.secondaryValue ?: (PI * e.value * e.value)
        String.format(Locale.US, "%.1f m radius · %.1f m²", e.value, area)
    }
}

private fun sigmaText(e: QuickMeasureEntry, system: UnitSystem): String? {
    val s = e.sigma ?: return null
    if (s <= 0) return null
    return when (e.kind) {
        MeasureKind.DBH -> MeasurementFormatter.diameterSigma(s, system)
        MeasureKind.HEIGHT -> MeasurementFormatter.heightSigma(s, system)
        else -> String.format(Locale.US, "±%.2f m", s)
    }
}

// MARK: - Detail sheet ----------------------------------------------------

/// The whole record behind one row.
///
/// FIELD REPORT 5 asked for this: the log table now shows the two numbers a
/// cruiser reads while walking, and everything else — what was typed into
/// the details sheet at capture time, the species, the ± band, where the
/// reading was taken, and in developer mode the ground truth entered against
/// this tree — lives one tap away instead of being squeezed into columns.
///
/// Internal, not private: the MAP peek opens THIS sheet for the row behind a
/// tapped pin (map spec item 4). There is exactly one per-tree record surface
/// in the quick-measure world and both entry points must land on it.
@Composable
internal fun FieldLogDetailSheet(
    row: FieldLogRowModel,
    unitSystem: UnitSystem,
    developerMode: Boolean,
    onSave: (QuickMeasureEntry) -> Unit,
    onAdd: (QuickMeasureEntry) -> Unit,
    /// (kind, tree number, tree name, species code, ground truth)
    onRemeasure: (MeasureKind, Int, String?, String?, Double?) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    // Every photo on this tree, in capture order — a Full measurement leaves
    // a diameter frame and a height frame, and this sheet used to show one
    // of them with no way to the other.
    val photoPages = measurePhotoPages(row.entries, unitSystem)
    var showPhotos by remember(row.id) { mutableStateOf(false) }
    // The recorded-coordinate editor: whether it is up, what is in its
    // field, and why the field cannot be saved (null = it can).
    var editingPosition by remember(row.id) { mutableStateOf(false) }
    var positionField by remember(row.id) { mutableStateOf("") }
    var positionRefusal by remember(row.id) { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.md)
            .padding(bottom = ForestixSpace.xl),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Text(row.title, style = type.title, color = colors.textPrimary)

        SheetSection("MEASUREMENTS") {
            row.entries.forEach { e ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(kindWord(e.kind), style = type.body, color = colors.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(looseValue(e, unitSystem), style = type.data, color = colors.textPrimary)
                    }
                    // The ± band the table used to carry. It is the
                    // measurement's own precision, so it belongs with the
                    // measurement rather than in a column being scaled to
                    // fit on a phone.
                    sigmaText(e, unitSystem)?.let {
                        Text(it, style = type.dataSmall, color = colors.textTertiary)
                    }
                }
            }
        }

        // Editing is per TREE: the two named readings are what a tree has,
        // and a loose crown / distance / plot record has no tree to
        // re-measure or to complete.
        row.treeNumber?.let { tree ->
            FieldLogEditSection(
                kind = MeasureKind.DBH, row = row, tree = tree,
                unitSystem = unitSystem, developerMode = developerMode,
                onSave = onSave, onAdd = onAdd, onRemeasure = onRemeasure)
            FieldLogEditSection(
                kind = MeasureKind.HEIGHT, row = row, tree = tree,
                unitSystem = unitSystem, developerMode = developerMode,
                onSave = onSave, onAdd = onAdd, onRemeasure = onRemeasure)
        }

        val species = row.entries.firstNotNullOfOrNull {
            it.speciesCode?.takeIf(String::isNotEmpty)
        }
        val position = row.entries.firstNotNullOfOrNull { it.position }
        val damage = row.entries.flatMap { it.damageCodes }.distinct().sorted()
        val note = row.entries.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString("\n").takeIf(String::isNotEmpty)

        SheetSection("DETAILS") {
            SheetRow("Species", species?.let { "${RegionalSpecies.nameForCode(it)} · $it" } ?: "—")
            position?.let { SheetRow("Stem position", it.displayName) }
            if (damage.isNotEmpty()) SheetRow("Damage", damage.joinToString(", "))
            note?.let {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Note", style = type.body, color = colors.textSecondary)
                    Text(it, style = type.body, color = colors.textPrimary)
                }
            }
            if (species == null && position == null && damage.isEmpty() && note == null) {
                Text("Nothing was attached to this reading.",
                    style = type.caption, color = colors.textTertiary)
            }
        }

        SheetSection("RECORDED") {
            SheetRow("When", SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(row.latest)))
            val fix = row.entries.firstOrNull { it.latitude != null && it.longitude != null }
            // POSITION IS TAPPABLE. A fix that never arrived, or arrived on
            // the wrong stem, used to be permanent — the reading carried it
            // for the rest of the cruise and into every export. The row now
            // opens an editor; what it shows is unchanged.
            //
            // Said out loud rather than left blank: a reading with no fix is
            // a different thing from one whose fix was not shown.
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickableNoRipple(onClick = {
                        positionField = fix?.let {
                            CoordinateInput.text(it.latitude!!, it.longitude!!)
                        } ?: ""
                        positionRefusal = null
                        editingPosition = true
                    }),
            ) {
                SheetRow(
                    "Position",
                    fix?.let { CoordinateInput.text(it.latitude!!, it.longitude!!) }
                        ?: "not recorded")
            }
            // WHERE THE COORDINATE CAME FROM. Without this a coordinate the
            // cruiser typed reads on screen — and exports — exactly like one
            // the satellites produced.
            fix?.positionRecordedSource?.let {
                SheetRow("Position source", FieldLogWords.positionSourceText(it))
            }
            row.entries.firstNotNullOfOrNull { it.captureMode }?.let {
                // "typed" is its own answer. Folding it into "Automatic" told
                // the cruiser the sensors produced a number nobody ever
                // pointed a camera at.
                SheetRow(
                    "Capture",
                    when (it) {
                        "manual" -> "Adjusted by hand"
                        "typed" -> "Typed by hand"
                        else -> "Automatic"
                    })
            }
            // The row's photos in the order they were shot. The sheet still
            // shows the first one in place, exactly as it always has; the
            // rest are one tap away in the shared viewer, which is the only
            // thing on this screen that pages.
            photoPages.firstOrNull()?.let { first ->
                FieldLogPhoto(first.photoPath, photoPages.size) { showPhotos = true }
            }
        }

        // There is no second ground-truth section here any more. The truth
        // field inside the DIAMETER and HEIGHT sections above is the only
        // place a truth is shown and the only place it is edited — see the
        // note at the foot of this file.
    }

    if (showPhotos) {
        PhotoViewerDialog(
            pages = photoPages,
            onDismiss = { showPhotos = false },
        )
    }

    if (editingPosition) {
        // Write a typed coordinate — or a clearance — onto EVERY reading on
        // this row. The cruiser is fixing where the TREE is, not where one
        // of its two or three readings is; setting only the newest would
        // leave the diameter and the height claiming different places, and
        // clearing only the newest would make an older fix pop back into the
        // row as if nothing had happened. `settingPosition` stamps the
        // source "manual" so the typed coordinate can never be read back as
        // a device fix.
        val apply: (Double?, Double?) -> Unit = { lat, lon ->
            row.entries.forEach { onSave(it.settingPosition(lat, lon)) }
            editingPosition = false
        }
        PositionEditorDialog(
            text = positionField,
            refusal = positionRefusal,
            canClear = row.entries.any { it.latitude != null && it.longitude != null },
            onTextChange = { new ->
                positionField = new
                // Refuse as they type, so Save is never a surprise. A blank
                // field is not a refusal: it means "clear", which Save then
                // performs.
                positionRefusal =
                    (CoordinateInput.parse(new) as? CoordinateInput.Result.Refused)?.message
            },
            onSave = {
                when (val parsed = CoordinateInput.parse(positionField)) {
                    is CoordinateInput.Result.Coordinate ->
                        apply(parsed.latitude, parsed.longitude)
                    // Nothing is written. The sentence stays on screen.
                    is CoordinateInput.Result.Refused -> positionRefusal = parsed.message
                    CoordinateInput.Result.Cleared -> apply(null, null)
                }
            },
            onClear = { apply(null, null) },
            onDismiss = { editingPosition = false },
        )
    }
}

/// The recorded-coordinate editor. Save is off while the text cannot be read
/// as a coordinate — the stored one is never replaced by a guess.
@Composable
private fun PositionEditorDialog(
    text: String,
    refusal: String?,
    canClear: Boolean,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Position") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = { Text("Latitude, longitude") },
                    placeholder = { Text("44.56417, -123.28556") },
                    singleLine = true,
                    isError = refusal != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                refusal?.let {
                    Text(it, style = type.caption, color = colors.confidenceBad)
                }
                Text(
                    "Decimal degrees, the way the app shows them. This is the " +
                        "position for every reading on this tree, and it is " +
                        "recorded as typed by hand.",
                    style = type.caption,
                    color = colors.textTertiary,
                )
                if (canClear) {
                    TextButton(onClick = onClear) {
                        Text("Clear position", color = colors.confidenceBad)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = refusal == null) {
                Text("Save position")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// MARK: - Editing one reading ---------------------------------------------

/// A prefilled field re-parses a hair off the number it was filled from — it
/// is rendered to four decimals, and under imperial it makes a round trip
/// through inches or feet. Anything smaller than this is that rounding, not
/// an edit, and treating it as an edit would restamp a measured reading as
/// typed and throw away its sigma.
private const val FIELD_LOG_VALUE_EPSILON = 0.001

/// True when a typed truth differs from the stored one, treating "absent"
/// and "present" as different states rather than comparing against a zero.
private fun truthChanged(typed: Double?, stored: Double?): Boolean = when {
    typed == null && stored == null -> false
    typed != null && stored != null -> abs(typed - stored) > FIELD_LOG_VALUE_EPSILON
    else -> true
}

/// What a hand-typed diameter or height has to be, in ONE place.
///
/// Two screens now read a number the cruiser typed into a field in the unit
/// system they are working in: the row editor completing a half-measured
/// tree, and the new-tree sheet creating one from nothing. They must accept
/// the same numbers, refuse the same numbers and warn in the same words — a
/// second copy of these sentences would drift the day one of them changed.
private object FieldLogTypedInput {

    fun quantity(kind: MeasureKind): TruthInput.Quantity =
        if (kind == MeasureKind.DBH) TruthInput.Quantity.DIAMETER
        else TruthInput.Quantity.HEIGHT

    /// The unit the field is typed in — the cruiser's active system,
    /// converted to the metric base on the way in.
    fun unit(kind: MeasureKind, imperial: Boolean): TruthInput.Unit =
        TruthInput.defaultUnit(quantity(kind), imperial)

    /// Placeholder copy is the scan screens' own, so the same field means the
    /// same thing wherever a measurement is typed.
    fun placeholder(kind: MeasureKind, imperial: Boolean): String =
        if (kind == MeasureKind.DBH) {
            if (imperial) "Diameter in inches" else "Diameter in cm"
        } else {
            if (imperial) "Height in feet" else "Height in metres"
        }

    /// The typed number in metric base units, or null when the field holds
    /// nothing usable. Never falls back to a stored value — a blank field
    /// means "nothing typed".
    fun parse(text: String, kind: MeasureKind, imperial: Boolean): Double? =
        TruthInput.parsePositiveBase(text, unit(kind, imperial))

    /// A height under the estimator's floor is not a standing tree, so it is
    /// REFUSED rather than warned about. Diameter has no such floor: any
    /// positive number is a stem somebody could have taped.
    fun isBelowHeightFloor(value: Double, kind: MeasureKind): Boolean =
        kind == MeasureKind.HEIGHT && value < HeightEstimator.MIN_H_M

    /// The number this text will actually be STORED as — null when the field
    /// is blank, unparseable, or holds something the app refuses. Null never
    /// means "use something else"; it means nothing is written.
    fun accepted(text: String, kind: MeasureKind, imperial: Boolean): Double? {
        val value = parse(text, kind, imperial) ?: return null
        return if (isBelowHeightFloor(value, kind)) null else value
    }

    /// What to say about what is in the field, or null when there is nothing
    /// to say. A blank field is silent — the cruiser has not typed yet.
    fun warning(text: String, kind: MeasureKind, imperial: Boolean): String? {
        if (TruthInput.normalized(text).isEmpty()) return null
        val u = unit(kind, imperial)
        val value = parse(text, kind, imperial)
            ?: return if (kind == MeasureKind.DBH) {
                "A typed diameter must be a number greater than zero."
            } else {
                "A typed height must be a number greater than zero."
            }
        if (isBelowHeightFloor(value, kind)) {
            // The floor is one physical height; the sentence is written in the
            // unit the field is in, so an imperial cruiser is not handed a
            // metre figure to compare against the feet they just typed.
            return String.format(
                Locale.US, "A typed height must be at least %.1f %s.",
                TruthInput.fromBase(HeightEstimator.MIN_H_M.toDouble(), u), u.raw)
        }
        // Outside the cruising window is a WARNING, not a refusal: the number
        // is the cruiser's own observation. Same wording as every other truth
        // field in the app.
        return TruthInput.warning(value, quantity(kind), u)
    }
}

/// One kind's editor: the number, its ground truth, and the two ways to
/// change either — type it, or go and measure it again. A tree the sensors
/// never read gets the same section, empty, so it can be completed from here
/// instead of staying half-measured forever.
@Composable
private fun FieldLogEditSection(
    kind: MeasureKind,
    row: FieldLogRowModel,
    tree: Int,
    unitSystem: UnitSystem,
    developerMode: Boolean,
    onSave: (QuickMeasureEntry) -> Unit,
    onAdd: (QuickMeasureEntry) -> Unit,
    onRemeasure: (MeasureKind, Int, String?, String?, Double?) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val existing = if (kind == MeasureKind.DBH) row.dbh else row.height
    val imperial = unitSystem == UnitSystem.IMPERIAL
    val quantity = FieldLogTypedInput.quantity(kind)
    // The MEASURED-value field is typed in the cruiser's active system and
    // converted to the metric base on the way in — the one place that
    // conversion is allowed to happen.
    val unit = FieldLogTypedInput.unit(kind, imperial)
    // The TRUTH field opens in the same unit but carries a per-entry
    // override: a tape in centimetres read against an imperial project is the
    // case that lost a day of analysis. Keyed on the unit system so changing
    // the system drops the override — identical rule to the three scan
    // screens, whose toggle this is.
    var truthUnit by remember(row.id, kind, unitSystem) { mutableStateOf(unit) }

    // Filled from the store ONCE. Re-filling on every store change would
    // overwrite what the cruiser is typing.
    var valueText by remember(row.id, kind) {
        mutableStateOf(existing?.let { TruthInput.text(it.value, unit) } ?: "")
    }
    var truthText by remember(row.id, kind) {
        mutableStateOf(existing?.truth?.let { TruthInput.text(it, unit) } ?: "")
    }

    val typed = FieldLogTypedInput.parse(valueText, kind, imperial)
    val tooShort = typed != null && FieldLogTypedInput.isBelowHeightFloor(typed, kind)
    val valueWarning = FieldLogTypedInput.warning(valueText, kind, imperial)
    val truthTyped = TruthInput.parsePositiveBase(truthText, truthUnit)
    val canSave = typed != null && !tooShort &&
        // Text that doesn't parse must never overwrite a stored truth.
        !(developerMode && TruthInput.isUnparseable(truthText)) &&
        (existing == null ||
            abs(typed - existing.value) > FIELD_LOG_VALUE_EPSILON ||
            (developerMode && truthChanged(truthTyped, existing.truth)))

    SheetSection(if (kind == MeasureKind.DBH) "DIAMETER" else "HEIGHT") {
        if (existing == null) {
            Text("Not measured. Type the number, or measure it now.",
                style = type.caption, color = colors.textTertiary)
        }
        OutlinedTextField(
            value = valueText,
            onValueChange = { valueText = TruthInput.sanitize(it) },
            placeholder = { Text(FieldLogTypedInput.placeholder(kind, imperial)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        valueWarning?.let {
            Text(it, style = type.caption, color = colors.confidenceBad)
        }
        if (developerMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Label and toggle read the SAME unit, so the field can never
                // say cm while the value is taken as inches.
                OutlinedTextField(
                    value = truthText,
                    onValueChange = { truthText = TruthInput.sanitize(it) },
                    label = { Text(TruthInput.fieldLabel(quantity, truthUnit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                // Switching the unit CONVERTS the digits, and this is
                // deliberately NOT what the three scan screens do.
                //
                // There the field starts empty and the toggle means "the
                // number I am about to type is in this unit", so
                // reinterpreting is the whole point. Here the field is seeded
                // from a truth already on the record. Reinterpreting it would
                // take a stored 30 cm, call it 30 in and write back 76.2 cm —
                // a silent corruption of the tape value the entire study is
                // measured against, reached by one tap on a square button.
                // Converting cannot change the quantity, only how it is
                // spelled. Text that does not parse is left alone rather than
                // blanked; the cruiser is mid-edit and their keystrokes are
                // not ours to discard.
                TruthUnitToggle(truthUnit, onDarkPanel = false) {
                    val next = TruthInput.toggled(truthUnit)
                    TruthInput.parsePositiveBase(truthText, truthUnit)?.let {
                        truthText = TruthInput.text(it, next)
                    }
                    truthUnit = next
                }
            }
            TruthInput.fieldWarning(truthText, quantity, truthUnit)?.let {
                Text(it, style = type.caption, color = colors.confidenceBad)
            }
            // WHY SAVE IS OFF. A truth is an observation ABOUT a reading, so it
            // needs one to sit on: storing it alone would mean inventing a
            // measurement to hang it from, which is the one thing this app must
            // never do. But the cruiser who failed to get a scan and DID tape
            // the tree still has a number to record — it is a typed
            // measurement, stamped as typed — and a Save that just stays grey
            // never said so.
            if (existing == null && typed == null && truthTyped != null) {
                Text(
                    "A ground truth attaches to a reading. Type the tape number as the measurement above — it is saved as typed, not measured.",
                    style = type.caption, color = colors.confidenceBad)
            }
        }
        ForestixProminentButton(
            "Save changes", modifier = Modifier.fillMaxWidth(), enabled = canSave,
        ) {
            val value = typed
            if (value != null) {
                // Developer mode owns the truth field. With it off the stored
                // truth is not on screen, so a save must leave it as it was.
                val truth = if (developerMode) truthTyped else existing?.truth
                if (existing != null) {
                    val next =
                        if (abs(value - existing.value) > FIELD_LOG_VALUE_EPSILON)
                            existing.typedValue(value)
                        else existing
                    onSave(next.copy(truth = truth))
                } else {
                    onAdd(
                        QuickMeasureEntry.typed(
                            kind = kind, value = value, treeNumber = tree,
                            treeName = row.treeName,
                            plotID = row.entries.firstOrNull()?.plotID,
                            truth = truth))
                }
            }
        }
        ForestixBorderedButton(
            label = when {
                kind == MeasureKind.DBH && existing != null -> "Measure the diameter again"
                kind == MeasureKind.DBH -> "Measure the diameter"
                existing != null -> "Measure the height again"
                else -> "Measure the height"
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            onRemeasure(
                kind, tree, row.treeName,
                row.entries.firstNotNullOfOrNull {
                    it.speciesCode?.takeIf(String::isNotEmpty)
                },
                existing?.truth)
        }
    }
}

// MARK: - New tree --------------------------------------------------------

/// A tree the cruiser is entering by hand, raised from the log's toolbar or
/// its empty state.
///
/// Every join key is resolved HERE, once, when the sheet opens — not read
/// again when Create is tapped. The number on screen is the number that gets
/// written, and the plot cannot move under the cruiser while they type.
private data class FieldLogNewTree(
    val treeNumber: Int,
    val plotID: UUID?,
    /// Shown so the cruiser can see which plot the stem joins. Null when the
    /// log has no plot to name — the readings are then stored with no plot,
    /// exactly as the pre-plot readings are, rather than claiming one.
    val plotName: String?,
    val suggestedName: String,
    /// Identity for the field state below, so a second "add a tree" opens on
    /// empty fields rather than the last one's leftovers.
    val id: UUID = UUID.randomUUID(),
)

/// Enter a tree the sensors never read.
///
/// THE FIELD CASE: the scan will not lock — bad light, too close, the depth
/// map refuses — so the cruiser tapes the stem by hand and walks on. The log
/// builds its rows by grouping READINGS, so without this sheet that tree has
/// no row to tap and no way into the app at all. It is also the worst row an
/// accuracy study can lose, because it is precisely the stem the sensors
/// found hard.
///
/// Everything here is the existing typed path with no row to start from: the
/// same tree-number rule as the measure chooser, the same name and species
/// controls, the same field rules as the row editor, and the same
/// [QuickMeasureEntry.typed] factory doing the writing.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldLogNewTreeSheet(
    request: FieldLogNewTree,
    unitSystem: UnitSystem,
    onDismiss: () -> Unit,
    /// (tree name, species code, diameter in cm, height in m) — null for
    /// anything the cruiser left blank. The host does the writing.
    onCreate: (String?, String?, Double?, Double?) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val imperial = unitSystem == UnitSystem.IMPERIAL

    var treeName by remember(request.id) { mutableStateOf(request.suggestedName) }
    var speciesCode by remember(request.id) { mutableStateOf<String?>(null) }
    var dbhText by remember(request.id) { mutableStateOf("") }
    var heightText by remember(request.id) { mutableStateOf("") }

    val dbh = FieldLogTypedInput.accepted(dbhText, MeasureKind.DBH, imperial)
    val height = FieldLogTypedInput.accepted(heightText, MeasureKind.HEIGHT, imperial)
    // Create needs at least one number, and refuses while a field holds
    // something that cannot be stored — a typo in the height must not be
    // quietly dropped on the floor while the diameter is saved.
    val dbhOK = TruthInput.normalized(dbhText).isEmpty() || dbh != null
    val heightOK = TruthInput.normalized(heightText).isEmpty() || height != null
    val canCreate = dbhOK && heightOK && (dbh != null || height != null)

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
                .padding(bottom = ForestixSpace.xl)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            Text("New tree", style = type.title, color = colors.textPrimary)

            SheetSection("TREE") {
                // Not typed, and not editable: it is the next free number, the
                // same rule the measure chooser follows, so a stem entered
                // here cannot collide with a scanned one.
                SheetRow("Tree number", "#${request.treeNumber}")
                OutlinedTextField(
                    value = treeName,
                    onValueChange = { treeName = it },
                    placeholder = { Text("Tree name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The same control the chooser and the details sheet use — one
                // species list, one typed-code escape, no second copy to drift.
                SpeciesPickerField(
                    speciesCode = speciesCode,
                    onSpeciesCode = { speciesCode = it },
                    unspecifiedLabel = "Species",
                    bordered = true,
                )
                // Named on screen rather than assumed: the cruiser can see
                // which plot is about to own this stem.
                request.plotName?.let { SheetRow("Plot", it) }
            }

            NewTreeMeasurementField(
                MeasureKind.DBH, dbhText, { dbhText = it }, imperial)
            NewTreeMeasurementField(
                MeasureKind.HEIGHT, heightText, { heightText = it }, imperial)

            Text(
                "Type what you taped. A tree is recorded by its readings, so it needs a diameter or a height — and both are saved as typed, not measured.",
                style = type.caption, color = colors.textTertiary,
            )
            ForestixProminentButton(
                "Create tree", modifier = Modifier.fillMaxWidth(), enabled = canCreate,
            ) {
                onCreate(
                    // trim(), matching iOS's `.whitespacesAndNewlines`: the
                    // same pasted "Plot3-T07\n" has to persist the same bytes
                    // on both phones, because the two halves of a split cruise
                    // join on it.
                    treeName.trim().ifEmpty { null },
                    speciesCode, dbh, height)
            }
        }
    }
}

/// One typed measurement on the new-tree sheet — the same field, unit and
/// refusals as the row editor's, because both go through [FieldLogTypedInput].
@Composable
private fun NewTreeMeasurementField(
    kind: MeasureKind,
    text: String,
    onText: (String) -> Unit,
    imperial: Boolean,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    SheetSection(if (kind == MeasureKind.DBH) "DIAMETER" else "HEIGHT") {
        OutlinedTextField(
            value = text,
            onValueChange = { onText(TruthInput.sanitize(it)) },
            placeholder = { Text(FieldLogTypedInput.placeholder(kind, imperial)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldLogTypedInput.warning(text, kind, imperial)?.let {
            Text(it, style = type.caption, color = colors.confidenceBad)
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        Text(title, style = type.sectionHead.copy(letterSpacing = 0.6.sp),
            color = colors.textTertiary)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(ForestixRadius.card)
                .background(colors.surface)
                .padding(ForestixSpace.md),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
        ) { content() }
    }
}

@Composable
private fun SheetRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type.body, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = type.body, color = colors.textPrimary, textAlign = TextAlign.End)
    }
}

/// The capture photo, if the file is still there. A missing file says so
/// rather than leaving an empty box — a blank row would read as "no photo
/// was taken".
///
/// Tapping it opens the shared full-screen viewer, the same one the map peek
/// and the cruise tree peek open, which is where a tree's second frame is
/// reachable.
@Composable
private fun FieldLogPhoto(name: String, count: Int, onOpen: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    // THIS is where every photo in the log went missing. The row is drawn
    // inside the detail ModalBottomSheet, whose composition is hosted by a
    // ComponentDialog built on ContextThemeWrapper(activity, …) — so
    // LocalContext here is that wrapper and `as? Activity` was null for
    // every row, every time. The map peek never hit it because MapHomeScreen
    // resolved its Activity outside the dialog and passed it in.
    //
    // The store now resolves against a Context and loads off the main
    // thread, so this reads the same file the map reads, by the same call.
    val context = LocalContext.current
    // Null while the decode is in flight AND when the file is really gone,
    // so the two are held apart: "file missing" is only stated once the
    // load has actually come back empty, never while it is still running.
    var loaded by remember(name) { mutableStateOf(false) }
    var bitmap by remember(name) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(name, context) {
        bitmap = MeasurePhotoStore.loadBitmap(context, name, targetPx = 1600)
        loaded = true
    }
    val shot = bitmap
    if (shot != null) {
        Box(Modifier.fillMaxWidth().clickableNoRipple(onOpen)) {
            Image(
                shot.asImageBitmap(),
                contentDescription = "Capture photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .clip(ForestixRadius.control),
            )
            // Same badge, same wording as the map peek's thumbnail ("×2").
            // Drawn only from two photos up — with one photo nothing sits
            // over the image and the sheet is what it always was.
            if (count > 1) {
                Text(
                    "×$count",
                    style = type.dataSmall.copy(
                        fontSize = 9.sp, fontWeight = FontWeight.Bold),
                    color = Color(0xFFF2F5F3),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xB306090A))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    } else if (loaded) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Photo", style = type.body, color = colors.textSecondary)
            Spacer(Modifier.weight(1f))
            Text("file missing", style = type.body, color = colors.textTertiary)
        }
    }
}

// MARK: - Compact relative time -------------------------------------------

private fun relativeAgo(epochMs: Long): String {
    val now = System.currentTimeMillis()
    val diff = (now - epochMs).coerceAtLeast(0)
    val min = diff / 60000
    val hr = min / 60
    val day = hr / 24
    return when {
        min < 1 -> "now"
        min < 60 -> "${min}m"
        hr < 24 -> "${hr}h"
        day < 7 -> "${day}d"
        else -> SimpleDateFormat("MMM d", Locale.US).format(Date(epochMs))
    }
}

// MARK: - One ground truth per reading
//
// FIELD REPORT — the detail sheet used to show a tree's ground truth TWICE:
// the editable truth field inside the DIAMETER and HEIGHT sections, and a
// read-only "GROUND TRUTH" section at the foot that read RawCaptureStore
// keyed on tree number. The two could hold different numbers, and nothing on
// screen said which one the export read.
//
// The second surface is gone. The truth field in each section is now the only
// place a truth is shown and the only place it is edited, and it is the one
// that has always been exported: `QuickMeasureEntry.truth`, the `truth`
// column in the readings CSV and `truth_cm` / `truth_m` in the stems and
// heights CSVs.
//
// What stopped being DISPLAYED: a hand value that sits in a raw-capture
// manifest and on no reading — typed before the truth lived on the reading,
// or typed for a capture whose reading was later deleted. Those were the only
// rows the section could still draw.
//
// What stopped being EXPORTED: nothing. That manifest value was never in any
// CSV; the section itself said so. It is still written, still in the manifest,
// and still ships inside the raw-capture ZIP exactly as before, where it
// belongs to the CAPTURE rather than to a reading.
//
// The manifest copy is deliberately NOT written back from this sheet. A truth
// keyed on tree number alone cannot say WHICH of that tree's readings it was
// taped for — that is the reason the truth was moved onto the reading in the
// first place (see `QuickMeasureEntry.truth`), and guessing here would put a
// number the cruiser never entered into the research corpus.
