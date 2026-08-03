// The sampling plot ON THE MAP HOME (M1/M2) — the cruise-side half of the
// basemap's `BasemapPlotOverlay`: what to draw, in the cruiser's units,
// and what happens when the drawn plot is tapped.
//
// WHY: the plot used to exist only in AR and as a 116 pt schematic card.
// Standing in a stand, the two questions a cruiser actually asks of it
// are "where is the plot?" and "am I in it?" — so the circle is now on
// the map at true ground scale, carrying labelled range rings, compass
// badges on true bearings, a centre mark, a live distance from that
// centre, and an INSIDE / OUTSIDE state that switches the whole overlay
// to the warning tint and draws a dotted line back to the centre once
// the cruiser has walked out of it.
//
// NO POSITION is its own state, never a third guess. It is reached
// whenever there is no fix YOUNG ENOUGH to say where the cruiser is
// standing — under canopy the last fix simply stops being replaced, and
// the app-scoped location service keeps serving it (and, on re-entering
// the map, the previous session's) indefinitely. In that state the plot
// still draws, but greyed and dash-bounded, carrying the words "No
// position" on the circle itself, and the banner says the distance is
// unknown rather than implying inside or outside.
//
// The drawing itself lives in Basemap/BasemapMapView.swift (it is a map
// layer, not a screen); this file owns the units, the ring interval, the
// state decision, the banner and the tap menu.

import SwiftUI
import Common
import Models
import Geo
import Basemap
import Sensors
import Positioning

// MARK: - Units

/// Metres out, feet back for an imperial cruiser. The plot is a LENGTH on
/// the ground, so it follows the same unit system as every other length
/// in the app — nothing here is ever hard-coded to one system.
private func lengthInDisplayUnit(_ metres: Double, _ system: UnitSystem) -> Double {
    system == .metric ? metres : Units.metersToFeet(metres)
}

private func displayUnitToMetres(_ value: Double, _ system: UnitSystem) -> Double {
    system == .metric ? value : Units.feetToMeters(value)
}

private func plotUnitSuffix(_ system: UnitSystem) -> String {
    system == .metric ? "m" : "ft"
}

/// A plot length for a cruiser to read at arm's length: one decimal, the
/// active unit ("7.2 m" / "23.6 ft").
///
/// Forwards to `MeasurementFormatter.plotLength` so this map-layer name and
/// the one the AR slider, the scan screens' border pill and the quick-measure
/// list use are literally the same rounding. They used to be two `%.1f`s that
/// happened to agree, which is how the peek and the banner came to print one
/// ring two ways.
func plotLengthLabel(_ metres: Double, _ system: UnitSystem) -> String {
    MeasurementFormatter.plotLength(m: metres, in: system)
}

// MARK: - Framing a new plot

/// How much wider than the plot's DIAMETER the viewport should be when the
/// map is asked to frame a plot. 1.6 leaves ~30 % of the plot's radius of
/// ground visible outside the ring on the short side — enough to see which
/// side of the boundary the you-dot is on, and to see the ring's own labels,
/// without shrinking the circle to a dot.
private let plotFramingHeadroom: Double = 1.6

/// The zoom at which a plot of `radiusM` fills the viewport with headroom.
///
/// Derived from the RADIUS, never from a hardcoded level: a 1/10-acre fixed
/// plot is ~11.3 m radius, a variable-radius or a large plot is not, and a
/// level that framed one would lose the other off-screen or leave it a dot.
///
/// The viewport's size in points is not needed — only how much GROUND it
/// currently shows. `region` is what the map is displaying at `currentZoom`,
/// so the SHORT side's ground span against the span we want is exactly the
/// scale change to apply, and zoom is log2 of scale. Returns nil rather than
/// a guess when there is no measured region yet (the map has not laid out) or
/// the radius is not a real length — the caller then leaves the camera alone
/// instead of flinging it to an invented zoom.
func plotFramingZoom(radiusM: Double,
                     currentZoom: Double,
                     region: BasemapRegion?) -> Double? {
    guard radiusM.isFinite, radiusM > 0 else { return nil }
    return spanFramingZoom(spanM: 2 * radiusM,
                           currentZoom: currentZoom,
                           region: region)
}

