// Field-log scope — which project / which plot the log is showing.
// Kotlin sibling of iOS Screens/FieldLogScope.swift: same cases, same
// user-visible strings, byte for byte.
//
// FIELD REPORT 5 (second half). The cruiser asked to read the field log
// "per project and per plot", and wrote "서브플롯? 스탠드?? 플롯??" — they are
// not sure what the app calls these levels. Neither was the code, so this
// file fixes ONE vocabulary and states, in the type system, the thing that
// makes the request delicate:
//
//   THIS APP HAS TWO SEPARATE WORLDS, AND THEY DO NOT SHARE A PLOT.
//
//   • CRUISE  Project → Plot → Tree.  (Stratum and PlannedPlot hang off the
//             project too, but no measurement is filed under them.) Stored
//             in the Room cruise database. A cruise Tree ALWAYS has a plot,
//             and that plot ALWAYS has a project.
//
//   • QUICK   QuickMeasurePlot → QuickMeasureEntry. Stored in the
//             project-less QuickMeasureHistory. A quick plot has NO project
//             — there is no column for one and no screen that could set it.
//             The default plot is literally named "Quick measurements".
//
// `QuickMeasureEntry.plotID` and `Plot.id` are both UUIDs and they are NOT
// interchangeable: a quick id will never match a cruise plot row and vice
// versa. Every scan screen's Accept branches on cruise-vs-quick and writes
// to exactly one of the two stores, so a tree can never be in both.
//
// Which is why this scope is a SEALED hierarchy over both worlds rather
// than a pair of loose UUIDs, and why the screen renders the two in
// separate sections and never interleaves them. Claiming a quick reading
// belongs to the project the cruiser happens to have open would be
// inventing a fact the data does not carry.

package com.hcjeong.forestix.ui.screens

import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.Tree
import java.util.UUID

sealed interface FieldLogScope {
    /// Both worlds, every plot.
    data object Everything : FieldLogScope
    /// One cruise project — all of its plots.
    data class CruiseProject(val id: UUID) : FieldLogScope
    /// One cruise plot.
    data class CruisePlot(val id: UUID) : FieldLogScope
    /// One quick-measure plot.
    data class QuickPlot(val id: UUID) : FieldLogScope
}

/// The words this feature uses, in one place, so the two platforms and the
/// two worlds cannot drift apart. iOS holds the identical strings in
/// `FieldLogWords`.
object FieldLogWords {
    /// Filter sheet title.
    const val FILTER_TITLE = "Show"
    const val EVERYTHING = "Everything"
    const val CRUISE_PROJECTS_HEADER = "Cruise projects"
    const val QUICK_PLOTS_HEADER = "Quick measurement plots"
    /// A project row that means "every plot in it".
    const val ALL_PLOTS = "All plots"
    /// What the PROJECT half of a quick-measure row's heading says. Not a
    /// placeholder — it is the fact: quick measurements are filed under no
    /// project at all.
    const val NO_PROJECT = "No project"
    /// Explains the split, in the cruiser's terms, above the list.
    const val WORLDS_CAPTION =
        "Cruise trees belong to a project and a plot. Quick measurements belong to a plot only " +
            "— they are not part of any project."
    /// Scope has no rows (as opposed to the whole log being empty).
    const val EMPTY_SCOPE = "Nothing recorded in this plot yet."
    /// The cruise store could not be read. Never silently show an empty
    /// cruise section instead — an empty plot and an unreadable database
    /// look the same to the cruiser and only one of them means "no trees".
    const val CRUISE_UNREADABLE =
        "Cruise trees could not be read from this device's database, so none are shown here."
    /// Separator between the project and the plot in a section heading.
    const val HEADING_SEPARATOR = " · "
    /// A reading whose plot row is gone. It is still a real reading, so it
    /// keeps its section — labelled for what is actually known about it
    /// rather than quietly re-homed under some other plot's heading.
    const val UNKNOWN_PLOT = "Plot not found"

    /// How a cruise plot is named everywhere in this feature.
    fun plotName(number: Int) = "Plot $number"

