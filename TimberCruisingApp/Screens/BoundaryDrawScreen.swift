// DRAW THE BOUNDARY — the stand boundary, sketched on the map instead of
// imported from a file. Map settings is its one door: the map's own
// press-and-hold draws a cruise AREA in place instead (a stratum, edited
// on the home map — see MapHomeScreen+Area.swift), and that is a different
// object with a different job. The cruiser's reference is a drone mission
// planner: a rectangle appears, you pull its corners onto the stand.
//
// The gestures, in the cruiser's own words:
//   • a CORNER is a draggable handle
//   • every EDGE carries a midpoint handle; dragging it adds a corner there
//   • LONG-PRESSING a corner deletes it
//
// Three deliberate choices, all of them about a cold thumb in a glove:
//   • The handles are 30 pt of ink inside a 48 pt touch target — bigger
//     than Apple's 44 pt minimum, because the target is also MOVING under
//     the finger.
//   • Nothing autosaves. Every edit is live on screen and none of it
//     reaches the store until "Save boundary" is pressed, so a half-dragged
//     shape can never become the boundary the cruise is planned against.
//     Leaving without saving throws the sketch away, and says so first.
//   • Undo is a full-shape stack, pushed before each gesture rather than
//     after. A dragged corner therefore undoes to where the drag STARTED,
//     not to some point in the middle of it.
//
// What it produces is the SAME `SurveyBoundary` an import produces, so plot
// generation, the area readout, the in/out verdict and the exports need no
// change at all — with `origin: .drawn` stamped on it forever (see
// `BoundaryOrigin`). It never reads the GPS: this screen is about where the
// cruiser INTENDS the stand to be, and a drawn corner is not a measured one.

import SwiftUI
import Common
import Geo
import Basemap

struct BoundaryDrawScreen: View {

    @EnvironmentObject private var settings: AppSettings
    @ObservedObject private var boundaryModel = SurveyBoundaryModel.shared
    @Environment(\.dismiss) private var dismiss

    /// Where the map opens — the camera the cruiser was already looking at,
    /// so "Draw the boundary" drops the rectangle on the ground they were
    /// looking at and not on some remembered default.
    let initialCamera: BasemapCamera

    @State private var camera: BasemapCamera
    /// nil until the map has laid out and the starting rectangle has been
    /// derived from the viewport (see `dropStartingRectangle`).
    @State private var draft: BoundaryDraft?
    @State private var undoStack: [[CoordinateConversions.LatLon]] = []
    @State private var name: String = Self.defaultName

    /// The corner a drag is currently moving, so a gesture that outlives a
    /// delete cannot start writing to a different vertex.
    @State private var draggingCorner: Int?
    /// Set when a long-press deleted a corner mid-touch — the drag
    /// recogniser on that same finger is then ignored until it lifts.
    @State private var suppressDragUntilRelease = false
    /// The corner an edge-handle drag CREATED, once it has moved far
    /// enough to mean it. Until then the edge handle is not a vertex.
    @State private var insertedFromEdge: Int?

    @State private var confirmingReplace = false
    @State private var confirmingDiscard = false
    /// A save that the store refused. Stays on screen: a boundary that did
    /// not persist must never look like one that did.
    @State private var saveFailure: String?

    static let defaultName = "Drawn boundary"

    /// Ink of the handle, and of the outline being drawn. Deliberately the
    /// same saturated orange the imported boundary uses on the map — this
    /// IS a survey boundary, and colouring it differently would suggest it
    /// is a different kind of object. Provenance is carried by words and by
    /// the stored stamp, never by a colour a cruiser has to remember.
    private static let ink = SurveyBoundaryModel.strokeColor

    private static let coordinateSpaceName = "boundaryDrawMap"
    /// Visible handle diameter, and the invisible target around it.
    private static let handleDiameter: CGFloat = 30
    private static let handleTarget: CGFloat = 48
    private static let midpointDiameter: CGFloat = 20

