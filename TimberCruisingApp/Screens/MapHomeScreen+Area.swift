// AREAS ON THE HOME MAP — the outline a cruise is laid out inside, drawn
// and edited IN PLACE instead of on a screen of its own.
//
// WHAT AN AREA IS. A `Stratum`: the project-scoped polygon the cruise
// design has always generated plots inside (`SamplingGenerator.StratumInput`,
// `PlannedPlot.stratumId`). Drawing one on the map used to produce a
// `SurveyBoundary` instead — a map decoration that drove nothing, so a
// cruiser who outlined their stand still had to go and draw it a second
// time inside Cruise setup before any plot could be laid in it. Making the
// drawn outline BE the stratum is what item 6 asks for, and it is also
// what gives every plot the area laid a permanent, exact link back to it
// (`stratumId`), which is what "delete the area, delete its plots" and
// "resize the area, re-lay its plots" both stand on. A geometric
// after-the-fact "which plots are inside this shape" test would sweep up
// plots a cruiser placed by hand, and a resize would then silently destroy
// them.
//
// The imported survey boundary is untouched and still lives in Map
// settings: a stand somebody surveyed into a file and an outline dragged
// across a satellite tile are different evidence, and only the second one
// is an area you can cruise from here.
//
// THE MAP KEEPS THE MAP. Every gesture here happens over the home map's
// own camera — "Draw an area" no longer pushes an editor that throws away
// the view the cruiser had framed. The gesture vocabulary is the one
// `BoundaryDrawScreen` established and a cruiser may already know:
//   • a CORNER is a draggable handle
//   • every EDGE carries a midpoint handle; dragging it adds a corner
//   • LONG-PRESSING a corner deletes it
// and, as there, nothing autosaves and undo is a full-shape stack pushed
// before each gesture.

import SwiftUI
import Common
import Models
import Persistence
import Geo
import Basemap

extension MapHomeScreen {

    // MARK: - Loading

    /// Areas belong to the project, so they follow `currentProject` and
    /// are drawn in BOTH modes: an outline the cruiser dragged onto their
    /// stand does not stop existing because they flipped to quick measure,
    /// and one that vanished on the toggle would read as lost.
    func reloadAreas() {
        // Measure mode never runs `reloadCruise`, so the project list and
        // the plan can both be empty here — and an area callout that says
        // "no plots planned" because nothing was loaded is a lie about the
        // cruiser's own work. This loads what it needs rather than
        // assuming a cruise reload has already happened.
        if projects.isEmpty {
            projects = (try? environment.projectRepository.list()) ?? []
        }
        guard let project = currentProject else {
            areas = []
            selectedAreaID = nil
            return
        }
        areas = (try? environment.stratumRepository.listByProject(project.id)) ?? []
        plannedPlots = ((try? environment.plannedPlotRepository
            .listByProject(project.id)) ?? []).filter { !$0.visited }
        if let id = selectedAreaID, !areas.contains(where: { $0.id == id }) {
            selectedAreaID = nil
        }
    }

    /// What the map draws. The draft, while one is open, is drawn through
    /// the SAME layer as the stored areas — one renderer, so a half-dragged
    /// outline can never look like a different kind of object from the one
    /// it is about to become. The area being edited drops out: its stored
    /// shape is exactly what the draft is replacing, and drawing both would
    /// show the cruiser two answers to one question.
    var areaOverlays: [BasemapArea] {
        var out: [BasemapArea] = areas.compactMap { stratum in
            guard stratum.id != editingAreaID,
                  let rings = try? CruiseSetupSheet.parseRings(from: stratum.polygonGeoJSON),
                  !rings.isEmpty
            else { return nil }
            return BasemapArea(id: stratum.id.uuidString,
                               rings: rings,
                               selected: stratum.id == selectedAreaID)
        }
        if let areaDraft, areaDraft.vertices.count >= 3 {
            out.append(BasemapArea(id: Self.areaDraftOverlayID,
                                   rings: [areaDraft.closedRing],
                                   selected: true))
        }
        return out
    }

    /// The draft's id on the map. Never a UUID string, so a hit on it can
    /// never be mistaken for a hit on a stored area.
    static var areaDraftOverlayID: String { "area-draft" }

    var selectedArea: Stratum? {
        guard let id = selectedAreaID else { return nil }
        return areas.first { $0.id == id }
    }

    // MARK: - Tap routing

