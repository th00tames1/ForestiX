// Move already-captured QUICK measurements into another quick-measure plot,
// in bulk or one at a time.
//
// WHY THIS EXISTS. The cruiser's own words: "그냥 그룹핑을 하고싶었던거임" — some
// of their readings are validation data and some are throwaway bench tests,
// and they want to separate them after the fact. The grouping mechanism
// already existed: `QuickMeasurePlot` has a name, every `QuickMeasureEntry`
// carries a `plotID` into one, the field log can be filtered to a single
// plot and the plot's name reaches every export. What did NOT exist was any
// way to change a reading's plot after it was recorded. The active plot was
// chosen BEFORE a measurement and could never be revisited, so a reading
// filed wrong was filed wrong forever. This is the missing move.
//
// This is NOT the cruise move (Screens/TreeMove.swift) and it is not a
// conversion into one. A quick reading has no project and cannot be given
// one — see the note at the foot of TreeMove.swift, which still holds. What
// a quick reading HAS is a plot, and that is what this changes.
//
// WHAT A MOVE REWRITES, AND WHAT IT DOES NOT.
//
//   Rewritten: `plotID`. That is the whole change, and it is the only field
//   `QuickMeasureEntry.inPlot` touches.
//
//   Untouched: the value, σ, confidence, method, capture mode, ground truth
//   and its source, the coordinate and ITS source, the photo, the species,
//   the stem position, the damage codes, the note, the timestamp, the tree
//   number and the cruiser's name for the stem. None of those is a statement
//   about a plot; they are statements about a reading, and they stay true
//   wherever it is filed. NOTHING HERE TOUCHES A MEASUREMENT.
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
// `max(all) + 1` across the whole log and is the cruiser's own label for the
// stem, printed on their tally sheet. Renaming their data to make room is
// not this feature's business, so the row is refused and NAMED — see
// `QuickMoveWords.numberTaken`.
//
// The Android sibling is android/.../ui/screens/QuickMove.kt; every
// user-visible string here is duplicated there byte for byte.

import SwiftUI
import Foundation
import Common
import Models

// MARK: - Words (byte-identical with the Android sibling)

/// Every user-visible string this feature owns that the CRUISE move does not
/// already own. Where the two moves say the same thing they share the same
/// constant — the count, the Cancel, the confirmation title, the "already
/// there" sentence and the whole failure report come from `TreeMoveWords`, so
/// the two selection modes on one list cannot drift into two dialects.
public enum QuickMoveWords {

    // Selection mode
    /// The cruise bar says "Move to project…" because a cruise tree is filed
    /// under one. A quick reading is filed under a plot and nothing else.
    public static let moveButton = "Move to plot…"
    /// Discoverability for the long press, shown under the list.
    public static let hint =
        "Press and hold a quick measurement to move it to another plot."

    // Destination picker
    public static let pickTitle = "Move to plot"
    /// The whole point of the feature in two sentences, where the cruiser is
    /// deciding. It names the two plots they said they wanted, because
    /// "make a plot first, somewhere else, then come back" is the detour that
    /// makes a feature go unused.
    public static let pickCaption =
        "A quick measurement is filed under a quick-measure plot, and that is the only grouping it has. Name one plot for the readings you are validating and another for the ones you are only bench-testing, and they stay apart everywhere the plot name goes — the log's filter, the summary card and every export."
    /// Header over the create-a-plot field.
    public static let newPlotHeader = "New plot"
    public static let newPlotPlaceholder = "Plot name"
    public static let newPlotAction = "Create and move here"
    /// A name is genuinely all that is needed — acreage, type and BAF are
    /// optional on `QuickMeasurePlot` and stay unset rather than invented.
    public static let newPlotFooter =
        "A name is all a quick-measure plot needs. Area, type and BAF stay unset until you fill them in, and the plot you are saving new measurements into does not change."
    public static let newPlotNeedsName = "Type a name for the new plot."
    /// Two plots with one name would defeat the split the cruiser is making,
    /// and neither the log nor the export could tell them apart.
    public static func newPlotNameTaken(_ name: String) -> String {
        "A plot called \"\(name)\" already exists — pick it in the list above."
    }
    /// How many readings are already filed under a destination, so the
    /// cruiser can tell "Validation" from an empty plot they made by mistake.
    public static func readingCount(_ n: Int) -> String {
        n == 1 ? "1 reading" : "\(n) readings"
    }
    /// A log with exactly one plot has nowhere to move anything TO. Said,
    /// rather than shown as an empty list.
    public static let noOtherPlots =
        "This is the only quick-measure plot on the device. Make another one below and the readings you picked will move straight into it."

