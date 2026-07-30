// Move already-captured QUICK measurements into another quick-measure plot,
// in bulk or one at a time. Kotlin sibling of iOS Screens/QuickMove.swift:
// same rules, same user-visible strings, byte for byte.
//
// WHY THIS EXISTS. The cruiser's own words: "그냥 그룹핑을 하고싶었던거임" — some
// of their readings are validation data and some are throwaway bench tests,
// and they want to separate them after the fact. The grouping mechanism
// already existed: [QuickMeasurePlot] has a name, every [QuickMeasureEntry]
// carries a plotID into one, the field log can be filtered to a single plot
// and the plot's name reaches every export. What did NOT exist was any way
// to change a reading's plot after it was recorded. The active plot was
// chosen BEFORE a measurement and could never be revisited, so a reading
// filed wrong was filed wrong forever. This is the missing move.
//
// This is NOT the cruise move (TreeMove.kt) and it is not a conversion into
// one. A quick reading has no project and cannot be given one — see the note
// at the foot of TreeMove.kt, which still holds. What a quick reading HAS is
// a plot, and that is what this changes.
//
// WHAT A MOVE REWRITES, AND WHAT IT DOES NOT.
//
//   Rewritten: plotID. That is the whole change, and it is the only field
//   [QuickMeasureEntry.inPlot] touches.
//
//   Untouched: the value, sigma, confidence, method, capture mode, ground
//   truth and its source, the coordinate and ITS source, the photo, the
//   species, the stem position, the damage codes, the note, the timestamp,
//   the tree number and the cruiser's name for the stem. None of those is a
//   statement about a plot; they are statements about a reading, and they
//   stay true wherever it is filed. NOTHING HERE TOUCHES A MEASUREMENT.
//
//   Also untouched: the ACTIVE plot. Moving old readings and choosing where
//   the next measurement lands are different acts, and a move that silently
//   re-pointed the next scan would be the same defect this feature exists to
//   fix, in the other direction. The confirmation says so before anything is
//   written, and a plot created from the picker here is created inactive.
//
// A WHOLE ROW MOVES, OR NONE OF IT. A field-log row is a TREE — it is keyed
// on (plot, tree number) and carries every reading recorded against that
// stem, which for a Full measurement is a diameter AND a height (and often a
// crown). Moving a row therefore moves all of that tree's readings together;
// moving one of them would leave a stem's diameter and height in different
// groups, which is precisely the confusion the cruiser is trying to end.
//
// A TREE NUMBER ALREADY USED IN THE DESTINATION STOPS THAT ROW. The log
// groups by (plot, tree number), so landing a tree #7 in a plot that already
// has a #7 would MERGE two stems into one row carrying two diameters. The
// cruise move renumbers instead, because cruise numbering restarts per plot
// and the number is the plot's own; a quick tree number is drawn from
// max(all) + 1 across the whole log and is the cruiser's own label for the
// stem, printed on their tally sheet. Renaming their data to make room is
// not this feature's business, so the row is refused and NAMED — see
// [QuickMoveWords.numberTaken].

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.QuickMeasureHistory
import com.hcjeong.forestix.data.QuickMeasurePlot
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

// MARK: - Words (byte-identical with the iOS sibling) ---------------------

/// Every user-visible string this feature owns that the CRUISE move does not
/// already own. Where the two moves say the same thing they share the same
/// constant — the count, the Cancel, the confirmation title, the "already
/// there" sentence and the whole failure report come from [TreeMoveWords], so
/// the two selection modes on one list cannot drift into two dialects.
object QuickMoveWords {

    // Selection mode
    /// The cruise bar says "Move to project…" because a cruise tree is filed
    /// under one. A quick reading is filed under a plot and nothing else.
    const val MOVE_BUTTON = "Move to plot…"

    /// Discoverability for the long press, shown under the list.
    const val HINT = "Press and hold a quick measurement to move it to another plot."