    /// The DISPLAY ORDINAL drawn in front of a field-log row.
    ///
    /// IT IS THE ROW'S POSITION IN THE LIST AS CURRENTLY SHOWN, AND NOTHING
    /// ELSE. It is not stored, not exported, and not an identity — it changes
    /// the moment a row above it is deleted or the scope filter narrows the
    /// list, which is exactly why it must never reach `TreeLabel.title` /
    /// `Tree.displayTitle`. That is the app's one naming rule, shared by the
    /// map, the cruise surfaces and the exports, and a number that moves when
    /// the view changes has no business in it. The ordinal is applied where
    /// the list is RENDERED, over the list the screen is actually drawing, so
    /// it always agrees with the order on screen however that order was
    /// arrived at.
    ///
    /// The trailing separator is the same middle dot as [HEADING_SEPARATOR];
    /// the gap to the tree name is the table's column gutter, so the ordinal
    /// stays inside its own narrow column instead of eating the name's.
    /// iOS's `FieldLogWords.rowOrdinal` returns the identical string.
    fun rowOrdinal(position: Int): String = "$position ·"

    /// Where a recorded coordinate came from, in words. The raws are
    /// `PositionSource` values, shared with the cruise Plot record.
    ///
    /// A coordinate the cruiser typed MUST read differently from one the
    /// satellites produced — otherwise a corrected position is
    /// indistinguishable from a measured one on screen and in the export,
    /// which is the same class of error as a typed diameter carrying a
    /// sensor sigma. Unknown raws print themselves rather than being folded
    /// into "Device GPS": a wrong provenance is worse than an opaque one.
    fun positionSourceText(raw: String): String = when (raw) {
        "manual" -> "Typed by hand"
        "gpsSingle" -> "Device GPS"
        "gpsAveraged" -> "Device GPS, averaged"
        "externalRTK" -> "External RTK receiver"
        "vioOffset", "vioChain" -> "Walked from a known point"
        else -> raw
    }
}

/// One cruise tree, flattened for the log's three-column table.
///
/// NOT WRITTEN here. The quick rows beside it can be edited, re-measured and
/// swiped away, because the field log owns that store. A cruise tree is
/// owned by the cruise flow and edited on TreeDetailScreen — duplicating
/// that editing here would give the same row two save paths.
///
/// It is still OPENED here: clicking the row navigates to TreeDetailScreen,
/// so the cruiser can inspect a tree from the surface they are reading it on.
/// That costs the cruise store no second save path, which is what the
/// paragraph above is about.
data class FieldLogCruiseRow(
    val id: UUID,
    val plotID: UUID,
    val treeLabel: String,
    /// cm, as stored.
    val dbhCm: Double,
    val heightM: Double?,
    val speciesCode: String,
    val recordedAt: Long,
) {
    companion object {
        fun from(tree: Tree) = FieldLogCruiseRow(
            id = tree.id,
            plotID = tree.plotId,
            treeLabel = tree.treeName ?: "#${tree.treeNumber}",
            dbhCm = tree.dbhCm.toDouble(),
            heightM = tree.heightM?.toDouble(),
            speciesCode = tree.speciesCode,
            recordedAt = tree.createdAt,
        )
    }
}

/// One heading plus the rows under it. A section is always ONE plot of ONE
/// world, so [projectLabel] / [plotLabel] are true of every row inside it —
/// which is how each row says where it belongs without the three-column
/// table growing two more columns it has no width for.
data class FieldLogSection(
    val id: String,
    val projectLabel: String,
    val plotLabel: String,
    /// Editable quick-measure rows. Empty on a cruise section.
    val quickRows: List<FieldLogRowModel> = emptyList(),
    /// Read-only cruise rows. Empty on a quick section.
    val cruiseRows: List<FieldLogCruiseRow> = emptyList(),
    /// WHEN WORK IN THIS PLOT BEGAN — the sort key.
    ///
    /// The EARLIEST reading in the section, for the reason
    /// [FieldLogRowModel.measuredAt] gives one level down: keyed on the
    /// newest, as it was, a single late reading added to a plot the cruiser
    /// finished an hour ago threw that whole plot back to the top of the log
    /// while they were reading it. Keyed on the earliest, a section takes its
    /// place the moment its first tree is measured and then stays there.
    val measuredAt: Long,
) {
    val heading: String get() = projectLabel + FieldLogWords.HEADING_SEPARATOR + plotLabel
    val isEmpty: Boolean get() = quickRows.isEmpty() && cruiseRows.isEmpty()
}

