// Phase 7 — device capability + power-state probes.
//
// ## Why in Common/
// Both the pre-field checklist and the top-of-home banner surface want to
// know "does this device have LiDAR?" and "is the battery low?". The checks
// themselves have no iOS dependencies beyond `#if canImport(UIKit)` and
// `#if canImport(ARKit)`, so we keep them in Common/ next to the other
// cross-module helpers.
//
// On macOS (the `swift test` host) both probes report sensible defaults
// so the test suite can run without crashing:
//   • LiDAR: `hasLiDAR` → false
//   • Battery: `level` → 1.0, `isLow` → false

import Foundation
#if canImport(UIKit)
import UIKit
#endif
#if canImport(ARKit)
import ARKit
#endif

public enum DeviceCapabilities {

    /// Whether the current device supports the ARKit scene-reconstruction
    /// features Forestix's DBH / height pipelines rely on. iPhone 12 Pro
    /// and later; iPads with the LiDAR scanner. Everything else falls
    /// back to **manual-only** mode — DBH via caliper, height via tape.
    public static var hasLiDAR: Bool {
        #if canImport(ARKit)
        if #available(iOS 13.4, macOS 10.15, *) {
            return ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh)
        }
        return false
        #else
        return false
        #endif
    }

    /// A short user-facing string for the Home-screen banner when the
    /// device lacks LiDAR. Keeps wording consistent across callers.
    public static let manualOnlyBannerTitle =
        "This device doesn't have a LiDAR sensor. Forestix will run in " +
        "**manual-only mode** — DBH via caliper and height via tape. All " +
        "other features remain available."
}

/// Lightweight battery-state snapshot. Read-once; if you need to react to
/// changes, observe `UIDevice.batteryLevelDidChangeNotification` directly.
public struct BatteryState: Sendable, Equatable {
    public let level: Float          // 0.0 … 1.0, or -1 if unknown
    public let isCharging: Bool
    public let isLow: Bool           // level ≤ 0.15

    /// Snapshot the current battery state. On macOS / non-iOS runners
    /// returns a sentinel "healthy" state so code paths don't explode.
    public static func current() -> BatteryState {
        #if os(iOS)
        UIDevice.current.isBatteryMonitoringEnabled = true
        let raw = UIDevice.current.batteryLevel
        let level = raw < 0 ? 1.0 : raw                  // -1 on simulator
        let charging: Bool
        switch UIDevice.current.batteryState {
        case .charging, .full: charging = true
        default:               charging = false
        }
        return BatteryState(level: level,
                            isCharging: charging,
                            isLow: level <= 0.15 && !charging)
        #else
        return BatteryState(level: 1.0, isCharging: false, isLow: false)
        #endif
    }
}

/// Free-storage probe. iOS sandbox root is the user's Documents directory;
/// we report *available* bytes (honoring APFS purge-able space).
public enum StorageProbe {
    public static func availableBytes() -> Int64 {
        let fm = FileManager.default
        guard let url = try? fm.url(for: .documentDirectory,
                                    in: .userDomainMask,
                                    appropriateFor: nil, create: false),
              let values = try? url.resourceValues(
                forKeys: [.volumeAvailableCapacityForImportantUsageKey]),
              let capacity = values.volumeAvailableCapacityForImportantUsage
        else { return Int64.max }
        return capacity
    }
}