    // Destination picker
    const val PICK_TITLE = "Move to plot"

    /// The whole point of the feature in two sentences, where the cruiser is
    /// deciding. It names the two plots they said they wanted, because
    /// "make a plot first, somewhere else, then come back" is the detour that
    /// makes a feature go unused.
    const val PICK_CAPTION =
        "A quick measurement is filed under a quick-measure plot, and that is the only" +
            " grouping it has. Name one plot for the readings you are validating and another" +
            " for the ones you are only bench-testing, and they stay apart everywhere the" +
            " plot name goes — the log's filter, the summary card and every export."

    /// Header over the create-a-plot field.
    const val NEW_PLOT_HEADER = "New plot"
    const val NEW_PLOT_PLACEHOLDER = "Plot name"
    const val NEW_PLOT_ACTION = "Create and move here"

    /// A name is genuinely all that is needed — acreage, type and BAF are
    /// optional on [QuickMeasurePlot] and stay unset rather than invented.
    const val NEW_PLOT_FOOTER =
        "A name is all a quick-measure plot needs. Area, type and BAF stay unset until you" +
            " fill them in, and the plot you are saving new measurements into does not change."

    const val NEW_PLOT_NEEDS_NAME = "Type a name for the new plot."

    /// Two plots with one name would defeat the split the cruiser is making,
    /// and neither the log nor the export could tell them apart.
    fun newPlotNameTaken(name: String): String =
        "A plot called \"$name\" already exists — pick it in the list above."

    /// How many readings are already filed under a destination, so the
    /// cruiser can tell "Validation" from an empty plot they made by mistake.
    fun readingCount(n: Int): String = if (n == 1) "1 reading" else "$n readings"

    /// A log with exactly one plot has nowhere to move anything TO. Said,
    /// rather than shown as an empty list.
    const val NO_OTHER_PLOTS =
        "This is the only quick-measure plot on the device. Make another one below and the" +
            " readings you picked will move straight into it."

    // Confirmation
    /// What is about to happen, named in full. Deliberately does NOT say
    /// "this can't be undone": unlike the cruise move, which clears the
    /// bearing and distance a tree had from the centre it is leaving, this
    /// changes one field and loses nothing, so the move back is the same
    /// three taps. Saying otherwise would be a threat the data does not
    /// support.
    fun confirmMessage(
        destination: String,
        movers: Int,
        readings: Int,
        alreadyThere: Int,
    ): String {
        val text = StringBuilder(
            if (movers == 1) {
                "1 tree moves to $destination."
            } else {
                "$movers trees move to $destination."
            },
        )
        text.append(
            if (readings == 1) {
                " Its 1 reading moves with it."
            } else {
                " All $readings readings on them move together, so no stem ends up with its" +
                    " diameter in one plot and its height in another."
            },
        )
        text.append(
            " The measurements themselves do not change: value, ± band, method, ground" +
                " truth, photo and coordinate are all left exactly as they were recorded.",
        )
        if (alreadyThere > 0) {
            text.append(
                if (alreadyThere == 1) {
                    " 1 tree you picked is already there and stays as it is."
                } else {
                    " $alreadyThere trees you picked are already there and stay as they are."
                },
            )
        }
        text.append(" New measurements keep saving into the plot you have open now.")
        text.append(" You can move them back the same way.")
        return text.toString()
    }

    // Per-row failure reasons (each follows "• <tree> — ")
    /// Every reading behind the row went away between the list being drawn
    /// and Move being tapped.
    const val ROW_GONE = "no longer in this device's log"

    /// The destination already holds that tree number — see the file header
    /// for why this refuses instead of renumbering.
    fun numberTaken(destination: String, number: Int): String =
        "$destination already has a tree #$number, and merging them would put two stems" +
            " on one row"

    /// The destination plot itself went away — every picked row fails, and
    /// the report names it once per row rather than claiming a partial move.
    fun destinationGone(name: String): String = "$name is no longer in this device's log"

