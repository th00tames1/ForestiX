// Plot mini-map — a compact plot-relative overview card shown while a
// plot is active, so the cruiser sees where they've measured without
// leaving AR.
//
// LOCKED design — a rounded-square dark-glass card, intentionally with no
// walked-trail breadcrumb; the card answers "where in THIS plot have I
// measured", not "where have I walked":
//   • rounded SQUARE card (ForestixRadius.card), 116 pt, TOP-RIGHT —
//     trailing 16 / top 22, the same row as the GPS badge on the left;
//   • AR dark-glass styling (black 0.55, white 0.18 border at 0.5 pt);
//   • schematic NORTH-UP content: the plot ring in the sampling ring's
//     AR cyan scaled to 78 % of the card, a small white centre dot, an
//     "N" tick at the top inside edge, the cruiser as the map home's
//     you-dot blue with a heading wedge, and the plot's measured trees
//     as confidence-tinted dots. Intentionally no walked-trail breadcrumb —
//     the card is plot-relative, not a track log;
//   • header "PLOT 2 · 5" (plot number · tree count) top-left inside,
//     "r 12 m" bottom-right inside.
//
// INTERACTION (field report F11): hosts that can re-open plot setup pass
// `onTap`, and the card becomes a button — cyan edge + a pencil badge, the
// app's existing "you can edit this" language. Without `onTap` it renders
// exactly as before and swallows nothing.
//
// POSITION DATA
//   YOU   — the camera's offset from the plot's AR anchor when the
//           shared session still holds it (accurate, drift-corrected).
//           The shared session runs `worldAlignment = .gravityAndHeading`,
//           so the ARKit world frame is north-aligned BY CONSTRUCTION
//           (+X = east, −Z = true north) — no extra AR→ENU yaw needed;
//           that alignment is the robust option chosen here. Falls back
//           to the GPS-ENU offset (current fix vs the plot centre
//           lat/lon) when no anchor is available (plot recorded via
//           GPS averaging, app restarted, anchor belongs to another
//           plot).
//   TREES — GPS-ENU offsets of the plot's trees (they carry the fix
//           captured at Accept). Dots are clamped to the ring edge
//           when outside the plot.
//
// Kept cheap: the live YOU/heading sample is polled on a 0.2 s timer
// (the sampling screen's existing cadence; GPS itself ticks at ~1 Hz)
// and published ONLY when the quantized value changes — no per-frame
// SwiftUI invalidation, and the AR session manager is read without
// being observed.

import SwiftUI
import Geo
import Positioning
import Sensors

// MARK: - Payload

/// Everything static the widget needs about the plot being measured.
/// Cruise flows pass the persisted plot + its trees; the quick-measure
/// flows pass just the active sampling ring (no number, no trees).
public struct PlotMiniMapInfo: Equatable {

    public struct TreeDot: Equatable {
        public let latitude: Double
        public let longitude: Double
        /// True for yellow/red confidence — rendered warn-amber;
        /// green renders ok-green.
        public let warn: Bool

        public init(latitude: Double, longitude: Double, warn: Bool) {
            self.latitude = latitude
            self.longitude = longitude
            self.warn = warn
        }
    }

    /// Persisted cruise `Plot` id — nil for the quick ActiveSamplingPlot.
    /// Used to decide whether the AR anchor (which marks the LAST placed
    /// ring) actually belongs to this plot.
    public let plotID: UUID?
    /// Cruise plot number for the header; nil renders a plain "PLOT".
    public let plotNumber: Int?
    public let radiusM: Double
    /// Plot centre fix — the ENU origin for trees and the GPS fallback
    /// for YOU. nil for the quick sampling ring (AR anchor only).
    public let centerLat: Double?
    public let centerLon: Double?
    /// Header count — ALL live trees, including ones without a fix.
    public let treeCount: Int
    public let trees: [TreeDot]

    public init(plotID: UUID?,
                plotNumber: Int?,
                radiusM: Double,
                centerLat: Double?,
                centerLon: Double?,
                treeCount: Int,
                trees: [TreeDot]) {
        self.plotID = plotID
        self.plotNumber = plotNumber
        self.radiusM = radiusM
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.treeCount = treeCount
        self.trees = trees
    }
}

// MARK: - Widget

public struct PlotMiniMapWidget: View {

    public let info: PlotMiniMapInfo

    /// FIELD REPORT F11 — the card is a WAY BACK INTO PLOT SETUP. Once the
    /// first (+) had placed the sampling plot there was no route to change
    /// its radius or centre; tapping the preview of the thing you want to
    /// edit is the obvious one. nil keeps the card inert (quick-measure, and
    /// any host with no plot to edit), exactly as it used to be.
    public let onTap: (() -> Void)?

    @StateObject private var model = PlotMiniMapLiveModel()

