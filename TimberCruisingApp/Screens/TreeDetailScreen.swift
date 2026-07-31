// Phase 5 §5.3 TreeDetailScreen. REQ-TAL-006.
//
// Single-tree inspector.
//
// ONE FORM FOR BOTH KINDS OF TREE. A cruise tree lives here; a quick
// measurement lives in the field log's record sheet (`FieldLogDetailForm`).
// They are the same act — a cruiser standing at a stem, reading back what the
// app holds about it — so they are one form, in one order:
//
//     Detail  →  Measurement  →  Computed  →  Recorded
//
// The rows are the same rows in the same places on both, and a field that a
// kind of tree does not carry reads "—" rather than being left out: a row that
// disappears is how two surfaces start looking like two screens again. The
// shared vocabulary and the shared rows are at the foot of this file
// (`TreeFormWords`, `TreeFormRows`, `TreeFormSpeciesRows`, `TreeComputed`,
// `MeasuredTimeSheet`) and the field log reads them from there — including the
// time editor, which is ONE sheet with two callers: a cruise tree passes the
// tree's name and no time source, a quick reading passes the kind of reading
// and the source its `time_source` column exports. It refuses live, as the
// wheel turns, for both.
//
// FIELD REPORT F8 — what this form does NOT show, and why:
//   • No "Method" readout. `.lidarDepth` / `.vioWalkoffTangent` told a cruiser
//     nothing; it is still on the record and in exports.
//   • No "Irregular" (nobody set it; the flag is untouched on the model).
//   • No "Placement" (bearing / distance from plot centre). Both were
//     hand-typed number fields, and a cruiser standing at a tree has no way
//     to type a meaningful bearing. The FIELDS SURVIVE on `Tree` and in every
//     export — the cruise chain still auto-computes `distanceFromCenterM`
//     from the capture fix. Only the manual UI went.
//   • No "Stem position". It is not a call a cruiser acts on, and it was one
//     of the two rows the quick form carried that this one did not.
//   • Confidence stays, and the chip is TAPPABLE — it opens a plain-language
//     explanation of what the grades mean and what actually drives them, so
//     the criteria are learnable in the field instead of being folklore.
//
// FIELD REPORT F6 — the read-only raw-metadata block is developer-mode only.
// A normal cruiser never sees `dbhCoverageDeg` / `heightAlphaTopDeg`.

import SwiftUI
import Models
import Common
import InventoryEngine

public struct TreeDetailScreen: View {

    @StateObject private var viewModel: TreeDetailViewModel
    @EnvironmentObject private var settings: AppSettings
    /// The stores behind the rows this form writes WITHOUT the view model —
    /// the plot a tree is filed under and the instant it was measured, neither
    /// of which the view model carries. Injected at the app root, so every
    /// caller that can reach this screen already has it.
    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    /// Projects and their plots, for the Plot row's picker. The same feed the
    /// field log lists the cruise world from.
    @StateObject private var cruiseFeed = FieldLogCruiseFeed()

    /// Which measurement's tier explainer is open. nil = closed.
    @State private var explaining: TierExplainer.Kind?

    /// Which measurement is being set. nil = neither; the value rows are
    /// read-outs until one is tapped.
    @State private var editing: TreeFormMeasurement?

    /// The plot picker, and the move it raises: what will happen, stated
    /// before anything is written, then what it left behind.
    @State private var pickingPlot = false
    @State private var pendingPlan: TreeMovePlan?
    @State private var moveFailure: String?

    /// Where this tree was re-filed and re-timed while the form was open, if
    /// it was. Both are written STRAIGHT TO THE RECORD by their own rows, and
    /// each write is followed by `viewModel.reload()`, so these two hold what
    /// the rows display and nothing that a later Save depends on.
    @State private var refiled: TreeRefiling?
    @State private var retimed: Date?

    /// The measured-time editor: whether it is up, what is in its picker, and
    /// why the picked time cannot be stored (nil = it can).
    @State private var editingTime = false
    @State private var timeDraft = Date()
    @State private var timeRefusal: String?

    /// What the two numeric fields currently HOLD, as text. They are seeded on
    /// appear from `dbhPrefill` / `heightPrefill` and never written back from a
    /// number afterwards — see `applyDBH`.
    @State private var dbhText = ""
    @State private var heightText = ""

    /// False while a numeric field holds something that is not a usable
    /// measurement. The row says so and Save stays off, rather than the form
    /// picking a number the tree never had.
    @State private var dbhEntryValid = true
    @State private var heightEntryValid = true

    /// The volume equation behind the current species. Resolved when the
    /// species changes rather than on every render — see `TreeComputed`.
    @State private var volumeEquation: (any InventoryEngine.VolumeEquation)?

