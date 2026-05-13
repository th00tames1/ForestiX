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
    public static func diameterSigma(mm: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "±%.1f mm", mm)
        case .imperial:
            let inches = mm / 25.4
            return String(format: "±%.2f in", inches)
        }
    }

    // MARK: - Height

    /// Renders a stored height (in metres) for display.
    ///   • metric  → "28.2 m"
    ///   • imperial → "92.5 ft"
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
    ///   • metric  → "±0.4 m"
    ///   • imperial → "±1.3 ft"
    public static func heightSigma(m: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "±%.1f m", m)
        case .imperial:
            let feet = m * 3.28084
            return String(format: "±%.1f ft", feet)
        }
    }

    // MARK: - Distance / generic length

    /// Renders a horizontal distance (stored in metres) for display.
    public static func distance(m: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
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
