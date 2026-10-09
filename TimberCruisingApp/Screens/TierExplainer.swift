// CONFIDENCE EXPLAINER — field report F8, corrected by field report item 6.
//
// The confidence chip is tappable, and this is what it opens. A cruiser was
// being shown a bare grade with no way to learn what moved it, so the grade
// read as a mood rather than a criterion. There is ONE set of words on every
// cruiser surface, taken from `ConfidenceStyle.descriptor`: Good, Fair,
// Check. The stored enum stays green / yellow / red and every export is
// untouched — the enum simply never reaches a cruiser's eyes.
//
// EVERY NUMBER ON THIS SHEET IS READ FROM THE THRESHOLD THE CODE APPLIES.
// `DBHEstimator.TierThresholds` and `HeightEstimator`'s constants are
// formatted into the sentences below at render time. Prose copies of those
// numbers are what this sheet used to carry, and a prose copy is a promise
// to update two things whenever one of them moves.
//
// WHAT THE PROSE USED TO SAY, AND WHY IT WAS WRONG. The diameter half of
// this sheet listed five drivers — shape match, radius precision, arc
// coverage, surface points, per-frame spread — all of them taken from
// `DBHEstimator.estimate`, the §7.1 partial-arc circle fit. That is NOT the
// method the app captures with. Since the edge-bracket (Adjust) became the
// default path, a diameter is graded by `bracketChordEstimate`, which
// applies exactly one rule: frame-to-frame agreement of the burst's
// diameters against `frameSpreadGreen`. None of those five checks run on it,
// and it sets arc coverage, RMSE and sigma to zero. A cruiser reading the
// old sheet was being taught the criteria of a path their capture had not
// taken. The frame-agreement rule now leads, and the circle-fit criteria are
// kept in their own section that says when they apply.
//
// The height half was and remains accurate: HeightEstimator's §7.9 matrix
// (sigma_H/H, walk-back, top aim angle) grades every height, and every
// threshold quoted comes from the constants it checks against.
//
// Wording is shared verbatim with the Android sibling
// (ui/screens/tree/TierExplainer.kt).

import Foundation   // String(format:) — the threshold formatters below
import SwiftUI
import Common
import Sensors

struct TierExplainer: View {

    enum Kind: String, Identifiable {
        case diameter
        case height
        var id: String { rawValue }

        var title: String {
            switch self {
            case .diameter: return "Diameter confidence"
            case .height:   return "Height confidence"
            }
        }
    }

    let kind: Kind

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("Every measurement is graded the moment it is computed. The grade travels with the record and into your exports. It is never a gate — you can keep a Check reading.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                // ONE vocabulary. The chip says Good / Fair / Check
                // (ConfidenceStyle.descriptor), so the sheet that exists to
                // explain that chip says Good / Fair / Check too — in its
                // heading, in each row's word, and in the prose underneath.
                // The colour is still carried by the dot; it is no longer a
                // second set of names for the same three grades.
                Section("What the grades mean") {
                    tierRow(.green,
                            "Every check passed. Take the number as it stands.")
                    tierRow(.yellow,
                            "One check fell short. The number is usable — treat it as a little softer than a Good one.")
                    tierRow(.red,
                            "A check failed outright, or two fell short. Re-measure if the tree is still in front of you; if it isn't, keep it. A Check reading is recorded honestly, not discarded.")
                }

                Section("What moves it") {
                    ForEach(drivers, id: \.0) { title, detail in
                        driverRow(title, detail)
                    }
                }

                // Diameter only, and only because the app has two grading
                // paths for it. Naming the section after the method keeps
                // the sheet from implying these checks ran on a capture that
                // never went near them.
                if !secondaryDrivers.isEmpty {
                    Section("If the circle fit was used") {
                        Text("The circle fit is not the capture the app takes by default. When it is the method that measured a stem, these are the checks it applies.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                        ForEach(secondaryDrivers, id: \.0) { title, detail in
                            driverRow(title, detail)
                        }
                    }
                }

                Section {
                    Text(combineRuleText)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .navigationTitle(kind.title)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .accessibilityIdentifier("tierExplainer.\(kind.rawValue)")
    }

    // MARK: - Copy, built from the shipped thresholds

    /// The rule that actually grades what the cruiser tapped.
    private var drivers: [(String, String)] {
        switch kind {
        case .diameter: return TierExplainerCopy.diameterDrivers
        case .height:   return TierExplainerCopy.heightDrivers
        }
    }

    /// The other diameter path's criteria. Empty for height, which has only
    /// one grading path.
    private var secondaryDrivers: [(String, String)] {
        switch kind {
        case .diameter: return TierExplainerCopy.circleFitDrivers
        case .height:   return []
        }
    }

    private var combineRuleText: String {
        switch kind {
        case .diameter: return TierExplainerCopy.diameterCombineRule
        case .height:   return TierExplainerCopy.heightCombineRule
        }
    }

    private func driverRow(_ title: String, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline.bold())
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }

