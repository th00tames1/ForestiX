// Re-file already-captured trees under another project, in bulk.
//
// WHAT "ASSIGN A PROJECT" CAN MEAN, WORLD BY WORLD. The two stores answer
// this differently, and only one of them can answer it safely at all:
//
//   • CRUISE (Project → Plot → Tree, Core Data). A cruise tree ALREADY has
//     a project; it has it through its plot. There is no project column on
//     Tree to set. So "move it to another project" can only mean RE-PARENT
//     IT TO A PLOT IN THAT PROJECT, and the cruiser therefore has to pick a
//     PLOT, not a project — a project alone names nowhere for the tree to
//     land. That is what this file does.
//
//   • QUICK (QuickMeasurePlot → QuickMeasureEntry, the project-less
//     history). There is no project column anywhere in that store either,
//     and no plot with a project above it. Filing a quick reading under a
//     project could only mean CONVERTING it into a cruise Tree, and this
//     app does not do that. See `TreeMoveWords.quickHasNoProjectBody` for
//     the reason stated to the cruiser, and the note at the foot of this
//     file for the schema facts behind it.
//
// WHAT A MOVE REWRITES, AND WHAT IT DOES NOT.
//
//   Kept, untouched: diameter, height, every σ, species, status, damage,
//   notes, photo, raw scan path, the tree's own latitude/longitude, and the
//   cruiser's name for the stem. None of this is a statement about a plot;
//   it is a statement about a tree, and it stays true wherever the tree is
//   filed. NOTHING HERE TOUCHES A MEASUREMENT.
//
//   Cleared: `bearingFromCenterDeg`, `distanceFromCenterM`, `boundaryCall`.
//   All three are measured FROM A PLOT CENTRE, and the tree is leaving that
//   centre. Carrying them across would put the tree at that bearing and
//   distance from a different point — the plot mini-map draws the stem from
//   exactly this pair (`PlotMiniMapWidget`), so a stale pair does not read
//   as stale, it reads as a tree standing somewhere it never stood. Both
//   fields are already optional and every surface already handles nil, so
//   "we do not know where this tree sat in its new plot" is a state the app
//   can say. The confirmation says it before anything moves.
//
//   Possibly rewritten: `treeNumber`. Cruise numbering restarts on each
//   plot, so a tree #3 moving into a plot that already has a #3 must take a
//   free number or the destination ends up with two trees wearing one
//   number, which nothing downstream can tell apart. The free number is the
//   plot's own next one — `max(live numbers) + 1`, the same rule the tally
//   loop uses (`MapHomeScreen+Cruise.nextTreeNumber`). This is disclosed in
//   the confirmation, counted, and never silent.
//
// The Android sibling is android/.../ui/screens/TreeMove.kt; every
// user-visible string here is duplicated there byte for byte.

import SwiftUI
import Foundation
import Common
import Models
import Persistence

// MARK: - Words (byte-identical with the Android sibling)

/// Every user-visible string this feature owns, in one place, so the two
/// platforms cannot drift. Android holds the identical strings in
/// `TreeMoveWords`.
public enum TreeMoveWords {

    // Selection mode
    /// Always on screen while selecting — the cruiser must never have to
    /// count ticked rows themselves.
    public static func selectionCount(_ n: Int) -> String {
        n == 1 ? "1 tree selected" : "\(n) trees selected"
    }
    public static let moveButton = "Move to project…"
    /// The way out of selection mode.
    public static let cancel = "Cancel"
    /// Discoverability for the long press, shown under the list.
    public static let hint =
        "Press and hold a cruise tree to file it under another project."

    // Destination picker
    public static let pickTitle = "Move to"
    /// Says why a PLOT is being asked for when the cruiser asked for a
    /// project. This is the whole shape of the feature in one sentence.
    public static let pickCaption =
        "A cruise tree belongs to a project through its plot, so pick the plot it should land in."
    /// A project with nowhere to put a tree. Not offered — and said, rather
    /// than the project simply being absent from the list.
    public static let noPlots = "No plots — start one on the map first"
    /// Marks a destination plot that has already been closed.
    public static let closedPlot = "Closed"
    /// The cruise store holds no projects at all.
    public static let noProjects =
        "There are no cruise projects on this device to move trees into."

    // Confirmation
    public static let confirmTitle = "Move these trees?"
    public static func confirmAction(_ n: Int) -> String {
        n == 1 ? "Move 1 tree" : "Move \(n) trees"
    }