    // Confirmation
    /// What is about to happen, named in full. Deliberately does NOT say
    /// "this can't be undone": unlike the cruise move, which clears the
    /// bearing and distance a tree had from the centre it is leaving, this
    /// changes one field and loses nothing, so the move back is the same
    /// three taps. Saying otherwise would be a threat the data does not
    /// support.
    ///
    /// Built by appending rather than as one literal: a single expression
    /// this long is what times the Swift type-checker out.
    public static func confirmMessage(destination: String,
                                      movers: Int,
                                      readings: Int,
                                      alreadyThere: Int) -> String {
        var text = movers == 1
            ? "1 tree moves to \(destination)."
            : "\(movers) trees move to \(destination)."
        text += readings == 1
            ? " Its 1 reading moves with it."
            : " All \(readings) readings on them move together, so no stem"
                + " ends up with its diameter in one plot and its height in another."
        text += " The measurements themselves do not change: value, ± band,"
            + " method, ground truth, photo and coordinate are all left"
            + " exactly as they were recorded."
        if alreadyThere > 0 {
            text += alreadyThere == 1
                ? " 1 tree you picked is already there and stays as it is."
                : " \(alreadyThere) trees you picked are already there and stay as they are."
        }
        text += " New measurements keep saving into the plot you have open now."
        text += " You can move them back the same way."
        return text
    }

    // Per-row failure reasons (each follows "• <tree> — ")
    /// Every reading behind the row went away between the list being drawn
    /// and Move being tapped.
    public static let rowGone = "no longer in this device's log"
    /// The destination already holds that tree number — see the file header
    /// for why this refuses instead of renumbering.
    public static func numberTaken(destination: String, number: Int) -> String {
        "\(destination) already has a tree #\(number), and merging them would"
            + " put two stems on one row"
    }
    /// The destination plot itself went away — every picked row fails, and
    /// the report names it once per row rather than claiming a partial move.
    public static func destinationGone(_ name: String) -> String {
        "\(name) is no longer in this device's log"
    }

    // Detail sheet
    /// The row label. A reading's plot IS its grouping, so the record sheet
    /// has to say which one it is in before the cruiser can tell what needs
    /// fixing.
    public static let plotRow = "Plot"
}

// MARK: - What is being moved

/// One field-log row, flattened to exactly what a move needs to know about
/// it. Built from a `FieldLogRowModel`, which is where the (plot, tree)
/// grouping is decided — this must never re-derive that grouping itself.
public struct QuickMoveRow: Identifiable, Equatable {
    /// The `FieldLogRowModel.id` this came from. Also what the detail sheet
    /// tracks the row by.
    public let id: String
    /// How the row is named in a failure line — the same title the sheet and
    /// the delete confirmation use.
    public let label: String
    /// nil for a loose reading (a sampling-plot record, a standalone crown or
    /// distance). Those never collide, because the log gives each its own row
    /// keyed on the reading id.
    public let treeNumber: Int?
    /// Every reading behind the row. All of them move, or none do.
    public let entryIDs: [UUID]
    /// The plot the row is in now, RAW — nil for a reading written before
    /// plots existed. Normalised through the default plot exactly where the
    /// rest of the app normalises it, never here.
    public let plotID: UUID?

