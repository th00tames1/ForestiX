// App-level user preferences backed by UserDefaults. Only keys a Phase 1
// cruiser can configure are exposed:
//   • unitSystem            — imperial vs metric display preference
//   • tileURLTemplate       — XYZ slippy-map template ({z}/{x}/{y}) drawn
//                             as an OVERLAY on top of the built-in
//                             satellite base layer (contour / forest-
//                             service tiles). When nil, the Map view
//                             shows the satellite base alone. Cruisers
//                             must acknowledge the overlay provider's
//                             usage policy before it draws.
//   • tileProviderLabel     — display name for the above (optional)
//   • providerUsageAcknowledged — gates overlay rendering until the cruiser
//                             has ticked the usage-policy checkbox.
//   • overlayEnabled        — draw the overlay layer over the satellite
//                             base (default true; toggled from the map's
//                             layers sheet).

import Foundation
import Common
import Models
import Sensors

/// Which world-sensing path the AR measurement screens raycast against.
///   • lidar — the scene-reconstruction mesh / sceneDepth path. Most
///     accurate, but only available on LiDAR-equipped devices.
///   • ar    — estimated-plane raycast. Works on every ARKit device, so
///     it's the only option on phones without the LiDAR scanner.
public enum MeasurementSource: String, CaseIterable, Sendable {
    case lidar
    case ar
    public var displayName: String { self == .lidar ? "LiDAR" : "AR" }
}

@MainActor
public final class AppSettings: ObservableObject {

    public enum Keys {
        public static let unitSystem              = "tc.unitSystem"
        public static let tileURLTemplate         = "tc.tileURLTemplate"
        public static let tileProviderLabel       = "tc.tileProviderLabel"
        public static let providerUsageAck        = "tc.providerUsageAcknowledged"
        public static let overlayEnabled          = "tc.overlayEnabled"
        public static let advancedMode            = "tc.advancedMode"
        public static let region                  = "tc.region"
        public static let regionPickerSeen        = "tc.regionPickerSeen"
        public static let logRule                 = "tc.logRule"
        public static let dbhMeasurementMethod    = "tc.dbhMeasurementMethod"
        public static let measurementSource       = "tc.measurementSource"
        public static let developerMode           = "tc.developerMode"
        public static let appearance              = "tc.appearance"
        public static let dbhMethodSource         = "tc.dbhMethodSource"
        public static let researchTreeId          = "tc.researchTreeId"
        public static let researchTrueValue       = "tc.researchTrueValue"
        public static let researchSpecies         = "tc.researchSpecies"
        public static let currentCruiseProjectID  = "tc.currentCruiseProjectID"
    }

    private let defaults: UserDefaults
    public init(defaults: UserDefaults) { self.defaults = defaults }

    /// Shared across the app (uses `.standard`).
    public static func live() -> AppSettings { AppSettings(defaults: .standard) }

    /// Isolated defaults store for previews / tests.
    public static func ephemeral() -> AppSettings {
        let name = "tc.preview.\(UUID().uuidString)"
        let ud = UserDefaults(suiteName: name) ?? .standard
        return AppSettings(defaults: ud)
    }

    // MARK: - Published properties

