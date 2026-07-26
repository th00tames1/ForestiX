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
                                          sourceFormat: boundary.sourceFormat)
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
        // Files handed over by UIDocumentPicker live outside the app
        // container and need the scope opened around every read.
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }

        let imported = try SurveyBoundaryImporter.load(fileURL: url)
        try store.save(imported)
        boundary = imported
        loadFailure = nil
        return imported
    }

    public func remove() {
        store.clear()
        boundary = nil
        loadFailure = nil
    }
}
