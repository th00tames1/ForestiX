// Timber Cruising hub — full standard cruising workflow.
//
// Surfaces the existing project/stratum/cruise design screens
// alongside an explicit entry into the Tree Measurement tools
// (the same five tiles surfaced at the top level), satisfying the
// requirement that Tree Measurement is reachable from inside the
// Timber Cruising flow.

import SwiftUI
import Common
import Models
import Sensors

public struct TimberCruisingHubScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ForestixSpace.md) {
                Text("Standard cruising workflow — projects, strata, plots, stand statistics.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .padding(.horizontal, ForestixSpace.md)
                    .padding(.top, ForestixSpace.sm)

                tileLink("Projects",
                         "Stratum · cruise design · field tally",
                         "folder",
                         destination: HomeScreen())
                tileLink("Recon cruise",
                         "Quick basal area tally · sample sizing",
                         "scope",
                         destination: ReconCruiseScreen())
                tileLink("Tree Measurement",
                         "Direct DBH · height · crown · distance · sampling",
                         "ruler",
                         destination: TreeMeasurementHubScreen())
                tileLink("Field log",
                         logSubtitle,
                         "list.bullet.rectangle",
                         destination: FieldLogScreen())
                tileLink("Reference",
                         "Formulas · log rules · conversions",
                         "book",
                         destination: ReferenceLibraryScreen())
                tileLink("Settings",
                         "Region · units · calibration",
                         "gearshape",
                         destination: SettingsScreen())

                Spacer(minLength: ForestixSpace.lg)
            }
            .padding(.horizontal, ForestixSpace.md)
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Timber Cruising")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var logSubtitle: String {
        if history.entries.isEmpty { return "No readings yet" }
        return "\(history.entries.count) readings"
    }

    @ViewBuilder
    private func tileLink<Destination: View>(
        _ title: String,
        _ subtitle: String,
        _ icon: String,
        destination: Destination
    ) -> some View {
        NavigationLink {
            destination
        } label: {
            HStack(spacing: ForestixSpace.md) {
                ZStack {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 40, height: 40)
                    Image(systemName: icon)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textTertiary)
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
        .buttonStyle(.plain)
    }
}