/// The cruise half of the log, read once and held. A read-back surface must
/// not put a Room query inside recomposition.
data class FieldLogCruiseData(
    val projects: List<Project> = emptyList(),
    val plotsByProject: Map<UUID, List<Plot>> = emptyMap(),
    val rowsByPlot: Map<UUID, List<FieldLogCruiseRow>> = emptyMap(),
    /// Non-null when the store could not be read. The screen SAYS SO rather
    /// than rendering an empty cruise side, which would read as "you have
    /// measured no trees".
    val failure: String? = null,
) {
    fun plot(id: UUID): Plot? =
        plotsByProject.values.firstNotNullOfOrNull { plots -> plots.firstOrNull { it.id == id } }

    fun project(plotID: UUID): Project? {
        val projectID = plotsByProject.entries
            .firstOrNull { (_, plots) -> plots.any { it.id == plotID } }?.key ?: return null
        return projects.firstOrNull { it.id == projectID }
    }

    /// Cruise sections in scope, most recently STARTED plot first. A plot
    /// with no trees is dropped from Everything and from a whole-project
    /// scope (there is nothing to read), but KEPT when the cruiser asked for
    /// that one plot —
    /// there the empty section is the answer to their question.
    fun sections(scope: FieldLogScope): List<FieldLogSection> {
        val out = mutableListOf<FieldLogSection>()
        for (project in projects) {
            for (plot in plotsByProject[project.id].orEmpty()) {
                val keep = when (scope) {
                    is FieldLogScope.Everything -> true
                    is FieldLogScope.CruiseProject -> scope.id == project.id
                    is FieldLogScope.CruisePlot -> scope.id == plot.id
                    is FieldLogScope.QuickPlot -> false
                }
                if (!keep) continue
                val rows = rowsByPlot[plot.id].orEmpty()
                // A plot the cruiser explicitly asked for keeps its heading,
                // so "nothing here yet" is an answer rather than a section
                // that silently isn't there.
                if (rows.isEmpty() && scope != FieldLogScope.CruisePlot(plot.id)) continue
                out += FieldLogSection(
                    id = "c|${plot.id}",
                    projectLabel = project.name,
                    plotLabel = FieldLogWords.plotName(plot.plotNumber),
                    cruiseRows = rows,
                    // The OLDEST tree in the plot, not the newest: same rule
                    // as the quick sections, so one late tree cannot throw a
                    // finished plot back to the top of the log. `rows` is
                    // sorted newest-first at load, so the oldest is last. An
                    // empty plot falls back to when it was started, which is
                    // the only time it has.
                    measuredAt = rows.lastOrNull()?.recordedAt ?: plot.startedAt,
                )
            }
        }
        // Ties break on id so two sections stamped in the same millisecond
        // cannot swap places between two reads. Matches the iOS sibling.
        return out.sortedWith(
            compareByDescending<FieldLogSection> { it.measuredAt }.thenBy { it.id },
        )
    }
}

/// Read every project, plot and live tree out of the cruise store. Soft
/// deleted trees stay out — `listByPlot` already defaults to
/// includeDeleted = false, and a tree the cruiser deleted must not reappear
/// in a read-back.
suspend fun loadFieldLogCruiseData(
    env: com.hcjeong.forestix.AppEnvironment,
): FieldLogCruiseData = try {
    val projects = env.projectRepository.list().sortedBy { it.name }
    val plots = mutableMapOf<UUID, List<Plot>>()
    val rows = mutableMapOf<UUID, List<FieldLogCruiseRow>>()
    for (project in projects) {
        val projectPlots = env.plotRepository.listByProject(project.id)
            .sortedBy { it.plotNumber }
        plots[project.id] = projectPlots
        for (plot in projectPlots) {
            // `recordedAt` is the tree's own `createdAt` — a cruise row is
            // one tree with one time, so it is already "when the tree was
            // measured" and nothing later re-dates it. Ties break on id so
            // two trees stamped in the same instant cannot swap places.
            rows[plot.id] = env.treeRepository.listByPlot(plot.id)
                .map(FieldLogCruiseRow::from)
                .sortedWith(
                    compareByDescending<FieldLogCruiseRow> { it.recordedAt }
                        .thenBy { it.id.toString() },
                )
        }
    }
    FieldLogCruiseData(projects, plots, rows, failure = null)
} catch (e: Exception) {
    // Say the read failed rather than returning a convincing empty result.
    FieldLogCruiseData(failure = FieldLogWords.CRUISE_UNREADABLE)
}
