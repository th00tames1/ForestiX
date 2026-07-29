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
//     the radius bottom-right inside, in the cruiser's own units
//     ("12 m radius" / "39 ft radius").
//
// INTERACTION: the card is ALWAYS a button — cyan edge + a magnifier
// badge — and the tap opens `PlotMapEnlargedView` (bottom of this file):
// the SAME drawing, centred and big, with the measured trees numbered.
// Field report F11 originally sent that tap straight into plot re-setup,
// which the field then corrected — a cruiser tapping a 116 pt map is
// asking to SEE it, not to rebuild the plot.
//
// Whether re-setup is OFFERED is a separate question, answered by
// `onEditPlot`: hosts that have somewhere to send the cruiser pass it and
// the enlarged view grows an "Edit plot" button; hosts that do not (the
// two height covers) pass nil and the button is simply absent. Opening
// the view was gated on that same closure until this round, which meant a
// height measurement could not open the bigger picture AT ALL.
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
//           plot) — and "current" means a fix `FixFreshness` still
//           believes, the same gate the map home's inside/outside
//           verdict uses. With neither anchor nor fresh fix there is no
//           cruiser on the card at all.
//   TREES — the recorded bearing + distance from the plot centre when
//           the tree record carries them, else the GPS-ENU offset of the
//           tree's own fix (captured at Accept) from the plot centre.
//           See `PlotMiniMapInfo.placedTrees`. A tree with NEITHER source
//           is left out rather than drawn at the centre.
//
// NOTHING IS CLAMPED. Marks are drawn where they actually are, at true
// scale, including in the band between the ring and the card edge; a
// mark that will not fit becomes an edge ARROW (the cruiser) or is left
// out (a tree). See `plotMarkPlacement`. The drawings used to pull any
// offset past the plot radius back onto the ring, which drew a cruiser
// 800 m away as one standing on the boundary.
//
// Kept cheap: the live YOU/heading sample is polled on a 0.2 s timer
// (the sampling screen's existing cadence; GPS itself ticks at ~1 Hz)
// and published ONLY when the quantized value changes — no per-frame
// SwiftUI invalidation, and the AR session manager is read without
// being observed.