/// Ground metres across the SHORTER side of what the map is showing. nil
/// until the map has laid out and reported a region — every caller then has
/// to decide what to do with "no idea how big the screen is" rather than
/// being handed an invented number.
func visibleSpanM(region: BasemapRegion?) -> Double? {
    guard let region else { return nil }
    let midLat = (region.minLatitude + region.maxLatitude) / 2
    let widthM = CoordinateConversions.haversineMeters(
        CoordinateConversions.LatLon(latitude: midLat,
                                     longitude: region.minLongitude),
        CoordinateConversions.LatLon(latitude: midLat,
                                     longitude: region.maxLongitude))
    let heightM = CoordinateConversions.haversineMeters(
        CoordinateConversions.LatLon(latitude: region.minLatitude,
                                     longitude: region.minLongitude),
        CoordinateConversions.LatLon(latitude: region.maxLatitude,
                                     longitude: region.minLongitude))
    let shownM = min(widthM, heightM)
    guard shownM.isFinite, shownM > 0 else { return nil }
    return shownM
}

/// The zoom at which `spanM` of ground fits across the short side with the
/// plot-framing headroom. `plotFramingZoom` is this expressed as a radius —
/// one rule, so a plot frame and a fit around two points can never disagree
/// about what "on screen" means.
func spanFramingZoom(spanM: Double,
                     currentZoom: Double,
                     region: BasemapRegion?) -> Double? {
    guard let shownM = visibleSpanM(region: region), spanM.isFinite, spanM > 0
    else { return nil }
    let zoom = currentZoom + log2(shownM / (spanM * plotFramingHeadroom))
    guard zoom.isFinite else { return nil }
    return min(max(zoom, BasemapMapView.zoomRange.lowerBound),
               BasemapMapView.zoomRange.upperBound)
}

// MARK: - Range rings

/// Round intervals a distance label may use, IN THE ACTIVE UNIT. The same
/// ladder serves metres and feet — which is the point: a 5 is a 5 whether
/// the cruiser reads metres or feet, and nothing in here knows about feet
/// specifically.
private let plotRingLadder: [Double] = [1, 2, 5, 10, 20, 25, 50, 100]

/// About this many rings inside the boundary. Fewer and the plot has no
/// sense of scale; more and the labels crowd each other.
private let plotRingTarget: Double = 4

/// Concentric range rings for a plot of `radiusM`, labelled in the active
/// unit.
///
/// RULE: take the ideal interval (radius ÷ 4), snap it to the nearest
/// rung of the 1 / 2 / 5 / 10 / 20 / 25 / 50 / 100 ladder ON A LOG SCALE
/// — so "nearest" means nearest by ratio, which is how a reader perceives
/// it — then draw a ring at every whole multiple of that interval that
/// lands strictly inside the boundary. A multiple within 2 % of the
/// boundary is dropped: the boundary is already drawn, and already
/// labelled with the radius in the banner.
///
/// Worked, metric, radius 7.2 m: ideal 1.8 → the ladder rungs either side
/// are 1 (ratio 1.80) and 2 (ratio 1.11), so 2 m wins → rings at 2, 4 and
/// 6 m. Worked, the same plot in feet (23.6 ft): ideal 5.9 → 5 (ratio
/// 1.18) beats 10 (ratio 1.69) → rings at 5, 10, 15 and 20 ft.
func plotRangeRings(radiusM: Double,
                    system: UnitSystem) -> [BasemapPlotOverlay.Ring] {
    let radius = lengthInDisplayUnit(radiusM, system)
    guard radius.isFinite, radius > 0 else { return [] }
    let ideal = radius / plotRingTarget
    guard let step = plotRingLadder.min(by: {
        abs(log($0 / ideal)) < abs(log($1 / ideal))
    }) else { return [] }
    let count = Int(floor(radius / step))
    guard count >= 1 else { return [] }
    return (1...count).compactMap { i in
        let at = step * Double(i)
        // Never draw a ring on top of the boundary itself.
        guard at < radius * 0.98 else { return nil }
        return BasemapPlotOverlay.Ring(
            radiusM: displayUnitToMetres(at, system),
            label: String(format: "%.0f %@", at, plotUnitSuffix(system)))
    }
}

