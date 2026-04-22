// Quick Measure home — the default Forestix entry point (when
// AppSettings.advancedMode == false).
//
// Layout: bento-grid hub. The screen is built from three tiers that
// communicate priority through size, not through color or copy:
//
//   Tier 1 — two hero cards (Diameter, Height). These are the primary
//            measurement actions a cruiser performs all day. Each is
//            a tall card with a decorative glyph, gradient surface,
//            and a "Start scan" capsule — the visual weight reflects
//            usage weight.
//
//   Tier 2 — a compact stats strip (Today / Total / Last reading).
//            Horizontal, three cells, monospaced — reads like an
//            instrument readout, not a dashboard.
//
//   Tier 3 — two supporting tiles (Field log, Settings) in a 2-col
//            grid. Same height, same chrome, smaller than the hero
//            cards. Navigation-only; their detail lives on their
//            own screens.
//
// Why this structure: an earlier revision stacked masthead + capacity
// warning + INSTRUMENT panel + FIELD LOG table on a single scroll,
// which read as a crammed dashboard; a follow-up flattened everything
// to equal-sized rows, which lost the hierarchy. The bento pattern —
// popularized on Apple's own product pages and surveyed by the iOS
// design community as the dominant 2025-2026 hub style — keeps the
// hierarchy explicit: bigger means more important.

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
                VStack(alignment: .leading, spacing: ForestixSpace.md) {
                    masthead
                    diameterHero
                    heightHero
                    statsStrip
                    supportingGrid
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
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $presentingDBHScan) { dbhCover }
            .fullScreenCover(isPresented: $presentingHeightScan) { heightCover }
            #endif
        }
    }

    // MARK: - Masthead

    private var masthead: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Quick measure")
                .font(ForestixType.title)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text(masterheadSubtitle)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textTertiary)
        }
        .padding(.top, ForestixSpace.xs)
        .padding(.bottom, ForestixSpace.xs)
    }

    private var masterheadSubtitle: String {
        if history.entries.isEmpty {
            return "Handheld instrument · start a scan below"
        }
        if history.isNearCapacity {
            return "Log nearing capacity — export soon"
        }
        return "\(todayCount) today · \(history.entries.count) total"
    }

    // MARK: - Hero cards

    private var diameterHero: some View {
        MeasurementHeroCard(
            title: "Diameter",
            subtitle: "Breast-height scan via LiDAR",
            systemImage: "ruler",
            ctaLabel: "Start scan",
            accessibilityId: "quickMeasure.dbhButton"
        ) {
            presentingDBHScan = true
        }
    }

    private var heightHero: some View {
        MeasurementHeroCard(
            title: "Height",
            subtitle: "AR tangent method · walk-off",
            systemImage: "arrow.up.and.down",
            ctaLabel: "Start scan",
            accessibilityId: "quickMeasure.heightButton"
        ) {
            presentingHeightScan = true
        }
    }

    // MARK: - Stats strip

    /// Three-cell instrument-style readout. Hidden when there's no
    /// history — the masthead subtitle already carries the "nothing
    /// to show" messaging.
    @ViewBuilder
    private var statsStrip: some View {
        if !history.entries.isEmpty {
            HStack(spacing: 0) {
                StatsCell(value: "\(todayCount)", label: "TODAY")
                StatsCellDivider()
                StatsCell(value: "\(history.entries.count)", label: "TOTAL")
                StatsCellDivider()
                StatsCell(value: lastRelative, label: "LAST")
            }
            .padding(.vertical, ForestixSpace.sm)
            .frame(maxWidth: .infinity)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .stroke(ForestixPalette.divider, lineWidth: 0.5))
        }
    }

    // MARK: - Supporting grid (Field log + Settings)

    private var supportingGrid: some View {
        HStack(spacing: ForestixSpace.sm) {
            NavigationLink {
                FieldLogScreen()
            } label: {
                SupportingTile(
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
                SupportingTile(
                    title: "Settings",
                    subtitle: settings.advancedMode ? "Advanced · on" : "Units · calibration",
                    systemImage: "gearshape",
                    trailingBadge: nil)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("quickMeasure.settingsLink")
        }
    }

    // MARK: - Derived stats

    private var todayCount: Int {
        history.entries.filter {
            Calendar.current.isDateInToday($0.createdAt)
        }.count
    }

    private var lastRelative: String {
        guard let first = history.entries.first else { return "—" }
        let fmt = RelativeDateTimeFormatter()
        fmt.unitsStyle = .abbreviated
        return fmt.localizedString(for: first.createdAt, relativeTo: Date())
    }

    private var fieldLogSubtitle: String {
        if history.entries.isEmpty { return "No readings yet" }
        return "\(history.entries.count) readings"
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

// MARK: - Measurement hero card

/// Large featured card for a primary measurement action. Visual
/// weight deliberately heavier than the supporting tiles below so
/// Tier 1 reads as Tier 1 at a glance.
///
/// Anatomy (top to bottom, left to right):
///   • Decorative glyph — 56 pt, top-right corner, low-opacity
///     primary tint. Acts as a watermark, not a control.
///   • Title — `ForestixType.title` scaled down to 22 pt semibold,
///     anchored bottom-left.
///   • Subtitle — one line of caption copy under the title.
///   • "Start scan" capsule — primary-coloured filled capsule at
///     the bottom-right with a forward chevron. Looks like a button
///     because it IS the button; the whole card is tappable but the
///     capsule is where the eye lands.
///
/// Background is a subtle vertical gradient inside the primary-muted
/// tone, with a hairline border and a soft 2 pt shadow. The gradient
/// gives the card depth without turning into a marketing banner.
private struct MeasurementHeroCard: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let ctaLabel: String
    let accessibilityId: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack(alignment: .topTrailing) {
                // Decorative glyph as a watermark.
                Image(systemName: systemImage)
                    .font(.system(size: 72, weight: .regular))
                    .foregroundStyle(ForestixPalette.primary.opacity(0.18))
                    .padding(.top, ForestixSpace.md)
                    .padding(.trailing, ForestixSpace.md)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 0) {
                    Spacer(minLength: 0)
                    Text(title)
                        .font(.system(size: 26, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .padding(.top, 2)
                    HStack {
                        Spacer()
                        HStack(spacing: 6) {
                            Text(ctaLabel)
                                .font(ForestixType.bodyBold)
                            Image(systemName: "arrow.forward")
                                .font(.system(size: 13, weight: .bold))
                        }
                        .foregroundStyle(Color.white)
                        .padding(.horizontal, ForestixSpace.md)
                        .padding(.vertical, ForestixSpace.xs)
                        .background(
                            Capsule().fill(ForestixPalette.primary))
                    }
                    .padding(.top, ForestixSpace.md)
                }
                .padding(ForestixSpace.md)
            }
            .frame(height: 160)
            .background(heroBackground)
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .stroke(ForestixPalette.primary.opacity(0.15),
                            lineWidth: 0.5))
            .clipShape(RoundedRectangle(cornerRadius: ForestixRadius.card,
                                         style: .continuous))
            .shadow(color: Color.black.opacity(0.04),
                    radius: 8, x: 0, y: 2)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityId)
        .accessibilityLabel("\(title). \(subtitle). \(ctaLabel).")
    }

    /// Subtle vertical gradient — lighter at top, a touch more saturated
    /// at the bottom. Keeps cards feeling dimensional without drifting
    /// into consumer-app territory.
    private var heroBackground: LinearGradient {
        LinearGradient(
            colors: [
                ForestixPalette.primary.opacity(0.06),
                ForestixPalette.primary.opacity(0.14)
            ],
            startPoint: .top,
            endPoint: .bottom)
    }
}

