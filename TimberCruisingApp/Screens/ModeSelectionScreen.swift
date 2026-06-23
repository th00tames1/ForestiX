// Top-level mode picker. Two large vertically-stacked buttons:
//   • Tree Measurement — direct measurement tools without the
//     project/stratum/cruise-design overhead.
//   • Timber Cruising  — full standard cruising workflow. Tree
//     Measurement is also reachable from inside this mode.

import SwiftUI

public struct ModeSelectionScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory

    public init() {}

    public var body: some View {
        NavigationStack {
            VStack(spacing: ForestixSpace.xl) {
                Spacer()

                VStack(alignment: .center, spacing: ForestixSpace.xxs) {
                    Text("FORESTIX")
                        .font(.system(size: 14, weight: .semibold))
                        .tracking(2.4)
                        .foregroundStyle(ForestixPalette.primary)
                    Text("Choose a workflow")
                        .font(ForestixType.title)
                        .foregroundStyle(ForestixPalette.textPrimary)
                }

                VStack(spacing: ForestixSpace.md) {
                    NavigationLink {
                        TreeMeasurementHubScreen()
                    } label: {
                        ModeButton(
                            title: "Tree Measurement",
                            subtitle: "Direct DBH · height · crown · distance · sampling plots",
                            systemImage: "ruler")
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("mode.treeMeasurement")

                    NavigationLink {
                        TimberCruisingHubScreen()
                    } label: {
                        ModeButton(
                            title: "Timber Cruising",
                            subtitle: "Standard cruise: projects · strata · plots · stand stats",
                            systemImage: "tree")
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("mode.timberCruising")
                }
                .padding(.horizontal, ForestixSpace.md)

                Spacer()
                Spacer()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(ForestixPalette.canvas.ignoresSafeArea())
            #if os(iOS)
            .navigationBarHidden(true)
            #endif
        }
    }
}

private struct ModeButton: View {
    let title: String
    let subtitle: String
    let systemImage: String

    var body: some View {
        HStack(spacing: ForestixSpace.md) {
            ZStack {
                RoundedRectangle(cornerRadius: ForestixRadius.control,
                                 style: .continuous)
                    .fill(ForestixPalette.primaryMuted)
                    .frame(width: 64, height: 64)
                Image(systemName: systemImage)
                    .font(.system(size: 28, weight: .medium))
                    .foregroundStyle(ForestixPalette.primary)
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                Text(subtitle)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            Image(systemName: "chevron.forward")
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(ForestixPalette.textTertiary)
        }
        .padding(ForestixSpace.md)
        .frame(maxWidth: .infinity, minHeight: 120, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .stroke(ForestixPalette.primary.opacity(0.18),
                        lineWidth: 0.75))
        .shadow(color: Color.black.opacity(0.05),
                radius: 8, x: 0, y: 2)
    }
}
