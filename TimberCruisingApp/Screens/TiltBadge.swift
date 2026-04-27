// Live device-tilt badge for the Diameter scan screen.
//
// The single biggest source of DBH bias is a leaning phone — a chord
// projected through a non-vertical slice of the cylinder reads a
// systematically wrong diameter. Showing the cruiser the live pitch
// + roll lets them self-correct before tapping. Arboreal Forest
// shipped this in v4.98 ("improved visualisation: device inclination").
//
// Self-contained on purpose: owns its own CMMotionManager so any
// scan screen can drop one in without plumbing IMU through its
// view model. Pairs with GPSAccuracyBadge in the same `topStrip`.
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
            return Tier(label: "Tilt —", color: ForestixPalette.confidenceBad)
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
            // Pitch from gravity vector — same convention as
            // IMUHelpers.pitchFromGravity. Returns radians; convert
            // to degrees for display.
            let g = d.gravity
            // pitch = atan2(-g.z, sqrt(g.x² + g.y²)) is the
            // device's nose-up/down angle. Use the same sign
            // convention the height pipeline uses.
            let pitch = atan2(-g.z, (g.x * g.x + g.y * g.y).squareRoot())
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