// MARK: - Stats strip cells

private struct StatsCell: View {
    let value: String
    let label: String

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(ForestixType.dataLarge)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(ForestixPalette.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }
}

private struct StatsCellDivider: View {
    var body: some View {
        Rectangle()
            .fill(ForestixPalette.divider)
            .frame(width: 0.5, height: 28)
    }
}

// MARK: - Supporting tile

/// Secondary tile used for Field log + Settings. Half the width of a
/// hero card, and a different anatomy: glyph top-left (inside its own
/// small tile), stacked title + subtitle, trailing chevron at the
/// bottom-right. Optional count badge pinned to the top-right.
private struct SupportingTile: View {
    let title: String
    let subtitle: String
    let systemImage: String
    let trailingBadge: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .top) {
                ZStack {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 36, height: 36)
                    Image(systemName: systemImage)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                Spacer()
                if let badge = trailingBadge {
                    Text(badge)
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(
                            Capsule().fill(ForestixPalette.surfaceRaised))
                }
            }
            Spacer(minLength: ForestixSpace.sm)
            HStack(alignment: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(2)
                }
                Spacer()
                Image(systemName: "chevron.forward")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
        .padding(ForestixSpace.md)
        .frame(height: 112, alignment: .topLeading)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 0.5))
        .shadow(color: Color.black.opacity(0.03),
                radius: 6, x: 0, y: 1)
    }
}