    public init(id: String,
                label: String,
                treeNumber: Int?,
                entryIDs: [UUID],
                plotID: UUID?) {
        self.id = id
        self.label = label
        self.treeNumber = treeNumber
        self.entryIDs = entryIDs
        self.plotID = plotID
    }

    /// The row as the field log built it.
    public init(_ row: FieldLogRowModel) {
        self.id = row.id
        self.label = row.title
        if case .tree(let n) = row.subject { self.treeNumber = n } else { self.treeNumber = nil }
        self.entryIDs = row.entries.map(\.id)
        self.plotID = row.entries.first?.plotID
    }
}

/// One row that did not move, and why — never folded into a count.
public struct QuickMoveFailure: Identifiable {
    public let id: String
    public let label: String
    public let reason: String

    /// The line the failure alert prints.
    public var line: String { "\(label) — \(reason)" }
}

/// Everything the confirmation has to be able to say, worked out BEFORE a
/// single reading is written.
public struct QuickMovePlan {
    public let destination: QuickMeasurePlot
    /// The rows that will actually be written, in the order they appear on
    /// screen. Rows already in the destination are NOT here.
    public let movers: [QuickMoveRow]
    /// How many individual readings those rows carry between them.
    public let readingCount: Int
    /// Picked rows already in the destination. Left alone.
    public let alreadyThere: Int
    /// Rows that cannot move, each with its own reason. Carried into the
    /// outcome so the report is the same whether the row was refused before
    /// the write or during it.
    public let failures: [QuickMoveFailure]

    /// Everything the cruiser picked, however it ends up being handled.
    public var selectedCount: Int {
        movers.count + alreadyThere + failures.count
    }
    public var hasWork: Bool { !movers.isEmpty }
}

/// What actually happened in the store.
public struct QuickMoveOutcome {
    public let destination: QuickMeasurePlot
    public let movedCount: Int
    public let failures: [QuickMoveFailure]
    public let alreadyThere: Int

    /// Every row the cruiser picked, so the report can say "9 of 12" rather
    /// than "9 of the 9 we tried".
    public var attempted: Int { movedCount + failures.count + alreadyThere }
    public var isClean: Bool { failures.isEmpty }
}

// MARK: - The move itself

/// Re-homes quick readings. Read the file header first — what this moves,
/// what it leaves alone and what it refuses is the whole design.
@MainActor
public enum QuickMover {

    /// Works out the move without writing anything.
    ///
    /// Does not throw: the quick store is already in memory, so there is no
    /// read that can fail the way the cruise planner's destination read can.
    /// Everything that can go wrong with a row is a row-level failure, and
    /// every one of them is named.
    public static func plan(rows: [QuickMoveRow],
                            to destination: QuickMeasurePlot,
                            history: QuickMeasureHistory) -> QuickMovePlan {
        // The one normalisation rule, read from where the rest of the app
        // reads it: an entry with no plotID lives in the default plot. Older
        // entries genuinely hold nil, and the bootstrap that rewrites them
        // only runs at launch.
        let fallback = history.defaultPlotID()
        let live = Set(history.entries.map(\.id))
        // Tree numbers ALREADY in the destination. Rows that arrive during
        // this same move add to it as they are accepted, so two picked rows
        // both numbered 7 cannot merge into each other either.
        var used = Set(history.entries
            .filter { ($0.plotID ?? fallback) == destination.id }
            .compactMap(\.treeNumber))

        var movers: [QuickMoveRow] = []
        var failures: [QuickMoveFailure] = []
        var alreadyThere = 0
        var readings = 0

        for row in rows {
            // A row whose readings were all deleted while the selection was
            // up. Named, not silently dropped from the count.
            let surviving = row.entryIDs.filter { live.contains($0) }
            guard !surviving.isEmpty else {
                failures.append(QuickMoveFailure(id: row.id,
                                                 label: row.label,
                                                 reason: QuickMoveWords.rowGone))
                continue
            }
            if (row.plotID ?? fallback) == destination.id {
                alreadyThere += 1
                continue
            }
            if let number = row.treeNumber, used.contains(number) {
                failures.append(QuickMoveFailure(
                    id: row.id,
                    label: row.label,
                    reason: QuickMoveWords.numberTaken(
                        destination: destination.name, number: number)))
                continue
            }
            if let number = row.treeNumber { used.insert(number) }
            movers.append(QuickMoveRow(id: row.id,
                                       label: row.label,
                                       treeNumber: row.treeNumber,
                                       entryIDs: surviving,
                                       plotID: row.plotID))
            readings += surviving.count
        }

        return QuickMovePlan(destination: destination,
                             movers: movers,
                             readingCount: readings,
                             alreadyThere: alreadyThere,
                             failures: failures)
    }

