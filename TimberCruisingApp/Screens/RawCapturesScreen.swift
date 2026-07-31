// RAW-CAPTURE REPLAY — developer-mode UI over the stored bundles
// (Settings › Developer › Raw captures). Lets the owner re-run the CURRENT
// estimator code over recorded field data, enter/track ground truth, and
// export the whole corpus for offline analysis.
//
// Everything here is read/replay only — no production estimator math lives
// in this file; it calls RawCaptureReplay, which calls the same DBH/Height
// estimators the live capture used.

import SwiftUI
import Common
import Sensors

public struct RawCapturesScreen: View {

    @State private var summaries: [RawCaptureSummary] = []
    /// On-disk accounting. `summaries` only holds bundles whose manifest
    /// decoded; this is what the folder ACTUALLY contains, so a capture that
    /// exists but can't be read is visible instead of vanishing from every
    /// count on this screen.
    @State private var inventory = RawCaptureStore.Inventory(directories: 0, parsed: 0)
    @State private var rerunSummary: String?
    @State private var isRerunning = false
    /// The repair is two steps on purpose — see `previewRepair`. `pendingRepairs`
    /// is what Apply would write; empty means there is nothing to apply.
    @State private var pendingRepairs: [CaptureReadingMatch.Repair] = []
    @State private var repairSummary: String?
    @State private var isRepairing = false
    @EnvironmentObject private var history: QuickMeasureHistory
    @State private var confirmClear = false
    /// Export runs off the main actor and can fail; both states are visible.
    @State private var isExporting = false
    @State private var exportError: String?
    #if os(iOS)
    @State private var shareURL: URL?
    #endif

    public init() {}

