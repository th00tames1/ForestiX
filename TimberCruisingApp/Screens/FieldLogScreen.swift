// Field log — dedicated screen that owns the full measurement history.
//
// FIELD REPORT 5 — the log is now ONE ROW PER TREE.
//
// It used to be one row per MEASUREMENT, so a tree the cruiser had
// diametered and then measured the height of appeared twice, in two
// places in the list, joined only by a small "#12" on each row's meta
// line. Reading back a plot meant scanning for pairs. The table now leads
// with the tree number and puts that tree's diameter and height beside it,
// which is the shape of the paper tally sheet this replaces.
//
// The ± RANGE and QUALITY columns are gone with it. Four columns on a
// phone had every cell scaling to fit, and the two that were dropped were
// the two a cruiser was not reading in the field. Neither number is lost:
// σ is still recorded on the entry, still exported, and now shown in the
// detail sheet — which is what a tap on a row opens, and which carries the
// whole record (species, stem position, damage, note, position, photo, and
// in developer mode the ground truth typed against that tree).
//
// Readings that were never attached to a tree — a sampling-plot record, a
// standalone crown or distance — still get their own row. They are real
// records; grouping by tree must not make them disappear.
//
// The screen otherwise keeps its shape:
//   • Plot summary card for the active plot
//   • Summary header — total count + readings-today + "last" timestamp
//   • Capacity banner — only when the log is near its cap
//   • Native iOS List, so swipe-to-delete and VoiceOver traversal are
//     standard
//   • Export CSV / bundle in the toolbar
//
// FIELD REPORT 5 (second half) — the log is now READ PER PROJECT AND PER
// PLOT. See FieldLogScope.swift for why that needed a closed enum: cruise
// trees and quick measurements live in two separate stores whose plot ids
// are not interchangeable, so the screen shows them in separate sections
// under a heading naming the project and the plot, and never interleaves
// them. Quick rows keep every edit path they had; cruise rows are read-only
// here, because TreeDetailScreen owns that store's writes.
//
// READ-ONLY MEANS NOT WRITTEN, NOT UNOPENABLE. That rule was over-applied:
// a cruise row had no chevron and no tap target at all, so the log listed a
// tree the cruiser could not inspect from the surface they were reading it
// on. A cruise row now OPENS — it pushes the existing TreeDetailScreen, the
// one surface that owns this store's writes. What the original argument was
// actually about survives untouched: the log still writes no MEASUREMENT
// onto a cruise tree. There is no inline editor and no swipe-delete for
// those rows, so a diameter or a height on a cruise stem still has exactly
// one save path.
//
// The three-column table did NOT grow two more columns for project and
// plot: the whole point of the one-row-per-tree change above was that four
// columns on a phone had every cell scaling to fit. The section heading
// carries the project and the plot for every row beneath it instead.
//
// The `QuickMeasureEntry` / `QuickMeasureHistory` backing store is unchanged
// — no changes to the durability / schema layer, and nothing here writes to
// the cruise store at all.

import SwiftUI
import Common
import Models
import Sensors

