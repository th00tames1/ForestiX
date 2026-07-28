// THE GPS chip — one definition, shown on the map home and on both
// measurement screens.
//
// FIELD REPORT 11/12. Two things came back from the stand:
//
//   • The map's chip was the readout the cruiser trusted, and it was only
//     on the map. Measuring a tree meant a different widget entirely —
//     `GPSAccuracyBadge`, a "GPS good / fair / check" pill built on
//     horizontal accuracy. Two vocabularies for one question ("can the app
//     see where I am right now?"), and the one shown at the moment a
//     position is actually STORED on a measurement was the vaguer of the
//     two. That badge is gone; this is what all three screens show.
//
//   • The stale colour read as brown. It was `confidenceWarn`, which is
//     deepened to #9A6414 so it can carry text on paper — correct for a
//     tier label, wrong for a 7 pt dot. The dots now come from the
//     dedicated `fixStale` / `fixLost` pair.
//
// WHAT THE DOT MEANS:
//
//   green   the fix is live (≤ `FixFreshness.maxUsableAge`, 5 s) — this is
//           also exactly the window in which the app will draw a position
//           and judge inside/outside, so the dot and the map agree
//   amber   stale, but there WAS a fix within the last minute — ordinary
//           under-canopy dropout, keep working
//   red     no fix at all, or nothing for over a minute — stop trusting
//           any position on screen and step out for sky
//
// The age is a MAGNITUDE (`abs`). A GPS timestamp is satellite UTC, so a
// device clock running behind it makes the plain difference negative, and
// clamping that to zero used to paint a green dot on a fix of any age
// while the map beside it drew nothing.

import SwiftUI
import Positioning

public struct GPSFixChip: View {

    /// The shared service — the same object the map, the camera seeding and
    /// the plot verdict all read, so the chip cannot disagree with them.
    @ObservedObject private var location = LocationService.shared

    /// True on the AR measurement screens: the chip is the only thing
    /// asking for location in its corner there, and holding the service
    /// alive for the length of a scan is what puts a fix on the stored
    /// reading. The map already owns an acquire for its own lifetime.
    private let acquiresService: Bool

    public init(acquiresService: Bool = false) {
        self.acquiresService = acquiresService
    }

    public var body: some View {
        // 1 s tick so the age suffix and the dot tier move while the chip is
        // on screen — a frozen "3s" on a fix that died two minutes ago is
        // the failure this whole chip exists to prevent.
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let snap = location.latestSnapshot ?? LocationService.lastGlobalFix
            let age = snap.map { abs(context.date.timeIntervalSince($0.timestamp)) }
            let state = Self.state(age: age)
            content(snap: snap, age: age, state: state)
                .lineLimit(1)
                .padding(.horizontal, 11)
                .padding(.vertical, 7)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(ForestixPalette.surface))
                .overlay(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(ForestixPalette.divider, lineWidth: 1))
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(Self.accessibilityText(snap: snap,
                                                           age: age,
                                                           state: state))
                .accessibilityIdentifier("gps.fixChip")
        }
        .onAppear {
            guard acquiresService else { return }
            location.requestAuthorization()
            location.acquire()
        }
        .onDisappear {
            guard acquiresService else { return }
            location.release()
        }
    }

    // MARK: - State

    /// What the dot says. Named states rather than a bare colour, so the
    /// spoken label and the drawn dot cannot drift apart.
    public enum FixState {
        case live
        case stale
        case lost

        var color: Color {
            switch self {
            case .live:  return ForestixPalette.confidenceOk
            case .stale: return ForestixPalette.fixStale
            case .lost:  return ForestixPalette.fixLost
            }
        }
    }

    static func state(age: TimeInterval?) -> FixState {
        guard let age, age.isFinite else { return .lost }
        if age > FixFreshness.lostAge      { return .lost }
        if age > FixFreshness.maxUsableAge { return .stale }
        return .live
    }

    // MARK: - Content

    @ViewBuilder
    private func content(snap: CLLocationSnapshot?,
                         age: TimeInterval?,
                         state: FixState) -> some View {
        if let snap {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    dot(state)
                    coordRow("X", String(format: "%.5f", snap.latitude))
                }
                coordRow("Y", String(format: "%.5f", snap.longitude))
                    .padding(.leading, 13)
                HStack(spacing: 4) {
                    coordRow("Z", snap.altitudeM.map {
                        String(format: "%.0f m", $0)
                    } ?? "—")
                    // The age only appears once it MATTERS. A live fix is
                    // the normal case and does not need a number ticking
                    // beside it; anything else does.
                    if state != .live, let age {
                        Text("· \(Self.fixAgeText(age))")
                            .font(.system(size: 11, weight: .medium,
                                          design: .monospaced))
                            .foregroundStyle(ForestixPalette.textTertiary)
                    }
                }
                .padding(.leading, 13)
            }
        } else {
            HStack(spacing: 6) {
                dot(.lost)
                Text("no fix")
                    .font(.system(size: 11, weight: .semibold,
                                  design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
        }
    }

    /// The status dot. The hairline ring is load-bearing, not decoration:
    /// amber on a white surface is a shape the eye can lose, and the ring
    /// gives it an edge in both appearances without darkening the hue back
    /// towards the brown this replaced.
    private func dot(_ state: FixState) -> some View {
        Circle()
            .fill(state.color)
            .frame(width: 7, height: 7)
            .overlay(
                Circle().stroke(ForestixPalette.textPrimary.opacity(0.25),
                                lineWidth: 0.5))
    }

    /// One labelled coordinate line — dim mono axis label, semibold mono
    /// value. X = latitude, Y = longitude, Z = altitude, the axis
    /// convention this crew asked for.
    private func coordRow(_ label: String, _ value: String) -> some View {
        HStack(spacing: 4) {
            Text(label)
                .font(.system(size: 11, weight: .medium, design: .monospaced))
                .foregroundStyle(ForestixPalette.textTertiary)
            Text(value)
                .font(.system(size: 11, weight: .semibold, design: .monospaced))
                .foregroundStyle(ForestixPalette.textPrimary)
        }
    }

    // MARK: - Text

    /// "12s" / "3m". Field-requested over the old "12 s ago": the chip is
    /// glanced at, not read, and the two words carried nothing the number
    /// beside a stale dot did not already say. Same strings on Android.
    static func fixAgeText(_ age: TimeInterval) -> String {
        age < 60 ? "\(Int(age))s" : "\(Int(age / 60))m"
    }

    /// VoiceOver gets the long form — it is heard once, not glanced at, so
    /// the abbreviation that helps the eye would only cost the ear.
    static func accessibilityText(snap: CLLocationSnapshot?,
                                  age: TimeInterval?,
                                  state: FixState) -> String {
        guard let snap else { return "No GPS fix" }
        var out = String(format: "GPS fix %.5f, %.5f",
                         snap.latitude, snap.longitude)
        if let alt = snap.altitudeM {
            out += String(format: ", altitude %.0f metres", alt)
        }
        if state != .live, let age {
            out += age < 60
                ? ", \(Int(age)) seconds old"
                : ", \(Int(age / 60)) minutes old"
        }
        return out
    }
}
