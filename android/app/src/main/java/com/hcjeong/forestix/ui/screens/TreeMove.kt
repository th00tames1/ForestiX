// Re-file already-captured trees under another project, in bulk.
// Kotlin sibling of iOS Screens/TreeMove.swift: same rules, same
// user-visible strings, byte for byte.
//
// WHAT "ASSIGN A PROJECT" CAN MEAN, WORLD BY WORLD. The two stores answer
// this differently, and only one of them can answer it safely at all:
//
//   • CRUISE (Project -> Plot -> Tree, the Room cruise database). A cruise
//     tree ALREADY has a project; it has it through its plot. There is no
//     project column on Tree to set. So "move it to another project" can
//     only mean RE-PARENT IT TO A PLOT IN THAT PROJECT, and the cruiser
//     therefore has to pick a PLOT, not a project — a project alone names
//     nowhere for the tree to land. That is what this file does.
//
//   • QUICK (QuickMeasurePlot -> QuickMeasureEntry, the project-less
//     history). There is no project column anywhere in that store either,
//     and no plot with a project above it. Filing a quick reading under a
//     project could only mean CONVERTING it into a cruise Tree, and this
//     app does not do that. See [TreeMoveWords.QUICK_HAS_NO_PROJECT_BODY]
//     for the reason stated to the cruiser, and the note at the foot of
//     this file for the schema facts behind it.
//
// WHAT A MOVE REWRITES, AND WHAT IT DOES NOT.
//
//   Kept, untouched: diameter, height, every sigma, species, status,
//   damage, notes, photo, raw scan path, the tree's own latitude/longitude,
//   and the cruiser's name for the stem. None of this is a statement about
//   a plot; it is a statement about a tree, and it stays true wherever the
//   tree is filed. NOTHING HERE TOUCHES A MEASUREMENT.
//
//   Cleared: bearingFromCenterDeg, distanceFromCenterM, boundaryCall. All
//   three are measured FROM A PLOT CENTRE, and the tree is leaving that
//   centre. Carrying them across would put the tree at that bearing and
//   distance from a different point — the plot mini-map draws the stem from
//   exactly this pair — so a stale pair does not read as stale, it reads as
//   a tree standing somewhere it never stood. Both fields are already
//   nullable and every surface already handles null, so "we do not know
//   where this tree sat in its new plot" is a state the app can say. The
//   confirmation says it before anything moves.
//
//   Possibly rewritten: treeNumber. Cruise numbering restarts on each plot,
//   so a tree #3 moving into a plot that already has a #3 must take a free
//   number or the destination ends up with two trees wearing one number,
//   which nothing downstream can tell apart. The free number is the plot's
//   own next one — max(live numbers) + 1, the same rule the tally loop
//   uses. This is disclosed in the confirmation, counted, and never silent.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.displayTitle
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.UUID

// MARK: - Words (byte-identical with the iOS sibling) ---------------------

/// Every user-visible string this feature owns, in one place, so the two
/// platforms cannot drift. iOS holds the identical strings in
/// `TreeMoveWords`.
object TreeMoveWords {

    // Selection mode
    /// Always on screen while selecting — the cruiser must never have to
    /// count ticked rows themselves.
    fun selectionCount(n: Int): String =
        if (n == 1) "1 tree selected" else "$n trees selected"

    const val MOVE_BUTTON = "Move to project…"
    /// The way out of selection mode.
    const val CANCEL = "Cancel"
    /// Discoverability for the long press, shown under the list.
    const val HINT = "Press and hold a cruise tree to file it under another project."

    // Destination picker
    const val PICK_TITLE = "Move to"
    /// Says why a PLOT is being asked for when the cruiser asked for a
    /// project. This is the whole shape of the feature in one sentence.
    const val PICK_CAPTION =
        "A cruise tree belongs to a project through its plot, so pick the plot it should land in."
    /// A project with nowhere to put a tree. Not offered — and said, rather
    /// than the project simply being absent from the list.
    const val NO_PLOTS = "No plots — start one on the map first"
    /// Marks a destination plot that has already been closed.
    const val CLOSED_PLOT = "Closed"
    /// The cruise store holds no projects at all.
    const val NO_PROJECTS =
        "There are no cruise projects on this device to move trees into."

    // Confirmation
    const val CONFIRM_TITLE = "Move these trees?"

    fun confirmAction(n: Int): String = if (n == 1) "Move 1 tree" else "Move $n trees"

