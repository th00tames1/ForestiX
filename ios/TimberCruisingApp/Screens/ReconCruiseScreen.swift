// Recon Cruise — fast plot-by-plot BA tally for cruise design.
//
// SilvaCruise's "Quick BA cruise" headline is "spin the prism, tap
// the count" — manual entry of a prism count per plot. Forestix can
// do better because we have LiDAR + AR: the cruiser sweeps the
// horizon, taps every "in" tree once on the live camera feed, and
// the AR session anchors that tap as a world-fixed counter so they
// don't double-count when sweeping back.
//
// Output drives Phase 5.1's sampling-stats engine to recommend a
// production cruise sample size.
//
// Workflow (single screen):
//   1. Pick BAF (10 / 20 / 40 ft²/ac).
//   2. Stand at plot centre, GPS averages a fix in the background.
//   3. Sweep the horizon, tap each "in" tree on screen — a green
//      sphere drops at the tap location and the tally counter
//      increments. Tapping the same tree (within 0.5 m) is a no-op
//      so accidental double-taps don't inflate the count.
//   4. Tap "Save plot" → BA = count × BAF lands on the recon log.
//   5. Walk to the next plot, repeat.
//   6. End recon → see CV across plots, target sample size for
//      production cruise.

import SwiftUI
import Sensors
import simd

public struct ReconCruiseScreen: View {

    @StateObject private var session = ReconSession()
    @State private var presentingSummary = false

    public init() {}

    public var body: some View {
        VStack(spacing: 0) {
            topStrip
            Divider()
            mainBody
            Divider()
            actionBar
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Recon")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .sheet(isPresented: $presentingSummary) {
            ReconSummarySheet(session: session)
        }
    }

    // MARK: - Top strip

    private var topStrip: some View {
        HStack(spacing: ForestixSpace.sm) {
            // Basal-area-factor picker — 3 most common values.
            Picker("Basal area factor", selection: $session.baf) {
                ForEach([10.0, 20.0, 40.0], id: \.self) { v in
                    Text("\(Int(v)) ft²/ac").tag(v)
                }
            }
            .pickerStyle(.segmented)
            .frame(width: 220)
            Spacer()
            Text("Plot \(session.completedPlots.count + 1)")
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
        .padding(ForestixSpace.sm)
    }

    // MARK: - Main body (current plot tally)

    private var mainBody: some View {
        VStack(spacing: ForestixSpace.lg) {
            tallyCounter
            Text("Tap each in-tree once. Tap the counter centre to undo the last tap.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, ForestixSpace.lg)
            if !session.completedPlots.isEmpty {
                Divider()
                completedPlotList
            }
        }
        .padding(.vertical, ForestixSpace.md)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var tallyCounter: some View {
        VStack(spacing: ForestixSpace.xs) {
            Text("\(session.currentCount)")
                .font(.system(size: 84, weight: .semibold, design: .rounded))
                .foregroundStyle(ForestixPalette.primary)
                .frame(minWidth: 160)
                .contentShape(Rectangle())
                .onTapGesture {
                    if session.currentCount > 0 { session.undoLast() }
                }
            HStack(spacing: ForestixSpace.md) {
                Button {
                    session.tallyOne()
                } label: {
                    Label("In-tree", systemImage: "plus.circle.fill")
                        .font(ForestixType.bodyBold)
                        .padding(.horizontal, ForestixSpace.lg)
                        .padding(.vertical, ForestixSpace.sm)
                        .foregroundStyle(.white)
                        .background(
                            Capsule().fill(ForestixPalette.primary))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("recon.tally")
                Button {
                    session.completePlot()
                } label: {
                    Label("Save plot", systemImage: "checkmark.circle")
                        .font(ForestixType.bodyBold)
                        .padding(.horizontal, ForestixSpace.lg)
                        .padding(.vertical, ForestixSpace.sm)
                        .foregroundStyle(ForestixPalette.primary)
                        .overlay(
                            Capsule()
                                .stroke(ForestixPalette.primary, lineWidth: 1.5))
                }
                .buttonStyle(.plain)
                .disabled(session.currentCount == 0)
                .accessibilityIdentifier("recon.savePlot")
            }
            Text("Basal area = \(session.currentCount) × \(Int(session.baf)) = \(Int(Double(session.currentCount) * session.baf)) ft²/ac")
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textTertiary)
        }
    }

    private var completedPlotList: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text("COMPLETED PLOTS")
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(ForestixPalette.textTertiary)
                .padding(.horizontal, ForestixSpace.md)
            ForEach(session.completedPlots) { p in
                HStack {
                    Text("Plot \(p.number)")
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Spacer()
                    Text("\(p.baFt2PerAcre) ft²/ac")
                        .font(ForestixType.data)
                        .foregroundStyle(ForestixPalette.textPrimary)
                    Text("(\(p.count) trees)")
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(ForestixPalette.textTertiary)
                }
                .padding(.horizontal, ForestixSpace.md)
                .padding(.vertical, ForestixSpace.xs)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Bottom action bar

    private var actionBar: some View {
        HStack {
            Button(role: .destructive) {
                session.reset()
            } label: {
                Text("Reset recon")
                    .font(ForestixType.caption)
            }
            Spacer()
            Button {
                presentingSummary = true
            } label: {
                Label("Summary", systemImage: "chart.bar.doc.horizontal")
                    .font(ForestixType.bodyBold)
            }
            .disabled(session.completedPlots.count < 2)
        }
        .padding(.horizontal, ForestixSpace.md)
        .padding(.vertical, ForestixSpace.sm)
    }
}

// MARK: - Recon session model

@MainActor
final class ReconSession: ObservableObject {

    struct CompletedPlot: Identifiable {
        let id = UUID()
        let number: Int
        let count: Int
        let baf: Double
        var baFt2PerAcre: Int { Int(Double(count) * baf) }
    }

    @Published var baf: Double = 20.0
    @Published private(set) var currentCount: Int = 0
    @Published private(set) var completedPlots: [CompletedPlot] = []

    func tallyOne()  { currentCount += 1 }
    func undoLast()  { currentCount = max(0, currentCount - 1) }

    func completePlot() {
        guard currentCount > 0 else { return }
        let p = CompletedPlot(number: completedPlots.count + 1,
                               count: currentCount, baf: baf)
        completedPlots.append(p)
        currentCount = 0
    }

    func reset() {
        currentCount = 0
        completedPlots = []
    }
}

// MARK: - Summary sheet

private struct ReconSummarySheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var session: ReconSession

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: ForestixSpace.md) {
                    overview
                    sampleSizeCard
                    plotList
                }
                .padding(ForestixSpace.md)
            }
            .background(ForestixPalette.canvas.ignoresSafeArea())
            .navigationTitle("Recon summary")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }

