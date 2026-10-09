// THE DISTANCES INSIDE INSTRUCTIONS — "stand within 4 m / 13 ft of the
// trunk", "0.5–3 m / 2–10 ft from the trunk", "you walked back more than
// 30 m / 100 ft".
//
// A failed scan's reason is the only thing that tells a cruiser what to do
// differently, and one worded in a unit they do not pace in is not an
// instruction. Those sentences are built where the check fails — inside the
// estimators, in Sensors — so the rule lives HERE, in Models, for the same
// reason `UncertaintyBand` does: the estimators and the screens have to say
// one thing, and Models is the lowest layer both can see. `MeasurementFormatter`
// in the UI layer forwards to this rather than keeping a second copy.
//
// TWO RULES HOLD EVERYWHERE THIS IS USED:
//
//   1. THE GATE DOES NOT MOVE. Every limit quoted through here is physical —
//      the depth camera's usable range, the geometry of a tangent height — and
//      the check still applies it in metres, unchanged. A cruiser flipping a
//      display toggle must never change which captures the app accepts, which
//      it grades yellow, or what σ it stores. This formats the sentence and
//      nothing else.
//   2. IT READS LIKE ADVICE, NOT LIKE A CONVERSION. Whole feet close in, the
//      nearest five further out, so 30 m becomes "100 ft" rather than "98 ft"
//      and 25 m becomes "80 ft" rather than "82 ft". Standing at 98 ft where
//      the sentence says 100 is inside a gate that was never sharp; a number
//      with a stray digit on it is one a cruiser stops reading.
//
// Byte-identical to the Android sibling (common/MeasurementFormatter.kt's
// `guidanceDistance` / `guidanceRange`).

import Foundation
import Common

public enum GuidanceDistance {

    /// One distance in an instruction: "4 m" / "13 ft".
    public static func text(metres: Double, in system: UnitSystem) -> String {
        "\(number(metres, in: system)) \(system == .metric ? "m" : "ft")"
    }

    /// A range in an instruction: "0.5–3 m" / "2–10 ft". ONE unit suffix on
    /// the pair, because it is one instruction, not two.
    public static func range(fromMetres: Double, toMetres: Double,
                             in system: UnitSystem) -> String {
        "\(number(fromMetres, in: system))–\(text(metres: toMetres, in: system))"
    }

    private static func number(_ metres: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            // A tenth only where there is one — "3 m", not "3.0 m".
            return metres == metres.rounded()
                ? String(format: "%.0f", metres)
                : String(format: "%.1f", metres)
        case .imperial:
            let feet = Units.metersToFeet(metres)
            let rounded = feet < 20 ? feet.rounded() : (feet / 5).rounded() * 5
            return String(format: "%.0f", rounded)
        }
    }
}
