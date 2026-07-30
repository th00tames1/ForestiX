// App-level owner of the ONE imported survey boundary.
//
// A shared singleton (same pattern as `LocationService.shared`) because
// two unrelated views need the same live state: the map draws the
// boundary, and Map settings › Survey boundary shows/imports/removes it.
// Both observe this object, so an import lands on the map the moment the
// sheet closes — without threading a binding through the whole screen.
//
// Storage lives in `SurveyBoundaryStore` (Geo, pure file IO). This type
// adds exactly three things: publishing, security-scoped file access for
// the document picker, and the Basemap overlay projection.

import Foundation
import SwiftUI
import Basemap
import Geo

@MainActor
public final class SurveyBoundaryModel: ObservableObject {

    public static let shared = SurveyBoundaryModel()

    /// The loaded boundary, nil when none is stored.
    @Published public private(set) var boundary: SurveyBoundary?
    /// Set when a STORED boundary could not be read back — surfaced in
    /// the sheet rather than silently drawing nothing.
    @Published public private(set) var loadFailure: String?

    private let store: SurveyBoundaryStore

    public init(store: SurveyBoundaryStore = SurveyBoundaryStore()) {
        self.store = store
        reload()
    }

    // MARK: - Boundary colours

    /// Fixed on purpose: the boundary sits on satellite imagery / street
    /// tiles, not on an app surface, so it does not follow the app's
    /// light/dark palette (same rationale as the attribution badge). A
    /// saturated orange is the one hue that survives both green canopy
    /// and pale OSM road fill, and it is drawn over a dark halo.
    public static let strokeColor = Color(red: 1.0, green: 0.42, blue: 0.13)
    public static let fillColor = Color(red: 1.0, green: 0.42, blue: 0.13).opacity(0.16)

    // MARK: - Reads

    public var record: SurveyBoundaryStore.Record? {
        guard let boundary else { return store.loadRecord() }
        return SurveyBoundaryStore.Record(displayName: boundary.displayName,
                                          featureCount: boundary.featureCount,
                                          importedAt: boundary.importedAt,
                                          sourceFormat: boundary.sourceFormat,
                                          origin: boundary.origin)
    }

    /// What `BasemapMapView` draws. nil when nothing is loaded, so the
    /// renderer skips the layer entirely.
    public var overlay: BasemapBoundaryOverlay? {
        guard let boundary, !boundary.features.isEmpty else { return nil }
        let shapes: [BasemapBoundaryOverlay.Shape] = boundary.features.map { feature in
            let kind: BasemapBoundaryOverlay.Shape.Kind
            switch feature.kind {
            case .polygon: kind = .polygon
            case .line:    kind = .line
            case .point:   kind = .point
            }
            return BasemapBoundaryOverlay.Shape(kind: kind, rings: feature.rings)
        }
        return BasemapBoundaryOverlay(shapes: shapes,
                                      stroke: Self.strokeColor,
                                      fill: Self.fillColor)
    }

    public func reload() {
        do {
            boundary = try store.load()
            loadFailure = nil
        } catch {
            boundary = nil
            loadFailure = String(describing: error)
        }
    }

    // MARK: - Import / remove

    /// Import a picked file. Throws `BoundaryImportError` — whose
    /// `description` is the exact sentence the sheet shows — so a file
    /// the app cannot confirm as WGS84 never reaches `boundary`.
    @discardableResult
    public func importBoundary(from url: URL) throws -> SurveyBoundary {
        let imported = try parseBoundary(from: url)
        try commit(imported)
        return imported
    }

    /// Read and GATE a picked file without storing anything. Split out from
    /// `importBoundary(from:)` so the caller can ask "replace the boundary
    /// you already have?" between the two halves: asking before the file is
    /// known to be valid would demand an answer about a replacement that
    /// may never happen, and asking after the write would be asking about
    /// one that already did.
    public func parseBoundary(from url: URL) throws -> SurveyBoundary {
        // Files handed over by UIDocumentPicker live outside the app
        // container and need the scope opened around every read.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        return try SurveyBoundaryImporter.load(fileURL: url)
    }

    /// Put a parsed boundary in the one slot, replacing whatever was there.
    /// Callers own the decision; this method only carries it out.
    public func commit(_ boundary: SurveyBoundary) throws {
        try store.save(boundary)
        self.boundary = boundary
        loadFailure = nil
    }

    /// Commit a boundary the cruiser DREW. Deliberately a separate entry
    /// point from `importBoundary(from:)`: there is no file, no
    /// security-scoped URL and no CRS to confirm — a drawn outline is
    /// already WGS84 because the map handed the coordinates over in it.
    /// What it does share with the import path is the replacement rule:
    /// one boundary at a time, and this one takes the slot. The CALLER is
    /// responsible for having asked first when something was already
    /// loaded — see `MapSettingsSheet`'s replace confirmation. This method
    /// will not second-guess a decision the cruiser has already made.
    @discardableResult
    public func saveDrawn(_ draft: BoundaryDraft,
                          displayName: String) throws -> SurveyBoundary {
        let boundary = try draft.surveyBoundary(displayName: displayName)
        try store.save(boundary)
        self.boundary = boundary
        loadFailure = nil
        return boundary
    }

    public func remove() {
        store.clear()
        boundary = nil
        loadFailure = nil
    }
}
