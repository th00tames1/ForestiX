// Quick Measure home — the default Forestix entry point (when
// AppSettings.advancedMode == false).
//
// Design pattern: hub-and-spoke. The home is deliberately minimal —
// a short masthead and four large navigation rows, each leading to a
// dedicated screen. No inline log table, no inline capacity banner,
// no embedded stats. Everything the cruiser can do lives behind one
// explicit tap.
//
// This replaces an earlier "dashboard-style" layout that stacked
// masthead + capacity warning + INSTRUMENT panel + FIELD LOG table
// on a single scroll view. In user testing that screen read as
// "crammed" — too many things competing for the cruiser's first
// glance. Now:
//
//   Home (this file)
//    ├─ Diameter      → DBHScanScreen   (fullScreenCover)
//    ├─ Height        → HeightScanScreen (fullScreenCover)
//    ├─ Field log     → FieldLogScreen   (NavigationLink)
//    └─ Settings      → SettingsScreen   (NavigationLink)
//
// Each spoke owns its own chrome; the hub just routes. Matches the
// hub-and-spoke pattern in Apple's HIG (and apps like Leica DISTO
// Plan, which surfaces its main features as a small grid of large
// tiles on the first screen).

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

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ForestixSpace.lg) {
                    masthead
                    hubRows
                    footerStats
                }
                .padding(.horizontal, ForestixSpace.md)
                .padding(.top, ForestixSpace.md)
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
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $presentingDBHScan) { dbhCover }
            .fullScreenCover(isPresented: $presentingHeightScan) { heightCover }
            #endif
        }
    }

    // MARK: - Masthead

    /// Single-line title. The earlier version had a marketing tagline
    /// underneath ("LiDAR diameter · AR height · no project required")
    /// that the designer review flagged as App Store copy — pro
    /// instrument tools don't need to pitch themselves on the power-on
    /// screen.
    private var masthead: some View {
        Text("Quick measure")
            .font(ForestixType.title)
            .foregroundStyle(ForestixPalette.textPrimary)
            .padding(.top, ForestixSpace.xs)
    }

    // MARK: - Hub rows

    private var hubRows: some View {
        VStack(spacing: ForestixSpace.sm) {
            HubRow(
                title: "Diameter",
                subtitle: "Breast-height scan via LiDAR",
                systemImage: "ruler",
                accessibilityId: "quickMeasure.dbhButton"
            ) {
                presentingDBHScan = true
            }

            HubRow(
                title: "Height",
                subtitle: "Tangent method via AR + IMU",
                systemImage: "arrow.up.and.down",
                accessibilityId: "quickMeasure.heightButton"
            ) {
                presentingHeightScan = true
            }

            // Field log and Settings both push onto the NavigationStack
            // rather than present a sheet — matches the rest of the
            // hub's "one screen per spoke" rhythm.
            NavigationLink {
                FieldLogScreen()
            } label: {
                HubRowLabel(
                    title: "Field log",
                    subtitle: fieldLogSubtitle,
                    systemImage: "list.bullet.rectangle",
                    trailingBadge: history.entries.isEmpty
                        ? nil : "\(history.entries.count)")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("quickMeasure.fieldLogButton")

            NavigationLink {
                SettingsScreen()
            } label: {
                HubRowLabel(
                    title: "Settings",
                    subtitle: settings.advancedMode
                        ? "Advanced mode on · calibration · backup"
                        : "Units · calibration · advanced mode",
                    systemImage: "gearshape",
                    trailingBadge: nil)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("quickMeasure.settingsLink")
        }
    }

    private var fieldLogSubtitle: String {
        if history.entries.isEmpty {
            return "No readings yet"
        }
        if history.isNearCapacity {
            return "Nearing capacity — export soon"
        }
        return "Recent readings and CSV export"
    }

    // MARK: - Footer stats

    /// Single line of tertiary text at the bottom — pure status, not
    /// a control. Gives the cruiser a glanceable "you've done N today"
    /// without recreating the old dashboard's inline log. Hidden when
    /// there are zero readings so the screen is genuinely empty.
    @ViewBuilder
    private var footerStats: some View {
        if !history.entries.isEmpty {
            let todayCount = history.entries.filter {
                Calendar.current.isDateInToday($0.createdAt)
            }.count
            HStack {
                Spacer()
                Text("\(todayCount) today · \(history.entries.count) total")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textTertiary)
                Spacer()
            }
            .padding(.top, ForestixSpace.xs)
        }
    }

    // MARK: - Scan covers (iOS only)

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(calibration: .identity),
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

// MARK: - Hub row (Button variant)

/// Action-button hub row — used for Diameter and Height because they
/// present a fullScreenCover rather than pushing a destination.
private struct HubRow: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let accessibilityId: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HubRowLabel(title: title,
                        subtitle: subtitle,
                        systemImage: systemImage,
                        trailingBadge: nil)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityId)
    }
}

// MARK: - Hub row label (shared content)

/// Visual body of a hub row — glyph tile, title + subtitle, trailing
/// chevron (or optional count badge). Shared between Button-backed
/// rows and NavigationLink-backed rows so the visual is identical.
///
/// Target height ≈ 76 pt so it comfortably meets Apple's 44 pt tap
/// target on any iPhone, with room to breathe. Only 4 of these on
/// the whole home screen, so they can afford the extra vertical
/// weight.
private struct HubRowLabel: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let trailingBadge: String?

    var body: some View {
        HStack(spacing: ForestixSpace.md) {
            ZStack {
                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                 style: .continuous)
                    .fill(ForestixPalette.primaryMuted)
                    .frame(width: 48, height: 48)
                Image(systemName: systemImage)
                    .font(.system(size: 20, weight: .medium))
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
            if let badge = trailingBadge {
                Text(badge)
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .padding(.horizontal, ForestixSpace.xs)
                    .padding(.vertical, 3)
                    .background(
                        Capsule().fill(ForestixPalette.surfaceRaised))
            }
            Image(systemName: "chevron.forward")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(ForestixPalette.textTertiary)
        }
        .padding(.horizontal, ForestixSpace.md)
        .padding(.vertical, ForestixSpace.sm)
        .frame(minHeight: 76)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
        .contentShape(Rectangle())
    }
}
