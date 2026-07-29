// Unit-aware display formatter — central source of truth for how
// every measurement renders given the cruiser's chosen UnitSystem.
// Storage stays metric (DBH cm, Height m, sigma DBH mm / sigma H m
// as documented on `QuickMeasureEntry`); the display layer converts
// once on the way to the screen / CSV / share sheet.
//
// Without this every screen had its own ad-hoc `String(format:"%.1f cm",
// value)` lines, and the `AppSettings.unitSystem` toggle was a lie:
// the cruiser could pick Imperial in Settings and still see cm
// everywhere. This helper plus a sweep through the display sites
// fixes that.

import Foundation
import Models

public enum MeasurementFormatter {

    // MARK: - Diameter

    /// Renders a stored DBH (in centimetres) for display.
    ///   • metric  → "34.5 cm"
    ///   • imperial → "13.6 in"
    public static func diameter(cm: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "%.1f cm", cm)
        case .imperial:
            let inches = cm / 2.54
            return String(format: "%.1f in", inches)
        }
    }

    /// Renders a DBH precision sigma (stored in millimetres).
    ///   • metric  → "±2.1 mm"
    ///   • imperial → "±0.08 in"  (mm → in via /25.4)
    /// The band is FLOORED at the smallest value the chosen precision can
    /// print, so neither branch can render "±0.0 mm" / "±0.00 in".
    ///
    /// A zero band claims a perfect measurement, which is the one thing an
    /// uncertainty readout must never say — and it is reachable: the imperial
    /// branch rounds anything under 0.127 mm to zero, and with the shipped
    /// depth-noise default a wide arc with a full burst lands near 0.11 mm.
    /// Rounding UP to the floor overstates the band slightly, which is the
    /// safe direction. Mirrors `UncertaintyBand` on the height side.
    public static func diameterSigma(mm: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "±%.1f mm", max(mm, 0.1))
        case .imperial:
            let inches = mm / 25.4
            return String(format: "±%.2f in", max(inches, 0.01))
        }
    }

    // MARK: - Height

    /// Renders a stored height (in metres) for display.
    ///   • metric  → "28.24 m"
    ///   • imperial → "92.65 ft"
    ///
    /// TWO decimals, field-requested. One was a rounding coarser than the
    /// measurement: a walk-off tangent fit resolves well inside a decimetre
    /// on a clean sightline, and the validation study compares these numbers
    /// against a hand-measured truth typed to the centimetre. At one decimal
    /// two heights 6 cm apart printed the same string, which made a real
    /// difference between algorithms invisible on the screen that shows it.
    /// The ± band beside it (`heightSigma`) is what says how much of the
    /// second decimal to believe. Android prints the identical string.
    public static func height(m: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "%.1f m", m)
        case .imperial:
            let feet = m * 3.28084
            return String(format: "%.1f ft", feet)
        }
    }

    /// Renders a height precision sigma (stored in metres).
    ///   • metric  → "±0.4 m"  (and "±0.04 m", never "±0.0 m")
    ///   • imperial → "±1.3 ft"
    ///
    /// Delegates to `UncertaintyBand` — the one place a ± band is rounded,
    /// shared with the estimators. This used to be its own `%.1f`, so the
    /// field log's uncertainty column and the map's quick-measure detail
    /// printed "±0.0 m" for any reading under 5 cm of σ: precisely the
    /// readings the app is least sure about, wearing a claim of perfection.
    public static func heightSigma(m: Double, in system: UnitSystem) -> String {
        UncertaintyBand.text(metres: m, in: system)
    }

    // MARK: - Distance / generic length

    /// Renders a horizontal distance (stored in metres) for display.
    ///   • metric  → "3.42 m"; sub-metre readings switch to whole
    ///     centimetres ("85 cm") — a close-range distance in "0.85 m"
    ///     reads slower than the tape-measure unit cruisers expect.
    ///   • imperial → "11.2 ft"
    public static func distance(m: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            if m < 1 { return String(format: "%.0f cm", m * 100) }
            return String(format: "%.2f m", m)
        case .imperial:
            return String(format: "%.1f ft", m * 3.28084)
        }
    }

    /// Returns the unit suffix only — for table columns that already
    /// formatted the number themselves (legacy code paths).
    public static func diameterUnit(_ system: UnitSystem) -> String {
        system == .metric ? "cm" : "in"
    }

    public static func heightUnit(_ system: UnitSystem) -> String {
        system == .metric ? "m" : "ft"
    }
}
