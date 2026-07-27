// CONFIDENCE EXPLAINER — field report F8.
//
// The confidence chip on the per-tree report is tappable, and this is what it
// opens. A cruiser was being shown "Green" / "Yellow" / "Red" with no way to
// learn what moved it, so the grade read as a mood rather than a criterion —
// and worse, the sheet named the three grades differently from the chip that
// opened it. There is now ONE set of words on every cruiser surface, taken
// from `ConfidenceStyle.descriptor`: Good, Fair, Check.
//
// The copy below is written from the ACTUAL checks, so it stays true:
//   • DBH  — DBHEstimator's §7.1 sanity tree (inlier count, arc coverage,
//            fitted-radius sanity, RMSE/r, σ_r/r, per-frame radius spread).
//   • Height — HeightEstimator's §7.9 matrix (σ_H/H ≤ 5 %, walk-back ≤ 25 m
//            and ≤ 30 m, top aim angle ≤ 75°).
// And from `combineChecks`: any hard failure ⇒ red; two cautions ⇒ red; one
// caution ⇒ yellow; none ⇒ green.
//
// Wording is shared verbatim with the Android sibling.

import SwiftUI
import Common

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

                // ONE vocabulary. The chip on the tree report says Good /
                // Fair / Check (ConfidenceStyle.descriptor), so the sheet
                // that exists to explain that chip says Good / Fair / Check
                // too — in its heading, in each row's word, and in the prose
                // underneath. The colour is still carried by the dot; it is
                // no longer a second set of names for the same three grades.
                // The STORED enum (green / yellow / red) and every export are
                // untouched — this is what the cruiser reads, nothing else.
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
                        VStack(alignment: .leading, spacing: 3) {
                            Text(title).font(.subheadline.bold())
                            Text(detail)
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .padding(.vertical, 2)
                    }
                }

                Section {
                    Text("One caution makes it Fair. Two make it Check. Anything that fails outright is Check on its own.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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

    private var drivers: [(String, String)] {
        switch kind {
        case .diameter:
            return [
                // "Fit quality" / "the fitted radius" were the estimator's
                // own words for its circle fit — the last of that vocabulary
                // left on a cruiser surface. The rows now say what the
                // cruiser is looking at; every THRESHOLD quoted is still the
                // estimator's real one.
                ("Shape match",
                 "How closely a round trunk matches the points the scanner returned. Left-over error above 7% of the trunk's radius fails; 5–7% is a caution. It is judged against the size of the stem, not in millimetres, so a small stem is held to a tighter tolerance than a big one."),
                // "Precision" was a statistics heading on a teaching sheet.
                // The heading now says what the row is about; the
                // THRESHOLDS quoted are still the estimator's real ones.
                ("How much it could be out",
                 "How repeatable that radius is. Worse than ±5% of it fails; ±2–5% is a caution."),
                ("Coverage",
                 "How much of the trunk's circumference the scan actually saw. Below 30° fails; 30–45° is a caution. Step around the stem a little, or stand where the whole face is in view."),
                ("Surface points",
                 "How many depth points landed on the trunk. Fewer than 10 fails; 10–20 is a caution. Move closer and fill the crosshair with bark, not gaps."),
                ("Steadiness",
                 "How much the width wanders from shot to shot while the phone is capturing. Above 10% fails; 5–10% is a caution. Brace the phone and let it settle before you capture."),
            ]
        case .height:
            return [
                ("How much it could be out",
                 "How far the height could be off, set against the height itself. Worse than ±5% is a caution. It grows with a long walk-back and with a steep aim, so both of the next two feed it."),
                ("Aim angle",
                 "How steeply you sighted the treetop. Steeper than 75° above level is a caution — you are too close to the tree. Walk back until you can see the top comfortably."),
                // "tracking drift" is the AR-internals phrase, and "the
                // distance may have crept" was only half a step away from
                // it — the sheet that TEACHES the checks can't be the last
                // place either survives. It now says what goes wrong in the
                // cruiser's terms. Both DISTANCES are the estimator's real
                // ones.
                ("Walk-back distance",
                 "How far you moved from the trunk. More than 25 m is a caution, and past 30 m the phone is no longer sure how far you actually walked, which adds a second one — enough on its own to make the reading Check."),
            ]
        }
    }

    private func tierRow(_ tier: ConfidenceTier, _ detail: String) -> some View {
        // The row's WORD comes from the same descriptor the chip uses, so the
        // sheet can never drift back into naming the stored enum. Rendering
        // `tier.rawValue.capitalized` here is what put "Green / Yellow / Red"
        // in front of a cruiser whose chip said "Good / Fair / Check".
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
