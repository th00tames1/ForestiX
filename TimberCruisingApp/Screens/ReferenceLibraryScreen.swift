// Field reference library — replaces the laminated pocket card a
// cruiser carries in their vest. SilvaCruise's killer "vest in your
// pocket" content packaged as in-app reference cards. Pure SwiftUI,
// no sensor work.
//
// Sections:
//   • Key formulas (basal area, TPA variable / fixed, plot radius,
//     QMD, plot spacing, slope correction, HDR / wind-risk)
//   • Log rules (Scribner Decimal C, International ¼", Doyle —
//     descriptions and use cases, not lookup tables for now)
//   • Conversions (chains↔ft, acres↔hectares, in↔cm, ft↔m, BAF
//     metric↔imperial)
//
// Each entry uses `FormulaCard`: bold title, monospace formula in a
// tinted code-block, 1-line italic caption. Same anatomy SilvaCruise
// uses, mapped to our DesignSystem tokens.

import SwiftUI

public struct ReferenceLibraryScreen: View {

    public init() {}

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: ForestixSpace.lg) {
                section(title: "KEY FORMULAS", entries: keyFormulas)
                section(title: "LOG RULES",    entries: logRules)
                section(title: "CONVERSIONS",  entries: conversions)
                section(title: "FIELD CHECKS", entries: fieldChecks)
            }
            .padding(.horizontal, ForestixSpace.md)
            .padding(.top, ForestixSpace.sm)
            .padding(.bottom, ForestixSpace.xl)
        }
        .background(ForestixPalette.canvas.ignoresSafeArea())
        .navigationTitle("Reference")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: - Section

    private func section(title: String, entries: [FormulaEntry]) -> some View {
        VStack(alignment: .leading, spacing: ForestixSpace.sm) {
            Text(title)
                .font(ForestixType.sectionHead)
                .tracking(1.5)
                .foregroundStyle(ForestixPalette.textTertiary)
                .padding(.leading, ForestixSpace.xs)
            VStack(spacing: ForestixSpace.sm) {
                ForEach(entries) { e in
                    FormulaCard(entry: e)
                }
            }
        }
    }

    // MARK: - Content

    private var keyFormulas: [FormulaEntry] { [
        FormulaEntry(
            title: "Basal area, per tree",
            formula: "BA = 0.005454 × DBH²",
            caption: "Basal area in ft², DBH in inches. Use 0.00007854 × DBH² for cm² → m²."),
        FormulaEntry(
            title: "Trees per acre — variable-radius (prism)",
            formula: "TPA = BAF / (0.005454 × DBH²)",
            caption: "Per-tree expansion factor for prism / angle-gauge sampling. BAF = basal area factor."),
        FormulaEntry(
            title: "Trees per acre — fixed-radius",
            formula: "TPA = 1 / plot_acres",
            caption: "Every tally tree in a fixed plot shares the same expansion factor."),
        FormulaEntry(
            title: "Plot radius",
            formula: "r = √(43,560 × acres / π)",
            caption: "Plot radius in feet for circular fixed-area plots."),
        FormulaEntry(
            title: "Quadratic mean diameter",
            formula: "QMD = √(Σ DBH² / n)",
            caption: "The basal-area-weighted central tendency of stand diameters."),
        FormulaEntry(
            title: "Plot spacing (systematic)",
            formula: "spacing = √(acres_per_plot × 43,560)",
            caption: "Grid spacing in feet for an even-sample design."),
        FormulaEntry(
            title: "Slope correction (basal area + radius)",
            formula: "horizontal = slope × cos(θ)",
            caption: "On a slope, multiply your tape distance by cos(slope) to get horizontal."),
        FormulaEntry(
            title: "Height-to-diameter ratio (wind risk)",
            formula: "ratio = height / DBH (same units)",
            caption: "Ratio > 100 → high windthrow risk; 80–100 marginal; < 80 stable."),
    ] }

    private var logRules: [FormulaEntry] { [
        FormulaEntry(
            title: "Scribner Decimal C",
            formula: "board-feet ≈ ((0.79 × D² − 2 × D − 4) / 16) × L",
            caption: "USFS standard for the Western US. Conservative on small logs."),
        FormulaEntry(
            title: "International ¼-Inch",
            formula: "board-feet = 0.04976 × D² × L − 1.86 × D × L",
            caption: "Most accurate of the three; standard for hardwoods + research."),
        FormulaEntry(
            title: "Doyle",
            formula: "board-feet = ((D − 4) / 4)² × L",
            caption: "Dominant in the Eastern US. Substantially underestimates small logs."),
    ] }

    private var conversions: [FormulaEntry] { [
        FormulaEntry(
            title: "Inches ↔ Centimetres",
            formula: "1 in = 2.54 cm    1 cm ≈ 0.394 in",
            caption: "Diameter conversions for cross-region comparisons."),
        FormulaEntry(
            title: "Feet ↔ Metres",
            formula: "1 ft = 0.3048 m    1 m ≈ 3.2808 ft",
            caption: "Height + plot-radius conversions."),
        FormulaEntry(
            title: "Acres ↔ Hectares",
            formula: "1 ac ≈ 0.4047 ha    1 ha ≈ 2.471 ac",
            caption: "Stand-area conversions for international handoff."),
        FormulaEntry(
            title: "Chains ↔ Feet",
            formula: "1 chain = 66 ft = 20.117 m",
            caption: "Surveyor's chain — still the unit of habit on USFS land."),
        FormulaEntry(
            title: "Basal area factor — Imperial ↔ Metric",
            formula: "factor₍ft²/ac₎ × 0.2296 = factor₍m²/ha₎",
            caption: "10 ft²/ac ≈ 2.30 m²/ha; 20 ≈ 4.59; 40 ≈ 9.18."),
    ] }

    private var fieldChecks: [FormulaEntry] { [
        FormulaEntry(
            title: "Limiting distance (variable-radius plot)",
            formula: "plot-radius-factor = √(BAF / 10,890) × DBH₍ft₎",
            caption: "Maximum horizontal distance a borderline tree may stand from plot centre. BAF = basal area factor."),
        FormulaEntry(
            title: "Sampling error (one-sided)",
            formula: "standard-error % = (t × variability) / √n",
            caption: "Add plots until standard error drops below your target (e.g. 10 %)."),
        FormulaEntry(
            title: "Reineke stand density index",
            formula: "index = TPA × (QMD / 10)^1.605",
            caption: "Species-independent stocking yardstick. TPA = trees per acre, QMD = quadratic mean diameter."),
    ] }
}

// MARK: - Formula entry + card

public struct FormulaEntry: Identifiable {
    public let id = UUID()
    public let title: String
    public let formula: String
    public let caption: String
}

private struct FormulaCard: View {
    let entry: FormulaEntry

    var body: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text(entry.title)
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
            Text(entry.formula)
                .font(.system(size: 14, weight: .regular, design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
                .padding(.horizontal, ForestixSpace.sm)
                .padding(.vertical, ForestixSpace.xs)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.chip,
                                     style: .continuous)
                        .fill(ForestixPalette.surfaceRaised))
            Text(entry.caption)
                .font(ForestixType.caption.italic())
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(ForestixSpace.md)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 0.5))
    }
}
