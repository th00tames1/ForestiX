// Field round 8 — app-scoped ACTIVE sampling plot.
//
// The sampling-plot screen used to keep its centre in a per-screen
// @State, so the ring vanished the moment the cruiser opened DBH or
// Height (and the centre drifted, because it was a raw world coordinate
// that never benefited from ARKit's world-map corrections). The plot is
// now:
//
//   • pinned to a REAL ARKit ARAnchor added to the shared AR session
//     (`ARKitSessionManager.shared`) — ARKit keeps the anchor fixed to
//     the physical ground through drift compensation / relocalization;
//   • stored here, app-scoped, as {anchorID, radiusM, placedAt} so every
//     AR measure screen can render it while the app runs.
//
// The MAP HOME draws its plot from the persisted cruise `Plot` instead —
// an ARKit anchor is a world-session coordinate, not a place on Earth,
// so it cannot be projected onto a map at all. This ring and that plot
// are the same physical circle seen from two frames.
//
// Replaced on a new placement; cleared by Reset on the sampling screen.
// Deliberately NOT persisted across app restarts — the anchor lives in
// the AR session's world map, which dies with the process, so a restored
// plot would have no anchor to attach to.
//
// DBH + Height render the active plot as a subdued (≈0.5 alpha) ring +
// centre pole under their own measurement markers via
// `subduedOverlayMarkers(for:centre:)`. Distance deliberately does not.
//
// THE CENTRE IS READ, NOT PINNED. Every screen's markers carry plain world
// positions taken from `centreWorld`, which each AR screen refreshes from
// the anchor on its own poll (`refreshTrackedCentre`). Two reasons:
//
//  1. It is the only way to obey the rule. RealityKit's ARAnchor-pinned
//     entities keep drawing at the anchor's last transform when ARKit stops
//     tracking, and a plot in the wrong place is worse than no plot — so
//     "hide it when tracking is lost" has to be decided here, and once we
//     are reading the tracking state per poll we may as well read the pose.
//  2. FIELD REPORT 14 — the ring and pillar never appeared on the DBH or
//     Height screens at all. Those screens build their ARView over a
//     session where the plot's ARAnchor ALREADY exists, and a
//     `AnchorEntity(.anchor(identifier:))` added to a scene that never saw
//     the anchor arrive has nothing to bind to. Reading the pose ourselves
//     removes the ordering dependency entirely.
//
// It is the same shape as the Android sibling, where ArSessionHub pushes
// the anchor pose into the plot nodes every frame and hides them when
// ARCore stops correcting it.

import Foundation
import AR
import Sensors
import simd

@MainActor
public final class ActiveSamplingPlot: ObservableObject {

    /// App-scoped singleton — the one ACTIVE plot for this app run.
    public static let shared = ActiveSamplingPlot()

    public struct Plot: Equatable {
        /// Identifier of the ARAnchor pinned at the plot centre in the
        /// shared AR session. The anchor's LIVE transform (not a frozen
        /// coordinate) is the plot centre.
        public let anchorID: UUID
        /// Sampling radius in metres (tracks the slider).
        public var radiusM: Double
        public let placedAt: Date

        public init(anchorID: UUID, radiusM: Double, placedAt: Date) {
            self.anchorID = anchorID
            self.radiusM = radiusM
            self.placedAt = placedAt
        }
    }

    @Published public private(set) var plot: Plot?

    /// Last plot centre ARKit was actually correcting, in world coordinates.
    /// nil when no plot is placed or when tracking has been lost for longer
    /// than `trackingGraceSeconds`. Every plot marker on every screen is
    /// drawn from THIS — so when it is nil, nothing is drawn.
    @Published public private(set) var centreWorld: SIMD3<Float>?

    /// True while a plot is placed but its centre is not being corrected,
    /// i.e. the geometry is hidden. Screens say so in words.
    @Published public private(set) var trackingLost = false

    /// How long the centre may go uncorrected before the plot is hidden.
    /// Long enough to ride out the routine sub-second tracking dips that
    /// made an immediate hide flicker; short enough that the world frame
    /// cannot have shifted far under a plot still on screen. Kept EQUAL to
    /// the Android `PLOT_POSE_GRACE_MS` — it is one rule, in two places.
    public static let trackingGraceSeconds: TimeInterval = 0.5

    /// Movement below this (1 mm) does not re-publish the centre — a marker
    /// list rebuilt at poll rate would churn the ring mesh for nothing.
    /// Android's `POSE_EPSILON_M`.
    private static let poseEpsilonM: Float = 0.001

    /// When the centre stopped being corrected; nil while it is.
    private var poseStaleSince: Date?

    /// Persisted cruise `Plot` whose centre the current anchor marks —
    /// stamped by the cruise Start-plot save, nil for quick-measure
    /// rings. Lets the plot mini-map trust the anchor path only when
    /// the anchor actually belongs to the plot being measured (a peek
    /// "Add tree" can target an OLDER open plot than the last-placed
    /// ring).
    @Published public private(set) var linkedCruisePlotID: UUID?

