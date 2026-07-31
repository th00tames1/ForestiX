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
// record (species, damage, note, position, photo, and in developer mode the
// ground truth typed against that tree).
//
// THAT SHEET IS THE TREE FORM. It draws the same three sections in the same
// order, with the same row labels and the same em dash, as the cruise tree's
// own screen — see the shared definitions at the foot of TreeDetailScreen.kt.
// A cruiser learns one screen. Where a quick reading does not carry a field
// the form has (a tally status), the row is still there and reads "—";
// where it carries something a cruise tree does not (the readings behind the
// row, the fix, the capture mode, the frame), that follows underneath.
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
// READ-ONLY MEANS NOT WRITTEN, NOT UNOPENABLE. That rule was over-applied:
// a cruise row had no chevron and no tap target at all, so the log listed a
// tree the cruiser could not inspect from the surface they were reading it
// on. A cruise row now OPENS — it navigates to the existing TreeDetailScreen,
// the one surface that owns this store's writes. What the original argument
// was actually about survives untouched: the log still writes no MEASUREMENT
// onto a cruise tree. There is no inline editor and no swipe-delete for those
// rows, so a diameter or a height on a cruise stem still has exactly one save
// path.
//
// The three-column table did NOT grow two more columns for project and
// plot: the whole point of the one-row-per-tree change above was that four
// columns on a phone had every cell scaling to fit. The section heading
// carries the project and the plot for every row beneath it instead.
//
// The screen otherwise keeps its shape: a summary card for WHATEVER THE
// FILTER IS SHOWING (see FieldLogSummary.kt — it used to be a card about one
// special case, the active quick-measure plot, and so was blank or stale under
// any other filter), grouped summary card (total / today / last + capacity
// banner), then one grouped surface card of rows with hairline dividers and
// trailing swipe-to-delete.
//
// The filter used to be a three-line bar at the top of the list; it is a
// toolbar funnel now, and the card below it says what the filter is set to.
// "New tree" gave up the (+) slot to that funnel and moved into the overflow
// menu beside it, alongside Export — still in the toolbar, which is the only
// part of this screen that stays reachable however far the list is scrolled.

