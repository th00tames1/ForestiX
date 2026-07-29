// Phase 5 §5.3 TreeDetailScreen. REQ-TAL-006.
//
// Single-tree inspector.
//
// FIELD REPORT F8 — rebuilt for readability. What changed and why:
//   • DBH and Height are no longer two sections with their own headers and
//     their own sub-rows. They are two rows in ONE "Measurements" group:
//     label left, value + unit right. Every other measured quantity in the
//     group gets the same row treatment, so the eye reads one column of
//     labels and one column of numbers.
//   • The "Method" readout is gone. `.lidarDepth` / `.vioWalkoffTangent`
//     told a cruiser nothing; it is still on the record and in exports.
//   • Confidence stays, and the tier chip is now TAPPABLE — it opens a
//     plain-language explanation of what the tiers mean and what actually
//     drives them, so the criteria are learnable in the field instead of
//     being folklore.
//   • "Irregular" is gone (nobody set it; the flag is untouched on the model).
//   • "Placement" (bearing / distance from plot centre) is gone. Both were
//     hand-typed number fields, and a cruiser standing at a tree has no way
//     to type a meaningful bearing. The FIELDS SURVIVE on `Tree` and in every
//     export — the cruise chain still auto-computes `distanceFromCenterM`
//     from the capture fix. Only the manual UI went.
//   • Species is a PICKER over the active country/region list (name-first),
//     with a free-typed escape hatch for a code that isn't in the list, and
//     the resolved common name shown next to whatever is chosen.
//
// FIELD REPORT F6 — the read-only raw-metadata block is developer-mode only.
// A normal cruiser never sees `dbhCoverageDeg` / `heightAlphaTopDeg`.

import SwiftUI
import Models
import Common

public struct TreeDetailScreen: View {

    @StateObject private var viewModel: TreeDetailViewModel
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    /// Which measurement's tier explainer is open. nil = closed.
    @State private var explaining: TierExplainer.Kind?
    /// True once the cruiser has chosen "Other code…", or on appear when the
    /// stored code isn't in the active country/region list. Drives the
    /// free-text row under the picker.
    @State private var freeTextCode = false

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