    /// Where a tap that missed every pin lands. The map reports what was
    /// under the finger and takes no view (`BasemapOverlayHit`); the rule
    /// for the OVERLAP is here, because only this screen knows what is
    /// selected right now.
    ///
    /// THE OVERLAP RULE: a plot is laid inside an area, so their targets
    /// coincide by construction. A tap on the pair TOGGLES — whichever of
    /// the two is not currently selected is the one the cruiser is asking
    /// for. Without it the area, being the whole interior, would swallow
    /// every tap and the plot underneath would be unreachable. It is also
    /// announced (`areaStackHint`) the first time it happens in a session:
    /// a toggle nobody knows about is the same as no toggle at all.
    func handleOverlayTap(_ hit: BasemapOverlayHit) {
        // A tap while drafting belongs to the draft, not to what is under
        // it: the outline being dragged is the only thing on this map the
        // cruiser is currently talking to.
        guard areaDraft == nil else { return }
        let areaID = hit.areaID.flatMap(UUID.init(uuidString:))
        switch (hit.plotID, areaID) {
        case let (plotID?, id?):
            if selectedAreaID == id {
                selectedAreaID = nil
                openPlotMenu(plotID)
                showAreaStackHint(showingArea: false)
            } else {
                selectArea(id)
                showAreaStackHint(showingArea: true)
            }
        case let (plotID?, nil):
            clearAreaSelection()
            openPlotMenu(plotID)
        case let (nil, id?):
            // Tapping the selected area again lets it go — otherwise an
            // area covering the viewport would be a selection with no way
            // out except a pin tap.
            if selectedAreaID == id { clearAreaSelection() } else { selectArea(id) }
        case (nil, nil):
            break
        }
    }

    func selectArea(_ id: UUID) {
        withAnimation(.easeOut(duration: 0.18)) {
            selectedPinID = nil
            plotMenuPlotID = nil
            selectedAreaID = id
        }
    }

    func clearAreaSelection() {
        withAnimation(.easeOut(duration: 0.18)) { selectedAreaID = nil }
    }

    /// Says the toggle out loud, once per session. Named for the thing the
    /// NEXT tap will reach, because that is the sentence a cruiser can act
    /// on straight away.
    private func showAreaStackHint(showingArea: Bool) {
        guard !areaStackHintSeen else { return }
        areaStackHintSeen = true
        withAnimation(.easeOut(duration: 0.18)) {
            areaStackHint = showingArea
                ? "Tap again for the plot under this area."
                : "Tap again for the area around this plot."
        }
    }

    // MARK: - Drawing and editing in place

    /// "Draw an area" from the pressed-point callout. The starting
    /// rectangle is sized from the VISIBLE viewport (half of its short
    /// side) rather than from a fixed ground distance: at zoom 12 a 200 m
    /// box is a dot and at zoom 20 it is off-screen, and a cruiser who
    /// cannot see what they were given assumes nothing happened. Centred
    /// on the press, so it lands on the ground under the finger.
    func beginAreaDraw(at coordinate: CoordinateConversions.LatLon) {
        let size = mapViewportSize
        guard size.width > 0, size.height > 0 else { return }
        let centre = BasemapMapView.screenPoint(latitude: coordinate.latitude,
                                                longitude: coordinate.longitude,
                                                camera: camera,
                                                viewportSize: size)
        let half = min(size.width, size.height) * 0.25
        let a = BasemapMapView.coordinate(
            at: CGPoint(x: centre.x - half, y: centre.y - half),
            camera: camera, viewportSize: size)
        let b = BasemapMapView.coordinate(
            at: CGPoint(x: centre.x + half, y: centre.y + half),
            camera: camera, viewportSize: size)
        editingAreaID = nil
        areaDraftName = defaultAreaName
        areaDraftUndo = []
        areaSaveRefusal = nil
        withAnimation(.easeOut(duration: 0.18)) {
            selectedPinID = nil
            selectedAreaID = nil
            areaDraft = BoundaryDraft.rectangle(corner: a, opposite: b)
        }
    }

    /// EDIT — the same in-place editor, seeded with the stored outline.
    func beginAreaEdit(_ stratum: Stratum) {
        guard let rings = try? CruiseSetupSheet.parseRings(from: stratum.polygonGeoJSON),
              let outer = rings.first, outer.count >= 4
        else {
            areaSaveRefusal = "This area's outline could not be read, so it can't be edited."
            return
        }
        editingAreaID = stratum.id
        areaDraftName = stratum.name
        areaDraftUndo = []
        areaSaveRefusal = nil
        withAnimation(.easeOut(duration: 0.18)) {
            areaDraft = BoundaryDraft(closedRing: outer)
        }
    }

    /// "Area 1", "Area 2", … — one past the highest number already spoken
    /// for, so re-using a name a deleted area had is never a surprise.
    private var defaultAreaName: String {
        let used = areas.compactMap { stratum -> Int? in
            guard stratum.name.hasPrefix("Area ") else { return nil }
            return Int(stratum.name.dropFirst("Area ".count))
        }
        return "Area \((used.max() ?? 0) + 1)"
    }

