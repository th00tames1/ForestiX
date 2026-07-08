// Tree Measurement hub — direct entry into the five measurement tools.
//
// Layout: 2-column × 3-row grid with the bottom-right slot intentionally
// blank for visual balance.
//
//   ┌─────────────┬─────────────┐
//   │  Diameter   │   Height    │
//   ├─────────────┼─────────────┤
//   │   Crown     │  Sampling   │
//   │             │   Plots     │
//   ├─────────────┼─────────────┤
//   │  Distance   │             │
//   └─────────────┴─────────────┘
//
// All five tiles persist to QuickMeasureHistory, surface in Field Log,
// and round-trip through CSV/bundle export.

import SwiftUI
import Common
import Models
import Sensors

public struct TreeMeasurementHubScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory

    @State private var presentingDBHScan = false
    @State private var presentingHeightScan = false
    @State private var presentingDistance = false
    @State private var presentingSampling = false
    @State private var pendingTreeNumber: Int?

    public init() {}

    private let columns = [
        GridItem(.flexible(), spacing: ForestixSpace.sm),
        GridItem(.flexible(), spacing: ForestixSpace.sm)
    ]

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ForestixSpace.md) {
                Text("Direct measurement tools — readings save to the field log automatically.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .padding(.horizontal, ForestixSpace.md)
                    .padding(.top, ForestixSpace.sm)

                LazyVGrid(columns: columns, spacing: ForestixSpace.sm) {
                    tile("Tree Diameter", "ruler",
                         "DBH via LiDAR scan",
                         "treeMeas.diameter") {
                        pendingTreeNumber = history.suggestedNextTreeNumber
                        presentingDBHScan = true
                    }
                    tile("Tree Height", "arrow.up.and.down",
                         "Height + crown, real scale",
                         "treeMeas.height") {
                        pendingTreeNumber = history.suggestedNextTreeNumber
                        presentingHeightScan = true
                    }
                    tile("Sampling Plots", "scope",
                         "Plot center + boundary",
                         "treeMeas.sampling") {
                        presentingSampling = true
                    }
                    tile("Distance Measurement", "arrow.left.and.right",
                         "Device-to-object + 2-point",
                         "treeMeas.distance") {
                        presentingDistance = true
                    }
                }
                .padding(.horizontal, ForestixSpace.md)

                NavigationLink {
                    FieldLogScreen()
                } label: {
                    HStack(spacing: ForestixSpace.sm) {
                        Image(systemName: "list.bullet.rectangle")
                            .font(.system(size: 16, weight: .medium))
                            .foregroundStyle(ForestixPalette.primary)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Field log")
                                .font(ForestixType.bodyBold)
                                .foregroundStyle(ForestixPalette.textPrimary)
                            Text(fieldLogSubtitle)
                                .font(ForestixType.caption)
                                .foregroundStyle(ForestixPalette.textSecondary)
                        }
                        Spacer()
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
                .padding(.horizontal, ForestixSpace.md)

                Spacer(minLength: ForestixSpace.lg)
            }
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Tree Measurement")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        .fullScreenCover(isPresented: $presentingDBHScan) { dbhCover }
        .fullScreenCover(isPresented: $presentingHeightScan) { heightCover }
        .fullScreenCover(isPresented: $presentingDistance) {
            NavigationStack {
                DistanceMeasureScreen()
                    .environmentObject(history)
                    .environmentObject(settings)
            }
        }
        .fullScreenCover(isPresented: $presentingSampling) {
            NavigationStack {
                SamplingPlotScreen()
                    .environmentObject(history)
                    .environmentObject(settings)
            }
        }
        #endif
    }

    private var fieldLogSubtitle: String {
        if history.entries.isEmpty { return "No readings yet" }
        return "\(history.entries.count) readings — view & export"
    }

    @ViewBuilder
    private func tile(_ title: String,
                      _ icon: String,
                      _ subtitle: String,
                      _ accessibilityID: String,
                      _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: ForestixSpace.sm) {
                ZStack {
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(ForestixPalette.primaryMuted)
                        .frame(width: 44, height: 44)
                    Image(systemName: icon)
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(ForestixPalette.primary)
                }
                Spacer(minLength: 0)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .multilineTextAlignment(.leading)
                    Text(subtitle)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .multilineTextAlignment(.leading)
                        .lineLimit(2)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .padding(ForestixSpace.md)
            .frame(maxWidth: .infinity, minHeight: 140, alignment: .topLeading)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .stroke(ForestixPalette.primary.opacity(0.18),
                            lineWidth: 0.5))
            .shadow(color: Color.black.opacity(0.04),
                    radius: 6, x: 0, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityID)
    }

    #if os(iOS)
    private var dbhCover: some View {
        NavigationStack {
            DBHScanScreen(
                viewModel: DBHScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.append(QuickMeasureEntry(
                        kind: .dbh,
                        value: Double(result.diameterCm),
                        sigma: Double(result.sigmaRmm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID,
                        speciesCode: meta.speciesCode,
                        position: meta.position ?? .dbh,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath))
                    presentingDBHScan = false
                })
        }
    }

    private var heightCover: some View {
        NavigationStack {
            HeightScanScreen(
                viewModel: HeightScanViewModel(calibration: .identity),
                onAccept: { result, meta in
                    history.append(QuickMeasureEntry(
                        kind: .height,
                        value: Double(result.heightM),
                        sigma: Double(result.sigmaHm),
                        confidenceRaw: result.confidence.rawValue,
                        method: result.method.rawValue,
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID,
                        speciesCode: meta.speciesCode,
                        damageCodes: meta.damageCodes,
                        note: meta.note.isEmpty ? nil : meta.note,
                        latitude: meta.latitude,
                        longitude: meta.longitude,
                        photoPath: meta.photoPath))
                    presentingHeightScan = false
                },
                onCrown: { widthM, heightM in
                    // Crown is now measured inside the Height session — it
                    // reuses the walk-off distance d_h so the canopy points
                    // land at real scale. Logged against the same tree.
                    history.append(QuickMeasureEntry(
                        kind: .crown,
                        value: widthM,
                        secondaryValue: heightM,
                        sigma: nil,
                        confidenceRaw: "green",
                        method: "ar.crown.dh",
                        treeNumber: pendingTreeNumber,
                        plotID: history.activePlotID))
                })
            .environmentObject(history)
            .environmentObject(settings)
        }
    }
    #endif
}
