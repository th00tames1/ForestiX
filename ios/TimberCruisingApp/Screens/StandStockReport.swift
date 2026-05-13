// Stand-and-stock report — species × DBH-class table for the
// active plot. The actual deliverable a consulting forester hands
// to a landowner / mill / agency. SilvaCruise's headline output;
// without it, Forestix is "an instrument" not "a cruise app".
//
// Output:
//   • Rows: species (sorted by tree count desc)
//   • Columns: DBH classes (10 cm bins by default)
//   • Cells: tree count + estimated BF (active log rule)
//   • Footer rows: subtotal per DBH class, grand totals
//
// Pure computation lives in `StandStockComputation`; the view is
// just rendering. Phase 4.3 will export this same computation as
// the Trees + Calculations CSV files in the multi-table bundle.

import SwiftUI
import Models
import Sensors

// MARK: - Computation

public enum StandStockComputation {

    public struct Row: Equatable {
        public let speciesCode: String
        public let countByClass: [Int: Int]      // class index → count
        public let bfByClass: [Int: Double]      // class index → BF
        public let totalCount: Int
        public let totalBF: Double
    }

    public struct Table {
        public let dbhClassesCm: [Range<Double>]   // ordered low→high
        public let rows: [Row]                      // one per species
        public let totalsByClass: [Int: Int]        // class → total count
        public let bfTotalsByClass: [Int: Double]   // class → total BF
        public let grandCount: Int
        public let grandBF: Double
    }

    /// Builds the table from a Quick Measure plot's entries.
    /// `binWidthCm` defaults to 10 cm (≈ 4 in) — common cruise bin.
    public static func build(entries: [QuickMeasureEntry],
                             logRule: LogRule,
                             binWidthCm: Double = 10.0) -> Table {
        // Group by tree number. Each tree bin contributes its DBH +
        // optional height + first non-empty species code.
        let byTree = Dictionary(grouping: entries) { $0.treeNumber ?? -1 }

        struct Tree {
            let dbhCm: Double
            let heightM: Double?
            let species: String
        }

        let trees: [Tree] = byTree.compactMap { (_, group) in
            guard let dbh = group.first(where: { $0.kind == .dbh })?.value
            else { return nil }
            let h   = group.first(where: { $0.kind == .height })?.value
            let sp  = group.first(where: { ($0.speciesCode ?? "").isEmpty == false })?
                .speciesCode ?? "—"
            return Tree(dbhCm: dbh, heightM: h, species: sp)
        }
        guard !trees.isEmpty else {
            return Table(dbhClassesCm: [], rows: [],
                          totalsByClass: [:], bfTotalsByClass: [:],
                          grandCount: 0, grandBF: 0)
        }

        // Class breakpoints — start at the DBH-class containing the
        // smallest tree, end at the class containing the biggest.
        let minDBH = trees.map(\.dbhCm).min()!
        let maxDBH = trees.map(\.dbhCm).max()!
        let firstClassLo = floor(minDBH / binWidthCm) * binWidthCm
        let lastClassHi  = ceil((maxDBH + 0.001) / binWidthCm) * binWidthCm
        var classes: [Range<Double>] = []
        var lo = firstClassLo
        while lo < lastClassHi {
            classes.append(lo..<(lo + binWidthCm))
            lo += binWidthCm
        }

        func classIndex(for dbh: Double) -> Int {
            let i = Int(floor((dbh - firstClassLo) / binWidthCm))
            return max(0, min(classes.count - 1, i))
        }

        // Aggregate per-species rows
        let bySpecies = Dictionary(grouping: trees, by: \.species)
        var rows: [Row] = []
        var totalsByClass: [Int: Int] = [:]
        var bfTotalsByClass: [Int: Double] = [:]
        var grandCount = 0
        var grandBF: Double = 0

        for (sp, group) in bySpecies {
            var c: [Int: Int] = [:]
            var bf: [Int: Double] = [:]
            var rowCount = 0
            var rowBF: Double = 0
            for t in group {
                let idx = classIndex(for: t.dbhCm)
                c[idx, default: 0] += 1
                rowCount += 1
                totalsByClass[idx, default: 0] += 1
                if let h = t.heightM,
                   let bfTree = VolumeConversion.boardFeet(
                       dbhCm: t.dbhCm, totalHeightM: h, rule: logRule) {
                    bf[idx, default: 0] += bfTree
                    rowBF += bfTree
                    bfTotalsByClass[idx, default: 0] += bfTree
                    grandBF += bfTree
                }
            }
            grandCount += rowCount
            rows.append(Row(speciesCode: sp,
                             countByClass: c, bfByClass: bf,
                             totalCount: rowCount, totalBF: rowBF))
        }
        rows.sort { $0.totalCount > $1.totalCount }

        return Table(dbhClassesCm: classes,
                      rows: rows,
                      totalsByClass: totalsByClass,
                      bfTotalsByClass: bfTotalsByClass,
                      grandCount: grandCount,
                      grandBF: grandBF)
    }
}

