// Spec §5.1 ARBoundary + §7.8 + REQ-BND-001..004. Holds the plot center
// (set by a screen-space tap in Phase 3 — hit-tested to ground in iOS,
// placed at (0,0,0) in macOS previews), the fixed-area radius, and the
// live user pose so the screen can render a 72-vertex ring, classify
// stems as inside/outside/borderline, and raise the 15 m drift warn.
//
// The ground mesh is held as a `GroundMeshSnapshot` so slope correction
// (§7.8 step 2) is testable — iOS glue code refreshes the snapshot from
// ARMeshAnchor updates; macOS keeps it empty and the ring stays flat at
// the center's Y.

import Foundation
import Combine
import simd
import Common
import Models
import Sensors
import AR

@MainActor
public final class ARBoundaryViewModel: ObservableObject {

    // MARK: - Published surface

    /// World-space plot center. Nil until the user taps to set it.
    @Published public private(set) var centerWorld: SIMD3<Float>?

    /// 72 ring vertices + closure vertex, slope-corrected against the
    /// current GroundMeshSnapshot. Empty until center is set.
    @Published public private(set) var ringVertices: [SIMD3<Float>] = []

    /// Fixed-area plot radius. Default 11.28 m = √(400/π) → 1/25 ha.
    @Published public var radiusM: Float = 11.28

    /// Live XZ distance from the current camera to the plot center.
    @Published public private(set) var userDistanceM: Float = 0

    /// REQ-BND-004: true when user has walked > 15 m from center.
    @Published public private(set) var isDrifted: Bool = false

    /// Drift warn threshold in metres. Default 15 m.
    @Published public var driftRadiusM: Float = 15

    @Published public private(set) var trackingStatus: TrackingStatus = .notAvailable

    // MARK: - Dependencies

    public let session: ARKitSessionManager

    /// Latest ground mesh snapshot (world-space vertices + triangles).
    /// iOS-side glue refreshes this from ARMeshAnchor updates.
    public private(set) var groundMesh: GroundMeshSnapshot = .empty

    // MARK: - Construction

    public init(session: ARKitSessionManager? = nil) {
        self.session = session ?? ARKitSessionManager()
    }

    // MARK: - Lifecycle

    private var depthCancellable: AnyCancellable?
    private var trackingCancellable: AnyCancellable?

    public func onAppear() {
        session.run()
        trackingCancellable = session.$trackingStatus
            .sink { [weak self] status in
                self?.trackingStatus = status
            }
        depthCancellable = session.$latestDepthFrame
            .compactMap { $0 }
            .sink { [weak self] frame in
                let c = frame.cameraPoseWorld.columns.3
                self?.updateUserPosition(SIMD3<Float>(c.x, c.y, c.z))
            }
    }

    public func onDisappear() {
        session.pause()
        depthCancellable?.cancel(); depthCancellable = nil
        trackingCancellable?.cancel(); trackingCancellable = nil
    }

    // MARK: - Center placement

    /// Set the plot center at a world-space point. Typically the screen
    /// layer hit-tests a tap to the ground and hands the result here.
    public func setCenter(_ point: SIMD3<Float>) {
        centerWorld = point
        refreshRingVertices()
    }

    /// Production convenience: drop the center at the current camera's
    /// XZ projected onto the ground mesh (or the camera's own Y if no
    /// triangle contains the column). Returns false if no AR frame has
    /// been received yet.
    @discardableResult
    public func setCenterAtCurrentCamera() -> Bool {
        guard let frame = session.latestDepthFrame else { return false }
        let c = frame.cameraPoseWorld.columns.3
        let y = GroundMeshSampler.sampleGroundY(
            atX: c.x, z: c.z, snapshot: groundMesh) ?? c.y
        setCenter(SIMD3<Float>(c.x, y, c.z))
        return true
    }

    public func clearCenter() {
        centerWorld = nil
        ringVertices = []
        userDistanceM = 0
        isDrifted = false
    }

    /// Update the ground mesh (called by iOS glue from ARMeshAnchor
    /// updates). Re-runs slope correction on the existing ring.
    public func updateGroundMesh(_ snapshot: GroundMeshSnapshot) {
        groundMesh = snapshot
        refreshRingVertices()
    }

    // MARK: - Stem classification (REQ-BND-003)

    public func membership(forStemXZ stem: SIMD2<Float>) -> PlotBoundaryRenderer.StemMembership? {
        guard let c = centerWorld else { return nil }
        return PlotBoundaryRenderer.membership(
            stemPositionXZ: stem,
            centerXZ: SIMD2<Float>(c.x, c.z),
            radiusM: radiusM)
    }

    // MARK: - Internals

    private func refreshRingVertices() {
        guard let center = centerWorld else {
            ringVertices = []
            return
        }
        let flat = PlotBoundaryRenderer.ringVertices(
            center: center, radiusM: radiusM)
        ringVertices = PlotBoundaryRenderer.slopeCorrected(flat) { [groundMesh] x, z in
            GroundMeshSampler.sampleGroundY(atX: x, z: z, snapshot: groundMesh)
        }
    }

    /// Test hook — pump a camera position without an ARKit frame.
    public func updateUserPosition(_ point: SIMD3<Float>) {
        guard let c = centerWorld else {
            userDistanceM = 0
            isDrifted = false
            return
        }
        let dx = point.x - c.x
        let dz = point.z - c.z
        userDistanceM = sqrt(dx * dx + dz * dz)
        isDrifted = PlotBoundaryRenderer.isDriftedBeyond(
            userXZ: SIMD2<Float>(point.x, point.z),
            centerXZ: SIMD2<Float>(c.x, c.z),
            driftRadiusM: driftRadiusM)
    }

    // MARK: - Preview factory

    public static func preview(
        centerWorld: SIMD3<Float>? = nil,
        radiusM: Float = 11.28,
        userDistanceM: Float = 0,
        isDrifted: Bool = false
    ) -> ARBoundaryViewModel {
        let vm = ARBoundaryViewModel()
        vm.radiusM = radiusM
        if let c = centerWorld { vm.setCenter(c) }
        vm.userDistanceM = userDistanceM
        vm.isDrifted = isDrifted
        return vm
    }
}
