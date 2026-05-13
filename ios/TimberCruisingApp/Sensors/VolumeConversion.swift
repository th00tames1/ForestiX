// Tree-volume conversion — DBH + height → board feet (Scribner /
// International ¼" / Doyle). Adopted from SilvaCruise's pitch
// ("peer-reviewed Wensel & Olsen 1995 for 8 western conifers,
// Clark stem profile SE-282 for southern species, Form Class 78
// fallback") so a Forestix DBH+H reading produces a salable cruise
// number, not just a raw measurement.
//
// Scope of this first cut:
//
//   • Three log rules implemented as explicit board-foot formulas
//     evaluated per 16-ft log (the spec foot for all three rules),
//     summed to the merchantable height.
//   • A simplified Form Class 78 volume table — generic taper
//     applicable to species without a specific equation in the
//     literature. Good enough as a default; specific Wensel/Olsen +
//     Clark coefficients can be wired in later as `VolumeRule`
//     conformances without breaking this API.
//
// Storage convention reminder: DBH cm + height m on disk; volume
// math is happiest in inches + feet so the conversions live here.

import Foundation

public enum LogRule: String, Codable, Sendable, CaseIterable {
    case scribner       // Scribner Decimal C — USFS Western standard
    case international  // International ¼-Inch — most accurate
    case doyle          // Doyle — Eastern US standard

    public var displayName: String {
        switch self {
        case .scribner:      return "Scribner Decimal C"
        case .international: return "International ¼″"
        case .doyle:         return "Doyle"
        }
    }
}

public enum VolumeConversion {

    /// Standard log length the per-log formulas are evaluated against.
    /// 16 ft is the conventional spec foot for all three rules.
    public static let logLengthFt: Double = 16.0

    /// Estimated board-foot volume of a tree from its DBH (cm) and
    /// total height (m), using the chosen log rule. The merchantable
    /// height is taken as `totalHeight × merchantableRatio` (default
    /// 0.6 — leaves the top 40 % out as non-merchantable taper).
    ///
    /// Returns nil for trees too small to bother with (DBH < 10 cm
    /// or merchantable height < 8 ft).
    public static func boardFeet(
        dbhCm: Double,
        totalHeightM: Double,
        rule: LogRule,
        merchantableRatio: Double = 0.6
    ) -> Double? {
        let dbhIn = dbhCm / 2.54
        let totalFt = totalHeightM * 3.28084
        let merchFt = totalFt * merchantableRatio
        guard dbhIn >= 4.0, merchFt >= 8.0 else { return nil }

        // Walk the merchantable stem in 16-ft logs and sum board
        // feet from each log. Diameter-inside-bark tapers linearly
        // from DBH to ~0.4 × DBH at the merchantable top — Form
        // Class 78 simplification.
        let nLogsExact = merchFt / logLengthFt
        let fullLogs = Int(floor(nLogsExact))
        let lastLogLen = (nLogsExact - Double(fullLogs)) * logLengthFt

        var totalBF: Double = 0
        for i in 0..<fullLogs {
            let topFractAlongMerch = Double(i + 1) / nLogsExact
            let topDiamIn = topDiameter(dbhIn: dbhIn,
                                         positionAlongStem: topFractAlongMerch)
            // Use mid-log scale rule input — closer to log scale tally
            // than top-only would be, especially on Scribner.
            let baseDiamIn = topDiameter(dbhIn: dbhIn,
                                          positionAlongStem: Double(i) / nLogsExact)
            let dIn = (baseDiamIn + topDiamIn) / 2
            totalBF += boardFeetPerLog(
                smallEndDIn: dIn, lengthFt: logLengthFt, rule: rule)
        }
        if lastLogLen >= 8.0 {
            let topDiamIn = topDiameter(dbhIn: dbhIn,
                                         positionAlongStem: 1.0)
            let baseDiamIn = topDiameter(dbhIn: dbhIn,
                                          positionAlongStem:
                                          Double(fullLogs) / nLogsExact)
            let dIn = (baseDiamIn + topDiamIn) / 2
            totalBF += boardFeetPerLog(
                smallEndDIn: dIn, lengthFt: lastLogLen, rule: rule)
        }
        return max(0, totalBF)
    }

    /// Single-log board feet under each of the three rules.
    /// All formulas accept inches (small-end diameter) + feet (length).
    public static func boardFeetPerLog(
        smallEndDIn d: Double, lengthFt L: Double, rule: LogRule
    ) -> Double {
        switch rule {
        case .scribner:
            // Scribner Decimal C polynomial approximation (USFS).
            // V = ((0.79 × D² − 2 × D − 4) / 16) × L
            return max(0, ((0.79 * d * d - 2 * d - 4) / 16.0) * L)
        case .international:
            // International ¼-Inch (1 in saw kerf) per 16-ft log:
            // V = 0.04976 × D² × L − 1.86 × D × L
            // For arbitrary L, scale linearly from the 16-ft form.
            let perFoot = (0.04976 * d * d - 1.86 * d) / 16.0
            return max(0, perFoot * L)
        case .doyle:
            // Doyle (slabs-and-edgings):
            // V = ((D − 4)² / 16) × L
            let small = max(0, d - 4)
            return (small * small / 16.0) * L
        }
    }

    /// Form-Class 78 simplified taper: the merchantable top
    /// diameter ≈ 0.4 × DBH, tapering linearly along the stem.
    /// Position 0 = base, 1 = merchantable top.
    private static func topDiameter(dbhIn: Double,
                                    positionAlongStem: Double) -> Double {
        let p = max(0.0, min(1.0, positionAlongStem))
        return dbhIn * (1.0 - 0.6 * p)
    }
}
