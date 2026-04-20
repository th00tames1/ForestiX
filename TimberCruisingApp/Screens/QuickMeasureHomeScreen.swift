// Quick Measure entry point — the default Forestix home when "Advanced
// mode" is OFF (AppSettings.advancedMode == false).
//
// Why this exists: a cruiser who just wants one-off tree diameter or
// tree height readings shouldn't have to spin up a Project → Stratum →
// CruiseDesign → PlannedPlot → Plot → Tree chain. This screen launches
// DBHScanScreen / HeightScanScreen directly against the neutral
// `ProjectCalibration.identity`, and logs results to QuickMeasureHistory
// (a UserDefaults-backed sidecar — see QuickMeasureHistory.swift).
//
// Power users can flip `advancedMode` on inside Settings (reachable via
// the gear icon in the toolbar) to surface the full project workflow.

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
                VStack(alignment: .leading, spacing: 20) {
                    header
                    actionCards
                    historySection
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .navigationTitle("Forestix")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink {
                        SettingsScreen()
                    } label: {
                        Image(systemName: "gearshape")
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

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("빠른 측정").font(.largeTitle).bold()
            Text("나무 직경(DBH)과 수고를 바로 측정하세요. 프로젝트 설정은 필요 없어요.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Action cards

    private var actionCards: some View {
        VStack(spacing: 12) {
            actionCard(
                title: "직경 측정 (DBH)",
                subtitle: "LiDAR로 가슴높이 직경을 스캔",
                systemImage: "ruler",
                tint: .green
            ) {
                presentingDBHScan = true
            }
            .accessibilityIdentifier("quickMeasure.dbhButton")

            actionCard(
                title: "수고 측정 (Height)",
                subtitle: "AR로 나무 높이를 계산",
                systemImage: "arrow.up.and.down",
                tint: .blue
            ) {
                presentingHeightScan = true
            }
            .accessibilityIdentifier("quickMeasure.heightButton")
        }
    }

    private func actionCard(title: String,
                            subtitle: String,
                            systemImage: String,
                            tint: Color,
                            action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                ZStack {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(tint.opacity(0.15))
                        .frame(width: 56, height: 56)
                    Image(systemName: systemImage)
                        .font(.title)
                        .foregroundStyle(tint)
                }
                VStack(alignment: .leading, spacing: 4) {
                    Text(title).font(.headline).foregroundStyle(.primary)
                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.bold())
                    .foregroundStyle(.tertiary)
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 80, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Self.cardBackground)
            )
        }
        .buttonStyle(.plain)
    }

    // MARK: - History

    private var historySection: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("최근 측정").font(.headline)
                Spacer()
                if !history.entries.isEmpty {
                    Button {
                        shareURL = history.exportCSV()
                    } label: {
                        Label("CSV", systemImage: "square.and.arrow.up")
                            .font(.subheadline)
                    }
                    .accessibilityIdentifier("quickMeasure.exportCSV")
                }
            }
            if history.entries.isEmpty {
                emptyHistory
            } else {
                VStack(spacing: 8) {
                    ForEach(history.entries) { entry in
                        HistoryRow(entry: entry) {
                            history.delete(id: entry.id)
                        }
                    }
                }
            }
        }
        .padding(.top, 4)
    }

    private var emptyHistory: some View {
        VStack(spacing: 6) {
            Image(systemName: "tray")
                .font(.title2)
                .foregroundStyle(.tertiary)
            Text("아직 측정 기록이 없어요")
                .font(.subheadline)
                .foregroundStyle(.secondary)
            Text("측정을 시작하면 여기에 시간순으로 쌓입니다.")
                .font(.caption)
                .foregroundStyle(.tertiary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 24)
    }

    // MARK: - Scan covers (iOS only — AR sessions don't run on macOS host)

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(
                    calibration: .identity),
                onResult: { result in
                    history.append(QuickMeasureEntry(
                        kind: .dbh,
                        value: Double(result.diameterCm),
                        sigma: Double(result.sigmaRmm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue))
                    presentingDBHScan = false
                })
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
                viewModel: HeightScanViewModel(
                    calibration: .identity),
                onResult: { result in
                    history.append(QuickMeasureEntry(
                        kind: .height,
                        value: Double(result.heightM),
                        sigma: Double(result.sigmaHm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue))
                    presentingHeightScan = false
                })
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { presentingHeightScan = false }
                }
            }
        }
    }
    #endif

    // MARK: - Cross-platform card background

    fileprivate static var cardBackground: Color {
        #if os(iOS)
        return Color(uiColor: .secondarySystemBackground)
        #else
        return Color.gray.opacity(0.12)
        #endif
    }
}

// MARK: - History row

private struct HistoryRow: View {
    let entry: QuickMeasureEntry
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: entry.kind == .dbh ? "ruler" : "arrow.up.and.down")
                .foregroundStyle(entry.kind == .dbh ? Color.green : Color.blue)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(valueLabel)
                    .font(.headline)
                    .monospacedDigit()
                Text(secondaryLabel)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Menu {
                Button(role: .destructive) {
                    onDelete()
                } label: {
                    Label("삭제", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis")
                    .foregroundStyle(.secondary)
                    .padding(6)
                    .contentShape(Rectangle())
            }
            .accessibilityIdentifier("quickMeasure.row.menu")
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(QuickMeasureHomeScreen.cardBackground)
        )
    }

    private var valueLabel: String {
        switch entry.kind {
        case .dbh:
            return String(format: "DBH %.1f cm", entry.value)
        case .height:
            return String(format: "Height %.1f m", entry.value)
        }
    }

    private var secondaryLabel: String {
        let rel = RelativeDateTimeFormatter()
        rel.unitsStyle = .abbreviated
        let when = rel.localizedString(for: entry.createdAt, relativeTo: Date())
        let sigma: String
        if let s = entry.sigma, s > 0 {
            sigma = " · ±\(String(format: "%.1f", s))"
        } else {
            sigma = ""
        }
        return "\(when) · \(entry.confidenceRaw)\(sigma)"
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
