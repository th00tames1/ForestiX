// In-app summary card — closes the "is this reasonable?" loop in the field.
// A cruiser gets an instant post-plot readout with standard stand stats and
// doesn't need a desktop tool to know whether to re-cruise.
//
// THE CARD IS NOW A RENDERER. It used to hold the quick-measure math itself
// and could therefore only ever describe a quick-measure plot — which is why
// it appeared for one special case and went blank or, worse, stale under any
// other filter. Every number it draws now arrives finished in a
// `FieldLogSummary`, built for whatever the field log's filter is currently
// set to; see FieldLogSummary.swift for the three branches and for why the
// cruise ones reuse the cruise view models rather than recomputing.
//
// Renders:
//   • A tappable heading — it opens the detail view, which carries the values
//     this card has no room for AND the settings behind them. A volume with
//     no log rule or equation named beside it is a number a cruiser cannot
//     check, and this card has never had space to name them.
//   • Four top-line stats
//   • Species mix breakdown
//
// The Stocking & Density gauge is gone — see the note at the foot of this
// file for why.

import SwiftUI
import Models

public struct PlotSummaryCard: View {

    public let summary: FieldLogSummary
    /// Raised by the heading. The host owns the detail view because it owns
    /// the presentation — the card is inside a List row.
    public let onOpenDetail: () -> Void

    public init(summary: FieldLogSummary,
                onOpenDetail: @escaping () -> Void) {
        self.summary = summary
        self.onOpenDetail = onOpenDetail
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.md) {
            header
            Divider()
            statsGrid
            if let note = summary.note {
                Text(note)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if !summary.speciesMix.isEmpty {
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
            // The heading is the control. A chevron rather than an info glyph:
            // it opens a view, which is what a chevron means everywhere else
            // in this app, and the row it sits on is the only thing on the
            // card a tap could plausibly be aimed at.
            Button(action: onOpenDetail) {
                HStack(spacing: 4) {
                    Text(summary.heading)
                        .font(ForestixType.sectionHead)
                        .tracking(1.5)
                        .foregroundStyle(ForestixPalette.textTertiary)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 9, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textTertiary)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(FieldLogSummary.detailTitle)
            .accessibilityIdentifier("fieldLog.summaryDetail")
            Text(summary.title)
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(2)
            if let subtitle = summary.subtitle {
                Text(subtitle)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .lineLimit(2)
            }
        }
    }

    // MARK: - Top stats

    /// FIELD REPORT — this row is four equal cells, so on a 360 pt phone each
    /// one is about 64 pt wide. "BASAL/HA", "TREES/HA" and "MEAN DBH" each
    /// want ~75 pt as spaced caps at 13 pt, so all three wrapped, and with no
    /// padding they ran into the hairline dividers besides. The cells keep
    /// their equal weights (they always shared the width correctly), and
    /// every label is now single-line, tightened and scaled rather than
    /// wrapped, so it is the SCALE that absorbs a long label instead of a
    /// second line.
    private var statsGrid: some View {
        HStack(spacing: 0) {
            ForEach(Array(summary.cells.enumerated()), id: \.offset) { index, cell in
                if index > 0 { divider }
                statsCell(cell)
            }
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(ForestixPalette.divider)
            .frame(width: 0.5, height: 32)
    }

    private func statsCell(_ cell: FieldLogSummary.Cell) -> some View {
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
            Text(cell.value)
                .font(ForestixType.data)
                .foregroundStyle(ForestixPalette.textPrimary)
                .lineLimit(1)
                .allowsTightening(true)
                .minimumScaleFactor(0.4)
            Text(cell.label)
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

    private var speciesMix: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text("SPECIES MIX")
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
            ForEach(summary.speciesMix, id: \.code) { row in
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
                                .frame(width: geo.size.width * CGFloat(min(max(row.share, 0), 1)))
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

// MARK: - Detail view

/// The full set of computed values, and the settings that produced them.
///
/// It exists because the card cannot name a log rule, a volume equation or a
/// plot size, and a volume read without them is a number the cruiser has no
/// way to check — the same figure means different things under Doyle and
/// under Scribner, and under a 0.1 ac plot and a BAF 20 sweep. Everything
/// here is text the builder already finished; this view only lays it out.
public struct FieldLogSummaryDetail: View {

    public let summary: FieldLogSummary
    @Environment(\.dismiss) private var dismiss

    public init(summary: FieldLogSummary) {
        self.summary = summary
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(summary.title)
                            .font(ForestixType.bodyBold)
                            .foregroundStyle(ForestixPalette.textPrimary)
                        if let subtitle = summary.subtitle {
                            Text(subtitle)
                                .font(ForestixType.caption)
                                .foregroundStyle(ForestixPalette.textSecondary)
                        }
                    }
                    if let note = summary.note {
                        Text(note)
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                ForEach(summary.groups.filter { !$0.rows.isEmpty }, id: \.title) { group in
                    Section(group.title) {
                        ForEach(group.rows, id: \.label) { row in
                            // Label above value, not beside it: several of
                            // these values are sentences, and a two-column row
                            // would squeeze them into a column three words
                            // wide.
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.label)
                                    .font(ForestixType.caption)
                                    .foregroundStyle(ForestixPalette.textSecondary)
                                Text(row.value)
                                    .font(ForestixType.body)
                                    .foregroundStyle(ForestixPalette.textPrimary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            }
            .navigationTitle(FieldLogSummary.detailTitle)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("fieldLog.summaryDetail.done")
                }
            }
        }
        .accessibilityIdentifier("fieldLog.summaryDetail.sheet")
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
//   • SDI is built from trees-per-acre, and the quick branch divides by
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
// species and the maximum on screen beside the word. The invented tenth of
// an acre is now at least DISCLOSED, on the detail view's "Plot area" line.