    /// What is about to happen, named in full, because this rewrites which
    /// project and which plot a piece of field data was recorded in.
    fun confirmMessage(
        destination: String,
        movers: Int,
        alreadyThere: Int,
        renumbered: Int,
        destinationClosed: Boolean,
    ): String {
        val text = StringBuilder(
            if (movers == 1) {
                "1 tree moves to $destination."
            } else {
                "$movers trees move to $destination."
            },
        )
        text.append(" Diameter, height, species, photos and notes are unchanged.")
        if (renumbered > 0) {
            text.append(
                if (renumbered == 1) {
                    " 1 of them is renumbered, because its tree number is already used there."
                } else {
                    " $renumbered of them are renumbered, because their tree numbers are" +
                        " already used there."
                },
            )
        }
        text.append(
            " Where each tree sat inside its old plot — bearing, distance from centre and" +
                " boundary call — is cleared, because it was measured from a centre the tree" +
                " is leaving.",
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
        if (destinationClosed) {
            text.append(
                " $destination is closed — its summary and height curve were worked out" +
                    " without these trees.",
            )
        }
        text.append(" This can't be undone.")
        return text.toString()
    }

    /// Every picked tree is already in the chosen plot. Nothing is written,
    /// and the cruiser is told that rather than shown a confirmation for a
    /// move of nothing.
    fun nothingToMove(destination: String): String =
        "Every tree you picked is already in $destination, so there is nothing to move."

    // Results
    const val FAILURE_TITLE = "Some trees did not move"
    const val PLAN_FAILED_TITLE = "Nothing was moved"

    /// A PARTIAL move, reported as a partial move. [moved] of [total] went;
    /// the rest are named with the reason each one stopped.
    fun failureMessage(
        moved: Int,
        total: Int,
        destination: String,
        failures: List<String>,
    ): String {
        val text = StringBuilder("$moved of $total trees moved to $destination.")
        text.append(" These did not move and are unchanged:")
        failures.forEach { text.append("\n• ").append(it) }
        return text.toString()
    }

    /// The destination could not be read, so no move was even planned.
    fun planFailedMessage(reason: String): String =
        "The trees already in the destination plot could not be read ($reason), so nothing" +
            " was moved. Nothing has changed."

    // Per-row failure reasons (each follows "• <tree> — ")
    const val TREE_GONE = "no longer in this device's database"

    fun storageError(reason: String): String = "storage error: $reason"

    // The quick-measure world's answer
    const val QUICK_HAS_NO_PROJECT_TITLE = "Quick measurements have no project"

    /// Says NO, and says why, rather than offering a conversion that would
    /// have to invent the fields a cruise tree carries. See the foot of this
    /// file.
    const val QUICK_HAS_NO_PROJECT_BODY =
        "A quick measurement is a reading, not a cruise tree: it has no plot centre, no" +
            " position within a plot, and it can be a crown or a distance with no diameter" +
            " at all — none of which a project's plot and stand figures can be worked out" +
            " without. Moving one into a project would mean inventing those. Measure the" +
            " tree in a cruise plot to record it under a project."

    /// A destination as it is named on screen — the same "project · Plot n"
    /// heading the log's own sections carry, so the confirmation names the
    /// destination in words the cruiser has already been reading.
    fun destinationLabel(projectName: String, plotNumber: Int): String =
        projectName + FieldLogWords.HEADING_SEPARATOR + FieldLogWords.plotName(plotNumber)
}

// MARK: - Destination -----------------------------------------------------

/// Where a move is headed. Carries the plot (the thing a tree can actually
/// be parented to) AND the project (the thing the cruiser asked for), so
/// every sentence on screen can name both.
data class TreeMoveDestination(val project: Project, val plot: Plot) {
    val label: String
        get() = TreeMoveWords.destinationLabel(project.name, plot.plotNumber)
    val isClosed: Boolean get() = plot.closedAt != null
}

// MARK: - Plan ------------------------------------------------------------

/// Everything the confirmation has to be able to say, worked out BEFORE a
/// single row is written. Building it reads the destination plot, which can
/// fail — and a failed read has to stop the move rather than let it proceed
/// on an unknown set of existing tree numbers.
data class TreeMovePlan(
    val destination: TreeMoveDestination,
    /// The trees that will actually be written, in the order they were
    /// selected. Trees already in the destination are NOT here.
    val movers: List<Tree>,
    /// Selected trees that are already in the destination plot. Left alone.
    val alreadyThere: Int,
    /// Selected ids that no longer resolve to a tree row.
    val missing: List<UUID>,
    /// treeID -> the number it will take, for the trees whose number has to
    /// change. Absent means the tree keeps the number it has.
    val renumbered: Map<UUID, Int>,
) {
    /// Everything the cruiser picked, however it ends up being handled.
    val selectedCount: Int get() = movers.size + alreadyThere + missing.size
    val hasWork: Boolean get() = movers.isNotEmpty()
}

/// One tree that did not move, and why — never folded into a count.
data class TreeMoveFailure(val id: UUID, val label: String, val reason: String) {
    /// The line the failure dialog prints.
    val line: String get() = "$label — $reason"
}

/// What actually happened on disk.
data class TreeMoveOutcome(
    val destination: TreeMoveDestination,
    val movedCount: Int,
    val failures: List<TreeMoveFailure>,
    /// Rows the cruiser picked that were already where they were headed.
    val alreadyThere: Int,
) {
    /// Every row the cruiser picked, so the report can say "9 of 12" rather
    /// than "9 of the 9 we tried".
    val attempted: Int get() = movedCount + failures.size + alreadyThere
    val isClean: Boolean get() = failures.isEmpty()
}

// MARK: - The move itself -------------------------------------------------

/// Re-parents cruise trees. Read the file header first — what this keeps,
/// what it clears and what it renumbers is the whole design.
object TreeMover {