extension MapHomeScreen {

    // MARK: - Framing a new plot

    /// Put the camera on a plot the cruiser has just created, close enough
    /// that the BOUNDARY is on screen.
    ///
    /// The question a cruiser asks the moment a plot exists is "am I in it?",
    /// and at the map's default zoom an 11 m ring is a few points across —
    /// unreadable, and no answer at all. `plotFramingZoom` derives the level
    /// from the plot's own radius, so a variable-radius or a large plot
    /// frames on the same rule.
    ///
    /// With no measured region yet the zoom is left exactly as it is and
    /// only the centre moves: a frame computed from nothing would be a
    /// guess, and this is the screen that answers "where am I standing".
    func frameCamera(onPlotAt centre: CoordinateConversions.LatLon,
                     radiusM: Double) {
        let zoom = plotFramingZoom(radiusM: radiusM,
                                   currentZoom: camera.zoom,
                                   region: visibleRegion) ?? camera.zoom
        withAnimation(.easeOut(duration: 0.35)) {
            camera = BasemapCamera(latitude: centre.latitude,
                                   longitude: centre.longitude,
                                   zoom: zoom)
        }
    }

    /// GLIDE the camera to `centre` at `zoom` — the ground travels under the
    /// cruiser's eye instead of cutting.
    ///
    /// `withAnimation` cannot do this. The map is a Canvas that reads
    /// `camera` at draw time, so an animated assignment slides the pin VIEWS
    /// over tiles that have already jumped — which reads as a glitch, not as
    /// a move. Showing someone where a plot is only works if they can watch
    /// the trip, so the camera is stepped here, frame by frame, and the
    /// tiles travel with the pins.
    ///
    /// Ease-out over ~0.6 s: quick off the mark, settling onto the target.
    /// A second call replaces the first — two loops writing the same camera
    /// would fight, and the cruiser's last tap is the one they meant.
    ///
    /// EVERY OTHER WRITER ENDS THE FLIGHT TOO, and the loop finds that out by
    /// looking rather than by being told. The map's own pan and pinch write
    /// this `camera` straight through the binding, and no gesture can cancel
    /// a Task it has never heard of — so a drag during a glide used to be
    /// undone 60 times a second, the ground hauling itself back out from
    /// under the cruiser's thumb. A camera that stops is a camera they can
    /// work with; one that fights them is not. The same test also ends a
    /// flight that a cut (`frameCamera`, my-location, the too-far branch of
    /// "Go to Plot N") has overtaken, since those assign `camera` outright.
    func flyCamera(to centre: CoordinateConversions.LatLon, zoom: Double) {
        let from = camera
        let steps = 36
        cameraFlight?.cancel()
        cameraFlight = Task { @MainActor in
            // Where this flight left the camera on its previous frame.
            // Anything else in it means someone else has taken the camera.
            var lastWritten = from
            for step in 1...steps {
                try? await Task.sleep(nanoseconds: 16_000_000)
                if Task.isCancelled { return }
                guard camera == lastWritten else { return }
                let t = Double(step) / Double(steps)
                let eased = 1 - pow(1 - t, 3)
                let next = BasemapCamera(
                    latitude: from.latitude + (centre.latitude - from.latitude) * eased,
                    longitude: from.longitude + (centre.longitude - from.longitude) * eased,
                    zoom: from.zoom + (zoom - from.zoom) * eased)
                camera = next
                lastWritten = next
            }
        }
    }

    // MARK: - The plot the map draws

    /// Everything the map home needs about the drawn plot: the overlay
    /// for the renderer, plus the banner's own facts. `distanceM` is nil
    /// when there is no fix young enough to trust — the ONE case where
    /// inside/outside is unknown, and the case an app-scoped location
    /// service will otherwise hide behind an hours-old position.
    struct CruisePlotOverlay {
        let plot: Plot
        let overlay: BasemapPlotOverlay
        let radiusM: Double
        let distanceM: Double?

