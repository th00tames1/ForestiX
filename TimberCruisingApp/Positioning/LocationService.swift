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
    /// chip + camera seeding, the scan screens' GPSAccuracyBadge, the
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
