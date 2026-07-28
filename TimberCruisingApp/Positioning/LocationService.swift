// Spec §7.3 + §4.5: CoreLocation wrapper that feeds the Positioning
// strategies. REQ-CTR-001, REQ-NAV-003.
//
// Responsibilities:
//  * Request `whenInUse` authorization and surface the status so the
//    UI can show a graceful "location denied" banner.
//  * Maintain a rolling buffer of up to 120 CLLocationSnapshots (2 min
//    at 1 Hz, enough for the 60 s averaging window + headroom).
//  * Expose @Published `latestSnapshot` / `latestHeading` for live
//    tier badges and the cruise map's navigation guide.
//  * A synchronous `tier(from:)` helper that classifies the most
//    recent fix for the header badge without running the 60 s
//    averager (REQ-NAV-003).
//
// Pure-macOS stub path: when CoreLocation is not available (SPM
// tests on Linux, or any non-Apple build), the class still compiles
// but refuses to start. The real pipeline only runs on iOS/macOS
// devices with GPS.

import Foundation
import Combine
import Models

#if canImport(CoreLocation)
import CoreLocation
#endif

@MainActor
public final class LocationService: NSObject, ObservableObject {

    /// App-scoped instance for PASSIVE consumers — the map home's GPS
    /// chip + camera seeding, the scan screens' GPSFixChip, the
    /// plot mini-map, and the cruise-mode navigation reads. One shared
    /// CLLocationManager instead of one per screen; subscriber
    /// ref-counting (`acquire()`/`release()`) keeps it running while at
    /// least one screen holds it and fully stops it at zero.
    ///
    /// The GPS-AVERAGING flow (PlotCenterViewModel / OffsetFlowViewModel
    /// behind RecordCentreSheet) deliberately does NOT use this
    /// instance: it `clearBuffer()`s and reads the rolling buffer as
    /// private state, which must stay isolated from passive subscribers.
    public static let shared = LocationService()

    public enum AuthStatus: Sendable, Equatable {
        case notDetermined, denied, restricted, authorizedWhenInUse, authorized
        case unsupported        // platform has no CoreLocation
    }

    /// Up to `bufferCapacity` recent samples in insertion order. The
    /// PlotCenter averager pulls the last 60 when the user accepts.
    @Published public private(set) var buffer: [CLLocationSnapshot] = []
    @Published public private(set) var latestSnapshot: CLLocationSnapshot?
    /// Most recent fix from ANY running instance (the GPS badge on the
    /// scan screens keeps one alive) — lets Accept-time capture read a
    /// position without spinning up a second CLLocationManager.
    public private(set) static var lastGlobalFix: CLLocationSnapshot?
    @Published public private(set) var authStatus: AuthStatus = .notDetermined

    /// Compass heading in degrees true north (0…360). nil until the
    /// first heading update arrives.
    @Published public private(set) var headingTrueDeg: Double?

    public let bufferCapacity: Int

    #if canImport(CoreLocation)
    private let manager: CLLocationManager
    #endif

    public init(bufferCapacity: Int = 120) {
        self.bufferCapacity = bufferCapacity
        #if canImport(CoreLocation)
        self.manager = CLLocationManager()
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        refreshAuthStatus()
        #else
        super.init()
        authStatus = .unsupported
        #endif
    }

    // MARK: - Lifecycle

    public func requestAuthorization() {
        #if canImport(CoreLocation)
        manager.requestWhenInUseAuthorization()
        #endif
    }

    public func start() {
        #if canImport(CoreLocation)
        manager.startUpdatingLocation()
        #if os(iOS)
        if CLLocationManager.headingAvailable() {
            manager.startUpdatingHeading()
        }
        #endif
        #endif
    }

    public func stop() {
        #if canImport(CoreLocation)
        manager.stopUpdatingLocation()
        #if os(iOS)
        manager.stopUpdatingHeading()
        #endif
        #endif
    }

    // MARK: - Shared-instance subscriber ref-counting

    /// Number of live `acquire()` holders on this instance.
    private var subscriberCount = 0

    /// Subscriber-counted `start()`: the CLLocationManager starts on
    /// the 0 → 1 transition and keeps running until the last holder
    /// calls `release()`. Passive consumers call this from `onAppear`
    /// (paired with `release()` in `onDisappear`) exactly where they
    /// previously start()/stop()'d their own per-screen instance.
    public func acquire() {
        subscriberCount += 1
        if subscriberCount == 1 { start() }
    }

    /// Balances `acquire()`. The manager fully stops when the last
    /// subscriber leaves, so the shared instance costs no battery
    /// while no screen is showing live GPS.
    public func release() {
        subscriberCount = max(0, subscriberCount - 1)
        if subscriberCount == 0 { stop() }
    }

    /// Grab the most recent `n` samples — what PlotCenterViewModel
    /// feeds into `GPSAveraging.compute` once the averaging window
    /// elapses.
    public func recentSamples(_ n: Int) -> [CLLocationSnapshot] {
        Array(buffer.suffix(n))
    }

    public func clearBuffer() {
        buffer.removeAll(keepingCapacity: true)
    }

    // MARK: - Live tier classification (REQ-NAV-003)

    /// Quick "what tier would one sample be?" for the nav header
    /// badge. Uses a conservative single-sample rule: horizontal
    /// accuracy alone drives the tier (we don't have scatter for a
    /// 1-sample window). Matches the UX: "GPS-C" while walking,
    /// "GPS-A" when it tightens up.
    public static func tier(forHorizontalAccuracyM mAcc: Double) -> PositionTier {
        if mAcc <= 0 { return .D }
        if mAcc < 5  { return .A }
        if mAcc < 10 { return .B }
        if mAcc < 20 { return .C }
        return .D
    }