        /// INSIDE is distance-from-centre ≤ radius, decided on a fix
        /// young enough to still describe where the cruiser is standing
        /// and nothing else. With no such fix it is neither inside nor
        /// outside — `distanceM` is nil and so is this.
        var inside: Bool? { distanceM.map { $0 <= radiusM } }

        /// The three states the drawing and the banner both speak.
        var state: BasemapPlotOverlay.CruiserState {
            switch inside {
            case .some(true):  return .inside
            case .some(false): return .outside
            case .none:        return .unknown
            }
        }
    }

    /// What the map and the banner BOTH say when there is no position to
    /// reason from. One constant, so the words on the circle and the
    /// words in the banner can never drift apart.
    static let noPositionLabel = "No position"

    /// The ACTIVE plot, drawn on the map at true ground scale — or nil
    /// when there is no open plot, when the open plot never got (or has
    /// lost) a real centre, or outside cruise mode.
    var cruisePlotOverlay: CruisePlotOverlay? {
        guard let plot = activePlot, plot.hasCentre else { return nil }
        let radiusM = plotRadiusM(plot)
        guard radiusM.isFinite, radiusM > 0 else { return nil }
        let centre = CoordinateConversions.LatLon(latitude: plot.centerLat,
                                                  longitude: plot.centerLon)
        // A fix YOUNG ENOUGH TO MEAN SOMETHING, or none at all.
        //
        // `latestSnapshot` on its own is not that: it is written on
        // ingest, never cleared, and sits on an app-scoped singleton, so
        // under canopy it freezes at the last fix that got through and
        // on re-entering the map it still holds the PREVIOUS session's.
        // Either would have this overlay assert Inside or Outside from a
        // position the cruiser left long ago — and which side of the
        // boundary they are on decides which trees belong to the plot.
        // `FixFreshness.usable` gates on the snapshot's own timestamp;
        // anything it rejects falls into the existing no-position branch,
        // which was always the correct one. It is the SAME call the
        // you-dot, the nav guide and the plot card make, so the words
        // here and every position the app draws expire together.
        // `LocationService.lastGlobalFix` is deliberately not consulted.
        let fix = FixFreshness.usable(location.latestSnapshot)
        let distanceM = fix.map {
            CoordinateConversions.haversineMeters(
                CoordinateConversions.LatLon(latitude: $0.latitude,
                                             longitude: $0.longitude),
                centre)
        }.flatMap { $0.isFinite ? $0 : nil }
        let state: BasemapPlotOverlay.CruiserState = {
            guard let distanceM else { return .unknown }
            return distanceM <= radiusM ? .inside : .outside
        }()
        let edge: Color = {
            switch state {
            case .inside:  return ForestixPalette.accent
            case .outside: return ForestixPalette.confidenceBad
            // Neutral, not a signal colour: the drawing must stop
            // looking like an answer, not offer a third one.
            case .unknown: return ForestixPalette.textTertiary
            }
        }()
        return CruisePlotOverlay(
            plot: plot,
            // The Basemap target cannot see the design system, so every
            // colour is handed over here: the plot's calm ink is the
            // map's ACTIVE-plot accent (the same hue as its ring pin, so
            // circle and pin read as one object), the warning ink is the
            // confidence-bad signal, the unknown ink is tertiary text —
            // grey, and pointedly not a signal colour — and the labels
            // wear the surface/divider chip treatment the map's pin
            // badges use.
            overlay: BasemapPlotOverlay(
                center: centre,
                radiusM: radiusM,
                rings: plotRangeRings(radiusM: radiusM,
                                      system: settings.unitSystem),
                // Only a usable fix goes over: with none the renderer
                // has nothing to connect to and draws its unknown state.
                cruiser: fix.map {
                    CoordinateConversions.LatLon(latitude: $0.latitude,
                                                 longitude: $0.longitude)
                },
                state: state,
                id: plot.id.uuidString,
                stroke: ForestixPalette.accent,
                warnStroke: ForestixPalette.confidenceBad,
                unknownStroke: ForestixPalette.textTertiary,
                fill: edge.opacity(0.12),
                ink: ForestixPalette.textPrimary,
                pillBackground: ForestixPalette.surface,
                pillBorder: ForestixPalette.divider,
                // The SAME words the banner uses — the circle states the
                // gap itself, so the map alone never implies a state it
                // does not know.
                unknownLabel: Self.noPositionLabel),
            radiusM: radiusM,
            distanceM: distanceM)
    }