    /// Card side (locked).
    private static let side: CGFloat = 116
    /// Ring diameter as a fraction of the card side (locked).
    private static let ringFraction: CGFloat = 0.78
    /// The sampling ring's AR cyan (0.2 / 0.85 / 1).
    private static let ringCyan = Color(red: 0.2, green: 0.85, blue: 1.0)
    /// The map home's GPS you-dot blue (#3B82C4).
    private static let youBlue = Color(red: 0.231, green: 0.510, blue: 0.769)

    public init(info: PlotMiniMapInfo, onTap: (() -> Void)? = nil) {
        self.info = info
        self.onTap = onTap
    }

    private var ringRadiusPt: CGFloat { Self.side * Self.ringFraction / 2 }
    private var centrePt: CGFloat { Self.side / 2 }

    public var body: some View {
        if let onTap {
            Button(action: onTap) { card }
                .buttonStyle(.plain)
                .accessibilityLabel("Edit plot. \(headerText.lowercased()), radius \(Int(info.radiusM)) metres")
                .accessibilityHint("Reopens plot setup to change the radius or centre")
                .accessibilityIdentifier("plotMiniMap")
        } else {
            card
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Plot map. \(headerText.lowercased()), radius \(Int(info.radiusM)) metres")
                .accessibilityIdentifier("plotMiniMap")
        }
    }

    private var card: some View {
        ZStack {
            // Plot ring — the boundary, always centred (the map is
            // plot-relative, not user-centred).
            Circle()
                .stroke(Self.ringCyan.opacity(0.9), lineWidth: 1.5)
                .frame(width: ringRadiusPt * 2, height: ringRadiusPt * 2)
                .position(x: centrePt, y: centrePt)

            // North tick — just inside the ring's top point (north-up
            // orientation is the differentiator worth labelling).
            Text("N")
                .font(.system(size: 8, weight: .medium, design: .monospaced))
                .foregroundStyle(.white.opacity(0.75))
                .position(x: centrePt, y: centrePt - ringRadiusPt + 7)

            // Plot centre dot.
            Circle()
                .fill(Color.white)
                .frame(width: 3, height: 3)
                .position(x: centrePt, y: centrePt)

            // Measured trees — confidence-tinted 5 pt dots.
            ForEach(Array(info.trees.enumerated()), id: \.offset) { _, tree in
                if let p = treePoint(tree) {
                    Circle()
                        .fill(tree.warn
                              ? ForestixPalette.confidenceWarn
                              : ForestixPalette.confidenceOk)
                        .frame(width: 5, height: 5)
                        .position(p)
                }
            }

            // YOU — you-dot blue with a heading wedge when available.
            if let you = model.you {
                youMark(headingDeg: you.headingDeg)
                    .position(point(eastM: you.eastM, northM: you.northM))
            }
        }
        .frame(width: Self.side, height: Self.side)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(Color.black.opacity(0.55)))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                // Tappable cards carry the brighter AR-cyan edge the rest of
                // the plot chrome uses for "this is the plot, and you can
                // touch it"; inert ones keep the old hairline.
                .stroke(onTap == nil ? .white.opacity(0.18)
                                     : Self.ringCyan.opacity(0.75),
                        lineWidth: onTap == nil ? 0.5 : 1))
        .overlay(alignment: .topLeading) {
            Text(headerText)
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white.opacity(0.85))
                .padding(.leading, 7)
                .padding(.top, 6)
        }
        .overlay(alignment: .topTrailing) {
            // The affordance: the app's standard "edit this" pencil, in the
            // one corner the card's content never occupies. Only drawn when
            // there is somewhere to go.
            if onTap != nil {
                Image(systemName: "pencil.circle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(.trailing, 6)
                    .padding(.top, 5)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Text(String(format: "r %.0f m", info.radiusM))
                .font(.system(size: 8, weight: .medium, design: .monospaced))
                .foregroundStyle(.white.opacity(0.75))
                .padding(.trailing, 7)
                .padding(.bottom, 5)
        }
        .contentShape(RoundedRectangle(cornerRadius: ForestixRadius.card,
                                       style: .continuous))
        .onAppear { model.start(info: info) }
        .onDisappear { model.stop() }
        .onChange(of: info) { _, new in model.update(info: new) }
    }

    private var headerText: String {
        if let n = info.plotNumber { return "PLOT \(n) · \(info.treeCount)" }
        return "PLOT"
    }

    // MARK: Geometry (north-up: +east → right, +north → up)

    /// Card point for a plot-relative ENU offset, clamped to the ring
    /// edge when the offset is outside the plot.
    private func point(eastM: Double, northM: Double) -> CGPoint {
        let radius = max(info.radiusM, 0.5)
        var e = eastM
        var n = northM
        let d = (e * e + n * n).squareRoot()
        if d > radius, d > 0 {
            let f = radius / d
            e *= f
            n *= f
        }
        let scale = ringRadiusPt / CGFloat(radius)
        return CGPoint(x: centrePt + CGFloat(e) * scale,
                       y: centrePt - CGFloat(n) * scale)
    }

    /// ENU offset of a tree from the plot centre; nil when the plot has
    /// no centre fix to measure from.
    private func treePoint(_ tree: PlotMiniMapInfo.TreeDot) -> CGPoint? {
        guard let lat = info.centerLat, let lon = info.centerLon else {
            return nil
        }
        let enu = CoordinateConversions.toENU(
            point: .init(latitude: tree.latitude, longitude: tree.longitude),
            origin: .init(latitude: lat, longitude: lon))
        return point(eastM: enu.east, northM: enu.north)
    }

    /// The cruiser: 7 pt you-blue dot, plus a small wedge orbiting at
    /// the compass heading (0° = up = north on this north-up map).
    private func youMark(headingDeg: Double?) -> some View {
        ZStack {
            if let h = headingDeg {
                MiniMapWedge()
                    .fill(Self.youBlue)
                    .frame(width: 7, height: 5)
                    .offset(y: -6.5)
                    .rotationEffect(.degrees(h))
            }
            Circle()
                .fill(Self.youBlue)
                .frame(width: 7, height: 7)
                .overlay(Circle().stroke(.white.opacity(0.85), lineWidth: 1))
        }
        .frame(width: 20, height: 20)
    }
}