    /// What is about to happen, named in full, because this rewrites which
    /// project and which plot a piece of field data was recorded in.
    ///
    /// Built by appending rather than as one literal: a single expression
    /// this long is what times the Swift type-checker out.
    public static func confirmMessage(destination: String,
                                      movers: Int,
                                      alreadyThere: Int,
                                      renumbered: Int,
                                      destinationClosed: Bool) -> String {
        var text = movers == 1
            ? "1 tree moves to \(destination)."
            : "\(movers) trees move to \(destination)."
        text += " Diameter, height, species, photos and notes are unchanged."
        if renumbered > 0 {
            text += renumbered == 1
                ? " 1 of them is renumbered, because its tree number is already used there."
                : " \(renumbered) of them are renumbered, because their tree numbers are already used there."
        }
        text += " Where each tree sat inside its old plot — bearing, distance"
            + " from centre and boundary call — is cleared, because it was"
            + " measured from a centre the tree is leaving."
        if alreadyThere > 0 {
            text += alreadyThere == 1
                ? " 1 tree you picked is already there and stays as it is."
                : " \(alreadyThere) trees you picked are already there and stay as they are."
        }
        if destinationClosed {
            text += " \(destination) is closed — its summary and height curve"
                + " were worked out without these trees."
        }
        text += " This can't be undone."
        return text
    }

    /// Every picked tree is already in the chosen plot. Nothing is written,
    /// and the cruiser is told that rather than shown a confirmation for a
    /// move of nothing.
    public static func nothingToMove(destination: String) -> String {
        "Every tree you picked is already in \(destination), so there is nothing to move."
    }

    // Results
    public static let failureTitle = "Some trees did not move"
    public static let planFailedTitle = "Nothing was moved"

    /// A PARTIAL move, reported as a partial move. `moved` of `total` went;
    /// the rest are named with the reason each one stopped.
    public static func failureMessage(moved: Int,
                                      total: Int,
                                      destination: String,
                                      failures: [String]) -> String {
        var text = "\(moved) of \(total) trees moved to \(destination)."
        text += " These did not move and are unchanged:"
        for line in failures { text += "\n• " + line }
        return text
    }

    /// The destination could not be read, so no move was even planned.
    public static func planFailedMessage(reason: String) -> String {
        "The trees already in the destination plot could not be read (\(reason)),"
            + " so nothing was moved. Nothing has changed."
    }

    // Per-row failure reasons (each follows "• <tree> — ")
    public static let treeGone =
        "no longer in this device's database"
    public static func storageError(_ reason: String) -> String {
        "storage error: \(reason)"
    }

    // The quick-measure world's answer
    public static let quickHasNoProjectTitle = "Quick measurements have no project"
    /// Says NO, and says why, rather than offering a conversion that would
    /// have to invent the fields a cruise tree carries. See the foot of this
    /// file.
    public static let quickHasNoProjectBody =
        "A quick measurement is a reading, not a cruise tree: it has no plot centre, no position within a plot, and it can be a crown or a distance with no diameter at all — none of which a project's plot and stand figures can be worked out without. Moving one into a project would mean inventing those. Measure the tree in a cruise plot to record it under a project."

    /// A destination as it is named on screen — the same "project · Plot n"
    /// heading the log's own sections carry, so the confirmation names the
    /// destination in words the cruiser has already been reading.
    public static func destinationLabel(projectName: String,
                                        plotNumber: Int) -> String {
        projectName + FieldLogWords.headingSeparator
            + FieldLogWords.plotName(number: plotNumber)
    }
}

// MARK: - Destination

/// Where a move is headed. Carries the plot (the thing a tree can actually
/// be parented to) AND the project (the thing the cruiser asked for), so
/// every sentence on screen can name both.
public struct TreeMoveDestination: Identifiable, Equatable {
    public let project: Project
    public let plot: Plot

    public var id: UUID { plot.id }

    /// Identity, not contents. `Project` and `Plot` are not themselves
    /// Equatable — they carry mutable measured fields, and two reads of the
    /// same plot seconds apart can legitimately differ — so a synthesised
    /// memberwise `==` would neither compile nor mean the right thing. What
    /// the picker and the confirmation ask is "is this the same destination",
    /// and a plot id answers exactly that.
    public static func == (lhs: TreeMoveDestination, rhs: TreeMoveDestination) -> Bool {
        lhs.plot.id == rhs.plot.id && lhs.project.id == rhs.project.id
    }

    public var label: String {
        TreeMoveWords.destinationLabel(projectName: project.name,
                                       plotNumber: plot.plotNumber)
    }
    public var isClosed: Bool { plot.closedAt != nil }

    public init(project: Project, plot: Plot) {
        self.project = project
        self.plot = plot
    }
}

// MARK: - Plan