package com.hcjeong.forestix.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
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
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
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
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.common.MeasuredTimeInput
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.common.TruthInput
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.TruthBackfill
import com.hcjeong.forestix.data.TruthBackfillReport
import com.hcjeong.forestix.sensors.HeightEstimator
import com.hcjeong.forestix.ui.MeasurePhotoStore
import com.hcjeong.forestix.ui.PendingTreeNumber
import com.hcjeong.forestix.ui.Routes
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.screens.plot.PlotFlowRoutes
import com.hcjeong.forestix.ui.screens.plot.FieldLogSummaryDetailSheet
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryCard
// The record sheet draws THE tree form — the same sections, rows and words
// the cruise tree's screen draws, from the same definitions.
import com.hcjeong.forestix.ui.screens.tree.TierExplainerKind
import com.hcjeong.forestix.ui.screens.tree.TierExplainerSheet
import com.hcjeong.forestix.ui.screens.tree.TreeFormComputedSection
import com.hcjeong.forestix.ui.screens.tree.TreeFormConfidenceRow
import com.hcjeong.forestix.ui.screens.tree.TreeFormMeasurementRow
import com.hcjeong.forestix.ui.screens.tree.TreeFormRow
import com.hcjeong.forestix.ui.screens.tree.TreeFormSection
import com.hcjeong.forestix.ui.screens.tree.TreeFormSpeciesRows
import com.hcjeong.forestix.ui.screens.tree.TreeFormWords
import com.hcjeong.forestix.ui.shareFile
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixBorderedButton
import com.hcjeong.forestix.ui.theme.ForestixColors
import com.hcjeong.forestix.ui.theme.ForestixDenseTextScale
import com.hcjeong.forestix.ui.theme.ForestixProminentButton
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.ForestixTypography
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
    /// What the summary card is showing — a function of [logScope]. Under
    /// Everything it is the one stand, or the log's counts; null only when
    /// nothing has been measured at all, or while the scope points at a plot
    /// the store no longer has. See FieldLogSummary.kt.
    var summary by remember { mutableStateOf<FieldLogSummary?>(null) }
    /// The cruise plot whose details the cruiser is editing, from a section
    /// heading. Item 8's second door: the first is the cruising map.
    var editingPlotDetail by remember { mutableStateOf<UUID?>(null) }
    var showingSummaryDetail by remember { mutableStateOf(false) }

    // Bulk re-file — see TreeMove.kt for the cruise move and QuickMove.kt
    // for the quick one.
    //
    // What the cruiser has ticked, or null when the log is NOT in selection
    // mode. null vs. empty is load-bearing: an empty set is "selecting,
    // nothing ticked yet" and still shows the bar, so the way out stays on
    // screen after the last row is un-ticked.
    //
    // It carries its WORLD because the two worlds have different
    // destinations: a cruise tree is re-parented to a cruise plot inside a
    // project, a quick reading is re-filed under a quick-measure plot, and
    // nothing can go to both. The gesture, the bar, the wording and the
    // confirmation shape are shared, so the two read as one mode with one
    // answer to "where is this going".
    //
    // Deliberately `remember`, not `rememberSaveable`: leaving the screen
    // disposes this composable and takes the selection with it, which is
    // the rule — a ticked set must not survive as a hidden mode.
    var selection by remember { mutableStateOf<FieldLogSelection?>(null) }
    var pickingDestination by remember { mutableStateOf(false) }
    /// The quick rows handed to [QuickMoveFlow]. Non-null while that move is
    /// in progress; the flow owns the picker, the confirmation and the
    /// report.
    var quickMoveRequest by remember { mutableStateOf<List<QuickMoveRow>?>(null) }
    /// The worked-out move, held while the confirmation is up. Nothing has
    /// been written at this point.
    var pendingPlan by remember { mutableStateOf<TreeMovePlan?>(null) }
    /// A finished move that did NOT fully succeed. A clean move reports
    /// itself by the rows appearing under their new heading.
    var moveResult by remember { mutableStateOf<TreeMoveOutcome?>(null) }
    /// The destination could not be read, so no move was planned.
    var planFailure by remember { mutableStateOf<String?>(null) }
    /// Every picked tree was already in the chosen plot.
    var nothingToMove by remember { mutableStateOf<String?>(null) }
    val haptics = LocalHapticFeedback.current

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
                    // When work in this plot BEGAN — the same minimum the
                    // rows use, one level up. See [FieldLogSection.measuredAt].
                    measuredAt = group.minOf { it.createdAt },
                )
            }
    }
    val sections = remember(quickSections, cruiseData, logScope) {
        // Ties break on id so two sections stamped in the same millisecond
        // cannot swap places between two recompositions.
        (quickSections + cruiseData.sections(logScope)).sortedWith(
            compareByDescending<FieldLogSection> { it.measuredAt }.thenBy { it.id },
        )
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

    /// Whether to hand the screen over to "you have measured nothing yet".
    ///
    /// ONLY UNDER EVERYTHING. A cruiser who has filtered to one plot has asked
    /// a question about that plot, and the answer is that plot's card reading
    /// "Nothing measured in this plot yet." — not a screen-filling notice
    /// about the whole app. Routing every empty log to EmptyState swallowed
    /// the card in the case it was most wanted: a plot just planned, opened to
    /// check its design before the first tree goes in. Mirrors iOS
    /// `showsWholeLogEmptyState`.
    val showsWholeLogEmptyState = logIsEmpty && logScope is FieldLogScope.Everything

    val rows = remember(entries) { fieldLogRows(entries) }

    // Rebuilt when the filter moves, when the cruise store is re-read, and
    // when the quick readings themselves change — keyed on the readings and
    // not on how many there are, because correcting a diameter in the record
    // sheet changes the QMD without changing the count. Two of the three
    // branches read the cruise store, so this belongs in an effect rather
    // than in composition.
    LaunchedEffect(logScope, entries, plots, cruiseData, settings) {
        summary = FieldLogSummaryBuilder.make(
            scope = logScope, quickPlots = plots, quickEntries = entries,
            settings = settings, env = env, cruiseData = cruiseData)
    }

    // MARK: bulk re-file helpers
    //
    // The ticked ids in the order they appear ON SCREEN. `selection` is a
    // Set, and the tree-number allocation in TreeMover.plan walks the list
    // in order — reading it back off the sections keeps that allocation
    // deterministic and in the order the cruiser is looking at.
    val cruiseSelection = (selection as? FieldLogSelection.Cruise)?.ids
    val quickSelection = (selection as? FieldLogSelection.Quick)?.ids

    val selectedIDsInOrder: List<UUID> = cruiseSelection?.let { picked ->
        sections.flatMap { it.cruiseRows }.map { it.id }.filter { it in picked }
    } ?: emptyList()

    /// The ticked quick rows, in on-screen order, as the mover wants them.
    /// Same reason as above: QuickMover.plan walks them in order when it
    /// works out which tree numbers the destination has left.
    val selectedQuickRows: List<QuickMoveRow> = quickSelection?.let { picked ->
        sections.flatMap { it.quickRows }.filter { it.id in picked }.map(::quickMoveRow)
    } ?: emptyList()

    fun toggleCruise(id: UUID) {
        val current = cruiseSelection ?: return
        selection = FieldLogSelection.Cruise(if (id in current) current - id else current + id)
    }

    fun toggleQuick(id: String) {
        val current = quickSelection ?: return
        selection = FieldLogSelection.Quick(if (id in current) current - id else current + id)
    }

    /// A long press on a cruise row. The first one opens selection mode with
    /// that row ticked; later ones just toggle, so a press that lands on an
    /// already-selected list behaves like the tap beside it. A press that
    /// lands while the OTHER world is being selected switches worlds rather
    /// than doing nothing — the two cannot go to one destination, and a long
    /// press that silently did nothing would read as a bug.
    fun longPressCruise(id: UUID) {
        if (cruiseSelection != null) {
            toggleCruise(id)
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            selection = FieldLogSelection.Cruise(setOf(id))
        }
    }

    /// The quick half of the same gesture. See [longPressCruise].
    fun longPressQuick(id: String) {
        if (quickSelection != null) {
            toggleQuick(id)
        } else {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            selection = FieldLogSelection.Quick(setOf(id))
        }
    }

    /// Destination picked. Plans the move — reading the destination plot's
    /// existing tree numbers — and raises the confirmation. NOTHING is
    /// written here.
    fun choose(destination: TreeMoveDestination) {
        pickingDestination = false
        val ids = selectedIDsInOrder
        scope.launch {
            try {
                val plan = TreeMover.plan(ids, destination, env)
                if (plan.hasWork) {
                    pendingPlan = plan
                } else {
                    nothingToMove = TreeMoveWords.nothingToMove(destination.label)
                }
            } catch (e: Exception) {
                // The free numbers in the destination are unknown, so the
                // move cannot be planned without guessing them. Say so;
                // write nothing.
                planFailure = TreeMoveWords.planFailedMessage(e.message ?: e.toString())
            }
        }
    }

    fun commit(plan: TreeMovePlan) {
        pendingPlan = null
        scope.launch {
            val outcome = TreeMover.apply(plan, env)
            selection = null
            // Re-read: the moved rows belong under a different heading now,
            // and a stale feed would go on showing them under the old one.
            cruiseData = loadFieldLogCruiseData(env)
            if (!outcome.isClean) moveResult = outcome
        }
    }

    // The plot this screen is reporting on — the one whose summary card sits
    // at the top of the log.
    //
    // A hand-entered stem joins the plot the cruiser is LOOKING at, so THE
    // FILTER decides it: filtered to one quick plot, the stem joins that one.
    // The card above the list is now that plot's card, and a "New tree" that
    // landed somewhere else would file a stem under a plot the cruiser is not
    // looking at and cannot see it appear in. Every other scope falls back to
    // the active plot, resolved through the default the same way every entry's
    // plotID is read — a cruise scope included, because a quick reading has no
    // cruise plot it could join.
    //
    // It is captured when the sheet opens rather than read again on Create, so
    // a plot switched elsewhere in the app mid-typing cannot claim the tree.
    val shownPlotID = (logScope as? FieldLogScope.QuickPlot)?.id
        ?: activePlotID ?: plots.firstOrNull { it.isDefault }?.id
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
            // And the same species suggestion, from the same place in the
            // history — a hand-entered stem stands in the same stand as the
            // scanned ones either side of it.
            suggestedSpecies = env.history.suggestedNextSpeciesCode,
        )
    }

    ForestixScaffold(
        nav, title = "Field log",
        actions = {
            // THE FILTER LIVES HERE NOW. It used to be a bar at the top of the
            // list carrying a chevron, the scope's name and a two-line caption
            // explaining the two worlds — three lines of chrome above every
            // reading, on the screen a cruiser scrolls most. The funnel is
            // filled while a filter is applied, so the toolbar says whether the
            // list is narrowed without spending a row saying it; WHAT it is
            // narrowed to is on the summary card immediately below, which now
            // follows the same scope.
            IconButton(onClick = { choosingScope = true }) {
                Icon(
                    if (logScope is FieldLogScope.Everything) Icons.Outlined.FilterAlt
                    else Icons.Filled.FilterAlt,
                    contentDescription = FieldLogWords.FILTER_TITLE + ": " +
                        fieldLogScopeLabel(logScope, plots, cruiseData),
                    tint = colors.primary)
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = colors.primary)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Ungated, unlike Export, and FIRST in the menu: the case it
                // exists for is a log with nothing in it yet and a stem the
                // scan would not lock. It lost the (+) slot to the funnel, so
                // it lives one tap deeper — but it is still in the toolbar,
                // which is the only part of this screen that stays reachable
                // however far the list is scrolled. The empty state keeps its
                // full-width button, because an empty log is exactly where a
                // cruiser looks for one.
                DropdownMenuItem(
                    text = { Text("New tree") },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                    onClick = {
                        menuOpen = false
                        startNewTree()
                    })
                // Export writes the QUICK-MEASURE tables. Under a cruise scope
                // none of the rows on screen are in them, so it is not
                // offered: a file that silently holds different trees than the
                // list above it is worse than no button. The cruise bundle has
                // its own "Export all" on the project screen.
                if (quickWorldVisible && entries.isNotEmpty()) {
                    HorizontalDivider(color = colors.divider, thickness = 0.5.dp)
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
        Column(Modifier.padding(padding).fillMaxSize()) {
        // The count of what is ticked, and the way out, PINNED above the
        // list rather than parked in a row of it — a selection the cruiser
        // has scrolled away from is exactly the hidden mode this must not
        // become.
        selection?.let { picked ->
            TreeMoveSelectionBar(
                selected = when (picked) {
                    is FieldLogSelection.Cruise -> picked.ids.size
                    is FieldLogSelection.Quick -> picked.ids.size
                },
                moveTitle = when (picked) {
                    is FieldLogSelection.Cruise -> TreeMoveWords.MOVE_BUTTON
                    is FieldLogSelection.Quick -> QuickMoveWords.MOVE_BUTTON
                },
                onMove = {
                    when (picked) {
                        is FieldLogSelection.Cruise -> pickingDestination = true
                        is FieldLogSelection.Quick -> quickMoveRequest = selectedQuickRows
                    }
                },
                onCancel = { selection = null })
        }
        if (showsWholeLogEmptyState) {
            EmptyState(Modifier, onNewTree = startNewTree)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(ForestixSpace.md),
            ) {
                // The summary card — the computed values for WHATEVER THE
                // FILTER IS SHOWING, quick plot or cruise plot or cruise
                // project, and under Everything the log's counts. It is always
                // present when anything has been measured: the card going
                // missing on the screen's own default scope was the original
                // complaint.
                summary?.let { current ->
                    item(key = "plotSummary") {
                        Box(Modifier.padding(bottom = ForestixSpace.md)) {
                            PlotSummaryCard(
                                summary = current,
                                onOpenDetail = { showingSummaryDetail = true })
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
                    //
                    // ON A CRUISE SECTION IT IS ALSO THE WAY IN TO THE PLOT.
                    // Aspect, slope, elevation and canopy cover could only be
                    // entered from the cruising map with the pin selected, so
                    // a plot reviewed later — which is what the field log is
                    // for — offered no way to record or correct them. Tapping
                    // the plot's own heading is where a cruiser looks for
                    // that. Mirrors the iOS section header.
                    item(key = "h|${section.id}") {
                        val plotID = section.cruisePlotID
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (plotID == null) Modifier
                                    else Modifier.clickable { editingPlotDetail = plotID })
                                .padding(start = ForestixSpace.md, top = ForestixSpace.md),
                        ) {
                            Text(
                                section.heading,
                                style = Forestix.type.sectionHead,
                                color = colors.textSecondary,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (plotID != null) {
                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = FieldLogWords.OPEN_PLOT_DETAIL,
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(16.dp))
                            }
                        }
                        ColumnHeader()
                    }

                    // Quick rows keep every path they had: tap to inspect,
                    // swipe to delete, re-measure from the detail sheet.
                    //
                    // NUMBERED HERE, AT THE POINT OF RENDER, off the index
                    // `itemsIndexed` hands back for the list the LazyColumn is
                    // actually drawing — never earlier, and never stored on
                    // the row. Whatever decides the order of
                    // `section.quickRows` is upstream of this line, so the
                    // ordinal cannot disagree with the order on screen. The
                    // identity is still `row.id`: the ordinal is not in the
                    // item key, so re-numbering after a delete moves no row's
                    // identity.
                    //
                    // The count RESTARTS in every section rather than running
                    // on through the screen. A section is one plot, the
                    // heading above it names that plot, and the cruiser is
                    // reading these rows against that plot's tally sheet — so
                    // the last ordinal in a section is the count they are
                    // checking. The whole-log total is already on the summary
                    // card above, and a continuous run would have said that
                    // number twice and the per-plot one nowhere.
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
                                // Press and hold now enters selection mode,
                                // exactly as it does on a cruise row, and
                                // once in it the ordinary click TOGGLES
                                // instead of opening — so a cruiser ticking
                                // a dozen readings never leaves the list by
                                // accident.
                                FieldLogRow(
                                    row, ordinal = index + 1, unitSystem = settings.unitSystem,
                                    onClick = {
                                        if (quickSelection == null) {
                                            inspectingId = row.id
                                        } else {
                                            toggleQuick(row.id)
                                        }
                                    },
                                    onLongClick = { longPressQuick(row.id) },
                                    selecting = quickSelection != null,
                                    isSelected = quickSelection?.contains(row.id) == true)
                            }
                        }
                    }
                    // Cruise rows OPEN: the click goes to TreeDetailScreen,
                    // which owns that store's writes. No inline editor and
                    // no swipe-delete here — a second path that saves a
                    // MEASUREMENT onto the same row is how two numbers for
                    // one tree get created, and that argument is untouched.
                    //
                    // Press and hold enters selection mode; once in it the
                    // ordinary click TOGGLES instead of navigating, so a
                    // cruiser ticking twelve rows never leaves the list by
                    // accident.
                    //
                    // The ordinal CONTINUES from the quick rows above rather
                    // than restarting: both blocks are one section under one
                    // heading, and two rows numbered "1" under the same
                    // heading would read as two lists. In practice a section
                    // holds one world — quick sections carry no cruise rows
                    // and vice versa — so this offset is normally zero; it is
                    // written this way so the numbering follows the render
                    // order rather than assuming that. It is the same offset
                    // the grouped-row shell already uses to round the right
                    // corners.
                    itemsIndexed(section.cruiseRows, key = { _, r -> "${section.id}|${r.id}" }) { index, row ->
                        FieldLogGroupedRow(
                            index = section.quickRows.size + index, total = total, colors = colors,
                        ) {
                            FieldLogCruiseRowView(
                                row,
                                ordinal = section.quickRows.size + index + 1,
                                system = settings.unitSystem,
                                onClick = {
                                    if (cruiseSelection == null) {
                                        nav.navigate(PlotFlowRoutes.treeDetail(row.id.toString()))
                                    } else {
                                        toggleCruise(row.id)
                                    }
                                },
                                onLongClick = { longPressCruise(row.id) },
                                selecting = cruiseSelection != null,
                                isSelected = cruiseSelection?.contains(row.id) == true)
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

                // Discoverability for the long press — one line per world,
                // each shown only when there is a row of that world on
                // screen to press, and neither while already selecting: the
                // bar above is saying what to do then.
                if (selection == null && sections.any { it.quickRows.isNotEmpty() }) {
                    item(key = "quickMoveHint") {
                        Text(
                            QuickMoveWords.HINT,
                            style = Forestix.type.caption, color = colors.textTertiary,
                            modifier = Modifier.padding(
                                start = ForestixSpace.md, top = ForestixSpace.xs))
                    }
                }
                if (selection == null && sections.any { it.cruiseRows.isNotEmpty() }) {
                    item(key = "moveHint") {
                        Text(
                            TreeMoveWords.HINT,
                            style = Forestix.type.caption, color = colors.textTertiary,
                            modifier = Modifier.padding(
                                start = ForestixSpace.md, top = ForestixSpace.xs))
                    }
                }
            }
        }
        } // Column — selection bar above, list below
    }

    if (showingSummaryDetail) {
        summary?.let {
            FieldLogSummaryDetailSheet(
                summary = it,
                onDismiss = { showingSummaryDetail = false },
                areaUnit = settings.unitSystem.areaUnit,
                onSaveArea = { plotID, acres ->
                    // Re-read what LANDED rather than what was typed: the
                    // store refuses an area it cannot divide by, and the sheet
                    // must show the plot, not the draft. The same entry filter
                    // the card was built with, so the two cannot disagree for
                    // a second reason.
                    val saved = env.history.setPlotAcres(plotID, acres)
                    saved?.let { plot ->
                        val defaultPlotID = plots.firstOrNull { p -> p.isDefault }?.id
                        FieldLogSummaryBuilder.quick(
                            plot,
                            entries.filter { e ->
                                (e.plotID ?: defaultPlotID) == plotID
                            },
                            settings)
                    }
                })
        }
    }

    editingPlotDetail?.let { plotID ->
        PlotDetailSheet(
            plotId = plotID,
            onDismiss = { editingPlotDetail = null },
            onSaved = {
                // The plot's own figures are on the summary card; a saved
                // slope or canopy cover does not change them, but the sheet
                // can also change the plot NUMBER, and the headings above
                // every cruise row are built from it. Re-reading the cruise
                // data re-keys the summary effect too, which is how the card
                // follows without a second rebuild call.
                scope.launch { cruiseData = loadFieldLogCruiseData(env) }
                editingPlotDetail = null
            })
    }

    if (choosingScope) {
        FieldLogScopeSheet(
            current = logScope,
            quickPlots = plots,
            data = cruiseData,
            // Changing scope hides rows, and a tick on a row that is no
            // longer on screen still counts toward the move: the bar would
            // say "12 selected" over a list of three.
            onPick = { logScope = it; selection = null; choosingScope = false },
            onDismiss = { choosingScope = false },
        )
    }

    if (pickingDestination) {
        TreeMovePicker(
            projects = cruiseData.projects,
            plotsByProject = cruiseData.plotsByProject,
            onPick = { choose(it) },
            onDismiss = { pickingDestination = false },
        )
    }

    // Names what will move and where BEFORE anything is written. This
    // rewrites which project a piece of field data was recorded in, and
    // there is no undo behind it.
    pendingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingPlan = null },
            title = { Text(TreeMoveWords.CONFIRM_TITLE) },
            text = {
                Text(
                    TreeMoveWords.confirmMessage(
                        destination = plan.destination.label,
                        movers = plan.movers.size,
                        alreadyThere = plan.alreadyThere,
                        renumbered = plan.renumbered.size,
                        destinationClosed = plan.destination.isClosed))
            },
            confirmButton = {
                TextButton(onClick = { commit(plan) }) {
                    Text(TreeMoveWords.confirmAction(plan.movers.size))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPlan = null }) { Text(TreeMoveWords.CANCEL) }
            },
            containerColor = colors.surface,
        )
    }

    // A PARTIAL move is reported as a partial move, naming every row that
    // stayed put and why. Nothing here ever says "done" over rows that did
    // not land.
    moveResult?.let { outcome ->
        AlertDialog(
            onDismissRequest = { moveResult = null },
            title = { Text(TreeMoveWords.FAILURE_TITLE) },
            text = {
                Text(
                    TreeMoveWords.failureMessage(
                        moved = outcome.movedCount,
                        total = outcome.attempted,
                        destination = outcome.destination.label,
                        failures = outcome.failures.map { it.line }))
            },
            confirmButton = {
                TextButton(onClick = { moveResult = null }) { Text("OK") }
            },
            containerColor = colors.surface,
        )
    }

    planFailure?.let { message ->
        AlertDialog(
            onDismissRequest = { planFailure = null },
            title = { Text(TreeMoveWords.PLAN_FAILED_TITLE) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { planFailure = null }) { Text("OK") }
            },
            containerColor = colors.surface,
        )
    }

    nothingToMove?.let { message ->
        AlertDialog(
            onDismissRequest = { nothingToMove = null },
            title = { Text(TreeMoveWords.CONFIRM_TITLE) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { nothingToMove = null }) { Text("OK") }
            },
            containerColor = colors.surface,
        )
    }

    // The quick-measure move owns its picker, its confirmation and its
    // report — see QuickMove.kt. The log only says which rows.
    QuickMoveFlow(
        rows = quickMoveRequest,
        onDismiss = { quickMoveRequest = null },
        onFinished = {
            // Selection mode ends whatever the outcome: the rows the cruiser
            // ticked have either moved (and are now under a different
            // heading) or been named in the report, and either way leaving
            // them ticked would point at a list they can no longer read.
            selection = null
        },
    )

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
                    onRowMoved = { inspectingId = it },
                    onRemeasure = { kind, tree, name, species, truth, truthSource, truthUnit ->
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
                            truthSource = truthSource, truthUnit = truthUnit,
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
            // The cruiser's own question was "서브플롯? 스탠드?? 플롯??" — this is
            // the answer, in one sentence. It used to sit above the list on the
            // log itself, two lines of caption over every reading; it belongs
            // here, under the two headers it explains, at the moment the
            // cruiser is choosing between them.
            Text(
                FieldLogWords.WORLDS_CAPTION,
                style = Forestix.type.caption, color = colors.textTertiary,
                modifier = Modifier.padding(top = ForestixSpace.md))
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
/// two read as one sheet of paper, and the same chevron: the row opens
/// TreeDetailScreen. It has no swipe and no inline editor — this store is
/// written by the cruise flow and edited on TreeDetailScreen, and that is
/// exactly where the click goes.
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FieldLogCruiseRowView(
    row: FieldLogCruiseRow,
    /// 1-based position in the list AS DRAWN — see [FieldLogRow]'s ordinal.
    ordinal: Int,
    system: UnitSystem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /// The log is in bulk-move selection mode. The tick box appears for
    /// every cruise row while it is, so an UNticked row is visibly unticked
    /// rather than merely missing a mark.
    selecting: Boolean = false,
    isSelected: Boolean = false,
) {
    val colors = Forestix.colors
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
    if (selecting) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) colors.primary else colors.textTertiary,
            modifier = Modifier.size(20.dp))
    }
    Column(Modifier.weight(1f)) {
        FieldLogColumns(
            // Same display ordinal, same column, same dimming as the quick row
            // — the two worlds read as one sheet of paper, and a cruise row
            // that skipped the number would break the run.
            ordinalSlot = {
                Text(
                    FieldLogWords.rowOrdinal(ordinal),
                    style = Forestix.type.fieldLogValue, color = colors.textTertiary,
                    textAlign = TextAlign.End, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            },
            treeSlot = {
                Text(
                    row.treeLabel, style = Forestix.type.fieldLogValue, color = colors.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            dbhSlot = {
                Text(
                    MeasurementFormatter.diameter(row.dbhCm, system),
                    style = Forestix.type.fieldLogValue, color = colors.textPrimary,
                    textAlign = TextAlign.End, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            },
            heightSlot = {
                Text(
                    row.heightM?.let { MeasurementFormatter.height(it, system) } ?: "—",
                    style = Forestix.type.fieldLogValue,
                    color = if (row.heightM == null) colors.textTertiary else colors.textPrimary,
                    textAlign = TextAlign.End, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            },
        )
        // Species under the tree name, through the same grid — see the quick
        // row for why this line is a table row rather than a plain Row.
        FieldLogColumns(
            Modifier.padding(top = 4.dp),
            ordinalSlot = {},
            treeSlot = {
                if (row.speciesCode.isNotEmpty()) {
                    Text(
                        RegionalSpecies.nameForCode(row.speciesCode),
                        style = Forestix.type.fieldLogMeta, color = colors.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            dbhSlot = {},
            heightSlot = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        relativeAgo(row.recordedAt),
                        style = Forestix.type.fieldLogMeta, color = colors.textTertiary,
                        maxLines = 1)
                    // The same "there is more behind this" affordance a quick
                    // row carries, for the same reason: this row opens. A
                    // chevron on a row that cannot be opened would be worse
                    // than none, so it appears here only because the click
                    // above it is real — and while selecting the click toggles
                    // instead, so it goes.
                    if (!selecting) {
                        Icon(
                            Icons.Filled.ChevronRight, contentDescription = null,
                            tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            },
        )
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
    /// WHEN THIS TREE WAS MEASURED — the sort key, and the time the row and
    /// the record sheet print.
    ///
    /// It is the EARLIEST reading in the group: the moment the cruiser
    /// arrived at this stem. Every later reading on it — the height after the
    /// diameter, a re-measure, a number corrected from the sheet — is the
    /// same visit to the same tree, not a new one.
    ///
    /// NOT the newest reading, which is what this used to be and is what
    /// scrambled the log: adding any reading to an old tree re-dated the
    /// whole row and threw it to the top of a list the cruiser was reading in
    /// order. NOT the diameter's time either, tempting as it is when DBH is
    /// what the workflow measures first — a row can have no diameter at all
    /// (height-only trees, and every loose reading), and a key that some rows
    /// do not have is a key that has to be invented for them.
    ///
    /// The iOS `FieldLogRowModel.measuredAt` is the same minimum.
    val measuredAt: Long,
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

/// What the log has ticked, and which WORLD it is ticking in. See the note
/// on `selection` in [FieldLogScreen] — the two worlds have different
/// destinations, so a selection has to know which one it is headed for.
sealed interface FieldLogSelection {
    data class Cruise(val ids: Set<UUID>) : FieldLogSelection
    /// Quick rows are keyed by [FieldLogRowModel.id], not by entry id: a row
    /// is a TREE, and it is the tree that moves.
    data class Quick(val ids: Set<String>) : FieldLogSelection
}

/// The id of the row a TREE's readings collapse into.
///
/// Stated once, because three surfaces have to agree on it: [fieldLogRows]
/// builds it, the map peek resolves a pin to it, and the record sheet
/// re-resolves itself after a move — which changes the plot half of the key,
/// and therefore the row's identity. A second hand-written copy of this
/// format is how one of them silently stops finding the row.
///
/// [plotID] is used RAW, exactly as the reading holds it, so a row's id is a
/// fact about the entries rather than about the default plot at the time it
/// was computed. iOS `FieldLogRowModel.treeRowID` builds the same string.
internal fun fieldLogTreeRowId(plotID: UUID?, treeNumber: Int): String =
    "t|${plotID?.toString() ?: "-"}|$treeNumber"

/// The id of the row a LOOSE reading gets to itself.
internal fun fieldLogLooseRowId(entryID: UUID): String = "e|$entryID"

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

/// Collapses the flat entry list into rows, most recently MEASURED tree
/// first.
///
/// [entries] arrives newest-first from the history, and that order is
/// preserved INSIDE each group, so `dbh` / `height` pick up the latest
/// reading of each kind without a second sort.
///
/// The rows themselves are then sorted on [FieldLogRowModel.measuredAt] —
/// they used to be left in the order the groups first appeared, which is the
/// order of each group's NEWEST reading, and that is the ordering that field
/// note describes as the bug. Ties break on `id` so a row can never swap
/// places with another between two renders of the same data.
internal fun fieldLogRows(entries: List<QuickMeasureEntry>): List<FieldLogRowModel> {
    val order = mutableListOf<String>()
    val grouped = LinkedHashMap<String, MutableList<QuickMeasureEntry>>()
    for (e in entries) {
        val key = e.treeNumber?.let { fieldLogTreeRowId(e.plotID, it) }
        // Never merged with anything: one row, this reading.
            ?: fieldLogLooseRowId(e.id)
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
            measuredAt = group.minOf { it.createdAt },
        )
    }.sortedWith(
        compareByDescending<FieldLogRowModel> { it.measuredAt }.thenBy { it.id },
    )
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
// FOUR columns now — a narrow display ordinal in front of the three the
// cruiser reads. Dropping RANGE and QUAL gave back roughly 128 dp on a 360 dp
// phone, and shrinking the row's type from `data` (17 sp) to `dataSmall`
// (13 sp) gave back about a quarter of every string on top of that, which is
// what pays for the ordinal column. A row has screen − 32 (list inset)
// − 32 (row padding) − 18 (three 6 dp gutters) to share:
//
//   column   widest content        needs    360 dp   411 dp   320 dp
//   #        "12 ·"                 31 dp    35 dp    41 dp    30 dp
//   TREE     "Plot3-T08"            70 dp    82 dp    97 dp    71 dp
//   DBH      "150.0 cm"             62 dp    80 dp    95 dp    69 dp
//   HEIGHT   "328.1 ft"             62 dp    80 dp    95 dp    69 dp
//
// (Needs are at 13 sp monospaced, ~7.8 dp per glyph. Diameter and height
// each print one decimal, so eight glyphs is the widest either unit system
// produces. Every cell clears its column at 360 dp and above. On a 320 dp
// phone a two-digit ordinal is 1 dp over and ellipsises to "12·" — the
// number is still legible, and the alternative was taking that dp off a
// tree name that is already level with its column there.)
//
// The ordinal column holds two digits and a separator and no more: it is a
// reading aid, and the log has already lost a column to fit on a phone. Past
// 99 rows in one section the cell ellipsises rather than taking width off the
// name — a three-digit ordinal is a rarity, a truncated tree name is a row
// the cruiser cannot identify. TREE carries a NAME, not just "#128", so it
// holds the largest share of what is left.
//
// Same numbers as the iOS sibling's `FieldLogTable.weights` — only the ratios
// matter, so the two files can be read side by side.
//
// Every heading is a single unwrappable word, so nothing can break
// mid-word the way "PRECISI/ON" and "QUALI/TY" did. The two numeric cells
// may take a second line instead — a sampling-plot reading is wider than
// any phone column, and a taller row is better than a measurement the
// cruiser cannot read in full.
private const val ColOrdinalWeight = 1.75f
private const val ColTreeWeight = 4.15f
private const val ColDbhWeight = 4.05f
private const val ColHeightWeight = 4.05f
private val ColGap = 6.dp

/// The font every value cell in a TREE ROW uses.
///
/// FIELD REPORT — the rows were `data` (17 sp) and read as oversized under a
/// summary card that had already come down to the same size, so the list and
/// its header carried equal weight. `dataSmall` (13 sp) is the next step of
/// the same scale and the floor of it: it is still monospaced and still
/// medium-weight, so the columns line up and the digits stay solid at arm's
/// length in daylight. The SUMMARY CARD is deliberately left at `data` — the
/// cruiser said it was fine, and the step between the two is now what says
/// which is the header. iOS: `FieldLogTable.valueFont`.
private val ForestixTypography.fieldLogValue: TextStyle get() = dataSmall

/// The row's second line — species, kinds, "2h ago". One step below the
/// values, so the numbers still lead the row after the shrink above.
/// `caption` (12 sp) is the existing scale's non-tabular small size, and none
/// of what this line carries is a measurement. iOS: `FieldLogTable.metaFont`.
private val ForestixTypography.fieldLogMeta: TextStyle get() = caption

/// The ONE definition of the field-log grid. The header and every row go
/// through it, so the columns cannot drift apart again.
@Composable
private fun FieldLogColumns(
    modifier: Modifier = Modifier,
    ordinalSlot: @Composable () -> Unit,
    treeSlot: @Composable () -> Unit,
    dbhSlot: @Composable () -> Unit,
    heightSlot: @Composable () -> Unit,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ColGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(ColOrdinalWeight), contentAlignment = Alignment.CenterEnd) { ordinalSlot() }
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
        // The ordinal column is headed by nothing. It is a display ordinal,
        // not a field of the record, so there is no name for it that would be
        // true — and a heading here would be one more user-visible string to
        // keep byte-identical across two platforms for a column whose meaning
        // is obvious the moment the rows below it read 1, 2, 3. The slot still
        // has to EXIST, or the header's TREE would sit over the rows'
        // ordinals.
        ordinalSlot = {},
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
@OptIn(ExperimentalFoundationApi::class)
private fun FieldLogRow(
    row: FieldLogRowModel,
    /// 1-based position in the list AS DRAWN. Handed in by the renderer, not
    /// read off the model — see [FieldLogWords.rowOrdinal]. It is display
    /// only: nothing here writes it, exports it, or joins on it.
    ordinal: Int,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /// The log is in move-selection mode. The tick box appears for every
    /// quick row while it is, so an UNticked row is visibly unticked rather
    /// than merely missing a mark — same rule as the cruise row beside it.
    selecting: Boolean = false,
    isSelected: Boolean = false,
) = ForestixDenseTextScale {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
    if (selecting) {
        Icon(
            if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (isSelected) colors.primary else colors.textTertiary,
            modifier = Modifier.size(20.dp))
    }
    Column(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FieldLogColumns(
            // The display ordinal, dimmed and in its own narrow column so it
            // cannot be misread as part of the tree's name.
            ordinalSlot = {
                Text(
                    FieldLogWords.rowOrdinal(ordinal),
                    style = type.fieldLogValue, color = colors.textTertiary,
                    textAlign = TextAlign.End, maxLines = 1, softWrap = false,
                    overflow = TextOverflow.Ellipsis)
            },
            treeSlot = {
                Text(
                    row.treeLabel ?: kindWord(row.entries.first().kind),
                    style = type.fieldLogValue, color = colors.textPrimary,
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
                        style = type.fieldLogValue, color = colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 3,
                        overflow = TextOverflow.Ellipsis)
                } else {
                    Text(
                        row.dbh?.let { MeasurementFormatter.diameter(it.value, unitSystem) } ?: "—",
                        style = type.fieldLogValue,
                        color = if (row.dbh == null) colors.textTertiary else colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            },
            heightSlot = {
                if (row.treeNumber != null) {
                    Text(
                        row.height?.let { MeasurementFormatter.height(it.value, unitSystem) } ?: "—",
                        style = type.fieldLogValue,
                        color = if (row.height == null) colors.textTertiary else colors.textPrimary,
                        textAlign = TextAlign.End, maxLines = 2,
                        overflow = TextOverflow.Ellipsis)
                }
            },
        )
        // SPECIES SITS UNDER THE TREE NAME. It used to be the first thing on
        // this line, which starts at the left edge of the row — so it was
        // drawn under the ORDINAL, in front of the tree it describes, and read
        // as a column of its own. Running the second line through the same
        // grid as the first puts it exactly under the name, and costs the row
        // no height: everything that was on this line still is.
        FieldLogColumns(
            ordinalSlot = {},
            treeSlot = {
                speciesName(row)?.let {
                    Text(it, style = type.fieldLogMeta, color = colors.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            // The kind words that used to be the TYPE column, kept here only
            // when a row carries something other than the two named columns
            // (a crown on the same tree). A loose row's kind is already what
            // the TREE column reads, so it is not repeated.
            dbhSlot = {
                if (row.treeNumber != null) {
                    val extras = row.entries
                        .filter { it.kind != MeasureKind.DBH && it.kind != MeasureKind.HEIGHT }
                        .map { kindWord(it.kind) }.distinct().sorted()
                    if (extras.isNotEmpty()) {
                        Text(
                            extras.joinToString(" "), style = type.fieldLogMeta,
                            color = colors.textTertiary, textAlign = TextAlign.End,
                            maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            heightSlot = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The time the row is SORTED on, so the order the cruiser
                    // reads is the order this column explains.
                    Text(
                        relativeAgo(row.measuredAt), style = type.fieldLogMeta,
                        color = colors.textTertiary, maxLines = 1, softWrap = false,
                        overflow = TextOverflow.Ellipsis)
                    // The standard "there is more behind this" affordance —
                    // the row is tappable and this says so. While selecting
                    // the click toggles instead of opening, so it goes.
                    if (!selecting) {
                        Icon(
                            Icons.Filled.ChevronRight, contentDescription = null,
                            tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    }
                }
            },
        )
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
    /// (kind, tree number, tree name, species code, ground truth, truth source)
    onRemeasure: (MeasureKind, Int, String?, String?, Double?, String?, String?) -> Unit,
    /// Where this row's id went after a move to another plot. A tree row's id
    /// carries its plot (see [fieldLogTreeRowId]), so a move from inside this
    /// sheet retires the id the host opened it with — and without this the
    /// host would answer a successful move with "every reading on this row
    /// has been deleted", which is exactly wrong.
    onRowMoved: (String) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val env = LocalAppEnvironment.current
    val plots by env.history.plots.collectAsStateWithLifecycle()
    val defaultPlotID = plots.firstOrNull { it.isDefault }?.id
    /// This one row, handed to the shared move flow. The single-tree case
    /// where entering a selection mode over the whole log is overkill — same
    /// picker, same confirmation, same report as the bulk move.
    var moveRequest by remember(row.id) { mutableStateOf<List<QuickMoveRow>?>(null) }
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
    /// WHICH reading the measured-time editor is working on (null = closed).
    ///
    /// One reading at a time, deliberately: the cruiser was offered a bulk
    /// offset and declined it — their notebook holds a per-tree time to the
    /// minute — and one act on one reading is also what keeps the provenance
    /// stamp simple. Held as an id, not a copy, so the editor writes to the
    /// reading as the store currently has it.
    var editingTimeOf by remember(row.id) { mutableStateOf<UUID?>(null) }
    /// Which measurement has its editor open under its row. null = both rows
    /// are readouts. One at a time: two open number fields on a phone push
    /// the Save that decides whether they land off the screen.
    var editingMeasure by remember(row.id) { mutableStateOf<MeasureKind?>(null) }
    /// Which measurement's tier explainer is open — the same sheet the cruise
    /// tree's confidence chip opens, so "Good / Fair / Check" is explained
    /// once for both kinds of tree. null = closed.
    var explaining by remember(row.id) { mutableStateOf<TierExplainerKind?>(null) }
    /// The time edit writes straight to the store rather than through the
    /// sheet's `onSave`, because the store's own `setMeasuredTime` is what
    /// re-checks the rule and stamps the provenance — routing it through a
    /// plain entry save would let a caller set a time without either.
    val writeScope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ForestixSpace.md)
            .padding(bottom = ForestixSpace.xl),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Text(row.title, style = type.title, color = colors.textPrimary)

        // Every fact this record carries, resolved once. A row is a TREE, and
        // these are facts about the stem rather than about one of its
        // readings.
        val species = row.entries.firstNotNullOfOrNull {
            it.speciesCode?.takeIf(String::isNotEmpty)
        }
        val damage = row.entries.flatMap { it.damageCodes }.distinct().sorted()
        val note = row.entries.mapNotNull { it.note?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString("\n").takeIf(String::isNotEmpty)
        // Read through the SAME null rule the rest of the app uses
        // (plotID ?: defaultPlotID) rather than a second one invented here. A
        // plot id that no plot answers to says so — it is a real state (the
        // plot was deleted out from under the reading) and quietly printing
        // the default plot's name would be a claim about where the reading is
        // filed that is not true.
        val homeID = row.entries.firstOrNull()?.plotID ?: defaultPlotID
        // The reading that gives the row its time: the moment the cruiser
        // arrived at this stem (see [FieldLogRowModel.measuredAt]).
        val earliest = row.entries.minByOrNull { it.createdAt }

        // MARK: 1 — Detail
        TreeFormSection(header = TreeFormWords.DETAIL) {
            // WHICH PLOT THIS IS IN, and the way to change it.
            //
            // The plot is the only grouping a quick reading has — it is what
            // separates a validation set from a bench test, and it reaches
            // every export. Tapping opens the same picker the bulk move uses.
            TreeFormRow(
                label = TreeFormWords.PLOT,
                // The em dash on this row means "still loading" and nothing
                // else. The quick store keeps a permanent default plot, so it
                // never legitimately answers with no plots at all — an empty
                // list is the bootstrap not being back yet. Once it is back, a
                // plot id nothing answers to is a plot that was deleted out
                // from under the reading, and the row says that instead of
                // shrugging. There is no third state here: the quick store
                // holds its plots in memory and has no failed read to report,
                // which is the one thing the cruise form can say and this one
                // cannot.
                value = if (plots.isEmpty()) {
                    null
                } else {
                    plots.firstOrNull { it.id == homeID }?.name
                        ?: FieldLogWords.UNKNOWN_PLOT
                },
                onTap = { moveRequest = listOf(quickMoveRow(row)) },
            )
            TreeFormSpeciesRows(
                code = species,
                onCode = { code ->
                    // Written onto EVERY reading on the row, on the same
                    // argument as the position: the cruiser is naming the
                    // TREE, and a species on the diameter but not on the
                    // height reads back as two different stems.
                    row.entries.forEach { onSave(it.copy(speciesCode = code)) }
                },
            )
            // MARKED when the reading that provides it was re-timed by hand:
            // this row is where a reader looks to see when work on the tree
            // began, and a hand-set time reaching it unlabelled would be
            // exactly the silent edit that marker exists to prevent.
            TreeFormRow(
                label = TreeFormWords.TIME,
                value = MeasuredTimeInput.Words.stamp(
                    MeasuredTimeInput.text(row.measuredAt),
                    earliest?.hasHandSetTime == true),
                onTap = { earliest?.let { editingTimeOf = it.id } },
            )
            TreeFormRow(label = TreeFormWords.TREE, value = row.treeLabel)
            // A quick reading records no tally status: it is a measurement,
            // not a stem on a cruise sheet.
            TreeFormRow(label = TreeFormWords.STATUS, value = null)
            TreeFormRow(
                label = TreeFormWords.DAMAGE,
                value = damage.takeIf { it.isNotEmpty() }?.joinToString(", "),
            )
            TreeFormRow(label = TreeFormWords.NOTES, value = note)
        }

        // MARK: 2 — Measurement. Tapping the VALUE is how a cruiser changes
        // it — the editor opens under the row it belongs to.
        TreeFormSection(header = TreeFormWords.MEASUREMENT) {
            listOf(MeasureKind.DBH, MeasureKind.HEIGHT).forEach { kind ->
                val isDbh = kind == MeasureKind.DBH
                val existing = if (isDbh) row.dbh else row.height
                TreeFormMeasurementRow(
                    label = if (isDbh) TreeFormWords.DBH else TreeFormWords.HEIGHT,
                    value = existing?.let { looseValue(it, unitSystem) },
                    // The measurement's own precision, read with the
                    // measurement rather than as a row of its own.
                    sigma = existing?.let { sigmaText(it, unitSystem) },
                    onTap = { editingMeasure = if (editingMeasure == kind) null else kind },
                )
                if (editingMeasure == kind) {
                    val tree = row.treeNumber
                    if (tree == null) {
                        // A crown, a distance or a sampling-plot record. There
                        // is no tree for a diameter to belong to, so there is
                        // nothing here to set.
                        Text(
                            "This reading isn't attached to a tree, so it has no " +
                                "diameter or height to set.",
                            style = type.caption, color = colors.textTertiary)
                    } else {
                        FieldLogEditSection(
                            kind = kind, row = row, tree = tree,
                            unitSystem = unitSystem, developerMode = developerMode,
                            onSave = onSave, onAdd = onAdd, onRemeasure = onRemeasure)
                    }
                }
                TreeFormConfidenceRow(
                    label = if (isDbh) TreeFormWords.DBH_CONFIDENCE
                    else TreeFormWords.HEIGHT_CONFIDENCE,
                    // NOT the grade a typed number inherited. `typedValue`
                    // clears the σ of a reading the cruiser overrode but keeps
                    // `confidenceRaw`, so without this the chip beside a
                    // hand-typed diameter is the last capture's Good/Fair/Check
                    // — a sensor's verdict on a number no sensor produced. A
                    // reading with no capture behind it has no grade, and the
                    // row says so with the em dash it uses for every ungraded
                    // measurement.
                    tier = existing?.takeIf { !it.isTypedReading }?.confidenceRaw,
                ) {
                    explaining = if (isDbh) TierExplainerKind.DIAMETER
                    else TierExplainerKind.HEIGHT
                }
            }
        }

        // MARK: 3 — Computed, from the measurements above.
        TreeFormComputedSection(
            dbhCm = row.dbh?.value?.toFloat(),
            heightM = row.height?.value?.toFloat(),
            speciesCode = species,
        )

        // Every reading behind this row, in the order they were taken —
        // including the re-measurements the two rows above supersede, which
        // is the record the cruise store has no equivalent of. Each one keeps
        // its own time, and the click re-times THAT reading.
        TreeFormSection(header = TreeFormWords.READINGS) {
            row.entries.forEach { e ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple(onClick = { editingTimeOf = e.id }),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(kindWord(e.kind), style = type.body, color = colors.textSecondary)
                        Spacer(Modifier.weight(1f))
                        Text(looseValue(e, unitSystem), style = type.data, color = colors.textPrimary)
                        Icon(
                            Icons.Filled.ChevronRight, contentDescription = null,
                            tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                    }
                    // The ± band the table used to carry. It is the
                    // measurement's own precision, so it belongs with the
                    // measurement rather than in a column being scaled to
                    // fit on a phone.
                    sigmaText(e, unitSystem)?.let {
                        Text(it, style = type.dataSmall, color = colors.textTertiary)
                    }
                    // WHEN IT WAS MEASURED, to the minute, in the cruiser's
                    // locale — and carrying the marker when that time was set
                    // by hand, so a desk-typed time can never read like a
                    // sensor-stamped one.
                    Text(
                        MeasuredTimeInput.Words.line(
                            MeasuredTimeInput.text(e.createdAt), e.hasHandSetTime),
                        style = type.dataSmall,
                        color = if (e.hasHandSetTime) colors.textSecondary else colors.textTertiary,
                    )
                }
            }
        }

        // WHERE AND HOW IT WAS RECORDED. The plot, the time, the species and
        // the note are up in DETAIL, where both kinds of tree carry them;
        // what is left here is what only a quick reading has — the fix it was
        // taken at, how it was captured, and the frame it was shot from.
        TreeFormSection(header = TreeFormWords.RECORDED) {
            val fix = row.entries.firstOrNull { it.latitude != null && it.longitude != null }
            // POSITION IS TAPPABLE. A fix that never arrived, or arrived on
            // the wrong stem, used to be permanent — the reading carried it
            // for the rest of the cruise and into every export. The row now
            // opens an editor; what it shows is unchanged.
            //
            // Said out loud rather than left blank: a reading with no fix is
            // a different thing from one whose fix was not shown.
            TreeFormRow(
                label = TreeFormWords.POSITION,
                value = fix?.let { CoordinateInput.text(it.latitude!!, it.longitude!!) }
                    ?: TreeFormWords.NO_POSITION,
                onTap = {
                    positionField = fix?.let {
                        CoordinateInput.text(it.latitude!!, it.longitude!!)
                    } ?: ""
                    positionRefusal = null
                    editingPosition = true
                },
            )
            // WHERE THE COORDINATE CAME FROM. Without this a coordinate the
            // cruiser typed reads on screen — and exports — exactly like one
            // the satellites produced.
            //
            // ALWAYS DRAWN, like every other row on both forms: a reading with
            // no fix has no source to name, and "—" says that. It used to be
            // `?.let`, which took the row off the sheet entirely and left the
            // cruise form and this one with different Recorded sections.
            TreeFormRow(
                label = TreeFormWords.POSITION_SOURCE,
                value = fix?.positionRecordedSource?.let(FieldLogWords::positionSourceText))
            // "typed" is its own answer. Folding it into "Automatic" told the
            // cruiser the sensors produced a number nobody ever pointed a
            // camera at. Absent on a reading recorded before the stamp
            // existed, which is the em dash's own case.
            TreeFormRow(
                label = TreeFormWords.CAPTURE,
                value = row.entries.firstNotNullOfOrNull { it.captureMode }
                    ?.let(TreeFormWords::captureText))
            // The row's photos in the order they were shot. The sheet still
            // shows the first one in place, exactly as it always has; the
            // rest are one tap away in the shared viewer, which is the only
            // thing on this screen that pages.
            photoPages.firstOrNull()?.let { first ->
                FieldLogPhoto(first.photoPath, photoPages.size) { showPhotos = true }
            }
        }

        // There is no second ground-truth section here any more. The truth
        // field inside the editor a measurement row opens is the only place a
        // truth is shown and the only place it is edited — see the note at the
        // foot of this file.
    }

    // Tier explainer — opened by tapping a confidence row.
    explaining?.let { kind ->
        TierExplainerSheet(kind) { explaining = null }
    }

    // The move raised by the Plot row. Same flow the log's selection bar
    // runs, given one row instead of a dozen.
    QuickMoveFlow(
        rows = moveRequest,
        onDismiss = { moveRequest = null },
        onFinished = { outcome ->
            // A tree row's id carries its plot, so a row that actually moved
            // is now a different row. Tell the host where it went; a row that
            // did NOT move (already there, refused, destination gone) keeps
            // the id it had, and the report says why.
            val number = row.treeNumber
            if (outcome.movedCount > 0 && number != null) {
                onRowMoved(fieldLogTreeRowId(outcome.destination.id, number))
            }
        },
    )

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

    // The measured-time editor, on ONE reading. The reading is looked up here
    // rather than captured when the click happened: the store can move under an
    // open dialog, and writing back a stale copy is how a correction lands on
    // the wrong reading.
    editingTimeOf?.let { id ->
        row.entries.firstOrNull { it.id == id }?.let { entry ->
            MeasuredTimeEditorDialog(
                epochMs = entry.createdAt,
                subject = kindWord(entry.kind),
                sourceText = MeasuredTimeInput.Words.sourceText(entry.timeRecordedSource),
                // WHICH KIND OF READING THIS IS decides what the cruiser is
                // told, and it is decided from the entry alone: a typed
                // reading has no capture behind it, so its stored time is
                // merely when it was typed.
                footer = if (entry.isTypedReading) {
                    MeasuredTimeInput.Words.TYPED_FOOTER
                } else {
                    MeasuredTimeInput.Words.SENSOR_FOOTER
                },
                onDismiss = { editingTimeOf = null },
                onSave = { picked ->
                    // The store re-checks the rule and stamps the provenance;
                    // nothing here can set a time without the stamp.
                    writeScope.launch { env.history.setMeasuredTime(id, picked) }
                    editingTimeOf = null
                },
            )
        }
    }
}

/// The measured-time editor: date and time to the minute, prefilled with the
/// value the record carries, Save off while the picked time cannot be stored.
///
/// The pickers are deliberately NOT clamped to the past. A cruiser who reaches
/// for tomorrow gets told why it cannot be stored, which is the requirement; a
/// control that silently refuses to move teaches nothing and reads as a bug.
///
/// It takes the INSTANT rather than a reading, because both kinds of tree set
/// a time here: a quick reading (which also carries where its time came from,
/// passed as [sourceText]) and a cruise tree (which does not). One editor, so
/// the rule about what a time may be, the rounding and the words are decided
/// in one place. [footer] is what the caller has to say about the record being
/// re-timed; it is the one sentence that differs between them.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeasuredTimeEditorDialog(
    epochMs: Long,
    subject: String,
    sourceText: String?,
    footer: String,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val opened = remember(epochMs) { Calendar.getInstance().apply { timeInMillis = epochMs } }
    // The date half, held as the UTC midnight the material picker speaks in.
    var dateUtcMs by remember(epochMs) { mutableStateOf(localDateAsUtcMillis(epochMs)) }
    var pickingDate by remember(epochMs) { mutableStateOf(false) }
    // The time half. `is24Hour` follows the phone's own clock setting so the
    // field matches the times printed everywhere else on this sheet.
    val timeState = rememberTimePickerState(
        initialHour = opened.get(Calendar.HOUR_OF_DAY),
        initialMinute = opened.get(Calendar.MINUTE),
        is24Hour = android.text.format.DateFormat.is24HourFormat(context),
    )
    val picked = combineLocal(dateUtcMs, timeState.hour, timeState.minute)
    val resolved = MeasuredTimeInput.resolve(picked)
    val refusal = (resolved as? MeasuredTimeInput.Result.Refused)?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(MeasuredTimeInput.Words.EDITOR_TITLE) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
            ) {
                Text(subject, style = type.caption, color = colors.textSecondary)
                // What is about to be stored, printed the way every other
                // surface prints it, so Save is never a surprise.
                Text(
                    MeasuredTimeInput.text(picked),
                    style = type.data, color = colors.textPrimary,
                )
                // What the record's time currently CLAIMS, in the same word
                // the `time_source` column exports, so the cruiser can see on
                // screen what an analyst will read in the CSV. A record with
                // no such column says nothing here rather than guessing.
                sourceText?.let { SheetRow("Time source", it) }
                // The date is behind a button and the time is inline, because
                // that is the shape Material gives us: there is no combined
                // control, and a full calendar unfolded inside this dialog
                // would push the sentence that explains the edit off-screen.
                // iOS shows one DatePicker carrying both halves.
                TextButton(onClick = { pickingDate = true }) { Text("Change date") }
                TimeInput(state = timeState)
                refusal?.let {
                    Text(it, style = type.caption, color = colors.confidenceBad)
                }
                // WHAT IS BEING RE-TIMED decides what the cruiser is told, and
                // the caller decides it (see [QuickMeasureEntry.isTypedReading]
                // for the quick side): a typed reading has no capture behind
                // it, so its stored time is merely when it was typed and the
                // notebook time is strictly better. A sensor reading has a
                // raw-capture bundle whose manifest holds the real capture
                // instant, and that manifest is NOT rewritten — so the two
                // records will disagree, and the cruiser is told so here,
                // before they commit, rather than discovering it in an export
                // months later.
                Text(footer, style = type.caption, color = colors.textTertiary)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (resolved is MeasuredTimeInput.Result.Time) onSave(resolved.epochMs)
                },
                enabled = refusal == null,
            ) { Text(MeasuredTimeInput.Words.SAVE) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(MeasuredTimeInput.Words.CANCEL) }
        },
        containerColor = colors.surface,
    )

    if (pickingDate) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = dateUtcMs)
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    // A cleared selection leaves the date it already had —
                    // never "today", which would silently re-date the reading.
                    dateState.selectedDateMillis?.let { dateUtcMs = it }
                    pickingDate = false
                }) { Text("Done") }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) {
                    Text(MeasuredTimeInput.Words.CANCEL)
                }
            },
        ) {
            DatePicker(state = dateState)
        }
    }
}

/// The UTC midnight that stands for a local date — what the material date
/// picker takes and returns. Converting through the calendar rather than by
/// arithmetic keeps a cruiser near a date boundary, or in a half-hour offset
/// zone, on the day they are actually looking at.
private fun localDateAsUtcMillis(epochMs: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = epochMs }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

/// The inverse: a picked UTC date plus a picked hour and minute, read back as a
/// local instant. Seconds and milliseconds are cleared here as well as in
/// [MeasuredTimeInput.truncatedToMinute] — the cruiser records to the minute,
/// and a second nobody typed has no business in the stored time.
private fun combineLocal(dateUtcMs: Long, hour: Int, minute: Int): Long {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = dateUtcMs }
    return Calendar.getInstance().apply {
        clear()
        set(
            utc.get(Calendar.YEAR), utc.get(Calendar.MONTH), utc.get(Calendar.DAY_OF_MONTH),
            hour, minute, 0,
        )
    }.timeInMillis
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
/// never read gets the same editor, empty, so it can be completed from here
/// instead of staying half-measured forever.
///
/// It draws BARE, with no header and no card of its own: it opens inside the
/// Measurement section, directly under the row whose value it sets, and a
/// second card nested in that one would read as a second screen.
@Composable
private fun FieldLogEditSection(
    kind: MeasureKind,
    row: FieldLogRowModel,
    tree: Int,
    unitSystem: UnitSystem,
    developerMode: Boolean,
    onSave: (QuickMeasureEntry) -> Unit,
    onAdd: (QuickMeasureEntry) -> Unit,
    onRemeasure: (MeasureKind, Int, String?, String?, Double?, String?, String?) -> Unit,
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
    // The last ground-truth recovery run's leftovers for THIS tree and kind.
    // A small JSON file, read once per section, not the manifest tree.
    val context = LocalContext.current
    val stranded = remember(row.id, kind) {
        if (developerMode) {
            TruthBackfillReport.load(context)?.stranded(kind, tree) ?: emptyList()
        } else {
            emptyList()
        }
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

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
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
            // Truths typed for a raw capture of this tree that the recovery
            // pass could not match to any reading.
            //
            // The old read-only "GROUND TRUTH" section is NOT coming back;
            // that surface showed the manifest value beside the reading's and
            // nothing said which one the export read. This says only what the
            // sheet cannot otherwise show: a number exists, it is in the ZIP,
            // and it is on no reading. Read from the report the recovery pass
            // leaves behind rather than from disk, so opening a tree never
            // costs ~300 manifest reads. Rendered in the truth field's own
            // unit, so the sentence and the field cannot disagree.
            for (s in stranded) {
                Text(
                    TruthBackfill.strandedLine(TruthInput.text(s.value, truthUnit)),
                    style = type.caption, color = colors.textTertiary)
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
                    var next =
                        if (abs(value - existing.value) > FIELD_LOG_VALUE_EPSILON)
                            existing.typedValue(value)
                        else existing
                    // Only RESTAMP the truth when the number itself moved.
                    // Saving a corrected diameter used to rewrite the truth
                    // too, which would now relabel a truth recovered from a
                    // raw capture as one typed here — a provenance claim the
                    // cruiser never made. An unchanged truth keeps whatever
                    // source it already carries.
                    if (developerMode && truthChanged(truthTyped, existing.truth)) {
                        next = next.settingTruth(truth)
                    }
                    onSave(next)
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
                // The tape value AND how it got onto the reading being
                // superseded — a truth recovered from a raw capture must not
                // come back stamped as one typed on the scan screen, and one
                // already re-based by TruthUnitRepair must not come back
                // without the unit that says so.
                existing?.truth, existing?.truthSource, existing?.truthUnit)
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
    /// The last species seen in the log, offered the same way the name is. It
    /// is the app's guess, never an observation — the sheet draws it dim until
    /// the cruiser picks in the control. Null when nothing in the log has a
    /// species, and then the picker opens unset.
    val suggestedSpecies: String?,
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
    var speciesCode by remember(request.id) { mutableStateOf(request.suggestedSpecies) }
    // False while `speciesCode` is only the log's carry-over, true once the
    // cruiser has picked. Styling only — the code is stored either way, on the
    // same argument as the measure chooser's.
    var speciesConfirmed by remember(request.id) { mutableStateOf(false) }
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
                    placeholder = { Text("e.g. Tree1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The same control the chooser and the details sheet use — one
                // species list, one typed-code escape, no second copy to drift.
                SpeciesPickerField(
                    speciesCode = speciesCode,
                    onSpeciesCode = {
                        speciesCode = it
                        // Any pick makes it definite, including re-picking the
                        // code already showing — that IS the confirmation.
                        speciesConfirmed = true
                    },
                    unspecifiedLabel = "Species",
                    bordered = true,
                    provisional = !speciesConfirmed,
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
//
// SINCE THEN. Hiding those rows turned out to hide the cruiser's own data: the
// tape values from the field days before the truth moved onto the reading are
// exactly the "manifest and no reading" class, and a blank True field is
// indistinguishable on screen from a tree nobody taped. They are recovered now
// rather than re-displayed — `TruthBackfill` attaches a manifest truth to the
// reading it belongs to (same kind, same tree, unambiguous plot, nearest
// timestamp) and stamps it `truth_source = capture`, so the value lives where
// every export already reads it and the analysis can still tell a matched
// truth from a typed one.
//
// What remains DISPLAY-ONLY is the residue: a manifest truth that matched no
// reading at all, because its reading was deleted or its tree number is
// ambiguous across plots. One quiet line in the section names that number and
// says it is in the ZIP. It is still not written back, and still not exported
// as a reading's truth, for the original reason — a value keyed on a tree
// number alone cannot say which reading it was taped for, and inventing one
// would put a number into the corpus that no measurement stands behind.
