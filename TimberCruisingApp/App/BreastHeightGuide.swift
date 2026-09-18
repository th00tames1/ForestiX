// BREAST-HEIGHT GUIDE — the selected height above the ground, drawn in the world.
//
// A phone cannot know it is reading the stem AT breast height, and "about
// chest high" is what a cruiser is otherwise left with. This puts the height
// on screen: a persistent line from the tapped base to the selected height,
// with a thin ring at the top and a label projected from the live anchor.
//
// IT IS A GUIDE AND NOTHING ELSE. It never writes to a measurement, never
// gates the shutter, never changes a recorded diameter and never reaches an
// export — the Diameter capture runs byte-for-byte as it does with the guide
// off, in every state below.
//
// One instance per screen, deliberately NOT a singleton like
// `ActiveSamplingPlot`: the plot is a place in the stand that outlives the
// screen that placed it, whereas this base belongs to the tree in front of
// the camera right now. It dies with the screen and with the tree.
//
// It sits beside `ActiveSamplingPlot` rather than in the AR module for the
// same reason that class does: it is anchor-and-marker STATE, not rendering.
// It also has to read the plot's own `trackingGraceSeconds` — one rule about
// how long an uncorrected pose may still be drawn, in one place — and AR
// cannot see App.
//
// THE BASE IS READ, NOT PINNED, for the two reasons ActiveSamplingPlot gives
// at length: a RealityKit `AnchorEntity(.anchor(identifier:))` added to a
// scene that never saw the anchor arrive binds to nothing, and one that did
// keeps drawing at the last transform when ARKit stops tracking. A guide
// drawn at the wrong height is worse than no guide, so the pose is re-read
// from the anchor on the screen's poll and the geometry goes away when the
// read refuses for longer than the grace window.

import Foundation
import AR
import Common
import Models
import Sensors
import simd

@MainActor
public final class BreastHeightGuide: ObservableObject {

    public enum Stage: Equatable {
        /// Gate off — nothing exists. No anchor, no markers, no label.
        case off
        /// Guide on, no base placed: the cruiser is aiming at the tree base
        /// and sees a small base preview where it would land.
        case aiming
        /// Base anchored; the assembly is drawn at the anchor's live pose.
        case placed
    }

    @Published public private(set) var stage: Stage = .off
    @Published public var height: BreastHeightGuideHeight = .meters130

    /// The base point as ARKit is currently correcting it — not the frozen
    /// coordinate the placing raycast returned. nil while nothing is placed
    /// and once tracking has been lost past the grace window, and everything
    /// drawn is drawn from THIS, so nil means nothing is drawn.
    @Published public private(set) var basePoint: SIMD3<Float>?

    /// Live crosshair hit while aiming — the ghost preview's position, and
    /// what `place(hit:using:)` would anchor. nil when the ray misses, which
    /// draws nothing rather than guessing.
    @Published public private(set) var ghostPoint: SIMD3<Float>?

    /// True once the base pose has gone stale beyond the tracking grace;
    /// the guide is hidden until tracking recovers.
    @Published public private(set) var trackingLost = false

    private var anchorID: UUID?

    /// When the pose stopped being corrected, on a MONOTONIC clock.
    /// `ProcessInfo.systemUptime` and not `Date()`, for the reason
    /// `ActiveSamplingPlot.poseStaleSince` spells out: a wall clock an NTP
    /// correction can step would either hold the guide at an uncorrected pose
    /// long past the grace or blink it off while tracking was fine.
    private var poseStaleSince: TimeInterval?

    public init() {}

    // MARK: - Label

    /// Label and world geometry always use the same selected height.
    public func label(in system: UnitSystem) -> String {
        system == .metric ? height.metricLabel : height.imperialLabel
    }

    // MARK: - State

    /// Gate on: start aiming. No-op once a base is placed, so a settings
    /// re-read or a re-poll never drops the base the cruiser just planted.
    public func arm() {
        guard stage == .off else { return }
        stage = .aiming
        ghostPoint = nil
    }

    /// Gate off: forget everything and take the anchor with it. Called when
    /// the toggle goes off and on screen teardown — an
    /// anchor left behind lives in the app-shared session for the rest of the
    /// process with nobody holding its id.
    public func disable(using session: ARKitSessionManager) {
        removeAnchor(using: session)
        stage = .off
        ghostPoint = nil
        basePoint = nil
        trackingLost = false
        poseStaleSince = nil
    }

    /// Drop the base and go back to aiming — the "Reset ground" button, and the
    /// tree change in the cruise tally. The Diameter screen is reused across
    /// trees, so a base at tree 7's foot must not still be drawn at tree 8.
    /// No-op while the gate is off, which keeps the tree-change hook from
    /// arming a guide nobody asked for.
    public func clearBase(using session: ARKitSessionManager) {
        guard stage != .off else { return }
        removeAnchor(using: session)
        stage = .aiming
        ghostPoint = nil
        basePoint = nil
        trackingLost = false
        poseStaleSince = nil
    }

