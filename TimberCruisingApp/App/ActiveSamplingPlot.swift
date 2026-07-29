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
    ///
    /// Built through a closure so the shared instance — and only it — hooks
    /// the shared AR session's pause. See `observeSessionPause`. Privately
    /// owned instances (tests, previews) stay inert.
    public static let shared: ActiveSamplingPlot = {
        let store = ActiveSamplingPlot()
        store.observeSessionPause()
        return store
    }()

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

    /// When the centre stopped being corrected, on a MONOTONIC clock; nil
    /// while it is being corrected.
    ///
    /// `ProcessInfo.systemUptime`, not `Date()`: this is an elapsed interval,
    /// and `Date()` is an absolute wall clock that an NTP correction or a
    /// manual clock/timezone change can step forwards or backwards mid
    /// session. A backwards step would push the deadline arbitrarily far out
    /// and leave the plot drawn at an uncorrected pose long past half a
    /// second; a forwards step would expire the window instantly and blink
    /// the ring off while tracking was fine. `systemUptime` is derived from
    /// the mach timebase and cannot be set. Android's sibling uses
    /// `SystemClock.elapsedRealtime()` for the same reason.
    private var poseStaleSince: TimeInterval?

    /// Persisted cruise `Plot` whose centre the current anchor marks —
    /// stamped by the cruise Start-plot save, nil for quick-measure
    /// rings. Lets the plot mini-map trust the anchor path only when
    /// the anchor actually belongs to the plot being measured (a peek
    /// "Add tree" can target an OLDER open plot than the last-placed
    /// ring).
    @Published public private(set) var linkedCruisePlotID: UUID?

    /// Quick-measure history row this ring was saved as, nil until the
    /// sampling screen's Save writes one and again on the next placement.
    ///
    /// FIELD REPORT 12 — "Edit plot" re-opens the sampling screen on the ring
    /// already placed, and its Save used to append unconditionally. Three
    /// radius tweaks during one plot wrote three `.samplingPlot` rows for one
    /// physical ring, and those rows reach the field log and the CSV the
    /// validation study reads. One ring is one row: Save updates the row it
    /// already has. The cruise path was given the same guard at
    /// `MapHomeScreen+Cruise.plotSetupCover`; this is the quick half of it.
    @Published public private(set) var linkedQuickEntryID: UUID?

    public init() {}

    /// Record a freshly placed plot (replaces any previous one — the
    /// caller removes the old ARAnchor from the session).
    public func place(anchorID: UUID, radiusM: Double) {
        plot = Plot(anchorID: anchorID, radiusM: radiusM, placedAt: Date())
        linkedCruisePlotID = nil
        // A NEW ring is a new record — the next Save writes its own row
        // rather than overwriting the one the previous ring left behind.
        linkedQuickEntryID = nil
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

    /// Associate the placed ring with the quick-measure row Save just wrote,
    /// so a later Save on the SAME ring updates that row instead of adding a
    /// second one. No-op while nothing is placed.
    public func link(quickEntryID: UUID) {
        guard plot != nil else { return }
        linkedQuickEntryID = quickEntryID
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
        linkedQuickEntryID = nil
        centreWorld = nil
        trackingLost = false   // nothing left to have lost track OF
        poseStaleSince = nil
    }

    // MARK: - Session pause

    /// Drop the tracked centre the instant the shared AR session pauses.
    ///
    /// `centreWorld` and `trackingLost` are otherwise only ever written by
    /// `refreshTrackedCentre`, which runs from an AR screen's poll — so when
    /// the last AR screen leaves or the app backgrounds, the last centre
    /// survived the gap untouched. On re-entry the ring, the pillar, the DBH
    /// border chip and the mini-map YOU dot were all drawn from that
    /// pre-pause pose for up to a poll interval plus the whole grace window,
    /// while ARKit was still relocalizing. That is a plot drawn at a stale
    /// pose, which is the one thing this class exists to prevent.
    ///
    /// Android does the same thing in `ArSessionHub.detach`, which owns the
    /// session and the plot together; here the session lives in Sensors, so
    /// it calls back.
    private func observeSessionPause() {
        ARKitSessionManager.shared.onSessionPaused = { [weak self] in
            self?.invalidateTrackedCentre()
        }
    }

    /// Forget where the centre is, WITHOUT forgetting that a plot is placed:
    /// the anchor is still in the session's world map and a healthy frame
    /// after the next attach brings the plot straight back. Until then the
    /// geometry is hidden and the screens say tracking is lost — same words
    /// they use for a tracking dip, because it is the same fact.
    ///
    /// This nils the centre; what keeps it nil is at the session manager.
    /// `refreshTrackedCentre` runs on each screen's own poll and would
    /// happily re-fill it from the next `trackedWorldAnchorPosition` read —
    /// which, in the tens of milliseconds between the next attach's
    /// `session.run` and ARKit's first new frame, could still be answering
    /// out of the last PRE-PAUSE frame. That frame's camera read `.normal`
    /// and its anchor transform is stale, so the poll would take the success
    /// branch, clear `trackingLost`, and redraw the ring at the pose the
    /// cruiser walked away from — undoing this invalidation in one tick.
    /// `ARKitSessionManager.liveFrameSincePause` closes that window: a frame
    /// captured before the current run is not read at all. Android gets the
    /// same property from `ArSessionHub.detach` nil-ing `controller.frame`.
    private func invalidateTrackedCentre() {
        poseStaleSince = nil
        centreWorld = nil
        trackingLost = plot != nil
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
        let now = ProcessInfo.processInfo.systemUptime
        let staleSince = poseStaleSince ?? now
        poseStaleSince = staleSince
        if now - staleSince >= Self.trackingGraceSeconds {
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