    private func tierRow(_ tier: ConfidenceTier, _ detail: String) -> some View {
        // The row's WORD comes from the same descriptor the chip uses, so the
        // sheet can never drift back into naming the stored enum.
        let descriptor = ConfidenceStyle.descriptor(for: tier.rawValue)
        return VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Circle()
                    .fill(descriptor.color)
                    .frame(width: 10, height: 10)
                Text(descriptor.label)
                    .font(.subheadline.bold())
                    .foregroundStyle(descriptor.color)
            }
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }
}

/// Every sentence on the explainer, assembled from the constants the
/// estimators check against.
///
/// Split out of the view, and built step by step rather than as one big
/// interpolated literal, for the type-checker's sake: a single expression
/// concatenating this many interpolated segments is what times Swift out.
enum TierExplainerCopy {

    // MARK: Numbers

    /// "7%" — a fraction threshold as the cruiser reads it. Whole percents
    /// print without a decimal point; anything else keeps one, so a
    /// threshold moved to 0.075 would render "7.5%" rather than silently
    /// rounding to the old number.
    static func percent(_ fraction: Double) -> String {
        // Quantise to 0.1% BEFORE deciding whether this is a whole percent:
        // 0.07 * 100 is 7.000000000000001 in binary floating point, and a
        // bare equality test against the rounded value renders the shipped
        // 7% threshold as "7.0%".
        let v = (fraction * 1000).rounded() / 10
        if abs(v - v.rounded()) < 0.0001 { return String(Int(v.rounded())) + "%" }
        return String(format: "%.1f", v) + "%"
    }

    /// "30°" from a threshold in degrees.
    static func degrees(_ value: Double) -> String {
        let v = (value * 10).rounded() / 10
        if abs(v - v.rounded()) < 0.0001 { return String(Int(v.rounded())) + "°" }
        return String(format: "%.1f", v) + "°"
    }

    /// "25 m" from a threshold in metres. Always metric: these are the
    /// estimator's own gates, not a measured value, and a cruiser working in
    /// feet still needs to recognise the number the code tests.
    static func metres(_ value: Float) -> String {
        let v = (Double(value) * 10).rounded() / 10
        if abs(v - v.rounded()) < 0.0001 { return String(Int(v.rounded())) + " m" }
        return String(format: "%.1f", v) + " m"
    }

    // MARK: Diameter — the default capture

    static var diameterDrivers: [(String, String)] {
        let spread = percent(DBHEstimator.TierThresholds.frameSpreadGreen)
        let frames = String(DBHEstimator.TierThresholds.minUsableFrames)
        var d = "How closely the frames of one burst agreed on the width. "
        d += "A spread up to " + spread + " of their average is Good; wider than that is Fair. "
        d += "Fewer than " + frames + " usable frames is Check. "
        d += "On an Adjust capture — the one the app takes by default — this is the whole grade, "
        d += "and none of the circle-fit checks below are applied to it. "
        d += "Brace the phone and let it settle before you capture."
        return [("Frame agreement", d)]
    }