    // Detail sheet
    /// The row label. A reading's plot IS its grouping, so the record sheet
    /// has to say which one it is in before the cruiser can tell what needs
    /// fixing.
    const val PLOT_ROW = "Plot"
}

// MARK: - What is being moved ---------------------------------------------

/// One field-log row, flattened to exactly what a move needs to know about
/// it. Built from a [FieldLogRowModel], which is where the (plot, tree)
/// grouping is decided — this must never re-derive that grouping itself.
data class QuickMoveRow(
    /// The [FieldLogRowModel.id] this came from. Also what the detail sheet
    /// tracks the row by.
    val id: String,
    /// How the row is named in a failure line — the same title the sheet and
    /// the delete confirmation use.
    val label: String,
    /// Null for a loose reading (a sampling-plot record, a standalone crown
    /// or distance). Those never collide, because the log gives each its own
    /// row keyed on the reading id.
    val treeNumber: Int?,
    /// Every reading behind the row. All of them move, or none do.
    val entryIDs: List<UUID>,
    /// The plot the row is in now, RAW — null for a reading written before
    /// plots existed. Normalised through the default plot exactly where the
    /// rest of the app normalises it, never here.
    val plotID: UUID?,
)

/// The row as the field log built it.
fun quickMoveRow(row: FieldLogRowModel): QuickMoveRow = QuickMoveRow(
    id = row.id,
    label = row.title,
    treeNumber = row.treeNumber,
    entryIDs = row.entries.map { it.id },
    plotID = row.entries.firstOrNull()?.plotID,
)

/// One row that did not move, and why — never folded into a count.
data class QuickMoveFailure(val id: String, val label: String, val reason: String) {
    /// The line the failure dialog prints.
    val line: String get() = "$label — $reason"
}

/// Everything the confirmation has to be able to say, worked out BEFORE a
/// single reading is written.
data class QuickMovePlan(
    val destination: QuickMeasurePlot,
    /// The rows that will actually be written, in the order they appear on
    /// screen. Rows already in the destination are NOT here.
    val movers: List<QuickMoveRow>,
    /// How many individual readings those rows carry between them.
    val readingCount: Int,
    /// Picked rows already in the destination. Left alone.
    val alreadyThere: Int,
    /// Rows that cannot move, each with its own reason. Carried into the
    /// outcome so the report is the same whether the row was refused before
    /// the write or during it.
    val failures: List<QuickMoveFailure>,
) {
    /// Everything the cruiser picked, however it ends up being handled.
    val selectedCount: Int get() = movers.size + alreadyThere + failures.size
    val hasWork: Boolean get() = movers.isNotEmpty()
}

/// What actually happened in the store.
data class QuickMoveOutcome(
    val destination: QuickMeasurePlot,
    val movedCount: Int,
    val failures: List<QuickMoveFailure>,
    val alreadyThere: Int,
) {
    /// Every row the cruiser picked, so the report can say "9 of 12" rather
    /// than "9 of the 9 we tried".
    val attempted: Int get() = movedCount + failures.size + alreadyThere
    val isClean: Boolean get() = failures.isEmpty()
}

// MARK: - The move itself -------------------------------------------------

/// Re-homes quick readings. Read the file header first — what this moves,
/// what it leaves alone and what it refuses is the whole design.
object QuickMover {

