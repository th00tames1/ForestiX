// App-level user preferences backed by UserDefaults. Only keys a Phase 1
// cruiser can configure are exposed:
//   • unitSystem            — imperial vs metric display preference
//   • tileURLTemplate       — XYZ slippy-map template ({z}/{x}/{y}). When
//                             nil, the Map view shows no basemap tiles. The
//                             spec explicitly does not ship a default
//                             provider; cruisers must paste their own and
//                             acknowledge the provider's usage policy.
//   • tileProviderLabel     — display name for the above (optional)
//   • providerUsageAcknowledged — gates basemap rendering until the cruiser
//                             has ticked the usage-policy checkbox.

import Foundation
import Models

@MainActor
public final class AppSettings: ObservableObject {

    public enum Keys {
        public static let unitSystem              = "tc.unitSystem"
        public static let tileURLTemplate         = "tc.tileURLTemplate"
        public static let tileProviderLabel       = "tc.tileProviderLabel"
        public static let providerUsageAck        = "tc.providerUsageAcknowledged"
        public static let advancedMode            = "tc.advancedMode"
        public static let region                  = "tc.region"
        public static let regionPickerSeen        = "tc.regionPickerSeen"
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

    /// When `true`, the full project/plot/cruise workflow is shown at
    /// app launch. When `false` (the default for new users), Forestix
    /// boots straight into Quick Measure — just DBH + Height — so
    /// cruisers who only want a one-off measurement aren't forced
    /// through project setup.
    public var advancedMode: Bool {
        get { defaults.bool(forKey: Keys.advancedMode) }
        set { defaults.set(newValue, forKey: Keys.advancedMode); objectWillChange.send() }
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
}