    public init(viewModel: @autoclosure @escaping () -> TreeDetailViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        Form {
            if viewModel.isDeleted {
                Section {
                    Label("Removed from the tally — this tree is left out of every total. You can put it back below.",
                          systemImage: "trash.circle")
                        .foregroundStyle(.secondary)
                        .font(.callout)
                }
            }
            detailSection
            measurementSection
            computedSection
            recordedSection
            // F6 — raw internals are a developer/audit surface, not a
            // cruiser one.
            if settings.developerMode {
                rawMetaSection
            }
            actionSection
        }
        .navigationTitle(viewModel.tree.displayTitle)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            // Seeded here and nowhere else. Assigning the text does NOT run
            // the bindings' setters, so seeding can never be mistaken for the
            // cruiser typing — which is what keeps an unedited field from
            // writing its rounded self back over the stored measurement.
            dbhText = dbhPrefill
            heightText = heightPrefill
            cruiseFeed.load(environment: environment)
            refreshVolumeEquation()
        }
        .onChange(of: viewModel.speciesCode) { _, _ in refreshVolumeEquation() }
        .sheet(item: $explaining) { kind in
            TierExplainer(kind: kind)
        }
        .sheet(item: $editing) { kind in
            measurementEditor(kind)
        }
        .sheet(isPresented: $editingTime) { timeEditor }
        .sheet(isPresented: $pickingPlot) {
            TreeMovePicker(projects: cruiseFeed.projects,
                           plotsByProject: cruiseFeed.plotsByProject,
                           onPick: { planMove(to: $0) })
        }
        // Names what will happen BEFORE anything is written: a re-file
        // rewrites which plot — and so which project — this measurement was
        // recorded in, and there is no undo behind it.
        .alert(TreeMoveWords.confirmTitle,
               isPresented: Binding(get: { pendingPlan != nil },
                                    set: { if !$0 { pendingPlan = nil } }),
               presenting: pendingPlan) { plan in
            Button(TreeMoveWords.confirmAction(plan.movers.count)) { commitMove(plan) }
            Button(TreeMoveWords.cancel, role: .cancel) { pendingPlan = nil }
        } message: { plan in
            Text(TreeMoveWords.confirmMessage(
                destination: plan.destination.label,
                movers: plan.movers.count,
                alreadyThere: plan.alreadyThere,
                renumbered: plan.renumbered.count,
                destinationClosed: plan.destination.isClosed))
        }
        .alert(TreeMoveWords.planFailedTitle,
               isPresented: Binding(get: { moveFailure != nil },
                                    set: { if !$0 { moveFailure = nil } })) {
            Button("OK", role: .cancel) { moveFailure = nil }
        } message: {
            Text(moveFailure ?? "")
        }
        .alert("Error", isPresented: errorBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.clearError() } })
    }

    // MARK: - Detail

    private var detailSection: some View {
        Section(TreeFormWords.detail) {
            TreeFormRows.tappable(label: TreeFormWords.plot,
                                  value: plotValueText,
                                  identifier: "treeDetail.plot") {
                pickingPlot = true
            }
            TreeFormSpeciesRows(code: $viewModel.speciesCode,
                                identifierPrefix: "treeDetail",
                                onEdited: { viewModel.markDirty() })
            TreeFormRows.tappable(label: TreeFormWords.time,
                                  value: timeValueText,
                                  identifier: "treeDetail.time") {
                timeDraft = shownCreatedAt
                timeRefusal = nil
                editingTime = true
            }
            TreeFormRows.readOnly(label: TreeFormWords.tree,
                                  value: viewModel.tree.displayTitle)
            Picker(TreeFormWords.status, selection: $viewModel.status) {
                Text("Live").tag(TreeStatus.live)
                Text("Dead standing").tag(TreeStatus.deadStanding)
                Text("Dead down").tag(TreeStatus.deadDown)
                Text("Cull").tag(TreeStatus.cull)
            }
            .onChange(of: viewModel.status) { _, _ in viewModel.markDirty() }
            TreeFormRows.readOnly(label: TreeFormWords.damage,
                                  value: TreeFormWords.list(viewModel.damageCodes))
            VStack(alignment: .leading, spacing: 4) {
                Text(TreeFormWords.notes)
                    .foregroundStyle(ForestixPalette.textSecondary)
                TextField(TreeFormWords.notes, text: $viewModel.notes, axis: .vertical)
                    .lineLimit(2...5)
                    .onChange(of: viewModel.notes) { _, _ in viewModel.markDirty() }
            }
            if viewModel.tree.isMultistem {
                Label("One stem of a multi-stem tree", systemImage: "arrow.branch")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Measurement (tap a value to set it)

    private var measurementSection: some View {
        Section(TreeFormWords.measurement) {
            TreeFormRows.measurement(
                label: TreeFormWords.dbh,
                value: MeasurementFormatter.diameter(cm: Double(viewModel.dbhCm),
                                                     in: settings.unitSystem),
                sigma: dbhIsTyped ? nil
                    : viewModel.tree.dbhSigmaMm.flatMap { s in
                        // ZERO IS NOT A PRECISION. Rows written before the
                        // capture path recorded a real σ carry 0.000 with a
                        // capture mode of "auto", so `dbhIsTyped` does not
                        // catch them and the row printed "± 0.0 cm" — a
                        // perfect measurement, claimed. Same guard the field
                        // log and both Android forms already apply.
                        s > 0 ? MeasurementFormatter.diameterSigma(
                            mm: Double(s), in: settings.unitSystem) : nil
                    },
                identifier: "treeDetail.dbh") {
                editing = .dbh
            }
            if !dbhEntryValid {
                entryWarning("A typed diameter must be a number greater than zero.")
            }
            confidenceRow(label: TreeFormWords.dbhConfidence,
                          rawTier: dbhIsTyped ? nil : viewModel.tree.dbhConfidence.rawValue,
                          kind: .diameter)

            TreeFormRows.measurement(
                label: TreeFormWords.height,
                value: viewModel.heightM.map {
                    MeasurementFormatter.height(m: Double($0), in: settings.unitSystem)
                } ?? TreeFormWords.none,
                sigma: heightIsTyped ? nil
                    : viewModel.tree.heightSigmaM.flatMap { s in
                        s > 0 ? MeasurementFormatter.heightSigma(
                            m: Double(s), in: settings.unitSystem) : nil
                    },
                identifier: "treeDetail.height") {
                editing = .height
            }
            if !heightEntryValid {
                entryWarning("A typed height must be a number greater than zero.")
            }
            confidenceRow(label: TreeFormWords.heightConfidence,
                          rawTier: heightIsTyped ? nil
                                                 : viewModel.tree.heightConfidence?.rawValue,
                          kind: .height)
        }
    }

    /// True when the diameter on screen was HAND TYPED rather than produced by
    /// a capture, and so has no ± band and no grade of its own.
    ///
    /// Two ways to be typed, and both count. The STORED stamp is what a
    /// diameter typed on an earlier visit carries: `save()` writes
    /// `dbhCaptureMode = "typed"` and leaves the σ and the tier the last
    /// capture put on the record, so without this test the row prints that
    /// capture's ± band and its Good/Fair/Check beside a number no sensor ever
    /// produced — and goes on printing them for the life of the tree. The LIVE
    /// test catches the same claim one step earlier: from the moment the field
    /// holds something other than the stored measurement, the stored precision
    /// has stopped describing what is on screen.
    ///
    /// SUPPRESSED HERE, NOT CLEARED ON THE RECORD. The σ, the RMSE, the
    /// coverage and the inlier count are the audit trail of a measurement that
    /// really was taken, they stay readable in the raw-metadata block, and
    /// destroying them is not the fix for printing them in the wrong place.
    /// The confidence row then reads "—", which is what a hand-typed diameter
    /// honestly carries — no tier at all — and the Capture row two sections
    /// down says in words that this number was typed by hand.
    private var dbhIsTyped: Bool {
        viewModel.tree.dbhCaptureMode == "typed"
            || viewModel.dbhCm != viewModel.tree.dbhCm
    }

    /// The same rule for the height, minus the half the record cannot keep.
    ///
    /// A `Tree` carries no capture-mode column for its height — `save()`
    /// stamps `heightSource = "measured"` whether the number came off the
    /// sensors or off a tape — so this can only catch a height typed on THIS
    /// screen, before it is saved. After the save a typed height is
    /// indistinguishable from a measured one on the record, and giving it a
    /// column of its own is a change to the model and to every export that
    /// reads it, not a fix to this form.
    private var heightIsTyped: Bool {
        viewModel.heightM != viewModel.tree.heightM
    }

    /// Setting a measurement, in place: the row that shows the number is what
    /// opens the field that changes it. There is no separate diameter screen
    /// or height screen to go and find.
    private func measurementEditor(_ kind: TreeFormMeasurement) -> some View {
        NavigationStack {
            Form {
                Section(kind.title) {
                    switch kind {
                    case .dbh:
                        valueRow(label: TreeFormWords.dbh,
                                 unit: dbhUnit.rawValue,
                                 identifier: "treeDetail.dbhField") {
                            TextField("0.0", text: dbhBinding)
                        }
                        if !dbhEntryValid {
                            entryWarning("A typed diameter must be a number greater than zero.")
                        }
                    case .height:
                        valueRow(label: TreeFormWords.height,
                                 unit: heightUnit.rawValue,
                                 identifier: "treeDetail.heightField") {
                            TextField("—", text: heightBinding)
                        }
                        if !heightEntryValid {
                            entryWarning("A typed height must be a number greater than zero.")
                        }
                    }
                }
            }
            .navigationTitle(kind.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { editing = nil }
                }
            }
        }
    }

    // MARK: - Computed

    private var computedSection: some View {
        Section(TreeFormWords.computed) {
            TreeFormRows.readOnly(
                label: TreeFormWords.basalArea,
                value: TreeComputed.basalAreaText(dbhCm: Double(viewModel.dbhCm),
                                                  in: settings.unitSystem))
            TreeFormRows.readOnly(
                label: TreeFormWords.volume,
                value: TreeComputed.volumeText(dbhCm: Double(viewModel.dbhCm),
                                               heightM: viewModel.heightM.map(Double.init),
                                               equation: volumeEquation,
                                               settings: settings))
        }
    }

    private func refreshVolumeEquation() {
        volumeEquation = TreeComputed.equation(forSpecies: viewModel.speciesCode,
                                               environment: environment)
    }

    // MARK: - Recorded

    /// Three rows, always all three, in this order — the same three the field
    /// log's record sheet draws for a quick reading. A row that is present on
    /// one of the two forms and absent from the other is how they start
    /// reading as two screens again, so a field a cruise tree does not carry
    /// keeps its row and says "—" rather than being left out.
    private var recordedSection: some View {
        Section(TreeFormWords.recorded) {
            TreeFormRows.readOnly(label: TreeFormWords.position,
                                  value: positionValueText)
            // WHERE THE COORDINATE CAME FROM. A cruise `Tree` has no column
            // for it: its fix is stamped once, at Accept, from the device — so
            // this row is the em dash's own case, a field this kind of tree
            // does not carry. A quick reading DOES carry one (it can be typed
            // by hand from the record sheet) and prints it here.
            TreeFormRows.readOnly(label: TreeFormWords.positionSource,
                                  value: TreeFormWords.none)
            TreeFormRows.readOnly(
                label: TreeFormWords.capture,
                value: viewModel.tree.dbhCaptureMode.map(TreeFormWords.captureText)
                    ?? TreeFormWords.none)
        }
    }

    /// Said out loud rather than left blank: a tree with no fix is a different
    /// thing from one whose fix was not shown. Same sentence the field log's
    /// record sheet uses for the same absence.
    private var positionValueText: String {
        guard let lat = viewModel.tree.latitude,
              let lon = viewModel.tree.longitude else { return TreeFormWords.noPosition }
        return CoordinateInput.text(latitude: lat, longitude: lon)
    }

    // MARK: - Prefilling an editable measurement, without truncating it

    /// The unit each field is TYPED in — the cruiser's own, from the helper
    /// every other typed measurement in the app goes through. Storage stays
    /// metric; the conversion happens on the way in and on the way out, so an
    /// imperial cruiser reads inches on the row and types inches in the field
    /// instead of reading inches and typing centimetres.
    private var dbhUnit: TruthInput.Unit {
        TruthInput.defaultUnit(.diameter, imperial: settings.unitSystem == .imperial)
    }

    private var heightUnit: TruthInput.Unit {
        TruthInput.defaultUnit(.height, imperial: settings.unitSystem == .imperial)
    }

    /// The stored DBH at the precision the rest of the app prints it —
    /// `MeasurementFormatter.diameter`'s one decimal, without the unit the row
    /// already carries. Android builds the identical string.
    private var dbhPrefill: String {
        MeasurementFormatter.entryText(
            TruthInput.fromBase(Double(viewModel.tree.dbhCm), unit: dbhUnit),
            fractionDigits: 1)
    }

    /// The stored height, same rule. A tree with no height prefills empty, so
    /// the placeholder shows and clearing the field stays meaningful.
    private var heightPrefill: String {
        viewModel.tree.heightM.map {
            MeasurementFormatter.entryText(
                TruthInput.fromBase(Double($0), unit: heightUnit),
                fractionDigits: 1)
        } ?? ""
    }

    /// Both fields go through a hand-rolled Binding rather than `.onChange`:
    /// a Binding's setter runs ONLY when the text field itself changes the
    /// text, so seeding the state in `.onAppear` cannot be mistaken for an
    /// edit.
    private var dbhBinding: Binding<String> {
        Binding(get: { dbhText }, set: { new in
            dbhText = new
            applyDBH(new)
        })
    }

    private var heightBinding: Binding<String> {
        Binding(get: { heightText }, set: { new in
            heightText = new
            applyHeight(new)
        })
    }

    /// Text the cruiser typed → the DBH that will be saved.
    ///
    /// Text still EQUAL to the prefill restores the stored Float verbatim. The
    /// prefill is rounded — a tree stored at 18.27 cm prefills "18.3" — so
    /// parsing the prefill back and saving it would coarsen a measurement
    /// nobody asked to change: open the form, press Save, lose 3 mm. The
    /// comparison is on the STRING for that reason, not on the number.
    private func applyDBH(_ text: String) {
        if text == dbhPrefill {
            viewModel.dbhCm = viewModel.tree.dbhCm
            dbhEntryValid = true
        } else if let typed = TruthInput.parsePositiveBase(text, unit: dbhUnit) {
            viewModel.dbhCm = Float(typed)
            dbhEntryValid = true
        } else {
            // Blank or not a number. Leave the stored diameter alone and say
            // so on the row: writing 0 cm here would be a measurement this
            // tree never had, and every tally downstream would believe it.
            viewModel.dbhCm = viewModel.tree.dbhCm
            dbhEntryValid = false
        }
        viewModel.markDirty()
    }

    /// Same rule for the height, with one difference: emptying the field is
    /// how a cruiser says this tree has no height, so blank is a valid entry
    /// that clears the stored value rather than a refusal.
    private func applyHeight(_ text: String) {
        if text == heightPrefill {
            viewModel.heightM = viewModel.tree.heightM
            heightEntryValid = true
        } else if TruthInput.normalized(text).isEmpty {
            viewModel.heightM = nil
            heightEntryValid = true
        } else if let typed = TruthInput.parsePositiveBase(text, unit: heightUnit) {
            viewModel.heightM = Float(typed)
            heightEntryValid = true
        } else {
            viewModel.heightM = viewModel.tree.heightM
            heightEntryValid = false
        }
        viewModel.markDirty()
    }

    /// Why a numeric row can't be saved. Same sentences the field log's
    /// editor uses for the same refusal, so the app says one thing.
    private func entryWarning(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(ForestixPalette.confidenceBad)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// One measured quantity: label left, editable value + unit right.
    private func valueRow<Field: View>(label: String,
                                       unit: String,
                                       identifier: String,
                                       @ViewBuilder field: () -> Field) -> some View {
        HStack(spacing: 8) {
            Text(label)
            Spacer(minLength: 8)
            field()
                .font(.body.monospacedDigit())
                .multilineTextAlignment(.trailing)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .frame(maxWidth: 110)
                .accessibilityIdentifier(identifier)
            Text(unit)
                .foregroundStyle(.secondary)
                .frame(minWidth: 22, alignment: .leading)
        }
    }

    /// Confidence row, shared with the field log's record sheet: the chip is a
    /// BUTTON, and tapping it explains the tiers and what drives them for this
    /// measurement. A measurement with no tier keeps its row and reads "—".
    private func confidenceRow(label: String,
                               rawTier: String?,
                               kind: TierExplainer.Kind) -> some View {
        TreeFormRows.confidence(label: label,
                                rawTier: rawTier,
                                identifier: "treeDetail.\(kind.rawValue)ConfidenceChip") {
            explaining = kind
        }
    }

    // MARK: - The plot this tree is filed under

    /// The plot the tree is in — the one it was just moved to, if it was moved
    /// on this screen, otherwise the one on the record.
    ///
    /// Three ways to have no name to print, and they are three different
    /// facts, so they get three different answers. The feed has not come back
    /// yet: nothing is known, and "—" is the absence of an answer. The feed
    /// came back BROKEN: the store could not be read, which is not the tree's
    /// fault and must not read like a missing plot. The feed came back fine
    /// and carries no such plot: the plot was deleted out from under the tree,
    /// which is exactly `FieldLogWords.unknownPlot`. Printing one word for all
    /// three would tell the cruiser their tree had been orphaned when the
    /// database was merely unreadable.
    private var plotValueText: String {
        let id = refiled?.plotID ?? viewModel.tree.plotId
        guard cruiseFeed.hasLoaded else { return TreeFormWords.none }
        guard let plot = cruiseFeed.plot(id: id) else {
            return cruiseFeed.failure != nil
                ? TreeFormWords.plotUnavailable
                : FieldLogWords.unknownPlot
        }
        guard let project = cruiseFeed.project(ofPlot: id) else {
            return FieldLogWords.plotName(number: plot.plotNumber)
        }
        return TreeMoveWords.destinationLabel(projectName: project.name,
                                              plotNumber: plot.plotNumber)
    }

    private func planMove(to destination: TreeMoveDestination) {
        pickingPlot = false
        do {
            let plan = try TreeMover.plan(treeIDs: [viewModel.tree.id],
                                          to: destination,
                                          environment: environment)
            guard plan.hasWork else {
                moveFailure = TreeMoveWords.nothingToMove(destination: destination.label)
                return
            }
            pendingPlan = plan
        } catch {
            moveFailure = TreeMoveWords.planFailedMessage(reason: error.localizedDescription)
        }
    }

    private func commitMove(_ plan: TreeMovePlan) {
        pendingPlan = nil
        let outcome = TreeMover.apply(plan, environment: environment)
        guard outcome.movedCount > 0 else {
            moveFailure = TreeMoveWords.failureMessage(
                moved: outcome.movedCount,
                total: outcome.attempted,
                destination: outcome.destination.label,
                failures: outcome.failures.map(\.line))
            return
        }
        // The number may have been taken in the destination, in which case the
        // move gave the tree a free one. Held for the row's own display.
        refiled = TreeRefiling(
            plotID: plan.destination.plot.id,
            treeNumber: plan.renumbered[viewModel.tree.id] ?? viewModel.tree.treeNumber)
        // AND THE SNAPSHOT SAVE BUILDS FROM IS REFRESHED HERE, not repaired
        // afterwards. `TreeMover` wrote the row; the copy this screen opened
        // with still names the old plot and the old number, and `save()`
        // copies from it. Re-stating the move after the save was the old
        // shape, and it wrote the stale plot first and put it back second —
        // two writes, both `try?`, so either failing left the tree in its old
        // plot silently with nothing marked dirty to repair it.
        viewModel.reload()
    }

    // MARK: - When this tree was measured

    /// The instant the row shows: the one set on this screen, if it was set,
    /// otherwise the record's own.
    private var shownCreatedAt: Date { retimed ?? viewModel.tree.createdAt }

    private var timeValueText: String {
        MeasuredTimeInput.text(shownCreatedAt)
    }

    private var timeEditor: some View {
        MeasuredTimeSheet(draft: $timeDraft,
                          refusal: $timeRefusal,
                          subject: viewModel.tree.displayTitle,
                          // A cruise `Tree` has one instant and no column
                          // saying where it came from, so there is nothing
                          // honest to put in that row. See `cruiseTimeFooter`.
                          sourceText: nil,
                          footer: TreeFormWords.cruiseTimeFooter,
                          identifier: "treeDetail.measuredAt",
                          onCancel: { editingTime = false },
                          onSave: { saveTime() })
    }

    /// Writes the measured time straight to the record. The view model does
    /// not carry `createdAt`, so this is the only path to it — and the same
    /// rule the quick world's readings go through decides what a picked time
    /// is allowed to be.
    private func saveTime() {
        switch MeasuredTimeInput.resolve(timeDraft) {
        case .refused(let why):
            timeRefusal = why
        case .time(let stamped):
            do {
                guard var fresh = try environment.treeRepository
                    .read(id: viewModel.tree.id, includeDeleted: true) else {
                    timeRefusal = TreeMoveWords.treeGone
                    return
                }
                fresh.createdAt = stamped
                fresh.updatedAt = Date()
                _ = try environment.treeRepository.update(fresh)
                retimed = stamped
                // `createdAt` is not on the view model, so a save built from
                // the opening snapshot would put the old instant back.
                viewModel.reload()
                timeRefusal = nil
                editingTime = false
            } catch {
                timeRefusal = TreeMoveWords.storageError(error.localizedDescription)
            }
        }
    }

    // MARK: - Raw metadata (developer mode only — F6)

    private var rawMetaSection: some View {
        Section("Raw metadata (read-only)") {
            metaRow("dbhMethod", viewModel.tree.dbhMethod.rawValue)
            metaRow("dbhIsIrregular", viewModel.tree.dbhIsIrregular ? "true" : "false")
            metaRow("dbhSigmaMm", viewModel.tree.dbhSigmaMm.map { String(format: "%.2f", $0) })
            metaRow("dbhRmseMm", viewModel.tree.dbhRmseMm.map { String(format: "%.2f", $0) })
            metaRow("dbhCoverageDeg", viewModel.tree.dbhCoverageDeg.map { String(format: "%.1f", $0) })
            metaRow("dbhNInliers", viewModel.tree.dbhNInliers.map { "\($0)" })
            metaRow("heightSource", viewModel.tree.heightSource)
            metaRow("heightSigmaM", viewModel.tree.heightSigmaM.map { String(format: "%.2f", $0) })
            metaRow("heightDHM", viewModel.tree.heightDHM.map { String(format: "%.2f", $0) })
            metaRow("heightAlphaTopDeg", viewModel.tree.heightAlphaTopDeg.map { String(format: "%.2f", $0) })
            metaRow("heightAlphaBaseDeg", viewModel.tree.heightAlphaBaseDeg.map { String(format: "%.2f", $0) })
            // Placement lost its editor (F8) but not its data — surface it
            // here so an auditor can still read what was recorded.
            metaRow("bearingFromCenterDeg", viewModel.tree.bearingFromCenterDeg.map { String(format: "%.1f", $0) })
            metaRow("distanceFromCenterM", viewModel.tree.distanceFromCenterM.map { String(format: "%.2f", $0) })
            metaRow("createdAt", viewModel.tree.createdAt.ISO8601Format())
            metaRow("updatedAt", viewModel.tree.updatedAt.ISO8601Format())
        }
    }

    private func metaRow(_ label: String, _ value: String?) -> some View {
        HStack {
            Text(label).font(.caption.monospaced())
            Spacer()
            Text(value ?? "—")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Actions

    private var actionSection: some View {
        Section {
            Button {
                viewModel.save()
                if viewModel.errorMessage == nil, !viewModel.dirty {
                    dismiss()
                }
            } label: {
                Text("Save changes").frame(maxWidth: .infinity)
            }
            .buttonStyle(.forestixProminent)
            .controlSize(.large)
            .disabled(!viewModel.dirty || viewModel.isSaving
                      || !dbhEntryValid || !heightEntryValid)

            if viewModel.isDeleted {
                Button {
                    viewModel.undelete()
                } label: {
                    Label("Put back in tally", systemImage: "arrow.uturn.backward")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            } else {
                Button(role: .destructive) {
                    viewModel.softDelete()
                } label: {
                    Label("Remove from tally", systemImage: "trash")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
        }
    }

}

// MARK: - Where this tree went while the form was open

/// A tree's new plot and the number it wears there. Held by the detail form so
/// the Plot row names the destination the instant the move lands, without
/// waiting on a re-read to come back.
struct TreeRefiling: Equatable {
    let plotID: UUID
    let treeNumber: Int
}

/// The two quantities the Measurement section sets. `Identifiable` so the
/// editor can be presented on the row that was tapped, which is what stops one
/// sheet from ever showing the diameter's field while writing the height.
enum TreeFormMeasurement: String, Identifiable {
    case dbh, height
    var id: String { rawValue }

    var title: String {
        switch self {
        case .dbh:    return TreeFormWords.dbh
        case .height: return TreeFormWords.height
        }
    }
}

// MARK: - By-id entry point

/// `TreeDetailScreen` for a caller that holds only the tree's ID.
///
/// The cruise map pushes the detail screen from a `Tree` it already has in
/// memory. The FIELD LOG does not: it holds `FieldLogCruiseRow`, a flattened
/// three-column projection, so it has to read the record back. That read
/// happens ONCE, in `onAppear`, rather than inside the destination builder —
/// the log is a read-back surface and a Core Data fetch in a view body runs
/// inside SwiftUI's layout pass.
///
/// This is the iOS sibling of Android's `TreeDetailScreen(nav, treeId)`,
/// which has always taken an id and loaded it; the failure sentence below is
/// that loader's, verbatim, because the two phones must say the same thing.
///
/// `includeDeleted` is true on purpose, exactly as Android reads it: a tree
/// soft-deleted after the caller listed it still has a record, and "Put back
/// in tally" lives on this screen. Refusing to open it would strand the row.
public struct TreeDetailByIDScreen: View {

    private let treeID: UUID

    @EnvironmentObject private var environment: AppEnvironment

    @State private var tree: Tree?
    /// Why there is no tree on screen. Never left nil-and-silent: an empty
    /// form reads as "this tree has nothing on it", which is a different
    /// fact from "this tree could not be read".
    @State private var loadFailure: String?

    public init(treeID: UUID) {
        self.treeID = treeID
    }

    public var body: some View {
        Group {
            if let tree {
                TreeDetailScreen(viewModel: TreeDetailViewModel(
                    tree: tree,
                    treeRepo: environment.treeRepository))
            } else if let loadFailure {
                Text(loadFailure)
                    .font(.callout)
                    .foregroundStyle(ForestixPalette.confidenceBad)
                    .multilineTextAlignment(.center)
                    .padding(ForestixSpace.lg)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .navigationTitle("Tree")
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .navigationTitle("Tree")
            }
        }
        .onAppear(perform: load)
    }

    /// Loads once. Re-appearing (a sheet closing over this screen, a child
    /// popping back onto it) must not refetch and rebuild the detail screen's
    /// view model underneath whatever the cruiser is editing.
    private func load() {
        guard tree == nil, loadFailure == nil else { return }
        do {
            guard let found = try environment.treeRepository
                .read(id: treeID, includeDeleted: true) else {
                loadFailure = Self.failureText("Tree not found")
                return
            }
            tree = found
        } catch {
            loadFailure = Self.failureText(error.localizedDescription)
        }
    }

    /// Android's `"Failed to load tree: ${e.message ?: e}"`, byte for byte.
    private static func failureText(_ reason: String) -> String {
        "Failed to load tree: \(reason)"
    }
}

// MARK: - The one form's vocabulary

/// Every label the tree form uses, in one place, so a cruise tree and a quick
/// measurement cannot end up calling the same row two things.
enum TreeFormWords {

    // Section headings, in the order they appear.
    static let detail = "Detail"
    static let measurement = "Measurement"
    static let computed = "Computed"
    static let recorded = "Recorded"

    // Detail rows.
    static let plot = "Plot"
    static let species = "Species"
    static let speciesName = "Name"
    static let speciesCode = "Code"
    static let time = "Time"
    static let tree = "Tree"
    static let status = "Status"
    static let damage = "Damage"
    static let notes = "Notes"

    // Measurement rows.
    static let dbh = "DBH"
    static let height = "Height"
    static let dbhConfidence = "DBH confidence"
    static let heightConfidence = "Height confidence"

    // Computed rows.
    static let basalArea = "Basal area"
    static let volume = "Volume"

    // Recorded rows.
    static let position = "Position"
    static let positionSource = "Position source"
    static let capture = "Capture"

    /// What a row shows when the tree does not carry that field. The row
    /// itself always stays: a row that disappears is how the cruise form and
    /// the quick form start reading as two different screens.
    ///
    /// IT MEANS THAT ONE THING. It is not the word for a record that could not
    /// be read, and it is not the word for a plot that was deleted — both of
    /// those are facts the form knows and must say out loud (see
    /// `plotUnavailable` and `FieldLogWords.unknownPlot`). The only other
    /// state it may stand for is "not loaded yet", which is the absence of an
    /// answer rather than a wrong one.
    static let none = "—"

    /// The cruise store could not be read, so where this tree is filed is
    /// unknown — a different fact from `FieldLogWords.unknownPlot`, which is
    /// the plot row's OTHER failure: the id is there and no plot answers to
    /// it, because the plot was deleted out from under the tree. Two facts,
    /// two sentences, the same two on both platforms and on both forms.
    static let plotUnavailable = "Plot unavailable"

    /// A tree with no fix. Said out loud — "not recorded" is a fact, an empty
    /// row is not.
    static let noPosition = "not recorded"

    /// Volume where the country's stem-volume standard has no coefficients in
    /// the app yet. The sentence is the stand summary's own (`FieldLogSummary`
    /// prints it in exactly this row shape), because a number refused on one
    /// screen and shown on another is worse than either.
    ///
    /// IT IS DELIBERATELY NOT ANDROID'S SENTENCE, and this is the one place in
    /// the tree form where the two phones are allowed to differ. Each
    /// platform's stand summary already refuses this number in its own words;
    /// a cruiser reads the summary and the tree form on ONE phone, minutes
    /// apart, and hearing two different reasons there is worse than the two
    /// phones wording the same refusal differently. Android says so in the
    /// same terms beside its own constant. Do not "align" them.
    static let volumePending = "Not available for this region yet."

    /// Shown while a CRUISE tree's measured time is being set.
    ///
    /// The quick world stamps a hand-set time and marks it everywhere it is
    /// printed. A cruise `Tree` has no column for that stamp, so this edit
    /// leaves nothing behind that says the time was typed rather than
    /// recorded — and a cruiser is owed that before they change it, not after.
    static let cruiseTimeFooter =
        "A cruise record has no field for who set this time, so nothing will mark it as set by hand. Set the time the tree was measured."

    /// A list of codes, or "—" when there are none.
    static func list(_ codes: [String]) -> String {
        let kept = codes.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        return kept.isEmpty ? none : kept.joined(separator: ", ")
    }

    /// How a measurement was captured, in words. "typed" is its own answer:
    /// folding it into "Automatic" would tell the cruiser the sensors produced
    /// a number nobody ever pointed a camera at.
    static func captureText(_ raw: String) -> String {
        switch raw {
        case "manual": return "Adjusted by hand"
        case "typed":  return "Typed by hand"
        default:       return "Automatic"
        }
    }
}

// MARK: - The one form's rows

/// The row shapes both kinds of tree are drawn with. Label left, value right,
/// a chevron when the row leads somewhere.
enum TreeFormRows {

    /// A fact the form shows and does not change.
    static func readOnly(label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundStyle(ForestixPalette.textSecondary)
            Spacer(minLength: 8)
            Text(value)
                .foregroundStyle(ForestixPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }

    /// A fact the form shows and DOES change: tapping it opens whatever sets
    /// it. The chevron is the app's standard "this leads somewhere".
    static func tappable(label: String,
                         value: String,
                         identifier: String,
                         action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(label)
                    .foregroundStyle(ForestixPalette.textSecondary)
                Spacer(minLength: 8)
                Text(value)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .multilineTextAlignment(.trailing)
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }

    /// A measured quantity: the number, the ± band it carries, and the tap
    /// that opens the field which sets it.
    static func measurement(label: String,
                            value: String,
                            sigma: String?,
                            identifier: String,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(label)
                        .foregroundStyle(ForestixPalette.textSecondary)
                    Spacer(minLength: 8)
                    Text(value)
                        .font(ForestixType.data)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Image(systemName: "chevron.right")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let sigma {
                    Text(sigma)
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(identifier)
    }

    /// The confidence chip, as a button that explains the grades.
    ///
    /// The chip's WORD is the one word the app uses for that grade everywhere
    /// else — Good / Fair / Check, from `ConfidenceStyle`. A measurement with
    /// no grade keeps its row and reads "—", so the diameter and the height
    /// line up whether or not the height was ever measured.
    static func confidence(label: String,
                           rawTier: String?,
                           identifier: String,
                           action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Text(label)
                    .foregroundStyle(ForestixPalette.textSecondary)
                Spacer(minLength: 8)
                if let rawTier {
                    let descriptor = ConfidenceStyle.descriptor(for: rawTier)
                    Circle()
                        .fill(descriptor.color)
                        .frame(width: 10, height: 10)
                    Text(descriptor.label)
                        .font(.caption.bold())
                        .foregroundStyle(descriptor.color)
                } else {
                    Text(TreeFormWords.none)
                        .foregroundStyle(ForestixPalette.textPrimary)
                }
                // The app's standard "there is more behind this" affordance.
                Image(systemName: "info.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // "tier" is the spec's word, not a cruiser's. Byte-identical to the
        // Android sibling's contentDescription tail.
        .accessibilityHint("Explains what Good, Fair and Check mean")
        .accessibilityIdentifier(identifier)
    }
}

// MARK: - The species rows, shared by both kinds of tree

/// Species picked by NAME over the active country/region list, with a
/// free-typed escape hatch for a code the list doesn't carry, and the resolved
/// common name under it — the whole point of picking by name is knowing "DF"
/// is a Douglas-fir.
///
/// One view, used by the cruise form and the field log's record sheet, because
/// a cruiser who learns to set a species on one must not have to learn it
/// again on the other.
struct TreeFormSpeciesRows: View {

    @Binding var code: String
    let identifierPrefix: String
    /// Run after every change the cruiser makes. The cruise form marks itself
    /// dirty; the field log writes the reading straight through.
    let onEdited: () -> Void

    @EnvironmentObject private var settings: AppSettings

    /// What the cruiser CHOSE, once they have chosen. nil means they have not,
    /// and the free-text row is decided by the stored code instead — a code
    /// the active list doesn't carry (imported data, a species typed on
    /// another region's setting) must stay editable rather than silently
    /// snapping to the first preset.
    ///
    /// Held as an answer rather than recomputed on appear: a cruiser who picks
    /// "Other code…" and has not typed anything yet has an empty code, and
    /// re-deriving from that would take the field away while they are looking
    /// at it.
    @State private var freeTextChoice: Bool?

    private var freeTextCode: Bool {
        freeTextChoice ?? (!trimmed.isEmpty && presetCode(matching: trimmed) == nil)
    }

    /// Picker tag for "a code that isn't in the list". Not a legal species
    /// code, so it can never collide with a real one.
    private static let otherTag = "\u{1}other"

    var body: some View {
        Picker(TreeFormWords.species, selection: selection) {
            Text("— Unspecified —").tag("")
            ForEach(options, id: \.0) { code, name in
                Text(Self.pickerLabel(name: name, code: code)).tag(code)
            }
            Text("Other code…").tag(Self.otherTag)
        }
        .accessibilityIdentifier("\(identifierPrefix).speciesPicker")

        // Escape hatch: a code the country/region list doesn't carry. Typing
        // here stores the code verbatim.
        if freeTextCode {
            HStack {
                Text(TreeFormWords.speciesCode)
                Spacer()
                TextField("e.g. ABCO", text: $code)
                    .multilineTextAlignment(.trailing)
                    .autocorrectionDisabled()
                    #if os(iOS)
                    .textInputAutocapitalization(.characters)
                    #endif
                    .onChange(of: code) { _, _ in onEdited() }
                    .accessibilityIdentifier("\(identifierPrefix).speciesCodeField")
            }
        }

        // The resolved common name for whatever code is set — the whole point
        // of picking by name is knowing "DF" is a Douglas-fir.
        TreeFormRows.readOnly(
            label: TreeFormWords.speciesName,
            value: trimmed.isEmpty
                ? TreeFormWords.none
                : (resolvedName ?? "Not in this region's list"))
    }

    private var trimmed: String {
        code.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Species offered by the ACTIVE country (and, for the US, region), plus
    /// the same "Other" catch-all the scan-time sheet carries.
    private var options: [(String, String)] {
        settings.country.speciesPresets(region: settings.region) + [("OT", "Other")]
    }

    /// The preset entry whose code matches `code` case-insensitively, so a
    /// stored "df" still selects "Douglas-fir · DF".
    private func presetCode(matching code: String) -> String? {
        options.first { $0.0.caseInsensitiveCompare(code) == .orderedSame }?.0
    }

    private var selection: Binding<String> {
        Binding(
            get: {
                if freeTextCode { return Self.otherTag }
                let current = trimmed
                if current.isEmpty { return "" }
                return presetCode(matching: current) ?? Self.otherTag
            },
            set: { new in
                if new == Self.otherTag {
                    // Hand the field over to the cruiser; don't keep a preset
                    // code sitting in it that they'd have to clear first.
                    freeTextChoice = true
                    if presetCode(matching: trimmed) != nil { code = "" }
                } else {
                    freeTextChoice = false
                    code = new
                }
                onEdited()
            })
    }

    /// Common name for the current code, or nil when it doesn't resolve
    /// (`RegionalSpecies.name(forCode:)` echoes the code back for unknowns).
    private var resolvedName: String? {
        let current = trimmed
        guard !current.isEmpty else { return nil }
        let name = RegionalSpecies.name(forCode: current)
        return name.caseInsensitiveCompare(current) == .orderedSame ? nil : name
    }

    /// Name-first option label — the common name reads prominently, the code
    /// trails dim (" · DF"). Identical treatment to `ScanMetadataSheet`.
    private static func pickerLabel(name: String, code: String) -> AttributedString {
        var label = AttributedString(name)
        var suffix = AttributedString(" · \(code)")
        suffix.foregroundColor = ForestixPalette.textSecondary
        suffix.font = .caption
        label.append(suffix)
        return label
    }
}

// MARK: - What the form works out from what was measured

/// The Computed section's two numbers. Both are derived, both come from the
/// app's own engine, and both say "—" rather than standing in for an input the
/// tree does not have.
@MainActor
enum TreeComputed {

    /// BA = π · DBH² / 4, straight from `InventoryEngine.basalAreaM2` — the
    /// same function every plot and stand total is summed from, so a cruiser
    /// can add these up and get the app's own answer.
    ///
    /// A stem's cross-section is an area, not a density, so it reads in the
    /// cruiser's own square unit: m² metric, ft² imperial.
    static func basalAreaText(dbhCm: Double?, in system: UnitSystem) -> String {
        guard let dbhCm, dbhCm > 0 else { return TreeFormWords.none }
        let m2 = Double(basalAreaM2(dbhCm: Float(dbhCm)))
        switch system {
        case .metric:
            return String(format: "%.3f m²", m2)
        case .imperial:
            return String(format: "%.2f ft²", Units.squareMetersToSquareFeet(m2))
        }
    }

    /// Total stem volume from the species' own equation.
    ///
    /// PRESENTED EXACTLY AS THE STAND SUMMARY PRESENTS IT, and for the same
    /// reasons. That screen shows cubic metres and nothing else — no board
    /// feet, on any unit system — and where the active country's stem-volume
    /// standard has no coefficients in the app it refuses the number outright
    /// and says so. Both rules are kept here. A per-tree volume is three
    /// orders of magnitude smaller than a per-hectare one, so it carries three
    /// decimals where that screen carries one; the quantity and the unit are
    /// the same.
    ///
    /// A species with no equation configured reads "—". The plot totals let a
    /// missing equation contribute 0, which is right for a sum and wrong for
    /// one stem: 0 m³ on a tree row is a claim about that tree.
    ///
    /// The equation is passed in rather than looked up here: this row is read
    /// on every render of the form, and `equation(forSpecies:)` is two Core
    /// Data fetches. The callers resolve it when the species changes.
    static func volumeText(dbhCm: Double?,
                           heightM: Double?,
                           equation: (any InventoryEngine.VolumeEquation)?,
                           settings: AppSettings) -> String {
        if settings.country.volumeStandard.isPending { return TreeFormWords.volumePending }
        guard let dbhCm, dbhCm > 0,
              // The same height floor `PlotStatsCalculator` applies before it
              // will ask an equation for a volume.
              let heightM, heightM > 1.3,
              let equation
        else { return TreeFormWords.none }
        let m3 = equation.totalVolumeM3(dbhCm: Float(dbhCm), heightM: Float(heightM))
        guard m3 > 0 else { return TreeFormWords.none }
        return String(format: "%.3f m³", m3)
    }

    /// The engine equation configured for a species, or nil when the code is
    /// blank, the species is unknown here, it names no equation, or it names
    /// one whose form the factory does not build.
    static func equation(forSpecies code: String?,
                         environment: AppEnvironment)
    -> (any InventoryEngine.VolumeEquation)? {
        let trimmed = (code ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let species = try? environment.speciesRepository.read(code: trimmed),
              let record = try? environment.volumeEquationRepository
                .read(id: species.volumeEquationId)
        else { return nil }
        return VolumeEquationFactory.make(from: record)
    }
}

// MARK: - Setting a measured time

/// The one picker both kinds of tree set their measured time in.
///
/// The RULE it obeys is `MeasuredTimeInput`'s — to the minute, never in the
/// future — so a cruise tree and a quick reading cannot end up with two ideas
/// of what a settable time is. It takes the INSTANT rather than a record,
/// because both kinds set a time here: a quick reading, which also carries
/// where its time came from and passes it as `sourceText`, and a cruise tree,
/// which has no such column and passes nil rather than guessing. The caller
/// supplies the footer, because what a hand-set time costs differs between the
/// two stores and the cruiser is told which one they are standing in.
///
/// The refusal is LIVE: it is recomputed as the wheel turns, so Save is never
/// a surprise and the sentence is on screen before the cruiser commits — the
/// same rule the coordinate editor follows. Android's
/// `MeasuredTimeEditorDialog` takes the same five things and refuses on the
/// same rule.
struct MeasuredTimeSheet: View {

    @Binding var draft: Date
    /// Why the picked time cannot be stored, nil when it can. Written here as
    /// the picker moves, and writable by the CALLER for the one failure this
    /// sheet cannot see — a store that refused the write.
    @Binding var refusal: String?
    /// What is being re-timed, in the cruiser's own words: a tree's name, or
    /// the kind of reading.
    let subject: String
    /// What the record's time currently CLAIMS, in the same word the
    /// `time_source` column exports, so the cruiser can see on screen what an
    /// analyst will read in the CSV. nil for a record that has no such column,
    /// which then says nothing here rather than inventing an answer.
    let sourceText: String?
    let footer: String
    let identifier: String
    let onCancel: () -> Void
    let onSave: () -> Void

    /// Whether the picked time may be STORED. Decided here rather than taken
    /// from `refusal`: the picker moves inside this sheet, so this is the only
    /// place that knows what it holds — and a caller's own failure sentence
    /// must not disable the Save that would retry it.
    private var ruleRefusal: String? {
        if case .refused(let why) = MeasuredTimeInput.resolve(draft) { return why }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text(subject)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                    DatePicker(MeasuredTimeInput.Words.label,
                               selection: $draft,
                               displayedComponents: [.date, .hourAndMinute])
                        .accessibilityIdentifier(identifier)
                    if let sourceText {
                        TreeFormRows.readOnly(label: MeasuredTimeSheet.sourceLabel,
                                              value: sourceText)
                    }
                } footer: {
                    Text(footer)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
                if let refusal {
                    Section {
                        Text(refusal)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.confidenceBad)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .navigationTitle(MeasuredTimeInput.Words.editorTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(MeasuredTimeInput.Words.cancel) { onCancel() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(MeasuredTimeInput.Words.save) { onSave() }
                        .disabled(ruleRefusal != nil)
                }
            }
            // Refuse as they turn the wheel — and on the way in, so a record
            // that already holds an unstorable time says why the moment the
            // sheet opens rather than at the first touch.
            .onAppear { refusal = ruleRefusal }
            .onChange(of: draft) { _, _ in refusal = ruleRefusal }
        }
    }

    /// The row label for `sourceText`. The same words the field log's own
    /// time-source row has always used, kept here so the two callers cannot
    /// label one fact two ways.
    static let sourceLabel = "Time source"
}