    func pushAreaUndo() {
        guard let areaDraft else { return }
        areaDraftUndo.append(areaDraft.vertices)
        // A field session drags a lot; the stack is for mistakes, not for
        // history, and an unbounded one is just a leak.
        if areaDraftUndo.count > 30 { areaDraftUndo.removeFirst() }
    }

    func undoAreaDraft() {
        guard let previous = areaDraftUndo.popLast() else { return }
        areaDraft = BoundaryDraft(vertices: previous)
        areaSaveRefusal = nil
    }

    func cancelAreaDraft() {
        withAnimation(.easeOut(duration: 0.18)) {
            areaDraft = nil
        }
        editingAreaID = nil
        areaDraftUndo = []
        areaSaveRefusal = nil
        areaDraggingCorner = nil
        areaInsertedFromEdge = nil
    }

    func deleteAreaCorner(_ index: Int) {
        guard var current = areaDraft else { return }
        pushAreaUndo()
        if current.removeVertex(at: index) {
            areaDraft = current
            // The finger is still down; whatever drag it starts next would
            // be aimed at a vertex that has just moved index.
            areaSuppressDragUntilRelease = true
            areaDraggingCorner = nil
        } else if !areaDraftUndo.isEmpty {
            // Refused at three corners — drop the snapshot we just took so
            // Undo does not appear to have something to undo.
            areaDraftUndo.removeLast()
        }
    }

    // MARK: - Saving

    /// Write the draft. A NEW area is created; an EDITED one is updated in
    /// place, keeping its id — which is what keeps the plots it already
    /// laid attached to it — and its plots are then re-laid on the new
    /// outline (see `relayPlots(forArea:)`).
    func saveAreaDraft() {
        guard let draft = areaDraft else { return }
        if let failure = draft.validationFailure {
            areaSaveRefusal = failure.description
            return
        }
        guard let project = currentProject ?? autoCreateProject() else {
            areaSaveRefusal = "Couldn't save the area: there is no project to put it in."
            return
        }
        let name = areaDraftName.trimmingCharacters(in: .whitespacesAndNewlines)
        let ring = draft.closedRing
        let stratum = Stratum(
            id: editingAreaID ?? UUID(),
            projectId: project.id,
            name: name.isEmpty ? defaultAreaName : name,
            areaAcres: Float(draft.areaAcres),
            polygonGeoJSON: GeoJSONImporter.serialise(rings: [ring]))
        do {
            if editingAreaID != nil {
                _ = try environment.stratumRepository.update(stratum)
                try relayPlots(forArea: stratum)
            } else {
                _ = try environment.stratumRepository.create(stratum)
            }
        } catch {
            areaSaveRefusal =
                "Storage error: \(error.localizedDescription). The area was not saved — try again."
            return
        }
        HapticFeedback.play(.success)
        editingAreaID = nil
        areaDraftUndo = []
        areaSaveRefusal = nil
        withAnimation(.easeOut(duration: 0.18)) { areaDraft = nil }
        reloadAreas()
        reloadCruise()
        selectArea(stratum.id)
    }

    /// RESIZING AN AREA MOVES ITS PLOTS. An area whose outline changed but
    /// whose plots stayed put is the worst of both: a plan that no longer
    /// matches the ground it claims to sample, with plots sitting outside
    /// the stand.
    ///
    /// The plots are re-laid with the SAME design the cruiser generated
    /// them with — the project's stored `CruiseDesign`, the same one Cruise
    /// setup wrote — so a resize changes where the plots are and nothing
    /// else. Only UNVISITED plans are touched: a planned plot the cruiser
    /// has already opened has become a measurement, and no outline drag may
    /// move or destroy that.
    ///
    /// An area with no plans yet, or a project with no design yet, has
    /// nothing to re-lay and this does nothing.
    private func relayPlots(forArea stratum: Stratum) throws {
        let existing = try environment.plannedPlotRepository
            .listByProject(stratum.projectId)
            .filter { $0.stratumId == stratum.id && !$0.visited }
        guard !existing.isEmpty,
              let design = try environment.cruiseDesignRepository
                  .forProject(stratum.projectId).first
        else { return }
        let rings = try CruiseSetupSheet.parseRings(from: stratum.polygonGeoJSON)
        let options = SamplingGenerator.GenerationOptions(
            projectId: stratum.projectId,
            scheme: design.samplingScheme,
            gridSpacingMeters: design.gridSpacingMeters.map(Double.init),
            // Count-based designs re-lay the number of plots the area is
            // holding right now, which is the number the cruiser asked for
            // the last time they generated into it.
            nPerStratum: existing.count,
            seed: 1)
        let relaid = try SamplingGenerator.generate(
            strata: [.init(stratumId: stratum.id, rings: rings)],
            options: options,
            startingPlotNumber: existing.map(\.plotNumber).min() ?? nextPlotNumber())
        guard !relaid.isEmpty else { return }
        for plot in existing {
            try environment.plannedPlotRepository.delete(id: plot.id)
        }
        for plot in relaid {
            _ = try environment.plannedPlotRepository.create(plot)
        }
    }