    /// Works out the move without writing anything.
    ///
    /// Does not throw: the quick store is already in memory, so there is no
    /// read that can fail the way the cruise planner's destination read can.
    /// Everything that can go wrong with a row is a row-level failure, and
    /// every one of them is named.
    fun plan(
        rows: List<QuickMoveRow>,
        destination: QuickMeasurePlot,
        history: QuickMeasureHistory,
    ): QuickMovePlan {
        // The one normalisation rule, read from where the rest of the app
        // reads it: an entry with no plotID lives in the default plot. Older
        // entries genuinely hold null, and the bootstrap that rewrites them
        // only runs at launch.
        val fallback = history.defaultPlotID()
        val entries: List<QuickMeasureEntry> = history.entries.value
        val live = entries.map { it.id }.toSet()
        // Tree numbers ALREADY in the destination. Rows that arrive during
        // this same move add to it as they are accepted, so two picked rows
        // both numbered 7 cannot merge into each other either.
        val used = entries
            .filter { (it.plotID ?: fallback) == destination.id }
            .mapNotNull { it.treeNumber }
            .toMutableSet()

        val movers = mutableListOf<QuickMoveRow>()
        val failures = mutableListOf<QuickMoveFailure>()
        var alreadyThere = 0
        var readings = 0

        for (row in rows) {
            // A row whose readings were all deleted while the selection was
            // up. Named, not silently dropped from the count.
            val surviving = row.entryIDs.filter { it in live }
            if (surviving.isEmpty()) {
                failures += QuickMoveFailure(row.id, row.label, QuickMoveWords.ROW_GONE)
                continue
            }
            if ((row.plotID ?: fallback) == destination.id) {
                alreadyThere++
                continue
            }
            val number = row.treeNumber
            if (number != null && used.contains(number)) {
                failures += QuickMoveFailure(
                    row.id, row.label,
                    QuickMoveWords.numberTaken(destination.name, number),
                )
                continue
            }
            if (number != null) used += number
            movers += row.copy(entryIDs = surviving)
            readings += surviving.size
        }

        return QuickMovePlan(destination, movers, readings, alreadyThere, failures)
    }

    /// Writes the plan in ONE statement.
    ///
    /// The whole set of readings is handed to the store together, so there is
    /// no window in which half a tree has landed.
    suspend fun apply(plan: QuickMovePlan, history: QuickMeasureHistory): QuickMoveOutcome {
        val failures = plan.failures.toMutableList()

        // The destination itself can have been deleted while the
        // confirmation was up. NOTHING is written, and every row that was
        // going to move is named with that reason — a "nothing was moved"
        // that names no rows would leave the cruiser guessing which ones.
        if (history.plot(plan.destination.id) == null) {
            plan.movers.forEach { row ->
                failures += QuickMoveFailure(
                    row.id, row.label,
                    QuickMoveWords.destinationGone(plan.destination.name),
                )
            }
            return QuickMoveOutcome(plan.destination, 0, failures, plan.alreadyThere)
        }

        val live = history.entries.value.map { it.id }.toSet()
        val ids = mutableSetOf<UUID>()
        var moved = 0
        for (row in plan.movers) {
            // Re-checked at the write, not trusted from the plan: the
            // guarantee that a row lands whole has to hold where the writing
            // happens, the same way `backfillTruths` re-checks its refusal.
            val surviving = row.entryIDs.filter { it in live }
            if (surviving.isEmpty()) {
                failures += QuickMoveFailure(row.id, row.label, QuickMoveWords.ROW_GONE)
                continue
            }
            ids += surviving
            moved++
        }
        history.moveEntries(ids, plan.destination.id)

        return QuickMoveOutcome(plan.destination, moved, failures, plan.alreadyThere)
    }
}

// MARK: - Destination picker ----------------------------------------------

