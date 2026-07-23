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

    /// Areal basis for the density cards (TPA, BA/area, volume/area). The
    /// engine computes per acre; metric countries re-express per hectare. The
    /// caller passes `settings.country.areaUnit`. Defaults to `.acre` so US
    /// callers, previews and tests are unchanged.
    private let areaUnit: AreaUnit

    public init(viewModel: @autoclosure @escaping () -> StandSummaryViewModel,
                volumePending: Bool = false,
                areaUnit: AreaUnit = .acre) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.volumePending = volumePending
        self.areaUnit = areaUnit
    }

    /// Per-acre → display-basis multiplier (1.0 for US acres, 2.47105 for
    /// metric hectares).
    private var densityFactor: Double { areaUnit.perAcreDensityFactor }

    public var body: some View {
        Form {
            headerSection
            statCardSection(title: "Trees / \(areaUnit.abbreviation)",
                            unit: areaUnit.densitySuffix,
                            stat: viewModel.tpaStat.scaledPerArea(by: densityFactor),
                            perPlot: viewModel.perPlotStats.map {
                                (plot: $0.plot, value: Double($0.stats.tpa) * densityFactor) })
            statCardSection(title: "Basal area", unit: areaUnit.densityLabel("m²"),
                            stat: viewModel.baStat.scaledPerArea(by: densityFactor),
                            perPlot: viewModel.perPlotStats.map {
                                (plot: $0.plot, value: Double($0.stats.baPerAcreM2) * densityFactor) })
            statCardSection(title: "Gross volume", unit: areaUnit.densityLabel("m³"),
                            stat: viewModel.volStat.scaledPerArea(by: densityFactor),
                            perPlot: viewModel.perPlotStats.map {
                                (plot: $0.plot, value: Double($0.stats.grossVolumePerAcreM3) * densityFactor) },
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
                    Text("Trees/\(areaUnit.abbreviation)").frame(maxWidth: .infinity, alignment: .trailing)
                    Text("Basal/\(areaUnit.abbreviation)").frame(maxWidth: .infinity, alignment: .trailing)
                    Text("Volume/\(areaUnit.abbreviation)").frame(maxWidth: .infinity, alignment: .trailing)
                }
                .font(.caption2.bold())
                .foregroundStyle(.secondary)
                ForEach(viewModel.perPlotStats, id: \.plot.id) { row in
                    HStack {
                        Text("\(row.plot.plotNumber)")
                            .frame(width: 28, alignment: .leading)
                        Text("\(row.stats.liveTreeCount)")
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(String(format: "%.1f", Double(row.stats.tpa) * densityFactor))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(String(format: "%.2f", Double(row.stats.baPerAcreM2) * densityFactor))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                        Text(String(format: "%.1f", Double(row.stats.grossVolumePerAcreM3) * densityFactor))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                    }
                    .font(.caption.monospacedDigit())
                }
            }
        }
    }
}