    // MARK: - Deleting

    /// Unvisited plans this area laid. VISITED ones are deliberately not
    /// counted and not deleted: the moment a planned plot is opened it has
    /// a real Plot standing on it with a tally inside, and nothing on this
    /// screen may take that away.
    func plannedPlotsInArea(_ id: UUID) -> [PlannedPlot] {
        plannedPlots.filter { $0.stratumId == id && !$0.visited }
    }

    /// The area the delete confirmation is about. Resolved from the id, not
    /// held as a copy, so the sentence always counts today's plan.
    var deleteAreaCandidate: Stratum? {
        guard let id = deleteAreaCandidateID else { return nil }
        return areas.first { $0.id == id }
    }

    func areaDeletionTitle(_ stratum: Stratum) -> String {
        "Delete \(stratum.name)?"
    }

    /// The count is the whole point of this sentence: an area and the plan
    /// laid inside it go together, and a cruiser who is about to lose a
    /// morning's planning must be told how much of it before they answer.
    func areaDeletionMessage(_ stratum: Stratum) -> String {
        let n = plannedPlotsInArea(stratum.id).count
        if n == 0 {
            return "No plots are planned inside it. The outline is removed and nothing else changes."
        }
        let noun = n == 1 ? "plot" : "plots"
        return "The \(n) planned \(noun) laid inside it \(n == 1 ? "is" : "are") deleted with it. "
            + "Plots you have already opened are kept — this only removes the plan."
    }

    func deleteArea(_ stratum: Stratum) {
        deleteAreaCandidateID = nil
        do {
            for planned in plannedPlotsInArea(stratum.id) {
                try environment.plannedPlotRepository.delete(id: planned.id)
            }
            try environment.stratumRepository.delete(id: stratum.id)
        } catch {
            areaSaveRefusal =
                "Storage error: \(error.localizedDescription). The area was not deleted — try again."
            return
        }
        if editingAreaID == stratum.id { cancelAreaDraft() }
        clearAreaSelection()
        reloadAreas()
        reloadCruise()
    }

    // MARK: - Cruise from an area

    /// Open Cruise setup with this area already chosen. The sheet then
    /// generates into THIS area only and leaves every other area's plan
    /// alone — see `CruiseSetupSheet.AreaSeed`.
    func openCruiseSetup(forArea stratum: Stratum) {
        cruiseSetupAreaID = stratum.id
        if currentProject == nil {
            // Same door the rest of the map uses: a cruise needs a project
            // and the cruiser did not come here to be told so.
            _ = autoCreateProject()
        }
        // Laying out a cruise puts the cruiser in cruise mode: the pins this
        // is about to generate are cruise content, and generating them onto
        // a map that does not draw them would look like nothing happened.
        if !isCruiseMode {
            withAnimation(.easeOut(duration: 0.18)) {
                selectedPinID = nil
                settings.mapMode = "cruise"
            }
            reloadCruise()
        }
        clearAreaSelection()
        presentingCruiseSetup = true
    }

    // MARK: - Presentations

    /// The area alerts, hung off the map stack. Its own function beside
    /// `cruisePresentations` rather than more modifiers on that chain,
    /// which is already long enough that two more put the type-checker over
    /// its budget and failed the build.
    func areaPresentations<Content: View>(over content: Content) -> some View {
        content
            // AREA DELETED, step two. Never silent and never vague: the plan
            // laid inside an area goes with it, and the count is in the
            // sentence, because a cruiser about to lose a morning's planning
            // has to know how much before they answer.
            .alert(deleteAreaCandidate.map(areaDeletionTitle) ?? "",
                   isPresented: Binding(
                       get: { deleteAreaCandidateID != nil },
                       set: { if !$0 { deleteAreaCandidateID = nil } }),
                   presenting: deleteAreaCandidate) { stratum in
                Button("Delete area", role: .destructive) { deleteArea(stratum) }
                    .accessibilityIdentifier("mapHome.areaDelete.confirm")
                Button("Cancel", role: .cancel) { deleteAreaCandidateID = nil }
            } message: { stratum in
                Text(areaDeletionMessage(stratum))
            }
            // A refusal the cruiser has to see: an area that did not persist
            // must never look like one that did. Only while no draft is open
            // — mid-drag the draft bar already carries the sentence.
            .alert("Couldn't save the area",
                   isPresented: Binding(
                       get: { areaSaveRefusal != nil && areaDraft == nil },
                       set: { if !$0 { areaSaveRefusal = nil } })
            ) {
                Button("OK", role: .cancel) { areaSaveRefusal = nil }
            } message: {
                Text(areaSaveRefusal ?? "")
            }
    }