    /// Writes the plan in ONE persist.
    ///
    /// The whole set of readings is handed to the store together, so the
    /// sidecar and the cache are rewritten once rather than once per reading
    /// on a phone the cruiser is holding — and there is no window in which
    /// half a tree has landed.
    public static func apply(_ plan: QuickMovePlan,
                             history: QuickMeasureHistory) -> QuickMoveOutcome {
        var failures = plan.failures

        // The destination itself can have been deleted while the
        // confirmation was up. NOTHING is written, and every row that was
        // going to move is named with that reason — a "nothing was moved"
        // that names no rows would leave the cruiser guessing which ones.
        guard history.plot(id: plan.destination.id) != nil else {
            for row in plan.movers {
                failures.append(QuickMoveFailure(
                    id: row.id,
                    label: row.label,
                    reason: QuickMoveWords.destinationGone(plan.destination.name)))
            }
            return QuickMoveOutcome(destination: plan.destination,
                                    movedCount: 0,
                                    failures: failures,
                                    alreadyThere: plan.alreadyThere)
        }

        let live = Set(history.entries.map(\.id))
        var ids: Set<UUID> = []
        var moved = 0
        for row in plan.movers {
            // Re-checked at the write, not trusted from the plan: the
            // guarantee that a row lands whole has to hold where the writing
            // happens, the same way `backfillTruths` re-checks its refusal.
            let surviving = row.entryIDs.filter { live.contains($0) }
            guard !surviving.isEmpty else {
                failures.append(QuickMoveFailure(id: row.id,
                                                 label: row.label,
                                                 reason: QuickMoveWords.rowGone))
                continue
            }
            ids.formUnion(surviving)
            moved += 1
        }
        history.moveEntries(ids, toPlot: plan.destination.id)

        return QuickMoveOutcome(destination: plan.destination,
                                movedCount: moved,
                                failures: failures,
                                alreadyThere: plan.alreadyThere)
    }
}

// MARK: - Destination picker

/// Pick the quick-measure plot a selection lands in, or name a new one.
///
/// Creating is HERE rather than behind a trip to the plot screen: the first
/// thing the cruiser will do is make "Validation" and "Bench test", and
/// sending them somewhere else to do it first is the detour that makes a
/// feature go unused. A quick plot needs nothing but a name — acreage, type
/// and BAF are all optional — so nothing is invented by creating one here.
public struct QuickMovePicker: View {

    /// Every quick plot, newest first, as the store holds them.
    public let plots: [QuickMeasurePlot]
    /// plot id → how many readings are filed under it, normalised through the
    /// default plot the same way everything else reads `plotID`.
    public let readingCounts: [UUID: Int]
    /// Plots the current selection is already entirely inside — shown, but
    /// not offered, so "move to where it already is" is not a tap away.
    public let currentPlotIDs: Set<UUID>
    public let onPick: (QuickMeasurePlot) -> Void
    /// Creates a plot with this name and moves into it. The picker validates
    /// the name; the host owns the store.
    public let onCreate: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var newName = ""
    @State private var refusal: String?