    // MARK: - Banner

    /// The plot's state in one small pill, top-centre under the map
    /// chrome: "Inside · Radius 7.2 m" over "3.1 m from centre". Outside
    /// turns the state line the warning colour, matching the boundary on
    /// the map; with no fix it says so instead of guessing.
    @ViewBuilder
    func cruisePlotBanner(_ plot: CruisePlotOverlay) -> some View {
        let system = settings.unitSystem
        let radiusLabel = "Radius \(plotLengthLabel(plot.radiusM, system))"
        let inside = plot.inside
        let stateLabel: String = {
            switch inside {
            case .some(true):  return "Inside · \(radiusLabel)"
            case .some(false): return "Outside · \(radiusLabel)"
            case .none:        return "\(Self.noPositionLabel) · \(radiusLabel)"
            }
        }()
        let distanceLabel = plot.distanceM
            .map { "\(plotLengthLabel($0, system)) from centre" }
            ?? "Distance from centre unknown"

        VStack(spacing: 2) {
            Text(stateLabel)
                .font(ForestixType.dataSmall)
                .foregroundStyle(inside == false ? ForestixPalette.confidenceBad
                                                 : ForestixPalette.textPrimary)
            Text(distanceLabel)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
        }
        .multilineTextAlignment(.center)
        .lineLimit(1)
        .padding(.horizontal, 12)
        .padding(.vertical, 7)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card,
                             style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .shadow(color: Color.black.opacity(0.18), radius: 4, y: 2)
        .frame(maxWidth: 320)
        // The plot's circle lives inside a Canvas, which VoiceOver
        // cannot target — so the banner is also the accessible way into
        // the plot's menu, and the way in when the circle is off-screen.
        .contentShape(RoundedRectangle(cornerRadius: ForestixRadius.card,
                                       style: .continuous))
        .onTapGesture { openPlotMenu(plot.plot.id.uuidString) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(stateLabel). \(distanceLabel)")
        .accessibilityHint("Opens the plot's edit and remove options")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("mapHome.plotBanner")
    }

    // MARK: - Tap menu (M2)

    /// A tap on the plot drawn on the map (the map's `onOverlayTap`) — raises
    /// that plot's Edit / Remove menu. Ignores an id that no longer names
    /// a plot in this project.
    func openPlotMenu(_ plotID: String) {
        guard let id = UUID(uuidString: plotID),
              plots.contains(where: { $0.id == id })
        else { return }
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        plotMenuPlotID = id
    }

    /// What a tap on the drawn plot opens: Edit (straight into the plot
    /// setup that placed it) and Remove.
    ///
    /// REMOVE IS THE DANGEROUS ONE, so it is two taps and the second one
    /// spells out what survives. It clears the plot's recorded CENTRE —
    /// the thing that makes it a plot on the map — and nothing else: the
    /// plot itself, its radius and every tree measured in it stay exactly
    /// where they are. A cruiser who mis-taps loses the circle, never the
    /// day's tally.
    @ViewBuilder
    func plotOverlayMenu(for plot: Plot) -> some View {
        let system = settings.unitSystem
        let trees = liveTrees(in: plot.id).count
        ZStack {
            Color.black.opacity(0.45)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture { plotMenuPlotID = nil }
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: ForestixSpace.sm) {
                Text("Plot \(plot.plotNumber)")
                    .font(ForestixType.bodyBold)
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text("Radius \(plotLengthLabel(plotRadiusM(plot), system)) · "
                     + "\(trees) \(trees == 1 ? "tree" : "trees") measured")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)

                Button("Edit plot") { editPlotFromMap(plot) }
                    .buttonStyle(.forestixProminent)
                    .accessibilityIdentifier("mapHome.plotMenu.edit")

                // Destructive row in the app's own outline-danger
                // treatment — the same shape the plot and tree peeks use
                // for their deletes.
                Button(role: .destructive) {
                    confirmingPlotRemovalID = plot.id
                } label: {
                    Text("Remove plot")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(ForestixPalette.confidenceBad)
                        .frame(maxWidth: .infinity, minHeight: 46)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(ForestixPalette.confidenceBad.opacity(0.5),
                                        lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("mapHome.plotMenu.remove")

                Button("Cancel") { plotMenuPlotID = nil }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("mapHome.plotMenu.cancel")
            }
            .padding(ForestixSpace.md)
            .background(
                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                 style: .continuous)
                    .fill(ForestixPalette.surface))
            .frame(maxWidth: 340)
            .padding(.horizontal, ForestixSpace.lg)
        }
        .accessibilityIdentifier("mapHome.plotMenu")
    }