    // MARK: - The projected layer (pin, callouts, handles)

    /// Everything this screen draws AT a coordinate rather than in a
    /// corner: the pressed-point pin and its callout, the selected area's
    /// callout, and the draft's handles. One GeometryReader, ignoring the
    /// safe area exactly as the map does, so its coordinate space and the
    /// map's are the same space and a handle sits where the ground is.
    var mapObjectLayer: some View {
        GeometryReader { geo in
            ZStack {
                if let areaDraft {
                    ForEach(Array(areaDraft.edgeMidpoints.enumerated()), id: \.offset) { index, coord in
                        areaMidpointHandle(edge: index, coordinate: coord, size: geo.size)
                    }
                    ForEach(Array(areaDraft.vertices.enumerated()), id: \.offset) { index, coord in
                        areaCornerHandle(index: index, coordinate: coord, size: geo.size)
                    }
                } else {
                    if let coordinate = mapPlanCoordinate {
                        planPin(at: coordinate, size: geo.size)
                    }
                    if let stratum = selectedArea {
                        areaCallout(for: stratum, size: geo.size)
                    }
                }
            }
            .coordinateSpace(.named(Self.areaCoordinateSpace))
            .onAppear { mapViewportSize = geo.size }
            .onChange(of: geo.size) { _, size in mapViewportSize = size }
        }
        .ignoresSafeArea()
    }

    static var areaCoordinateSpace: String { "mapHomeObjects" }

    private static var handleDiameter: CGFloat { 30 }
    private static var handleTarget: CGFloat { 48 }
    private static var midpointDiameter: CGFloat { 20 }

    private func screenPoint(_ coord: CoordinateConversions.LatLon,
                             size: CGSize) -> CGPoint {
        BasemapMapView.screenPoint(latitude: coord.latitude,
                                   longitude: coord.longitude,
                                   camera: camera,
                                   viewportSize: size)
    }

    @ViewBuilder
    private func areaCornerHandle(index: Int,
                                  coordinate: CoordinateConversions.LatLon,
                                  size: CGSize) -> some View {
        Circle()
            .fill(ForestixPalette.cruiseAccent)
            .overlay(Circle().stroke(.white, lineWidth: 3))
            .frame(width: Self.handleDiameter, height: Self.handleDiameter)
            // 30 pt of ink inside a 48 pt target — bigger than the 44 pt
            // minimum, because the target is also MOVING under a gloved
            // thumb.
            .frame(width: Self.handleTarget, height: Self.handleTarget)
            .contentShape(Circle())
            // Long-press first: a still finger means delete, a moving one
            // means drag. `maximumDistance` keeps a shaky hold from being
            // read as a drag and losing the delete.
            .onLongPressGesture(minimumDuration: 0.55, maximumDistance: 12) {
                deleteAreaCorner(index)
            }
            .gesture(areaCornerDrag(index: index, size: size))
            .position(screenPoint(coordinate, size: size))
            .accessibilityLabel("Corner \(index + 1)")
            .accessibilityIdentifier("mapHome.area.corner.\(index)")
    }

    private func areaCornerDrag(index: Int, size: CGSize) -> some Gesture {
        // 6 pt of slop, so the long-press has room to win on a still hand.
        DragGesture(minimumDistance: 6,
                    coordinateSpace: .named(Self.areaCoordinateSpace))
            .onChanged { value in
                guard !areaSuppressDragUntilRelease else { return }
                if areaDraggingCorner == nil {
                    // One undo entry per gesture, captured before the first
                    // millimetre moves — so an undo lands where the drag
                    // STARTED, not somewhere in the middle of it.
                    pushAreaUndo()
                    areaDraggingCorner = index
                }
                guard areaDraggingCorner == index else { return }
                areaDraft?.move(vertexAt: index,
                                to: BasemapMapView.coordinate(at: value.location,
                                                              camera: camera,
                                                              viewportSize: size))
            }
            .onEnded { _ in
                areaDraggingCorner = nil
                areaSuppressDragUntilRelease = false
            }
    }

    @ViewBuilder
    private func areaMidpointHandle(edge index: Int,
                                    coordinate: CoordinateConversions.LatLon,
                                    size: CGSize) -> some View {
        Circle()
            .fill(.white)
            .overlay(Circle().stroke(ForestixPalette.cruiseAccent, lineWidth: 3))
            .frame(width: Self.midpointDiameter, height: Self.midpointDiameter)
            .frame(width: Self.handleTarget, height: Self.handleTarget)
            .contentShape(Circle())
            .gesture(areaMidpointDrag(edge: index, size: size))
            .position(screenPoint(coordinate, size: size))
            .accessibilityLabel("Add a corner on edge \(index + 1)")
            .accessibilityIdentifier("mapHome.area.midpoint.\(index)")
    }

