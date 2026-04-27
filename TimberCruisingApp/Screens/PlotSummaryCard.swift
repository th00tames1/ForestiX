// In-app plot summary card — closes the "is this plot reasonable?"
// loop in the field. Adopted from Arboreal Forest's instant
// post-plot summary + SilvaCruise's stand stats. Cruiser doesn't
// need a desktop tool to know whether to re-cruise.
//
// Renders:
//   • Top-line stats — BA/ac, TPA, QMD, mean DBH, mean H, BF/ac
//   • Stocking & Density gauge (Phase 1.2 component)
//   • Species mix breakdown
//
// All math is pure functions on a list of `QuickMeasureEntry`. No
// dependencies on plot acreage for now (variable-radius / unscaled
// presentation); Phase 4 wires this to per-plot acreage when the
// stand-and-stock report needs absolute-unit numbers.

import SwiftUI
import Models
import Sensors

public struct PlotSummaryCard: View {

    public let plot: QuickMeasurePlot
    public let entries: [QuickMeasureEntry]
    public let unitSystem: UnitSystem
    public let logRule: LogRule

    public init(plot: QuickMeasurePlot,
                entries: [QuickMeasureEntry],
                unitSystem: UnitSystem,
                logRule: LogRule) {
        self.plot = plot
        self.entries = entries
        self.unitSystem = unitSystem
        self.logRule = logRule
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.md) {
            header
            Divider()
            statsGrid
            if let stats = stats, stats.distinctTrees >= 1 {
                Divider()
                stockingGauge
                Divider()
                speciesMix
            }
        }
        .padding(ForestixSpace.md)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 0.5))
    }

    // MARK: - Header

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("PLOT SUMMARY")
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            Text(plot.name)
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            if !plot.unitName.isEmpty || plot.acres != nil {
                Text(plotSubtitle)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
            }
        }
    }

    private var plotSubtitle: String {
        var parts: [String] = []
        if !plot.unitName.isEmpty { parts.append(plot.unitName) }
        if let ac = plot.acres {
            parts.append(String(format: "%.2f ac", ac))
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Top stats

    private var statsGrid: some View {
        let s = stats
        return HStack(spacing: 0) {
            statsCell("TREES", s?.distinctTrees.description ?? "—")
            divider
            statsCell("BA/ac", s.map { String(format: "%.0f", $0.baPerAcre) } ?? "—")
            divider
            statsCell("TPA",   s.map { String(format: "%.0f", $0.tpa) } ?? "—")
            divider
            statsCell("QMD",
                      s.flatMap { $0.qmd.map { qmd in
                          MeasurementFormatter.diameter(cm: qmd, in: unitSystem)
                      } } ?? "—")
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(ForestixPalette.divider)
            .frame(width: 0.5, height: 32)
    }

    private func statsCell(_ label: String, _ value: String) -> some View {
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

    // MARK: - Stocking gauge

    @ViewBuilder
    private var stockingGauge: some View {
        if let s = stats, s.distinctTrees > 0 {
            // Relative density via Reineke SDI / Max SDI ratio. With
            // no species-specific Max SDI table yet, use a generic
            // Max SDI of 717 (USFS PNW conifer mid-range) for the
            // bar position. Refined per-species in Phase 5.
            let maxSDI: Double = 717
            let relDensityPct = min(100, max(0, (s.sdi / maxSDI) * 100))
            StockingGauge(
                relativeDensityPct: relDensityPct,
                regimeLabel: regimeLabel(for: relDensityPct))
        }
    }

    private func regimeLabel(for pct: Double) -> String {
        switch pct {
        case ..<25:    return "Understocked"
        case ..<35:    return "Low stocking"
        case ..<60:    return "Adequately stocked"
        default:       return "Over-dense"
        }
    }

    // MARK: - Species mix

    @ViewBuilder
    private var speciesMix: some View {
        if let s = stats, !s.speciesMix.isEmpty {
            VStack(alignment: .leading, spacing: ForestixSpace.xs) {
                Text("SPECIES MIX")
                    .font(ForestixType.sectionHead)
                    .tracking(1.5)
                    .foregroundStyle(ForestixPalette.textTertiary)
                ForEach(s.speciesMix, id: \.code) { row in
                    HStack {
                        Text(row.code.isEmpty ? "—" : row.code)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .frame(width: 56, alignment: .leading)
                        // Bar viz of the share, with a numeric label.
                        GeometryReader { geo in
                            ZStack(alignment: .leading) {
                                Capsule()
                                    .fill(ForestixPalette.surfaceRaised)
                                Capsule()
                                    .fill(ForestixPalette.primary.opacity(0.5))
                                    .frame(width: geo.size.width * CGFloat(row.share))
                            }
                        }
                        .frame(height: 8)
                        Text(String(format: "%.0f%%", row.share * 100))
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textPrimary)
                            .frame(width: 44, alignment: .trailing)
                    }
                }
            }
        }
    }

    // MARK: - Stats compute

    private struct Stats {
        let distinctTrees: Int
        let tpa: Double
        let baPerAcre: Double
        let qmd: Double?
        let meanHeightM: Double?
        let bfPerAcre: Double?
        let sdi: Double
        let speciesMix: [(code: String, share: Double)]
    }

    private var stats: Stats? {
        guard !entries.isEmpty else { return nil }

        // Group entries by tree number; each tree contributes the
        // first DBH it has and the first Height it has.
        let byTree = Dictionary(grouping: entries) { $0.treeNumber ?? -1 }
        let trees = byTree.map { (_, group) -> (dbhCm: Double?, hM: Double?, species: String) in
            let dbh = group.first(where: { $0.kind == .dbh })?.value
            let h   = group.first(where: { $0.kind == .height })?.value
            let sp  = group.first(where: { ($0.speciesCode ?? "").isEmpty == false })?.speciesCode ?? ""
            return (dbh, h, sp)
        }

        let dbhTrees = trees.compactMap { $0.dbhCm }
        guard !dbhTrees.isEmpty else { return nil }

        // BA per tree (m² → ft²/ac via dbh in inches). Use SilvaCruise's
        // ft²-per-tree formula on each tree. With no plot-acres tied to
        // these readings yet, "per acre" means "per tree-bin" — useful
        // as a relative readout, refined in Phase 4 with real acreage.
        let baFt2: [Double] = dbhTrees.map { cm in
            let inches = cm / 2.54
            return 0.005454 * inches * inches
        }
        let acres = max(plot.acres ?? 0.1, 0.05)   // sane fallback
        let baPerAcre = baFt2.reduce(0, +) / acres
        let tpa = Double(dbhTrees.count) / acres
        // QMD in cm (display layer converts to inches if needed).
        let qmdSqCm = dbhTrees.map { $0 * $0 }.reduce(0, +) / Double(dbhTrees.count)
        let qmd = qmdSqCm.squareRoot()

        let heights = trees.compactMap { $0.hM }
        let meanH = heights.isEmpty ? nil : heights.reduce(0, +) / Double(heights.count)

        // Optional BF/ac when DBH+H+rule available.
        var bfPerAcre: Double?
        var totalBF: Double = 0
        var bfCount = 0
        for t in trees {
            guard let dbh = t.dbhCm, let h = t.hM else { continue }
            if let bf = VolumeConversion.boardFeet(dbhCm: dbh,
                                                    totalHeightM: h,
                                                    rule: logRule) {
                totalBF += bf
                bfCount += 1
            }
        }
        if bfCount > 0 {
            bfPerAcre = totalBF / acres
        }

        // Reineke SDI = TPA × (QMD_in / 10)^1.605
        let qmdIn = qmd / 2.54
        let sdi = tpa * pow(qmdIn / 10.0, 1.605)

        // Species mix
        let totalTrees = trees.count
        let bySpecies = Dictionary(grouping: trees, by: { $0.species })
        let mix: [(String, Double)] = bySpecies
            .map { (code, group) in (code, Double(group.count) / Double(totalTrees)) }
            .sorted { $0.1 > $1.1 }

        return Stats(
            distinctTrees: dbhTrees.count,
            tpa: tpa,
            baPerAcre: baPerAcre,
            qmd: qmd,
            meanHeightM: meanH,
            bfPerAcre: bfPerAcre,
            sdi: sdi,
            speciesMix: mix.map { (code: $0.0, share: $0.1) })
    }
}
