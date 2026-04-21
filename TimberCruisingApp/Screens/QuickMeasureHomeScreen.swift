// Quick Measure entry point — the default Forestix home when "Advanced
// mode" is OFF (AppSettings.advancedMode == false).
//
// A cruiser who just wants one-off tree diameter or tree height
// readings shouldn't have to spin up a Project → Stratum → CruiseDesign
// → PlannedPlot → Plot → Tree chain. This screen launches DBHScanScreen
// / HeightScanScreen directly against `ProjectCalibration.identity` and
// logs results into QuickMeasureHistory (UserDefaults-backed sidecar —
// see QuickMeasureHistory.swift).
//
// Power users flip `advancedMode` on inside Settings (gear icon in the
// toolbar) to surface the full project workflow.
//
// Layout philosophy (see DesignSystem.swift): this is a professional
// instrument. Measurement cards are restrained, the recent readings
// area reads like a field log — tabular monospaced values, muted tier
// chips, no saturated colour cards. A cruiser should feel like they're
// using a tool, not browsing a catalogue.

import SwiftUI
import Common
import Models
import Sensors

public struct QuickMeasureHomeScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory

    @State private var presentingDBHScan = false
    @State private var presentingHeightScan = false
    @State private var shareURL: URL?

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ForestixSpace.lg) {
                    masthead
                    instrumentSection
                    historySection
                }
                .padding(.horizontal, ForestixSpace.md)
                .padding(.top, ForestixSpace.sm)
                .padding(.bottom, ForestixSpace.xl)
            }
            .background(ForestixPalette.canvas.ignoresSafeArea())
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .principal) {
                    Text("FORESTIX")
                        .font(.system(size: 13, weight: .semibold, design: .default))
                        .tracking(2.0)
                        .foregroundStyle(ForestixPalette.textPrimary)
                }
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        SettingsScreen()
                    } label: {
                        Image(systemName: "gearshape")
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                    .accessibilityIdentifier("quickMeasure.settingsLink")
                }
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $presentingDBHScan) { dbhCover }
            .fullScreenCover(isPresented: $presentingHeightScan) { heightCover }
            .sheet(item: Binding(
                get: { shareURL.map(ShareWrapper.init) },
                set: { shareURL = $0?.url })
            ) { wrapper in
                QuickMeasureShareSheet(url: wrapper.url)
            }
            #endif
        }
    }

    // MARK: - Masthead

    private var masthead: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xxs) {
            Text("Quick measure")
                .font(ForestixType.title)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("LiDAR diameter · AR height · no project required")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
        .padding(.top, ForestixSpace.xs)
    }

    // MARK: - Instrument section

    private var instrumentSection: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            sectionHeader("INSTRUMENT")
            VStack(spacing: 0) {
                instrumentRow(
                    title: "Diameter",
                    subtitle: "Stem diameter via LiDAR · DBH recommended",
                    glyph: "ruler",
                    accessibilityId: "quickMeasure.dbhButton"
                ) { presentingDBHScan = true }

                Rectangle()
                    .fill(ForestixPalette.divider)
                    .frame(height: 0.5)
                    .padding(.leading, 56)

                instrumentRow(
                    title: "Height",
                    subtitle: "Tangent method via AR + IMU",
                    glyph: "arrow.up.and.down",
                    accessibilityId: "quickMeasure.heightButton"
                ) { presentingHeightScan = true }
            }
            .forestixPanel()
        }
    }

    private func instrumentRow(title: String,
                               subtitle: String,
                               glyph: String,
                               accessibilityId: String,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: ForestixSpace.sm) {
                ZStack {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 40, height: 40)
                    Image(systemName: glyph)
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                Spacer(minLength: ForestixSpace.xs)
                Image(systemName: "chevron.forward")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            .padding(.horizontal, ForestixSpace.md)
            .padding(.vertical, ForestixSpace.sm)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityId)
    }

    // MARK: - History section

    private var historySection: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            HStack(alignment: .firstTextBaseline) {
                sectionHeader("FIELD LOG")
                Spacer()
                if !history.entries.isEmpty {
                    Text("\(history.entries.count)")
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textTertiary)
                    Button {
                        shareURL = history.exportCSV()
                    } label: {
                        Label("Export CSV",
                              systemImage: "square.and.arrow.up")
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.primary)
                    }
                    .accessibilityIdentifier("quickMeasure.exportCSV")
                }
            }

            if history.entries.isEmpty {
                emptyLog
            } else {
                logTable
            }
        }
    }

    private var emptyLog: some View {
        VStack(alignment: .center, spacing: ForestixSpace.xs) {
            Image(systemName: "tray")
                .font(.system(size: 22, weight: .light))
                .foregroundStyle(ForestixPalette.textTertiary)
            Text("No readings yet")
                .font(ForestixType.body)
                .foregroundStyle(ForestixPalette.textSecondary)
            Text("Completed measurements appear here in chronological order.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textTertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, ForestixSpace.lg)
        .forestixPanel()
    }

    private var logTable: some View {
        VStack(spacing: 0) {
            logHeaderRow
            ForEach(history.entries.indices, id: \.self) { index in
                let entry = history.entries[index]
                LogEntryRow(entry: entry) {
                    history.delete(id: entry.id)
                }
                if index < history.entries.count - 1 {
                    Rectangle()
                        .fill(ForestixPalette.divider)
                        .frame(height: 0.5)
                        .padding(.leading, ForestixSpace.md)
                }
            }
        }
        .forestixPanel()
    }

    private var logHeaderRow: some View {
        HStack(spacing: ForestixSpace.sm) {
            Text("TYPE").frame(width: 52, alignment: .leading)
            Text("VALUE").frame(width: 96, alignment: .trailing)
            Text("±σ").frame(width: 60, alignment: .trailing)
            Spacer(minLength: 0)
            Text("QUALITY")
        }
        .font(.system(size: 10, weight: .semibold, design: .default))
        .tracking(1.2)
        .foregroundStyle(ForestixPalette.textTertiary)
        .padding(.horizontal, ForestixSpace.md)
        .padding(.vertical, ForestixSpace.xs)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(ForestixPalette.surfaceRaised.opacity(0.5))
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text)
            .font(ForestixType.sectionHead)
            .tracking(1.5)
            .foregroundStyle(ForestixPalette.textSecondary)
    }

    // MARK: - Scan covers (iOS only — AR sessions don't run on macOS host)

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(calibration: .identity),
                // Persist on Accept only so a retake doesn't pollute
                // the log with intermediate readings.
                onAccept: { result in
                    history.append(QuickMeasureEntry(
                        kind: .dbh,
                        value: Double(result.diameterCm),
                        sigma: Double(result.sigmaRmm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue))
                    presentingDBHScan = false
                },
                showMeshOverlay: true)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { presentingDBHScan = false }
                }
            }
        }
    }

    private var heightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(calibration: .identity),
                onAccept: { result in
                    history.append(QuickMeasureEntry(
                        kind: .height,
                        value: Double(result.heightM),
                        sigma: Double(result.sigmaHm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue))
                    presentingHeightScan = false
                },
                showMeshOverlay: true)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { presentingHeightScan = false }
                }
            }
        }
    }
    #endif
}