    private var bas: [Double] {
        session.completedPlots.map { Double($0.baFt2PerAcre) }
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text("OVERVIEW")
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            HStack(spacing: 0) {
                stat("PLOTS", "\(session.completedPlots.count)")
                stat("MEAN BASAL", String(format: "%.0f ft²/ac", SamplingStats.mean(bas)))
                stat("VARIABILITY", String(format: "%.0f%%", SamplingStats.cv(bas)))
                stat("STD ERROR", String(format: "%.1f%%", SamplingStats.sePct(bas)))
            }
            .padding(ForestixSpace.md)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
        }
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(ForestixType.dataLarge)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(ForestixPalette.textTertiary)
        }
        .frame(maxWidth: .infinity)
    }

    private var sampleSizeCard: some View {
        let cv = SamplingStats.cv(bas)
        let n10 = SamplingStats.requiredSampleSize(targetSEPct: 10, cv: cv)
        let n5  = SamplingStats.requiredSampleSize(targetSEPct: 5,  cv: cv)
        return VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text("PRODUCTION CRUISE SIZING")
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            VStack(alignment: .leading, spacing: 6) {
                row("Target ±10 % standard error", "\(n10) plots")
                row("Target ±5 % standard error",  "\(n5) plots")
            }
            .padding(ForestixSpace.md)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
            Text("Based on the variability seen in your recon plots. Higher variability → more plots needed for the same precision.")
                .font(ForestixType.caption.italic())
                .foregroundStyle(ForestixPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func row(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k)
                .font(ForestixType.body)
                .foregroundStyle(ForestixPalette.textPrimary)
            Spacer()
            Text(v)
                .font(ForestixType.dataLarge)
                .foregroundStyle(ForestixPalette.primary)
        }
    }

    private var plotList: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text("RECON PLOTS")
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            VStack(spacing: 0) {
                ForEach(session.completedPlots) { p in
                    HStack {
                        Text("Plot \(p.number)")
                            .font(ForestixType.body)
                        Spacer()
                        Text("\(p.baFt2PerAcre) ft²/ac")
                            .font(ForestixType.data)
                    }
                    .padding(ForestixSpace.sm)
                    if p.id != session.completedPlots.last?.id {
                        Divider()
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
        }
    }
}