    static var diameterCombineRule: String {
        var s = "A default Adjust capture is graded on frame agreement alone. "
        s += "Where the circle fit measured the stem instead, one caution makes it Fair, "
        s += "two make it Check, and anything that fails outright is Check on its own."
        return s
    }

    // MARK: Diameter — the circle-fit path

    static var circleFitDrivers: [(String, String)] {
        var rows: [(String, String)] = []

        var shape = "How closely a round trunk matches the points the scanner returned. "
        shape += "Left-over error above " + percent(DBHEstimator.TierThresholds.rmseOverRadiusReject) + " of the trunk's radius fails; "
        shape += percent(DBHEstimator.TierThresholds.rmseOverRadiusWarn) + "–" + percent(DBHEstimator.TierThresholds.rmseOverRadiusReject) + " is a caution. "
        shape += "It is judged against the size of the stem, not in millimetres, so a small stem is held to a tighter tolerance than a big one."
        rows.append(("Shape match", shape))

        var precision = "How repeatable that radius is. "
        precision += "Worse than ±" + percent(DBHEstimator.TierThresholds.sigmaOverRadiusReject) + " of it fails; "
        precision += "±" + percent(DBHEstimator.TierThresholds.sigmaOverRadiusWarn) + "–" + percent(DBHEstimator.TierThresholds.sigmaOverRadiusReject) + " is a caution."
        rows.append(("How much it could be out", precision))

        var coverage = "How much of the trunk's circumference the scan actually saw. "
        coverage += "Below " + degrees(DBHEstimator.TierThresholds.minArcDegReject) + " fails; "
        coverage += degrees(DBHEstimator.TierThresholds.minArcDegReject) + "–" + degrees(DBHEstimator.TierThresholds.minArcDegWarn) + " is a caution. "
        coverage += "Step around the stem a little, or stand where the whole face is in view."
        rows.append(("Coverage", coverage))

        var points = "How many depth points landed on the trunk. "
        points += "Fewer than " + String(DBHEstimator.TierThresholds.minInliersReject) + " fails; "
        points += String(DBHEstimator.TierThresholds.minInliersReject) + "–" + String(DBHEstimator.TierThresholds.minInliersWarn) + " is a caution. "
        points += "Move closer and fill the crosshair with bark, not gaps."
        rows.append(("Surface points", points))

        var steady = "How much the width wanders from shot to shot while the phone is capturing. "
        steady += "Above " + percent(DBHEstimator.TierThresholds.radiusCoVReject) + " fails; "
        steady += percent(DBHEstimator.TierThresholds.radiusCoVWarn) + "–" + percent(DBHEstimator.TierThresholds.radiusCoVReject) + " is a caution. "
        steady += "Brace the phone and let it settle before you capture."
        rows.append(("Steadiness", steady))

        return rows
    }

    // MARK: Height

    static var heightDrivers: [(String, String)] {
        var rows: [(String, String)] = []

        var sigma = "How far the height could be off, set against the height itself. "
        sigma += "Worse than ±" + percent(Double(HeightEstimator.sigmaRatioYellow)) + " is a caution. "
        sigma += "It grows with a long walk-back and with a steep aim, so both of the next two feed it."
        rows.append(("How much it could be out", sigma))

        let topDeg = Double(HeightEstimator.maxAlphaTopRadYellow) * 180 / Double.pi
        var aim = "How steeply you sighted the treetop. "
        aim += "Steeper than " + degrees(topDeg.rounded()) + " above level is a caution — you are too close to the tree. "
        aim += "Walk back until you can see the top comfortably."
        rows.append(("Aim angle", aim))

        var walk = "How far you moved from the trunk. "
        walk += "More than " + metres(HeightEstimator.yellowDhMeters) + " is a caution, and past "
        walk += metres(HeightEstimator.highDriftDhMeters)
        walk += " the phone is no longer sure how far you actually walked, which adds a second one — "
        walk += "enough on its own to make the reading Check."
        rows.append(("Walk-back distance", walk))

        return rows
    }

    static var heightCombineRule: String {
        "One caution makes it Fair. Two make it Check. Anything that fails outright is Check on its own."
    }
}
