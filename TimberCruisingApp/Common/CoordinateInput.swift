// One rule for reading a coordinate a cruiser TYPED.
//
// The app has always been able to show where a reading was taken and never
// able to change it: a fix that never arrived, or arrived off the tree,
// stayed on the record forever. This is the parser behind the field log's
// editable Position row.
//
// IT ACCEPTS EXACTLY WHAT THE APP PRINTS — "44.56417, -123.28556", decimal
// degrees, latitude first, separated by a comma. Nothing else. A cruiser who
// types something this cannot read gets told so and the stored coordinate is
// left alone; guessing at "44 33 51 N" and storing the guess would put a
// tree somewhere nobody chose, which is worse than refusing.
//
// Ported 1:1 from / to Android `common/CoordinateInput.kt` — a cruise split
// across two phones must accept and refuse the same strings.

import Foundation

public enum CoordinateInput {

    /// What the text in the field means.
    public enum Result: Equatable {
        /// A usable coordinate.
        case coordinate(latitude: Double, longitude: Double)
        /// The field is empty — the cruiser is CLEARING the position, which
        /// is a legitimate answer ("this reading has no position") and not a
        /// failure to parse.
        case cleared
        /// Unusable. Carries the sentence to put on screen.
        case refused(String)
    }

    /// Why a coordinate was refused. Byte-identical on both platforms.
    public enum Refusal {
        public static let format =
            "Type the coordinate the way the app shows it: latitude, longitude — for example 44.56417, -123.28556."
        public static let latitudeRange = "Latitude must be between -90 and 90."
        public static let longitudeRange = "Longitude must be between -180 and 180."
    }

    /// How a stored coordinate is printed — the one format, so what the
    /// cruiser reads is exactly what this parser will take back.
    public static func text(latitude: Double, longitude: Double) -> String {
        String(format: "%.5f, %.5f", latitude, longitude)
    }

    public static func parse(_ raw: String) -> Result {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .cleared }

        // Exactly one comma. Two would mean the cruiser typed a decimal
        // comma ("44,565, -123,285"), and there is no way to tell which of
        // those commas separates the pair — so it is refused rather than
        // resolved by a coin toss.
        let parts = trimmed.split(separator: ",", omittingEmptySubsequences: false)
        guard parts.count == 2 else { return .refused(Refusal.format) }

        guard let lat = decimalDegrees(String(parts[0])),
              let lon = decimalDegrees(String(parts[1]))
        else { return .refused(Refusal.format) }

        guard lat >= -90, lat <= 90 else {
            return .refused(Refusal.latitudeRange)
        }
        guard lon >= -180, lon <= 180 else {
            return .refused(Refusal.longitudeRange)
        }
        return .coordinate(latitude: lat, longitude: lon)
    }

    /// One signed decimal number, and nothing else. Deliberately hand-rolled
    /// rather than handed to a number formatter: a locale-aware parse would
    /// read "44.5" as 445 under a decimal-comma locale, which is a 100 km
    /// error that looks like a valid coordinate.
    private static func decimalDegrees(_ raw: String) -> Double? {
        let field = raw.trimmingCharacters(in: .whitespaces)
        guard !field.isEmpty else { return nil }
        var digits = 0
        var dots = 0
        for (index, ch) in field.enumerated() {
            if ch == "-" || ch == "+" {
                guard index == 0 else { return nil }
            } else if ch == "." {
                dots += 1
                guard dots == 1 else { return nil }
            } else if ch.isASCII && ch.isNumber {
                digits += 1
            } else {
                return nil
            }
        }
        guard digits > 0 else { return nil }
        return Double(field)
    }
}
