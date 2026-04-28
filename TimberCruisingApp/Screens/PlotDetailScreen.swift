// Plot detail screen — SilvaCruise-style sub-tab navigation inside
// a single project context. The tab strip at the top keeps the
// cruiser inside the plot while flipping between Summary, the tree
// tally list, and plot-level settings (rename, area, type).
//
// Reachable from the plot picker in TreeIdentitySheet (Phase 2)
// and from a new "Plots" surfacing inside the FieldLogScreen
// summary header. Phase 4 wires this in; Phase 5 may add a
// dedicated Stats sub-tab.

import SwiftUI
import Models
import Sensors

public struct PlotDetailScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings

    public let plotID: UUID

    public init(plotID: UUID) {
        self.plotID = plotID
    }

    private enum Tab: String, CaseIterable {
        case summary, trees, info
        var label: String {
            switch self {
            case .summary: return "Summary"
            case .trees:   return "Trees"
            case .info:    return "Info"
            }
        }
    }

    @State private var selectedTab: Tab = .summary

    public var body: some View {
        if let plot = history.plot(id: plotID) {
            VStack(spacing: 0) {
                tabStrip
                Divider()
                ScrollView {
                    VStack(alignment: .leading, spacing: ForestixSpace.md) {
                        switch selectedTab {
                        case .summary: summaryTab(plot: plot)
                        case .trees:   treesTab(plot: plot)
                        case .info:    infoTab(plot: plot)
                        }
                    }
                    .padding(.horizontal, ForestixSpace.md)
                    .padding(.vertical, ForestixSpace.md)
                }
            }
            .background(ForestixPalette.canvas.ignoresSafeArea())
            .navigationTitle(plot.name)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
        } else {
            VStack {
                Text("Plot not found")
                    .font(ForestixType.body)
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
        }
    }

    // MARK: - Tab strip

    private var tabStrip: some View {
        HStack(spacing: 0) {
            ForEach(Tab.allCases, id: \.self) { tab in
                Button {
                    selectedTab = tab
                } label: {
                    VStack(spacing: 6) {
                        Text(tab.label)
                            .font(ForestixType.bodyBold)
                            .foregroundStyle(selectedTab == tab
                                              ? ForestixPalette.primary
                                              : ForestixPalette.textSecondary)
                        Rectangle()
                            .fill(selectedTab == tab
                                   ? ForestixPalette.primary
                                   : Color.clear)
                            .frame(height: 2)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.top, ForestixSpace.xs)
                }
                .buttonStyle(.plain)
            }
        }
        .background(ForestixPalette.surface)
    }

    // MARK: - Summary tab

    private func summaryTab(plot: QuickMeasurePlot) -> some View {
        let entries = history.entries(forPlot: plotID)
        return VStack(alignment: .leading, spacing: ForestixSpace.md) {
            if entries.isEmpty {
                emptyTabState(message: "No readings yet for this plot. Start a scan from the Quick Measure home.")
            } else {
                PlotSummaryCard(
                    plot: plot,
                    entries: entries,
                    unitSystem: settings.unitSystem,
                    logRule: settings.logRule)
            }
        }
    }

    // MARK: - Trees tab

    private func treesTab(plot: QuickMeasurePlot) -> some View {
        let entries = history.entries(forPlot: plotID)
        // Group entries by tree number; each row in the tab is a
        // tree, not a measurement, so cruisers see "Tree 7 — DBH
        // 34.5 cm + Height 28.2 m" together.
        let byTree = Dictionary(grouping: entries) { $0.treeNumber ?? -1 }
            .sorted { ($0.key) < ($1.key) }
        return VStack(alignment: .leading, spacing: ForestixSpace.sm) {
            if entries.isEmpty {
                emptyTabState(message: "No trees tallied. Pick this plot before your next scan.")
            } else {
                ForEach(byTree, id: \.key) { (num, group) in
                    TreeRowCard(treeNumber: num,
                                entries: group,
                                unitSystem: settings.unitSystem)
                }
            }
        }
    }

    // MARK: - Info tab

    private func infoTab(plot: QuickMeasurePlot) -> some View {
        VStack(alignment: .leading, spacing: ForestixSpace.sm) {
            infoRow("NAME", plot.name)
            infoRow("UNIT", plot.unitName.isEmpty ? "—" : plot.unitName)
            infoRow("ACRES",
                    plot.acres.map { String(format: "%.2f", $0) } ?? "—")
            infoRow("TYPE", plot.typeRaw.capitalized)
            if let baf = plot.baf {
                infoRow("BASAL FACTOR", String(format: "%.0f ft²/ac", baf))
            }
            if let r = plot.radiusFt {
                infoRow("RADIUS", String(format: "%.1f ft", r))
            }
            infoRow("CREATED",
                    DateFormatter.localizedString(
                        from: plot.createdAt,
                        dateStyle: .medium, timeStyle: .short))
            if plot.isDefault {
                Text("Default plot — auto-created. Cannot be deleted.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textTertiary)
                    .padding(.top, ForestixSpace.xs)
            }
        }
        .padding(ForestixSpace.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
    }

    private func infoRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(width: 96, alignment: .leading)
            Text(value)
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
            Spacer(minLength: 0)
        }
    }

    private func emptyTabState(message: String) -> some View {
        VStack(spacing: ForestixSpace.xs) {
            Image(systemName: "tray")
                .font(.system(size: 24, weight: .light))
                .foregroundStyle(ForestixPalette.textTertiary)
            Text(message)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, ForestixSpace.lg)
    }
}

// MARK: - Tree row

private struct TreeRowCard: View {
    let treeNumber: Int
    let entries: [QuickMeasureEntry]
    let unitSystem: UnitSystem

    var body: some View {
        let dbh = entries.first { $0.kind == .dbh }
        let hgt = entries.first { $0.kind == .height }
        let species = entries.compactMap { $0.speciesCode }.first

        return HStack(spacing: ForestixSpace.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(treeNumber > 0 ? "Tree #\(treeNumber)" : "Untagged")
                    .font(ForestixType.bodyBold)
                    .foregroundStyle(ForestixPalette.textPrimary)
                if let s = species, !s.isEmpty {
                    Text(s)
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.primary)
                }
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                if let d = dbh {
                    Text("DBH " + MeasurementFormatter.diameter(
                        cm: d.value, in: unitSystem))
                        .font(ForestixType.data)
                        .foregroundStyle(ForestixPalette.textPrimary)
                }
                if let h = hgt {
                    Text("Height " + MeasurementFormatter.height(
                        m: h.value, in: unitSystem))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
            }
        }
        .padding(ForestixSpace.md)
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
