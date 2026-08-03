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
import Common
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

    /// The same number `diameter(cm:in:)` prints, WITHOUT the suffix — for the
    /// stat cells and table columns that set the value and its unit in two
    /// different type styles. It exists so those call sites do not each write
    /// their own ÷ 2.54; one of them forgetting is a tenth-of-an-inch stand
    /// reported as a centimetre one.
    public static func diameterValue(cm: Double, in system: UnitSystem) -> Double {
        system == .metric ? cm : Units.cmToInches(cm)
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
    ///   • metric  → "28.2 m"
    ///   • imperial → "92.7 ft"
    ///
    /// ONE decimal. Two decimals were tried and taken back out: a tangent
    /// height is a difference of two sighted angles, and the σ it carries is
    /// decimetres at cruising range, so a centimetre digit was a precision
    /// the measurement does not have. The ± band beside it (`heightSigma`)
    /// is what says how much of the first decimal to believe. Android prints
    /// the identical string.
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

    /// A distance the cruiser is about to WALK, at pacing precision: whole
    /// metres or whole feet until the number gets long, then kilometres or
    /// miles.
    ///
    /// Deliberately NOT `distance(m:in:)`. That one renders a MEASUREMENT —
    /// centimetres under a metre, two decimals above — and a walking
    /// instruction has neither that precision nor any use for it; "142.37 m to
    /// the plot" is three digits of noise on a number the cruiser will pace to
    /// within a few strides.
    ///
    /// The rollovers are round in each system rather than converted from one
    /// another: metric turns over just under the kilometre (so the longest
    /// metre reading is "994 m" and never "1000 m"), imperial at the mile. A
    /// US cruiser reads "3200 ft" without hesitating — feet are the pacing
    /// unit — so breaking earlier would trade a readable number for an
    /// unreadable "0.61 mi".
    public static func navDistance(m: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return m < 995
                ? String(format: "%.0f m", m)
                : String(format: "%.1f km", m / 1000)
        case .imperial:
            let feet = Units.metersToFeet(m)
            return feet < 5280
                ? String(format: "%.0f ft", feet)
                : String(format: "%.1f mi", feet / 5280)
        }
    }

    // MARK: - Guidance distances (the sentences that say where to stand)

    /// A distance quoted inside an INSTRUCTION — "stand within 4 m / 13 ft of
    /// the trunk", "you walked back more than 30 m / 100 ft".
    ///
    /// Delegates to `GuidanceDistance`, the one place the rounding rule lives,
    /// shared with the estimators — the same arrangement `heightSigma` has with
    /// `UncertaintyBand`, and for the same reason: the sentence a screen writes
    /// and the sentence a failed scan returns have to quote one number.
    ///
    /// THE GATE BEHIND THE SENTENCE DOES NOT MOVE. See the note on that type.
    public static func guidanceDistance(m: Double, in system: UnitSystem) -> String {
        GuidanceDistance.text(metres: m, in: system)
    }

    /// The same, for a RANGE — "0.5–3 m" / "2–10 ft".
    public static func guidanceRange(fromM: Double, toM: Double,
                                     in system: UnitSystem) -> String {
        GuidanceDistance.range(fromMetres: fromM, toMetres: toM, in: system)
    }

    // MARK: - Ground elevation

    /// A stored elevation (metres) in the unit the cruiser reads — feet in
    /// imperial, metres in metric.
    ///
    /// Elevation is the only length in this app the cruiser TYPES rather than
    /// measures, so its field needs both directions of the conversion, not
    /// just a rendered string; `elevationMetres` is the way back. Keeping the
    /// pair here keeps the ft/m factor in the one file that owns it — the
    /// alternative was a fourth hand-written 3.28084 out in a screen.
    ///
    /// Elevation renders and is typed in WHOLE units. The number comes off a
    /// map, an altimeter or a phone's barometric fix, none of which are good
    /// to a decimetre, and a cruiser typing "412" should read back "412".
    public static func elevationDisplay(m: Double, in system: UnitSystem) -> Double {
        system == .metric ? m : m * 3.28084
    }

    /// The inverse of `elevationDisplay` — what a typed elevation stores as.
    public static func elevationMetres(display: Double, in system: UnitSystem) -> Double {
        system == .metric ? display : display / 3.28084
    }

    // MARK: - Editable fields

    /// The text an EDITABLE numeric field is PREFILLED with, for a value that
    /// came out of storage: the bare number in the unit it is stored in, with
    /// no unit suffix (the row carries that), rounded exactly the way the
    /// read-only surface for that quantity rounds it — one decimal for a
    /// diameter or a height, two for a distance. A form that shows a different
    /// number from the field log is two numbers for one tree.
    ///
    /// A ROUNDED prefill is only safe in a field whose screen refuses to write
    /// it back unedited. `18.27` prefills as "18.3", and saving that text over
    /// the stored value is a silent re-measurement of the tree — the cruiser
    /// opened a form and lost 3 cm. Every caller therefore compares the field's
    /// current text against this prefill and leaves the stored value alone when
    /// they are equal; see `TreeDetailScreen` and the map peek's edit sheet.
    /// Any new caller owes the same guard.
    ///
    /// `String(format:)` rounds half-to-EVEN on the value's exact binary
    /// expansion. The Android sibling reproduces that with BigDecimal
    /// HALF_EVEN rather than its own `String.format`, which rounds half-UP and
    /// printed "42.3" for a stored 42.25 where this prints "42.2".
    public static func entryText(_ value: Double, fractionDigits: Int) -> String {
        String(format: "%.\(fractionDigits)f", value)
    }

    /// Returns the unit suffix only — for table columns that already
    /// formatted the number themselves (legacy code paths).
    public static func diameterUnit(_ system: UnitSystem) -> String {
        system == .metric ? "cm" : "in"
    }

    public static func heightUnit(_ system: UnitSystem) -> String {
        system == .metric ? "m" : "ft"
    }

    // MARK: - Basal-area factor (prism)

    /// The unit a BAF is WRITTEN in for this cruiser: "ft²/ac" in the US
    /// convention, "m²/ha" in the metric one. A BAF box with no unit on it is
    /// how a metric cruiser types 4 into a field the engine reads as 20.
    public static func bafUnit(_ system: UnitSystem) -> String {
        system == .metric ? "m²/ha" : "ft²/ac"
    }

    /// A stored BAF (always ft²/ac — see `CruiseDesign.baf`) expressed in the
    /// unit this cruiser types in, and the way back. The pair exists for the
    /// same reason `elevationDisplay`/`elevationMetres` does: a BAF is TYPED,
    /// so the field needs both directions, and both must use one constant.
    /// The arithmetic itself stays in `Units` — a BAF is a basal area per unit
    /// land area and converts exactly like one.
    public static func bafDisplay(stored: Double, in system: UnitSystem) -> Double {
        system == .metric ? Units.baPerAcreToBaPerHa(stored) : stored
    }

    /// The inverse of `bafDisplay` — what a typed BAF stores as (ft²/ac).
    public static func bafStored(display: Double, in system: UnitSystem) -> Double {
        system == .metric ? Units.baPerHaToBaPerAcre(display) : display
    }

    /// The BAF a fresh design starts from: the US convention's 20 ft²/ac, or
    /// the metric convention's round 4 m²/ha (≈17 ft²/ac — the nearest whole
    /// prism a metric cruiser actually carries, not a conversion of 20).
    public static func defaultBAFStored(_ system: UnitSystem) -> Double {
        system == .metric ? Units.baPerHaToBaPerAcre(4) : 20
    }

    // MARK: - Basal area per unit land area

    /// A stand's basal area per unit land area, in the numerator the cruiser's
    /// basis is written with.
    ///
    /// The inventory engine reports basal area as SQUARE METRES PER ACRE — the
    /// acre is the canonical stored basis, the square metre is the storage
    /// rule. Screens used to convert only the DENOMINATOR (× the per-hectare
    /// factor) and print the numerator as a literal "m²", so an imperial cruise
    /// read "11.5 m²/ac": a real number in a unit no cruise sheet uses, and
    /// 10.76× away from the ft²/ac the SAME app prints for the SAME stand on
    /// the quick-measure card. Both halves of the fraction convert here, or
    /// neither is right.
    public static func basalAreaDensity(m2PerAcre: Double, in unit: AreaUnit) -> Double {
        m2PerAcre * basalAreaDensityFactor(unit)
    }

    /// The same conversion as a bare multiplier, for the confidence-interval
    /// rows: a mean and its half-width have to be scaled by ONE number or the
    /// band stops belonging to the value it brackets.
    public static func basalAreaDensityFactor(_ unit: AreaUnit) -> Double {
        unit == .hectare
            ? Units.acresPerHectare
            : Units.squareMetersToSquareFeet(1.0)
    }

    /// The numerator alone — "m²" or "ft²" — for rows that already carry the
    /// "/ha" / "/ac" suffix in their heading.
    public static func basalAreaNumeratorUnit(_ unit: AreaUnit) -> String {
        unit == .hectare ? "m²" : "ft²"
    }

    /// The whole label: "m²/ha" or "ft²/ac".
    public static func basalAreaDensityUnit(_ unit: AreaUnit) -> String {
        unit.densityLabel(basalAreaNumeratorUnit(unit))
    }

    // MARK: - Plot geometry (the sampling ring itself)

    /// The AREA INSIDE ONE PLOT, in the unit that convention sizes plots with:
    /// square metres for a metric cruiser (a "400 m² plot"), acres for a US one
    /// (a "1/10-acre plot").
    ///
    /// This is the one area readout that does NOT go through `AreaUnit`. That
    /// type governs the basis a per-area DENSITY rests on, and putting a plot
    /// through it gives a metric cruiser "0.0402 ha" — three leading zeros for
    /// a number they already know as 402 m². Stands are drawn in hectares and
    /// acres (`BoundaryDrawScreen`); plots are counted in square metres and
    /// acres. Different quantity, different convention.
    public static func plotArea(m2: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "%.1f m²", m2)
        case .imperial:
            return String(format: "%.3f ac", Units.squareMetersToAcres(m2))
        }
    }

    /// The bounds of the AR plot-radius slider, in the unit the cruiser drags
    /// in, and the step it lands on.
    ///
    /// The imperial range is round FEET, not a conversion of the metric one. A
    /// slider stepped in half METRES cannot be stopped on a round foot at all,
    /// so a US cruiser laying the 37.2 ft radius that makes a tenth of an acre
    /// had to settle for whatever half-metre was nearest. Half a foot is the
    /// same "half a unit" granularity the metric slider has always had, and it
    /// reaches every plot size in the US table. The two ranges cover the same
    /// ground — 30 m is 98 ft — so neither system inherits the other's
    /// fractions.
    public static func plotRadiusSliderRange(_ system: UnitSystem) -> ClosedRange<Double> {
        system == .metric ? 1.0...30.0 : 3.0...100.0
    }

    public static func plotRadiusSliderStep(_ system: UnitSystem) -> Double {
        system == .metric ? 0.5 : 0.5
    }

    /// A stored plot radius (metres) in the unit the slider drags in, and the
    /// way back — the same bidirectional pair `elevationDisplay`/
    /// `elevationMetres` exists for, and for the same reason: the slider WRITES
    /// as well as reads, so a one-way format would store feet as metres.
    public static func plotRadiusDisplay(m: Double, in system: UnitSystem) -> Double {
        system == .metric ? m : Units.metersToFeet(m)
    }

    /// The inverse of `plotRadiusDisplay` — what a dragged radius stores as.
    public static func plotRadiusMetres(display: Double, in system: UnitSystem) -> Double {
        system == .metric ? display : Units.feetToMeters(display)
    }

    /// A plot length for a cruiser to read at arm's length: one decimal, the
    /// active unit ("7.2 m" / "23.6 ft").
    ///
    /// Coarser than `distance(m:in:)` on purpose. A plot radius is a number
    /// the cruiser CHOSE, not one the phone read, so it has no centimetre to
    /// report and the tape-measure switch below a metre would be nonsense on
    /// it. `plotLengthLabel` in the map layer is this function — one rounding
    /// rule, so the banner, the mini-map, the AR slider and the peek cannot
    /// print one ring three ways.
    public static func plotLength(m: Double, in system: UnitSystem) -> String {
        String(format: "%.1f %@",
               plotRadiusDisplay(m: m, in: system),
               system == .metric ? "m" : "ft")
    }

    /// A recorded sampling ring the way the quick-measure list states it:
    /// the radius the cruiser set and the ground it encloses.
    public static func samplingPlotSummary(radiusM: Double,
                                           areaM2: Double,
                                           in system: UnitSystem) -> String {
        "\(plotLength(m: radiusM, in: system)) radius · \(plotArea(m2: areaM2, in: system))"
    }

    // MARK: - Stand-area radius (the circular stratum)

    /// A circular AREA's radius in the unit the cruiser draws in, and the
    /// way back — the same bidirectional pair `plotRadiusDisplay`/
    /// `plotRadiusMetres` and `elevationDisplay`/`elevationMetres` exist
    /// for, and for the same reason: the field WRITES as well as reads, so
    /// a one-way format would store feet as metres.
    ///
    /// Its own pair rather than the plot one, because a plot radius and a
    /// stand radius are different quantities that happen to share a
    /// conversion: the plot pair is tied to the AR slider's range and step
    /// (1–30 m / 3–100 ft), and a stratum is drawn at hundreds of metres.
    /// Sharing them would put a stand's radius under a slider's bounds the
    /// first time someone tidied the two together.
    public static func areaRadiusDisplay(m: Double, in system: UnitSystem) -> Double {
        system == .metric ? m : Units.metersToFeet(m)
    }

    /// The inverse of `areaRadiusDisplay` — what a typed radius stores as.
    public static func areaRadiusMetres(display: Double, in system: UnitSystem) -> Double {
        system == .metric ? display : Units.feetToMeters(display)
    }

    /// The read-only echo beside the radius field: one decimal in the
    /// cruiser's unit ("50.0 m" / "164.0 ft"). One decimal because this is a
    /// number the cruiser CHOSE, not one the phone read — the same rounding
    /// rule, and the same reason, as `plotLength`.
    public static func areaRadius(m: Double, in system: UnitSystem) -> String {
        String(format: "%.1f %@",
               areaRadiusDisplay(m: m, in: system),
               system == .metric ? "m" : "ft")
    }

    /// The unit suffix the radius field is labelled with.
    public static func areaRadiusUnit(_ system: UnitSystem) -> String {
        system == .metric ? "m" : "ft"
    }

    // MARK: - Crown

    /// A crown's two spans, stored in metres, as one reading.
    ///
    /// ONE unit suffix for the pair: a crown is a single measurement read on
    /// two axes, and "4.2 m × 5.1 m" reads as two separate measurements of two
    /// separate things.
    /// ONE crown span on its own, for the scan screen's "wide · tall" line.
    /// Two decimals where `crownSpread` gives one: this is read at the moment
    /// of measurement, while the cruiser is still checking that the tap landed
    /// on the canopy edge, and the second decimal is what says it moved.
    public static func crownSpan(m: Double, in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "%.2f m", m)
        case .imperial:
            return String(format: "%.2f ft", Units.metersToFeet(m))
        }
    }

    public static func crownSpread(_ aM: Double, _ bM: Double,
                                   in system: UnitSystem) -> String {
        switch system {
        case .metric:
            return String(format: "%.1f × %.1f m", aM, bM)
        case .imperial:
            return String(format: "%.1f × %.1f ft",
                          Units.metersToFeet(aM), Units.metersToFeet(bM))
        }
    }
}
