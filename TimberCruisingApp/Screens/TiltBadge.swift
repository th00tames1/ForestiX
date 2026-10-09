// Live device-tilt badge for the Diameter scan screen.
//
// The single biggest source of DBH bias is a leaning phone — a chord
// projected through a non-vertical slice of the cylinder reads a
// systematically wrong diameter. Showing the cruiser the live pitch
// + roll lets them self-correct before tapping. A live device
// inclination readout is a field-proven aid.
//
// Self-contained on purpose: owns its own CMMotionManager so any
// scan screen can drop one in without plumbing IMU through its
// view model. Pairs with GPSFixChip in the same `topStrip`.
//
// Tier mapping uses the existing confidence palette so the colour
// language matches the rest of the app:
//   • |pitch| ≤ 3°      → Good   (confidenceOk)
//   • |pitch| ≤ 8°      → Fair   (confidenceWarn)
//   • |pitch|  > 8°     → Check  (confidenceBad)

import SwiftUI

#if canImport(CoreMotion) && os(iOS)
import CoreMotion
#endif

public struct TiltBadge: View {

    @StateObject private var motion = TiltMonitor()

    public init() {}

    public var body: some View {
        let tier = currentTier
        return HStack(spacing: 6) {
            Image(systemName: "level")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(tier.color)
            Text(tier.label)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white)
            if let p = motion.pitchDeg {
                Text(String(format: "%+.0f°", p))
                    .font(ForestixType.dataSmall)
                    .foregroundStyle(.white.opacity(0.75))
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(Color.black.opacity(0.55))
        .overlay(
            Capsule().stroke(Color.white.opacity(0.20), lineWidth: 0.5))
        .clipShape(Capsule())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Device tilt \(tier.label.lowercased())")
        .accessibilityValue(motion.pitchDeg.map {
            String(format: "%.0f degrees", $0)
        } ?? "no reading")
        .onAppear { motion.start() }
        .onDisappear { motion.stop() }
    }

    private struct Tier {
        let label: String
        let color: Color
    }

    private var currentTier: Tier {
        guard let p = motion.pitchDeg else {
            // Was "Tilt —", which reads as a broken value rather than
            // "no reading yet". The badge only ever says Level or Tilted
            // otherwise, so say the third state in words too.
            return Tier(label: "Tilt: no reading", color: ForestixPalette.confidenceBad)
        }
        let abs = Swift.abs(p)
        if abs <= 3 {
            return Tier(label: "Level", color: ForestixPalette.confidenceOk)
        } else if abs <= 8 {
            return Tier(label: "Tilted", color: ForestixPalette.confidenceWarn)
        } else {
            return Tier(label: "Tilted", color: ForestixPalette.confidenceBad)
        }
    }
}

// MARK: - Live pitch source

#if canImport(CoreMotion) && os(iOS)

@MainActor
private final class TiltMonitor: ObservableObject {

    @Published private(set) var pitchDeg: Double?

    private let manager = CMMotionManager()
    private let queue = OperationQueue()

    init() {
        queue.name = "com.forestix.tilt"
        queue.qualityOfService = .userInitiated
    }

    func start() {
        guard manager.isDeviceMotionAvailable else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 30.0  // 30 Hz; UI doesn't need 100
        manager.startDeviceMotionUpdates(
            using: .xArbitraryZVertical,
            to: queue
        ) { [weak self] data, _ in
            guard let self, let d = data else { return }
            // Pitch from the gravity vector, in the SAME sign convention as
            // `IMUHelpers.pitchFromGravity` — which is what this comment
            // always claimed and the arithmetic did not do.
            //
            // It was `atan2(-g.z, …)` against IMUHelpers' `atan2(+g.z, …)`,
            // so the badge read the opposite sign to the height pipeline and,
            // once the guide line became an artificial horizon, the opposite
            // sign to the line drawn 22 pt below it. Aiming up showed "−5°"
            // over a horizon that had moved down. Two instruments on one
            // screen, a thumb apart, disagreeing about which way is up.
            //
            // Display only — the ±3° band is symmetric, so nothing but the
            // printed sign changes, and the height pipeline never read this.
            let g = d.gravity
            let pitch = atan2(g.z, (g.x * g.x + g.y * g.y).squareRoot())
            let degrees = pitch * 180 / .pi
            Task { @MainActor [weak self] in
                self?.pitchDeg = degrees
            }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
    }
}

#else

@MainActor
private final class TiltMonitor: ObservableObject {
    @Published private(set) var pitchDeg: Double?
    func start() {}
    func stop() {}
}

#endif