    // MARK: - Internal

    fileprivate func ingest(_ s: CLLocationSnapshot) {
        buffer.append(s)
        if buffer.count > bufferCapacity {
            buffer.removeFirst(buffer.count - bufferCapacity)
        }
        latestSnapshot = s
        Self.lastGlobalFix = s
    }

    #if canImport(CoreLocation)
    fileprivate func refreshAuthStatus() {
        switch manager.authorizationStatus {
        case .notDetermined:       authStatus = .notDetermined
        case .denied:              authStatus = .denied
        case .restricted:          authStatus = .restricted
        case .authorizedWhenInUse: authStatus = .authorizedWhenInUse
        case .authorizedAlways:    authStatus = .authorized
        @unknown default:          authStatus = .denied
        }
    }
    #endif
}

// MARK: - Fix freshness

/// THE ONE RULE for "does this stored fix still describe where the phone
/// is?", and the only place the app is allowed to decide that.
///
/// It lives here, beside `latestSnapshot` and `lastGlobalFix`, because
/// those two are precisely what it exists to guard. Both are written on
/// ingest and NEVER cleared: `latestSnapshot` survives `stop()`, and
/// `lastGlobalFix` is a static that outlives every screen. So under
/// canopy they freeze at the last fix that got through, and on
/// re-entering a screen they still hold the previous session's — an hour
/// old, a valley away. Age is the only thing that separates those from a
/// live fix, and the snapshot carries its own timestamp.
///
/// Anything the app would be WRONG about — which side of a plot boundary
/// the cruiser stands on, where to paint the you-dot, where to put the
/// cruiser on the plot card — goes through here first. One rule means the
/// words and the picture can never contradict each other.
public enum FixFreshness {

    /// Older than this and a fix is no longer evidence of where the
    /// phone is.
    ///
    /// WHY FIVE SECONDS. A cruiser walks about 1.2–1.4 m/s in a stand, so
    /// five seconds is at most six or seven metres of movement — the
    /// order of one plot radius, which is the largest error that still
    /// leaves "inside" a meaningful word. CoreLocation delivers at
    /// roughly 1 Hz here (`distanceFilter` is none), so five seconds also
    /// tolerates four consecutive dropped fixes before the app goes
    /// quiet: tighter and it would flicker to "no position" on ordinary
    /// under-canopy jitter, looser and a cruiser could walk clean out of
    /// a plot while the map still said they were in it.
    public static let maxUsableAge: TimeInterval = 5

    /// Past this, the fix is not merely stale — the phone has had no
    /// contact with the sky for a minute.
    ///
    /// This is the line the GPS chip draws between AMBER and RED, and the
    /// distinction is a real one in the field: under a closed canopy a fix
    /// drops for a few seconds constantly and comes straight back, which is
    /// worth showing but not worth alarming about. A whole minute with
    /// nothing is a different situation — the cruiser should stop trusting
    /// any position on screen and step out for sky. Both platforms read
    /// this constant.
    public static let lostAge: TimeInterval = 60

    /// The fix to REASON FROM and DRAW FROM, or nil when there is none
    /// worth either.
    ///
    /// THE TEST IS TWO-SIDED, ON MAGNITUDE. A one-sided `now - timestamp
    /// <= budget` passes every fix of every age the moment the clock
    /// reading trails the timestamp's, which is not exotic: a device with
    /// no network time, a hand-set clock, or a boot before NTP lands can
    /// sit behind the satellite time GNSS receivers stamp fixes with, and
    /// then a subtraction that should have been "eight hours" is negative
    /// and sails through. `abs(age)` fails closed in both directions — a
    /// timestamp in the future is no more evidence than one in the past.
    /// A non-finite age (a corrupt or unset timestamp) is refused for the
    /// same reason.
    public static func usable(_ snapshot: CLLocationSnapshot?,
                              asOf now: Date = Date()) -> CLLocationSnapshot? {
        guard let snapshot else { return nil }
        let age = now.timeIntervalSince(snapshot.timestamp)
        guard age.isFinite, abs(age) <= maxUsableAge else { return nil }
        return snapshot
    }
}

// MARK: - CoreLocation delegate

#if canImport(CoreLocation)
extension LocationService: CLLocationManagerDelegate {
    public nonisolated func locationManager(
        _ manager: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        let snapshots = locations.map(CLLocationSnapshot.init)
        Task { @MainActor in
            for s in snapshots { self.ingest(s) }
        }
    }

    #if os(iOS)
    public nonisolated func locationManager(
        _ manager: CLLocationManager,
        didUpdateHeading newHeading: CLHeading
    ) {
        // Prefer true heading; fall back to magnetic when true is
        // unavailable (CoreLocation reports -1 before first fix).
        let deg = newHeading.trueHeading >= 0
            ? newHeading.trueHeading
            : newHeading.magneticHeading
        Task { @MainActor in self.headingTrueDeg = deg }
    }
    #endif

    public nonisolated func locationManagerDidChangeAuthorization(
        _ manager: CLLocationManager
    ) {
        Task { @MainActor in self.refreshAuthStatus() }
    }

    public nonisolated func locationManager(
        _ manager: CLLocationManager, didFailWithError error: Error
    ) {
        // Transient CoreLocation failures (kCLErrorLocationUnknown)
        // are fine — the next sample usually arrives. We log and
        // continue; auth/denied cases are handled via the delegate
        // callback above.
    }
}
#endif