/// Small upward-pointing triangle used as the heading wedge.
private struct MiniMapWedge: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        p.closeSubpath()
        return p
    }
}

// MARK: - Live position model

/// Owns the 0.2 s poll that turns (AR anchor | GPS fix) + heading into
/// the YOU sample. Deliberately does NOT observe the AR session manager
/// (it publishes at 60 Hz) or its own LocationService — both are read
/// on the timer tick, and `you` is published only when the quantized
/// value actually changes.
@MainActor
final class PlotMiniMapLiveModel: ObservableObject {

    struct You: Equatable {
        var eastM: Double
        var northM: Double
        var headingDeg: Double?
    }

    @Published private(set) var you: You?

    private var info: PlotMiniMapInfo?
    private var timer: Timer?
    /// App-shared service for fix + compass heading (refcounted
    /// acquire/release) — same self-contained pattern as
    /// GPSAccuracyBadge, so the widget lights up on every host screen
    /// without AppEnvironment plumbing. Never observed.
    private let location = LocationService.shared

    func start(info: PlotMiniMapInfo) {
        self.info = info
        location.requestAuthorization()
        location.acquire()
        timer?.invalidate()
        // 0.2 s — the sampling screen's existing poll cadence. Each tick
        // is a handful of float ops; GPS underneath only refreshes ~1 Hz.
        timer = Timer.scheduledTimer(withTimeInterval: 0.2,
                                     repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.tick() }
        }
        tick()
    }

    func update(info: PlotMiniMapInfo) {
        self.info = info
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        location.release()
    }

    private func tick() {
        guard let info else {
            if you != nil { you = nil }
            return
        }
        var next: You?
        let heading = location.headingTrueDeg.map { ($0 / 2).rounded() * 2 }

        // AR-anchor path — accurate and north-aligned by construction
        // (the shared session's `.gravityAndHeading` world alignment:
        // +X = east, −Z = true north). Only taken when the live anchor
        // actually marks THIS plot's centre.
        let store = ActiveSamplingPlot.shared
        let session = ARKitSessionManager.shared
        let anchorMatchesPlot = info.plotID == nil
            || store.linkedCruisePlotID == info.plotID
        if anchorMatchesPlot,
           let active = store.plot,
           let centre = session.worldAnchorPosition(id: active.anchorID),
           let cam = session.currentCameraWorldPosition {
            next = You(eastM: Double(cam.x - centre.x),
                       northM: Double(-(cam.z - centre.z)),
                       headingDeg: heading)
        } else if let lat = info.centerLat, let lon = info.centerLon,
                  let fix = location.latestSnapshot
                        ?? LocationService.lastGlobalFix {
            // GPS-ENU fallback: current fix vs the plot centre.
            let enu = CoordinateConversions.toENU(
                point: .init(latitude: fix.latitude,
                             longitude: fix.longitude),
                origin: .init(latitude: lat, longitude: lon))
            next = You(eastM: enu.east,
                       northM: enu.north,
                       headingDeg: heading)
        }
        // Quantize to 5 cm so sub-jitter movement doesn't invalidate the
        // card; `you` publishes only on a real change.
        if var q = next {
            q.eastM = (q.eastM * 20).rounded() / 20
            q.northM = (q.northM * 20).rounded() / 20
            next = q
        }
        if you != next { you = next }
    }
}
