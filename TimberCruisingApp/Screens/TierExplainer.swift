// CONFIDENCE EXPLAINER — field report F8.
//
// The tier chip on the per-tree report is tappable, and this is what it
// opens. A cruiser was being shown "Green" / "Yellow" / "Red" with no way to
// learn what moved it, so the grade read as a mood rather than a criterion.
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
                    Text("Every measurement is graded the moment it is computed. The grade travels with the record and into your exports. It is never a gate — you can keep a red reading.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Section("What the colours mean") {
                    tierRow(.green,
                            "Every check passed. Take the number as it stands.")
                    tierRow(.yellow,
                            "One check fell short. The number is usable — treat it as a little softer than a green one.")
                    tierRow(.red,
                            "A check failed outright, or two fell short. Re-measure if the tree is still in front of you; if it isn't, keep it. Red is recorded honestly, not discarded.")
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
                    Text("One caution makes it yellow. Two make it red. Anything that fails outright is red on its own.")
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
                ("Fit quality",
                 "How closely a circle matches the trunk points the scanner returned. Leftover error above 7% of the fitted radius fails; 5–7% is a caution. It is judged against the radius, not in millimetres, so a small stem is held to a tighter tolerance than a big one."),
                ("Radius precision",
                 "How repeatable that radius is. Worse than ±5% fails; ±2–5% is a caution."),
                ("Coverage",
                 "How much of the trunk's circumference the scan actually saw. Below 30° fails; 30–45° is a caution. Step around the stem a little, or stand where the whole face is in view."),
                ("Surface points",
                 "How many depth points landed on the trunk. Fewer than 10 fails; 10–20 is a caution. Move closer and fill the crosshair with bark, not gaps."),
                ("Steadiness",
                 "How much the fitted radius swings between frames of the burst. Above 10% fails; 5–10% is a caution. Brace the phone and let it settle before you capture."),
            ]
        case .height:
            return [
                ("Precision",
                 "The height's own uncertainty measured against the height itself. Worse than ±5% is a caution. It grows with a long walk-back and with a steep aim, so both of the next two feed it."),
                ("Aim angle",
                 "How steeply you sighted the treetop. Steeper than 75° above level is a caution — you are too close to the tree. Walk back until you can see the top comfortably."),
                ("Walk-back distance",
                 "How far you moved from the trunk. More than 25 m is a caution, and past 30 m tracking drift adds a second one — which on its own is enough to make the reading red."),
            ]
        }
    }

    private func tierRow(_ tier: ConfidenceTier, _ detail: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            HStack(spacing: 6) {
                Circle()
                    .fill(ConfidenceStyle.descriptor(for: tier.rawValue).color)
                    .frame(width: 10, height: 10)
                Text(tier.rawValue.capitalized)
                    .font(.subheadline.bold())
                    .foregroundStyle(ConfidenceStyle.descriptor(for: tier.rawValue).color)
            }
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 2)
    }
}
