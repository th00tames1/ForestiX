// In-app plot summary card — closes the "is this plot reasonable?"
// loop in the field. Modelled on an instant
// post-plot summary + standard stand stats. Cruiser doesn't
// need a desktop tool to know whether to re-cruise.
//
// Renders:
//   • Top-line stats — BA/ac, TPA, QMD, mean DBH, mean H, BF/ac
//   • Species mix breakdown
//
// The Stocking & Density gauge is gone — see the note at the foot of this
// file for why.
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
    /// Areal basis for the density stats (BA, TPA per unit land area). Metric
    /// countries read per hectare; US per acre. Defaults to `.acre`.
    public let areaUnit: AreaUnit

    public init(plot: QuickMeasurePlot,
                entries: [QuickMeasureEntry],
                unitSystem: UnitSystem,
                logRule: LogRule,
                areaUnit: AreaUnit = .acre) {
        self.plot = plot
        self.entries = entries
        self.unitSystem = unitSystem
        self.logRule = logRule
        self.areaUnit = areaUnit
    }

    /// Per-acre → display-basis multiplier (1.0 US, 2.47105 metric hectares).
    private var densityFactor: Double { areaUnit.perAcreDensityFactor }

    public var body: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.md) {
            header
            Divider()
            statsGrid
            if let stats = stats, stats.distinctTrees >= 1 {
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
            parts.append(String(format: "%.2f %@",
                                areaUnit.fromAcres(Double(ac)), areaUnit.abbreviation))
        }
        return parts.joined(separator: " · ")
    }

    // MARK: - Top stats

    /// FIELD REPORT — this row is four equal cells, so on a 360 pt phone each
    /// one is about 64 pt wide. "BASAL/HA", "TREES/HA" and "MEAN DBH" each
    /// want ~75 pt as spaced caps at 13 pt, so all three wrapped, and with no
    /// padding they ran into the hairline dividers besides. The cells keep
    /// their equal weights (they always shared the width correctly), and
    /// every label is now single-line, tightened and scaled rather than
    /// wrapped, so it is the SCALE that absorbs a long label instead of a
    /// second line. That is what let "QMD" and "TPA"/"TPH" go back to
    /// "MEAN DBH" and "TREES/AC"/"TREES/HA" — both land at ~0.85 of full
    /// size, above the 0.8 floor below, and match the Android sibling.
    private var statsGrid: some View {
        let s = stats
        return HStack(spacing: 0) {
            statsCell("TREES", s?.distinctTrees.description ?? "—")
            divider
            // "BA" was the last index initialism on this card, and it
            // disagreed with both the comment above (which already claimed
            // "BASAL/HA") and the Android sibling (which already ships
            // "BASAL/$suffix"). Basal area is genuine forestry vocabulary,
            // so it is spelled rather than renamed; the per-area suffix
            // stays in the label because this cell has no unit slot.
            statsCell(areaUnit.densityLabel("BASAL").uppercased(),
                      s.map { String(format: "%.0f", $0.baPerAcre * densityFactor) } ?? "—")
            divider
            statsCell(treesPerAreaLabel,
                      s.map { String(format: "%.0f", $0.tpa * densityFactor) } ?? "—")
            divider
            statsCell("MEAN DBH",
                      s.flatMap { $0.qmd.map { qmd in
                          MeasurementFormatter.diameter(cm: qmd, in: unitSystem)
                      } } ?? "—")
        }
    }

    /// Trees per unit land area, spelled so the label says what the number
    /// is. "TPA"/"TPH" also swapped a letter with the units setting, so the
    /// label a cruiser learned on one project was a different label on the
    /// next. The cell can no longer wrap (single line, tightened, scaled),
    /// so the width argument the old abbreviation rested on is gone; the
    /// Android sibling already ships these words.
    private var treesPerAreaLabel: String {
        areaUnit == .hectare ? "TREES/HA" : "TREES/AC"
    }

    private var divider: some View {
        Rectangle()
            .fill(ForestixPalette.divider)
            .frame(width: 0.5, height: 32)
    }

    private func statsCell(_ label: String, _ value: String) -> some View {
        VStack(spacing: 2) {
            // FIELD REPORT — these values were `dataLarge` (26 pt), which the
            // cruiser read as awkwardly oversized, and which is also why the
            // cell needed a 0.4 scale floor: "31.4 cm" measures ~113 pt at 26 pt
            // monospaced against the ~56 pt a cell has on a 360 pt phone, so a
            // QMD carrying its unit was being shrunk by more than half before it
            // fitted. One step down the SAME data scale (`data`, 17 pt) is the
            // size the log's own row values already use, so the card and the
            // rows beneath it now read as one sheet. The floor stays — a
            // measurement scaled down is still right, one truncated is wrong.
            Text(value)
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(0.4)
            Text(label)
                .font(ForestixType.sectionHead)
                .tracking(0.8)
                .foregroundStyle(ForestixPalette.textTertiary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(0.8)
        }
        // Keeps the caps off the hairline dividers either side.
        .padding(.horizontal, ForestixSpace.xxs)
        .frame(maxWidth: .infinity)
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
                        Text(row.code.isEmpty ? "—" : RegionalSpecies.name(forCode: row.code))
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .lineLimit(1)
                            .frame(width: 96, alignment: .leading)
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

        // BA per tree, in the base unit that matches the displayed density
        // label: ft² for the US "/ac" card, m² for the metric "/ha" card.
        // Computing ft² and then labelling it "/ha" over-reads BA by ~10.76×
        // (the ft²→m² factor), so the numerator has to switch with the label.
        // With no plot-acres tied to these readings yet, "per acre" means
        // "per tree-bin" — a relative readout, refined in Phase 4 with real
        // acreage.
        let baPerTree: [Double] = dbhTrees.map { cm in
            if areaUnit == .hectare {
                let m = cm / 100.0
                return Double.pi / 4.0 * m * m          // m² basal area
            } else {
                let inches = cm / 2.54
                return 0.005454 * inches * inches        // ft² basal area
            }
        }
        let acres = max(plot.acres ?? 0.1, 0.05)   // sane fallback
        let baPerAcre = baPerTree.reduce(0, +) / acres
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
            speciesMix: mix.map { (code: $0.0, share: $0.1) })
    }
}

// MARK: - Why the stocking gauge is gone
//
// FIELD REPORT — "Over-dense" was shown with nothing on screen saying what
// it crossed, so the cruiser asked for the rule to be named. The rule turned
// out to be un-nameable:
//
//   • The verdict was `Reineke SDI / 717 >= 60 %`, and 717 was one hard-coded
//     "generic PNW conifer mid-range" maximum applied to every species on
//     every continent this app ships to. A maximum SDI is species-specific;
//     against the wrong species the same stand is understocked or over-dense
//     purely by which constant is in the source.
//   • SDI is built from trees-per-acre, and this card divides by
//     `max(plot.acres ?? 0.1, 0.05)` — a plot with no acreage entered gets an
//     invented tenth of an acre. The density the word was computed from is
//     therefore not a density of anything the cruiser measured.
//
// So there is no threshold to print, and printing "60 % of SDI 717" would
// have dressed up a number nobody can defend as if it were a finding. The
// whole gauge went with the word: the coloured band and the marker encode
// the SAME percentage, so leaving them would have kept the verdict and only
// removed its name. TREES, BASAL, TREES/AC, MEAN DBH and the species mix are
// unchanged — they are counts and diameters, not a judgement.
//
// If a per-species maximum-SDI table ever lands, this comes back WITH the
// species and the maximum on screen beside the word.