    private func areaMidpointDrag(edge index: Int, size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 6,
                    coordinateSpace: .named(Self.areaCoordinateSpace))
            .onChanged { value in
                let coord = BasemapMapView.coordinate(at: value.location,
                                                      camera: camera,
                                                      viewportSize: size)
                if let inserted = areaInsertedFromEdge {
                    areaDraft?.move(vertexAt: inserted, to: coord)
                } else {
                    // The handle becomes a real corner only now, on the
                    // first movement — a stray touch on an edge must not
                    // silently add a vertex the cruiser never asked for.
                    guard var current = areaDraft else { return }
                    pushAreaUndo()
                    let inserted = current.insertVertex(onEdgeAt: index, at: coord)
                    areaDraft = current
                    areaInsertedFromEdge = inserted
                }
            }
            .onEnded { _ in areaInsertedFromEdge = nil }
    }

    // MARK: - Pressed-point pin + its callout (the planning menu)

    /// THE PIN THE CRUISER PRESSED, with the choices in a bubble over it.
    ///
    /// This replaces a bottom action sheet. A sheet at the foot of the
    /// screen asks "plan a plot here" while pointing at nothing — the
    /// cruiser has to hold the spot in their head and trust that the app
    /// held it too. Anchored to the press, the question and the ground it
    /// is about are the same object.
    ///
    /// BOTH MODES, but not the same menu in both. "Draw an area" is always
    /// here. "Plan a plot here" plans a CRUISE plot, and measure mode's
    /// plots — the project-less quick-measure ones — hold no coordinate to
    /// plan (`QuickMeasurePlot` has no lat/lon at all), so there is nothing
    /// for it to write there. It is left OUT of the measure-mode menu
    /// rather than shown and refused.
    @ViewBuilder
    private func planPin(at coordinate: CoordinateConversions.LatLon,
                         size: CGSize) -> some View {
        let point = screenPoint(coordinate, size: size)
        MapPinGlyph(tint: ForestixPalette.cruiseAccent)
            .position(x: point.x, y: point.y - MapPinGlyph.height / 2)
            .accessibilityIdentifier("mapHome.planPin")
        MapCallout(anchor: point,
                   viewport: size,
                   title: "Plan here") {
            if isCruiseMode {
                calloutButton("Plan a plot here", prominent: true) {
                    planPlot(at: coordinate)
                    dismissPlanMenu()
                }
                .accessibilityIdentifier("mapHome.planMenu.plot")
            }
            calloutButton("Draw an area", prominent: !isCruiseMode) {
                let at = coordinate
                dismissPlanMenu()
                beginAreaDraw(at: at)
            }
            .accessibilityIdentifier("mapHome.planMenu.area")
            calloutButton("Cancel", prominent: false) { dismissPlanMenu() }
                .accessibilityIdentifier("mapHome.planMenu.cancel")
        }
    }

    func dismissPlanMenu() {
        mapPlanCoordinate = nil
        awaitingMapPlanPress = false
    }

    // MARK: - Selected-area callout

    /// What a selected area offers: EDIT (back into the in-place editor),
    /// CRUISE (Cruise setup seeded with this outline) and DELETE. Anchored
    /// on the area's own centre for the same reason the plan menu is
    /// anchored on the press — several areas can be on screen at once, and
    /// a card in the middle of the screen would not say which one.
    @ViewBuilder
    private func areaCallout(for stratum: Stratum, size: CGSize) -> some View {
        if let centre = areaCentre(stratum) {
            let point = screenPoint(centre, size: size)
            MapCallout(anchor: point,
                       viewport: size,
                       title: stratum.name,
                       subtitle: areaSubtitle(stratum)) {
                calloutButton("Cruise this area", prominent: true) {
                    openCruiseSetup(forArea: stratum)
                }
                .accessibilityIdentifier("mapHome.areaMenu.cruise")
                calloutButton("Edit outline", prominent: false) {
                    beginAreaEdit(stratum)
                }
                .accessibilityIdentifier("mapHome.areaMenu.edit")
                calloutButton("Delete area", prominent: false, destructive: true) {
                    deleteAreaCandidateID = stratum.id
                }
                .accessibilityIdentifier("mapHome.areaMenu.delete")
            }
        }
    }

    /// Size in the cruiser's own unit, and how much of the plan sits in
    /// here — the two numbers that decide whether this is the area they
    /// meant to tap.
    private func areaSubtitle(_ stratum: Stratum) -> String {
        let unit = settings.unitSystem.areaUnit
        let size = String(format: "%.2f %@",
                          unit.fromAcres(Double(stratum.areaAcres)),
                          unit.abbreviation)
        let n = plannedPlotsInArea(stratum.id).count
        if n == 0 { return "\(size) · no plots planned" }
        return "\(size) · \(n) plot\(n == 1 ? "" : "s") planned"
    }

    /// Mean of the outer ring's corners, in ENU about the first corner so
    /// the anchor lands inside the shape rather than wherever naive
    /// lon/lat averaging would put it.
    private func areaCentre(_ stratum: Stratum) -> CoordinateConversions.LatLon? {
        guard let rings = try? CruiseSetupSheet.parseRings(from: stratum.polygonGeoJSON),
              let outer = rings.first, let origin = outer.first, outer.count >= 3
        else { return nil }
        let plane = outer.map { CoordinateConversions.toENU(point: $0, origin: origin) }
        let east = plane.map(\.east).reduce(0, +) / Double(plane.count)
        let north = plane.map(\.north).reduce(0, +) / Double(plane.count)
        return CoordinateConversions.toLatLon(
            enu: .init(east: east, north: north), origin: origin)
    }

    @ViewBuilder
    private func calloutButton(_ title: String,
                               prominent: Bool,
                               destructive: Bool = false,
                               action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 14, weight: prominent ? .bold : .semibold))
                .foregroundStyle(prominent
                                 ? ForestixPalette.primaryInk
                                 : (destructive ? ForestixPalette.confidenceBad
                                                : ForestixPalette.textPrimary))
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .fill(prominent ? ForestixPalette.primary : Color.clear))
                .overlay(
                    RoundedRectangle(cornerRadius: ForestixRadius.control,
                                     style: .continuous)
                        .stroke(prominent
                                ? Color.clear
                                : (destructive
                                   ? ForestixPalette.confidenceBad.opacity(0.5)
                                   : ForestixPalette.divider),
                                lineWidth: 1))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - The draft's control bar

    /// Replaces the action cluster while an outline is being dragged. It
    /// carries what `BoundaryDrawScreen`'s panel carries — live area, the
    /// corner count, undo, the name, and a refusal the moment the shape
    /// becomes invalid rather than at the moment Save is pressed — because
    /// the shape is still under the thumb that caused it.
    var areaDraftBar: some View {
        VStack(spacing: ForestixSpace.sm) {
            Text("Drag a corner to move it. Drag the small handle on an edge to add a corner. Press and hold a corner to delete it.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)

            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(areaDraftAreaText)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .accessibilityIdentifier("mapHome.area.areaReadout")
                    Text(areaDraftCornerText)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                Spacer(minLength: 0)
                Button {
                    undoAreaDraft()
                } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward")
                }
                .disabled(areaDraftUndo.isEmpty)
                .accessibilityIdentifier("mapHome.area.undo")
            }

            if let failure = areaDraft?.validationFailure {
                areaRefusal(failure.description)
            } else if let areaSaveRefusal {
                areaRefusal(areaSaveRefusal)
            }

            TextField("Area name", text: $areaDraftName)
                .textFieldStyle(.roundedBorder)
                .foregroundStyle(ForestixPalette.textPrimary)
                #if os(iOS)
                .textInputAutocapitalization(.words)
                #endif
                .accessibilityIdentifier("mapHome.area.name")

            HStack(spacing: ForestixSpace.sm) {
                Button("Cancel") { cancelAreaDraft() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                    .accessibilityIdentifier("mapHome.area.cancel")
                Button {
                    saveAreaDraft()
                } label: {
                    Text(editingAreaID == nil ? "Save area" : "Save changes")
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(ForestixProminentButtonStyle())
                .disabled(areaDraft?.isValid != true)
                .accessibilityIdentifier("mapHome.area.save")
            }
        }
        .padding(ForestixSpace.md)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .fill(ForestixPalette.surface))
        .padding(.horizontal, ForestixSpace.sm)
        .padding(.bottom, ForestixSpace.sm)
        .accessibilityIdentifier("mapHome.area.bar")
    }

    /// Blank rather than "0.00 ac" while the shape has no defined
    /// interior: a number computed from a self-crossing outline is a
    /// number that means nothing.
    private var areaDraftAreaText: String {
        guard let areaDraft, areaDraft.isValid else { return "Area —" }
        let unit = settings.unitSystem.areaUnit
        return String(format: "Area %.2f %@",
                      unit.fromAcres(areaDraft.areaAcres), unit.abbreviation)
    }

    private var areaDraftCornerText: String {
        let count = areaDraft?.vertices.count ?? 0
        return count == 1 ? "1 corner" : "\(count) corners"
    }

    private func areaRefusal(_ text: String) -> some View {
        Label {
            Text(text)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.confidenceBad)
                .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(ForestixPalette.confidenceBad)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("mapHome.area.error")
    }

    /// The overlap toggle, said once. Sits where the plan prompt sits and
    /// is dismissed by the same tap that reads it.
    var areaStackHintBanner: some View {
        HStack(spacing: ForestixSpace.xs) {
            Image(systemName: "square.stack.3d.up")
                .foregroundStyle(ForestixPalette.textSecondary)
            Text(areaStackHint ?? "")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(ForestixPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 4)
            Button {
                withAnimation(.easeOut(duration: 0.18)) { areaStackHint = nil }
            } label: {
                Text("Got it")
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(ForestixPalette.primary)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.control, style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.control, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .padding(.horizontal, ForestixSpace.sm)
        .accessibilityIdentifier("mapHome.area.stackHint")
    }
}

// MARK: - Anchored map furniture

/// The teardrop dropped where the cruiser pressed. Its own small view
/// rather than a `BasemapMarker` because it is not one of the screen's
/// data pins: it exists only while its callout is up, it is never
/// selectable, and it must not appear in the marker list the map hit-tests
/// against.
struct MapPinGlyph: View {

    static let height: CGFloat = 34

    let tint: Color

    var body: some View {
        ZStack {
            Image(systemName: "mappin.circle.fill")
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(tint)
                .background(Circle().fill(.white).padding(3))
            Image(systemName: "arrowtriangle.down.fill")
                .font(.system(size: 9))
                .foregroundStyle(tint)
                .offset(y: 16)
        }
        .frame(width: 34, height: Self.height)
        .shadow(color: .black.opacity(0.28), radius: 3, y: 1)
        .allowsHitTesting(false)
    }
}

/// A bubble anchored ABOVE a point on the map, with a pointer aimed back
/// at it — the shape a cruiser expects from a mission planner, and the one
/// thing an action sheet at the foot of the screen cannot be.
///
/// It clamps itself into the viewport horizontally, and flips BELOW the
/// anchor when there is not enough room above, so a press near the top of
/// the screen still gets a readable menu. Both are ordinary in the field:
/// the ground a cruiser wants is very often at the edge of what is framed.
struct MapCallout<Content: View>: View {

    let anchor: CGPoint
    let viewport: CGSize
    let title: String
    var subtitle: String? = nil
    @ViewBuilder let content: Content

    private static var width: CGFloat { 240 }
    private static var pointer: CGFloat { 9 }
    /// Clearance from the pin's own glyph, and from the viewport edge.
    private static var gap: CGFloat { 26 }
    private static var margin: CGFloat { 12 }

    @State private var measured: CGSize = .zero

    var body: some View {
        let below = anchor.y - measured.height - Self.gap < Self.margin
        VStack(spacing: 0) {
            if below { pointerShape(up: true) }
            card
            if !below { pointerShape(up: false) }
        }
        .frame(width: Self.width)
        .background(
            GeometryReader { proxy in
                Color.clear
                    .onAppear { measured = proxy.size }
                    .onChange(of: proxy.size) { _, size in measured = size }
            })
        .position(x: clampedX, y: clampedY(below: below))
        .accessibilityIdentifier("mapHome.callout")
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: ForestixSpace.xs) {
            Text(title)
                .font(ForestixType.bodyBold)
                .foregroundStyle(ForestixPalette.textPrimary)
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            content
        }
        .padding(ForestixSpace.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .fill(ForestixPalette.surface))
        .overlay(
            RoundedRectangle(cornerRadius: ForestixRadius.card, style: .continuous)
                .stroke(ForestixPalette.divider, lineWidth: 1))
        .shadow(color: .black.opacity(0.22), radius: 8, y: 3)
    }

    private func pointerShape(up: Bool) -> some View {
        Image(systemName: up ? "arrowtriangle.up.fill" : "arrowtriangle.down.fill")
            .font(.system(size: Self.pointer))
            .foregroundStyle(ForestixPalette.surface)
            .frame(height: Self.pointer)
            .offset(x: pointerOffset)
    }

    /// The pointer follows the anchor when the card itself has been
    /// clamped away from it, so the bubble never points at empty ground.
    private var pointerOffset: CGFloat {
        min(max(anchor.x - clampedX, -Self.width / 2 + 14), Self.width / 2 - 14)
    }

    private var clampedX: CGFloat {
        let half = Self.width / 2
        return min(max(anchor.x, half + Self.margin),
                   max(viewport.width - half - Self.margin, half + Self.margin))
    }

    private func clampedY(below: Bool) -> CGFloat {
        let half = measured.height / 2
        return below
            ? anchor.y + Self.gap + half
            : anchor.y - Self.gap - half
    }
}
