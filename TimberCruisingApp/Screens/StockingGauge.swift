// Stocking & Density gauge — a 5-band gradient bar with a position
// marker, modelled on SilvaCruise's plot-summary readout. Lets the
// cruiser see at a glance whether a stand is understocked / fully
// stocked / over-dense without parsing four separate density numbers.
//
// The classic stocking-percent ranges (USFS / standard silviculture):
//   • 0–25 %   → understocked
//   • 25–35 %  → low stocking
//   • 35–60 %  → adequately stocked
//   • 60–100 % → fully stocked / over-dense
//
// Uses the design-system tier colours so the gauge stays consistent
// with the QUAL chip and the GPS / Tilt badges. Caller passes the
// current relative-density percentage (Curtis RD or similar) and a
// short label that names the regime; the gauge does the rest.

import SwiftUI

public struct StockingGauge: View {

    /// Current relative density expressed as a percent (0…100+).
    /// Values above 100 clamp to the right edge of the bar.
    public let relativeDensityPct: Double
    /// Short regime label rendered as a pill above the gauge —
    /// e.g. "Understocked", "Adequately stocked", "Over-dense".
    public let regimeLabel: String

    public init(relativeDensityPct: Double, regimeLabel: String) {
        self.relativeDensityPct = relativeDensityPct
        self.regimeLabel = regimeLabel
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            HStack {
                regimePill
                Spacer()
            }
            gradientBar
            tickLabels
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Stocking \(regimeLabel)")
        .accessibilityValue(String(format: "%.0f percent", relativeDensityPct))
    }

    // MARK: - Regime pill

    private var regimePill: some View {
        Text(regimeLabel)
            .font(ForestixType.sectionHead)
            .tracking(1.2)
            .padding(.horizontal, ForestixSpace.xs)
            .padding(.vertical, 3)
            .overlay(
                Capsule()
                    .stroke(regimeColor, lineWidth: 0.75))
            .foregroundStyle(regimeColor)
    }

    private var regimeColor: Color {
        switch relativeDensityPct {
        case ..<25:    return ForestixPalette.confidenceBad   // understocked
        case ..<35:    return ForestixPalette.confidenceWarn  // low
        case ..<60:    return ForestixPalette.confidenceOk    // adequate
        default:       return ForestixPalette.confidenceWarn  // over-dense
        }
    }

    // MARK: - Gradient bar + marker

    private var gradientBar: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                // 5-band gradient — left red (under), through amber and
                // green (adequate), to amber on the right (over-dense).
                Capsule()
                    .fill(LinearGradient(
                        stops: [
                            .init(color: ForestixPalette.confidenceBad,  location: 0.00),
                            .init(color: ForestixPalette.confidenceWarn, location: 0.25),
                            .init(color: ForestixPalette.confidenceOk,   location: 0.45),
                            .init(color: ForestixPalette.confidenceOk,   location: 0.60),
                            .init(color: ForestixPalette.confidenceWarn, location: 1.00)
                        ],
                        startPoint: .leading,
                        endPoint: .trailing))
                    .frame(height: 10)

                // Position marker — black halo + white pin so it pops
                // on every band of the gradient.
                let clamped = max(0, min(100, relativeDensityPct))
                let x = geo.size.width * CGFloat(clamped / 100.0)
                ZStack {
                    Capsule()
                        .fill(Color.black)
                        .frame(width: 4, height: 18)
                    Capsule()
                        .fill(Color.white)
                        .frame(width: 2, height: 16)
                }
                .position(x: x, y: 5)
            }
        }
        .frame(height: 18)
    }

    // MARK: - Tick labels

    private var tickLabels: some View {
        HStack {
            tickLabel("0%")
            Spacer()
            tickLabel("25%")
            Spacer()
            tickLabel("35%")
            Spacer()
            tickLabel("60%")
            Spacer()
            tickLabel("100%")
        }
    }

    private func tickLabel(_ s: String) -> some View {
        Text(s)
            .font(ForestixType.dataSmall)
            .foregroundStyle(ForestixPalette.textTertiary)
    }
}

#Preview {
    VStack(spacing: 24) {
        StockingGauge(relativeDensityPct: 16, regimeLabel: "Understocked")
        StockingGauge(relativeDensityPct: 30, regimeLabel: "Low stocking")
        StockingGauge(relativeDensityPct: 50, regimeLabel: "Adequately stocked")
        StockingGauge(relativeDensityPct: 78, regimeLabel: "Over-dense")
    }
    .padding()
}
