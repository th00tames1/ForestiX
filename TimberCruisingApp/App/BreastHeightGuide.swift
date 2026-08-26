// Developer-mode BREAST-HEIGHT GUIDE — where 1.37 m is, drawn in the world.
//
// A phone cannot know it is reading the stem AT breast height, and "about
// chest high" is what a cruiser is otherwise left with. This puts the height
// on screen: a sphere at the tree base, a white line up from it to
// `Units.breastHeightM`, and a flat ring at the top the cruiser can bring
// around the trunk to see exactly where breast height crosses it.
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
        /// and sees a ghost of the assembly where it would land.
        case aiming
        /// Base anchored; the assembly is drawn at the anchor's live pose.
        case placed
    }

    @Published public private(set) var stage: Stage = .off

    /// The base point as ARKit is currently correcting it — not the frozen
    /// coordinate the placing raycast returned. nil while nothing is placed
    /// and once tracking has been lost past the grace window, and everything
    /// drawn is drawn from THIS, so nil means nothing is drawn.
    @Published public private(set) var basePoint: SIMD3<Float>?

    /// Live crosshair hit while aiming — the ghost preview's position, and
    /// what `place(hit:using:)` would anchor. nil when the ray misses, which
    /// draws nothing rather than guessing.
    @Published public private(set) var ghostPoint: SIMD3<Float>?

    /// True while a base is placed but its pose is not being corrected, i.e.
    /// the geometry is hidden. The screen says so in words.
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

    /// "1.37 m" or "4.5 ft" — the breast-height convention in the cruiser's
    /// own unit.
    ///
    /// THE IMPERIAL STRING IS NOT DERIVED FROM THE CONSTANT, on purpose.
    /// `Units.metersToFeet(Units.breastHeightM)` is 4.4948, which a formatter
    /// renders "4.49 ft" — and that invites the reader to think the app
    /// rounded a metric standard into an imperial one. It did not: 4.5 ft is
    /// the US definition and 1.37 m is that same height written in metres.
    /// Both are spelled out, which is also what `ScanMetadataSheet`'s
    /// `breastHeightWord` does for the position footer.
    public static func label(in system: UnitSystem) -> String {
        system == .metric ? "1.37 m" : "4.5 ft"
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
    /// the toggle or developer mode goes off and on screen teardown — an
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

    /// Drop the base and go back to aiming — the "Clear base" button, and the
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

    /// Anchor the base at a crosshair hit. Returns false when the session
    /// refuses to make an anchor, so the caller can say the placement failed
    /// in the words every other crosshair-to-ground placement uses.
    @discardableResult
    public func place(hit: SIMD3<Float>,
                      using session: ARKitSessionManager) -> Bool {
        removeAnchor(using: session)
        guard let id = session.addWorldAnchor(
            at: hit, name: "forestix.breastHeight.base")
        else {
            basePoint = nil
            return false
        }
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

    /// Movement below this (1 mm) does not re-publish the base — a marker
    /// list rebuilt at poll rate would churn the ring mesh for nothing. The
    /// plot's `poseEpsilonM`, and Android's `POSE_EPSILON_M`.
    private static let poseEpsilonM: Float = 0.001

    // MARK: - Geometry

    /// World point the ring sits at: breast height above the live base.
    /// The label is drawn at the projection of this, so both are nil
    /// together and the guide never leaves a floating number behind.
    public var ringWorldPoint: SIMD3<Float>? {
        drawPoint.map { $0 + SIMD3<Float>(0, Float(Units.breastHeightM), 0) }
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

    /// The three shapes of the guide, or an empty list whenever there is
    /// nothing honest to draw (gate off, ray missing the ground, tracking
    /// lost past the grace).
    ///
    /// While aiming they are the same three shapes at 0.35 alpha — the ghost
    /// precedent the sampling screen set for its plot pillar, so the cruiser
    /// sees where the base will land before committing to it.
    public func markers() -> [ARSceneMarker] {
        guard let base = drawPoint else { return [] }
        let h = Float(Units.breastHeightM)
        let alpha: Float = stage == .placed ? 1.0 : 0.35
        let white = SIMD4<Float>(1, 1, 1, alpha)
        return [
            // Base — the only piece that may scale with distance. It marks a
            // place, not a length, so growing it across a stand costs nothing
            // and keeps it findable.
            ARSceneMarker(id: Self.baseSphereId,
                          worldPosition: base,
                          shape: .sphere(radiusM: 0.06),
                          colorRGBA: white,
                          scalesWithDistance: true),
            // Riser — centred, because a cylinder is centred on its position,
            // so half the height puts its foot on the base and its cap at
            // breast height.
            //
            // scalesWithDistance is FALSE here and on the ring, and that is
            // the whole point of the feature: the scaling factor is applied
            // to every child of a marker's anchor, so a scaled riser draws a
            // height that is not 1.37 m — the exact error the guide exists to
            // prevent.
            ARSceneMarker(id: Self.riserId,
                          worldPosition: base + SIMD3<Float>(0, h / 2, 0),
                          shape: .cylinder(radiusM: 0.012, heightM: h),
                          colorRGBA: white),
            // Breast height itself: a DOUGHNUT, not the flat rim the plot
            // boundary uses.
            //
            // It was that rim, and the rim is the wrong shape here for the
            // one reason that matters: this marker sits at 1.37 m, so a
            // cruiser holding the phone at chest height looks at it almost
            // exactly edge-on — and a flat disc seen edge-on is a hairline
            // across the bark, at the moment it has to be read. A tube looks
            // the same from every direction. 5 cm of tube is the band width
            // the rim drew at, so nothing about the reading changes.
            ARSceneMarker(id: Self.ringId,
                          worldPosition: base + SIMD3<Float>(0, h, 0),
                          shape: .torus(radiusM: 0.35, tubeM: 0.05),
                          colorRGBA: white),
        ]
    }
}
