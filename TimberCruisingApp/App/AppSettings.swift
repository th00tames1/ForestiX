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
import Basemap
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
        public static let country                 = "tc.country"
        public static let region                  = "tc.region"
        public static let regionPickerSeen        = "tc.regionPickerSeen"
        public static let logRule                 = "tc.logRule"
        public static let dbhMeasurementMethod    = "tc.dbhMeasurementMethod"
        public static let dbhEdgeAdjustDefault    = "tc.dbhEdgeAdjustDefault"
        public static let dbhBracketHalfWidth     = "tc.dbhBracketHalfWidth"
        public static let measurementSource       = "tc.measurementSource"
        public static let developerMode           = "tc.developerMode"
        public static let rawCaptureEnabled       = "tc.rawCaptureEnabled"
        public static let breastHeightGuide       = "tc.breastHeightGuide"
        public static let dbhAutoSegmentation    = "tc.dbhAutoSegmentation"
        public static let appearance              = "tc.appearance"
        // RETIRED: "tc.dbhMethodSource" (depth / AR-motion / AR-caliper
        // picker). The AR arms are gone — they recorded no raw captures —
        // so the DBH scan is depth-only. The key is no longer read, which
        // migrates any stored "arMotion"/"arCaliper" value to the depth
        // method.
        public static let researchTrueValue       = "tc.researchTrueValue"
        public static let researchSpecies         = "tc.researchSpecies"
        public static let currentCruiseProjectID  = "tc.currentCruiseProjectID"
        public static let mapMode                 = "tc.mapMode"
        public static let mapType                 = "tc.mapType"
        public static let measureHeightAfterDBH   = "tc.measureHeightAfterDiameter"
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

    /// The stored setting decoded from its raw string.
    ///
    /// Exposed because a handful of screens are pushed from contexts that do
    /// NOT carry an `AppSettings` in their environment — `StratumDrawScreen`
    /// off the cruise-setup sheet, `RecordCentreSheet` and the offset flow it
    /// pushes — and an `@EnvironmentObject` that is not there is a crash, not
    /// a fallback. Those read the key with `@AppStorage` and decode here, so
    /// they cannot end up with a different default from everyone else. Same
    /// shape as `Country.fromRaw` / `BasemapType.fromRaw`.
    public static func unitSystem(fromRaw raw: String?) -> UnitSystem {
        raw.flatMap(UnitSystem.init(rawValue:)) ?? .imperial
    }

    public var unitSystem: UnitSystem {
        get { AppSettings.unitSystem(fromRaw: defaults.string(forKey: Keys.unitSystem)) }
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

    /// CRUISE MODE — id of the project the cruise map is currently
    /// scoped to (the project chip). nil until the cruiser has picked
    /// or created one; the map home's cruise mode falls back to the
    /// most recently updated project.
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

    /// Map home mode — "measure" (quick measure, the default) or
    /// "cruise". One map, two modes: the home screen renders the mode
    /// this returns and the toggle circle flips it. Persisted so the
    /// app reopens in the mode the cruiser was working in.
    public var mapMode: String {
        get { defaults.string(forKey: Keys.mapMode) ?? "measure" }
        set { defaults.set(newValue, forKey: Keys.mapMode); objectWillChange.send() }
    }

    /// Which BUILT-IN base layer the map draws — "satellite" (Esri World
    /// Imagery) or "normal" (OpenStreetMap standard street tiles), picked
    /// in Map settings › Map type. Defaults to `.satellite`, which is
    /// what the app has always drawn, so existing installs see no change.
    /// The Android sibling reads the same `tc.mapType` key and the same
    /// raw values.
    public var mapType: BasemapType {
        get { BasemapType.fromRaw(defaults.string(forKey: Keys.mapType)) }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.mapType)
            objectWillChange.send()
        }
    }

    /// Country the cruiser is working in — sits above `region` and decides the
    /// volume standard, the cubic unit, whether a board-foot log rule applies,
    /// and whether the first-run cascade shows the US region step. Defaults to
    /// `.unitedStates` so every existing install keeps behaving exactly as it
    /// did before internationalisation landed. The Android sibling reads the
    /// same `tc.country` key.
    public var country: Country {
        get { Country.fromRaw(defaults.string(forKey: Keys.country)) }
        set {
            defaults.set(newValue.rawValue, forKey: Keys.country)
            objectWillChange.send()
        }
    }

    /// Pre-loaded regional species filter. nil = no region picked yet
    /// (the first-run locale cascade hasn't been shown / has been dismissed).
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

    /// RAW-CAPTURE REPLAY (developer-mode research) — when on, every DBH
    /// burst and every Height compute serializes a bundle of the exact raw
    /// inputs the estimator consumed (depth buffers, intrinsics, tap, axis,
    /// poses, calibration, one reference RGB) plus a reproducibility
    /// self-check, so the owner can re-run current/updated estimator code
    /// on stored field data offline. Gated by BOTH this flag AND
    /// `developerMode`; default OFF so field cruisers pay zero cost. The
    /// Android sibling reads the same `tc.rawCaptureEnabled` key.
    public var rawCaptureEnabled: Bool {
        get { defaults.bool(forKey: Keys.rawCaptureEnabled) }
        set { defaults.set(newValue, forKey: Keys.rawCaptureEnabled); objectWillChange.send() }
    }

    /// BREAST-HEIGHT GUIDE — draws a marker from the tree base up to
    /// `Units.breastHeightM` on the Diameter scan so the cruiser can see
    /// where breast height crosses the stem instead of judging it. The base
    /// is placed by tapping the ground at the foot of the trunk.
    ///
    /// THIS KEY ALONE gates it. It used to require `developerMode` as well,
    /// which put the only answer the app offers to "how does the phone know
    /// it read the stem at breast height?" behind a switch a cruiser has no
    /// reason to find. `defaults.bool`, so someone who has never seen the row
    /// still gets it OFF. It is a GUIDE: it writes nothing, changes no
    /// recorded diameter and reaches no export.
    /// The Android sibling reads the same `tc.breastHeightGuide` key.

    /// AUTOMATIC STEM EDGES — reads the stem's edges out of a segmentation
    /// mask instead of walking the depth map.
    ///
    /// DEVELOPER MODE AND THIS KEY, never either alone. It went behind the
    /// developer gate once it had been measured: against 60 real captures it
    /// finds a trunk in 40 % of frames, and where it does the edges span about
    /// 0.21 of the screen against the cruiser's own bracket at 0.36 — the mask
    /// has holes mid-stem and bleeds into the bank behind the tree. It changes
    /// what the instrument measures — not the chord identity, which is
    /// untouched, but the two pixels the identity is applied between — and
    /// this project's numbers are the paper's numbers.
    ///
    /// A reading it produced is recorded as "segmented", distinct from a
    /// thumb-placed "manual", so a corpus can be split on it later.
    ///
    /// The Android sibling reads the same `tc.dbhAutoSegmentation` key.
    public var dbhAutoSegmentation: Bool {
        get { defaults.bool(forKey: Keys.dbhAutoSegmentation) }
        set { defaults.set(newValue, forKey: Keys.dbhAutoSegmentation); objectWillChange.send() }
    }

    public var breastHeightGuide: Bool {
        get { defaults.bool(forKey: Keys.breastHeightGuide) }
        set { defaults.set(newValue, forKey: Keys.breastHeightGuide); objectWillChange.send() }
    }

    /// App appearance — "light" (default) or "dark". Both are the same
    /// Field High-Contrast identity; the root maps this to the SwiftUI
    /// colour scheme so trait-dynamic tokens + system sheets flip together.
    public var appearance: String {
        get { defaults.string(forKey: Keys.appearance) ?? "light" }
        set { defaults.set(newValue, forKey: Keys.appearance); objectWillChange.send() }
    }

    /// Operator-set tag written into every research-log row while Developer
    /// mode is on: the reference measurement for the controlled cylinder /
    /// known-distance experiments (blank for real-tree runs, joined later by
    /// tree id — which now comes from the tree the capture is locked to, not
    /// from a box the operator retyped).
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
    /// persisted value. (The research toggle that used to flip it,
    /// `MeasureSourceToggleButton`, is no longer rendered — field report
    /// F5 — so in practice this now always resolves to the device default.
    /// Deliberate: F5 required the CONTROL to go, not the sensor path to
    /// change, and the resolution order here is unchanged.)
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

    /// CRUISE — after a diameter is accepted in the cruise tally, go
    /// straight into Height for the SAME tree. Field report F10: the tally
    /// used to loop diameter-only, so heights were never asked for and the
    /// cruiser had to go back through the tree peek for every one.
    ///
    /// Defaults to TRUE (note `object(forKey:) as? Bool`, not
    /// `defaults.bool`, which would default to false). Cruisers running a
    /// diameter-only tally turn it off in Settings › Measuring.
    public var measureHeightAfterDiameter: Bool {
        get { defaults.object(forKey: Keys.measureHeightAfterDBH) as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: Keys.measureHeightAfterDBH)
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

    /// Whether the diameter scan opens with the edge bracket (ADJUST) up.
    ///
    /// DEFAULTS TO TRUE. Automatic edge-finding jitters
    /// left and right on a real stem: bark texture, a stick against the
    /// trunk, a second stem behind it, and the found edges move between
    /// frames. The cruiser's verdict on it was that the automatic path was
    /// not usable in the stand. Placing the bracket by hand takes one drag
    /// and is stable, so that is what the screen opens on.
    ///
    /// IT BRIEFLY WENT BACK TO AUTO, for one commit, after the field
    /// reported the bracket reading 1.5× high on Android and showing nothing
    /// at all on iOS. Both were faults in this round's own changes — a
    /// symmetric-about-the-crosshair drag that over-reads whenever the aim
    /// is not exactly on the stem centre, and a view→depth mapping that was
    /// never built because it was pushed from a view that had not been laid
    /// out. With those fixed the bracket is back to what the field had
    /// already confirmed works, so it is the default again.
    ///
    /// It is the LAST-USED mode, not a hard default: the Auto pill still
    /// exists and a cruiser who prefers automatic edges gets it back on the
    /// next scan without hunting for a Settings row. Note `object(forKey:)
    /// as? Bool` rather than `defaults.bool`, which would default to false.
    public var dbhEdgeAdjustDefault: Bool {
        get { defaults.object(forKey: Keys.dbhEdgeAdjustDefault) as? Bool ?? true }
        set {
            defaults.set(newValue, forKey: Keys.dbhEdgeAdjustDefault)
            objectWillChange.send()
        }
    }

    /// HALF the bracket width, as a fraction of the walk axis, so a bracket
    /// set on one tree opens at the same width on the next — and so the
    /// diameter screen can open ON the bracket without an Auto interlude.
    ///
    /// FIELD REPORT 4 — a cruise walks a plot at roughly one standing
    /// distance from stem to stem, so consecutive trees subtend nearly the
    /// same on-screen width. Re-dragging every time was work the previous
    /// tree had already done.
    ///
    /// ONE number, not two edges: the handles move INDEPENDENTLY once the
    /// bracket is up (see `DBHScanScreen.adjustHandle` — making them
    /// symmetric caused a 1.5× over-read and was reverted), but a re-opened
    /// bracket has nothing better than the crosshair to centre on, so the
    /// width is the only part of a previous tree's bracket worth carrying.
    /// What is stored is therefore the half-SPAN, `(right − left) / 2`.
    ///
    /// Clamped on read as well as write: a value from a future build, or a
    /// corrupt default, must not be able to produce a bracket wider than
    /// the screen or narrower than the fit's minimum gap.
    public var dbhBracketHalfWidth: Double {
        get {
            let stored = defaults.object(forKey: Keys.dbhBracketHalfWidth) as? Double
            return Self.clampBracketHalfWidth(stored ?? Self.defaultBracketHalfWidth)
        }
        set {
            defaults.set(Self.clampBracketHalfWidth(newValue),
                         forKey: Keys.dbhBracketHalfWidth)
            objectWillChange.send()
        }
    }

    /// FIRST-RUN bracket half-width — half the fraction of the walk axis a
    /// typical stem covers at a typical working distance. Replaced by the
    /// cruiser's own width the first time a handle is released.
    ///
    /// DERIVED, not picked for symmetry. The old value was 0.25 (a bracket
    /// across half the screen), and it is what made ADJUST look dead: half
    /// of a 192-px walk axis is a 96-px span, which against the depth-scaled
    /// focal is ≈ 59·z cm — over the 100 cm plausibility ceiling of the day
    /// past about 1.7 m, so every frame returned nil. The ceiling is 300 cm
    /// now and that particular failure is gone, but the number was never
    /// right: it opens the bracket at roughly twice a real stem.
    ///
    /// WHERE 0.135 COMES FROM. `val/analysis/relayer.py` replays 97
    /// tape-verified McDunn captures through the shipped ADJUST path and
    /// reproduces the stored diameter to a median of 0.00 cm, so its numbers
    /// describe this code. Median k = w/(2f) on iOS is 0.130, where w is the
    /// bracket span in walk-axis pixels and f the depth-scaled focal. Hence
    ///
    ///     span fraction = w / extent = 2k · (f / extent)
    ///
    /// `bracketChordFit` divides by fx and walks the depth map's short axis
    /// in portrait, so f/extent = (fx_full · depthW/imgW) / depthH =
    /// fx_full / imgH — the downsample cancels, and on a 1920×1440 capture
    /// with fx_full ≈ 1480 that is ≈ 1.03. So span ≈ 0.26 · 1.03 ≈ 0.27, and
    /// half of it is 0.135. (It is device-dependent at the few-per-cent
    /// level through fx_full; a seed does not need better than that.)
    ///
    /// CROSS-CHECK against the identity d = w·z/(f − w/2): a 0.135 half-width
    /// is a 35 cm stem at 1.2 m, which is the middle of the range the field
    /// protocol asks for — DBH accuracy degrades measurably past ~1.5 m.
    ///
    /// The same constant seeds Android (`AppSettings.kt`), where the number
    /// is a fraction of the VIEW that ARCore converts to depth space rather
    /// than the 1:1 identification iOS uses. It is kept identical because
    /// the two platforms share this key, and because on-screen a stem of a
    /// given size at a given range covers a similar fraction of either
    /// phone's preview. Android's own median k cannot be inverted into a
    /// view fraction without the device's display transform.
    public static let defaultBracketHalfWidth: Double = 0.135

    /// The bracket's legal half-width range. 0.02 is half the fit's 0.04
    /// minimum gap; 0.48 puts the handles 2 % in from each screen edge,
    /// where they are still grabbable.
    public static func clampBracketHalfWidth(_ value: Double) -> Double {
        guard value.isFinite else { return defaultBracketHalfWidth }
        return min(max(value, 0.02), 0.48)
    }

    /// DBH sensing path. There is exactly ONE now: the LiDAR depth path.
    ///
    /// This used to be a 3-way picker that CLAMPED toward `.arCaliper` on
    /// non-LiDAR hardware. Both AR arms were removed (they recorded no raw
    /// captures), so the getter ignores whatever is stored and always reports
    /// the depth method — that is the migration: a device that persisted
    /// "arMotion" / "arCaliper" can no longer select a method that does not
    /// exist. Read-only: nothing writes the key any more.
    public var dbhMethodSource: DBHMethodSource { .lidarDepth }
}