// MARK: - Log row

private struct LogEntryRow: View {
    let entry: QuickMeasureEntry
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: ForestixSpace.sm) {
            Text(typeLabel)
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textSecondary)
                .frame(width: 52, alignment: .leading)

            Text(valueText)
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(width: 96, alignment: .trailing)

            Text(sigmaText)
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(width: 60, alignment: .trailing)

            Spacer(minLength: 0)

            ConfidenceChip(rawTier: entry.confidenceRaw)

            Menu {
                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Label("Delete", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .padding(.horizontal, ForestixSpace.xs)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("quickMeasure.row.menu")
        }
        .padding(.horizontal, ForestixSpace.md)
        .padding(.vertical, ForestixSpace.sm)
        .overlay(alignment: .bottomLeading) {
            Text(timestampText)
                .font(.system(size: 10, weight: .regular, design: .default))
                .foregroundStyle(ForestixPalette.textTertiary)
                .padding(.leading, ForestixSpace.md)
                .padding(.bottom, 2)
                .allowsHitTesting(false)
        }
    }

    private var typeLabel: String {
        switch entry.kind {
        case .dbh:    return "DIA"
        case .height: return "HGT"
        }
    }

    private var valueText: String {
        switch entry.kind {
        case .dbh:    return String(format: "%.1f cm", entry.value)
        case .height: return String(format: "%.1f m",  entry.value)
        }
    }

    private var sigmaText: String {
        guard let s = entry.sigma, s > 0 else { return "—" }
        let unit = (entry.kind == .dbh) ? "mm" : "m"
        let v = (entry.kind == .dbh) ? s : s
        return String(format: "±%.1f %@", v, unit)
    }

    private var timestampText: String {
        let rel = RelativeDateTimeFormatter()
        rel.unitsStyle = .abbreviated
        return rel.localizedString(for: entry.createdAt, relativeTo: Date())
            .uppercased()
    }
}

// MARK: - Confidence chip

private struct ConfidenceChip: View {
    let rawTier: String

    var body: some View {
        let d = ConfidenceStyle.descriptor(for: rawTier)
        return Text(d.label.uppercased())
            .font(.system(size: 10, weight: .semibold, design: .default))
            .tracking(0.8)
            .padding(.horizontal, ForestixSpace.xs)
            .padding(.vertical, 3)
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.chip,
                                 style: .continuous)
                    .stroke(d.color, lineWidth: 0.75)
            )
            .foregroundStyle(d.color)
    }
}

// MARK: - Share-sheet wrapper (iOS only)

#if os(iOS)
private struct ShareWrapper: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct QuickMeasureShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController, context: Context) {}
}
#endif