    init(initialCamera: BasemapCamera) {
        self.initialCamera = initialCamera
        _camera = State(initialValue: initialCamera)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                helpBanner
                mapArea
                controlPanel
            }
            .navigationTitle("Draw the boundary")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { attemptDismiss() }
                        .accessibilityIdentifier("boundaryDraw.cancel")
                }
            }
            // ALERTS, not confirmationDialogs. A destructive confirmation is
            // read before an irreversible write, so it is centred and it looks
            // the same wherever the control that raised it happens to sit — an
            // action sheet becomes a popover anchored to its source in a
            // regular size class. Same rule as every other delete in this app;
            // see the field log's delete for the full argument.
            .alert("Replace the boundary?", isPresented: $confirmingReplace) {
                Button("Replace", role: .destructive) { commitSave() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(Self.replacementMessage(for: boundaryModel.boundary))
            }
            .alert("Discard this outline?", isPresented: $confirmingDiscard) {
                Button("Discard", role: .destructive) { dismiss() }
                Button("Keep editing", role: .cancel) {}
            } message: {
                Text("The outline has not been saved. Leaving now loses it.")
            }
        }
    }

    // MARK: - Help banner

    private var helpBanner: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Drag a corner to move it. Drag the small handle on an edge to add a corner. Press and hold a corner to delete it.")
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.horizontal, ForestixSpace.md)
        .padding(.vertical, ForestixSpace.sm)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(ForestixPalette.surface)
    }

    // MARK: - Map + handles

    private var mapArea: some View {
        GeometryReader { geo in
            ZStack {
                BasemapMapView(
                    camera: $camera,
                    baseTileCache: makeBaseTileCache(settings: settings),
                    overlayTileCache: settings.overlayEnabled
                        ? makeOverlayTileCache(settings: settings) : nil,
                    // The outline under construction is drawn through the
                    // map's own boundary layer, so it sits exactly where a
                    // saved boundary will sit — no second renderer to
                    // disagree with the first about what the shape looks
                    // like.
                    boundary: draftOverlay,
                    style: BasemapStyle(
                        canvas: ForestixPalette.canvas,
                        grid: ForestixPalette.divider.opacity(0.55),
                        pinStroke: ForestixPalette.surface,
                        pinInk: ForestixPalette.primaryInk,
                        badgeBackground: ForestixPalette.surface,
                        badgeBorder: ForestixPalette.divider,
                        badgeText: ForestixPalette.textSecondary,
                        selectionHalo: ForestixPalette.primaryMuted))

                // Handles live in a plain ZStack with NO background, so
                // only the handles themselves take touches — every tap,
                // pan and pinch that misses one falls straight through to
                // the map underneath and pans it, which is what a cruiser
                // does far more often than they drag a corner.
                if let draft {
                    ForEach(Array(draft.edgeMidpoints.enumerated()), id: \.offset) { index, coord in
                        midpointHandle(edge: index, coordinate: coord, size: geo.size)
                    }
                    ForEach(Array(draft.vertices.enumerated()), id: \.offset) { index, coord in
                        cornerHandle(index: index, coordinate: coord, size: geo.size)
                    }
                }
            }
            .coordinateSpace(.named(Self.coordinateSpaceName))
            .onAppear { dropStartingRectangle(size: geo.size) }
        }
        .frame(maxHeight: .infinity)
        .clipped()
    }

    /// The live outline, in the map's own overlay type.
    private var draftOverlay: BasemapBoundaryOverlay? {
        guard let draft, draft.vertices.count >= 2 else { return nil }
        var ring = draft.vertices
        if let first = ring.first, draft.vertices.count >= 3 { ring.append(first) }
        return BasemapBoundaryOverlay(
            shapes: [BasemapBoundaryOverlay.Shape(
                kind: draft.vertices.count >= 3 ? .polygon : .line,
                rings: [ring])],
            stroke: Self.ink,
            fill: SurveyBoundaryModel.fillColor)
    }

    private func screenPoint(_ coord: CoordinateConversions.LatLon,
                             size: CGSize) -> CGPoint {
        BasemapMapView.screenPoint(latitude: coord.latitude,
                                   longitude: coord.longitude,
                                   camera: camera,
                                   viewportSize: size)
    }

    @ViewBuilder
    private func cornerHandle(index: Int,
                              coordinate: CoordinateConversions.LatLon,
                              size: CGSize) -> some View {
        let point = screenPoint(coordinate, size: size)
        Circle()
            .fill(Self.ink)
            .overlay(Circle().stroke(.white, lineWidth: 3))
            .frame(width: Self.handleDiameter, height: Self.handleDiameter)
            .frame(width: Self.handleTarget, height: Self.handleTarget)
            .contentShape(Circle())
            // Long-press first: a still finger means delete, a moving one
            // means drag. `maximumDistance` keeps a shaky hold from being
            // read as a drag and losing the delete.
            .onLongPressGesture(minimumDuration: 0.55, maximumDistance: 12) {
                deleteCorner(index)
            }
            .gesture(cornerDrag(index: index, size: size))
            .position(point)
            .accessibilityLabel("Corner \(index + 1)")
            .accessibilityIdentifier("boundaryDraw.corner.\(index)")
    }

    private func cornerDrag(index: Int, size: CGSize) -> some Gesture {
        // 6 pt of slop, so the long-press has room to win on a still hand.
        DragGesture(minimumDistance: 6,
                    coordinateSpace: .named(Self.coordinateSpaceName))
            .onChanged { value in
                guard !suppressDragUntilRelease else { return }
                if draggingCorner == nil {
                    // One undo entry per gesture, captured before the
                    // first millimetre moves.
                    pushUndo()
                    draggingCorner = index
                }
                guard draggingCorner == index else { return }
                draft?.move(vertexAt: index,
                            to: BasemapMapView.coordinate(at: value.location,
                                                          camera: camera,
                                                          viewportSize: size))
            }
            .onEnded { _ in
                draggingCorner = nil
                suppressDragUntilRelease = false
            }
    }

    @ViewBuilder
    private func midpointHandle(edge index: Int,
                                coordinate: CoordinateConversions.LatLon,
                                size: CGSize) -> some View {
        let point = screenPoint(coordinate, size: size)
        Circle()
            .fill(.white)
            .overlay(Circle().stroke(Self.ink, lineWidth: 3))
            .frame(width: Self.midpointDiameter, height: Self.midpointDiameter)
            .frame(width: Self.handleTarget, height: Self.handleTarget)
            .contentShape(Circle())
            .gesture(midpointDrag(edge: index, size: size))
            .position(point)
            .accessibilityLabel("Add a corner on edge \(index + 1)")
            .accessibilityIdentifier("boundaryDraw.midpoint.\(index)")
    }

    private func midpointDrag(edge index: Int, size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 6,
                    coordinateSpace: .named(Self.coordinateSpaceName))
            .onChanged { value in
                let coord = BasemapMapView.coordinate(at: value.location,
                                                      camera: camera,
                                                      viewportSize: size)
                if let inserted = insertedFromEdge {
                    draft?.move(vertexAt: inserted, to: coord)
                } else {
                    // The handle becomes a real corner only now, on the
                    // first movement — a stray touch on an edge must not
                    // silently add a vertex the cruiser never asked for.
                    guard var current = draft else { return }
                    pushUndo()
                    let inserted = current.insertVertex(onEdgeAt: index, at: coord)
                    draft = current
                    insertedFromEdge = inserted
                }
            }
            .onEnded { _ in insertedFromEdge = nil }
    }

    // MARK: - Editing actions

    /// The rectangle this editor drops, sized from the VISIBLE viewport
    /// (middle half of it) rather than from a fixed ground distance: at
    /// zoom 12 a 200 m box is a dot and at zoom 20 it is off-screen, and a
    /// cruiser who cannot see what they were given assumes nothing
    /// happened.
    private func dropStartingRectangle(size: CGSize) {
        guard draft == nil, size.width > 0, size.height > 0 else { return }
        let a = BasemapMapView.coordinate(
            at: CGPoint(x: size.width * 0.25, y: size.height * 0.25),
            camera: camera, viewportSize: size)
        let b = BasemapMapView.coordinate(
            at: CGPoint(x: size.width * 0.75, y: size.height * 0.75),
            camera: camera, viewportSize: size)
        draft = BoundaryDraft.rectangle(corner: a, opposite: b)
    }

    private func deleteCorner(_ index: Int) {
        guard var current = draft else { return }
        pushUndo()
        if current.removeVertex(at: index) {
            draft = current
            // The finger is still down; whatever drag it starts next
            // would be aimed at a vertex that has just moved index.
            suppressDragUntilRelease = true
            draggingCorner = nil
        } else if !undoStack.isEmpty {
            // Refused at three corners — undo the snapshot we just took so
            // the button does not appear to have something to undo.
            undoStack.removeLast()
        }
    }

    private func pushUndo() {
        guard let draft else { return }
        undoStack.append(draft.vertices)
        // A field session drags a lot; the stack is for mistakes, not for
        // history, and an unbounded one is just a leak.
        if undoStack.count > 30 { undoStack.removeFirst() }
    }

    private func undo() {
        guard let previous = undoStack.popLast() else { return }
        draft = BoundaryDraft(vertices: previous)
        saveFailure = nil
    }

    // MARK: - Save

    private func attemptSave() {
        saveFailure = nil
        guard let draft, draft.isValid else { return }
        // REPLACEMENT IS NEVER SILENT. An imported boundary was surveyed by
        // someone with instruments; overwriting it with a sketch because a
        // button was in the way is the worst thing this screen could do.
        if boundaryModel.boundary != nil {
            confirmingReplace = true
        } else {
            commitSave()
        }
    }

    private func commitSave() {
        guard let draft else { return }
        do {
            try boundaryModel.saveDrawn(draft, displayName: trimmedName)
            dismiss()
        } catch let error as BoundaryDraftError {
            saveFailure = error.description
        } catch {
            saveFailure = "The boundary could not be saved (\(error.localizedDescription))."
        }
    }

    private func attemptDismiss() {
        // Any edit at all counts as unsaved work: the starting rectangle
        // alone is not worth a prompt, but a moved corner is.
        if undoStack.isEmpty {
            dismiss()
        } else {
            confirmingDiscard = true
        }
    }

    private var trimmedName: String {
        let t = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? Self.defaultName : t
    }

    static func replacementMessage(for existing: SurveyBoundary?) -> String {
        guard let existing else { return "" }
        let origin = existing.origin == .drawn
            ? "drawn on the map" : "imported from a file"
        return "\(existing.displayName) is loaded now — \(origin). Forestix keeps one boundary at a time, so saving replaces it. The replaced boundary is not recoverable in the app."
    }

    // MARK: - Control panel

    private var controlPanel: some View {
        VStack(spacing: ForestixSpace.sm) {
            HStack(alignment: .firstTextBaseline) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(areaText)
                        .font(ForestixType.bodyBold)
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .accessibilityIdentifier("boundaryDraw.area")
                    Text(cornerText)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                Spacer(minLength: 0)
                Button {
                    undo()
                } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward")
                }
                .disabled(undoStack.isEmpty)
                .accessibilityIdentifier("boundaryDraw.undo")
            }

            // A refusal is shown the moment the shape becomes invalid, not
            // at the moment Save is pressed — the corner that caused it is
            // still under the thumb.
            if let failure = draft?.validationFailure {
                refusal(failure.description)
            } else if let saveFailure {
                refusal(saveFailure)
            }

            TextField("Boundary name", text: $name)
                .textFieldStyle(.roundedBorder)
                .foregroundStyle(ForestixPalette.textPrimary)
                #if os(iOS)
                .textInputAutocapitalization(.words)
                #endif
                .accessibilityIdentifier("boundaryDraw.name")

            Button {
                attemptSave()
            } label: {
                Text("Save boundary")
                    .frame(maxWidth: .infinity, minHeight: 52)
            }
            .buttonStyle(ForestixProminentButtonStyle())
            .disabled(draft?.isValid != true)
            .accessibilityIdentifier("boundaryDraw.save")
        }
        .padding(ForestixSpace.md)
        .background(ForestixPalette.surface)
    }

    private func refusal(_ text: String) -> some View {
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
        .accessibilityIdentifier("boundaryDraw.error")
    }

    /// Live area in the cruiser's OWN unit system — they are laying out a
    /// stand, so the number they need is acres or hectares, never m².
    /// Blank rather than "0.00 ac" while the shape has no defined
    /// interior: a number computed from a self-crossing outline is a
    /// number that means nothing.
    private var areaText: String {
        guard let draft, draft.isValid else { return "Area —" }
        let unit = settings.unitSystem.areaUnit
        let value = unit.fromAcres(draft.areaAcres)
        return String(format: "Area %.2f %@", value, unit.abbreviation)
    }

    private var cornerText: String {
        let count = draft?.vertices.count ?? 0
        return count == 1 ? "1 corner" : "\(count) corners"
    }
}