    /// Picker tag for "a code that isn't in the list". Not a legal species
    /// code, so it can never collide with a real one.
    private static let otherTag = "\u{1}other"

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
            identitySection
            measurementsSection
            notesSection
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
            // A code the active list doesn't carry (imported data, a species
            // typed on another region's setting) must stay editable rather
            // than silently snapping to the first preset.
            freeTextCode = !trimmedCode.isEmpty && presetCode(matching: trimmedCode) == nil
            // Seeded here and nowhere else. Assigning the text does NOT run
            // the bindings' setters, so seeding can never be mistaken for the
            // cruiser typing — which is what keeps an unedited field from
            // writing its rounded self back over the stored measurement.
            dbhText = dbhPrefill
            heightText = heightPrefill
        }
        .sheet(item: $explaining) { kind in
            TierExplainer(kind: kind)
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

    // MARK: - Identity

    private var identitySection: some View {
        Section("Identity") {
            Picker("Species", selection: speciesSelection) {
                Text("— Unspecified —").tag("")
                ForEach(speciesOptions, id: \.0) { code, name in
                    Text(Self.pickerLabel(name: name, code: code)).tag(code)
                }
                Text("Other code…").tag(Self.otherTag)
            }
            .accessibilityIdentifier("treeDetail.speciesPicker")

            // Escape hatch: a code the country/region list doesn't carry.
            // Typing here stores the code verbatim.
            if freeTextCode {
                HStack {
                    Text("Code")
                    Spacer()
                    TextField("e.g. ABCO", text: $viewModel.speciesCode)
                        .multilineTextAlignment(.trailing)
                        .autocorrectionDisabled()
                        #if os(iOS)
                        .textInputAutocapitalization(.characters)
                        #endif
                        .onChange(of: viewModel.speciesCode) { _, _ in
                            viewModel.markDirty()
                        }
                        .accessibilityIdentifier("treeDetail.speciesCodeField")
                }
            }

            // The resolved common name for whatever code is set — the whole
            // point of picking by name is knowing "DF" is a Douglas-fir.
            if !trimmedCode.isEmpty {
                HStack {
                    Text("Name")
                    Spacer()
                    Text(resolvedSpeciesName ?? "Not in this region's list")
                        .foregroundStyle(.secondary)
                }
            }

            Picker("Status", selection: $viewModel.status) {
                Text("Live").tag(TreeStatus.live)
                Text("Dead standing").tag(TreeStatus.deadStanding)
                Text("Dead down").tag(TreeStatus.deadDown)
                Text("Cull").tag(TreeStatus.cull)
            }
            .onChange(of: viewModel.status) { _, _ in viewModel.markDirty() }

            if viewModel.tree.isMultistem {
                Label("One stem of a multi-stem tree", systemImage: "arrow.branch")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var trimmedCode: String {
        viewModel.speciesCode.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Species offered by the ACTIVE country (and, for the US, region),
    /// plus the same "Other" catch-all the scan-time sheet carries.
    private var speciesOptions: [(String, String)] {
        settings.country.speciesPresets(region: settings.region)
            + [("OT", "Other")]
    }

    /// The preset entry whose code matches `code` case-insensitively, so a
    /// stored "df" still selects "Douglas-fir · DF".
    private func presetCode(matching code: String) -> String? {
        speciesOptions.first {
            $0.0.caseInsensitiveCompare(code) == .orderedSame
        }?.0
    }

    private var speciesSelection: Binding<String> {
        Binding(
            get: {
                if freeTextCode { return Self.otherTag }
                let code = trimmedCode
                if code.isEmpty { return "" }
                return presetCode(matching: code) ?? Self.otherTag
            },
            set: { new in
                if new == Self.otherTag {
                    // Hand the field over to the cruiser; don't keep a preset
                    // code sitting in it that they'd have to clear first.
                    freeTextCode = true
                    if presetCode(matching: trimmedCode) != nil {
                        viewModel.speciesCode = ""
                    }
                } else {
                    freeTextCode = false
                    viewModel.speciesCode = new
                }
                viewModel.markDirty()
            })
    }

    /// Common name for the current code, or nil when it doesn't resolve
    /// (`RegionalSpecies.name(forCode:)` echoes the code back for unknowns).
    private var resolvedSpeciesName: String? {
        let code = trimmedCode
        guard !code.isEmpty else { return nil }
        let name = RegionalSpecies.name(forCode: code)
        return name.caseInsensitiveCompare(code) == .orderedSame ? nil : name
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

    // MARK: - Measurements (one group, label left / value right)

    private var measurementsSection: some View {
        Section("Measurements") {
            valueRow(label: "DBH", unit: "cm",
                     identifier: "treeDetail.dbh") {
                TextField("0.0", text: dbhBinding)
            }
            if !dbhEntryValid {
                entryWarning("A typed diameter must be a number greater than zero.")
            }
            confidenceRow(label: "DBH confidence",
                          tier: viewModel.tree.dbhConfidence,
                          kind: .diameter)

            valueRow(label: "Height", unit: "m",
                     identifier: "treeDetail.height") {
                TextField("—", text: heightBinding)
            }
            if !heightEntryValid {
                entryWarning("A typed height must be a number greater than zero.")
            }
            if let tier = viewModel.tree.heightConfidence {
                confidenceRow(label: "Height confidence",
                              tier: tier,
                              kind: .height)
            }
        }
    }

    // MARK: - Prefilling an editable measurement, without truncating it

    /// The stored DBH at the precision the rest of the app prints it —
    /// `MeasurementFormatter.diameter`'s one decimal, without the " cm" the
    /// row already carries. Android builds the identical string.
    private var dbhPrefill: String {
        MeasurementFormatter.entryText(Double(viewModel.tree.dbhCm), fractionDigits: 1)
    }

    /// The stored height, same rule. A tree with no height prefills empty, so
    /// the placeholder shows and clearing the field stays meaningful.
    private var heightPrefill: String {
        viewModel.tree.heightM.map {
            MeasurementFormatter.entryText(Double($0), fractionDigits: 1)
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
        } else if let typed = TruthInput.parsePositive(text) {
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
        } else if let typed = TruthInput.parsePositive(text) {
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

    /// Confidence row. The chip is a BUTTON: tapping it explains the tiers
    /// and what drives them for this measurement.
    private func confidenceRow(label: String,
                               tier: ConfidenceTier,
                               kind: TierExplainer.Kind) -> some View {
        Button {
            explaining = kind
        } label: {
            HStack(spacing: 8) {
                Text(label)
                    .foregroundStyle(ForestixPalette.textPrimary)
                Spacer(minLength: 8)
                tierBadge(tier)
                // The app's standard "there is more behind this" affordance.
                Image(systemName: "info.circle")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // "tier" is the spec's word, not a cruiser's — and it was the only
        // place the app still said it out loud. Byte-identical to the
        // Android sibling's contentDescription tail.
        .accessibilityHint("Explains what Good, Fair and Check mean")
        .accessibilityIdentifier("treeDetail.\(kind.rawValue)ConfidenceChip")
    }

    // MARK: - Notes

    private var notesSection: some View {
        Section("Notes") {
            TextField("Notes", text: $viewModel.notes, axis: .vertical)
                .lineLimit(2...5)
                .onChange(of: viewModel.notes) { _, _ in viewModel.markDirty() }
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

    /// The chip's WORD is the one word the app uses for that grade
    /// everywhere else — Good / Fair / Check, from `ConfidenceStyle`. It
    /// used to print the stored enum ("Green" / "Yellow" / "Red"), so this
    /// chip, the field log's quality column and the sheet this chip opens
    /// were three different vocabularies for one reading. The colour still
    /// carries the grade too; only the naming is unified.
    private func tierBadge(_ tier: ConfidenceTier) -> some View {
        let descriptor = ConfidenceStyle.descriptor(for: tier.rawValue)
        return HStack(spacing: 6) {
            Circle()
                .fill(descriptor.color)
                .frame(width: 10, height: 10)
            Text(descriptor.label)
                .font(.caption.bold())
                .foregroundStyle(descriptor.color)
        }
    }
}
