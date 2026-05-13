// Three-tier GPS accuracy badge for the AR scan screens.
//
// Renders a compact pill on the scan camera overlay so the cruiser
// can tell at a glance whether they're standing under a clean sky
// or under canopy. Three tiers map onto the existing confidence
// palette (Good / Fair / Check) so the colour vocabulary across
// the app stays unified:
//
//   • ≤ 5 m   → "GPS good"   (ForestixPalette.confidenceOk)
//   • 5–15 m  → "GPS fair"   (ForestixPalette.confidenceWarn)
//   • > 15 m / unknown → "GPS check" (ForestixPalette.confidenceBad)
//
// The badge owns a local `LocationService` so it works without any
// AppEnvironment plumbing — it lights up regardless of which scan
// screen hosts it. On macOS / non-CoreLocation hosts the service is
// a no-op stub, so the badge silently falls back to "check".

import SwiftUI
import Positioning

public struct GPSAccuracyBadge: View {

    @StateObject private var location = LocationService()

    public init() {}

    public var body: some View {
        let tier = currentTier
        return HStack(spacing: 6) {
            Circle()
                .fill(tier.color)
                .frame(width: 8, height: 8)
                .overlay(
                    Circle().stroke(Color.black.opacity(0.4), lineWidth: 0.5))
            Text(tier.label)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.55))
        .overlay(
            Capsule().stroke(Color.white.opacity(0.20), lineWidth: 0.5))
        .clipShape(Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("GPS \(tier.label.lowercased())")
        .onAppear {
            location.requestAuthorization()
            location.start()
        }
        .onDisappear { location.stop() }
    }

    // MARK: - Tier classification

    private struct Tier {
        let label: String
        let color: Color
    }

    private var currentTier: Tier {
        // The CLLocation guard: a negative horizontalAccuracy means
        // CoreLocation couldn't compute one — treat as "no fix".
        guard let snap = location.latestSnapshot,
              snap.horizontalAccuracyM > 0 else {
            return Tier(label: "GPS check",
                        color: ForestixPalette.confidenceBad)
        }
        let acc = snap.horizontalAccuracyM
        if acc <= 5 {
            return Tier(label: "GPS good",
                        color: ForestixPalette.confidenceOk)
        } else if acc <= 15 {
            return Tier(label: "GPS fair",
                        color: ForestixPalette.confidenceWarn)
        } else {
            return Tier(label: "GPS check",
                        color: ForestixPalette.confidenceBad)
        }
    }
}