    /// Where the ghost goes while aiming. nil is a miss and draws nothing.
    public func updateGhost(_ hit: SIMD3<Float>?) {
        guard stage == .aiming else { return }
        ghostPoint = hit
    }

    /// Anchor the base at a hit. Returns false when the session cannot create
    /// an anchor, leaving the previous guide unchanged.
    @discardableResult
    public func place(hit: SIMD3<Float>,
                      using session: ARKitSessionManager) -> Bool {
        guard let id = session.addWorldAnchor(
            at: hit, name: "forestix.breastHeight.base")
        else {
            return false
        }
        removeAnchor(using: session)
        anchorID = id
        stage = .placed
        ghostPoint = nil
        // Where the anchor is is unknown until a poll reads it off a tracked
        // frame; nothing is drawn in between, which is one poll tick — the
        // tap that got here needed tracking anyway.
        basePoint = nil
        trackingLost = false
        poseStaleSince = nil
        return true
    }

    /// Re-read the base from its ARAnchor, or hide the guide once the pose
    /// has gone uncorrected for longer than the grace window. Inside the
    /// window the last corrected point is kept, which is what stops a routine
    /// sub-second tracking dip from blinking the whole assembly.
    ///
    /// The window is `ActiveSamplingPlot.trackingGraceSeconds` READ, not
    /// restated: it is one rule about how long an uncorrected pose may still
    /// be drawn, and a second copy of the number here would be a second rule.
    @discardableResult
    public func refresh(using session: ARKitSessionManager) -> SIMD3<Float>? {
        guard stage == .placed, let anchorID else {
            basePoint = nil
            trackingLost = false
            poseStaleSince = nil
            return nil
        }
        if let live = session.trackedWorldAnchorPosition(id: anchorID) {
            poseStaleSince = nil
            if trackingLost { trackingLost = false }
            let moved = basePoint.map {
                simd_distance($0, live) >= Self.poseEpsilonM
            } ?? true
            if moved { basePoint = live }
            return basePoint
        }
        let now = ProcessInfo.processInfo.systemUptime
        let staleSince = poseStaleSince ?? now
        poseStaleSince = staleSince
        if now - staleSince >= ActiveSamplingPlot.trackingGraceSeconds {
            if !trackingLost { trackingLost = true }
            basePoint = nil
        }
        return basePoint
    }

    private func removeAnchor(using session: ARKitSessionManager) {
        if let anchorID { session.removeWorldAnchor(id: anchorID) }
        anchorID = nil
    }

    /// Movement below this (1 mm) does not re-publish the base or rebuild
    /// marker geometry. Matches the plot's `poseEpsilonM`.
    private static let poseEpsilonM: Float = 0.001

    // MARK: - Geometry

    /// Selected height above the live base. Ticks and label share this
    /// projection and disappear together when the anchor is unavailable.
    public var heightWorldPoint: SIMD3<Float>? {
        guard stage == .placed, !trackingLost else { return nil }
        return basePoint.map { height.point(above: $0) }
    }

    /// The point the assembly is drawn from — the anchored base once placed,
    /// the live crosshair hit while aiming.
    private var drawPoint: SIMD3<Float>? {
        switch stage {
        case .off:     return nil
        case .aiming:  return ghostPoint
        case .placed:  return basePoint
        }
    }

    // Stable ids so `ARCameraView` diffs these anchors instead of rebuilding
    // their meshes on every body evaluation. Hex-only UUIDs — see the note on
    // `DBHScanScreen.cylinderMarkerId`.
    private static let baseSphereId =
        UUID(uuidString: "00B4EA17-0000-0000-0000-000000000001") ?? UUID()
    private static let riserId =
        UUID(uuidString: "00B4EA17-0000-0000-0000-000000000002") ?? UUID()
    private static let ringId =
        UUID(uuidString: "00B4EA17-0000-0000-0000-000000000003") ?? UUID()
    /// Preview dot while aiming; riser and ring stay for the lifetime of the
    /// tracked ground anchor. No timer hides an otherwise valid guide.
    public func markers() -> [ARSceneMarker] {
        guard let base = drawPoint else { return [] }
        let white = SIMD4<Float>(1, 1, 1, stage == .placed ? 0.9 : 0.45)
        var markers = [ARSceneMarker(id: Self.baseSphereId, worldPosition: base,
                                    shape: .sphere(radiusM: 0.015), colorRGBA: white)]
        if stage == .placed {
            let h = Float(height.meters)
            markers.append(ARSceneMarker(id: Self.riserId,
                worldPosition: base + SIMD3<Float>(0, h / 2, 0),
                shape: .cylinder(radiusM: 0.004, heightM: h), colorRGBA: white))
            markers.append(ARSceneMarker(id: Self.ringId,
                worldPosition: base + SIMD3<Float>(0, h, 0),
                shape: .torus(radiusM: 0.35, tubeM: 0.01), colorRGBA: white))
        }
        return markers
    }
}