    public init(plots: [QuickMeasurePlot],
                readingCounts: [UUID: Int],
                currentPlotIDs: Set<UUID>,
                onPick: @escaping (QuickMeasurePlot) -> Void,
                onCreate: @escaping (String) -> Void) {
        self.plots = plots
        self.readingCounts = readingCounts
        self.currentPlotIDs = currentPlotIDs
        self.onPick = onPick
        self.onCreate = onCreate
    }

    /// Destinations worth offering: everything the selection is not already
    /// wholly inside.
    private var offered: [QuickMeasurePlot] {
        plots.filter { !currentPlotIDs.contains($0.id) || currentPlotIDs.count > 1 }
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(QuickMoveWords.pickCaption)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                if offered.isEmpty {
                    Section {
                        Text(QuickMoveWords.noOtherPlots)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } else {
                    Section {
                        ForEach(offered) { plot in
                            plotRow(plot)
                        }
                    }
                }
                newPlotSection
            }
            .navigationTitle(QuickMoveWords.pickTitle)
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

    private func plotRow(_ plot: QuickMeasurePlot) -> some View {
        Button {
            onPick(plot)
        } label: {
            HStack(spacing: ForestixSpace.xs) {
                Text(plot.name)
                    .font(ForestixType.body)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                Text(QuickMoveWords.readingCount(readingCounts[plot.id] ?? 0))
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("quickMove.plot")
    }

    private var newPlotSection: some View {
        Section {
            TextField(QuickMoveWords.newPlotPlaceholder, text: $newName)
                .foregroundStyle(ForestixPalette.textPrimary)
                .autocorrectionDisabled()
                .accessibilityIdentifier("quickMove.newPlotName")
            if let refusal {
                Text(refusal)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.confidenceBad)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button(QuickMoveWords.newPlotAction) { create() }
                .accessibilityIdentifier("quickMove.createPlot")
        } header: {
            Text(QuickMoveWords.newPlotHeader)
        } footer: {
            Text(QuickMoveWords.newPlotFooter)
        }
    }

    /// Validates the typed name and hands it over. A blank name and a name
    /// already in use are both REFUSED on screen rather than quietly
    /// producing an unnamed plot or a second "Validation" nothing downstream
    /// could tell from the first.
    private func create() {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            refusal = QuickMoveWords.newPlotNeedsName
            return
        }
        if let clash = plots.first(where: {
            $0.name.compare(trimmed, options: [.caseInsensitive, .diacriticInsensitive])
                == .orderedSame
        }) {
            refusal = QuickMoveWords.newPlotNameTaken(clash.name)
            return
        }
        refusal = nil
        onCreate(trimmed)
    }
}

// MARK: - The whole flow, as one modifier

/// Picker → confirmation → write → report, hung off any view that can raise
/// a set of rows to move.
///
/// It is a modifier rather than code in the field log because TWO surfaces
/// start this move — the log's selection bar and the record sheet's Plot row,
/// which is the single-tree case where entering a selection mode is overkill
/// — and a second copy of the confirmation is how the two would come to say
/// different things about what a move does.
public struct QuickMoveFlow: ViewModifier {

    /// Non-nil arms the flow: these are the rows the cruiser picked. Set back
    /// to nil by every way out.
    @Binding var request: [QuickMoveRow]?
    /// Called after a move that actually wrote something, with what happened.
    let onFinished: (QuickMoveOutcome) -> Void

    @EnvironmentObject private var history: QuickMeasureHistory
    /// The worked-out move, held while the confirmation is up. Nothing has
    /// been written at this point.
    @State private var pendingPlan: QuickMovePlan?
    /// A finished move that did NOT fully succeed. A clean move reports
    /// itself by the rows appearing under their new heading.
    @State private var outcome: QuickMoveOutcome?
    /// Every picked row was already in the chosen plot.
    @State private var nothingToMove: String?

    public func body(content: Content) -> some View {
        content
            .sheet(isPresented: Binding(get: { request != nil && pendingPlan == nil },
                                        set: { if !$0 { request = nil } })) {
                picker
            }
            .alert(TreeMoveWords.confirmTitle,
                   isPresented: Binding(get: { pendingPlan != nil },
                                        set: { if !$0 { pendingPlan = nil; request = nil } }),
                   presenting: pendingPlan) { plan in
                Button(TreeMoveWords.confirmAction(plan.movers.count)) { commit(plan) }
                Button(TreeMoveWords.cancel, role: .cancel) {
                    pendingPlan = nil
                    request = nil
                }
            } message: { plan in
                Text(QuickMoveWords.confirmMessage(
                    destination: plan.destination.name,
                    movers: plan.movers.count,
                    readings: plan.readingCount,
                    alreadyThere: plan.alreadyThere))
            }
            // A PARTIAL move is reported as a partial move, naming every row
            // that stayed put and why. Nothing here ever says "done" over
            // rows that did not land.
            .alert(TreeMoveWords.failureTitle,
                   isPresented: Binding(get: { outcome != nil },
                                        set: { if !$0 { outcome = nil } }),
                   presenting: outcome) { _ in
                Button("OK", role: .cancel) { outcome = nil }
            } message: { result in
                Text(TreeMoveWords.failureMessage(
                    moved: result.movedCount,
                    total: result.attempted,
                    destination: result.destination.name,
                    failures: result.failures.map(\.line)))
            }
            .alert(TreeMoveWords.confirmTitle,
                   isPresented: Binding(get: { nothingToMove != nil },
                                        set: { if !$0 { nothingToMove = nil } })) {
                Button("OK", role: .cancel) { nothingToMove = nil }
            } message: {
                Text(nothingToMove ?? "")
            }
    }

    private var picker: some View {
        let rows = request ?? []
        let fallback = history.defaultPlotID()
        var counts: [UUID: Int] = [:]
        for entry in history.entries {
            guard let id = entry.plotID ?? fallback else { continue }
            counts[id, default: 0] += 1
        }
        let homes = Set(rows.compactMap { $0.plotID ?? fallback })
        return QuickMovePicker(
            plots: history.plots,
            readingCounts: counts,
            currentPlotIDs: homes,
            onPick: { choose($0) },
            onCreate: { name in
                // Created INACTIVE: making a destination must not re-point
                // where the next measurement lands. See the file header.
                choose(history.createPlot(name: name, makeActive: false))
            })
    }

    /// Destination picked. Plans the move and raises the confirmation.
    /// NOTHING is written here.
    private func choose(_ destination: QuickMeasurePlot) {
        let rows = request ?? []
        let plan = QuickMover.plan(rows: rows,
                                   to: destination,
                                   history: history)
        if plan.hasWork {
            pendingPlan = plan
        } else if plan.failures.isEmpty {
            // Everything picked is already there. Say so rather than show a
            // confirmation for a move of nothing.
            request = nil
            nothingToMove = TreeMoveWords.nothingToMove(destination: destination.name)
        } else {
            // Nothing can move and it is not because it is already there —
            // report the reasons instead of a confirmation.
            request = nil
            outcome = QuickMoveOutcome(destination: destination,
                                       movedCount: 0,
                                       failures: plan.failures,
                                       alreadyThere: plan.alreadyThere)
        }
    }

    private func commit(_ plan: QuickMovePlan) {
        let result = QuickMover.apply(plan, history: history)
        pendingPlan = nil
        request = nil
        onFinished(result)
        if !result.isClean { outcome = result }
    }
}

public extension View {
    /// Arms the quick-measure move on this view. See `QuickMoveFlow`.
    func quickMoveFlow(request: Binding<[QuickMoveRow]?>,
                       onFinished: @escaping (QuickMoveOutcome) -> Void)
        -> some View {
        modifier(QuickMoveFlow(request: request, onFinished: onFinished))
    }
}