// MARK: - View

public struct StandStockReport: View {

    @EnvironmentObject private var settings: AppSettings
    public let plot: QuickMeasurePlot
    public let entries: [QuickMeasureEntry]

    public init(plot: QuickMeasurePlot,
                entries: [QuickMeasureEntry]) {
        self.plot = plot
        self.entries = entries
    }

    public var body: some View {
        let table = StandStockComputation.build(
            entries: entries, logRule: settings.logRule)
        return ScrollView([.vertical]) {
            VStack(alignment: .leading, spacing: ForestixSpace.md) {
                header
                if table.rows.isEmpty {
                    emptyState
                } else {
                    ScrollView(.horizontal, showsIndicators: false) {
                        VStack(alignment: .leading, spacing: 0) {
                            classHeader(table)
                            ForEach(Array(table.rows.enumerated()), id: \.offset) { _, row in
                                speciesRow(row, table: table)
                            }
                            Divider().padding(.vertical, 4)
                            totalsRow(table)
                        }
                    }
                    notes(table)
                }
            }
            .padding(.horizontal, ForestixSpace.md)
            .padding(.vertical, ForestixSpace.md)
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Stand & stock")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: header / empty / notes

    private var header: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("STAND & STOCK")
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            Text(plot.name)
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text("\(entries.count) reading\(entries.count == 1 ? "" : "s") · \(settings.logRule.displayName)")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
    }

    private var emptyState: some View {
        Text("No tallied trees yet. Add DBH (and optionally height) readings to populate this report.")
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.textSecondary)
            .padding(.vertical, ForestixSpace.lg)
            .frame(maxWidth: .infinity)
    }

    private func notes(_ table: StandStockComputation.Table) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("DBH-class width: 10 cm. Heights blank = trees with no height reading; BF computed only when both DBH + height present.")
                .font(ForestixType.caption.italic())
                .foregroundStyle(ForestixPalette.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: row helpers

    private func classHeader(_ t: StandStockComputation.Table) -> some View {
        HStack(spacing: 0) {
            Text("SPECIES")
                .frame(width: 90, alignment: .leading)
            ForEach(Array(t.dbhClassesCm.enumerated()), id: \.offset) { _, range in
                Text(classLabel(for: range))
                    .frame(width: 80, alignment: .trailing)
                    .help(String(format: "DBH %.0f-%.0f cm", range.lowerBound, range.upperBound))
            }
            Text("TOTAL").frame(width: 96, alignment: .trailing)
        }
        .font(ForestixType.sectionHead)
        .tracking(1.2)
        .foregroundStyle(ForestixPalette.textTertiary)
        .padding(.vertical, ForestixSpace.xs)
    }

    private func classLabel(for range: Range<Double>) -> String {
        if settings.unitSystem == .imperial {
            let lo = range.lowerBound / 2.54
            let hi = range.upperBound / 2.54
            return String(format: "%.0f-%.0f in", lo, hi)
        } else {
            return String(format: "%.0f-%.0f cm",
                           range.lowerBound, range.upperBound)
        }
    }

    private func speciesRow(_ row: StandStockComputation.Row,
                             table: StandStockComputation.Table) -> some View {
        HStack(spacing: 0) {
            Text(row.speciesCode)
                .font(ForestixType.dataSmall)
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(width: 90, alignment: .leading)
            ForEach(Array(table.dbhClassesCm.enumerated()), id: \.offset) { i, _ in
                Text(cellLabel(count: row.countByClass[i] ?? 0,
                                bf: row.bfByClass[i] ?? 0))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 80, alignment: .trailing)
            }
            Text(totalLabel(count: row.totalCount, bf: row.totalBF))
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(width: 96, alignment: .trailing)
        }
        .padding(.vertical, 4)
    }

    private func totalsRow(_ t: StandStockComputation.Table) -> some View {
        HStack(spacing: 0) {
            Text("TOTAL")
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(width: 90, alignment: .leading)
            ForEach(Array(t.dbhClassesCm.enumerated()), id: \.offset) { i, _ in
                Text(cellLabel(count: t.totalsByClass[i] ?? 0,
                                bf: t.bfTotalsByClass[i] ?? 0))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .frame(width: 80, alignment: .trailing)
            }
            Text(totalLabel(count: t.grandCount, bf: t.grandBF))
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(width: 96, alignment: .trailing)
        }
        .padding(.vertical, 4)
        .background(ForestixPalette.surface)
    }

    private func cellLabel(count: Int, bf: Double) -> String {
        if count == 0 { return "—" }
        if bf <= 0 { return "\(count)" }
        return "\(count) · \(Int(bf)) BF"
    }

    private func totalLabel(count: Int, bf: Double) -> String {
        if bf <= 0 { return "\(count)" }
        return "\(count) · \(Int(bf)) BF"
    }
}
