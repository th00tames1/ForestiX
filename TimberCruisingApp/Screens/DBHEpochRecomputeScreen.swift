// PREVIEW-THEN-COMMIT over the cruise diameters (Settings › Developer ›
// Raw captures › Recompute diameters).
//
// It lives in the raw-captures console because that is where the evidence is:
// a tree only HAS a bundle when raw capture was on, and this rewrites measured
// field data from those bundles. Nothing in this file decides anything — the
// rule, the tolerance and the skip reasons are `DBHEpochRecompute`, and the
// arithmetic is the shipped estimator underneath it.
//
// Two steps, always. The counts, the largest move, the mean move and every
// per-tree before/after are on screen BEFORE the confirm, because "47 diameters
// changed" is a thing the cruiser should get to read before it happens rather
// than after.

import SwiftUI
import Common
import Models
import Sensors

public struct DBHEpochRecomputeScreen: View {

    @EnvironmentObject private var environment: AppEnvironment

    @State private var plan: DBHEpochRecompute.Plan?
    @State private var isPlanning = false
    @State private var confirming = false
    /// What LANDED. Set only after a run, and it replaces the plan on screen
    /// so a stale preview can never be confirmed twice.
    @State private var resultText: String?

    public init() {}

    public var body: some View {
        List {
            Section {
                Button {
                    preview()
                } label: {
                    HStack {
                        Label("Check stored diameters",
                              systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        if isPlanning { ProgressView() }
                    }
                }
                .disabled(isPlanning)
                .accessibilityIdentifier("dbhEpoch.preview")
                if let resultText {
                    Text(resultText)
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .accessibilityIdentifier("dbhEpoch.result")
                }
            } header: {
                Text("Estimator epoch \(DBHEstimator.estimatorEpoch)")
            } footer: {
                Text(Self.explanation)
            }

            if let plan {
                summarySection(plan)
                if !plan.skips.isEmpty { skipSection(plan) }
                if !plan.changes.isEmpty { changeSection(plan) }
            }
        }
        .navigationTitle("Recompute diameters")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .alert("Rewrite \(plan?.changes.count ?? 0) diameters?",
               isPresented: $confirming) {
            Button("Rewrite", role: .destructive) { apply() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text(Self.confirmMessage)
        }
    }

    // MARK: - Sections

    @ViewBuilder
    private func summarySection(_ plan: DBHEpochRecompute.Plan) -> some View {
        Section {
            if plan.isEmpty {
                // A button that would do nothing is worse than a sentence
                // saying so — it invites the tap and then reports zero.
                Text(Self.nothingToDo)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .accessibilityIdentifier("dbhEpoch.nothing")
            } else {
                labeled("Trees to rewrite", "\(plan.changes.count)")
                labeled("Mean change", Self.signedCm(plan.meanDeltaCm))
                if let largest = plan.largestChange {
                    labeled("Largest change",
                            Self.signedCm(largest.deltaCm) + " · " + largest.treeLabel)
                }
                Button(role: .destructive) {
                    confirming = true
                } label: {
                    Label("Rewrite \(plan.changes.count) diameters",
                          systemImage: "square.and.pencil")
                }
                .accessibilityIdentifier("dbhEpoch.apply")
            }
        } header: {
            Text("What would change")
        } footer: {
            Text("\(plan.treesSeen) trees checked · \(plan.skips.count) left alone.")
        }
    }

    @ViewBuilder
    private func skipSection(_ plan: DBHEpochRecompute.Plan) -> some View {
        Section {
            ForEach(plan.skipGroups, id: \.reason.text) { group in
                HStack {
                    Text(group.reason.text)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                    Spacer()
                    Text("\(group.count)")
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textPrimary)
                }
                .accessibilityIdentifier("dbhEpoch.skipGroup")
            }
        } header: {
            Text("Left alone")
        } footer: {
            Text(Self.skipFooter)
        }
    }

    @ViewBuilder
    private func changeSection(_ plan: DBHEpochRecompute.Plan) -> some View {
        Section {
            ForEach(plan.changes, id: \.treeID) { change in
                VStack(alignment: .leading, spacing: 2) {
                    Text(change.treeLabel)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(Self.changeLine(change))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                .accessibilityIdentifier("dbhEpoch.changeRow")
            }
        } header: {
            Text("Every change")
        }
    }

    /// Key on the left, value on the right — the raw-captures console's own
    /// row, so the two screens read as one place.
    private func labeled(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k).foregroundStyle(ForestixPalette.textSecondary)
            Spacer()
            Text(v)
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }

    // MARK: - Wording (byte-identical to Android)

    static let explanation =
        "Re-derives each cruise diameter from the raw capture it was measured "
        + "from, with the estimator this build ships, and records the epoch "
        + "that produced it. Typed diameters, trees with no capture, and trees "
        + "whose capture cannot be identified are left alone. Heights are not "
        + "touched."

    static let nothingToDo =
        "Nothing to recompute. Every measured diameter with an identifiable "
        + "capture already carries this estimator epoch."

    static let confirmMessage =
        "This replaces measured field data. Every change is listed on this "
        + "screen; nothing else is written."

    static let skipFooter =
        "A tree is only rewritten when exactly one of its bundles still holds "
        + "the diameter the row holds. Anything less certain is left as the "
        + "cruiser recorded it."

    /// "+1.3 cm" / "-0.4 cm" — signed, because the direction is the point.
    static func signedCm(_ v: Double) -> String {
        String(format: "%+.1f cm", v)
    }

    /// "38.4 → 39.7 cm (+1.3)".
    static func changeLine(_ c: DBHEpochRecompute.Change) -> String {
        String(format: "%.1f → %.1f cm (%+.1f)",
               c.storedCm, c.recomputedCm, c.deltaCm)
    }

    /// The sentence after a run. Counts what reached the store, not what was
    /// planned — the two differ exactly when a row moved under the preview.
    static func resultText(_ r: DBHEpochRecompute.Result) -> String {
        var s = "Rewrote \(r.written) diameters at estimator epoch "
        s += "\(DBHEstimator.estimatorEpoch). "
        if r.stale > 0 {
            s += "\(r.stale) no longer held the value they were matched on and "
            s += "are unchanged. "
        }
        if r.failed > 0 {
            s += "\(r.failed) could not be written and are unchanged. "
        }
        return s
    }

    // MARK: - Actions

    /// Read the trees on the main actor, then plan (which replays depth
    /// bundles) off it. Writes nothing.
    private func preview() {
        guard !isPlanning else { return }
        isPlanning = true
        resultText = nil
        let projects = DBHEpochRecompute.loadProjects(environment: environment)
        Task.detached(priority: .userInitiated) {
            let summaries = RawCaptureStore.list()
            var merged = DBHEpochRecompute.Plan()
            for project in projects {
                let p = DBHEpochRecompute.plan(project: project, summaries: summaries)
                merged.changes += p.changes
                merged.skips += p.skips
            }
            let done = merged
            await MainActor.run {
                plan = done
                isPlanning = false
            }
        }
    }

    private func apply() {
        guard let changes = plan?.changes, !changes.isEmpty else { return }
        let result = DBHEpochRecompute.apply(changes,
                                             repository: environment.treeRepository)
        resultText = Self.resultText(result)
        // The preview is spent: every row it described has either been written
        // or re-read and refused. Re-run it to see the new state.
        plan = nil
    }
}