/// Everything the confirmation has to be able to say, worked out BEFORE a
/// single row is written. Building it reads the destination plot, which can
/// fail — and a failed read has to stop the move rather than let it proceed
/// on an unknown set of existing tree numbers.
public struct TreeMovePlan {
    public let destination: TreeMoveDestination
    /// The trees that will actually be written, in the order they were
    /// selected. Trees already in the destination are NOT here.
    public let movers: [Tree]
    /// Selected trees that are already in the destination plot. Left alone.
    public let alreadyThere: Int
    /// Selected ids that no longer resolve to a tree row.
    public let missing: [UUID]
    /// treeID → the number it will take, for the trees whose number has to
    /// change. Absent means the tree keeps the number it has.
    public let renumbered: [UUID: Int]

    /// Everything the cruiser picked, however it ends up being handled.
    public var selectedCount: Int {
        movers.count + alreadyThere + missing.count
    }
    public var hasWork: Bool { !movers.isEmpty }
}

/// One tree that did not move, and why — never folded into a count.
public struct TreeMoveFailure: Identifiable {
    public let id: UUID
    public let label: String
    public let reason: String

    /// The line the failure alert prints.
    public var line: String { "\(label) — \(reason)" }
}

/// What actually happened on disk.
public struct TreeMoveOutcome {
    public let destination: TreeMoveDestination
    public let movedCount: Int
    public let failures: [TreeMoveFailure]
    /// Rows the cruiser picked that were already where they were headed.
    public let alreadyThere: Int

    /// Every row the cruiser picked, so the report can say "9 of 12" rather
    /// than "9 of the 9 we tried".
    public var attempted: Int { movedCount + failures.count + alreadyThere }
    public var isClean: Bool { failures.isEmpty }
}

// MARK: - The move itself

/// Re-parents cruise trees. Read the file header first — what this keeps,
/// what it clears and what it renumbers is the whole design.
public enum TreeMover {

    /// Works out the move without writing anything.
    ///
    /// Throws only when the DESTINATION cannot be read: the free tree
    /// numbers are derived from what is already in that plot, and guessing
    /// them would be how two trees end up wearing one number. A tree on the
    /// SOURCE side that cannot be read is not fatal — it is recorded in
    /// `missing` and reported, because the other eleven rows are still
    /// perfectly movable.
    public static func plan(treeIDs: [UUID],
                            to destination: TreeMoveDestination,
                            environment: AppEnvironment) throws -> TreeMovePlan {
        // LIVE trees only, matching the tally loop's own next-number rule
        // (`MapHomeScreen+Cruise.nextTreeNumber`). A soft-deleted row does
        // not hold its number against a new tree there, so it must not hold
        // it against a moved one either — otherwise the two paths would
        // disagree about what "free" means in the same plot.
        let existing = try environment.treeRepository
            .listByPlot(destination.plot.id, includeDeleted: false)
        var used = Set(existing.map(\.treeNumber))
        var highest = existing.map(\.treeNumber).max() ?? 0

        var movers: [Tree] = []
        var missing: [UUID] = []
        var alreadyThere = 0
        var renumbered: [UUID: Int] = [:]

        for id in treeIDs {
            // `try?` on a throwing call that already returns an Optional
            // flattens (SE-0230), so this is one `Tree?`: a read that threw
            // and a row that is not there both land in `missing`, which is
            // right — either way the tree cannot be moved and the cruiser
            // has to be told which one it was.
            guard let tree = try? environment.treeRepository.read(id: id)
            else {
                missing.append(id)
                continue
            }
            if tree.plotId == destination.plot.id {
                alreadyThere += 1
                continue
            }
            if used.contains(tree.treeNumber) {
                highest += 1
                renumbered[tree.id] = highest
                used.insert(highest)
            } else {
                used.insert(tree.treeNumber)
                highest = max(highest, tree.treeNumber)
            }
            movers.append(tree)
        }

        return TreeMovePlan(destination: destination,
                            movers: movers,
                            alreadyThere: alreadyThere,
                            missing: missing,
                            renumbered: renumbered)
    }