    /// EDIT → the plot setup screen that placed the ring, pointed at THIS
    /// plot so Save edits its radius (and re-stamps its centre if the
    /// cruiser actually re-places the ring) instead of minting a Plot N+1
    /// beside it.
    func editPlotFromMap(_ plot: Plot) {
        plotMenuPlotID = nil
        // Any ring belonging to a DIFFERENT plot goes first. This door can
        // open on an older open plot while the ring still marks the newest
        // one, and `editCruisePlot` would read that ring as "the cruiser
        // re-placed this plot's centre" and move the plot onto today's fix.
        armPlotSetup(editing: plot.id)
        editingMapPlotID = plot.id
        presentingPlotSetup = true
    }

    /// The plot the Remove confirmation is about. Resolved from the id
    /// (not held as a copy) so the alert always reads today's tree count.
    var plotMenuRemovalPlot: Plot? {
        guard let id = confirmingPlotRemovalID else { return nil }
        return plots.first { $0.id == id }
    }

    /// The confirmation's title and body. The trees line is not optional
    /// politeness: a cruiser who mis-taps here and loses a day of tallies
    /// has no recovery, so it states outright what survives.
    func plotRemovalTitle(_ plot: Plot) -> String {
        "Remove Plot \(plot.plotNumber) from the map?"
    }

    func plotRemovalMessage(_ plot: Plot) -> String {
        let n = liveTrees(in: plot.id).count
        let noun = n == 1 ? "tree" : "trees"
        let verb = n == 1 ? "is" : "are"
        return "This clears the plot's centre, so the plot stops being drawn "
            + "on the map. The \(n) \(noun) measured in it \(verb) kept — "
            + "nothing you tallied is deleted, and you can set the centre "
            + "again from Edit plot."
    }

    /// REMOVE, confirmed: take the plot OFF THE MAP and change nothing
    /// else.
    ///
    /// It clears exactly ONE thing: the recorded CENTRE, back to the
    /// (0, 0) sentinel every cruise surface already reads as "no centre".
    /// The Plot row, its number, its radius/area and every Tree row
    /// measured in it are untouched, so the tally still exports, still
    /// rolls up, and the plot is one "Edit plot" away from having a
    /// centre again (re-centring rewrites the whole position stamp in one
    /// go). Deleting the plot row instead would leave those trees with no
    /// plot to belong to — invisible on every screen and in every export —
    /// which is precisely the loss this control must never cause.
    ///
    /// The AR ring that mirrors this plot on the scan screens is dropped
    /// too when it is linked to it: a ring standing in for a centre that
    /// no longer exists would be a lie about where the plot is.
    func removePlotFromMap(_ plot: Plot) {
        confirmingPlotRemovalID = nil
        plotMenuPlotID = nil
        var updated = plot
        updated.centerLat = 0
        updated.centerLon = 0
        guard (try? environment.plotRepository.update(updated)) != nil else {
            // Storage error — leave the centre in place so the map keeps
            // telling the truth; the next tap retries.
            return
        }
        // One way to drop a ring, anchor included — two ways is how the next
        // caller forgets the anchor half (`ActiveSamplingPlot.drop`).
        if samplingPlot.linkedCruisePlotID == plot.id {
            samplingPlot.drop()
        }
        withAnimation(.easeOut(duration: 0.18)) { selectedPinID = nil }
        reloadCruise()
    }
}
