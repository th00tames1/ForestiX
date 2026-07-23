// Phase 5 §5.5 StandSummaryScreen. REQ-AGG-003, §7.5.
//
// Stratified stand-level summary: three stat cards (TPA, BA/ac, V/ac) each
// showing mean ± SE ± 95% CI and a Swift Charts bar of per-plot values.
// Below: a table of per-plot PlotStats and a stratum breakdown.

import SwiftUI
import Charts
import Models
import Common
import InventoryEngine

public struct StandSummaryScreen: View {

    @StateObject private var viewModel: StandSummaryViewModel

    /// TRUE when the active country's volume standard is pending official
    /// coefficients (South Korea) — the gross-volume card then renders "—"
    /// rather than a fabricated 0. Defaults to false so US / metric callers,
    /// previews and tests are unchanged.
    private let volumePending: Bool

    public init(viewModel: @autoclosure @escaping () -> StandSummaryViewModel,
                volumePending: Bool = false) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.volumePending = volumePending
    }

    public var body: some View {
        Form {
            headerSection
            statCardSection(title: "Trees / ac", unit: "/ac",
                            stat: viewModel.tpaStat,
                            perPlot: viewModel.perPlotStats.map {
                                (plot: $0.plot, value: Double($0.stats.tpa)) })
            statCardSection(title: "Basal area", unit: "m²/ac",
                            stat: viewModel.baStat,
                            perPlot: viewModel.perPlotStats.map {
                                (plot: $0.plot, value: Double($0.stats.baPerAcreM2)) })
            statCardSection(title: "Gross volume", unit: "m³/ac",
                            stat: viewModel.volStat,
                            perPlot: viewModel.perPlotStats.map {
                                (plot: $0.plot, value: Double($0.stats.grossVolumePerAcreM3)) },
                            pending: volumePending)
            perPlotTableSection
        }
        .navigationTitle("Stand summary")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.refresh() }
        .alert("Error", isPresented: errorBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { _ in })
    }

    private var headerSection: some View {
        Section {
            HStack {
                VStack(alignment: .leading) {
                    Text(viewModel.project.name).font(ForestixType.bodyBold)
                    Text("\(viewModel.closedPlots.count) closed plot(s) · \(viewModel.totalLiveTreeCount) live trees")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
        }
    }

    private func statCardSection(
        title: String,
        unit: String,
        stat: StandStat,
        perPlot: [(plot: Models.Plot, value: Double)],
        pending: Bool = false
    ) -> some View {
        Section(title) {
            if pending {
                VStack(alignment: .leading, spacing: 6) {
                    Text("—").font(ForestixType.dataLarge)
                    Text("Volume for South Korea is pending official NIFoS coefficients. Trees, basal area and stocking are unaffected.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            } else {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(String(format: "%.2f %@", stat.mean, unit))
                        .font(ForestixType.dataLarge)
                    Spacer()
                    Text(String(format: "± %.2f (95%% confidence)", stat.ci95HalfWidth))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                HStack(spacing: 12) {
                    Text("Std error \(String(format: "%.2f", stat.seMean))")
                    Text("eff. plots \(String(format: "%.1f", stat.dfSatterthwaite))")
                    Text("n \(stat.nPlots)")
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)

                if !perPlot.isEmpty {
                    Chart {
                        ForEach(perPlot, id: \.plot.id) { row in
                            BarMark(
                                x: .value("Plot", "\(row.plot.plotNumber)"),
                                y: .value(title, row.value))
                                .foregroundStyle(ForestixPalette.primary)
                        }
                        RuleMark(y: .value("Mean", stat.mean))
                            .foregroundStyle(.secondary)
                            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4]))
                    }
                    .frame(height: 140)
                }

                if !stat.byStratum.isEmpty {
                    Divider().padding(.vertical, 2)
                    ForEach(stat.byStratum.keys.sorted(), id: \.self) { key in
                        if let s = stat.byStratum[key] {
                            HStack {
                                Text(viewModel.stratumName(forKey: key))
                                    .font(.caption)
                                Spacer()
                                Text(String(format: "n=%d  mean=%.2f  std-dev=%.2f",
                                            s.nPlots, s.mean, sqrt(max(s.variance, 0))))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    private var perPlotTableSection: some View {
        Section("Per-plot") {
            if viewModel.perPlotStats.isEmpty {
                Text("No closed plots yet.").foregroundStyle(.secondary)
            } else {
                HStack {
                    Text("#").frame(width: 28, alignment: .leading)
                    Text("Live").frame(maxWidth: .infinity, alignment: .trailing)
                    Text("Trees/ac").frame(maxWidth: .infinity, alignment: .trailing)
                    Text("Basal/ac").frame(maxWidth: .infinity, alignment: .trailing)
                    Text("Volume/ac").frame(maxWidth: .infinity, alignment: .trailing)
                }
                .font(.caption2.bold())
                .foregroundStyle(.secondary)
                ForEach(viewModel.perPlotStats, id: \.plot.id) { row in
                    HStack {
                        Text("\(row.plot.plotNumber)")
                            .frame(width: 28, alignment: .leading)
                        Text("\(row.stats.liveTreeCount)")
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(String(format: "%.1f", row.stats.tpa))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(String(format: "%.2f", row.stats.baPerAcreM2))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(String(format: "%.1f", row.stats.grossVolumePerAcreM3))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .font(.caption.monospacedDigit())
                }
            }
        }
    }
}