    /// Works out the move without writing anything.
    ///
    /// Throws only when the DESTINATION cannot be read: the free tree
    /// numbers are derived from what is already in that plot, and guessing
    /// them would be how two trees end up wearing one number. A tree on the
    /// SOURCE side that cannot be read is not fatal — it is recorded in
    /// [TreeMovePlan.missing] and reported, because the other eleven rows
    /// are still perfectly movable.
    suspend fun plan(
        treeIDs: List<UUID>,
        destination: TreeMoveDestination,
        env: AppEnvironment,
    ): TreeMovePlan {
        // LIVE trees only, matching the tally loop's own next-number rule. A
        // soft-deleted row does not hold its number against a new tree
        // there, so it must not hold it against a moved one either —
        // otherwise the two paths would disagree about what "free" means in
        // the same plot.
        val existing = env.treeRepository.listByPlot(destination.plot.id, includeDeleted = false)
        val used = existing.map { it.treeNumber }.toMutableSet()
        var highest = existing.maxOfOrNull { it.treeNumber } ?: 0

        val movers = mutableListOf<Tree>()
        val missing = mutableListOf<UUID>()
        var alreadyThere = 0
        val renumbered = mutableMapOf<UUID, Int>()

        for (id in treeIDs) {
            // A read that threw and a row that is not there both land in
            // `missing`, which is right — either way the tree cannot be
            // moved and the cruiser has to be told which one it was.
            val tree = try {
                env.treeRepository.read(id)
            } catch (e: Exception) {
                null
            }
            if (tree == null) {
                missing += id
                continue
            }
            if (tree.plotId == destination.plot.id) {
                alreadyThere++
                continue
            }
            if (used.contains(tree.treeNumber)) {
                highest += 1
                renumbered[tree.id] = highest
                used += highest
            } else {
                used += tree.treeNumber
                highest = maxOf(highest, tree.treeNumber)
            }
            movers += tree
        }

        return TreeMovePlan(destination, movers, alreadyThere, missing, renumbered)
    }

