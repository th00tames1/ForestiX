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
// INTERACTION: hosts that can re-open plot setup pass `onEditPlot`, and
// the card becomes a button — cyan edge + a magnifier badge. Without it
// the card renders exactly as before and swallows nothing.
//
// The tap opens `PlotMapEnlargedView` (bottom of this file): the SAME
// drawing, centred and big, with the measured trees numbered. Field
// report F11 originally sent that tap straight into plot re-setup, which
// the field then corrected — a cruiser tapping a 116 pt map is asking to
// SEE it, not to rebuild the plot. Re-setup is still one control away,
// inside the enlarged view.
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
//   TREES — the recorded bearing + distance from the plot centre when
//           the tree record carries them, else the GPS-ENU offset of the
//           tree's own fix (captured at Accept) from the plot centre.
//           See `PlotMiniMapInfo.placedTrees`. Dots are clamped to the
//           ring edge when outside the plot; a tree with NEITHER source
//           is left out rather than drawn at the centre.
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

    /// One measured tree. Carries BOTH position sources the tree record
    /// can hold; `plotOffset` below picks whichever is actually
    /// populated. A dot with neither is not drawn (and is counted as
    /// unplaceable) rather than being parked at the centre, which would
    /// read as a real measurement standing on the plot pin.
    public struct TreeDot: Equatable {
        /// Tree number — the only label on the drawing, and the only one
        /// the cruiser can act on.
        public let number: Int
        /// GPS fix captured with the tree. nil when no fix was available
        /// at Accept.
        public let latitude: Double?
        public let longitude: Double?
        /// Plot-LOCAL polar position recorded on the tree record:
        /// compass bearing from the plot centre (0° = north, clockwise)
        /// and horizontal distance from it. Preferred over GPS when
        /// present — it is already in the frame this map draws.
        public let bearingFromCenterDeg: Double?
        public let distanceFromCenterM: Double?
        /// True for yellow/red confidence — rendered warn-amber;
        /// green renders ok-green.
        public let warn: Bool

        public init(number: Int,
                    latitude: Double?,
                    longitude: Double?,
                    bearingFromCenterDeg: Double?,
                    distanceFromCenterM: Double?,
                    warn: Bool) {
            self.number = number
            self.latitude = latitude
            self.longitude = longitude
            self.bearingFromCenterDeg = bearingFromCenterDeg
            self.distanceFromCenterM = distanceFromCenterM
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

    // MARK: Tree placement (ONE rule, shared by both map sizes)

    /// A tree resolved into the plot's own local frame: metres east and
    /// north of the plot centre, plus the number and tint the drawing
    /// needs. Produced only for trees that HAVE a position.
    public struct PlacedTree: Equatable {
        public let number: Int
        public let eastM: Double
        public let northM: Double
        public let warn: Bool
    }

    /// The plot's trees in plot-local metres, and how many had to be
    /// dropped for want of a position.
    ///
    /// SOURCE PREFERENCE, per tree: the recorded bearing + distance from
    /// the plot centre first — it is already expressed in this frame, so
    /// it needs no centre fix and survives a plot whose centre was placed
    /// by AR rather than GPS. Otherwise the tree's own GPS fix converted
    /// to an ENU offset from the plot centre, which is what the card has
    /// always drawn. A tree with neither is OMITTED, never defaulted to
    /// (0, 0): a dot on the centre pin is a lie the cruiser would read as
    /// a real stem.
    public var placedTrees: (placed: [PlacedTree], omitted: Int) {
        var placed: [PlacedTree] = []
        var omitted = 0
        for tree in trees {
            if let offset = plotOffset(for: tree) {
                placed.append(PlacedTree(number: tree.number,
                                         eastM: offset.east,
                                         northM: offset.north,
                                         warn: tree.warn))
            } else {
                omitted += 1
            }
        }
        return (placed, omitted)
    }

    /// Plot-local (east, north) metres for one tree, or nil when neither
    /// source is populated. Non-finite stored values are treated as
    /// missing — a NaN would otherwise place a dot nowhere at all.
    private func plotOffset(for tree: TreeDot)
        -> (east: Double, north: Double)? {
        if let bearing = tree.bearingFromCenterDeg,
           let distance = tree.distanceFromCenterM,
           bearing.isFinite, distance.isFinite, distance >= 0 {
            // Compass bearing: 0° = north, growing clockwise.
            let rad = bearing * .pi / 180
            return (east: distance * sin(rad), north: distance * cos(rad))
        }
        if let lat = tree.latitude, let lon = tree.longitude,
           let centerLat, let centerLon,
           lat.isFinite, lon.isFinite {
            let enu = CoordinateConversions.toENU(
                point: .init(latitude: lat, longitude: lon),
                origin: .init(latitude: centerLat, longitude: centerLon))
            guard enu.east.isFinite, enu.north.isFinite else { return nil }
            return (east: enu.east, north: enu.north)
        }
        return nil
    }
}

// MARK: - Widget

public struct PlotMiniMapWidget: View {

    public let info: PlotMiniMapInfo

    /// Re-open plot setup, to change the plot's radius or centre.
    ///
    /// FIELD REPORT F11 made the card a way back into plot setup: once the
    /// first (+) had placed the sampling plot there was no route to change
    /// it, and tapping the preview of the thing you want to edit is the
    /// obvious one. THE FIELD CORRECTED THAT: jumping straight into
    /// re-setup is too abrupt, because the cruiser tapping the card
    /// usually just wants a better look at the plot. So the tap now opens
    /// the ENLARGED plot view, and this closure is what its "Edit plot"
    /// button runs. nil keeps the card inert (quick-measure, and any host
    /// with no plot to edit), exactly as it used to be.
    public let onEditPlot: (() -> Void)?

    @StateObject private var model = PlotMiniMapLiveModel()

    /// The enlarged plot view, presented over the host screen.
    @State private var showingEnlarged = false
    /// "Edit plot" was pressed inside the enlarged view. The host's own
    /// re-setup presentation is started from the cover's `onDismiss`, NOT
    /// from the button: starting it while this cover is still animating
    /// away is how you get a re-setup screen that never appears.
    @State private var editRequested = false

    /// Card side (locked).
    private static let side: CGFloat = 116
    /// Ring diameter as a fraction of the card side (locked).
    private static let ringFraction: CGFloat = 0.78
    /// The sampling ring's AR cyan (0.2 / 0.85 / 1).
    private static let ringCyan = Color(red: 0.2, green: 0.85, blue: 1.0)
    /// The map home's GPS you-dot blue (#3B82C4).
    private static let youBlue = Color(red: 0.231, green: 0.510, blue: 0.769)

    public init(info: PlotMiniMapInfo, onEditPlot: (() -> Void)? = nil) {
        self.info = info
        self.onEditPlot = onEditPlot
    }

    private var ringRadiusPt: CGFloat { Self.side * Self.ringFraction / 2 }
    private var centrePt: CGFloat { Self.side / 2 }

    public var body: some View {
        if onEditPlot != nil {
            Button { showingEnlarged = true } label: { card }
                .buttonStyle(.plain)
                .accessibilityLabel("Show a bigger plot view. \(headerText.lowercased()), radius \(Int(info.radiusM.rounded())) metres")
                .accessibilityHint("Opens a larger view of the plot and the trees measured so far")
                .accessibilityIdentifier("plotMiniMap")
                // `fullScreenCover` is iOS-only; the UI target also
                // compiles for macOS under SPM, where a sheet is the
                // equivalent presentation.
                #if os(iOS)
                .fullScreenCover(isPresented: $showingEnlarged,
                                 onDismiss: runRequestedEdit) { enlargedView }
                #else
                .sheet(isPresented: $showingEnlarged,
                       onDismiss: runRequestedEdit) { enlargedView }
                #endif
        } else {
            card
                .allowsHitTesting(false)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Plot map. \(headerText.lowercased()), radius \(Int(info.radiusM)) metres")
                .accessibilityIdentifier("plotMiniMap")
        }
    }

    private var enlargedView: some View {
        PlotMapEnlargedView(
            info: info,
            canEditPlot: onEditPlot != nil,
            onEditPlot: {
                editRequested = true
                showingEnlarged = false
            },
            onClose: { showingEnlarged = false })
            // Clear the presentation's own backdrop so the enlarged view's
            // scrim is the only one and the scene stays visible (dimmed)
            // behind it — a DIALOG over the plot, not a new screen. The
            // sibling platform presents the same panel in a Dialog, which
            // behaves this way by default.
            .presentationBackground(.clear)
    }

    /// Runs the host's re-setup AFTER the enlarged view is fully gone, so
    /// the two presentations never overlap.
    private func runRequestedEdit() {
        guard editRequested else { return }
        editRequested = false
        onEditPlot?()
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

            // Measured trees — confidence-tinted 5 pt dots. Unplaceable
            // trees simply are not here; the enlarged view is where the
            // cruiser is told how many that is.
            ForEach(Array(info.placedTrees.placed.enumerated()),
                    id: \.offset) { _, tree in
                Circle()
                    .fill(tree.warn
                          ? ForestixPalette.confidenceWarn
                          : ForestixPalette.confidenceOk)
                    .frame(width: 5, height: 5)
                    .position(point(eastM: tree.eastM, northM: tree.northM))
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
                .stroke(onEditPlot == nil ? .white.opacity(0.18)
                                          : Self.ringCyan.opacity(0.75),
                        lineWidth: onEditPlot == nil ? 0.5 : 1))
        .overlay(alignment: .topLeading) {
            Text(headerText)
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white.opacity(0.85))
                .padding(.leading, 7)
                .padding(.top, 6)
        }
        .overlay(alignment: .topTrailing) {
            // The affordance, in the one corner the card's content never
            // occupies. Only drawn when the card is live. It is a MAGNIFIER,
            // not the old pencil: the tap now opens the enlarged plot view,
            // and a pencil would promise an editor the first tap no longer
            // reaches. Editing is one control deeper, inside that view.
            if onEditPlot != nil {
                Image(systemName: "magnifyingglass.circle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.9))
                    .padding(.trailing, 6)
                    .padding(.top, 5)
            }
        }
        .overlay(alignment: .bottomTrailing) {
            Text(String(format: "%.0f m radius", info.radiusM))
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

// MARK: - Enlarged plot view

/// The plot mini-map, big enough to read: the same north-up drawing
/// centred over the host screen, with every measured tree that HAS a
/// position drawn at it and numbered.
///
/// Why this exists: tapping the 116 pt card used to drop the cruiser
/// straight into plot re-setup. Almost always the tap meant "let me look
/// at that properly" — coverage, gaps, whether the north side of the plot
/// has been walked. So the tap lands here, and re-setup is an explicit
/// "Edit plot" button.
///
/// LEGIBILITY: the panel follows the app's appearance, so the drawing has
/// to read on pale paper AND on dark slate. Every mark is therefore drawn
/// twice — a casing in the panel's own colour under the ink, and a dark
/// halo under the cyan rim — the same trick the AR ring uses to stay
/// visible on sunlit litter and in deep shade.
///
/// LABELS: tree numbers only. No coordinates, no bearings, no record ids
/// — nothing the cruiser cannot act on standing in the plot.
struct PlotMapEnlargedView: View {

    let info: PlotMiniMapInfo
    /// False on hosts with no re-setup destination; the Edit plot button
    /// is then simply absent rather than present and dead.
    let canEditPlot: Bool
    let onEditPlot: () -> Void
    let onClose: () -> Void

    /// Live YOU sample, exactly as the card computes it — the enlarged
    /// view runs its own poll rather than borrowing the card's, which
    /// stops while this cover is up.
    @StateObject private var model = PlotMiniMapLiveModel()

    /// The sampling ring's AR cyan, shared with the card and the AR ring.
    private static let ringCyan = Color(red: 0.2, green: 0.85, blue: 1.0)
    /// The map home's GPS you-dot blue (#3B82C4).
    private static let youBlue = Color(red: 0.231, green: 0.510, blue: 0.769)
    /// The AR ring's dark halo, in 2D. A wider dark stroke UNDER the
    /// bright cyan rim keeps the boundary legible on a light panel as
    /// well as a dark one.
    private static let ringHalo = Color(red: 0.03, green: 0.06, blue: 0.08)
        .opacity(0.55)
    /// Panel width cap — wide enough to read a 30 m plot's tree spread on
    /// a phone, narrow enough to stay a panel rather than a screen.
    private static let maxPanelWidth: CGFloat = 380
    /// Ring diameter vs the drawing box. Larger than the card's: the
    /// enlarged view has no header furniture crowding the corners.
    private static let ringFraction: CGFloat = 0.84
    /// Above this many trees the numbers are dropped and only the dots
    /// are drawn — past it the labels collide and the picture reads
    /// worse, not better. Coverage (which is what the view is for)
    /// survives either way.
    private static let maxLabels = 30

    private var placement: (placed: [PlotMiniMapInfo.PlacedTree],
                            omitted: Int) { info.placedTrees }

    var body: some View {
        ZStack {
            // Scrim. Tapping it dismisses, like any dialog.
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture(perform: onClose)
                .accessibilityHidden(true)

            panel
                .frame(maxWidth: Self.maxPanelWidth)
                .padding(.horizontal, ForestixSpace.md)
        }
        .accessibilityIdentifier("plotMap.enlarged")
        .onAppear { model.start(info: info) }
        .onDisappear { model.stop() }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.sm) {
            header
            plotDrawing
            Text(summaryLine)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
            // Never let the picture quietly show fewer trees than were
            // measured: a gap the cruiser walks back to fill has to be a
            // real gap.
            if placement.omitted > 0 {
                Text(omittedLine)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("plotMap.enlarged.omitted")
            }
            controls
        }
        .padding(ForestixSpace.md)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
    }

    // MARK: Chrome

    private var header: some View {
        HStack(spacing: ForestixSpace.xs) {
            Text(info.plotNumber.map { "Plot \($0)" } ?? "Plot")
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityAddTraits(.isHeader)
            // Obvious dismiss, on a full-size tap target (gloves).
            Button(action: onClose) {
                Image(systemName: "xmark")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Close the plot view")
            .accessibilityIdentifier("plotMap.enlarged.closeIcon")
        }
    }

    private var summaryLine: String {
        let treeWord = info.treeCount == 1 ? "tree" : "trees"
        // Same radius wording as the card, so the two never disagree.
        return "\(info.treeCount) \(treeWord) measured · "
            + String(format: "%.0f m radius", info.radiusM)
    }

    private var omittedLine: String {
        let n = placement.omitted
        let subject = n == 1 ? "tree isn't" : "trees aren't"
        return "\(n) \(subject) shown — no position was recorded"
    }

    private var controls: some View {
        VStack(spacing: ForestixSpace.xs) {
            if canEditPlot {
                Button("Edit plot", action: onEditPlot)
                    .buttonStyle(.forestixProminent)
                    .accessibilityHint("Reopens plot setup to change the radius or centre")
                    .accessibilityIdentifier("plotMap.enlarged.edit")
            }
            Button("Close", action: onClose)
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                .accessibilityIdentifier("plotMap.enlarged.close")
        }
        .padding(.top, ForestixSpace.xxs)
    }

    // MARK: Drawing

    /// The plot itself — square, filling the panel's width.
    private var plotDrawing: some View {
        GeometryReader { geo in
            plotCanvas(side: geo.size.width)
                .frame(width: geo.size.width, height: geo.size.width)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(mapAccessibilityLabel)
    }

    private var mapAccessibilityLabel: String {
        var text = "Plot map, north up. \(summaryLine)."
        text += " \(placement.placed.count) shown on the map."
        if placement.omitted > 0 { text += " \(omittedLine)." }
        return text
    }

    private func plotCanvas(side: CGFloat) -> some View {
        let ringRadius = side * Self.ringFraction / 2
        let centre = side / 2
        let scale = ringRadius / CGFloat(max(info.radiusM, 0.5))
        // Casing = the panel's own colour. Drawn under every ink mark, it
        // is what keeps a dot readable where it lands on top of the rim,
        // in either appearance.
        let casing = ForestixPalette.surface
        let placed = placement.placed
        let showLabels = placed.count <= Self.maxLabels

        return ZStack {
            // Plot boundary: dark halo under the AR-ring cyan.
            Circle()
                .stroke(Self.ringHalo, lineWidth: 4)
                .frame(width: ringRadius * 2, height: ringRadius * 2)
                .position(x: centre, y: centre)
            Circle()
                .stroke(Self.ringCyan, lineWidth: 2)
                .frame(width: ringRadius * 2, height: ringRadius * 2)
                .position(x: centre, y: centre)

            // North tick — same convention as the card, above the rim.
            Text("N")
                .font(.system(size: 8, weight: .medium, design: .monospaced))
                .foregroundStyle(ForestixPalette.textSecondary)
                .position(x: centre, y: 6)

            // Plot centre — a CROSS, so it can never be read as one of
            // the round tree dots.
            centreMark(casing: casing)
                .position(x: centre, y: centre)

            // Measured trees — confidence-tinted, casing-ringed.
            ForEach(Array(placed.enumerated()), id: \.offset) { _, tree in
                treeMark(tree, casing: casing, showLabel: showLabels)
                    .position(point(eastM: tree.eastM,
                                    northM: tree.northM,
                                    centre: centre,
                                    scale: scale))
            }

            // YOU — the card's mark, scaled up and casing-ringed.
            if let you = model.you {
                youMark(headingDeg: you.headingDeg, casing: casing)
                    .position(point(eastM: you.eastM,
                                    northM: you.northM,
                                    centre: centre,
                                    scale: scale))
            }
        }
    }

    private func centreMark(casing: Color) -> some View {
        ZStack {
            PlotCentreCross()
                .stroke(casing, style: StrokeStyle(lineWidth: 5,
                                                   lineCap: .round))
            PlotCentreCross()
                .stroke(ForestixPalette.textPrimary,
                        style: StrokeStyle(lineWidth: 2, lineCap: .round))
        }
        .frame(width: 16, height: 16)
    }

    /// A tree: confidence-tinted dot on a casing ring, its number set
    /// directly under it so a label never sits where another dot is.
    private func treeMark(_ tree: PlotMiniMapInfo.PlacedTree,
                          casing: Color,
                          showLabel: Bool) -> some View {
        ZStack {
            Circle()
                .fill(casing)
                .frame(width: 11, height: 11)
            Circle()
                .fill(tree.warn ? ForestixPalette.confidenceWarn
                                : ForestixPalette.confidenceOk)
                .frame(width: 8, height: 8)
            if showLabel {
                Text("\(tree.number)")
                    .font(.system(size: 9, weight: .semibold,
                                  design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .shadow(color: casing, radius: 2)
                    .fixedSize()
                    .offset(y: 11)
            }
        }
        .frame(width: 11, height: 11)
    }

    /// The cruiser, at the enlarged view's scale.
    private func youMark(headingDeg: Double?, casing: Color) -> some View {
        ZStack {
            if let h = headingDeg {
                MiniMapWedge()
                    .fill(Self.youBlue)
                    .frame(width: 10, height: 9)
                    .offset(y: -10.5)
                    .rotationEffect(.degrees(h))
            }
            Circle()
                .fill(Self.youBlue)
                .frame(width: 11, height: 11)
                .overlay(Circle().stroke(casing, lineWidth: 1.5))
        }
        .frame(width: 30, height: 30)
    }

    /// Canvas point for a plot-local offset, clamped to the ring edge
    /// when the tree sits outside the plot — the same rule the card uses,
    /// so a borderline stem lands in the same place on both.
    private func point(eastM: Double,
                       northM: Double,
                       centre: CGFloat,
                       scale: CGFloat) -> CGPoint {
        let radius = max(info.radiusM, 0.5)
        var e = eastM
        var n = northM
        let d = (e * e + n * n).squareRoot()
        if d > radius, d > 0 {
            let f = radius / d
            e *= f
            n *= f
        }
        return CGPoint(x: centre + CGFloat(e) * scale,
                       y: centre - CGFloat(n) * scale)
    }
}

/// The plot-centre cross: two full-width strokes through the middle.
private struct PlotCentreCross: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: rect.midX, y: rect.minY))
        p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        p.move(to: CGPoint(x: rect.minX, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        return p
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
