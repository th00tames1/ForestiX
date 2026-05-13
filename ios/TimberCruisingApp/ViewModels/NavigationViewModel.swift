// Spec §5.1 NavigationScreen view model. REQ-NAV-002/003/004.
//
// Owns the live GPS subscription, computes bearing + distance to
// the planned plot target using GeoMath, classifies tier per-sample
// for the on-screen badge, and emits a single haptic pulse when the
// user crosses inside the 5 m arrival radius (REQ-NAV-002 "Haptic
// pulse when within 5 m"). Track-log recording is a user toggle
// (REQ-NAV-004) — when on, each accepted snapshot is appended to a
// `TrackLogRepository` NDJSON file.

import Foundation
import Combine
import Models
import Positioning

@MainActor
public final class NavigationViewModel: ObservableObject {

    // MARK: - Inputs

    public let target: PlannedPlot
    public let location: LocationService

    // MARK: - Derived state

    @Published public private(set) var distanceM: Double?
    @Published public private(set) var bearingDeg: Double?
    @Published public private(set) var tier: PositionTier = .D
    @Published public private(set) var authStatus: LocationService.AuthStatus = .notDetermined
    @Published public private(set) var hasArrived: Bool = false

    /// REQ-NAV-004: per-session track log toggle. Off by default to
    /// respect user privacy / battery; the UI flips it on.
    @Published public var isTrackLogEnabled: Bool = false

    public let arrivalRadiusM: Double
    private var cancellables: Set<AnyCancellable> = []
    private let onArrival: (() -> Void)?

    public init(
        target: PlannedPlot,
        location: LocationService,
        arrivalRadiusM: Double = 5,
        onArrival: (() -> Void)? = nil
    ) {
        self.target = target
        self.location = location
        self.arrivalRadiusM = arrivalRadiusM
        self.onArrival = onArrival
        wireBindings()
    }

    // MARK: - Binding

    private func wireBindings() {
        location.$latestSnapshot
            .sink { [weak self] snap in self?.ingest(snap) }
            .store(in: &cancellables)
        location.$authStatus
            .sink { [weak self] s in self?.authStatus = s }
            .store(in: &cancellables)
    }

    private func ingest(_ snap: CLLocationSnapshot?) {
        guard let snap else {
            distanceM = nil
            bearingDeg = nil
            tier = .D
            return
        }
        let d = GeoMath.distanceM(
            fromLat: snap.latitude, fromLon: snap.longitude,
            toLat: target.plannedLat, toLon: target.plannedLon)
        let b = GeoMath.bearingDeg(
            fromLat: snap.latitude, fromLon: snap.longitude,
            toLat: target.plannedLat, toLon: target.plannedLon)
        distanceM = d
        bearingDeg = b
        tier = LocationService.tier(
            forHorizontalAccuracyM: snap.horizontalAccuracyM)
        // Edge-trigger the 5 m arrival — fire once when we first
        // cross the threshold. Leaving and re-entering re-arms.
        if d <= arrivalRadiusM {
            if !hasArrived {
                hasArrived = true
                onArrival?()
            }
        } else if d > arrivalRadiusM * 1.5 {
            hasArrived = false
        }
    }

    // MARK: - Controls

    public func start() {
        location.requestAuthorization()
        location.start()
    }

    public func stop() {
        location.stop()
    }

    /// Arrow rotation in degrees for the compass needle. Combines
    /// great-circle bearing to target with the phone's current true
    /// heading so the arrow points in a device-relative direction.
    public var arrowRotationDeg: Double? {
        guard let bearing = bearingDeg else { return nil }
        let heading = location.headingTrueDeg ?? 0
        return fmod(bearing - heading + 360, 360)
    }

    // MARK: - Preview helpers

    public static func preview(
        target: PlannedPlot,
        distanceM: Double?,
        bearingDeg: Double?,
        tier: PositionTier,
        authStatus: LocationService.AuthStatus = .authorizedWhenInUse,
        hasArrived: Bool = false,
        isTrackLogEnabled: Bool = false
    ) -> NavigationViewModel {
        let loc = LocationService()
        let vm = NavigationViewModel(target: target, location: loc)
        vm.distanceM = distanceM
        vm.bearingDeg = bearingDeg
        vm.tier = tier
        vm.authStatus = authStatus
        vm.hasArrived = hasArrived
        vm.isTrackLogEnabled = isTrackLogEnabled
        return vm
    }
}