import SwiftUI
import Geo
import Models
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
    /// The cruiser's unit system, for the ONE length this card states:
    /// the plot radius. It is passed in rather than read from settings
    /// because this payload is built by the hosting screen, which has
    /// them; the card used to hard-code metres and so printed "12 m
    /// radius" to a cruiser who had chosen feet everywhere else.
    public let unitSystem: UnitSystem

    public init(plotID: UUID?,
                plotNumber: Int?,
                radiusM: Double,
                centerLat: Double?,
                centerLon: Double?,
                treeCount: Int,
                trees: [TreeDot],
                unitSystem: UnitSystem) {
        self.plotID = plotID
        self.plotNumber = plotNumber
        self.radiusM = radiusM
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.treeCount = treeCount
        self.trees = trees
        self.unitSystem = unitSystem
    }

    /// The radius, in the cruiser's units — "12 m radius" / "39 ft
    /// radius". One property, used by the card, the enlarged view and
    /// both accessibility labels, so the four can never disagree.
    public var radiusLabel: String {
        "\(plotLengthLabel(radiusM, unitSystem)) radius"
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
    /// How far in from the card edge a mark may still be drawn, as a
    /// fraction of the side — enough to keep the mark's own width inside
    /// the card. Everything between the ring and this margin is drawable
    /// space, which is the point: a stem (or a cruiser) a little OUTSIDE
    /// the plot is drawn a little outside the ring, where it belongs,
    /// instead of being pushed onto it.
    private static let insetFraction: CGFloat = 0.045
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

    /// The card is ALWAYS a way into the enlarged view. It used to be
    /// gated on `onEditPlot` — a host that had no re-setup destination
    /// (the two height covers) got a card that could not be opened at
    /// all, so during a height measurement the bigger picture of the
    /// plot was simply unreachable. Editing and LOOKING are separate
    /// questions: `canEditPlot` already answers the first one on its own
    /// inside the enlarged view, where the Edit button appears only when
    /// there is somewhere for it to go.
    public var body: some View {
        Button { showingEnlarged = true } label: { card }
            .buttonStyle(.plain)
            .accessibilityLabel("Show a bigger plot view. \(headerText.lowercased()), \(info.radiusLabel)")
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

            // Measured trees — confidence-tinted 5 pt dots, each at its
            // TRUE offset. Trees with no position, and trees whose
            // position falls off the card, simply are not here; the
            // enlarged view is where the cruiser is told how many that
            // is. What none of them do any more is get parked on the ring
            // as if they had been measured at the boundary.
            ForEach(Array(info.placedTrees.placed.enumerated()),
                    id: \.offset) { _, tree in
                if let p = markPlacement(eastM: tree.eastM,
                                         northM: tree.northM)?.pointOnMap {
                    Circle()
                        .fill(tree.warn
                              ? ForestixPalette.confidenceWarn
                              : ForestixPalette.confidenceOk)
                        .frame(width: 5, height: 5)
                        .position(p)
                }
            }

            // YOU — you-dot blue with a heading wedge when available, at
            // the true offset; an edge arrow when the cruiser is off the
            // card entirely.
            if let you = model.you,
               let spot = markPlacement(eastM: you.eastM,
                                        northM: you.northM) {
                switch spot {
                case let .at(p):
                    youMark(headingDeg: you.headingDeg).position(p)
                case let .beyond(p, bearing):
                    youAwayMark(bearingDeg: bearing).position(p)
                }
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
                // The brighter AR-cyan edge the rest of the plot chrome
                // uses for "this is the plot, and you can touch it".
                // Every card is touchable now, so every card wears it.
                .stroke(Self.ringCyan.opacity(0.75), lineWidth: 1))
        .overlay(alignment: .topLeading) {
            Text(headerText)
                .font(.system(size: 9, weight: .semibold, design: .monospaced))
                .foregroundStyle(.white.opacity(0.85))
                .padding(.leading, 7)
                .padding(.top, 6)
        }
        .overlay(alignment: .topTrailing) {
            // The affordance, in the one corner the card's content never
            // occupies. It is a MAGNIFIER, not the old pencil: the tap
            // opens the enlarged plot view, and a pencil would promise an
            // editor the tap does not reach. Editing is one control
            // deeper, inside that view, and only where it is offered.
            Image(systemName: "magnifyingglass.circle.fill")
                .font(.system(size: 13))
                .foregroundStyle(.white.opacity(0.9))
                .padding(.trailing, 6)
                .padding(.top, 5)
        }
        .overlay(alignment: .bottomTrailing) {
            Text(info.radiusLabel)
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

    /// Where a plot-relative ENU offset lands on this card — at TRUE
    /// scale, never clamped. See `plotMarkPlacement`.
    private func markPlacement(eastM: Double, northM: Double)
        -> PlotMarkPlacement? {
        plotMarkPlacement(eastM: eastM, northM: northM,
                          radiusM: info.radiusM,
                          ringFraction: Self.ringFraction,
                          insetFraction: Self.insetFraction,
                          side: Self.side)
    }

    /// The cruiser, OFF THIS CARD: a bare you-blue arrowhead on the card
    /// edge, pointing the way to them.
    ///
    /// Deliberately NOT the you-dot. The dot is the app's mark for "you
    /// are here", and here it does not know that — only which direction
    /// "there" is. An arrowhead with no dot under it reads as a
    /// direction, which is exactly the claim being made.
    private func youAwayMark(bearingDeg: Double) -> some View {
        MiniMapWedge()
            .fill(Self.youBlue.opacity(0.9))
            .frame(width: 9, height: 8)
            .overlay(
                MiniMapWedge()
                    .stroke(.white.opacity(0.8), lineWidth: 0.75)
                    .frame(width: 9, height: 8))
            .rotationEffect(.degrees(bearingDeg))
            .frame(width: 16, height: 16)
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
    /// Drawable margin, as a fraction of the box side — the card's
    /// constant with room for this view's larger marks. Same purpose:
    /// the band between the ring and the box edge is where a stem or a
    /// cruiser just OUTSIDE the plot is drawn, instead of being clamped
    /// onto the boundary.
    private static let insetFraction: CGFloat = 0.035
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
        return "\(info.treeCount) \(treeWord) measured · \(info.radiusLabel)"
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

    /// Where one tree lands on this view's drawing, at true scale.
    /// Side-independent: `plotMarkPlacement` derives everything from the
    /// box side, so whether a mark FITS depends only on its offset in
    /// plot radii — which is why the spoken count below can be computed
    /// without a laid-out box.
    private func markPlacement(eastM: Double, northM: Double,
                               side: CGFloat = 100) -> PlotMarkPlacement? {
        plotMarkPlacement(eastM: eastM, northM: northM,
                          radiusM: info.radiusM,
                          ringFraction: Self.ringFraction,
                          insetFraction: Self.insetFraction,
                          side: side)
    }

    /// Trees that actually appear on the drawing — positioned AND close
    /// enough to fit. The count VoiceOver reads has to be the count a
    /// sighted cruiser can see, or the two are looking at different maps.
    private var drawnTreeCount: Int {
        placement.placed.filter {
            markPlacement(eastM: $0.eastM,
                          northM: $0.northM)?.pointOnMap != nil
        }.count
    }

    private var mapAccessibilityLabel: String {
        var text = "Plot map, north up. \(summaryLine)."
        text += " \(drawnTreeCount) shown on the map."
        if placement.omitted > 0 { text += " \(omittedLine)." }
        return text
    }

    private func plotCanvas(side: CGFloat) -> some View {
        let ringRadius = side * Self.ringFraction / 2
        let centre = side / 2
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

            // Measured trees — confidence-tinted, casing-ringed, each at
            // its TRUE offset. One that will not fit the box is left out
            // rather than pushed onto the rim; `drawnTreeCount` keeps the
            // spoken count honest about it.
            ForEach(Array(placed.enumerated()), id: \.offset) { _, tree in
                if let p = markPlacement(eastM: tree.eastM,
                                         northM: tree.northM,
                                         side: side)?.pointOnMap {
                    treeMark(tree, casing: casing, showLabel: showLabels)
                        .position(p)
                }
            }

            // YOU — the card's mark, scaled up and casing-ringed; the
            // card's edge arrow when the cruiser is off the drawing.
            if let you = model.you,
               let spot = markPlacement(eastM: you.eastM,
                                        northM: you.northM,
                                        side: side) {
                switch spot {
                case let .at(p):
                    youMark(headingDeg: you.headingDeg, casing: casing)
                        .position(p)
                case let .beyond(p, bearing):
                    youAwayMark(bearingDeg: bearing, casing: casing)
                        .position(p)
                }
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

    /// The cruiser, OFF THIS DRAWING: the card's bare arrowhead at panel
    /// scale, on the box edge, pointing the way to them. No dot — the
    /// view knows the direction, not the place.
    private func youAwayMark(bearingDeg: Double, casing: Color) -> some View {
        MiniMapWedge()
            .fill(Self.youBlue)
            .frame(width: 13, height: 12)
            .overlay(
                MiniMapWedge()
                    .stroke(casing, lineWidth: 1.5)
                    .frame(width: 13, height: 12))
            .rotationEffect(.degrees(bearingDeg))
            .frame(width: 26, height: 26)
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

// MARK: - Mark placement (ONE rule, both map sizes)

/// Where a plot-local offset lands on a square, north-up plot drawing.
enum PlotMarkPlacement: Equatable {
    /// Inside the drawing. The mark goes exactly here, at true scale.
    case at(CGPoint)
    /// Outside it. The point is ON the drawing's edge and `bearingDeg` is
    /// the compass direction (0° = north = up, clockwise) from the plot
    /// centre to the REAL position — for an arrow that points at it
    /// without pretending to be it.
    case beyond(CGPoint, bearingDeg: Double)

    /// The point to draw a PLACE at, and nothing else. `.beyond` yields
    /// nil, so a caller that has only a "you are here"-style mark (a tree
    /// dot) leaves it out rather than drawing it somewhere it isn't.
    var pointOnMap: CGPoint? {
        if case let .at(p) = self { return p }
        return nil
    }
}

/// True position, never relocated.
///
/// THIS REPLACES A CLAMP. Both plot drawings used to pull any offset
/// past the plot radius back onto the ring, so a cruiser standing 800 m
/// away — an hours-old fix from a valley over, before the age gate — was
/// drawn sitting ON the plot boundary, and a stem measured against a bad
/// fix was drawn as a stem at the plot edge. Clamping a position is
/// inventing data: it turns "I am 800 m away" into "I am at the plot
/// edge", which is the reading a cruiser would act on.
///
/// So a mark now goes where it actually is. The band between the ring and
/// the drawing's own margin is real space at true scale — a stem or a
/// cruiser a little outside the plot is drawn a little outside the ring,
/// which is the truth and is legible. Past the margin the answer is
/// `.beyond`: an edge point plus the direction, which the caller draws as
/// an arrow (the cruiser) or declines to draw at all (a tree). Nothing is
/// ever moved onto the boundary.
///
/// Everything is derived from `side`, so whether a mark FITS depends only
/// on its offset expressed in plot radii — the same verdict at any size,
/// and computable without a laid-out box.
func plotMarkPlacement(eastM: Double,
                       northM: Double,
                       radiusM: Double,
                       ringFraction: CGFloat,
                       insetFraction: CGFloat,
                       side: CGFloat) -> PlotMarkPlacement? {
    guard eastM.isFinite, northM.isFinite, side.isFinite, side > 0
    else { return nil }
    // The floor mirrors the ring's own: a degenerate radius must not
    // divide the drawing by ~zero and fling every mark to infinity.
    let radius = max(radiusM.isFinite ? radiusM : 0, 0.5)
    let centre = side / 2
    let scale = side * ringFraction / 2 / CGFloat(radius)
    let dx = CGFloat(eastM) * scale
    let dy = -CGFloat(northM) * scale          // screen y grows downward
    guard dx.isFinite, dy.isFinite else { return nil }

    let lo = side * insetFraction
    let hi = side - lo
    guard hi > lo else { return nil }

    let p = CGPoint(x: centre + dx, y: centre + dy)
    if p.x >= lo, p.x <= hi, p.y >= lo, p.y <= hi { return .at(p) }

    // Walk the ray centre → p out to the first box edge it crosses.
    var t: CGFloat = 1
    if dx > 0 { t = min(t, (hi - centre) / dx) }
    if dx < 0 { t = min(t, (lo - centre) / dx) }
    if dy > 0 { t = min(t, (hi - centre) / dy) }
    if dy < 0 { t = min(t, (lo - centre) / dy) }
    guard t.isFinite, t >= 0 else { return nil }

    // Compass bearing to the real position: 0° = north = up, clockwise —
    // the same convention the heading wedge is rotated by.
    let bearing = atan2(Double(dx), Double(-dy)) * 180 / .pi
    return .beyond(CGPoint(x: centre + dx * t, y: centre + dy * t),
                   bearingDeg: bearing)
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
    /// GPSFixChip, so the widget lights up on every host screen
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
        // `centreWorld`, not the raw anchor pose: while tracking is lost the
        // plot's own geometry is hidden, and a YOU dot placed against the
        // pose behind it would be the wrong answer drawn confidently. It
        // falls through to the GPS path below instead, which is the honest
        // one when AR does not know where the plot is.
        if anchorMatchesPlot,
           store.plot != nil,
           let centre = store.centreWorld,
           let cam = session.currentCameraWorldPosition {
            next = You(eastM: Double(cam.x - centre.x),
                       northM: Double(-(cam.z - centre.z)),
                       headingDeg: heading)
        } else if let lat = info.centerLat, let lon = info.centerLon,
                  let fix = FixFreshness.usable(location.latestSnapshot) {
            // GPS-ENU fallback: CURRENT fix vs the plot centre — and
            // "current" is now the app's one definition of it.
            //
            // This path used to read `latestSnapshot ?? lastGlobalFix`
            // with no age test at all: the ungated snapshot, plus the
            // very cross-session fallback the inside/outside verdict
            // deliberately refuses. Together with the old clamp that put
            // an hours-old fix from a valley away ON the plot ring, in
            // you-dot blue, while the map home's banner was saying "No
            // position" about the same fix. `FixFreshness.usable` is
            // exactly the call the verdict makes, and `lastGlobalFix` is
            // gone from here for the same reason it is absent there: a
            // fix from some other screen's location manager, of unknown
            // age, is not evidence of where the cruiser is standing now.
            //
            // No usable fix leaves `next` nil, which draws no cruiser at
            // all — the card's honest empty answer. The AR-anchor branch
            // above is untouched: it is a live camera pose against this
            // plot's own anchor, not a stored fix, and has no age to gate.
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