    /// Writes the plan. ONE `update` per tree, so a row either lands whole
    /// in its new plot or is left exactly as it was — there is no half-moved
    /// state a later read could mistake for a real one. A row that throws is
    /// named in the outcome; the rest of the selection still moves, and the
    /// caller reports the split.
    public static func apply(_ plan: TreeMovePlan,
                             environment: AppEnvironment) -> TreeMoveOutcome {
        var moved = 0
        var failures: [TreeMoveFailure] = []

        for id in plan.missing {
            // A row that vanished between the list being drawn and Move
            // being tapped. It is named by its id because there is no tree
            // left to ask for a name.
            failures.append(TreeMoveFailure(id: id,
                                            label: "Tree \(id.uuidString.prefix(8))",
                                            reason: TreeMoveWords.treeGone))
        }

        for tree in plan.movers {
            var subject = tree
            subject.plotId = plan.destination.plot.id
            subject.treeNumber = plan.renumbered[tree.id] ?? tree.treeNumber
            // Measured from the centre this tree is leaving — see the file
            // header. Cleared, not carried and not recomputed.
            subject.bearingFromCenterDeg = nil
            subject.distanceFromCenterM = nil
            subject.boundaryCall = nil
            subject.updatedAt = Date()
            do {
                _ = try environment.treeRepository.update(subject)
                moved += 1
            } catch {
                failures.append(TreeMoveFailure(
                    id: tree.id,
                    label: tree.displayTitle,
                    reason: TreeMoveWords.storageError(error.localizedDescription)))
            }
        }

        return TreeMoveOutcome(destination: plan.destination,
                               movedCount: moved,
                               failures: failures,
                               alreadyThere: plan.alreadyThere)
    }
}

// MARK: - Destination picker

/// Pick the PLOT a selection lands in. Projects are the headings because
/// that is what the cruiser asked for; plots are the rows because that is
/// what a tree can be parented to.
///
/// A project with no plots is listed and disabled rather than hidden: "that
/// project has nowhere to put a tree yet" is an answer, and a project simply
/// missing from this list is not.
public struct TreeMovePicker: View {

    public let projects: [Project]
    public let plotsByProject: [UUID: [Plot]]
    public let onPick: (TreeMoveDestination) -> Void

    @Environment(\.dismiss) private var dismiss

    public init(projects: [Project],
                plotsByProject: [UUID: [Plot]],
                onPick: @escaping (TreeMoveDestination) -> Void) {
        self.projects = projects
        self.plotsByProject = plotsByProject
        self.onPick = onPick
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(TreeMoveWords.pickCaption)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if projects.isEmpty {
                    Section {
                        Text(TreeMoveWords.noProjects)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                ForEach(projects) { project in
                    Section(project.name) {
                        let plots = plotsByProject[project.id] ?? []
                        if plots.isEmpty {
                            Text(TreeMoveWords.noPlots)
                                .font(ForestixType.caption)
                                .foregroundStyle(ForestixPalette.textTertiary)
                        } else {
                            ForEach(plots) { plot in
                                plotRow(project: project, plot: plot)
                            }
                        }
                    }
                }
            }
            .navigationTitle(TreeMoveWords.pickTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(TreeMoveWords.cancel) { dismiss() }
                }
            }
        }
    }

    private func plotRow(project: Project, plot: Plot) -> some View {
        Button {
            onPick(TreeMoveDestination(project: project, plot: plot))
        } label: {
            HStack(spacing: ForestixSpace.xs) {
                Text(FieldLogWords.plotName(number: plot.plotNumber))
                    .font(ForestixType.body)
                    .foregroundStyle(ForestixPalette.textPrimary)
                Spacer(minLength: 0)
                if plot.closedAt != nil {
                    // Said here, and said again in the confirmation: a
                    // closed plot's summary and H–D fit were computed
                    // without the trees about to land in it.
                    Text(TreeMoveWords.closedPlot)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("treeMove.plot")
    }
}

// MARK: - Why the quick-measure world is not done here
//
// The request was "assign already-captured trees to a project". For the
// quick-measure store that could only mean converting a QuickMeasureEntry
// into a cruise `Tree`, and the two schemas do not carry the same facts:
//
//   1. A cruise `Tree.dbhCm` is NON-OPTIONAL. The log's rows are grouped
//      READINGS, and a row can legitimately hold a height and no diameter,
//      or be a crown, a distance or a sampling-plot record with no tree
//      number at all. Those rows cannot become trees without a diameter
//      being invented for them.
//   2. A cruise `Tree` lives in a `Plot` with a recorded centre, an area
//      and a position tier, and it carries `bearingFromCenterDeg`,
//      `distanceFromCenterM` and `boundaryCall` measured from that centre.
//      A quick plot has none of it — no centre, no area, no tier — so
//      every one of those fields would have to be invented, and they feed
//      expansion factors, basal area per acre and the whole stand summary.
//   3. `TreeStatus`, `dbhMethod` and the confidence tiers are recorded
//      differently on the two sides, and the conversion is one-way: the
//      quick row would be consumed and there would be no way back to the
//      reading as it was captured.
//
// Any one of those makes the conversion lossy; together they make it a
// fabrication. So the app says so, on screen, in
// `TreeMoveWords.quickHasNoProjectBody`, when a cruiser long-presses a
// quick row — rather than offering a button that quietly manufactures the
// missing half of a cruise record.