    public var unitSystem: UnitSystem {
        get {
            let raw = defaults.string(forKey: Keys.unitSystem) ?? UnitSystem.imperial.rawValue
            return UnitSystem(rawValue: raw) ?? .imperial
        }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.unitSystem)
            objectWillChange.send()
        }
    }

    public var tileURLTemplate: String? {
        get {
            let raw = defaults.string(forKey: Keys.tileURLTemplate)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            return (raw?.isEmpty == false) ? raw : nil
        }
        set {
            defaults.set(newValue, forKey: Keys.tileURLTemplate)
            objectWillChange.send()
        }
    }

    public var tileProviderLabel: String? {
        get { defaults.string(forKey: Keys.tileProviderLabel) }
        set { defaults.set(newValue, forKey: Keys.tileProviderLabel); objectWillChange.send() }
    }

    public var providerUsageAcknowledged: Bool {
        get { defaults.bool(forKey: Keys.providerUsageAck) }
        set { defaults.set(newValue, forKey: Keys.providerUsageAck); objectWillChange.send() }
    }

    /// Draw the user overlay on top of the satellite base. Defaults to
    /// TRUE (unlike `defaults.bool`'s false) — a cruiser who pastes an
    /// overlay template expects to see it without hunting for a switch.
    public var overlayEnabled: Bool {
        get { defaults.object(forKey: Keys.overlayEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Keys.overlayEnabled); objectWillChange.send() }
    }

    /// When `true`, the full project/plot/cruise workflow is shown at
    /// app launch. When `false` (the default for new users), Forestix
    /// boots straight into Quick Measure — just DBH + Height — so
    /// cruisers who only want a one-off measurement aren't forced
    /// through project setup.
    public var advancedMode: Bool {
        get { defaults.bool(forKey: Keys.advancedMode) }
        set { defaults.set(newValue, forKey: Keys.advancedMode); objectWillChange.send() }
    }

    /// CRUISE MODE — id of the project the cruise map is currently
    /// scoped to (the project chip). nil until the cruiser has picked
    /// or created one; CruiseMapScreen falls back to the most recently
    /// updated project.
    public var currentCruiseProjectID: UUID? {
        get {
            guard let raw = defaults.string(forKey: Keys.currentCruiseProjectID)
            else { return nil }
            return UUID(uuidString: raw)
        }
        set {
            if let id = newValue {
                defaults.set(id.uuidString, forKey: Keys.currentCruiseProjectID)
            } else {
                defaults.removeObject(forKey: Keys.currentCruiseProjectID)
            }
            objectWillChange.send()
        }
    }

    /// Pre-loaded regional species filter. nil = no region picked yet
    /// (RegionPickerSheet hasn't been shown / has been dismissed).
    /// "all" = explicit "show every species" choice.
    public var region: Region? {
        get {
            guard let raw = defaults.string(forKey: Keys.region) else { return nil }
            return Region(rawValue: raw)
        }
        set {
            if let r = newValue {
                defaults.set(r.rawValue, forKey: Keys.region)
            } else {
                defaults.removeObject(forKey: Keys.region)
            }
            objectWillChange.send()
        }
    }

    /// Has the region picker been presented at least once? Tracked
    /// separately from `region` so dismissing without picking still
    /// counts as "seen" — we don't want to nag the user on every launch.
    public var regionPickerSeen: Bool {
        get { defaults.bool(forKey: Keys.regionPickerSeen) }
        set { defaults.set(newValue, forKey: Keys.regionPickerSeen); objectWillChange.send() }
    }

    /// Default log rule for board-foot volume calculations.
    /// Defaults to Scribner Decimal C — USFS standard west of the
    /// Mississippi. Eastern cruisers flip to Doyle once.
    public var logRule: LogRule {
        get {
            let raw = defaults.string(forKey: Keys.logRule) ?? LogRule.scribner.rawValue
            return LogRule(rawValue: raw) ?? .scribner
        }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.logRule)
            objectWillChange.send()
        }
    }

    /// Whether this device has the LiDAR scanner the mesh raycast path
    /// relies on. Checked once at launch (and any time the UI asks) so
    /// the measurement screens can disable the LiDAR toggle and fall the
    /// cruiser back to the AR (estimated-plane) path automatically.
    public var deviceSupportsLiDAR: Bool { ARKitSessionManager.supportsLiDAR }

    /// Developer / research mode — surfaces the live measurement internals
    /// (depth source, intrinsics, point counts, raw chord, pitch, distance,
    /// σ) on the AR screens and unlocks the validation-experiment tooling.
    public var developerMode: Bool {
        get { defaults.bool(forKey: Keys.developerMode) }
        set { defaults.set(newValue, forKey: Keys.developerMode); objectWillChange.send() }
    }

    /// App appearance — "light" (default) or "dark". Both are the same
    /// Field High-Contrast identity; the root maps this to the SwiftUI
    /// colour scheme so trait-dynamic tokens + system sheets flip together.
    public var appearance: String {
        get { defaults.string(forKey: Keys.appearance) ?? "light" }
        set { defaults.set(newValue, forKey: Keys.appearance); objectWillChange.send() }
    }

    /// Operator-set tags written into every research-log row while
    /// Developer mode is on. `researchTreeId` labels the physical tree (so
    /// repeats group + join to ground truth); `researchTrueValue` is the
    /// reference measurement for the controlled cylinder / known-distance
    /// experiments (blank for real-tree runs, joined later by tree id).
    public var researchTreeId: String {
        get { defaults.string(forKey: Keys.researchTreeId) ?? "" }
        set { defaults.set(newValue, forKey: Keys.researchTreeId); objectWillChange.send() }
    }
    public var researchTrueValue: String {
        get { defaults.string(forKey: Keys.researchTrueValue) ?? "" }
        set { defaults.set(newValue, forKey: Keys.researchTrueValue); objectWillChange.send() }
    }
    public var researchSpecies: String {
        get { defaults.string(forKey: Keys.researchSpecies) ?? "" }
        set { defaults.set(newValue, forKey: Keys.researchSpecies); objectWillChange.send() }
    }

    /// Preferred world-sensing source for the AR measurement screens.
    /// Field mode is opinionated: LiDAR devices ALWAYS raycast against
    /// the scene-reconstruction mesh (`.lidar`) and devices without the
    /// scanner use the estimated-plane path (`.ar`) — there's no
    /// user-facing switch any more. Only Developer mode honours the
    /// persisted value, so the research toggle
    /// (`MeasureSourceToggleButton`) keeps working there.
    public var measurementSource: MeasurementSource {
        get {
            guard ARKitSessionManager.supportsLiDAR else { return .ar }
            guard developerMode else { return .lidar }
            let raw = defaults.string(forKey: Keys.measurementSource)
            return raw.flatMap(MeasurementSource.init(rawValue:)) ?? .lidar
        }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.measurementSource)
            objectWillChange.send()
        }
    }

    /// Phase 19 — which DBH algorithm the live preview + burst should
    /// use. Defaults to `.chord` (the silhouette / pixel-width method
    /// every peer LiDAR forestry app uses). Cruisers who want the older
    /// partial-arc circle fit can switch from Settings.
    public var dbhMeasurementMethod: DBHMeasurementMethod {
        get {
            let raw = defaults.string(forKey: Keys.dbhMeasurementMethod)
                ?? DBHMeasurementMethod.chord.rawValue
            return DBHMeasurementMethod(rawValue: raw) ?? .chord
        }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.dbhMeasurementMethod)
            objectWillChange.send()
        }
    }

    /// 3-way DBH sensing path (LiDAR depth / AR motion / AR caliper) —
    /// the within-device comparison picker, now a Developer-mode research
    /// control. Field mode always uses the LiDAR depth path (the DBH
    /// screen blocks scanning outright on non-LiDAR devices). In
    /// Developer mode the persisted choice is honoured; a persisted
    /// `.lidarDepth` on a non-LiDAR device is reported as `.arCaliper`
    /// (the most robust depth-free method).
    public var dbhMethodSource: DBHMethodSource {
        get {
            guard developerMode else {
                return ARKitSessionManager.supportsLiDAR ? .lidarDepth
                                                         : .arCaliper
            }
            let stored = defaults.string(forKey: Keys.dbhMethodSource)
                .flatMap(DBHMethodSource.init(rawValue:)) ?? .lidarDepth
            if !ARKitSessionManager.supportsLiDAR && stored == .lidarDepth {
                return .arCaliper
            }
            return stored
        }
        set {
            // Clamp on write too so a non-LiDAR device never persists
            // `.lidarDepth` — keeps the stored value in sync with the getter,
            // which already reports `.arCaliper` on those devices.
            let clamped: DBHMethodSource =
                (!ARKitSessionManager.supportsLiDAR && newValue == .lidarDepth)
                ? .arCaliper : newValue
            defaults.set(clamped.rawValue, forKey: Keys.dbhMethodSource)
            objectWillChange.send()
        }
    }
}