    /// Writes the plan. ONE `update` per tree, so a row either lands whole
    /// in its new plot or is left exactly as it was — there is no half-moved
    /// state a later read could mistake for a real one. A row that throws is
    /// named in the outcome; the rest of the selection still moves, and the
    /// caller reports the split.
    suspend fun apply(plan: TreeMovePlan, env: AppEnvironment): TreeMoveOutcome {
        var moved = 0
        val failures = mutableListOf<TreeMoveFailure>()

        for (id in plan.missing) {
            // A row that vanished between the list being drawn and Move
            // being tapped. It is named by its id because there is no tree
            // left to ask for a name.
            failures += TreeMoveFailure(
                id = id,
                label = "Tree ${id.toString().take(8)}",
                reason = TreeMoveWords.TREE_GONE,
            )
        }

        for (tree in plan.movers) {
            val subject = tree.copy(
                plotId = plan.destination.plot.id,
                treeNumber = plan.renumbered[tree.id] ?: tree.treeNumber,
                // Measured from the centre this tree is leaving — see the
                // file header. Cleared, not carried and not recomputed.
                bearingFromCenterDeg = null,
                distanceFromCenterM = null,
                boundaryCall = null,
                updatedAt = System.currentTimeMillis(),
            )
            try {
                env.treeRepository.update(subject)
                moved++
            } catch (e: Exception) {
                failures += TreeMoveFailure(
                    id = tree.id,
                    label = tree.displayTitle,
                    reason = TreeMoveWords.storageError(e.message ?: e.toString()),
                )
            }
        }

        return TreeMoveOutcome(plan.destination, moved, failures, plan.alreadyThere)
    }
}

// MARK: - Selection bar ---------------------------------------------------

/// What is ticked, where it can go, and the way out — all three on one line,
/// always visible while selecting.
@Composable
fun TreeMoveSelectionBar(
    selected: Int,
    onMove: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Forestix.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ForestixSpace.md, vertical = ForestixSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        Text(
            TreeMoveWords.selectionCount(selected),
            style = Forestix.type.bodyBold, color = colors.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Text(
            TreeMoveWords.MOVE_BUTTON,
            style = Forestix.type.bodyBold,
            // Dim AND deaf with nothing ticked: a Move that opens a picker
            // for an empty selection would end in a confirmation about no
            // trees.
            color = if (selected == 0) colors.textTertiary else colors.primary,
            modifier = if (selected == 0) Modifier else Modifier.clickableNoRipple(onMove))
        Text(
            TreeMoveWords.CANCEL,
            style = Forestix.type.body, color = colors.textSecondary,
            modifier = Modifier.clickableNoRipple(onCancel))
    }
}

// MARK: - Destination picker ----------------------------------------------

/// Pick the PLOT a selection lands in. Projects are the headings because
/// that is what the cruiser asked for; plots are the rows because that is
/// what a tree can be parented to.
///
/// A project with no plots is listed and disabled rather than hidden: "that
/// project has nowhere to put a tree yet" is an answer, and a project simply
/// missing from this list is not.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeMovePicker(
    projects: List<Project>,
    plotsByProject: Map<UUID, List<Plot>>,
    onPick: (TreeMoveDestination) -> Unit,
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
                TreeMoveWords.PICK_TITLE,
                style = Forestix.type.bodyBold, color = colors.textPrimary)
            Text(
                TreeMoveWords.PICK_CAPTION,
                style = Forestix.type.caption, color = colors.textTertiary,
                modifier = Modifier.padding(top = 4.dp, bottom = ForestixSpace.sm))

            if (projects.isEmpty()) {
                Text(
                    TreeMoveWords.NO_PROJECTS,
                    style = Forestix.type.caption, color = colors.textSecondary)
            }

            projects.forEach { project ->
                // Same shape as the scope sheet's group headings — the
                // project names the group, the plots under it are the rows
                // that can actually be picked.
                Text(
                    project.name,
                    style = Forestix.type.sectionHead, color = colors.textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = ForestixSpace.md, bottom = 4.dp))
                val plots = plotsByProject[project.id].orEmpty()
                if (plots.isEmpty()) {
                    Text(
                        TreeMoveWords.NO_PLOTS,
                        style = Forestix.type.caption, color = colors.textTertiary,
                        modifier = Modifier.padding(
                            start = ForestixSpace.md, bottom = ForestixSpace.xs))
                } else {
                    plots.forEach { plot ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableNoRipple {
                                    onPick(TreeMoveDestination(project, plot))
                                }
                                .padding(
                                    start = ForestixSpace.md,
                                    top = ForestixSpace.sm, bottom = ForestixSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
                        ) {
                            Text(
                                FieldLogWords.plotName(plot.plotNumber),
                                style = Forestix.type.body, color = colors.textPrimary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            if (plot.closedAt != null) {
                                // Said here, and said again in the
                                // confirmation: a closed plot's summary and
                                // H-D fit were computed without the trees
                                // about to land in it.
                                Text(
                                    TreeMoveWords.CLOSED_PLOT,
                                    style = Forestix.type.caption,
                                    color = colors.textTertiary)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.size(ForestixSpace.lg))
        }
    }
}

// MARK: - Why the quick-measure world is not done here
//
// The request was "assign already-captured trees to a project". For the
// quick-measure store that could only mean converting a QuickMeasureEntry
// into a cruise `Tree`, and the two schemas do not carry the same facts:
//
//   1. A cruise `Tree.dbhCm` is NON-NULL. The log's rows are grouped
//      READINGS, and a row can legitimately hold a height and no diameter,
//      or be a crown, a distance or a sampling-plot record with no tree
//      number at all. Those rows cannot become trees without a diameter
//      being invented for them.
//   2. A cruise `Tree` lives in a `Plot` with a recorded centre, an area
//      and a position tier, and it carries bearingFromCenterDeg,
//      distanceFromCenterM and boundaryCall measured from that centre. A
//      quick plot has none of it — no centre, no area, no tier — so every
//      one of those fields would have to be invented, and they feed
//      expansion factors, basal area per acre and the whole stand summary.
//   3. TreeStatus, dbhMethod and the confidence tiers are recorded
//      differently on the two sides, and the conversion is one-way: the
//      quick row would be consumed and there would be no way back to the
//      reading as it was captured.
//
// Any one of those makes the conversion lossy; together they make it a
// fabrication. So the app says so, on screen, in
// [TreeMoveWords.QUICK_HAS_NO_PROJECT_BODY], when a cruiser long-presses a
// quick row — rather than offering a button that quietly manufactures the
// missing half of a cruise record.