/// Pick the quick-measure plot a selection lands in, or name a new one.
///
/// Creating is HERE rather than behind a trip to the plot screen: the first
/// thing the cruiser will do is make "Validation" and "Bench test", and
/// sending them somewhere else to do it first is the detour that makes a
/// feature go unused. A quick plot needs nothing but a name — acreage, type
/// and BAF are all optional — so nothing is invented by creating one here.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickMovePicker(
    plots: List<QuickMeasurePlot>,
    /// plot id -> how many readings are filed under it, normalised through
    /// the default plot the same way everything else reads plotID.
    readingCounts: Map<UUID, Int>,
    /// Plots the current selection is already entirely inside — shown, but
    /// not offered, so "move to where it already is" is not a tap away.
    currentPlotIDs: Set<UUID>,
    onPick: (QuickMeasurePlot) -> Unit,
    /// Creates a plot with this name and moves into it. The picker validates
    /// the name; the host owns the store.
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Forestix.colors
    var newName by remember { mutableStateOf("") }
    var refusal by remember { mutableStateOf<String?>(null) }

    /// Destinations worth offering: everything the selection is not already
    /// wholly inside.
    val offered = plots.filter { it.id !in currentPlotIDs || currentPlotIDs.size > 1 }

    /// Validates the typed name and hands it over. A blank name and a name
    /// already in use are both REFUSED on screen rather than quietly
    /// producing an unnamed plot or a second "Validation" nothing downstream
    /// could tell from the first.
    fun create() {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            refusal = QuickMoveWords.NEW_PLOT_NEEDS_NAME
            return
        }
        val clash = plots.firstOrNull {
            it.name.lowercase(Locale.US) == trimmed.lowercase(Locale.US)
        }
        if (clash != null) {
            refusal = QuickMoveWords.newPlotNameTaken(clash.name)
            return
        }
        refusal = null
        onCreate(trimmed)
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
                .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        ) {
            Text(
                QuickMoveWords.PICK_TITLE,
                style = Forestix.type.bodyBold, color = colors.textPrimary)
            Text(
                QuickMoveWords.PICK_CAPTION,
                style = Forestix.type.caption, color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = ForestixSpace.sm))

            if (offered.isEmpty()) {
                Text(
                    QuickMoveWords.NO_OTHER_PLOTS,
                    style = Forestix.type.caption, color = colors.textSecondary)
            }

            offered.forEach { plot ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { onPick(plot) }
                        .padding(top = ForestixSpace.sm, bottom = ForestixSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                ) {
                    Text(
                        plot.name,
                        style = Forestix.type.body, color = colors.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    Text(
                        QuickMoveWords.readingCount(readingCounts[plot.id] ?: 0),
                        style = Forestix.type.caption, color = colors.textTertiary)
                }
            }

            Text(
                QuickMoveWords.NEW_PLOT_HEADER,
                style = Forestix.type.sectionHead, color = colors.textTertiary,
                modifier = Modifier.padding(top = ForestixSpace.md, bottom = 4.dp))
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it; refusal = null },
                placeholder = { Text(QuickMoveWords.NEW_PLOT_PLACEHOLDER) },
                singleLine = true,
                isError = refusal != null,
                modifier = Modifier.fillMaxWidth())
            refusal?.let {
                Text(
                    it, style = Forestix.type.caption, color = colors.confidenceBad,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Text(
                QuickMoveWords.NEW_PLOT_FOOTER,
                style = Forestix.type.caption, color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { create() }) {
                Text(QuickMoveWords.NEW_PLOT_ACTION, color = colors.primary)
            }
            Spacer(Modifier.size(ForestixSpace.lg))
        }
    }
}

// MARK: - The whole flow, as one composable -------------------------------