    public init() {}

    /// Record a freshly placed plot (replaces any previous one — the
    /// caller removes the old ARAnchor from the session).
    public func place(anchorID: UUID, radiusM: Double) {
        plot = Plot(anchorID: anchorID, radiusM: radiusM, placedAt: Date())
        linkedCruisePlotID = nil
        // The centre this anchor marks is unknown until a poll reads it off
        // a tracked frame. Nothing is drawn in the meantime — which is one
        // poll tick, since the tap that got here needed tracking anyway.
        centreWorld = nil
        trackingLost = false
        poseStaleSince = nil
    }

    /// Associate the placed ring with the cruise `Plot` it was just
    /// saved as. No-op while nothing is placed.
    public func link(cruisePlotID: UUID) {
        guard plot != nil else { return }
        linkedCruisePlotID = cruisePlotID
    }

    /// Keep the stored radius in sync with the sampling screen's slider.
    /// No-op while no plot is placed.
    public func updateRadius(_ radiusM: Double) {
        guard var p = plot, p.radiusM != radiusM else { return }
        p.radiusM = radiusM
        plot = p
    }

    /// Reset — the caller removes the ARAnchor from the session.
    public func clear() {
        plot = nil
        linkedCruisePlotID = nil
        centreWorld = nil
        trackingLost = false   // nothing left to have lost track OF
        poseStaleSince = nil
    }

    // MARK: - Tracked centre

    /// Re-read the plot centre from its ARAnchor and republish it, or hide
    /// the plot once the pose has gone uncorrected for longer than
    /// `trackingGraceSeconds`. Every AR screen showing the plot calls this
    /// on its own poll; it is the ONE place the hide/show rule lives.
    ///
    /// Inside the grace window the last corrected centre is kept, which is
    /// what stops a routine tracking dip from blinking the ring. Past it the
    /// centre goes nil and the geometry goes with it: an uncorrected anchor
    /// translation belongs to a world frame that is no longer the one the
    /// camera is in.
    @discardableResult
    public func refreshTrackedCentre(
        using session: ARKitSessionManager
    ) -> SIMD3<Float>? {
        guard let plot else {
            centreWorld = nil
            trackingLost = false
            poseStaleSince = nil
            return nil
        }
        if let live = session.trackedWorldAnchorPosition(id: plot.anchorID) {
            poseStaleSince = nil
            if trackingLost { trackingLost = false }
            let moved = centreWorld.map {
                simd_distance($0, live) >= Self.poseEpsilonM
            } ?? true
            if moved { centreWorld = live }
            return centreWorld
        }
        let now = Date()
        let staleSince = poseStaleSince ?? now
        poseStaleSince = staleSince
        if now.timeIntervalSince(staleSince) >= Self.trackingGraceSeconds {
            if !trackingLost { trackingLost = true }
            centreWorld = nil
        }
        return centreWorld
    }

    // MARK: - Subdued overlay markers (DBH / Height)

    // Stable ids so the overlay anchors are diffed, never rebuilt
    // per body evaluation. Hex-only UUIDs (see DBHScanScreen note).
    private static let overlayRingId =
        UUID(uuidString: "00000000-5A11-0AAA-0000-000000000001") ?? UUID()
    private static let overlayPoleId =
        UUID(uuidString: "00000000-5A11-0AAA-0000-000000000002") ?? UUID()

    /// The active plot rendered as non-interactive context inside the
    /// DBH / Height screens: the sampling ring in its existing marker
    /// style dimmed to 0.5 alpha, plus the centre pole. `centre` is the
    /// tracked world centre from `centreWorld` — callers hold nothing back
    /// when it is nil, they draw nothing at all.
    public static func subduedOverlayMarkers(
        for plot: Plot,
        centre: SIMD3<Float>
    ) -> [ARSceneMarker] {
        [
            // Boundary ring — same cyan as the sampling screen, half alpha.
            ARSceneMarker(id: overlayRingId,
                          worldPosition: centre + SIMD3<Float>(0, 0.02, 0),
                          shape: .ring(radiusM: Float(plot.radiusM),
                                       thicknessM: 0.4),
                          colorRGBA: SIMD4<Float>(0.2, 0.85, 1, 0.5)),
            // Centre pole — white, half alpha. 3 cm, not 5: the same pole
            // the sampling screen draws, thinned per field report F1 (it
            // read as a fence post and hid the trunk behind it). The two
            // MUST stay equal — it is one pillar seen from two screens.
            ARSceneMarker(id: overlayPoleId,
                          worldPosition: centre + SIMD3<Float>(0, 0.6, 0),
                          shape: .cylinder(radiusM: 0.03, heightM: 1.2),
                          colorRGBA: SIMD4<Float>(1, 1, 1, 0.5)),
        ]
    }
}
