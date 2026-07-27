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
// `subduedOverlayMarkers(for:)`. Distance deliberately does not.

import Foundation
import AR

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
    /// style dimmed to 0.5 alpha, plus the centre pole. Both markers are
    /// pinned to the plot's ARAnchor (offsets in anchor-local space), so
    /// they track ARKit's world-map corrections with zero per-frame work.
    public static func subduedOverlayMarkers(for plot: Plot) -> [ARSceneMarker] {
        [
            // Boundary ring — same cyan as the sampling screen, half alpha.
            ARSceneMarker(id: overlayRingId,
                          worldPosition: SIMD3<Float>(0, 0.02, 0),
                          shape: .ring(radiusM: Float(plot.radiusM),
                                       thicknessM: 0.4),
                          colorRGBA: SIMD4<Float>(0.2, 0.85, 1, 0.5),
                          worldAnchorID: plot.anchorID),
            // Centre pole — white, half alpha. 3 cm, not 5: the same pole
            // the sampling screen draws, thinned per field report F1 (it
            // read as a fence post and hid the trunk behind it). The two
            // MUST stay equal — it is one pillar seen from two screens.
            ARSceneMarker(id: overlayPoleId,
                          worldPosition: SIMD3<Float>(0, 0.6, 0),
                          shape: .cylinder(radiusM: 0.03, heightM: 1.2),
                          colorRGBA: SIMD4<Float>(1, 1, 1, 0.5),
                          worldAnchorID: plot.anchorID),
        ]
    }
}