/// Picker -> confirmation -> write -> report, for any surface that can raise
/// a set of rows to move.
///
/// It is one composable rather than code in the field log because TWO
/// surfaces start this move — the log's selection bar and the record sheet's
/// Plot row, which is the single-tree case where entering a selection mode is
/// overkill — and a second copy of the confirmation is how the two would come
/// to say different things about what a move does.
///
/// Compose it UNCONDITIONALLY: [rows] going null is a way out of the picker,
/// not a reason to dispose the report that a finished move still has to show.
@Composable
fun QuickMoveFlow(
    /// Non-null arms the flow: these are the rows the cruiser picked.
    rows: List<QuickMoveRow>?,
    /// Every way out of the picker and the confirmation calls this, so the
    /// host can set [rows] back to null.
    onDismiss: () -> Unit,
    /// Called after a move that actually wrote something, with what happened.
    onFinished: (QuickMoveOutcome) -> Unit,
) {
    val env = LocalAppEnvironment.current
    val colors = Forestix.colors
    val scope = rememberCoroutineScope()
    val plots by env.history.plots.collectAsStateWithLifecycle()
    val entries by env.history.entries.collectAsStateWithLifecycle()

    /// The worked-out move, held while the confirmation is up. Nothing has
    /// been written at this point.
    var pendingPlan by remember { mutableStateOf<QuickMovePlan?>(null) }
    /// A finished move that did NOT fully succeed. A clean move reports
    /// itself by the rows appearing under their new heading.
    var outcome by remember { mutableStateOf<QuickMoveOutcome?>(null) }
    /// Every picked row was already in the chosen plot.
    var nothingToMove by remember { mutableStateOf<String?>(null) }

    /// Destination picked. Plans the move and raises the confirmation.
    /// NOTHING is written here.
    fun choose(destination: QuickMeasurePlot) {
        val plan = QuickMover.plan(rows.orEmpty(), destination, env.history)
        when {
            plan.hasWork -> pendingPlan = plan
            plan.failures.isEmpty() -> {
                // Everything picked is already there. Say so rather than show
                // a confirmation for a move of nothing.
                onDismiss()
                nothingToMove = TreeMoveWords.nothingToMove(destination.name)
            }
            else -> {
                // Nothing can move and it is not because it is already there
                // — report the reasons instead of a confirmation.
                onDismiss()
                outcome = QuickMoveOutcome(destination, 0, plan.failures, plan.alreadyThere)
            }
        }
    }

    if (rows != null && pendingPlan == null) {
        val fallback = plots.firstOrNull { it.isDefault }?.id
        val counts = entries
            .mapNotNull { it.plotID ?: fallback }
            .groupingBy { it }
            .eachCount()
        val homes = rows.mapNotNull { it.plotID ?: fallback }.toSet()
        QuickMovePicker(
            plots = plots,
            readingCounts = counts,
            currentPlotIDs = homes,
            onPick = { choose(it) },
            onCreate = { name ->
                // Created INACTIVE: making a destination must not re-point
                // where the next measurement lands. See the file header.
                choose(env.history.createPlot(name = name, makeActive = false))
            },
            onDismiss = onDismiss)
    }

    // Names what will move and where BEFORE anything is written.
    pendingPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingPlan = null; onDismiss() },
            title = { Text(TreeMoveWords.CONFIRM_TITLE) },
            text = {
                Text(
                    QuickMoveWords.confirmMessage(
                        destination = plan.destination.name,
                        movers = plan.movers.size,
                        readings = plan.readingCount,
                        alreadyThere = plan.alreadyThere))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingPlan = null
                    onDismiss()
                    scope.launch {
                        val result = QuickMover.apply(plan, env.history)
                        onFinished(result)
                        if (!result.isClean) outcome = result
                    }
                }) { Text(TreeMoveWords.confirmAction(plan.movers.size)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPlan = null; onDismiss() }) {
                    Text(TreeMoveWords.CANCEL)
                }
            },
            containerColor = colors.surface,
        )
    }

    // A PARTIAL move is reported as a partial move, naming every row that
    // stayed put and why. Nothing here ever says "done" over rows that did
    // not land.
    outcome?.let { result ->
        AlertDialog(
            onDismissRequest = { outcome = null },
            title = { Text(TreeMoveWords.FAILURE_TITLE) },
            text = {
                Text(
                    TreeMoveWords.failureMessage(
                        moved = result.movedCount,
                        total = result.attempted,
                        destination = result.destination.name,
                        failures = result.failures.map { it.line }))
            },
            confirmButton = {
                TextButton(onClick = { outcome = null }) { Text("OK") }
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
}