    public var body: some View {
        List {
            if summaries.isEmpty {
                Section {
                    // "No captures yet" is only true when the FOLDER is empty
                    // too. With unreadable bundles on disk it was a flat lie —
                    // the exact failure this screen exists to make impossible.
                    if inventory.unparseable > 0 {
                        Text(unreadableNotice)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.confidenceWarn)
                            .accessibilityIdentifier("rawCaptures.unreadable")
                    } else {
                        Text("No captures yet. Turn on Settings › Developer › Record raw captures, then measure a tree.")
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
            } else {
                Section {
                    Button {
                        rerunAll()
                    } label: {
                        HStack {
                            Label("Re-run all (current estimators)",
                                  systemImage: "arrow.triangle.2.circlepath")
                            Spacer()
                            if isRerunning { ProgressView() }
                        }
                    }
                    .disabled(isRerunning)
                    .accessibilityIdentifier("rawCaptures.rerunAll")
                    if let s = rerunSummary {
                        Text(s)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .accessibilityIdentifier("rawCaptures.rerunSummary")
                    }
                } header: {
                    Text("Stored captures")
                }

                // Re-run above only REPORTS. This is the one that writes, so
                // it is a separate section, it previews first, and Apply only
                // appears once there is something to apply.
                Section {
                    Button {
                        previewRepair()
                    } label: {
                        HStack {
                            Label("Check readings against captures",
                                  systemImage: "checkmark.gobackward")
                            Spacer()
                            if isRepairing { ProgressView() }
                        }
                    }
                    .disabled(isRepairing)
                    .accessibilityIdentifier("rawCaptures.checkReadings")
                    if let s = repairSummary {
                        Text(s)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .accessibilityIdentifier("rawCaptures.repairSummary")
                    }
                    if !pendingRepairs.isEmpty {
                        Button(role: .destructive) {
                            applyRepair()
                        } label: {
                            Label("Apply \(pendingRepairs.count) correction\(pendingRepairs.count == 1 ? "" : "s")",
                                  systemImage: "square.and.pencil")
                        }
                        .accessibilityIdentifier("rawCaptures.applyRepair")
                    }
                } header: {
                    Text("Field log")
                } footer: {
                    Text("Replays each capture with the current estimator and compares it with the reading it produced. Readings you typed are never touched.")
                }

                Section {
                    NavigationLink {
                        DBHAlgorithmSweepView()
                    } label: {
                        Label("Compare algorithms (DBH)", systemImage: "list.number")
                    }
                    .accessibilityIdentifier("rawCaptures.compareAlgorithms")
                    NavigationLink {
                        HeightAlgorithmSweepView()
                    } label: {
                        Label("Compare algorithms (height)", systemImage: "list.number")
                    }
                    .accessibilityIdentifier("rawCaptures.compareAlgorithmsHeight")
                } header: {
                    Text("Accuracy validation")
                } footer: {
                    Text("Runs every candidate algorithm over the stored captures from the same recorded inputs and ranks them by error vs your entered ground truth — raw, plus an in-sample fitted bias/slope correction.")
                }

                Section {
                    ForEach(summaries) { sum in
                        NavigationLink {
                            RawCaptureDetailView(id: sum.id) { reload() }
                        } label: {
                            row(sum)
                        }
                    }
                    .onDelete(perform: deleteRows)
                } header: {
                    // Readable / on disk — the two numbers differ exactly when
                    // captures are unreadable, and the header says so first.
                    Text(inventory.unparseable > 0
                         ? "Captures (\(summaries.count) of \(inventory.directories) readable)"
                         : "Captures (\(summaries.count))")
                } footer: {
                    VStack(alignment: .leading, spacing: 4) {
                        if inventory.unparseable > 0 {
                            Text(unreadableNotice)
                                .foregroundStyle(ForestixPalette.confidenceWarn)
                                .accessibilityIdentifier("rawCaptures.unreadable")
                        }
                        // Free space sits WITH the corpus size: the field
                        // session that fills the phone stops recording, and you
                        // want to see that coming, not discover it at the truck.
                        Text(storageFooter)
                            .foregroundStyle(RawCaptureStore.isStorageLow()
                                             ? ForestixPalette.confidenceWarn
                                             : ForestixPalette.textSecondary)
                            .accessibilityIdentifier("rawCaptures.storage")
                        // WHAT THE ZIP IS, said where the ZIP is exported. The
                        // research CSV export splits itself against the field
                        // log; this one deliberately does not, because a
                        // complete capture archive is the thing worth having
                        // and a bundle whose reading was retaken is exactly
                        // what an accuracy study wants to see. Nothing is left
                        // out — so the honest notice is the inverse one: what
                        // is IN it that the field log no longer shows.
                        Text(Self.corpusCompletenessNotice)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .accessibilityIdentifier("rawCaptures.completeness")
                    }
                }
            }
        }
        .navigationTitle("Raw captures")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        exportCorpus()
                    } label: {
                        Label(isExporting ? "Exporting…" : "Export ZIP",
                              systemImage: "square.and.arrow.up")
                    }
                    .disabled(summaries.isEmpty || isExporting)
                    Button(role: .destructive) {
                        confirmClear = true
                    } label: {
                        Label("Clear all", systemImage: "trash")
                    }
                    .disabled(summaries.isEmpty)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityIdentifier("rawCaptures.menu")
            }
            #endif
        }
        #if os(iOS)
        .sheet(item: Binding(
            get: { shareURL.map(ShareURLItem.init) },
            set: { shareURL = $0?.url })
        ) { item in
            RawCaptureShareSheet(url: item.url)
        }
        #endif
        .alert("Export failed",
               isPresented: Binding(get: { exportError != nil },
                                    set: { if !$0 { exportError = nil } })) {
            Button("OK", role: .cancel) { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        // ALERT, not a confirmationDialog: a destructive confirmation is
        // centred and reads the same wherever the control that raised it sits.
        // Same rule as every other delete in this app; see the field log's
        // delete for the full argument.
        .alert("Clear all raw captures?", isPresented: $confirmClear) {
            Button("Delete all", role: .destructive) {
                RawCaptureStore.clearAll()
                reload()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Deletes every stored bundle (depth, poses, images). This cannot be undone.")
        }
        .onAppear(perform: reload)
    }

    // MARK: - Row

    @ViewBuilder
    private func row(_ sum: RawCaptureSummary) -> some View {
        let m = sum.manifest
        HStack(spacing: ForestixSpace.sm) {
            VStack(alignment: .leading, spacing: 2) {
                Text(titleLine(m))
                    .font(ForestixType.bodyBold)
                    .foregroundStyle(ForestixPalette.textPrimary)
                Text(subtitleLine(m))
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
            Spacer()
            selfcheckBadge(m.replaySelfcheck.status)
        }
        .accessibilityIdentifier("rawCaptures.row")
    }

    private func titleLine(_ m: RawCaptureManifest) -> String {
        let unit = m.kind == "height" ? "m" : "cm"
        var s = String(format: "%@ %.1f %@", m.kind.uppercased(), m.resultLive.value, unit)
        if let t = m.truth.value {
            s += String(format: "  ·  truth %.1f", t)
        }
        return s
    }

    private func subtitleLine(_ m: RawCaptureManifest) -> String {
        var bits: [String] = [m.context.mode]
        if let n = m.context.treeNumber { bits.append("tree \(n)") }
        bits.append(shortDate(m.createdAt))
        return bits.joined(separator: " · ")
    }

    private func selfcheckBadge(_ status: String) -> some View {
        let pass = status == "pass"
        return Text(pass ? "self-check" : "check fail")
            .font(.system(size: 10, weight: .semibold, design: .monospaced))
            .foregroundStyle(.white)
            .padding(.horizontal, 6).padding(.vertical, 3)
            .background(pass ? ForestixPalette.confidenceOk : ForestixPalette.confidenceBad,
                        in: Capsule())
    }

    // MARK: - Actions

    private func reload() {
        // Explicit refresh point: clear out sidecar-only leftovers from an
        // earlier build so they neither inflate "unreadable" nor reach the ZIP.
        RawCaptureStore.reapOrphanBundles()
        let listing = RawCaptureStore.listing()
        summaries = listing.summaries
        inventory = listing.inventory
    }

    /// The ZIP holds every bundle, including ones the field log has moved on
    /// from. Byte-identical to the Android sibling.
    static let corpusCompletenessNotice =
        "Export ZIP is the COMPLETE corpus, not the field log: a bundle whose "
        + "reading was deleted or retaken is still in it, and a ground truth "
        + "the field log has since corrected keeps its original value here. "
        + "Nothing is filtered out."

    /// "X MB on device · Y GB free".
    private var storageFooter: String {
        let bytes = RawCaptureStore.totalSizeBytes()
        let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        return "\(size) on device · \(RawCaptureStore.freeSpaceText())"
    }

    /// The vanishing class, stated plainly. A bundle whose manifest.json is
    /// missing or won't decode is skipped by every reader on this screen, so
    /// it is absent from the count above, from both sweeps, and from every
    /// skip counter — while the arithmetic in those footers still balances.
    /// Counting the folders on disk is the only way the operator can see that
    /// captures exist which nothing can read.
    private var unreadableNotice: String {
        let n = inventory.unparseable
        return "\(n) capture folder\(n == 1 ? "" : "s") on disk cannot be read "
            + "(manifest.json missing or corrupt). \(n == 1 ? "It is" : "They are") "
            + "NOT in the count above, in either algorithm sweep, or in any skip "
            + "tally — export the corpus and inspect \(n == 1 ? "it" : "them") "
            + "before trusting the totals."
    }

    /// Zip the corpus OFF the main actor, streaming entry by entry to a temp
    /// file. The old path read the whole corpus into memory (twice) on the
    /// main thread from a SwiftUI button — on a real corpus that is a jetsam
    /// kill, and the field data is stranded on the phone with no way off.
    /// Failures now surface in an alert instead of silently doing nothing.
    private func exportCorpus() {
        #if os(iOS)
        guard !isExporting else { return }
        isExporting = true
        exportError = nil
        Task.detached(priority: .userInitiated) {
            do {
                let url = try RawCaptureStore.exportZIPStreaming()
                await MainActor.run {
                    shareURL = url
                    isExporting = false
                }
            } catch {
                let message = (error as? LocalizedError)?.errorDescription
                    ?? error.localizedDescription
                await MainActor.run {
                    exportError = message
                    isExporting = false
                }
            }
        }
        #endif
    }

    private func deleteRows(_ offsets: IndexSet) {
        for i in offsets { RawCaptureStore.delete(id: summaries[i].id) }
        reload()
    }

    /// Re-run the current estimators over every bundle and summarize the
    /// signed error against truth (where present) plus how many changed vs
    /// the recorded live value.
    private func rerunAll() {
        isRerunning = true
        let items = summaries
        Task.detached(priority: .userInitiated) {
            var errorsVsTruth: [Double] = []
            var changedVsLive = 0
            for sum in items {
                let m = sum.manifest
                let rerun: Double?
                if m.kind == "dbh" {
                    rerun = RawCaptureReplay.rerunDBH(manifest: m, id: sum.id)
                        .map { Double($0.diameterCm) }
                } else {
                    rerun = RawCaptureReplay.rerunHeight(manifest: m)
                        .map { Double($0.result.heightM) }
                }
                guard let r = rerun else { continue }
                if abs(r - m.resultLive.value) > max(1e-3 * abs(m.resultLive.value), 1e-6) {
                    changedVsLive += 1
                }
                if let t = m.truth.value { errorsVsTruth.append(r - t) }
            }
            let summary = Self.summaryText(
                total: items.count,
                errorsVsTruth: errorsVsTruth,
                changedVsLive: changedVsLive)
            await MainActor.run {
                rerunSummary = summary
                isRerunning = false
            }
        }
    }

    static func summaryText(total: Int, errorsVsTruth: [Double], changedVsLive: Int) -> String {
        var lines: [String] = []
        lines.append("\(total) bundle\(total == 1 ? "" : "s") re-run · \(changedVsLive) changed vs live")
        if errorsVsTruth.isEmpty {
            lines.append("No ground truth entered yet — add truth values to see error vs truth.")
        } else {
            let mean = errorsVsTruth.reduce(0, +) / Double(errorsVsTruth.count)
            let sorted = errorsVsTruth.sorted()
            let median = sorted[sorted.count / 2]
            lines.append(String(format: "vs truth (n=%d): mean %+.2f · median %+.2f",
                                errorsVsTruth.count, mean, median))
        }
        return lines.joined(separator: "\n")
    }

    /// Which stored readings disagree with their own capture, without
    /// changing anything. Always run before the repair, and shown on its own,
    /// because "45 readings will change" is a thing the cruiser should get to
    /// read before it happens rather than after.
    private func previewRepair() {
        isRepairing = true
        let items = summaries
        let entries = history.entries
        Task.detached(priority: .userInitiated) {
            let found = Self.plan(items: items, entries: entries)
            await MainActor.run {
                pendingRepairs = found
                repairSummary = Self.repairPreviewText(found)
                isRepairing = false
            }
        }
    }

    /// Write the previewed corrections into the field log.
    private func applyRepair() {
        let plan = pendingRepairs
        guard !plan.isEmpty else { return }
        let changed = history.repairValuesFromCaptures(CaptureReadingMatch.map(plan))
        // Report what LANDED, not what was planned. The store re-checks every
        // repair against the value it expected to find and skips any reading
        // that moved in between, so the two counts can legitimately differ —
        // and if they do, that is the interesting number.
        repairSummary = changed == plan.count
            ? "\(changed) reading\(changed == 1 ? "" : "s") corrected from their raw captures."
            : "\(changed) of \(plan.count) corrected — the rest no longer held the value they were matched on."
        pendingRepairs = []
    }

    /// Pure so it can run off the main actor and be reasoned about on its own:
    /// replay every bundle, then pair the results with the readings.
    static func plan(items: [RawCaptureSummary],
                     entries: [QuickMeasureEntry]) -> [CaptureReadingMatch.Repair] {
        var captures: [CaptureReadingMatch.Capture] = []
        for sum in items {
            let m = sum.manifest
            // CRUISE CAPTURES ARE SKIPPED, for the reason `TruthBackfill`
            // gives: a cruise capture's reading is a Tree/Stem record in Core
            // Data, not a `QuickMeasureEntry`, so there is nothing here to
            // pair it with and a tree number would collide across the two
            // worlds if we tried.
            guard m.context.mode != "cruise",
                  let kind = readingKind(m.kind),
                  let when = TruthBackfill.parseISO(m.createdAt)
            else { continue }
            let value: Double?
            if m.kind == "dbh" {
                value = RawCaptureReplay.rerunDBH(manifest: m, id: sum.id)
                    .map { Double($0.diameterCm) }
            } else {
                value = RawCaptureReplay.rerunHeight(manifest: m)
                    .map { Double($0.result.heightM) }
            }
            guard let v = value else { continue }
            captures.append(.init(
                bundleID: sum.id, kind: kind,
                treeNumber: m.context.treeNumber,
                plotID: m.context.plotId.flatMap(UUID.init(uuidString:)),
                createdAt: when, value: v,
                oppositeAxisValue: m.kind == "dbh"
                    ? RawCaptureReplay.rerunDBHOppositeAxis(manifest: m, id: sum.id)
                    : nil,
                storedValue: m.resultLive.value,
                sigma: m.resultLive.sigma > 0 ? m.resultLive.sigma : nil))
        }
        return CaptureReadingMatch.repairs(captures: captures, entries: entries)
    }

    /// A bundle's kind string as a field-log kind. Only the two kinds the
    /// estimators replay are repairable.
    private static func readingKind(_ raw: String) -> QuickMeasureEntry.Kind? {
        switch raw {
        case "dbh":    return .dbh
        case "height": return .height
        default:       return nil
        }
    }

    static func repairPreviewText(_ plan: [CaptureReadingMatch.Repair]) -> String {
        guard !plan.isEmpty else {
            return "No reading was measured on the wrong guide axis. Nothing to correct."
        }
        let factors = plan.map { $0.expected / $0.corrected }.sorted()
        let median = factors[factors.count / 2]
        var lines = ["\(plan.count) reading\(plan.count == 1 ? "" : "s") measured on the wrong guide axis."]
        lines.append(String(format: "Stored / correct: median %.3f", median))
        lines.append("Tap Apply to replace them with the values their captures give.")
        return lines.joined(separator: "\n")
    }

    private func shortDate(_ iso: String) -> String {
        // created_at is ISO8601 with fractional seconds; show the date +
        // HH:MM prefix without a heavy formatter round-trip.
        guard iso.count >= 16 else { return iso }
        let dayPart = iso.prefix(10)
        let timeStart = iso.index(iso.startIndex, offsetBy: 11)
        let timeEnd = iso.index(iso.startIndex, offsetBy: 16)
        return "\(dayPart) \(iso[timeStart..<timeEnd])"
    }
}

// MARK: - Detail

struct RawCaptureDetailView: View {

    let id: String
    var onChange: () -> Void

    @State private var manifest: RawCaptureManifest?
    @State private var truthText: String = ""
    /// Result of the last Save / Clear — the console never changes a stored
    /// truth without saying what it did.
    @State private var truthStatus: String?
    @State private var rerunPrimary: Double?
    @State private var rerunReposed: Double?
    @State private var didRerun = false

    var body: some View {
        Form {
            if let m = manifest {
                overviewSection(m)
                comparisonSection(m)
                truthSection(m)
                metaSection(m)
            } else {
                Text("Bundle unavailable.")
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
        }
        .navigationTitle("Capture detail")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear(perform: load)
    }

    private var unit: String { manifest?.kind == "height" ? "m" : "cm" }

    // This is the DESK console, not a field-entry surface: it types in the
    // bundle's own metric base, stated in the section header and in the
    // placeholder. There is no per-entry unit toggle here (that lives on the
    // scan screens, where the cruiser's active system decides), but the unit is
    // still RECORDED with the value, so every truth in the corpus carries a
    // truth_unit and nothing has to be inferred later.
    private var consoleQuantity: TruthInput.Quantity {
        manifest?.kind == "height" ? .height : .diameter
    }
    private var consoleUnit: TruthInput.Unit {
        TruthInput.defaultUnit(consoleQuantity, imperial: false)
    }

    @ViewBuilder
    private func overviewSection(_ m: RawCaptureManifest) -> some View {
        Section {
            labeled("Kind", m.kind.uppercased())
            labeled("Live value", String(format: "%.2f %@", m.resultLive.value, unit))
            labeled("σ", String(format: "%.2f", m.resultLive.sigma))
            labeled("Tier", m.resultLive.tier)
            // A thin burst (dusk / canopy / tracking loss) is recorded rather
            // than dropped — the count is visible so it can be weighed in
            // analysis. Height bundles carry 0–2 aim frames.
            if m.kind == "height" {
                labeled("Aim depth frames", "\(m.resultLive.frameCount)",
                        warn: m.resultLive.frameCount < 2)
            } else {
                labeled("Depth frames", "\(m.resultLive.frameCount)",
                        warn: m.resultLive.frameCount < 5)
            }
            labeled("Self-check", m.replaySelfcheck.status
                    + (m.replaySelfcheck.delta.map { String(format: " (Δ %.3f)", $0) } ?? ""))
            // TWO explicit booleans — the old single "accepted" flag meant
            // "fit not red" here and "operator accepted" on Android.
            labeled("Fit tier OK (record time)", m.resultLive.tierOk ? "yes" : "no")
            labeled("Operator accepted", m.resultLive.operatorAccepted ? "yes" : "no")
        } header: {
            Text("Result")
        }
    }

    @ViewBuilder
    private func comparisonSection(_ m: RawCaptureManifest) -> some View {
        Section {
            labeled("Live (recorded)", String(format: "%.2f %@", m.resultLive.value, unit))
            labeled("Re-run (current)",
                    didRerun ? (rerunPrimary.map { String(format: "%.2f %@", $0, unit) } ?? "—")
                             : "tap Re-run")
            if didRerun, let r = rerunPrimary {
                labeled("Δ re-run − live", String(format: "%+.3f %@", r - m.resultLive.value, unit))
            }
            if m.kind == "height", didRerun {
                labeled("Re-run (reposed d_h)",
                        rerunReposed.map { String(format: "%.2f %@", $0, unit) } ?? "—")
            }
            if let t = m.truth.value {
                labeled("Truth", String(format: "%.2f %@", t, unit))
                if didRerun, let r = rerunPrimary {
                    labeled("Δ re-run − truth", String(format: "%+.3f %@", r - t, unit))
                }
            }
            Button {
                rerun(m)
            } label: {
                Label("Re-run this capture", systemImage: "arrow.triangle.2.circlepath")
            }
            .accessibilityIdentifier("rawCaptures.detail.rerun")
        } header: {
            Text("Original vs re-run vs truth")
        }
    }

    @ViewBuilder
    private func truthSection(_ m: RawCaptureManifest) -> some View {
        Section {
            HStack {
                // ',' is accepted as the decimal separator and normalised.
                TextField(TruthInput.promptLabel(consoleQuantity, unit: consoleUnit),
                          text: $truthText)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("rawCaptures.detail.truthField")
                Button("Save") { saveTruth() }
                .buttonStyle(.forestixProminent)
                .frame(width: 90)
            }
            if let warning = truthWarning() {
                TruthFieldWarning(text: warning)
            }
            // Clearing a stored truth is EXPLICIT. Save used to write
            // `Double(text)` straight through, so an empty or mistyped field
            // wiped a good hand measurement on the way past.
            if m.truth.value != nil {
                Button(role: .destructive) {
                    truthStatus = RawCaptureStore.clearTruth(id: id) == .applied
                        ? "Truth cleared." : "Couldn't clear the truth."
                    load()
                    onChange()
                } label: {
                    Label("Clear stored truth", systemImage: "xmark.circle")
                }
                .accessibilityIdentifier("rawCaptures.detail.truthClear")
            }
            if let status = truthStatus {
                Text(status)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
        } header: {
            Text("Ground truth (\(unit))")
        } footer: {
            Text("Persists into the bundle manifest; used as the reference in Re-run comparisons. Blank or unreadable input is never saved — use Clear to remove a stored value.")
        }
    }

    /// Save guard: an empty or unparseable field NEVER overwrites a stored
    /// truth, and the input is left alone so nothing typed is lost.
    private func saveTruth() {
        // parsePositiveBASE, not parsePositive: the value stored is the metric
        // base and `consoleUnit` is what the field says it is being typed in.
        // They agree today only because this console is hardcoded metric — the
        // shared helper exists so that adding a toggle here cannot leave a
        // number unconverted under a truth_unit that says it was converted.
        guard let value = TruthInput.parsePositiveBase(truthText, unit: consoleUnit) else {
            truthStatus = TruthInput.normalized(truthText).isEmpty
                ? "Nothing entered — stored truth left as it was."
                : "Not a number — stored truth left as it was."
            return
        }
        switch RawCaptureStore.applyTruth(id: id, value: value, unit: consoleUnit) {
        case .applied:
            truthStatus = "Truth saved."
            load()
            onChange()
        case .pending:
            // Queued, not in the manifest yet (the writer is still running).
            // Neither reloaded nor cleared — `load()` would replace the typed
            // text with the manifest's older value.
            truthStatus = "Truth queued — waiting for the capture to finish writing"
        case .failed(let reason):
            truthStatus = "NOT saved — \(reason)"
        }
    }

    private func truthWarning() -> String? {
        TruthInput.fieldWarning(truthText, quantity: consoleQuantity, unit: consoleUnit)
    }

    @ViewBuilder
    private func metaSection(_ m: RawCaptureManifest) -> some View {
        Section {
            labeled("Device", m.device)
            labeled("App", m.appCommit)
            labeled("Algorithm", m.settings.algorithm)
            labeled("Capture mode", m.settings.captureMode)
            if let n = m.context.treeNumber { labeled("Tree", "\(n)") }
            labeled("Mode", m.context.mode)
            if let dbh = m.dbh {
                labeled("Frames", "\(dbh.frames.count)")
                if let f = dbh.frames.first {
                    labeled("Depth", "\(f.width)×\(f.height) \(f.format)")
                }
                labeled("Bracket", dbh.bracket.enabled ? "manual" : "auto")
            }
            if let h = m.height {
                labeled("d_h", String(format: "%.2f m", h.dHM))
                labeled("Pose samples", "\(h.poseSamples.count)")
                labeled("Anchor hit", h.anchor.hitType)
            }
            if let g = m.gps {
                labeled("GPS", String(format: "%.5f, %.5f ±%.0fm", g.lat, g.lon, g.accM))
            }
        } header: {
            Text("Provenance")
        }
    }

    /// `warn` tints the value — used for a thin frame count, which is kept
    /// (not dropped) but shouldn't pass unnoticed.
    private func labeled(_ k: String, _ v: String, warn: Bool = false) -> some View {
        HStack {
            Text(k).foregroundStyle(ForestixPalette.textSecondary)
            Spacer()
            Text(v)
                .font(ForestixType.dataSmall)
                .foregroundStyle(warn ? ForestixPalette.confidenceWarn
                                      : ForestixPalette.textPrimary)
                .multilineTextAlignment(.trailing)
        }
    }

    private func load() {
        manifest = RawCaptureStore.loadManifest(id: id)
        truthText = manifest?.truth.value.map { String(format: "%g", $0) } ?? ""
    }

    private func rerun(_ m: RawCaptureManifest) {
        let bundleID = id
        Task.detached(priority: .userInitiated) {
            let primary: Double?
            var reposed: Double?
            if m.kind == "dbh" {
                primary = RawCaptureReplay.rerunDBH(manifest: m, id: bundleID)
                    .map { Double($0.diameterCm) }
            } else {
                let r = RawCaptureReplay.rerunHeight(manifest: m)
                primary = r.map { Double($0.result.heightM) }
                reposed = r?.reposed.map { Double($0.heightM) }
            }
            await MainActor.run {
                rerunPrimary = primary
                rerunReposed = reposed
                didRerun = true
            }
        }
    }
}

// MARK: - DBH multi-algorithm sweep (accuracy validation)

/// Ranks every candidate DBH algorithm against the entered ground truth.
/// Read/replay only — all math is `RawCaptureReplay.rankDBH`, which runs the
/// production estimators over the stored bytes. Runs off the main actor like
/// the "Re-run all" self-check.
struct DBHAlgorithmSweepView: View {

    @State private var report: RawCaptureReplay.DBHSweepReport?
    @State private var isRunning = false
    /// Bundle folders the listing couldn't read — in none of the sweep's own
    /// counters, so they are reported separately rather than vanishing.
    @State private var unreadableOnDisk = 0

    var body: some View {
        List {
            if isRunning {
                Section {
                    HStack {
                        ProgressView()
                        Text("Comparing…").foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
            }
            if let r = report {
                summarySection(r)
                if r.rankings.contains(where: { $0.n > 0 }) {
                    rankingSection(r)
                    perCaptureSection(r)
                }
            }
        }
        .navigationTitle("Compare algorithms (DBH)")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear(perform: runIfNeeded)
    }

    // MARK: Summary

    @ViewBuilder
    private func summarySection(_ r: RawCaptureReplay.DBHSweepReport) -> some View {
        Section {
            labeled("DBH captures", "\(r.totalDBH)")
            labeled("Scored (with truth)", "\(r.scored)")
            labeled("Skipped (no truth)", "\(r.skippedNoTruth)")
            // Unreadable captures used to be tallied as "no truth" and vanish.
            labeled("Skipped (unreadable)", "\(r.skippedUnreadable)")
            // Bundles the LISTING couldn't read never reach this report at
            // all, so they get their own row instead of balancing out of it.
            if unreadableOnDisk > 0 {
                labeled("On disk, unreadable", "\(unreadableOnDisk)")
            }
            if let best = r.rankings.first(where: { $0.n > 0 }) {
                // No winner is crowned on a handful of captures.
                if best.n >= RawCaptureReplay.minRankingN {
                    labeled("Best (lowest raw RMSE)", best.name)
                } else {
                    labeled("Best (lowest raw RMSE)",
                            "not called (n < \(RawCaptureReplay.minRankingN))")
                }
            }
        } header: {
            Text("Corpus")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(SweepCopy.corpusFooter(
                    scored: r.scored,
                    noTruth: r.skippedNoTruth,
                    unreadable: r.skippedUnreadable,
                    total: r.totalDBH,
                    truthNoun: "diameter"))
                if let note = SweepCopy.unreadableOnDisk(unreadableOnDisk) {
                    Text(note).foregroundStyle(ForestixPalette.confidenceWarn)
                }
            }
        }
    }

    // MARK: Ranking table

    @ViewBuilder
    private func rankingSection(_ r: RawCaptureReplay.DBHSweepReport) -> some View {
        Section {
            AlgorithmRankingTable(rankings: r.rankings, unit: "cm")
            AlgorithmRankingLegend()
        } header: {
            Text("Algorithm ranking (DBH, vs truth)")
        } footer: {
            Text(SweepCopy.rankingFooter(unit: "cm"))
        }
    }

    // MARK: Per-capture breakdown

    @ViewBuilder
    private func perCaptureSection(_ r: RawCaptureReplay.DBHSweepReport) -> some View {
        Section {
            ForEach(r.perCapture, id: \.id) { cap in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(cap.treeNumber.map { "Tree \($0)" } ?? "Capture")
                            .font(ForestixType.bodyBold)
                        Spacer()
                        Text(String(format: "truth %.1f cm", cap.truth))
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                    ForEach(cap.entries, id: \.algorithmId) { e in
                        captureAlgoRow(e, truth: cap.truth, isWinner: e.algorithmId == cap.winnerId)
                    }
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("Per capture (★ = closest to truth)")
        }
    }

    private func captureAlgoRow(_ e: RawCaptureReplay.DBHSweepEntry,
                                truth: Double, isWinner: Bool) -> some View {
        HStack(spacing: 4) {
            Text(isWinner ? "★ \(e.name)" : "  \(e.name)")
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(1)
            if let v = e.value {
                Text(String(format: "%.1f", v)).frame(width: 56, alignment: .trailing)
                Text(String(format: "%+.1f", v - truth)).frame(width: 56, alignment: .trailing)
            } else {
                Text("N/A").frame(width: 56, alignment: .trailing)
                Text("—").frame(width: 56, alignment: .trailing)
            }
        }
        .font(.system(size: 11, weight: isWinner ? .bold : .regular, design: .monospaced))
        .foregroundStyle(isWinner ? ForestixPalette.confidenceOk
                                  : (e.value == nil ? ForestixPalette.textSecondary
                                                    : ForestixPalette.textPrimary))
    }

    // MARK: Layout helper

    private func labeled(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k).foregroundStyle(ForestixPalette.textSecondary)
            Spacer()
            Text(v)
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textPrimary)
        }
    }

    // MARK: Run

    private func runIfNeeded() {
        guard report == nil, !isRunning else { return }
        isRunning = true
        Task.detached(priority: .userInitiated) {
            let listing = RawCaptureStore.listing()
            let out = RawCaptureReplay.rankDBH(listing.summaries)
            let gap = listing.inventory.unparseable
            await MainActor.run {
                report = out
                unreadableOnDisk = gap
                isRunning = false
            }
        }
    }
}

// MARK: - Shared sweep copy (DBH + height, matched to Android)

/// ONE source for the sweep footers, so the DBH and Height views can't drift
/// apart from each other or from the Android sibling. Both platforms sort by
/// RAW RMSE; the corrected column is an in-sample calibration fit and is
/// labelled as such wherever it appears.
enum SweepCopy {

    /// "scored + skipped == total" is stated explicitly, so a capture that
    /// silently disappeared from the corpus is visible as an arithmetic gap.
    static func corpusFooter(scored: Int, noTruth: Int, unreadable: Int,
                             total: Int, truthNoun: String) -> String {
        let accounting = "\(scored) scored + \(noTruth) no truth "
            + "+ \(unreadable) unreadable = \(total) captures."
        if scored == 0 {
            return accounting + " No captures have a truth value yet — enter true "
                + truthNoun + "s on individual captures, then return here."
        }
        var out = accounting + " Ranked over the \(scored) capture"
            + (scored == 1 ? "" : "s") + " with a ground-truth \(truthNoun)."
        if unreadable > 0 {
            out += " Unreadable captures have a truth but their stored bytes "
                + "won't reconstruct — inspect them before trusting the corpus."
        }
        if scored < RawCaptureReplay.minRankingN {
            out += " Fewer than \(RawCaptureReplay.minRankingN) scored captures: "
                + "no winner is called yet."
        }
        return out
    }

    /// Appended to a sweep's corpus footer when the raw-captures folder holds
    /// bundle directories the listing can't read at all. Those captures are in
    /// NONE of the numbers above — including the "scored + skipped = total"
    /// identity, which balances precisely because they were never counted.
    static func unreadableOnDisk(_ n: Int) -> String? {
        guard n > 0 else { return nil }
        return "\(n) further capture folder\(n == 1 ? "" : "s") on disk cannot be "
            + "read (manifest.json missing or corrupt) and \(n == 1 ? "is" : "are") "
            + "in none of these numbers."
    }

    static func rankingFooter(unit: String) -> String {
        "bias = mean(estimate − truth); RMSE / MAE in \(unit). "
        + "Sorted by RAW RMSE — the corrected column never reorders the table. "
        + "corr, a, b come from an IN-SAMPLE fit truth ≈ a + b·estimate on these "
        + "same captures (a calibration fit, not held-out), so corr is optimistic; "
        + "a biased algorithm can look best only after correction. "
        + "No winner is highlighted until n ≥ \(RawCaptureReplay.minRankingN)."
    }
}

// MARK: - Shared ranking table (DBH + height)

/// The best-first ranking rendered as a horizontally-scrollable column table:
/// n · bias · RMSE(raw) · RMSE(corrected, in-sample) · a · b. Shared by both
/// sweep views so DBH and height stay structurally identical.
///
/// Ordering is by RAW RMSE on both platforms — the corrected number is an
/// in-sample calibration fit and must never be the sort key. The raw winner
/// (row 0) is bold-green ONLY once it has n ≥ `minRankingN`; under that the
/// numbers are still shown but nothing is crowned, because an RMSE ordering
/// over a handful of trees is noise. The lowest CORRECTED RMSE gets its cell
/// tinted (same n floor) so a systematically-biased algorithm that only wins
/// after correction is visible without reordering the table.
struct AlgorithmRankingTable: View {

    let rankings: [RawCaptureReplay.AlgorithmRanking]
    let unit: String

    /// Enough scored captures for a "best" claim to mean anything.
    private var winnerIsCallable: Bool {
        (rankings.first(where: { $0.n > 0 })?.n ?? 0) >= RawCaptureReplay.minRankingN
    }

    private var bestCorrectedId: String? {
        guard winnerIsCallable else { return nil }
        return rankings.filter { $0.n > 0 }
            .min { $0.rmseCorrected < $1.rmseCorrected }?.algorithmId
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 5) {
                header
                ForEach(Array(rankings.enumerated()), id: \.element.algorithmId) { idx, rank in
                    row(rank,
                        isWinner: idx == 0 && rank.n > 0 && winnerIsCallable,
                        isBestCorrected: rank.algorithmId == bestCorrectedId)
                }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 6) {
            Text("Algorithm").frame(width: 132, alignment: .leading)
            Text("n").frame(width: 24, alignment: .trailing)
            Text("bias").frame(width: 48, alignment: .trailing)
            Text("RMSE").frame(width: 48, alignment: .trailing)
            // "corr" is in-sample — spelled out in the header, not just the
            // footer, so the column can't be read as held-out accuracy.
            Text("corr*").frame(width: 52, alignment: .trailing)
            Text("a").frame(width: 48, alignment: .trailing)
            Text("b").frame(width: 44, alignment: .trailing)
        }
        .font(.system(size: 11, weight: .semibold, design: .monospaced))
        .foregroundStyle(ForestixPalette.textSecondary)
    }

    private func row(_ rank: RawCaptureReplay.AlgorithmRanking,
                     isWinner: Bool, isBestCorrected: Bool) -> some View {
        HStack(spacing: 6) {
            Text(rank.name).frame(width: 132, alignment: .leading).lineLimit(1)
            Text("\(rank.n)").frame(width: 24, alignment: .trailing)
            if rank.n > 0 {
                Text(String(format: "%+.2f", rank.bias)).frame(width: 48, alignment: .trailing)
                Text(String(format: "%.2f", rank.rmse)).frame(width: 48, alignment: .trailing)
                Text(String(format: "%.2f", rank.rmseCorrected))
                    .frame(width: 52, alignment: .trailing)
                    .foregroundStyle(isBestCorrected ? ForestixPalette.confidenceOk
                                                     : ForestixPalette.textPrimary)
                if rank.fitted {
                    Text(String(format: "%+.2f", rank.a)).frame(width: 48, alignment: .trailing)
                    Text(String(format: "%.3f", rank.b)).frame(width: 44, alignment: .trailing)
                } else {
                    Text("—").frame(width: 48, alignment: .trailing)
                    Text("—").frame(width: 44, alignment: .trailing)
                }
            } else {
                Text("—").frame(width: 48, alignment: .trailing)
                Text("—").frame(width: 48, alignment: .trailing)
                Text("—").frame(width: 52, alignment: .trailing)
                Text("—").frame(width: 48, alignment: .trailing)
                Text("—").frame(width: 44, alignment: .trailing)
            }
        }
        .font(.system(size: 12, weight: isWinner ? .bold : .regular, design: .monospaced))
        .foregroundStyle(isWinner ? ForestixPalette.confidenceOk : ForestixPalette.textPrimary)
        .accessibilityIdentifier("rawCaptures.sweep.rankRow")
    }
}

/// Footnote under the ranking table: what the starred column is.
struct AlgorithmRankingLegend: View {
    var body: some View {
        Text("* corr = in-sample corrected RMSE (calibration fit, not held-out)")
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.textSecondary)
    }
}

// MARK: - Height multi-algorithm sweep (accuracy validation)

/// Mirror of `DBHAlgorithmSweepView` over stored HEIGHT captures. Read/replay
/// only — all math is `RawCaptureReplay.rankHeight`, which runs the production
/// tangent estimator (and any registered height candidate) over the stored
/// tangent geometry. Runs off the main actor.
struct HeightAlgorithmSweepView: View {

    @State private var report: RawCaptureReplay.HeightSweepReport?
    @State private var isRunning = false
    /// Bundle folders the listing couldn't read — in none of the sweep's own
    /// counters, so they are reported separately rather than vanishing.
    @State private var unreadableOnDisk = 0

    var body: some View {
        List {
            if isRunning {
                Section {
                    HStack {
                        ProgressView()
                        Text("Comparing…").foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
            }
            if let r = report {
                summarySection(r)
                if r.rankings.contains(where: { $0.n > 0 }) {
                    rankingSection(r)
                    perCaptureSection(r)
                }
            }
        }
        .navigationTitle("Compare algorithms (height)")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear(perform: runIfNeeded)
    }

    // MARK: Summary

    @ViewBuilder
    private func summarySection(_ r: RawCaptureReplay.HeightSweepReport) -> some View {
        Section {
            labeled("Height captures", "\(r.totalHeight)")
            labeled("Scored (with truth)", "\(r.scored)")
            labeled("Skipped (no truth)", "\(r.skippedNoTruth)")
            labeled("Skipped (unreadable)", "\(r.skippedUnreadable)")
            // Bundles the LISTING couldn't read never reach this report at
            // all, so they get their own row instead of balancing out of it.
            if unreadableOnDisk > 0 {
                labeled("On disk, unreadable", "\(unreadableOnDisk)")
            }
            if let best = r.rankings.first(where: { $0.n > 0 }) {
                if best.n >= RawCaptureReplay.minRankingN {
                    labeled("Best (lowest raw RMSE)", best.name)
                } else {
                    labeled("Best (lowest raw RMSE)",
                            "not called (n < \(RawCaptureReplay.minRankingN))")
                }
            }
        } header: {
            Text("Corpus")
        } footer: {
            VStack(alignment: .leading, spacing: 4) {
                Text(SweepCopy.corpusFooter(
                    scored: r.scored,
                    noTruth: r.skippedNoTruth,
                    unreadable: r.skippedUnreadable,
                    total: r.totalHeight,
                    truthNoun: "height"))
                if let note = SweepCopy.unreadableOnDisk(unreadableOnDisk) {
                    Text(note).foregroundStyle(ForestixPalette.confidenceWarn)
                }
            }
        }
    }

    // MARK: Ranking table

    @ViewBuilder
    private func rankingSection(_ r: RawCaptureReplay.HeightSweepReport) -> some View {
        Section {
            AlgorithmRankingTable(rankings: r.rankings, unit: "m")
            AlgorithmRankingLegend()
        } header: {
            Text("Algorithm ranking (Height, vs truth)")
        } footer: {
            Text(SweepCopy.rankingFooter(unit: "m"))
        }
    }

    // MARK: Per-capture breakdown

    @ViewBuilder
    private func perCaptureSection(_ r: RawCaptureReplay.HeightSweepReport) -> some View {
        Section {
            ForEach(r.perCapture, id: \.id) { cap in
                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(cap.treeNumber.map { "Tree \($0)" } ?? "Capture")
                            .font(ForestixType.bodyBold)
                        Spacer()
                        Text(String(format: "truth %.1f m", cap.truth))
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                    ForEach(cap.entries, id: \.algorithmId) { e in
                        captureAlgoRow(e, truth: cap.truth, isWinner: e.algorithmId == cap.winnerId)
                    }
                }
                .padding(.vertical, 2)
            }
        } header: {
            Text("Per capture (★ = closest to truth)")
        }
    }

    private func captureAlgoRow(_ e: RawCaptureReplay.HeightSweepEntry,
                                truth: Double, isWinner: Bool) -> some View {
        HStack(spacing: 4) {
            Text(isWinner ? "★ \(e.name)" : "  \(e.name)")
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(1)
            if let v = e.value {
                Text(String(format: "%.2f", v)).frame(width: 56, alignment: .trailing)
                Text(String(format: "%+.2f", v - truth)).frame(width: 56, alignment: .trailing)
            } else {
                Text("N/A").frame(width: 56, alignment: .trailing)
                Text("—").frame(width: 56, alignment: .trailing)
            }
        }
        .font(.system(size: 11, weight: isWinner ? .bold : .regular, design: .monospaced))
        .foregroundStyle(isWinner ? ForestixPalette.confidenceOk
                                  : (e.value == nil ? ForestixPalette.textSecondary
                                                    : ForestixPalette.textPrimary))
    }

    // MARK: Layout helper

    private func labeled(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k).foregroundStyle(ForestixPalette.textSecondary)
            Spacer()
            Text(v)
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textPrimary)
        }
    }

    // MARK: Run

    private func runIfNeeded() {
        guard report == nil, !isRunning else { return }
        isRunning = true
        Task.detached(priority: .userInitiated) {
            let listing = RawCaptureStore.listing()
            let out = RawCaptureReplay.rankHeight(listing.summaries)
            let gap = listing.inventory.unparseable
            await MainActor.run {
                report = out
                unreadableOnDisk = gap
                isRunning = false
            }
        }
    }
}

// MARK: - Share sheet (iOS)

#if os(iOS)
private struct ShareURLItem: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct RawCaptureShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController, context: Context) {}
}
#endif
