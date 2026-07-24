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
    @State private var rerunSummary: String?
    @State private var isRerunning = false
    @State private var confirmClear = false
    #if os(iOS)
    @State private var shareURL: URL?
    #endif

    public init() {}

    public var body: some View {
        List {
            if summaries.isEmpty {
                Section {
                    Text("No raw captures yet. Turn on “Record raw captures”, then measure a tree in Developer mode.")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
            } else {
                Section {
                    Button {
                        rerunAll()
                    } label: {
                        HStack {
                            Label("Re-run all", systemImage: "arrow.triangle.2.circlepath")
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
                    Text("Re-run current estimators")
                }

                Section {
                    NavigationLink {
                        DBHAlgorithmSweepView()
                    } label: {
                        Label("Compare algorithms (DBH)", systemImage: "list.number")
                    }
                    .accessibilityIdentifier("rawCaptures.compareAlgorithms")
                } header: {
                    Text("Accuracy validation")
                } footer: {
                    Text("Runs every candidate DBH algorithm over the stored captures from the same depth bytes and ranks them by error vs your entered ground truth.")
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
                    Text("Captures (\(summaries.count))")
                }
            }
        }
        .navigationTitle("Raw Captures")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            #if os(iOS)
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        if let url = RawCaptureStore.exportZIP() { shareURL = url }
                    } label: {
                        Label("Export ZIP", systemImage: "square.and.arrow.up")
                    }
                    .disabled(summaries.isEmpty)
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
        .confirmationDialog("Delete every raw capture?",
                            isPresented: $confirmClear,
                            titleVisibility: .visible) {
            Button("Delete all", role: .destructive) {
                RawCaptureStore.clearAll()
                reload()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This erases all recorded bundles on this device. This cannot be undone.")
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
        summaries = RawCaptureStore.list()
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
        .navigationTitle("Capture")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear(perform: load)
    }

    private var unit: String { manifest?.kind == "height" ? "m" : "cm" }

    @ViewBuilder
    private func overviewSection(_ m: RawCaptureManifest) -> some View {
        Section {
            labeled("Kind", m.kind.uppercased())
            labeled("Live value", String(format: "%.2f %@", m.resultLive.value, unit))
            labeled("σ", String(format: "%.2f", m.resultLive.sigma))
            labeled("Tier", m.resultLive.tier)
            labeled("Accepted", m.resultLive.accepted ? "yes" : "no")
            labeled("Self-check", m.replaySelfcheck.status
                    + (m.replaySelfcheck.delta.map { String(format: " (Δ %.3f)", $0) } ?? ""))
        } header: {
            Text("Result")
        }
    }

    @ViewBuilder
    private func comparisonSection(_ m: RawCaptureManifest) -> some View {
        Section {
            labeled("Original (live)", String(format: "%.2f %@", m.resultLive.value, unit))
            labeled("Re-run (current)",
                    didRerun ? (rerunPrimary.map { String(format: "%.2f %@", $0, unit) } ?? "—")
                             : "tap Re-run")
            if didRerun, let r = rerunPrimary {
                labeled("Δ re-run − live", String(format: "%+.3f %@", r - m.resultLive.value, unit))
            }
            if m.kind == "height", didRerun {
                labeled("Re-run (d_h re-derived)",
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
                TextField(m.kind == "height" ? "True height (m)" : "True Ø (cm)",
                          text: $truthText)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("rawCaptures.detail.truthField")
                Button("Save") {
                    let value = Double(truthText)
                    RawCaptureStore.updateTruth(id: id, value: value)
                    load()
                    onChange()
                }
                .buttonStyle(.forestixProminent)
                .frame(width: 90)
            }
        } header: {
            Text("Ground truth")
        } footer: {
            Text("Persists into the bundle manifest; used as the reference in Re-run comparisons.")
        }
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

    var body: some View {
        List {
            if isRunning {
                Section {
                    HStack {
                        ProgressView()
                        Text("Sweeping…").foregroundStyle(ForestixPalette.textSecondary)
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
        .navigationTitle("Compare algorithms")
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
            if let best = r.rankings.first(where: { $0.n > 0 }) {
                labeled("Best (lowest RMSE)", best.name)
            }
        } header: {
            Text("Corpus")
        } footer: {
            if r.scored == 0 {
                Text("No captures have a truth value yet. Enter true diameters on individual captures, then return here.")
            } else {
                Text("Ranked over the \(r.scored) capture\(r.scored == 1 ? "" : "s") that have a ground-truth diameter.")
            }
        }
    }

    // MARK: Ranking table

    @ViewBuilder
    private func rankingSection(_ r: RawCaptureReplay.DBHSweepReport) -> some View {
        Section {
            rankHeaderRow
            ForEach(Array(r.rankings.enumerated()), id: \.element.algorithmId) { idx, rank in
                rankRow(rank, isWinner: idx == 0 && rank.n > 0)
            }
        } header: {
            Text("Ranking (best first)")
        } footer: {
            Text("bias = mean(estimate − truth); RMSE / MAE in cm. Lower RMSE is better; bias shows systematic over/under-estimation.")
        }
    }

    private var rankHeaderRow: some View {
        HStack(spacing: 4) {
            Text("Algorithm").frame(maxWidth: .infinity, alignment: .leading)
            Text("n").frame(width: 28, alignment: .trailing)
            Text("bias").frame(width: 52, alignment: .trailing)
            Text("RMSE").frame(width: 52, alignment: .trailing)
            Text("MAE").frame(width: 52, alignment: .trailing)
        }
        .font(.system(size: 11, weight: .semibold, design: .monospaced))
        .foregroundStyle(ForestixPalette.textSecondary)
    }

    private func rankRow(_ rank: RawCaptureReplay.AlgorithmRanking, isWinner: Bool) -> some View {
        HStack(spacing: 4) {
            Text(rank.name)
                .frame(maxWidth: .infinity, alignment: .leading)
                .lineLimit(1)
            Text("\(rank.n)").frame(width: 28, alignment: .trailing)
            if rank.n > 0 {
                Text(String(format: "%+.2f", rank.bias)).frame(width: 52, alignment: .trailing)
                Text(String(format: "%.2f", rank.rmse)).frame(width: 52, alignment: .trailing)
                Text(String(format: "%.2f", rank.mae)).frame(width: 52, alignment: .trailing)
            } else {
                Text("—").frame(width: 52, alignment: .trailing)
                Text("—").frame(width: 52, alignment: .trailing)
                Text("—").frame(width: 52, alignment: .trailing)
            }
        }
        .font(.system(size: 12, weight: isWinner ? .bold : .regular, design: .monospaced))
        .foregroundStyle(isWinner ? ForestixPalette.confidenceOk : ForestixPalette.textPrimary)
        .accessibilityIdentifier("rawCaptures.sweep.rankRow")
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
            Text("Per capture (winner highlighted)")
        }
    }

    private func captureAlgoRow(_ e: RawCaptureReplay.DBHSweepEntry,
                                truth: Double, isWinner: Bool) -> some View {
        HStack(spacing: 4) {
            Text(isWinner ? "▶ \(e.name)" : e.name)
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
            let items = RawCaptureStore.list()
            let out = RawCaptureReplay.rankDBH(items)
            await MainActor.run {
                report = out
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