public struct FieldLogScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var environment: AppEnvironment
    /// Cruise trees, read once on appear. See FieldLogCruiseFeed.
    @StateObject private var cruiseFeed = FieldLogCruiseFeed()
    /// What the log is showing. Seeded from the caller — the cruise project
    /// sheet opens it already narrowed to the plot in hand — and changed
    /// from the filter sheet.
    @State private var scope: FieldLogScope
    @State private var choosingScope = false
    @State private var shareURL: URL?
    /// The row whose detail sheet is open. nil = closed.
    @State private var inspecting: FieldLogRowModel?
    /// The CRUISE tree being pushed onto TreeDetailScreen. nil = nothing
    /// pushed. Held as an id rather than a row because the destination reads
    /// the tree back out of the cruise store — the row here is a flattened
    /// three-column projection, not the record.
    @State private var openingCruiseTree: UUID?
    /// The row a swipe asked to delete, held until the cruiser confirms.
    /// EVERY row a swipe reaches goes through here (see `requestDelete`).
    @State private var pendingDelete: FieldLogRowModel?
    /// A "measure again" the detail sheet asked for. Held here, not in the
    /// sheet, because the scan is a full-screen cover: it has to present
    /// AFTER the sheet has gone, from the screen the sheet was raised from.
    /// `pending` is the handoff (set while the sheet is still closing), the
    /// sheet's onDismiss moves it into `rescan` — same two-step the map
    /// home's measure chooser uses so the cover doesn't fight the sheet's
    /// dismissal animation.
    @State private var pendingRescan: FieldLogRescan?
    @State private var rescan: FieldLogRescan?
    /// "Edit plot", tapped in the plot mini-map's enlarged view from a
    /// re-measure launched here (FIELD REPORT 12). The scan screens only
    /// OFFER Edit when a host hands them somewhere to go, and this host used
    /// to hand them nothing — so the same enlarged view that offered Edit
    /// everywhere else offered only Close when the cruiser reached it from
    /// the field log. Android has never had the hole: its scan screens build
    /// the destination themselves and fall back to the quick sampling
    /// screen, which is exactly where this one points.
    @State private var rescanPlotSetup = false
    /// The "add a tree" the toolbar or the empty state raised. nil = closed.
    @State private var newTree: FieldLogNewTree?

    // MARK: Bulk re-file (see TreeMove.swift and QuickMove.swift)

    /// What the cruiser has ticked, or nil when the log is NOT in selection
    /// mode. nil vs. empty is load-bearing: an empty set is "selecting,
    /// nothing ticked yet" and still shows the bar, so the way out stays on
    /// screen after the last row is un-ticked.
    ///
    /// It carries its WORLD because the two worlds have different
    /// destinations: a cruise tree is re-parented to a cruise plot inside a
    /// project, a quick reading is re-filed under a quick-measure plot, and
    /// nothing can go to both. The gesture, the bar, the wording and the
    /// confirmation shape are shared — see `selectionBar` — so the two read
    /// as one mode with one answer to "where is this going".
    private enum Selection: Equatable {
        case cruise(Set<UUID>)
        /// Quick rows are keyed by `FieldLogRowModel.id`, not by entry id: a
        /// row is a TREE, and it is the tree that moves.
        case quick(Set<String>)
    }
    @State private var selection: Selection?
    @State private var pickingDestination = false
    /// The quick rows handed to `quickMoveFlow`. Non-nil while that move is
    /// in progress; the flow owns the picker, the confirmation and the
    /// report.
    @State private var quickMoveRequest: [QuickMoveRow]?
    /// The worked-out move, held while the confirmation is up. Nothing has
    /// been written at this point.
    @State private var pendingPlan: TreeMovePlan?
    /// A finished move that did NOT fully succeed. A clean move reports
    /// itself by the rows appearing under their new heading.
    @State private var moveResult: TreeMoveOutcome?
    /// The destination could not be read, so no move was planned.
    @State private var planFailure: String?
    /// Every picked tree was already in the chosen plot.
    @State private var nothingToMove: String?

    /// `scope` defaults to everything; the cruise project sheet passes the
    /// plot in hand so the log opens on it.
    public init(scope: FieldLogScope = .everything) {
        _scope = State(initialValue: scope)
    }

    /// Quick-measure rows in scope. `.everything` is every plot, a quick
    /// plot is that plot, and a CRUISE scope is none of them — a cruise
    /// project has no quick plots under it, and pretending otherwise is
    /// exactly the conflation this feature exists to avoid.
    private var quickSections: [FieldLogSection] {
        let entries: [QuickMeasureEntry]
        switch scope {
        case .everything:            entries = history.entries
        case .quickPlot(let id):     entries = history.entries.filter { $0.plotID == id }
        case .cruiseProject, .cruisePlot: return []
        }
        // One section per quick plot, so a heading names the plot every row
        // under it belongs to. Entries whose plot row is missing (a plot
        // deleted out from under them) are grouped under their own id and
        // labelled with what is actually known, never re-homed silently.
        var order: [UUID?] = []
        var grouped: [UUID?: [QuickMeasureEntry]] = [:]
        for entry in entries {
            if grouped[entry.plotID] == nil { order.append(entry.plotID) }
            grouped[entry.plotID, default: []].append(entry)
        }
        return order.compactMap { plotID -> FieldLogSection? in
            guard let group = grouped[plotID], !group.isEmpty else { return nil }
            let rows = FieldLogRowModel.rows(from: group)
            return FieldLogSection(
                id: "q|\(plotID?.uuidString ?? "-")",
                projectLabel: FieldLogWords.noProject,
                plotLabel: plotID.flatMap { history.plot(id: $0)?.name }
                    ?? FieldLogWords.unknownPlot,
                quickRows: rows,
                cruiseRows: [],
                latest: group.map(\.createdAt).max() ?? Date.distantPast)
        }
        .sorted { $0.latest > $1.latest }
    }

    private var cruiseSections: [FieldLogSection] {
        cruiseFeed.sections(for: scope)
    }

    private var sections: [FieldLogSection] {
        (quickSections + cruiseSections).sorted { $0.latest > $1.latest }
    }

    /// True when the whole log — both worlds — holds nothing at all, which
    /// is the only case the "no readings yet" empty state describes. A scope
    /// that merely happens to be empty gets `FieldLogWords.emptyScope`
    /// instead, so "you have measured nothing" and "nothing in THIS plot"
    /// never read the same.
    private var logIsEmpty: Bool {
        history.entries.isEmpty
            && cruiseFeed.rowsByPlot.values.allSatisfy(\.isEmpty)
            && cruiseFeed.failure == nil
    }

    public var body: some View {
        VStack(spacing: 0) {
            // The count of what is ticked, and the way out, PINNED above the
            // list rather than parked in a row of it — a selection the
            // cruiser has scrolled away from is exactly the hidden mode this
            // must not become.
            if let ticked = selection { selectionBar(ticked) }
            moveHost
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .onAppear { cruiseFeed.load(environment: environment) }
        // Leaving the screen ends selection mode. A ticked set that survived
        // a pop would re-appear over a list the cruiser has since filtered,
        // pointing at trees they can no longer see.
        .onDisappear { selection = nil }
        // Same argument for the filter: changing scope hides rows, and a
        // tick on a row that is no longer on screen still counts toward the
        // move. The bar would say "12 selected" over a list of three.
        .onChange(of: scope) { _, _ in selection = nil }
        .sheet(isPresented: $choosingScope) {
            FieldLogScopeSheet(
                scope: $scope,
                quickPlots: history.plots,
                projects: cruiseFeed.projects,
                plotsByProject: cruiseFeed.plotsByProject)
        }
        .navigationTitle("Field log")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            // Ungated, unlike Export: the case this exists for is a log with
            // nothing in it yet and a stem the scan would not lock.
            ToolbarItem(placement: .primaryAction) {
                Button {
                    startNewTree()
                } label: {
                    Label("New tree", systemImage: "plus")
                        .foregroundStyle(ForestixPalette.primary)
                }
                .accessibilityIdentifier("fieldLog.newTree")
            }
            // Export writes the QUICK-MEASURE tables. Under a cruise scope
            // none of the rows on screen are in them, so the button is not
            // offered: a file that silently holds different trees than the
            // list above it is worse than no button. The cruise bundle has
            // its own "Export all" on the project screen.
            if quickWorldVisible, !history.entries.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button {
                            shareURL = history.exportCSV()
                        } label: {
                            Label("CSV (single file)", systemImage: "doc.text")
                        }
                        Button {
                            shareURL = history.exportBundle(
                                logRule: settings.logRule)
                        } label: {
                            Label("All tables (zip of 5 CSVs)", systemImage: "doc.zipper")
                        }
                    } label: {
                        Label("Export", systemImage: "square.and.arrow.up")
                            .foregroundStyle(ForestixPalette.primary)
                    }
                    .accessibilityIdentifier("fieldLog.exportMenu")
                }
            }
        }
        .sheet(item: $newTree) { request in
            FieldLogNewTreeSheet(
                request: request,
                unitSystem: settings.unitSystem,
                onCreate: { name, species, dbhCm, heightM in
                    createTree(request, treeName: name, speciesCode: species,
                               dbhCm: dbhCm, heightM: heightM)
                })
                .environmentObject(history)
                .environmentObject(settings)
        }
        .sheet(item: $inspecting, onDismiss: {
            rescan = pendingRescan
            pendingRescan = nil
        }) { row in
            FieldLogDetailSheet(
                rowID: row.id,
                unitSystem: settings.unitSystem,
                onRemeasure: { request in
                    pendingRescan = request
                    inspecting = nil
                })
                .environmentObject(history)
                .environmentObject(settings)
        }
        #if os(iOS)
        .fullScreenCover(item: $rescan) { request in
            NavigationStack {
                rescanCover(request)
                    // Presented from INSIDE the rescan cover, the way the map
                    // home presents its own: a cover raised on the field log
                    // itself would be behind the scan screen it was asked for.
                    .fullScreenCover(isPresented: $rescanPlotSetup) {
                        rescanPlotSetupCover
                    }
            }
        }
        #endif
        // EVERY swipe asks, and names what it is about to take.
        //
        // This used to confirm only for a row carrying several readings and
        // delete a single-reading row on the spot. Most rows ARE a single
        // reading, so in practice a swipe — a cheap, easily-misfired gesture
        // on a list the cruiser scrolls one-handed — destroyed a measurement
        // with nothing in the way. What it destroys is field data: getting it
        // back means walking to the tree again, and in an accuracy-validation
        // set the tree that gets re-measured is not the same observation.
        // One tap of friction is nothing against that.
        .confirmationDialog(
            pendingDelete.map { "Delete \($0.title)?" } ?? "",
            isPresented: Binding(get: { pendingDelete != nil },
                                 set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            if let row = pendingDelete {
                Button(FieldLogRowModel.deleteActionTitle(row.entries.count),
                       role: .destructive) {
                    for entry in row.entries { history.delete(id: entry.id) }
                    pendingDelete = nil
                }
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            if let row = pendingDelete {
                Text(row.deleteWarning)
            }
        }
        #if os(iOS)
        .sheet(item: Binding(
            get: { shareURL.map(ShareWrapper.init) },
            set: { shareURL = $0?.url })
        ) { wrapper in
            FieldLogShareSheet(url: wrapper.url)
        }
        #endif
    }

    // MARK: - Bulk re-file (see TreeMove.swift)

    /// The list plus BOTH moves. Split from `body`'s modifier chain, and
    /// then split again between the two worlds, because a chain this long is
    /// a type-checker cost on its own.
    private var moveHost: some View {
        cruiseMoveHost
            // The quick-measure move owns its picker, its confirmation and
            // its report — see QuickMove.swift. The log only says which rows.
            .quickMoveFlow(request: $quickMoveRequest) { _ in
                // Selection mode ends whatever the outcome: the rows the
                // cruiser ticked have either moved (and are now under a
                // different heading) or been named in the report, and either
                // way leaving them ticked would point at a list they can no
                // longer read.
                selection = nil
            }
    }

    private var cruiseMoveHost: some View {
        Group {
            if logIsEmpty {
                emptyState
            } else {
                populatedList
            }
        }
        .sheet(isPresented: $pickingDestination) {
            TreeMovePicker(projects: cruiseFeed.projects,
                           plotsByProject: cruiseFeed.plotsByProject,
                           onPick: { choose($0) })
        }
        // Names what will move and where BEFORE anything is written. This
        // rewrites which project a piece of field data was recorded in, and
        // there is no undo behind it.
        .alert(TreeMoveWords.confirmTitle,
               isPresented: Binding(
                   get: { pendingPlan != nil },
                   set: { if !$0 { pendingPlan = nil } }),
               presenting: pendingPlan) { plan in
            Button(TreeMoveWords.confirmAction(plan.movers.count)) { commit(plan) }
            Button(TreeMoveWords.cancel, role: .cancel) { pendingPlan = nil }
        } message: { plan in
            Text(TreeMoveWords.confirmMessage(
                destination: plan.destination.label,
                movers: plan.movers.count,
                alreadyThere: plan.alreadyThere,
                renumbered: plan.renumbered.count,
                destinationClosed: plan.destination.isClosed))
        }
        // A PARTIAL move is reported as a partial move, naming every row
        // that stayed put and why. Nothing here ever says "done" over rows
        // that did not land.
        .alert(TreeMoveWords.failureTitle,
               isPresented: Binding(
                   get: { moveResult != nil },
                   set: { if !$0 { moveResult = nil } }),
               presenting: moveResult) { _ in
            Button("OK", role: .cancel) { moveResult = nil }
        } message: { outcome in
            Text(TreeMoveWords.failureMessage(
                moved: outcome.movedCount,
                total: outcome.attempted,
                destination: outcome.destination.label,
                failures: outcome.failures.map(\.line)))
        }
        .alert(TreeMoveWords.planFailedTitle,
               isPresented: Binding(
                   get: { planFailure != nil },
                   set: { if !$0 { planFailure = nil } })) {
            Button("OK", role: .cancel) { planFailure = nil }
        } message: {
            Text(planFailure ?? "")
        }
        .alert(TreeMoveWords.confirmTitle,
               isPresented: Binding(
                   get: { nothingToMove != nil },
                   set: { if !$0 { nothingToMove = nil } })) {
            Button("OK", role: .cancel) { nothingToMove = nil }
        } message: {
            Text(nothingToMove ?? "")
        }
    }

    /// What is ticked, where it can go, and the way out — all three on one
    /// line, always visible while selecting.
    ///
    /// ONE bar for both worlds. Only the destination differs, so only the
    /// Move button's words differ; the count, the Cancel and the layout are
    /// the same object, because two selection modes on one list that behaved
    /// differently would be worse than the gap they close.
    private func selectionBar(_ selection: Selection) -> some View {
        let count: Int
        let moveTitle: String
        switch selection {
        case .cruise(let ids): count = ids.count; moveTitle = TreeMoveWords.moveButton
        case .quick(let ids):  count = ids.count; moveTitle = QuickMoveWords.moveButton
        }
        return HStack(spacing: ForestixSpace.sm) {
            Text(TreeMoveWords.selectionCount(count))
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .accessibilityIdentifier("fieldLog.selectionCount")
            Spacer(minLength: 4)
            Button(moveTitle) { startMove() }
                .font(ForestixType.bodyBold)
                .foregroundStyle(count == 0
                                 ? ForestixPalette.textTertiary
                                 : ForestixPalette.primary)
                .buttonStyle(.plain)
                .disabled(count == 0)
                .accessibilityIdentifier("fieldLog.moveTo")
            Button(TreeMoveWords.cancel) { self.selection = nil }
                .font(ForestixType.body)
                .foregroundStyle(ForestixPalette.textSecondary)
                .buttonStyle(.plain)
                .accessibilityIdentifier("fieldLog.endSelection")
        }
        .padding(.horizontal, ForestixSpace.md)
        .padding(.vertical, ForestixSpace.sm)
        .frame(maxWidth: .infinity)
        .background(ForestixPalette.surface)
    }

    /// Move tapped. Each world raises its own destination picker.
    private func startMove() {
        switch selection {
        case .cruise:            pickingDestination = true
        case .quick:             quickMoveRequest = selectedQuickRows
        case .none:              break
        }
    }

    /// The ticked cruise ids in the order they appear on screen. `selection`
    /// holds a Set, and the tree-number allocation in `TreeMover.plan` walks
    /// the list in order — reading it back off the sections keeps that
    /// allocation deterministic and in the order the cruiser is looking at.
    private var selectedIDsInOrder: [UUID] {
        guard case .cruise(let ids) = selection else { return [] }
        return sections.flatMap(\.cruiseRows).map(\.id)
            .filter { ids.contains($0) }
    }

    /// The ticked quick rows, on screen order, as the mover wants them. Same
    /// reason as above: `QuickMover.plan` walks them in order when it works
    /// out which tree numbers the destination has left.
    private var selectedQuickRows: [QuickMoveRow] {
        guard case .quick(let ids) = selection else { return [] }
        return sections.flatMap(\.quickRows)
            .filter { ids.contains($0.id) }
            .map { QuickMoveRow($0) }
    }

    /// The ticked cruise ids, or nil when the log is not selecting cruise
    /// rows. Read by the rows to draw their tick boxes.
    private var cruiseSelection: Set<UUID>? {
        if case .cruise(let ids) = selection { return ids }
        return nil
    }

    private var quickSelection: Set<String>? {
        if case .quick(let ids) = selection { return ids }
        return nil
    }

    /// A long press on a cruise row. The first one opens selection mode with
    /// that row ticked; later ones just toggle, so a press that lands on an
    /// already-selected list behaves like the tap beside it. A press that
    /// lands while the OTHER world is being selected switches worlds rather
    /// than doing nothing — the two cannot go to one destination, and a
    /// long press that silently did nothing would read as a bug.
    private func longPressCruise(_ id: UUID) {
        if case .cruise = selection {
            toggleCruise(id)
        } else {
            HapticFeedback.play(.success)
            selection = .cruise([id])
        }
    }

    private func toggleCruise(_ id: UUID) {
        guard case .cruise(var ids) = selection else { return }
        if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }
        selection = .cruise(ids)
    }

    /// The quick half of the same gesture. See `longPressCruise`.
    private func longPressQuick(_ id: String) {
        if case .quick = selection {
            toggleQuick(id)
        } else {
            HapticFeedback.play(.success)
            selection = .quick([id])
        }
    }

    private func toggleQuick(_ id: String) {
        guard case .quick(var ids) = selection else { return }
        if ids.contains(id) { ids.remove(id) } else { ids.insert(id) }
        selection = .quick(ids)
    }

    /// Destination picked. Plans the move — reading the destination plot's
    /// existing tree numbers — and raises the confirmation. NOTHING is
    /// written here.
    private func choose(_ destination: TreeMoveDestination) {
        pickingDestination = false
        do {
            let plan = try TreeMover.plan(treeIDs: selectedIDsInOrder,
                                          to: destination,
                                          environment: environment)
            if plan.hasWork {
                pendingPlan = plan
            } else {
                nothingToMove = TreeMoveWords.nothingToMove(
                    destination: destination.label)
            }
        } catch {
            // The free numbers in the destination are unknown, so the move
            // cannot be planned without guessing them. Say so; write nothing.
            planFailure = TreeMoveWords.planFailedMessage(
                reason: error.localizedDescription)
        }
    }

    private func commit(_ plan: TreeMovePlan) {
        let outcome = TreeMover.apply(plan, environment: environment)
        pendingPlan = nil
        selection = nil
        // Re-read: the moved rows belong under a different heading now, and
        // a stale feed would go on showing them under the old one.
        cruiseFeed.load(environment: environment)
        if !outcome.isClean { moveResult = outcome }
    }

    // MARK: - Populated list

    private var populatedList: some View {
        List {
            Section {
                scopeBar
                    .listRowInsets(EdgeInsets(top: ForestixSpace.sm,
                                              leading: ForestixSpace.md,
                                              bottom: ForestixSpace.sm,
                                              trailing: ForestixSpace.md))
                    .listRowBackground(ForestixPalette.surface)
            }

            // Plot summary card — BA / TPA / QMD + species mix for the
            // active QUICK plot. It reads the quick-measure
            // store, so it is shown only while the quick world is on screen:
            // under a cruise scope it would be a card about a different plot
            // than every row beneath it.
            if quickWorldVisible,
               let plotID = history.activePlotID,
               let plot = history.plot(id: plotID) {
                let plotEntries = history.entries(forPlot: plotID)
                if plotEntries.count >= 1 {
                    Section {
                        PlotSummaryCard(
                            plot: plot,
                            entries: plotEntries,
                            unitSystem: settings.unitSystem,
                            logRule: settings.logRule,
                            areaUnit: settings.unitSystem.areaUnit)
                            .listRowInsets(EdgeInsets(
                                top: ForestixSpace.sm,
                                leading: ForestixSpace.md,
                                bottom: ForestixSpace.sm,
                                trailing: ForestixSpace.md))
                            .listRowBackground(Color.clear)
                    }
                }
            }
            if quickWorldVisible {
                Section {
                    summaryHeader
                        .listRowInsets(EdgeInsets(top: ForestixSpace.sm,
                                                  leading: ForestixSpace.md,
                                                  bottom: ForestixSpace.sm,
                                                  trailing: ForestixSpace.md))
                        .listRowBackground(ForestixPalette.surface)
                    if history.isNearCapacity {
                        capacityBanner
                            .listRowInsets(EdgeInsets(top: ForestixSpace.xs,
                                                      leading: ForestixSpace.md,
                                                      bottom: ForestixSpace.xs,
                                                      trailing: ForestixSpace.md))
                            .listRowBackground(ForestixPalette.surface)
                    }
                }
            }

            // The cruise store refused to read. Say so where the missing
            // rows would have been — an empty cruise side and an unreadable
            // database look identical, and only one of them means "no trees".
            if let failure = cruiseFeed.failure {
                Section {
                    noticeBanner(failure)
                        .listRowInsets(EdgeInsets(top: ForestixSpace.xs,
                                                  leading: ForestixSpace.md,
                                                  bottom: ForestixSpace.xs,
                                                  trailing: ForestixSpace.md))
                        .listRowBackground(ForestixPalette.surface)
                }
            }

            if sections.isEmpty {
                Section {
                    Text(FieldLogWords.emptyScope)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .listRowBackground(ForestixPalette.surface)
                }
            }

            ForEach(sections) { section in
                Section {
                    // Quick rows keep every path they had: tap to inspect,
                    // swipe to delete, re-measure from the detail sheet.
                    // Press and hold now enters selection mode, exactly as it
                    // does on a cruise row, and once in it the ordinary tap
                    // TOGGLES instead of opening — so a cruiser ticking a
                    // dozen readings never leaves the list by accident.
                    ForEach(section.quickRows) { row in
                        Button {
                            if quickSelection == nil {
                                inspecting = row
                            } else {
                                toggleQuick(row.id)
                            }
                        } label: {
                            FieldLogRow(row: row,
                                        unitSystem: settings.unitSystem,
                                        selecting: quickSelection != nil,
                                        isSelected: quickSelection?.contains(row.id) == true)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(ForestixPalette.surface)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                requestDelete(row)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                        .onLongPressGesture { longPressQuick(row.id) }
                    }
                    // Cruise rows OPEN: the tap pushes TreeDetailScreen,
                    // which owns that store's writes. No inline editor and
                    // no swipe-delete here — a second path that saves a
                    // MEASUREMENT onto the same row is how two numbers for
                    // one tree get created, and that argument is untouched.
                    // Press and hold enters selection mode; once in it the
                    // ordinary tap TOGGLES instead of pushing, so a cruiser
                    // ticking twelve rows never leaves the list by accident.
                    ForEach(section.cruiseRows) { row in
                        Button {
                            if cruiseSelection == nil {
                                openingCruiseTree = row.id
                            } else {
                                toggleCruise(row.id)
                            }
                        } label: {
                            FieldLogCruiseRowView(
                                row: row,
                                unitSystem: settings.unitSystem,
                                selecting: cruiseSelection != nil,
                                isSelected: cruiseSelection?.contains(row.id) == true)
                        }
                        .buttonStyle(.plain)
                        .listRowBackground(ForestixPalette.surface)
                        .onLongPressGesture { longPressCruise(row.id) }
                    }
                    if section.isEmpty {
                        Text(FieldLogWords.emptyScope)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .listRowBackground(ForestixPalette.surface)
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 4) {
                        // The heading is where a row says which project and
                        // which plot it belongs to — it is true of every row
                        // beneath it, and it costs the table no column width.
                        Text(section.heading)
                            .font(ForestixType.sectionHead)
                            .tracking(FieldLogTable.headerTracking)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .lineLimit(2)
                        FieldLogColumnHeader()
                    }
                    .textCase(nil)
                }
            }

            // Discoverability for the long press — one line per world, each
            // shown only when there is a row of that world on screen to
            // press, and neither while already selecting: the bar above is
            // saying what to do then.
            if selection == nil, sections.contains(where: { !$0.isEmpty }) {
                Section {
                    if sections.contains(where: { !$0.quickRows.isEmpty }) {
                        hintLine(QuickMoveWords.hint)
                    }
                    if sections.contains(where: { !$0.cruiseRows.isEmpty }) {
                        hintLine(TreeMoveWords.hint)
                    }
                }
            }
        }
        #if os(iOS)
        .listStyle(.insetGrouped)
        #endif
        .scrollContentBackground(.hidden)
        // Declared on the List, not inside the ForEach: a destination
        // registered from a lazy row is not reliably there when the push
        // happens. Same shape as ProjectBrowserScreen's stand-summary push,
        // and it resolves against whichever stack the log was pushed onto.
        .navigationDestination(item: $openingCruiseTree) { id in
            TreeDetailByIDScreen(treeID: id)
        }
    }

    /// One line of press-and-hold discoverability under the list.
    private func hintLine(_ text: String) -> some View {
        Text(text)
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
            .listRowBackground(ForestixPalette.surface)
    }

    /// True while quick-measure rows can appear under the current scope.
    private var quickWorldVisible: Bool {
        switch scope {
        case .everything, .quickPlot:     return true
        case .cruiseProject, .cruisePlot: return false
        }
    }

    // MARK: - Scope bar

    /// What the log is showing, and the way to change it.
    private var scopeBar: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                choosingScope = true
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                        .foregroundStyle(ForestixPalette.primary)
                    Text(scopeLabel)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 0)
                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("fieldLog.scope")
            // The cruiser's own question was "서브플롯? 스탠드?? 플롯??" — this
            // is the answer, in one sentence, where they will be looking.
            Text(FieldLogWords.worldsCaption)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var scopeLabel: String {
        switch scope {
        case .everything:
            return FieldLogWords.everything
        case .cruiseProject(let id):
            guard let project = cruiseFeed.projects.first(where: { $0.id == id })
            else { return FieldLogWords.everything }
            return project.name
        case .cruisePlot(let id):
            guard let plot = cruiseFeed.plot(id: id) else {
                return FieldLogWords.everything
            }
            let projectName = cruiseFeed.project(ofPlot: id)?.name
            let plotName = FieldLogWords.plotName(number: plot.plotNumber)
            return projectName.map { $0 + FieldLogWords.headingSeparator + plotName }
                ?? plotName
        case .quickPlot(let id):
            return history.plot(id: id)?.name ?? FieldLogWords.unknownPlot
        }
    }

    private func noticeBanner(_ text: String) -> some View {
        HStack(spacing: ForestixSpace.xs) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(ForestixPalette.confidenceWarn)
            Text(text)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
    }

    // MARK: - Measure again

    #if os(iOS)
    /// The scan the cruiser asked to re-run, wired so the reading lands on
    /// the tree it was launched from. Accept goes through
    /// `replaceReading`, so the tree ends up with ONE diameter and ONE
    /// height rather than a second copy the log would never show.
    @ViewBuilder
    private func rescanCover(_ request: FieldLogRescan) -> some View {
        switch request.kind {
        case .dbh:
            DBHScanScreen(
                viewModel: DBHScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.replaceReading(QuickMeasureEntry(
                        kind: .dbh,
                        value: Double(result.diameterCm),
                        sigma: result.method == .manualVisual
                            ? nil : Double(result.sigmaRmm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: request.treeNumber,
                        treeName: request.treeName,
                        plotID: request.plotID,
                        // The chip is SEEDED with the row's code now, so this
                        // is the row's species until the cruiser changes it —
                        // and a cruiser who CLEARS it means it, which the old
                        // `?? request.speciesCode` fallback silently undid.
                        // Android has always written the chip verbatim.
                        speciesCode: meta.speciesCode,
                        position: meta.position ?? .dbh,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath,
                        captureMode: meta.captureMode,
                        // A truth typed on the scan screen is the cruiser's
                        // newest word on this tree and wins; otherwise the
                        // tape reading did not change because the scan did,
                        // and a re-measure that dropped it would quietly cost
                        // the validation study its comparison.
                        truth: meta.truth ?? request.truth,
                        // Provenance follows the value it belongs to: a truth
                        // typed on the scan screen is typed (nil), while one
                        // riding across from the reading being re-measured
                        // keeps the source it already had.
                        truthSource: meta.truth != nil ? nil : request.truthSource))
                    rescan = nil
                    return true
                },
                projectID: nil,
                quickTreeNumber: request.treeNumber,
                // Seed the species chip from the row being re-measured, as
                // Android does — otherwise the cruiser retypes a code the
                // accept handler would have restored behind their back.
                initialSpeciesCode: request.speciesCode,
                onEditPlot: { rescanPlotSetup = true })
        case .height:
            HeightScanScreen(
                viewModel: HeightScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.replaceReading(QuickMeasureEntry(
                        kind: .height,
                        value: Double(result.heightM),
                        sigma: result.sigmaHm.map(Double.init),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: request.treeNumber,
                        treeName: request.treeName,
                        plotID: request.plotID,
                        // Seeded chip, written verbatim — see the diameter
                        // cover.
                        speciesCode: meta.speciesCode,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath,
                        // See the diameter cover: a truth typed on the scan
                        // screen wins, else the row's own tape value rides
                        // across the re-measure.
                        truth: meta.truth ?? request.truth,
                        // Provenance follows the value it belongs to: a truth
                        // typed on the scan screen is typed (nil), while one
                        // riding across from the reading being re-measured
                        // keeps the source it already had.
                        truthSource: meta.truth != nil ? nil : request.truthSource))
                    rescan = nil
                    return true
                },
                onCrown: { widthM, heightM in
                    // Crown is measured inside the Height session; it is a
                    // separate reading of the same tree, so it is appended
                    // rather than replacing the height.
                    history.append(QuickMeasureEntry(
                        kind: .crown,
                        value: widthM,
                        secondaryValue: heightM,
                        sigma: nil,
                        confidenceRaw: "green",
                        method: "ar.crown.dh",
                        treeNumber: request.treeNumber,
                        // NAMED, like every other crown this app writes
                        // (map home, Android height screen). A nameless crown
                        // row exports a blank tree_name for a tree whose other
                        // readings carry one.
                        treeName: request.treeName,
                        plotID: request.plotID))
                },
                projectID: nil,
                treeNumber: request.treeNumber,
                // Seed the species chip from the row being re-measured, as
                // Android does.
                initialSpeciesCode: request.speciesCode,
                onEditPlot: { rescanPlotSetup = true })
                .environmentObject(history)
                .environmentObject(settings)
        }
    }

    /// Plot setup re-opened from a re-measure's mini-map (FIELD REPORT 12).
    /// The quick sampling screen, exactly as the measure chooser opens it —
    /// a re-measure launched from the field log is a QUICK reading, so the
    /// ring it is measuring into is the quick sampling ring. Same destination
    /// Android's scan screens fall back to (`Routes.SAMPLING`).
    private var rescanPlotSetupCover: some View {
        NavigationStack {
            SamplingPlotScreen()
                .environmentObject(history)
                .environmentObject(settings)
        }
    }
    #endif

    // MARK: - A tree the sensors never read

    /// The plot this screen is reporting on — the one whose summary card sits
    /// at the top of the log — resolved through the default plot the same way
    /// every entry's `plotID` is read.
    ///
    /// A hand-entered stem joins the plot the cruiser is LOOKING at. It is
    /// captured when the sheet opens rather than read again on Create, so a
    /// plot switched elsewhere in the app mid-typing cannot claim the tree.
    private var shownPlotID: UUID? {
        history.activePlotID ?? history.defaultPlotID()
    }

    private func startNewTree() {
        let plotID = shownPlotID
        newTree = FieldLogNewTree(
            // The measure chooser's own rule — max(existing) + 1 across the
            // log — so a hand-entered stem cannot land on a number a scan has
            // already used, in this plot or any other.
            treeNumber: history.suggestedNextTreeNumber,
            plotID: plotID,
            plotName: plotID.flatMap { history.plot(id: $0)?.name },
            // Same suggestion the chooser offers: the successor of the highest
            // name in the series the cruiser is using, or blank if they have
            // never named a tree.
            suggestedName: history.suggestedNextTreeName ?? "",
            // And the same species suggestion, from the same place in the
            // history — a hand-entered stem stands in the same stand as the
            // scanned ones either side of it.
            suggestedSpecies: history.suggestedNextSpeciesCode)
    }

    /// Writes the tree.
    ///
    /// Both readings go through `QuickMeasureEntry.typed` — the SAME factory
    /// the row editor's "this kind has no reading yet" branch calls — so σ is
    /// nil, capture_mode is "typed" and the method is the manual arm. A stem
    /// entered here is stamped exactly like a number typed into an existing
    /// row, and neither can be read back as a scan.
    private func createTree(_ request: FieldLogNewTree,
                            treeName: String?,
                            speciesCode: String?,
                            dbhCm: Double?,
                            heightM: Double?) {
        let readings: [(QuickMeasureEntry.Kind, Double?)] = [(.dbh, dbhCm),
                                                             (.height, heightM)]
        for (kind, value) in readings {
            guard let value else { continue }
            history.append(.typed(kind: kind,
                                  value: value,
                                  treeNumber: request.treeNumber,
                                  treeName: treeName,
                                  plotID: request.plotID,
                                  speciesCode: speciesCode))
        }
    }

    /// EVERY swipe asks first — see the dialog attached to `body` for why a
    /// single-reading row is the dangerous case, not the safe one.
    private func requestDelete(_ row: FieldLogRowModel) {
        pendingDelete = row
    }

    // MARK: - Summary header

    private var summaryHeader: some View {
        let now = Date()
        let cal = Calendar.current
        let todayCount = history.entries.filter {
            cal.isDate($0.createdAt, inSameDayAs: now)
        }.count
        let lastAgo = history.entries.first.map {
            compactRelativeAgo($0.createdAt, now: now)
        } ?? "—"

        return HStack(alignment: .firstTextBaseline, spacing: ForestixSpace.lg) {
            summaryCell(value: "\(history.entries.count)", label: "TOTAL")
            summaryCell(value: "\(todayCount)",            label: "TODAY")
            summaryCell(value: lastAgo,                     label: "LAST")
            Spacer(minLength: 0)
        }
    }

    private func summaryCell(value: String, label: String) -> some View {
        // Single-line throughout: "LAST" can carry a date ("Mar 14"), which
        // is what would push this row into wrapping first.
        //
        // FIELD REPORT — the values were `dataLarge` (26 pt) and read as
        // oversized beside everything else on the screen. `data` (17 pt) is
        // the next step of the same scale and the one the log's row values
        // and the plot-summary card already use.
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(FieldLogTable.valueScaleFloor)
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(FieldLogTable.headerTracking)
                .foregroundStyle(ForestixPalette.textTertiary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(FieldLogTable.labelScaleFloor)
        }
    }

    // MARK: - Capacity banner

    private var capacityBanner: some View {
        HStack(spacing: ForestixSpace.xs) {
            Image(systemName: "exclamationmark.triangle")
                .foregroundStyle(ForestixPalette.confidenceWarn)
            Text("Log nearly full. Export soon to free space.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .padding(ForestixSpace.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.control,
                             style: .continuous)
                .fill(ForestixPalette.confidenceWarn.opacity(0.12))
        )
    }

    // MARK: - Empty state

    private var emptyState: some View {
        VStack(spacing: ForestixSpace.md) {
            Spacer()
            Image(systemName: "tray")
                .font(.system(size: 34, weight: .light))
                .foregroundStyle(ForestixPalette.textTertiary)
            Text("No readings yet")
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("Accept a scan in a measurement tool and it'll land here.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, ForestixSpace.xl)
            // A scan is not the only way in. The stem the sensors refused is
            // still a stem, and this is the door for it — repeated here
            // because an empty log is exactly where a cruiser looks for one.
            Button("Add a tree by hand") { startNewTree() }
                .buttonStyle(.borderedProminent)
                .tint(ForestixPalette.primary)
                .accessibilityIdentifier("fieldLog.emptyNewTree")
            Spacer()
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Row model

/// One row of the log: everything recorded against one tree, or a single
/// reading that was never attached to one.
///
/// GROUPING IS BY (plot, tree number), not by tree number alone. Tree
/// numbering restarts on each plot, so keying on the number by itself would
/// have merged plot 1's tree 4 with plot 2's tree 4 into a row claiming a
/// diameter and a height that came off two different trees.
public struct FieldLogRowModel: Identifiable, Equatable {

    public enum Subject: Equatable {
        case tree(number: Int)
        /// A reading with no tree number — a sampling-plot record, or a
        /// standalone crown / distance measurement.
        case loose(kind: QuickMeasureEntry.Kind)
    }

    public let id: String
    public let subject: Subject
    /// The cruiser's name for this tree, when they gave it one. nil falls
    /// back to "#treeNumber" through `treeLabel`.
    public let treeName: String?
    /// Newest diameter and height on this tree. Earlier re-measurements
    /// stay in `entries` and are listed in the detail sheet.
    public let dbh: QuickMeasureEntry?
    public let height: QuickMeasureEntry?
    /// Every reading behind this row, newest first.
    public let entries: [QuickMeasureEntry]
    /// Sort key — the most recent reading in the group.
    public let latest: Date

    /// What the TREE column shows. nil for a row that belongs to no tree.
    public var treeLabel: String? {
        if let treeName { return treeName }
        if case .tree(let n) = subject { return "#\(n)" }
        return nil
    }

    public var title: String {
        if let treeName { return treeName }
        switch subject {
        case .tree(let n):     return "Tree #\(n)"
        case .loose(let kind): return FieldLogRowModel.kindWord(kind)
        }
    }

    /// The destructive button, counting what it will take. Singular when
    /// there is one — "Delete 1 readings" is the sentence a cruiser reads
    /// while deciding whether to destroy a measurement. Android's
    /// `deleteActionTitle` returns the same strings.
    static func deleteActionTitle(_ count: Int) -> String {
        count == 1 ? "Delete 1 reading" : "Delete \(count) readings"
    }

    /// What a destructive swipe is actually about to remove.
    public var deleteWarning: String {
        let kinds = entries.map { FieldLogRowModel.kindWord($0.kind).lowercased() }
        let listed = Array(Set(kinds)).sorted().joined(separator: " and ")
        return "This removes the \(listed) recorded against it. It cannot be undone."
    }

    static func kindWord(_ kind: QuickMeasureEntry.Kind) -> String {
        switch kind {
        case .dbh:          return "DBH"
        case .height:       return "Height"
        case .crown:        return "Crown"
        case .distance:     return "Dist"
        case .samplingPlot: return "Plot"
        }
    }

    /// The id of the row a TREE's readings collapse into.
    ///
    /// Stated once, because three surfaces have to agree on it: `rows(from:)`
    /// builds it, the map peek resolves a pin to it, and the record sheet
    /// re-resolves itself after a move — which changes the plot half of the
    /// key, and therefore the row's identity. A second hand-written copy of
    /// this format is how one of them silently stops finding the row.
    ///
    /// `plotID` is used RAW, exactly as the reading holds it, so a row's id
    /// is a fact about the entries rather than about the default plot at the
    /// time it was computed.
    public static func treeRowID(plotID: UUID?, treeNumber: Int) -> String {
        "t|\(plotID?.uuidString ?? "-")|\(treeNumber)"
    }

    /// The id of the row a LOOSE reading gets to itself.
    public static func looseRowID(entryID: UUID) -> String {
        "e|\(entryID.uuidString)"
    }

    /// Collapses the flat entry list into rows, newest tree first.
    ///
    /// `entries` arrives newest-first from the history, and that order is
    /// preserved inside each group, so `dbh` / `height` pick up the latest
    /// reading of each kind without a second sort.
    public static func rows(from entries: [QuickMeasureEntry]) -> [FieldLogRowModel] {
        var order: [String] = []
        var grouped: [String: [QuickMeasureEntry]] = [:]

        for entry in entries {
            let key: String
            if let n = entry.treeNumber {
                key = treeRowID(plotID: entry.plotID, treeNumber: n)
            } else {
                // Never merged with anything: one row, this reading.
                key = looseRowID(entryID: entry.id)
            }
            if grouped[key] == nil { order.append(key) }
            grouped[key, default: []].append(entry)
        }

        return order.compactMap { key in
            guard let group = grouped[key], let first = group.first else { return nil }
            let subject: Subject = first.treeNumber
                .map { Subject.tree(number: $0) } ?? .loose(kind: first.kind)
            return FieldLogRowModel(
                id: key,
                subject: subject,
                // Any reading on the tree carries the name; take the first
                // that has one rather than `first`'s, which is the newest and
                // may be a re-measurement recorded before the tree was named.
                treeName: group.compactMap(\.treeName).first,
                dbh: group.first { $0.kind == .dbh },
                height: group.first { $0.kind == .height },
                entries: group,
                latest: group.map(\.createdAt).max() ?? first.createdAt)
        }
    }
}

// MARK: - Measure-again request

/// A "measure again" raised from the detail sheet: which scan to open, and
/// every join key the accepted reading needs to land back on the SAME tree
/// instead of starting a new one — the plot, the tree number, the species
/// already recorded against it, and the ground truth already typed for it.
public struct FieldLogRescan: Identifiable, Equatable {
    public enum Kind: Equatable { case dbh, height }

    public let id = UUID()
    public let kind: Kind
    public let treeNumber: Int
    public let treeName: String?
    public let plotID: UUID?
    public let speciesCode: String?
    public let truth: Double?
    /// How that truth got onto the reading being superseded (a
    /// `QuickMeasureEntry.truthSource` raw, nil = typed against the reading).
    /// It rides along with the value because the two are one fact:
    /// re-labelling a capture-recovered truth as one typed on the scan screen
    /// would claim a provenance the cruiser never gave it.
    public let truthSource: String?
}

// MARK: - New tree

/// A tree the cruiser is entering by hand, raised from the log's toolbar or
/// its empty state.
///
/// Every join key is resolved HERE, once, when the sheet opens — not read
/// again when Create is tapped. The number on screen is the number that gets
/// written, and the plot cannot move under the cruiser while they type.
private struct FieldLogNewTree: Identifiable {
    let id = UUID()
    let treeNumber: Int
    let plotID: UUID?
    /// Shown so the cruiser can see which plot the stem joins. nil when the
    /// log has no plot to name — the readings are then stored with no plot,
    /// exactly as the pre-plot readings are, rather than claiming one.
    let plotName: String?
    let suggestedName: String
    /// The last species seen in the log, offered the same way the name is. It
    /// is the app's guess, never an observation — the sheet draws it dim until
    /// the cruiser picks in the control. nil when nothing in the log has a
    /// species, and then the picker opens unset.
    let suggestedSpecies: String?
}

// MARK: - Typed-measurement rules

/// What a hand-typed diameter or height has to be, in ONE place.
///
/// Two screens now read a number the cruiser typed into a field in the unit
/// system they are working in: the row editor completing a half-measured
/// tree, and the new-tree sheet creating one from nothing. They must accept
/// the same numbers, refuse the same numbers, and warn in the same words —
/// a second copy of these sentences would drift the day one of them changed.
private enum FieldLogTypedInput {

    static func quantity(_ kind: QuickMeasureEntry.Kind) -> TruthInput.Quantity {
        kind == .dbh ? .diameter : .height
    }

    /// The unit the field is typed in — the cruiser's active system,
    /// converted to the metric base on the way in.
    static func unit(_ kind: QuickMeasureEntry.Kind,
                     imperial: Bool) -> TruthInput.Unit {
        TruthInput.defaultUnit(quantity(kind), imperial: imperial)
    }

    /// Placeholder copy is the scan screens' own, so the same field means the
    /// same thing wherever a measurement is typed.
    static func placeholder(_ kind: QuickMeasureEntry.Kind,
                            imperial: Bool) -> String {
        if kind == .dbh {
            return imperial ? "Diameter in inches" : "Diameter in cm"
        }
        return imperial ? "Height in feet" : "Height in metres"
    }

    /// The typed number in metric base units, or nil when the field holds
    /// nothing usable. Never falls back to a stored value — a blank field
    /// means "nothing typed".
    static func parse(_ text: String,
                      kind: QuickMeasureEntry.Kind,
                      imperial: Bool) -> Double? {
        TruthInput.parsePositiveBase(text, unit: unit(kind, imperial: imperial))
    }

    /// A height under the estimator's floor is not a standing tree, so it is
    /// REFUSED rather than warned about. Diameter has no such floor: any
    /// positive number is a stem somebody could have taped.
    static func isBelowHeightFloor(_ value: Double,
                                   kind: QuickMeasureEntry.Kind) -> Bool {
        kind == .height && value < Double(HeightEstimator.minHMeters)
    }

    /// The number this text will actually be STORED as — nil when the field
    /// is blank, unparseable, or holds something the app refuses. nil never
    /// means "use something else"; it means nothing is written.
    static func accepted(_ text: String,
                         kind: QuickMeasureEntry.Kind,
                         imperial: Bool) -> Double? {
        guard let value = parse(text, kind: kind, imperial: imperial),
              !isBelowHeightFloor(value, kind: kind) else { return nil }
        return value
    }

    /// What to say about what is in the field, or nil when there is nothing
    /// to say. A blank field is silent — the cruiser has not typed yet.
    static func warning(_ text: String,
                        kind: QuickMeasureEntry.Kind,
                        imperial: Bool) -> String? {
        guard !TruthInput.normalized(text).isEmpty else { return nil }
        let u = unit(kind, imperial: imperial)
        guard let value = parse(text, kind: kind, imperial: imperial) else {
            return kind == .dbh
                ? "A typed diameter must be a number greater than zero."
                : "A typed height must be a number greater than zero."
        }
        if isBelowHeightFloor(value, kind: kind) {
            // The floor is one physical height; the sentence is written in the
            // unit the field is in, so an imperial cruiser is not handed a
            // metre figure to compare against the feet they just typed.
            let floor = TruthInput.fromBase(Double(HeightEstimator.minHMeters),
                                            unit: u)
            return String(format: "A typed height must be at least %.1f %@.",
                          floor, u.rawValue)
        }
        // Outside the cruising window is a WARNING, not a refusal: the number
        // is the cruiser's own observation. Same wording as every other truth
        // field in the app.
        return TruthInput.warning(base: value, quantity: quantity(kind), unit: u)
    }
}

// MARK: - Table geometry

/// Splits the proposed width across its subviews on fixed WEIGHTS. Both the
/// column header and every row lay out through this, so the columns line up
/// without either side hard-coding a point width — and on a narrow phone the
/// columns give ground proportionally instead of the last one falling off the
/// end.
private struct WeightedColumns: Layout {

    let weights: [CGFloat]
    let spacing: CGFloat

    private func widths(for total: CGFloat) -> [CGFloat] {
        let usable = max(0, total - spacing * CGFloat(max(0, weights.count - 1)))
        let sum = weights.reduce(0, +)
        guard sum > 0 else { return weights.map { _ in 0 } }
        return weights.map { usable * $0 / sum }
    }

    func sizeThatFits(proposal: ProposedViewSize,
                      subviews: Subviews,
                      cache: inout ()) -> CGSize {
        // No width proposed (an "ideal size" query, which List does make while
        // estimating row heights): answer with the content's own width rather
        // than the 10 pt placeholder `replacingUnspecifiedDimensions` returns,
        // which would report a row one line tall of squeezed columns.
        guard let total = proposal.width, total > 0 else {
            let ideal = subviews.map { $0.sizeThatFits(.unspecified) }
            let width = ideal.reduce(0) { $0 + $1.width }
                + spacing * CGFloat(max(0, subviews.count - 1))
            return CGSize(width: width, height: ideal.map(\.height).max() ?? 0)
        }
        let w = widths(for: total)
        let height = zip(subviews, w).map { sub, width in
            sub.sizeThatFits(ProposedViewSize(width: width,
                                              height: proposal.height)).height
        }.max() ?? 0
        return CGSize(width: total, height: height)
    }

    func placeSubviews(in bounds: CGRect,
                       proposal: ProposedViewSize,
                       subviews: Subviews,
                       cache: inout ()) {
        let w = widths(for: bounds.width)
        var x = bounds.minX
        for (index, sub) in subviews.enumerated() {
            let width = index < w.count ? w[index] : 0
            sub.place(at: CGPoint(x: x, y: bounds.midY),
                      anchor: .leading,
                      proposal: ProposedViewSize(width: width,
                                                 height: bounds.height))
            x += width + spacing
        }
    }
}

/// Shared geometry for the field-log table.
///
/// THREE columns now, not four. Dropping ± RANGE and QUALITY gave back
/// roughly 110 pt on a 360 pt phone, which is why every cell here sits well
/// inside its column instead of scaling to fit as the four-column table did:
/// at 288 pt of content the shares are TREE 73.8 / DBH 99.1 / HEIGHT 99.1,
/// against measured demands of "Plot3-T08" 68, "150.0 cm" 82 and
/// "150.00 ft" 88. The scale floors below are a backstop for an unusually
/// long value, not the layout.
private enum FieldLogTable {
    /// TREE · DBH · HEIGHT. The two measurement columns are equal — either
    /// can carry the longest string depending on the unit system.
    ///
    /// TREE carries a NAME now, not just "#128", so it holds a quarter of the
    /// row rather than a fifth. The width it took came off the two
    /// measurement columns, which were still well clear of their widest
    /// reading. Same proportions as the Android sibling's column weights.
    static let weights: [CGFloat] = [3.8, 5.1, 5.1]
    /// 8 pt, not the 12 pt row default: gaps are width the numbers could
    /// have had.
    static let gap: CGFloat = ForestixSpace.xs
    /// ALL-CAPS header tracking. The house `sectionHead` value is 1.2, which
    /// adds ~8 pt to a seven-letter header. 0.8 keeps the spaced-caps look
    /// and leaves the headings comfortably inside their columns.
    static let headerTracking: CGFloat = 0.8
    /// A cell shrinks a little rather than truncating, and never below this.
    static let labelScaleFloor: CGFloat = 0.72
    /// Values may run long — a sampling-plot row's "5.6 m radius · 98.5 m²"
    /// is wider than any phone column, and a truncated measurement is a
    /// wrong measurement. That row spans two columns (see `FieldLogRow`), so
    /// this floor is only ever reached by an extreme reading.
    static let valueScaleFloor: CGFloat = 0.35
}

/// One cell of the table: always a single line, tightened and then scaled
/// down to fit rather than wrapped or truncated.
private struct FieldLogCell: View {
    let text: String
    let font: Font
    let color: Color
    var alignment: Alignment = .trailing
    var tracking: CGFloat = 0
    var scaleFloor: CGFloat = FieldLogTable.labelScaleFloor

    var body: some View {
        Text(text)
            .font(font)
            .tracking(tracking)
            .foregroundStyle(color)
            .lineLimit(1)
            .allowsTightening(true)
            .minimumScaleFactor(scaleFloor)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

// MARK: - Column header

/// The column header lives as the List `Section` header, which gets
/// inset-grouped styling for free. It's a separate view, so it shares the
/// column weights with the row below — update `FieldLogTable` or neither.
/// Android uses the identical strings.
private struct FieldLogColumnHeader: View {
    var body: some View {
        WeightedColumns(weights: FieldLogTable.weights,
                        spacing: FieldLogTable.gap) {
            cell("TREE", alignment: .leading)
            cell("DBH")
            cell("HEIGHT")
        }
    }

    private func cell(_ text: String,
                      alignment: Alignment = .trailing) -> some View {
        FieldLogCell(text: text,
                     font: ForestixType.sectionHead,
                     color: ForestixPalette.textTertiary,
                     alignment: alignment,
                     tracking: FieldLogTable.headerTracking)
    }
}

// MARK: - Row

private struct FieldLogRow: View {
    let row: FieldLogRowModel
    let unitSystem: UnitSystem
    /// The log is in move-selection mode. The tick box appears for every
    /// quick row while it is, so an UNticked row is visibly unticked rather
    /// than merely missing a mark — same rule as the cruise row beside it.
    var selecting: Bool = false
    var isSelected: Bool = false

    var body: some View {
        HStack(spacing: ForestixSpace.xs) {
            if selecting {
                Image(systemName: isSelected
                      ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(isSelected
                                     ? ForestixPalette.primary
                                     : ForestixPalette.textTertiary)
                    .accessibilityHidden(true)
            }
            table
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityText)
        .accessibilityHint(selecting
                           ? "Selected for a move"
                           : "Opens the full record")
    }

    private var table: some View {
        VStack(alignment: .leading, spacing: 4) {
            // Same weights as the column header — the two are one table.
            WeightedColumns(weights: FieldLogTable.weights,
                            spacing: FieldLogTable.gap) {
                FieldLogCell(text: row.treeLabel
                                ?? FieldLogRowModel.kindWord(row.entries[0].kind),
                             font: ForestixType.data,
                             color: ForestixPalette.textPrimary,
                             alignment: .leading)

                if case .loose(let kind) = row.subject {
                    // A plot record or a standalone crown / distance has no
                    // diameter-and-height shape to fill. Its reading spans
                    // the two measurement columns rather than being forced
                    // into one of them under a heading it does not match.
                    FieldLogCell(text: looseValue(kind),
                                 font: ForestixType.data,
                                 color: ForestixPalette.textPrimary,
                                 scaleFloor: FieldLogTable.valueScaleFloor)
                    Color.clear.frame(height: 0)
                } else {
                    FieldLogCell(text: row.dbh.map {
                                    MeasurementFormatter.diameter(
                                        cm: $0.value, in: unitSystem)
                                 } ?? "—",
                                 font: ForestixType.data,
                                 color: row.dbh == nil
                                    ? ForestixPalette.textTertiary
                                    : ForestixPalette.textPrimary,
                                 scaleFloor: FieldLogTable.valueScaleFloor)

                    FieldLogCell(text: row.height.map {
                                    MeasurementFormatter.height(
                                        m: $0.value, in: unitSystem)
                                 } ?? "—",
                                 font: ForestixType.data,
                                 color: row.height == nil
                                    ? ForestixPalette.textTertiary
                                    : ForestixPalette.textPrimary,
                                 scaleFloor: FieldLogTable.valueScaleFloor)
                }
            }
            HStack(spacing: 6) {
                if case .loose = row.subject {} else {
                    // The kind words that used to be the TYPE column, kept
                    // here only when a row carries something other than the
                    // two named columns (a crown on the same tree).
                    ForEach(extraKinds, id: \.self) { word in
                        Text(word)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textTertiary)
                    }
                }
                if let species = speciesName {
                    Text(species)
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(1)
                }
                Text(compactRelativeAgo(row.latest))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                // The standard "there is more behind this" affordance —
                // the row is a button and this says so. While selecting the
                // tap toggles instead of opening, so it goes.
                if !selecting {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
        }
    }

    /// Kinds on this tree beyond the two the table names.
    private var extraKinds: [String] {
        let extras = row.entries
            .filter { $0.kind != .dbh && $0.kind != .height }
            .map { FieldLogRowModel.kindWord($0.kind) }
        return Array(Set(extras)).sorted()
    }

    private var speciesName: String? {
        guard let code = row.entries.compactMap(\.speciesCode)
            .first(where: { !$0.isEmpty }) else { return nil }
        return RegionalSpecies.name(forCode: code)
    }

    private func looseValue(_ kind: QuickMeasureEntry.Kind) -> String {
        guard let entry = row.entries.first else { return "—" }
        switch kind {
        case .dbh:
            return MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            let area = entry.secondaryValue ?? (.pi * entry.value * entry.value)
            return String(format: "%.1f m radius · %.1f m²", entry.value, area)
        }
    }

    private var accessibilityText: String {
        var parts: [String] = [row.title]
        if let d = row.dbh {
            parts.append("DBH " + MeasurementFormatter.diameter(
                cm: d.value, in: unitSystem))
        }
        if let h = row.height {
            parts.append("Height " + MeasurementFormatter.height(
                m: h.value, in: unitSystem))
        }
        if case .loose(let kind) = row.subject {
            parts.append(looseValue(kind))
        }
        if let species = speciesName { parts.append(species) }
        return parts.joined(separator: ", ")
    }
}

// MARK: - Cruise row (read-only)

/// A cruise tree in the log's table. Same three columns as a quick row so
/// the two read as one sheet of paper, and the same chevron: the row opens
/// TreeDetailScreen. It has no swipe and no inline editor — this store is
/// written by the cruise flow and edited on TreeDetailScreen, and that is
/// exactly where the tap goes.
private struct FieldLogCruiseRowView: View {
    let row: FieldLogCruiseRow
    let unitSystem: UnitSystem
    /// The log is in bulk-move selection mode. The tick box appears for
    /// every cruise row while it is, so an UNticked row is visibly unticked
    /// rather than merely missing a mark.
    var selecting: Bool = false
    var isSelected: Bool = false

    var body: some View {
        HStack(spacing: ForestixSpace.xs) {
            if selecting {
                Image(systemName: isSelected
                      ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 18))
                    .foregroundStyle(isSelected
                                     ? ForestixPalette.primary
                                     : ForestixPalette.textTertiary)
                    .accessibilityHidden(true)
            }
            table
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityHint(selecting
                           ? "Selected for a move"
                           : "Opens the full record")
    }

    private var table: some View {
        VStack(alignment: .leading, spacing: 4) {
            WeightedColumns(weights: FieldLogTable.weights,
                            spacing: FieldLogTable.gap) {
                FieldLogCell(text: row.treeLabel,
                             font: ForestixType.data,
                             color: ForestixPalette.textPrimary,
                             alignment: .leading)
                FieldLogCell(text: MeasurementFormatter.diameter(
                                cm: row.dbhCm, in: unitSystem),
                             font: ForestixType.data,
                             color: ForestixPalette.textPrimary,
                             scaleFloor: FieldLogTable.valueScaleFloor)
                FieldLogCell(text: row.heightM.map {
                                MeasurementFormatter.height(m: $0, in: unitSystem)
                             } ?? "—",
                             font: ForestixType.data,
                             color: row.heightM == nil
                                ? ForestixPalette.textTertiary
                                : ForestixPalette.textPrimary,
                             scaleFloor: FieldLogTable.valueScaleFloor)
            }
            HStack(spacing: 6) {
                if !row.speciesCode.isEmpty {
                    Text(RegionalSpecies.name(forCode: row.speciesCode))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(1)
                }
                Text(compactRelativeAgo(row.recordedAt))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .lineLimit(1)
                Spacer(minLength: 0)
                // The same "there is more behind this" affordance a quick row
                // carries, for the same reason: this row opens. A chevron on
                // a row that cannot be opened would be worse than none, so it
                // appears here only because the tap above it is real — and
                // while selecting the tap toggles instead, so it goes.
                if !selecting {
                    Image(systemName: "chevron.right")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
        }
    }
}

// MARK: - Scope sheet

/// The filter. Lists every scope the two stores can actually answer for —
/// no free text, so the cruiser cannot ask for a project a quick reading
/// could never be under.
private struct FieldLogScopeSheet: View {
    @Binding var scope: FieldLogScope
    let quickPlots: [QuickMeasurePlot]
    let projects: [Project]
    let plotsByProject: [UUID: [Plot]]
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    choice(FieldLogWords.everything, target: .everything)
                }
                if !projects.isEmpty {
                    Section(FieldLogWords.cruiseProjectsHeader) {
                        ForEach(projects) { project in
                            choice(project.name,
                                   detail: FieldLogWords.allPlots,
                                   target: .cruiseProject(project.id))
                            ForEach(plotsByProject[project.id] ?? []) { plot in
                                choice(FieldLogWords.plotName(number: plot.plotNumber),
                                       target: .cruisePlot(plot.id),
                                       indented: true)
                            }
                        }
                    }
                }
                if !quickPlots.isEmpty {
                    Section(FieldLogWords.quickPlotsHeader) {
                        ForEach(quickPlots) { plot in
                            choice(plot.name, target: .quickPlot(plot.id))
                        }
                    }
                }
            }
            .navigationTitle(FieldLogWords.filterTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func choice(_ title: String,
                        detail: String? = nil,
                        target: FieldLogScope,
                        indented: Bool = false) -> some View {
        Button {
            scope = target
            dismiss()
        } label: {
            HStack(spacing: ForestixSpace.xs) {
                Text(title)
                    .font(ForestixType.body)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .lineLimit(1)
                if let detail {
                    Text(detail)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
                Spacer(minLength: 0)
                if scope == target {
                    Image(systemName: "checkmark")
                        .foregroundStyle(ForestixPalette.primary)
                }
            }
            .padding(.leading, indented ? ForestixSpace.md : 0)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - New-tree sheet

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
/// `QuickMeasureEntry.typed` factory doing the writing.
private struct FieldLogNewTreeSheet: View {

    let request: FieldLogNewTree
    let unitSystem: UnitSystem
    /// (tree name, species code, diameter in cm, height in m) — nil for
    /// anything the cruiser left blank. The host does the writing.
    let onCreate: (String?, String?, Double?, Double?) -> Void

    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    @State private var treeName: String
    @State private var speciesCode: String?
    /// False while `speciesCode` is only the log's carry-over, true once the
    /// cruiser has picked. Styling only — the code is stored either way, on the
    /// same argument as the measure chooser's.
    @State private var speciesConfirmed = false
    @State private var dbhText = ""
    @State private var heightText = ""

    init(request: FieldLogNewTree,
         unitSystem: UnitSystem,
         onCreate: @escaping (String?, String?, Double?, Double?) -> Void) {
        self.request = request
        self.unitSystem = unitSystem
        self.onCreate = onCreate
        _treeName = State(initialValue: request.suggestedName)
        _speciesCode = State(initialValue: request.suggestedSpecies)
    }

    private var imperial: Bool { unitSystem == .imperial }

    private func text(_ kind: QuickMeasureEntry.Kind) -> String {
        kind == .dbh ? dbhText : heightText
    }

    private func binding(_ kind: QuickMeasureEntry.Kind) -> Binding<String> {
        kind == .dbh ? $dbhText : $heightText
    }

    private func accepted(_ kind: QuickMeasureEntry.Kind) -> Double? {
        FieldLogTypedInput.accepted(text(kind), kind: kind, imperial: imperial)
    }

    private func isBlank(_ kind: QuickMeasureEntry.Kind) -> Bool {
        TruthInput.normalized(text(kind)).isEmpty
    }

    /// `.whitespacesAndNewlines`, matching the measure chooser and Kotlin's
    /// `trim()`: the same pasted "Plot3-T07\n" has to persist the same bytes
    /// on both phones, because the two halves of a split cruise join on it.
    private var trimmedName: String? {
        let trimmed = treeName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    /// Create needs at least one number, and refuses while a field holds
    /// something that cannot be stored — a typo in the height must not be
    /// quietly dropped on the floor while the diameter is saved.
    private var canCreate: Bool {
        let dbhOK = isBlank(.dbh) || accepted(.dbh) != nil
        let heightOK = isBlank(.height) || accepted(.height) != nil
        let haveOne = accepted(.dbh) != nil || accepted(.height) != nil
        return dbhOK && heightOK && haveOne
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Tree") {
                    // Not typed, and not editable: it is the next free number,
                    // the same rule the measure chooser follows, so a stem
                    // entered here cannot collide with a scanned one.
                    labelled("Tree number", "#\(request.treeNumber)")
                    TextField("e.g. Tree1", text: $treeName)
                        .autocorrectionDisabled()
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .accessibilityIdentifier("fieldLog.newTree.treeName")
                    // The same control the chooser and the details sheet use —
                    // one species list, one typed-code escape, no second copy
                    // to drift.
                    SpeciesPickerField(speciesCode: $speciesCode,
                                       unspecifiedLabel: "Species",
                                       provisional: !speciesConfirmed,
                                       onPick: { speciesConfirmed = true })
                        .environmentObject(settings)
                    if let plotName = request.plotName {
                        // Named on screen rather than assumed: the cruiser can
                        // see which plot is about to own this stem.
                        labelled("Plot", plotName)
                    }
                }
                measurementSection(.dbh)
                measurementSection(.height)
                Section {
                    Text("Type what you taped. A tree is recorded by its readings, so it needs a diameter or a height — and both are saved as typed, not measured.")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Create tree") {
                        onCreate(trimmedName, speciesCode,
                                 accepted(.dbh), accepted(.height))
                        dismiss()
                    }
                    .disabled(!canCreate)
                    .accessibilityIdentifier("fieldLog.newTree.create")
                }
            }
            .navigationTitle("New tree")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    /// One typed measurement — the same field, unit and refusals as the row
    /// editor's, because both go through `FieldLogTypedInput`.
    @ViewBuilder
    private func measurementSection(_ kind: QuickMeasureEntry.Kind) -> some View {
        Section(kind == .dbh ? "Diameter" : "Height") {
            TextField(FieldLogTypedInput.placeholder(kind, imperial: imperial),
                      text: binding(kind))
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .foregroundStyle(ForestixPalette.textPrimary)
            if let warning = FieldLogTypedInput.warning(text(kind), kind: kind,
                                                        imperial: imperial) {
                Text(warning)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.confidenceBad)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func labelled(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(ForestixPalette.textSecondary)
            Spacer(minLength: 8)
            Text(value)
                .foregroundStyle(ForestixPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }
}

// MARK: - Detail sheet

/// The whole record behind one row.
///
/// FIELD REPORT 5 asked for this: the log table now shows the two numbers a
/// cruiser reads while walking, and everything else — what was typed into
/// the details sheet at capture time, the species, the ± band, where the
/// reading was taken, and in developer mode the ground truth entered against
/// this tree — lives one tap away instead of being squeezed into columns.
/// Internal, not private: the MAP peek opens THIS sheet for the row behind a
/// tapped pin (map spec item 4). There is exactly one per-tree record surface
/// in the quick-measure world and both entry points must land on it.
struct FieldLogDetailSheet: View {

    /// The row is looked up by id rather than carried by value: an edit
    /// made in this sheet changes the store, and a snapshot taken at
    /// presentation time would keep showing the number the cruiser just
    /// replaced.
    let rowID: String
    let unitSystem: UnitSystem
    let onRemeasure: (FieldLogRescan) -> Void

    @EnvironmentObject private var history: QuickMeasureHistory
    @Environment(\.dismiss) private var dismiss

    /// Where the row went when it was moved to another plot. A tree row's id
    /// carries its plot (see `FieldLogRowModel.treeRowID`), so a move from
    /// inside this sheet retires the id it was opened with — and without
    /// this the sheet would answer a successful move with "every reading on
    /// this row has been deleted", which is exactly wrong.
    @State private var movedRowID: String?

    private var shownRowID: String { movedRowID ?? rowID }

    private var liveRow: FieldLogRowModel? {
        FieldLogRowModel.rows(from: history.entries).first { $0.id == shownRowID }
    }

    var body: some View {
        NavigationStack {
            Group {
                if let row = liveRow {
                    FieldLogDetailForm(row: row,
                                       unitSystem: unitSystem,
                                       onRemeasure: onRemeasure,
                                       onRowMoved: { movedRowID = $0 })
                } else {
                    // Every reading behind this row went away while the
                    // sheet was open. An empty form would read as "this
                    // tree has nothing on it" — say what happened instead.
                    Text("Every reading on this row has been deleted.")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle(liveRow?.title ?? "Field log")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

/// The record itself, once the row is known to still exist.
private struct FieldLogDetailForm: View {

    let row: FieldLogRowModel
    let unitSystem: UnitSystem
    let onRemeasure: (FieldLogRescan) -> Void
    /// Where this row's id went after a move. See `FieldLogDetailSheet`.
    let onRowMoved: (String) -> Void

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings

    @State private var dbhText = ""
    @State private var dbhTruthText = ""
    @State private var heightText = ""
    @State private var heightTruthText = ""
    /// The fields are filled from the store ONCE. Re-filling them on every
    /// store change would overwrite what the cruiser is typing.
    @State private var seeded = false
    /// Per-entry unit override for the two TRUTH fields, remembered with the
    /// unit system it was chosen under so changing the system drops it.
    /// Same shape as the scan screens' `truthUnitChoice`.
    @State private var dbhTruthUnitChoice: (unit: TruthInput.Unit, imperial: Bool)?
    @State private var heightTruthUnitChoice: (unit: TruthInput.Unit, imperial: Bool)?
    /// The photo the sheet is showing full-screen, if any — the SAME viewer
    /// the map peek and the cruise tree peek open, so a tree with a diameter
    /// frame and a height frame pages the same way from every surface.
    @State private var photoViewer: PhotoViewerContext?
    /// The recorded-coordinate editor: whether it is up, what is in its
    /// field, and why the field cannot be saved (nil = it can).
    @State private var editingPosition = false
    @State private var positionText = ""
    @State private var positionRefusal: String?
    /// The last ground-truth recovery run's leftovers, loaded once with the
    /// fields. A small JSON file, not the manifest tree.
    @State private var backfillReport: TruthBackfillReport?
    /// This one row, handed to the shared move flow. The single-tree case
    /// where entering a selection mode over the whole log is overkill — same
    /// picker, same confirmation, same report as the bulk move.
    @State private var moveRequest: [QuickMoveRow]?

    /// A prefilled field re-parses a hair off the number it was filled
    /// from — it is rendered to four decimals, and under imperial it makes
    /// a round trip through inches or feet. Anything smaller than this is
    /// that rounding, not an edit, and treating it as an edit would restamp
    /// a measured reading as typed and throw away its σ.
    private static let valueEpsilon: Double = 0.001

    var body: some View {
        Form {
            measurementsSection
            // Editing is per TREE: the two named readings are what a tree
            // has, and a loose crown / distance / plot record has no tree to
            // re-measure or to complete.
            if case .tree(let number) = row.subject {
                editSection(.dbh, tree: number)
                editSection(.height, tree: number)
            }
            detailsSection
            contextSection
            // There is no second ground-truth section here any more. The
            // truth field inside the Diameter and Height sections above is
            // the only place a truth is shown or edited — see the note at
            // the foot of this file.
        }
        .onAppear(perform: seedFields)
        .sheet(isPresented: $editingPosition) { positionEditor }
        // The move raised by the Plot row. Same flow the log's selection bar
        // runs, given one row instead of a dozen.
        .quickMoveFlow(request: $moveRequest) { outcome in
            // A tree row's id carries its plot, so a row that actually moved
            // is now a different row. Tell the sheet where it went; a row
            // that did NOT move (already there, refused, destination gone)
            // keeps the id it had, and the report says why.
            guard outcome.movedCount > 0,
                  case .tree(let number) = row.subject else { return }
            onRowMoved(FieldLogRowModel.treeRowID(
                plotID: outcome.destination.id, treeNumber: number))
        }
        #if os(iOS)
        .fullScreenCover(item: $photoViewer) { context in
            MeasurePhotoDetailView(context: context)
        }
        #endif
    }

    // MARK: Editing

    private func existing(_ kind: QuickMeasureEntry.Kind) -> QuickMeasureEntry? {
        kind == .dbh ? row.dbh : row.height
    }

    private var imperial: Bool { unitSystem == .imperial }

    private func quantity(_ kind: QuickMeasureEntry.Kind) -> TruthInput.Quantity {
        FieldLogTypedInput.quantity(kind)
    }

    /// The unit the MEASURED-value field of a section is typed in — the
    /// cruiser's active system, converted to the metric base on the way in.
    private func unit(_ kind: QuickMeasureEntry.Kind) -> TruthInput.Unit {
        FieldLogTypedInput.unit(kind, imperial: imperial)
    }

    /// The unit the TRUTH field of a section is typed in. It opens in the
    /// cruiser's active system like every other field, and the toggle
    /// overrides it for THIS entry — a tape in centimetres read against an
    /// imperial project is the case that lost a day of analysis. The override
    /// is remembered with the system it was chosen under, so changing the
    /// system drops it; identical rule to the three scan screens.
    private func truthUnit(_ kind: QuickMeasureEntry.Kind) -> TruthInput.Unit {
        let choice = kind == .dbh ? dbhTruthUnitChoice : heightTruthUnitChoice
        if let choice, choice.imperial == imperial { return choice.unit }
        return TruthInput.defaultUnit(quantity(kind), imperial: imperial)
    }

    /// Switching the unit CONVERTS the digits, and this is deliberately NOT
    /// what the three scan screens do.
    ///
    /// There the field starts empty and the toggle means "the number I am
    /// about to type is in this unit", so reinterpreting the digits is the
    /// whole point. Here the field is seeded from a truth already on the
    /// record. Reinterpreting it would take a stored 30 cm, call it 30 in and
    /// write back 76.2 cm — a silent tenfold-ish corruption of the tape value
    /// the entire study is measured against, reached by one tap on a square
    /// button. Converting cannot change the quantity, only how it is spelled.
    private func toggleTruthUnit(_ kind: QuickMeasureEntry.Kind) {
        let from = truthUnit(kind)
        let next: (unit: TruthInput.Unit, imperial: Bool) =
            (TruthInput.toggled(from), imperial)
        // Re-spell whatever is in the field, if it is a number at all. Text
        // that does not parse is left alone rather than blanked; the cruiser
        // is mid-edit and their keystrokes are not ours to discard.
        let respelled = TruthInput.parsePositiveBase(truthText(kind), unit: from)
            .map { TruthInput.text(base: $0, unit: next.unit) }
        if kind == .dbh {
            dbhTruthUnitChoice = next
            if let respelled { dbhTruthText = respelled }
        } else {
            heightTruthUnitChoice = next
            if let respelled { heightTruthText = respelled }
        }
    }

    private func valueBinding(_ kind: QuickMeasureEntry.Kind) -> Binding<String> {
        kind == .dbh ? $dbhText : $heightText
    }

    private func truthBinding(_ kind: QuickMeasureEntry.Kind) -> Binding<String> {
        kind == .dbh ? $dbhTruthText : $heightTruthText
    }

    private func valueText(_ kind: QuickMeasureEntry.Kind) -> String {
        kind == .dbh ? dbhText : heightText
    }

    private func truthText(_ kind: QuickMeasureEntry.Kind) -> String {
        kind == .dbh ? dbhTruthText : heightTruthText
    }

    /// Placeholder copy is the scan screens' own, so the same field means
    /// the same thing wherever a measurement is typed.
    private func valuePlaceholder(_ kind: QuickMeasureEntry.Kind) -> String {
        FieldLogTypedInput.placeholder(kind, imperial: imperial)
    }

    /// The typed number in metric base units, or nil when the field holds
    /// nothing usable. Never falls back to the stored value — a blank field
    /// means "nothing typed", and Save stays off.
    private func parsedValue(_ kind: QuickMeasureEntry.Kind) -> Double? {
        FieldLogTypedInput.parse(valueText(kind), kind: kind, imperial: imperial)
    }

    private func valueWarning(_ kind: QuickMeasureEntry.Kind) -> String? {
        FieldLogTypedInput.warning(valueText(kind), kind: kind, imperial: imperial)
    }

    private func canSave(_ kind: QuickMeasureEntry.Kind) -> Bool {
        guard let value = parsedValue(kind) else { return false }
        if FieldLogTypedInput.isBelowHeightFloor(value, kind: kind) {
            return false
        }
        // Text that doesn't parse must never overwrite a stored truth.
        if settings.developerMode, TruthInput.isUnparseable(truthText(kind)) {
            return false
        }
        return isDirty(kind, value: value)
    }

    private func isDirty(_ kind: QuickMeasureEntry.Kind, value: Double) -> Bool {
        guard let existing = existing(kind) else { return true }
        if abs(value - existing.value) > Self.valueEpsilon { return true }
        return truthEdited(kind, current: existing)
    }

    /// True when the truth field holds something different from what is stored.
    /// With developer mode off the field is not on screen at all, so nothing
    /// can have been edited and a save must leave the stored truth — and its
    /// provenance — exactly as they were.
    private func truthEdited(_ kind: QuickMeasureEntry.Kind,
                             current: QuickMeasureEntry) -> Bool {
        guard settings.developerMode else { return false }
        let typed = TruthInput.parsePositiveBase(truthText(kind), unit: truthUnit(kind))
        switch (typed, current.truth) {
        case (nil, nil):          return false
        case let (new?, old?):    return abs(new - old) > Self.valueEpsilon
        default:                  return true
        }
    }

    private func save(_ kind: QuickMeasureEntry.Kind, tree: Int) {
        guard let value = parsedValue(kind) else { return }
        let current = existing(kind)
        // Developer mode owns the truth field. With it off the stored truth
        // is not on screen, so a save must leave it exactly as it was.
        let truth = settings.developerMode
            ? TruthInput.parsePositiveBase(truthText(kind), unit: truthUnit(kind))
            : current?.truth
        if let current {
            var next = current
            if abs(value - current.value) > Self.valueEpsilon {
                next = next.typedValue(value)
            }
            // Only RESTAMP the truth when the number itself moved. Saving a
            // corrected diameter used to rewrite the truth too, which would now
            // relabel a truth recovered from a raw capture as one typed here —
            // a provenance claim the cruiser never made. An unchanged truth
            // keeps whatever source it already carries.
            if truthEdited(kind, current: current) {
                next = next.settingTruth(truth)
            }
            history.update(next)
        } else {
            history.append(.typed(kind: kind, value: value,
                                  treeNumber: tree,
                                  treeName: row.entries.compactMap(\.treeName).first,
                                  plotID: row.entries.first?.plotID,
                                  truth: truth))
        }
    }

    private func seedFields() {
        guard !seeded else { return }
        seeded = true
        backfillReport = TruthBackfillReport.load()
        dbhText = row.dbh.map {
            TruthInput.text(base: $0.value, unit: unit(.dbh))
        } ?? ""
        dbhTruthText = row.dbh?.truth.map {
            TruthInput.text(base: $0, unit: truthUnit(.dbh))
        } ?? ""
        heightText = row.height.map {
            TruthInput.text(base: $0.value, unit: unit(.height))
        } ?? ""
        heightTruthText = row.height?.truth.map {
            TruthInput.text(base: $0, unit: truthUnit(.height))
        } ?? ""
    }

    /// One kind's editor: the number, its ground truth, and the two ways to
    /// change either — type it, or go and measure it again. A tree the
    /// sensors never read has the same section, empty, so it can be
    /// completed from here instead of staying half-measured forever.
    @ViewBuilder
    private func editSection(_ kind: QuickMeasureEntry.Kind, tree: Int) -> some View {
        let current = existing(kind)
        Section(kind == .dbh ? "Diameter" : "Height") {
            if current == nil {
                Text("Not measured. Type the number, or measure it now.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            TextField(valuePlaceholder(kind), text: valueBinding(kind))
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .foregroundStyle(ForestixPalette.textPrimary)
            if let warning = valueWarning(kind) {
                warningRow(warning)
            }
            if settings.developerMode {
                HStack(spacing: 6) {
                    // Label and toggle read the SAME unit, so the field can
                    // never say cm while the value is taken as inches.
                    TextField(TruthInput.fieldLabel(quantity(kind), unit: truthUnit(kind)),
                              text: truthBinding(kind))
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                        .foregroundStyle(ForestixPalette.textPrimary)
                    TruthUnitToggle(
                        unit: truthUnit(kind),
                        onToggle: { toggleTruthUnit(kind) },
                        identifier: kind == .dbh
                            ? "fieldLog.dbhTruthUnit"
                            : "fieldLog.heightTruthUnit",
                        chrome: .form)
                }
                if let warning = TruthInput.fieldWarning(truthText(kind),
                                                         quantity: quantity(kind),
                                                         unit: truthUnit(kind)) {
                    warningRow(warning)
                }
                if let hint = truthNeedsReadingHint(kind) {
                    warningRow(hint)
                }
                ForEach(strandedTruths(kind, tree: tree), id: \.captureID) { s in
                    strandedRow(s, kind: kind)
                }
            }
            Button("Save changes") { save(kind, tree: tree) }
                .disabled(!canSave(kind))
            Button(remeasureTitle(kind, hasReading: current != nil)) {
                onRemeasure(FieldLogRescan(
                    kind: kind == .dbh ? .dbh : .height,
                    treeNumber: tree,
                    treeName: row.entries.compactMap(\.treeName).first,
                    plotID: row.entries.first?.plotID,
                    speciesCode: row.entries.compactMap(\.speciesCode)
                        .first(where: { !$0.isEmpty }),
                    // The tape value AND how it got onto the reading being
                    // superseded — a truth recovered from a raw capture must
                    // not come back stamped as one typed on the scan screen.
                    truth: current?.truth,
                    truthSource: current?.truthSource))
            }
        }
    }

    /// Why Save stays off when the cruiser types a tape value for a kind that
    /// has no reading and no number beside it.
    ///
    /// A truth is an observation ABOUT a reading, so it needs one to sit on:
    /// storing it alone would mean inventing a measurement to hang it from,
    /// which is the one thing this app must never do. But the cruiser who
    /// failed to get a scan and DID tape the tree still has a number to
    /// record — it is a typed measurement, stamped as typed, and the sentence
    /// says exactly that instead of leaving a dead button with no reason.
    private func truthNeedsReadingHint(_ kind: QuickMeasureEntry.Kind) -> String? {
        guard existing(kind) == nil,
              parsedValue(kind) == nil,
              TruthInput.parsePositiveBase(truthText(kind),
                                           unit: truthUnit(kind)) != nil
        else { return nil }
        return "A ground truth attaches to a reading. Type the tape number as the measurement above — it is saved as typed, not measured."
    }

    /// Truths that were typed for a raw capture of this tree and could not be
    /// matched to any reading — the last recovery run's leftovers.
    ///
    /// The old read-only "Ground truth" section is NOT coming back; that
    /// surface showed the manifest value beside the reading's and nothing said
    /// which one the export read. This says only what the sheet cannot
    /// otherwise show: a number exists, it is in the ZIP, and it is on no
    /// reading. Read from the report the recovery pass leaves behind rather
    /// than from disk, so opening a tree never costs ~300 manifest reads.
    private func strandedTruths(_ kind: QuickMeasureEntry.Kind,
                                tree: Int) -> [TruthBackfillReport.Stranded] {
        backfillReport?.stranded(kind: kind, treeNumber: tree) ?? []
    }

    private func strandedRow(_ s: TruthBackfillReport.Stranded,
                             kind: QuickMeasureEntry.Kind) -> some View {
        // Rendered in the same unit as the truth field directly above, so the
        // sentence and the field can never disagree about what the number is.
        let text = TruthInput.text(base: s.value, unit: truthUnit(kind))
        return Text(TruthBackfill.strandedLine(text))
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func remeasureTitle(_ kind: QuickMeasureEntry.Kind,
                                hasReading: Bool) -> String {
        switch (kind, hasReading) {
        case (.dbh, true):  return "Measure the diameter again"
        case (.dbh, false): return "Measure the diameter"
        case (_, true):     return "Measure the height again"
        case (_, false):    return "Measure the height"
        }
    }

    private func warningRow(_ text: String) -> some View {
        Text(text)
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.confidenceBad)
            .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: Measurements

    private var measurementsSection: some View {
        Section("Measurements") {
            ForEach(row.entries) { entry in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(FieldLogRowModel.kindWord(entry.kind))
                            .foregroundStyle(ForestixPalette.textSecondary)
                        Spacer(minLength: 8)
                        Text(value(entry))
                            .font(ForestixType.data)
                            .foregroundStyle(ForestixPalette.textPrimary)
                    }
                    // The ± band the table used to carry. It is the
                    // measurement's own precision, so it belongs with the
                    // measurement rather than in a column being scaled to
                    // fit on a phone.
                    if let band = sigma(entry) {
                        Text(band)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textTertiary)
                    }
                }
            }
        }
    }

    private func value(_ entry: QuickMeasureEntry) -> String {
        switch entry.kind {
        case .dbh:
            return MeasurementFormatter.diameter(cm: entry.value, in: unitSystem)
        case .height:
            return MeasurementFormatter.height(m: entry.value, in: unitSystem)
        case .crown:
            return String(format: "%.1f × %.1f m",
                          entry.value, entry.secondaryValue ?? 0)
        case .distance:
            return MeasurementFormatter.distance(m: entry.value, in: unitSystem)
        case .samplingPlot:
            let area = entry.secondaryValue ?? (.pi * entry.value * entry.value)
            return String(format: "%.1f m radius · %.1f m²", entry.value, area)
        }
    }

    private func sigma(_ entry: QuickMeasureEntry) -> String? {
        guard let s = entry.sigma, s > 0 else { return nil }
        switch entry.kind {
        case .dbh:    return MeasurementFormatter.diameterSigma(mm: s, in: unitSystem)
        case .height: return MeasurementFormatter.heightSigma(m: s, in: unitSystem)
        case .crown, .distance, .samplingPlot:
            return String(format: "±%.2f m", s)
        }
    }

    // MARK: What the cruiser typed

    private var detailsSection: some View {
        Section("Details") {
            row(label: "Species", value: speciesText)
            if let position = row.entries.compactMap(\.position).first {
                self.row(label: "Stem position", value: position.displayName)
            }
            if !damageCodes.isEmpty {
                self.row(label: "Damage", value: damageCodes.joined(separator: ", "))
            }
            if let note = notes {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Note")
                        .foregroundStyle(ForestixPalette.textSecondary)
                    Text(note)
                        .font(ForestixType.body)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if speciesText == "—" && damageCodes.isEmpty && notes == nil
                && row.entries.allSatisfy({ $0.position == nil }) {
                Text("Nothing was attached to this reading.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
    }

    private var speciesText: String {
        guard let code = row.entries.compactMap(\.speciesCode)
            .first(where: { !$0.isEmpty }) else { return "—" }
        return "\(RegionalSpecies.name(forCode: code)) · \(code)"
    }

    private var damageCodes: [String] {
        Array(Set(row.entries.flatMap(\.damageCodes))).sorted()
    }

    private var notes: String? {
        let all = row.entries.compactMap(\.note)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return all.isEmpty ? nil : all.joined(separator: "\n")
    }

    // MARK: Where and when

    private var contextSection: some View {
        Section("Recorded") {
            // WHICH PLOT THIS IS IN, and the way to change it.
            //
            // The plot is the only grouping a quick reading has — it is what
            // separates a validation set from a bench test, and it reaches
            // every export — and until now the record sheet did not name it
            // at all. A cruiser could not see what needed fixing, let alone
            // fix it. Tapping opens the same picker the bulk move uses.
            Button {
                moveRequest = [QuickMoveRow(row)]
            } label: {
                HStack {
                    Text(QuickMoveWords.plotRow)
                        .foregroundStyle(ForestixPalette.textSecondary)
                    Spacer(minLength: 8)
                    Text(plotValueText)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .multilineTextAlignment(.trailing)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("fieldLog.detail.plot")
            row(label: "When", value: timestampText)
            // POSITION IS TAPPABLE. A fix that never arrived, or arrived on
            // the wrong stem, used to be permanent — the reading carried it
            // for the rest of the cruise and into every export. The row now
            // opens an editor; what it shows is unchanged.
            Button {
                positionText = locatedEntry.map {
                    CoordinateInput.text(latitude: $0.latitude ?? 0,
                                         longitude: $0.longitude ?? 0)
                } ?? ""
                positionRefusal = nil
                editingPosition = true
            } label: {
                HStack {
                    Text("Position")
                        .foregroundStyle(ForestixPalette.textSecondary)
                    Spacer(minLength: 8)
                    Text(positionValueText)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .multilineTextAlignment(.trailing)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("fieldLog.detail.position")
            // WHERE THE COORDINATE CAME FROM. Without this a coordinate the
            // cruiser typed reads on screen — and exports — exactly like one
            // the satellites produced.
            if let source = locatedEntry?.positionRecordedSource {
                self.row(label: "Position source",
                         value: FieldLogWords.positionSourceText(source))
            }
            if let mode = row.entries.compactMap(\.captureMode).first {
                // "typed" is its own answer. Folding it into "Automatic"
                // told the cruiser the sensors produced a number nobody
                // ever pointed a camera at.
                self.row(label: "Capture", value: captureModeText(mode))
            }
            // The row's photos in the order they were shot. The sheet still
            // shows the first one in place, exactly as it always has; the
            // rest are one tap away in the shared viewer, which is the only
            // thing on this screen that pages.
            let photoPages = MeasurePhotoPage.pages(for: row.entries,
                                                    unitSystem: unitSystem)
            if let first = photoPages.first {
                FieldLogPhotoRow(name: first.photoPath, count: photoPages.count) {
                    photoViewer = PhotoViewerContext(pages: photoPages)
                }
            }
        }
    }

    /// The plot this row is filed under, read through the SAME nil rule the
    /// rest of the app uses (`plotID ?? defaultPlotID`) rather than a second
    /// one invented here. A plot id that no plot answers to says so — it is a
    /// real state (the plot was deleted out from under the reading) and
    /// quietly printing the default plot's name would be a claim about where
    /// the reading is filed that is not true.
    private var plotValueText: String {
        let id = row.entries.first?.plotID ?? history.defaultPlotID()
        guard let id else { return FieldLogWords.unknownPlot }
        return history.plot(id: id)?.name ?? FieldLogWords.unknownPlot
    }

    private var timestampText: String {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US")
        fmt.dateFormat = "MMM d, HH:mm"
        return fmt.string(from: row.latest)
    }

    // MARK: The recorded coordinate

    /// The reading whose coordinate this row shows and edits — the newest
    /// one that HAS a coordinate. nil when nothing on the tree was located.
    private var locatedEntry: QuickMeasureEntry? {
        row.entries.first { $0.latitude != nil && $0.longitude != nil }
    }

    /// Said out loud rather than left blank: a reading with no fix is a
    /// different thing from one whose fix was not shown.
    private var positionValueText: String {
        guard let fix = locatedEntry else { return "not recorded" }
        return CoordinateInput.text(latitude: fix.latitude ?? 0,
                                    longitude: fix.longitude ?? 0)
    }

    /// Write a typed coordinate — or a clearance — onto EVERY reading on
    /// this row.
    ///
    /// The cruiser is fixing where the TREE is, not where one of its two or
    /// three readings is; setting only the newest would leave the diameter
    /// and the height claiming different places, and clearing only the
    /// newest would make an older fix pop back into the row as if nothing
    /// had happened. `settingPosition` stamps the source `manual` so the
    /// typed coordinate can never be read back as a device fix.
    private func applyPosition(latitude: Double?, longitude: Double?) {
        for entry in row.entries {
            history.update(entry.settingPosition(latitude: latitude,
                                                 longitude: longitude))
        }
    }

    /// The editor. Save is off while the text cannot be read as a
    /// coordinate — the stored one is never replaced by a guess.
    private var positionEditor: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("44.56417, -123.28556", text: $positionText)
                        .font(.body.monospacedDigit())
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.never)
                        #endif
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .accessibilityIdentifier("fieldLog.positionField")
                    if let refusal = positionRefusal {
                        Text(refusal)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.confidenceBad)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                } header: {
                    Text("Latitude, longitude")
                } footer: {
                    Text("Decimal degrees, the way the app shows them. This is the position for every reading on this tree, and it is recorded as typed by hand.")
                }

                Section {
                    Button("Save position") { savePosition() }
                        .disabled(positionRefusal != nil)
                    if locatedEntry != nil {
                        Button("Clear position", role: .destructive) {
                            applyPosition(latitude: nil, longitude: nil)
                            editingPosition = false
                        }
                    }
                }
            }
            .onChange(of: positionText) { _, new in
                // Refuse as they type, so Save is never a surprise. A blank
                // field is not a refusal: it means "clear", which Save then
                // performs.
                switch CoordinateInput.parse(new) {
                case .refused(let message): positionRefusal = message
                case .coordinate, .cleared: positionRefusal = nil
                }
            }
            .navigationTitle("Position")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { editingPosition = false }
                }
            }
        }
    }

    private func savePosition() {
        switch CoordinateInput.parse(positionText) {
        case .coordinate(let lat, let lon):
            applyPosition(latitude: lat, longitude: lon)
            editingPosition = false
        case .cleared:
            applyPosition(latitude: nil, longitude: nil)
            editingPosition = false
        case .refused(let message):
            // Nothing is written. The sentence stays on screen.
            positionRefusal = message
        }
    }

    private func captureModeText(_ mode: String) -> String {
        switch mode {
        case "manual": return "Adjusted by hand"
        case "typed":  return "Typed by hand"
        default:       return "Automatic"
        }
    }

    // MARK: Row helper

    private func row(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(ForestixPalette.textSecondary)
            Spacer(minLength: 8)
            Text(value)
                .foregroundStyle(ForestixPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }
}

/// The capture photo, if the file is still there. A missing file says so
/// rather than leaving an empty box — the container can move between
/// installs, and a blank row would read as "no photo was taken".
///
/// Tapping it opens the shared full-screen viewer. With one photo on the
/// tree nothing is drawn over the image and the sheet looks exactly as it
/// did; from two up it carries the same "×N" badge the map peek's thumbnail
/// uses, because that is where the second frame is reachable.
private struct FieldLogPhotoRow: View {
    let name: String
    let count: Int
    let onOpen: () -> Void

    var body: some View {
        #if canImport(UIKit)
        if let image = UIImage(contentsOfFile:
                                MeasurePhotoStore.url(for: name).path) {
            Button(action: onOpen) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(maxHeight: 220)
                    .clipShape(RoundedRectangle(cornerRadius: ForestixRadius.control,
                                                style: .continuous))
                    .overlay(alignment: .topTrailing) {
                        if count > 1 { countBadge }
                    }
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Capture photo")
            .accessibilityIdentifier("fieldLog.detail.photo")
        } else {
            HStack {
                Text("Photo")
                    .foregroundStyle(ForestixPalette.textSecondary)
                Spacer(minLength: 8)
                Text("file missing")
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
        #else
        EmptyView()
        #endif
    }

    /// Same badge, same wording as the map peek's thumbnail ("×2").
    private var countBadge: some View {
        Text("×\(count)")
            .font(.system(size: 9, weight: .bold, design: .monospaced))
            .foregroundStyle(MeasurePhotoChrome.ink)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .background(
                RoundedRectangle(cornerRadius: 4)
                    .fill(MeasurePhotoChrome.glass.opacity(0.70)))
            .padding(4)
    }
}

// MARK: - Compact relative time

/// Compact relative timestamp — "now" (<60 s), "5m", "3h", "2d",
/// then "MMM d" from a week out. Verbatim port of the Android
/// FieldLogScreen `relativeAgo` thresholds so the LAST summary cell
/// and row timestamps render identically on both platforms.
private func compactRelativeAgo(_ date: Date, now: Date = Date()) -> String {
    let diff = max(0, now.timeIntervalSince(date))
    let mins = Int(diff) / 60
    let hrs  = mins / 60
    let days = hrs / 24
    if mins < 1  { return "now" }
    if mins < 60 { return "\(mins)m" }
    if hrs  < 24 { return "\(hrs)h" }
    if days < 7  { return "\(days)d" }
    let fmt = DateFormatter()
    fmt.locale = Locale(identifier: "en_US")
    fmt.dateFormat = "MMM d"
    return fmt.string(from: date)
}

// MARK: - Share sheet plumbing

#if os(iOS)
private struct ShareWrapper: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct FieldLogShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController, context: Context) {}
}
#endif

// MARK: - One ground truth per reading
//
// FIELD REPORT — the detail sheet used to show a tree's ground truth TWICE:
// the editable truth field inside the Diameter and Height sections, and a
// read-only "Ground truth" section at the foot that read RawCaptureStore
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
