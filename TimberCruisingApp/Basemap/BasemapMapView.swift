// Interactive slippy-map canvas for the MAP-FIRST HOME
// (design/forestix-redesign-v2-maphome.html — screens ① map home and
// ② pin selected). Pure SwiftUI + web-mercator maths over TileCache —
// no MapKit — rendering TWO layers: the built-in satellite base
// (Esri World Imagery — imagery out of the box, zero setup) and, drawn
// on top of it, an optional user-configured XYZ overlay (contour /
// forest-service tiles, often transparent PNGs). The canvas-coloured
// background + faint tile grid only show through where a base tile is
// neither cached nor fetchable.
//
// Lives in the Basemap library target (deps: Common + Geo only), so it
// cannot import the app design system: every colour arrives via
// `BasemapStyle` from the hosting screen. Markers are the mock's
// teardrop pin — 30 pt dot, label inside, badge chips underneath —
// plus the pulsing blue you-dot.
//
// Tiles come from the on-disk TileCache first; misses are fetched over
// URLSession, stored back into the cache, and kept in a small in-memory
// LRU (~64 decoded images) so panning doesn't re-hit the disk.

import Foundation
import SwiftUI
import CoreGraphics
import Geo
#if canImport(ImageIO)
import ImageIO
#endif

// MARK: - Camera

/// Continuous map camera. `zoom` is fractional — tiles render at the
/// nearest integer level and are scaled — clamped to
/// `BasemapMapView.zoomRange` (3…24; past `maxTileZoom` the z-19 tiles
/// scale up, so dense stands can still be separated).
public struct BasemapCamera: Equatable, Sendable {
    public var latitude: Double
    public var longitude: Double
    public var zoom: Double

    public init(latitude: Double, longitude: Double, zoom: Double) {
        self.latitude = latitude
        self.longitude = longitude
        self.zoom = zoom
    }
}

// MARK: - Visible region

/// Axis-aligned lat/lon box of what the viewport currently shows.
/// The offline downloader feeds `ring` straight into
/// `OfflineBasemap.planJob(aoiRings:…)`.
public struct BasemapRegion: Equatable, Sendable {
    public let minLatitude: Double
    public let maxLatitude: Double
    public let minLongitude: Double
    public let maxLongitude: Double

    public init(minLatitude: Double, maxLatitude: Double,
                minLongitude: Double, maxLongitude: Double) {
        self.minLatitude = minLatitude
        self.maxLatitude = maxLatitude
        self.minLongitude = minLongitude
        self.maxLongitude = maxLongitude
    }

    /// Corner ring (NW → NE → SE → SW) in the shape `planJob` expects.
    public var ring: [CoordinateConversions.LatLon] {
        [
            CoordinateConversions.LatLon(latitude: maxLatitude, longitude: minLongitude),
            CoordinateConversions.LatLon(latitude: maxLatitude, longitude: maxLongitude),
            CoordinateConversions.LatLon(latitude: minLatitude, longitude: maxLongitude),
            CoordinateConversions.LatLon(latitude: minLatitude, longitude: minLongitude)
        ]
    }
}

// MARK: - Marker

/// One map marker. The default `.teardrop` is the tree pin from the v2
/// mock; `badge` renders as one small chip per character ("DH" →
/// [D][H]) — the mock's measurement-kind badges under the dot.
///
/// v3 (cruise mode) adds `.ring` — the plot ring marker: a hollow
/// 34 pt circle centred ON the coordinate (not bottom-anchored),
/// stroked and labelled in `tint`. `dashed: true` is the "planned"
/// style (hollow dashed); solid rings read active/done purely through
/// the tint the host passes (accent = active, ok-green = done).
public struct BasemapMarker: Identifiable, Equatable {

    public enum Shape: Equatable, Sendable {
        /// Bottom-anchored teardrop pin (tree).
        case teardrop
        /// Centre-anchored hollow ring (cruise plot).
        case ring(dashed: Bool)
    }

    public let id: String
    public let latitude: Double
    public let longitude: Double
    /// Short label inside the dot, e.g. "T3" (or "P2" on a ring).
    public let title: String
    public let tint: Color
    public let badge: String?
    public let shape: Shape

    public init(id: String, latitude: Double, longitude: Double,
                title: String, tint: Color, badge: String? = nil,
                shape: Shape = .teardrop) {
        self.id = id
        self.latitude = latitude
        self.longitude = longitude
        self.title = title
        self.tint = tint
        self.badge = badge
        self.shape = shape
    }
}

// MARK: - Guide line

/// v3 (cruise mode) navigation guide: one dashed great-line from the
/// cruiser (you-dot) to a planned plot, drawn in the mock's `.guide`
/// language (2.5 pt, dash 2 9, round caps). The whole navigation UI is
/// this line plus a host-rendered floating distance chip — there is no
/// separate navigation screen.
public struct BasemapGuideLine: Equatable {
    public let from: CoordinateConversions.LatLon
    public let to: CoordinateConversions.LatLon
    public let color: Color

    public init(from: CoordinateConversions.LatLon,
                to: CoordinateConversions.LatLon,
                color: Color) {
        self.from = from
        self.to = to
        self.color = color
    }
}

// MARK: - Survey boundary overlay

/// The imported survey boundary, ready to draw.
///
/// DRAW ORDER (fixed, and the reason this is a Canvas layer rather than
/// a SwiftUI overlay): satellite-or-OSM base → the user's XYZ overlay →
/// THIS → the sampling plot (`BasemapPlotOverlay`) → the app's own
/// content (plot pins, planned plots, the location marker) which lives
/// in views stacked above the Canvas. Being inside the Canvas is also
/// what makes the boundary hit-test-transparent: the pins sit in front
/// of it, so a tap meant for a pin can never land on the boundary.
public struct BasemapBoundaryOverlay: Equatable {

    public struct Shape: Equatable {
        public enum Kind: Equatable, Sendable { case polygon, line, point }
        public let kind: Kind
        /// Polygon: ring 0 outer, the rest holes. Line: one path.
        /// Point: one ring holding one position.
        public let rings: [[CoordinateConversions.LatLon]]

        public init(kind: Kind, rings: [[CoordinateConversions.LatLon]]) {
            self.kind = kind
            self.rings = rings
        }
    }

    public let shapes: [Shape]
    /// Outline colour. Drawn over a dark halo so it reads on BOTH bases
    /// (bright imagery and pale OSM street tiles).
    public let stroke: Color
    /// Semi-transparent polygon fill.
    public let fill: Color
    public let lineWidth: Double

    public init(shapes: [Shape], stroke: Color, fill: Color, lineWidth: Double = 2.5) {
        self.shapes = shapes
        self.stroke = stroke
        self.fill = fill
        self.lineWidth = lineWidth
    }

    public var isEmpty: Bool { shapes.isEmpty }
}

// MARK: - Planned areas

/// A CRUISE AREA the cruiser drew on this map — the outline a cruise is
/// laid out inside.
///
/// Separate from `BasemapBoundaryOverlay` because the two are different
/// objects to the cruiser and behave differently on screen: the imported
/// boundary is a file's geometry and is hit-test transparent, while an
/// area is the cruiser's own editable plan and TAKES TAPS — selecting it
/// is how its menu is reached. They also sit in different layers, areas
/// above the boundary, so an area drawn inside an imported stand reads as
/// being inside it.
///
/// `rings[0]` is the outer ring; the rest are holes. The ring may be open
/// or closed — the renderer closes it either way, so a stored polygon and
/// a half-drawn draft can be handed over unchanged.
public struct BasemapArea: Equatable, Identifiable {
    /// Echoed back through `onOverlayTap` when this area takes a tap.
    public let id: String
    public let rings: [[CoordinateConversions.LatLon]]
    /// Drawn heavier, with its corners marked — the cruiser has to be able
    /// to tell which of several outlines their next action applies to.
    public let selected: Bool
    /// Whether a SELECTED area marks its outer-ring vertices. On for the
    /// shapes a cruiser drags corner by corner; off for a circle, whose
    /// ring is 128 densified points — marked, it reads as a beaded rim
    /// rather than an outline, and every dot is a corner the cruiser cannot
    /// grab. An explicit flag rather than a vertex-count threshold, so a
    /// genuinely 30-corner hand-drawn stand keeps its corners.
    public let drawsCorners: Bool

    public init(id: String,
                rings: [[CoordinateConversions.LatLon]],
                selected: Bool = false,
                drawsCorners: Bool = true) {
        self.id = id
        self.rings = rings
        self.selected = selected
        self.drawsCorners = drawsCorners
    }
}

/// What a tap on the map landed on, once the markers have had their turn.
///
/// Both slots can be filled at once, and that is the whole reason this is
/// one value rather than two callbacks: an area and a plot overlap all the
/// time (a plot is laid INSIDE an area), and only the host knows which of
/// the two is selected right now and therefore which the cruiser meant
/// this time. The map reports what is under the finger and takes no view.
public struct BasemapOverlayHit: Equatable {
    public let plotID: String?
    public let areaID: String?

    public var isEmpty: Bool { plotID == nil && areaID == nil }
}

// MARK: - Sampling-plot overlay

/// THE SAMPLING PLOT, drawn on the map at TRUE GEOGRAPHIC SCALE.
///
/// Everything here is expressed in metres on the ground, never in
/// points: the renderer derives the on-screen scale from the camera at
/// the plot's own latitude, so the circle grows and shrinks with the
/// zoom exactly like the imagery under it. A cruiser can therefore read
/// "am I standing in the plot" straight off the map.
///
/// DRAW ORDER: above the imported survey boundary, below every
/// app-owned marker (the pins are views stacked on the Canvas, so a tap
/// meant for a pin still reaches the pin).
///
/// The Basemap target cannot see the app design system, so every colour
/// arrives from the host — as with `BasemapStyle` and
/// `BasemapBoundaryOverlay`. Strokes are drawn over the same dark halo
/// the boundary uses, because the drawing has to read on BOTH bases:
/// bright satellite imagery and pale OpenStreetMap street tiles.
public struct BasemapPlotOverlay: Equatable {

    /// One concentric RANGE RING inside the plot boundary: how far out it
    /// sits, and the label to draw on it. The label arrives ready-made
    /// because the renderer never converts units — the host owns "2 m"
    /// vs "20 ft".
    public struct Ring: Equatable, Sendable {
        public let radiusM: Double
        public let label: String

        public init(radiusM: Double, label: String) {
            self.radiusM = radiusM
            self.label = label
        }
    }

    /// WHERE THE CRUISER IS RELATIVE TO THIS PLOT — three states, never
    /// two.
    ///
    /// `unknown` is a first-class state, not a quiet fallback to
    /// `inside`. Before it existed the drawing for "no usable fix" was
    /// pixel-identical to `inside` — same calm tint, same solid
    /// boundary, no connector — so a cruiser glancing at the map read
    /// "you are in the plot" from a map that knew nothing at all. Which
    /// trees belong to a plot is decided on that reading.
    public enum CruiserState: Equatable, Sendable {
        /// A usable fix, inside the boundary.
        case inside
        /// A usable fix, beyond the boundary.
        case outside
        /// NO usable fix — none yet, or the last one is too old to be
        /// evidence of anything. The host decides what "too old" means.
        case unknown
    }

    public let center: CoordinateConversions.LatLon
    public let radiusM: Double
    public let rings: [Ring]
    /// The cruiser's LIVE fix. nil whenever `state` is `.unknown`: with
    /// nothing trustworthy to draw, the renderer draws no you-point and
    /// no connector.
    public let cruiser: CoordinateConversions.LatLon?
    /// Inside / outside / unknown. `.outside` and `.inside` are only
    /// ever passed with a `cruiser`.
    public let state: CruiserState
    /// Echoed back through `onOverlayTap` when the boundary is tapped.
    public let id: String

    /// Calm boundary / ring / centre ink.
    public let stroke: Color
    /// Boundary ink while the cruiser is OUTSIDE — the warning state.
    public let warnStroke: Color
    /// Boundary / ring / centre ink while the position is UNKNOWN — a
    /// neutral, deliberately un-signal-like grey. It must not be either
    /// of the other two inks: the whole point is that the drawing stops
    /// looking like an answer.
    public let unknownStroke: Color
    /// Translucent disc fill; the imagery must read through it.
    public let fill: Color
    /// Label ink inside the pills.
    public let ink: Color
    public let pillBackground: Color
    public let pillBorder: Color
    /// The words the drawing itself carries in the `.unknown` state, so
    /// the map states the gap instead of leaving it to the banner. The
    /// host owns the wording — it is the SAME phrase the banner uses.
    public let unknownLabel: String

    public init(center: CoordinateConversions.LatLon,
                radiusM: Double,
                rings: [Ring] = [],
                cruiser: CoordinateConversions.LatLon? = nil,
                state: CruiserState = .unknown,
                id: String = "plot",
                stroke: Color,
                warnStroke: Color,
                unknownStroke: Color,
                fill: Color,
                ink: Color,
                pillBackground: Color,
                pillBorder: Color,
                unknownLabel: String = "No position") {
        self.center = center
        self.radiusM = radiusM
        self.rings = rings
        self.cruiser = cruiser
        self.state = state
        self.id = id
        self.stroke = stroke
        self.warnStroke = warnStroke
        self.unknownStroke = unknownStroke
        self.fill = fill
        self.ink = ink
        self.pillBackground = pillBackground
        self.pillBorder = pillBorder
        self.unknownLabel = unknownLabel
    }

    /// True only for a live fix beyond the boundary — the ONE state that
    /// draws the connector home.
    var outside: Bool { state == .outside }

    /// Boundary ink for the current state — the ONE place the state
    /// changes the drawing's colour.
    var edgeStroke: Color {
        switch state {
        case .inside:  return stroke
        case .outside: return warnStroke
        case .unknown: return unknownStroke
        }
    }

    /// Ink for the INTERIOR detail (range rings, their labels, the
    /// compass badges). Follows the calm stroke while the position is
    /// known — inside and outside keep the exact drawing they shipped
    /// with, where only the boundary carries the warning — and greys out
    /// with everything else when it is not.
    var detailStroke: Color {
        state == .unknown ? unknownStroke : stroke
    }
}

// MARK: - Style

/// Colours injected by the host — the Basemap target cannot see the app
/// palette, and the map must follow the app's light/dark appearance.
public struct BasemapStyle {
    /// Background of the no-tile (and tiles-still-loading) canvas.
    public var canvas: Color
    /// Faint tile-boundary grid drawn under the tiles.
    public var grid: Color
    /// Teardrop border + you-dot ring (the app's surface colour).
    public var pinStroke: Color
    /// Label ink inside the teardrop (dark on the signal green).
    public var pinInk: Color
    public var badgeBackground: Color
    public var badgeBorder: Color
    public var badgeText: Color
    /// Halo ring around the selected pin.
    public var selectionHalo: Color
    /// Outline and fill of a cruise area (`BasemapArea`). Deliberately not
    /// the survey boundary's colour: an outline the cruiser drew to cruise
    /// inside and an outline a surveyor filed are different objects, and
    /// the map is where that difference is easiest to see.
    public var areaStroke: Color
    public var areaFill: Color

    public init(canvas: Color = Color(white: 0.93),
                grid: Color = Color(white: 0.82),
                pinStroke: Color = .white,
                pinInk: Color = .black,
                badgeBackground: Color = .white,
                badgeBorder: Color = Color(white: 0.82),
                badgeText: Color = Color(white: 0.35),
                selectionHalo: Color = Color.green.opacity(0.35),
                areaStroke: Color = Color.blue,
                areaFill: Color = Color.blue.opacity(0.12)) {
        self.canvas = canvas
        self.grid = grid
        self.pinStroke = pinStroke
        self.pinInk = pinInk
        self.badgeBackground = badgeBackground
        self.badgeBorder = badgeBorder
        self.badgeText = badgeText
        self.selectionHalo = selectionHalo
        self.areaStroke = areaStroke
        self.areaFill = areaFill
    }
}

// MARK: - Base map type

/// Which built-in BASE layer the map draws under everything else,
/// persisted as `tc.mapType`. Raw values are the on-disk contract shared
/// with the Android sibling — do not rename them.
///
/// Default is `.satellite`: that is what the app has always drawn, so an
/// existing install sees no change.
public enum BasemapType: String, CaseIterable, Sendable {
    case satellite
    case normal

    public static let `default`: BasemapType = .satellite

    public static func fromRaw(_ raw: String?) -> BasemapType {
        guard let raw, let v = BasemapType(rawValue: raw) else { return .default }
        return v
    }

    /// The tile provider this type draws.
    public var provider: TileCache.ProviderConfig {
        switch self {
        case .satellite: return .esriWorldImagery
        case .normal:    return .openStreetMap
        }
    }

    /// Attribution the provider's terms require on screen. Swaps with the
    /// layer — it is a licence obligation, not decoration.
    public var attribution: String {
        switch self {
        case .satellite: return TileCache.ProviderConfig.esriWorldImageryAttribution
        case .normal:    return TileCache.ProviderConfig.openStreetMapAttribution
        }
    }
}

// MARK: - Provider convenience

public extension TileCache.ProviderConfig {
    /// Built-in satellite base layer — ships with the app so the map
    /// shows imagery whenever online, zero setup. NOTE Esri's tile
    /// scheme is {z}/{y}/{x} (row before column); `resolvedURL(for:)`
    /// substitutes tokens by NAME, so the swapped order is honoured.
    static let esriWorldImagery = TileCache.ProviderConfig(
        urlTemplate: "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        fileExtension: "jpg",
        providerId: "esri-world-imagery")

    /// Attribution the imagery terms require on screen whenever the
    /// built-in base layer can draw.
    static let esriWorldImageryAttribution = "Esri · Maxar · Earthstar Geographics"

    /// Built-in STREET base layer — OpenStreetMap standard tiles. The
    /// alternative to satellite for cruisers working roads, parcels and
    /// labelled features rather than canopy.
    ///
    /// OSM's tile usage policy REQUIRES an identifying User-Agent (the
    /// operators block anonymous / generic clients outright), so the
    /// header rides on the provider config and every fetch path picks it
    /// up through `TileCache.request(for:)`.
    static let openStreetMap = TileCache.ProviderConfig(
        urlTemplate: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        fileExtension: "png",
        providerId: "osm-standard",
        requestHeaders: ["User-Agent": TileCache.ProviderConfig.osmUserAgent])

    /// Attribution the ODbL requires on screen whenever OSM tiles draw.
    static let openStreetMapAttribution = "© OpenStreetMap contributors"

    /// Descriptive, contactable client identifier — what the OSM tile
    /// policy asks for. Built from the app's own bundle version so a
    /// released build identifies itself precisely.
    static var osmUserAgent: String {
        let version = (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString")
            as? String) ?? "1.0"
        return "Forestix/\(version) (iOS timber-cruising app; +https://github.com/th00tames1/ForestiX)"
    }

    /// Build a config from a user-pasted XYZ template (Settings). The
    /// file extension is sniffed from the template tail; unknown
    /// endings fall back to png (the raster default).
    static func fromUserTemplate(_ template: String) -> TileCache.ProviderConfig {
        let path = template.lowercased()
            .split(separator: "?").first.map(String.init) ?? template.lowercased()
        var ext = "png"
        for candidate in ["png", "jpg", "jpeg", "webp"] where path.hasSuffix("." + candidate) {
            ext = candidate
            break
        }
        return TileCache.ProviderConfig(
            urlTemplate: template,
            fileExtension: ext,
            providerId: providerId(forURLTemplate: template))
    }
}

public extension TileCache {
    /// Canonical on-device root for interactive/offline basemap tiles.
    /// Application Support (not Caches) on purpose: a cruiser who
    /// pre-downloads tiles for a week in the woods must not have the OS
    /// silently purge them under disk pressure.
    static func defaultBasemapRoot() -> URL {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory,
                                in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        let dir = base
            .appendingPathComponent("Forestix", isDirectory: true)
            .appendingPathComponent("basemap-tiles", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

// MARK: - Tile loader

/// Disk-first tile source with an in-memory LRU of decoded images and
/// fetch-on-miss over URLSession. Misses are stored back into the
/// TileCache so the interactive map and the offline downloader share
/// one file tree. Failed URLs are remembered (no hot-loop refetching on
/// airplane mode) and retried when a pan/zoom gesture settles.
@MainActor
final class BasemapTileLoader: ObservableObject {

    /// Bumped whenever a fetched tile lands so the canvas re-renders.
    @Published private(set) var revision = 0

    private var cache: TileCache?
    private var images: [TileCache.Key: CGImage] = [:]
    private var lru: [TileCache.Key] = []
    private var inflight: Set<TileCache.Key> = []
    private var failed: Set<TileCache.Key> = []
    private let memoryCapacity = 64

    /// (Re)point the loader at a cache. Resets in-memory state — the
    /// provider (and therefore the tile imagery) changed.
    func attach(cache: TileCache?) {
        self.cache = cache
        images.removeAll()
        lru.removeAll()
        inflight.removeAll()
        failed.removeAll()
        revision += 1
    }

    /// Give transient network failures another chance. Called when a
    /// gesture ends — cheap, and self-healing after airplane mode.
    func retryFailed() {
        guard !failed.isEmpty else { return }
        failed.removeAll()
        revision += 1
    }

    /// Memory → disk → network, in that order. Returns nil while the
    /// tile is still on its way (the canvas colour shows through).
    func image(for key: TileCache.Key) -> CGImage? {
        if let hit = images[key] {
            touch(key)
            return hit
        }
        guard let cache else { return nil }
        if let data = cache.data(for: key), let decoded = Self.decode(data) {
            insert(decoded, for: key)
            return decoded
        }
        fetch(key)
        return nil
    }

    private func fetch(_ key: TileCache.Key) {
        guard let cache,
              !inflight.contains(key),
              !failed.contains(key),
              // Provider-owned request: carries OSM's required
              // identifying User-Agent when the OSM base is selected.
              let request = cache.request(for: key)
        else { return }
        inflight.insert(key)
        Task { [weak self] in
            var decoded: CGImage?
            var payload: Data?
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 200
                if (200..<300).contains(status), let image = Self.decode(data) {
                    decoded = image
                    payload = data
                }
            } catch {
                // Fall through — recorded as failed below.
            }
            guard let self else { return }
            self.inflight.remove(key)
            if let decoded, let payload {
                try? self.cache?.store(payload, for: key)
                self.insert(decoded, for: key)
                self.revision += 1
            } else {
                self.failed.insert(key)
            }
        }
    }

    private func insert(_ image: CGImage, for key: TileCache.Key) {
        images[key] = image
        lru.removeAll { $0 == key }
        lru.append(key)
        while lru.count > memoryCapacity {
            let evicted = lru.removeFirst()
            images[evicted] = nil
        }
    }

    private func touch(_ key: TileCache.Key) {
        guard lru.last != key else { return }
        lru.removeAll { $0 == key }
        lru.append(key)
    }

    nonisolated private static func decode(_ data: Data) -> CGImage? {
        #if canImport(ImageIO)
        guard let source = CGImageSourceCreateWithData(data as CFData, nil)
        else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
        #else
        return nil
        #endif
    }
}

// MARK: - Map view

public struct BasemapMapView: View {

    /// Interactive zoom bounds (slippy-map levels). The camera zooms
    /// well past the imagery's native maximum so trees standing a few
    /// metres apart resolve into separate pins — dense-stand field
    /// request.
    public static let zoomRange: ClosedRange<Double> = 3...24

    /// Deepest level tiles are FETCHED at (Esri World Imagery tops out
    /// around z19). Beyond it the renderer overzooms: the z-19 tiles
    /// draw scaled by 2^(zoom − 19).
    public static let maxTileZoom: Double = 19

    @Binding private var camera: BasemapCamera
    /// Built-in satellite base — drawn first, under everything.
    private let baseTileCache: TileCache?
    /// Optional user overlay (contour / forest-service tiles) — drawn
    /// on top of the base; transparent pixels let the imagery through.
    private let overlayTileCache: TileCache?
    /// Imported survey boundary — drawn ABOVE both tile layers and BELOW
    /// every app-owned marker.
    private let boundary: BasemapBoundaryOverlay?
    /// Cruise areas — above the imported boundary, below the sampling
    /// plot, and unlike the boundary they take taps (see `onOverlayTap`).
    private let areas: [BasemapArea]
    /// The cruiser's sampling plot at true geographic scale — drawn
    /// directly ON TOP of the survey boundary and under the pins.
    private let plotOverlay: BasemapPlotOverlay?
    private let markers: [BasemapMarker]
    private let selectedMarkerID: String?
    private let youLocation: CoordinateConversions.LatLon?
    private let guideLine: BasemapGuideLine?
    private let style: BasemapStyle
    private let onMarkerTap: (String) -> Void
    private let onMapTap: () -> Void
    /// A tap that landed on the sampling plot's boundary, on an area, or
    /// on both at once. Only fires when no marker took the tap first, and
    /// only when at least one of the two was hit — an empty hit goes to
    /// `onMapTap` instead.
    private let onOverlayTap: (BasemapOverlayHit) -> Void
    /// A press-and-hold that ended without the finger travelling — carries
    /// the coordinate under it. The host raises its own menu; the map has
    /// no opinion about what a long press means.
    private let onMapLongPress: (CoordinateConversions.LatLon) -> Void
    private let onCameraChange: (BasemapCamera, BasemapRegion) -> Void

    @StateObject private var baseLoader = BasemapTileLoader()
    @StateObject private var overlayLoader = BasemapTileLoader()
    /// Camera captured at pan-gesture start (translation is absolute).
    @State private var dragStart: BasemapCamera?
    /// Zoom captured at pinch-gesture start.
    @State private var zoomStart: Double?
    @State private var pulsing = false

    /// The mock's GPS blue (#3B82C4) — deliberately outside the signal
    /// palette so "you" never reads as a measurement tier.
    private let youBlue = Color(red: 0.231, green: 0.510, blue: 0.769)

    public init(camera: Binding<BasemapCamera>,
                baseTileCache: TileCache?,
                overlayTileCache: TileCache? = nil,
                boundary: BasemapBoundaryOverlay? = nil,
                areas: [BasemapArea] = [],
                plotOverlay: BasemapPlotOverlay? = nil,
                markers: [BasemapMarker] = [],
                selectedMarkerID: String? = nil,
                youLocation: CoordinateConversions.LatLon? = nil,
                guideLine: BasemapGuideLine? = nil,
                style: BasemapStyle = BasemapStyle(),
                onMarkerTap: @escaping (String) -> Void = { _ in },
                onMapTap: @escaping () -> Void = {},
                onOverlayTap: @escaping (BasemapOverlayHit) -> Void = { _ in },
                onMapLongPress: @escaping (CoordinateConversions.LatLon) -> Void = { _ in },
                onCameraChange: @escaping (BasemapCamera, BasemapRegion) -> Void = { _, _ in }) {
        self._camera = camera
        self.baseTileCache = baseTileCache
        self.overlayTileCache = overlayTileCache
        self.boundary = boundary
        self.areas = areas
        self.plotOverlay = plotOverlay
        self.markers = markers
        self.selectedMarkerID = selectedMarkerID
        self.youLocation = youLocation
        self.guideLine = guideLine
        self.style = style
        self.onMarkerTap = onMarkerTap
        self.onMapTap = onMapTap
        self.onOverlayTap = onOverlayTap
        self.onMapLongPress = onMapLongPress
        self.onCameraChange = onCameraChange
    }

    public var body: some View {
        GeometryReader { geo in
            let size = geo.size
            let baseTiles = tileDrawList(size: size, cache: baseTileCache,
                                         loader: baseLoader)
            let overlayTiles = tileDrawList(size: size, cache: overlayTileCache,
                                            loader: overlayLoader)
            ZStack {
                Canvas { context, canvasSize in
                    context.fill(Path(CGRect(origin: .zero, size: canvasSize)),
                                 with: .color(style.canvas))
                    // Faint tile grid — shows through only where a base
                    // tile is neither cached nor fetchable, and gives
                    // pan feedback wherever tiles haven't arrived yet.
                    context.stroke(Self.gridPath(camera: camera, size: canvasSize),
                                   with: .color(style.grid), lineWidth: 0.5)
                    for tile in baseTiles {
                        context.draw(tile.image, in: tile.rect)
                    }
                    // Overlay above the base — transparent PNG overlays
                    // (contours etc.) composite over the imagery.
                    for tile in overlayTiles {
                        context.draw(tile.image, in: tile.rect)
                    }
                    // Imported survey boundary — above BOTH tile layers,
                    // below every app-owned marker (those are views
                    // stacked on this Canvas, so they also take the taps).
                    if let boundary, !boundary.isEmpty {
                        Self.drawBoundary(boundary, camera: camera,
                                          size: canvasSize, context: &context)
                    }
                    // Cruise areas, between the imported boundary and the
                    // sampling plot: an area drawn inside an imported
                    // stand reads as being inside it, and a plot laid
                    // inside an area reads as being inside that.
                    if !areas.isEmpty {
                        Self.drawAreas(areas, style: style, camera: camera,
                                       size: canvasSize, context: &context)
                    }
                    // THE SAMPLING PLOT — directly on top of the imported
                    // boundary and under everything the app owns, so a
                    // plot inside an imported stand reads as being inside
                    // it, and the pins still sit in front of both.
                    if let plotOverlay {
                        Self.drawPlot(plotOverlay, camera: camera,
                                      size: canvasSize, context: &context)
                    }
                    // Navigation guide — dashed you→plot line under the
                    // pins (they are separate views above the Canvas).
                    // Projected without clipping so the line still draws
                    // when either endpoint is off-screen.
                    if let guide = guideLine {
                        let a = Self.screenPoint(latitude: guide.from.latitude,
                                                 longitude: guide.from.longitude,
                                                 camera: camera,
                                                 viewportSize: canvasSize)
                        let b = Self.screenPoint(latitude: guide.to.latitude,
                                                 longitude: guide.to.longitude,
                                                 camera: camera,
                                                 viewportSize: canvasSize)
                        var path = Path()
                        path.move(to: a)
                        path.addLine(to: b)
                        context.stroke(path, with: .color(guide.color),
                                       style: StrokeStyle(lineWidth: 2.5,
                                                          lineCap: .round,
                                                          dash: [2, 9]))
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture(count: 2) { point in
                    doubleTapZoom(at: point, size: size)
                }
                // A tap that lands on the sampling plot's boundary or
                // inside an area belongs to that object; everything else
                // clears the selection. Routed HERE rather than through
                // tappable shapes stacked over the Canvas on purpose: a
                // hit-testing view that size would also swallow the pan
                // gesture, and panning across your own plot is the most
                // ordinary thing a cruiser does. Markers are views ABOVE
                // the Canvas, so they consume their own taps first.
                //
                // BOTH hits are reported, never just the topmost. An area
                // and the plots laid inside it overlap by construction, and
                // the map cannot know which one the cruiser meant on this
                // particular tap — the host can, because it knows what is
                // selected right now.
                .onTapGesture { point in
                    let plotHit = plotOverlay.flatMap {
                        Self.plotHitTest($0, at: point, camera: camera, size: size)
                            ? $0.id : nil
                    }
                    let areaHit = Self.areaHitTest(areas, at: point,
                                                   camera: camera, size: size)
                    let hit = BasemapOverlayHit(plotID: plotHit, areaID: areaHit)
                    if hit.isEmpty { onMapTap() } else { onOverlayTap(hit) }
                }
                .gesture(panGesture(size: size)
                    .simultaneously(with: pinchGesture(size: size)))
                // PRESS AND HOLD → a coordinate for the host's planning
                // menu. Simultaneous, not exclusive, because the pan and
                // pinch above are how the cruiser reaches the ground they
                // want to press on, and a long press that stole the drag
                // would make the map feel stuck. The cost of running
                // alongside is that a hold-then-drag would fire on release
                // too, so `longPressGesture` refuses any press whose finger
                // travelled — see there.
                .simultaneousGesture(longPressGesture(size: size))

                if let you = youLocation,
                   let point = screenPoint(latitude: you.latitude,
                                           longitude: you.longitude,
                                           size: size) {
                    youDot.position(point)
                }

                ForEach(markers) { marker in
                    if let point = screenPoint(latitude: marker.latitude,
                                               longitude: marker.longitude,
                                               size: size) {
                        markerView(marker)
                            .position(x: point.x,
                                      y: point.y - markerAnchorOffset(marker))
                            .zIndex(marker.id == selectedMarkerID ? 2 : 1)
                    }
                }
            }
            .onAppear {
                baseLoader.attach(cache: baseTileCache)
                overlayLoader.attach(cache: overlayTileCache)
                emitCamera(size: size)
            }
            .onChange(of: baseTileCache?.provider.providerId) { _, _ in
                baseLoader.attach(cache: baseTileCache)
            }
            .onChange(of: overlayTileCache?.provider.providerId) { _, _ in
                overlayLoader.attach(cache: overlayTileCache)
            }
            .onChange(of: geo.size) { _, newSize in
                emitCamera(size: newSize)
            }
        }
        .clipped()
    }

    // MARK: Screen-point accessor (host overlays)

    /// Pure projection of a coordinate into viewport points for a given
    /// camera — the same maths the view uses, callable by the host to
    /// float its own overlays over the map (e.g. the cruise-mode live
    /// distance chip on the navigation guide line). No clipping: points
    /// off-screen come back outside the viewport bounds.
    public static func screenPoint(latitude: Double, longitude: Double,
                                   camera: BasemapCamera,
                                   viewportSize: CGSize) -> CGPoint {
        let worldPts = worldPoints(zoom: camera.zoom)
        let x = (worldX(longitude: longitude)
            - worldX(longitude: camera.longitude)) * worldPts
            + Double(viewportSize.width) / 2
        let y = (worldY(latitude: latitude)
            - worldY(latitude: camera.latitude)) * worldPts
            + Double(viewportSize.height) / 2
        return CGPoint(x: x, y: y)
    }

    /// The exact inverse of `screenPoint(latitude:longitude:camera:
    /// viewportSize:)` — where on the ground a point on screen is.
    ///
    /// Needed by any host that lets the cruiser MOVE something with a
    /// finger rather than just look at it (the boundary editor's corner
    /// and edge handles). Derived from the same normalised-world maths as
    /// the forward projection, so a coordinate round-trips through both
    /// unchanged, and a handle dragged one pixel moves one pixel's worth
    /// of ground.
    public static func coordinate(at point: CGPoint,
                                  camera: BasemapCamera,
                                  viewportSize: CGSize) -> CoordinateConversions.LatLon {
        let worldPts = worldPoints(zoom: camera.zoom)
        let x = worldX(longitude: camera.longitude)
            + (Double(point.x) - Double(viewportSize.width) / 2) / worldPts
        let y = worldY(latitude: camera.latitude)
            + (Double(point.y) - Double(viewportSize.height) / 2) / worldPts
        // Clamped to one world copy: a drag past the antimeridian or into
        // the Mercator pole cap has no sensible boundary meaning, and
        // wrapping silently would fling a corner to the far side of the
        // planet.
        return CoordinateConversions.LatLon(
            latitude: latitude(fromWorldY: min(max(y, 0), 1)),
            longitude: longitude(fromWorldX: min(max(x, 0), 1)))
    }

    // MARK: Visible-region accessor (offline downloader)

    /// Pure projection of a camera + viewport into the lat/lon box it
    /// shows — the same maths the view uses, callable by the host at
    /// any time (e.g. "Download visible area").
    public static func visibleRegion(camera: BasemapCamera,
                                     viewportSize: CGSize) -> BasemapRegion {
        let worldPts = worldPoints(zoom: camera.zoom)
        let cx = worldX(longitude: camera.longitude)
        let cy = worldY(latitude: camera.latitude)
        let halfW = viewportSize.width > 0 ? Double(viewportSize.width) / 2 / worldPts : 0
        let halfH = viewportSize.height > 0 ? Double(viewportSize.height) / 2 / worldPts : 0
        let minX = min(max(cx - halfW, 0), 1)
        let maxX = min(max(cx + halfW, 0), 1)
        let minY = min(max(cy - halfH, 0), 1)
        let maxY = min(max(cy + halfH, 0), 1)
        // World y grows southwards: minY is the NORTH edge.
        return BasemapRegion(
            minLatitude: latitude(fromWorldY: maxY),
            maxLatitude: latitude(fromWorldY: minY),
            minLongitude: longitude(fromWorldX: minX),
            maxLongitude: longitude(fromWorldX: maxX))
    }

    // MARK: Tiles

    private struct TileDraw {
        let image: Image
        let rect: CGRect
    }

    /// Compute the visible tile rects and resolve their images (memory /
    /// disk / kick off a fetch) for one layer. Runs in `body` — on the
    /// main actor — so the Canvas closure only consumes plain values.
    private func tileDrawList(size: CGSize, cache: TileCache?,
                              loader: BasemapTileLoader) -> [TileDraw] {
        _ = loader.revision   // re-render whenever a fetched tile lands
        guard cache != nil, size.width > 0, size.height > 0 else { return [] }
        let tz = Int(min(max(camera.zoom.rounded(), Self.zoomRange.lowerBound),
                         Self.maxTileZoom))
        let n = 1 << tz
        let tileSize = 256 * pow(2, camera.zoom - Double(tz))
        let cx = Self.worldX(longitude: camera.longitude) * Double(n)
        let cy = Self.worldY(latitude: camera.latitude) * Double(n)
        let halfW = Double(size.width) / 2
        let halfH = Double(size.height) / 2
        let minX = max(0, Int(floor(cx - halfW / tileSize)))
        let maxX = min(n - 1, Int(floor(cx + halfW / tileSize)))
        let minY = max(0, Int(floor(cy - halfH / tileSize)))
        let maxY = min(n - 1, Int(floor(cy + halfH / tileSize)))
        guard minX <= maxX, minY <= maxY else { return [] }

        var out: [TileDraw] = []
        for y in minY...maxY {
            for x in minX...maxX {
                let key = TileCache.Key(z: tz, x: x, y: y)
                guard let cg = loader.image(for: key) else { continue }
                let rect = CGRect(
                    x: (Double(x) - cx) * tileSize + halfW,
                    y: (Double(y) - cy) * tileSize + halfH,
                    // +0.5 pt bleed hides hairline seams between tiles
                    // at fractional zoom scales.
                    width: tileSize + 0.5,
                    height: tileSize + 0.5)
                out.append(TileDraw(image: Image(decorative: cg, scale: 1),
                                    rect: rect))
            }
        }
        return out
    }

    /// Tile-boundary grid at the current zoom — moves with the camera so
    /// panning has spatial feedback even with zero tiles on disk.
    private static func gridPath(camera: BasemapCamera, size: CGSize) -> Path {
        var path = Path()
        guard size.width > 0, size.height > 0 else { return path }
        let tz = Int(min(max(camera.zoom.rounded(), zoomRange.lowerBound),
                         maxTileZoom))
        let n = 1 << tz
        let tileSize = 256 * pow(2, camera.zoom - Double(tz))
        let cx = worldX(longitude: camera.longitude) * Double(n)
        let cy = worldY(latitude: camera.latitude) * Double(n)
        let halfW = Double(size.width) / 2
        let halfH = Double(size.height) / 2
        let minX = Int(floor(cx - halfW / tileSize))
        let maxX = Int(floor(cx + halfW / tileSize))
        let minY = Int(floor(cy - halfH / tileSize))
        let maxY = Int(floor(cy + halfH / tileSize))
        for x in minX...(maxX + 1) {
            let sx = (Double(x) - cx) * tileSize + halfW
            path.move(to: CGPoint(x: sx, y: 0))
            path.addLine(to: CGPoint(x: sx, y: size.height))
        }
        for y in minY...(maxY + 1) {
            let sy = (Double(y) - cy) * tileSize + halfH
            path.move(to: CGPoint(x: 0, y: sy))
            path.addLine(to: CGPoint(x: size.width, y: sy))
        }
        return path
    }

    // MARK: Survey boundary

    /// Project + stroke the imported boundary. Polygons get a
    /// semi-transparent fill plus an outline; lines get the outline only;
    /// points get a small ringed dot. Everything is drawn over a dark
    /// halo so the boundary reads on both bases — a thin bright line
    /// disappears into pale OSM street tiles, and a thin dark line
    /// disappears into shaded canopy on the satellite base.
    private static func drawBoundary(_ boundary: BasemapBoundaryOverlay,
                                     camera: BasemapCamera,
                                     size: CGSize,
                                     context: inout GraphicsContext) {
        // A shape entirely outside a generously padded viewport is
        // skipped before any of its vertices are projected.
        let pad = 64.0
        let viewport = CGRect(x: -pad, y: -pad,
                              width: size.width + pad * 2,
                              height: size.height + pad * 2)

        var areaPath = Path()
        var outlinePath = Path()
        var dots: [CGPoint] = []

        for shape in boundary.shapes {
            switch shape.kind {
            case .point:
                guard let p = shape.rings.first?.first else { continue }
                let pt = screenPoint(latitude: p.latitude, longitude: p.longitude,
                                     camera: camera, viewportSize: size)
                if viewport.contains(pt) { dots.append(pt) }
            case .polygon, .line:
                let closed = (shape.kind == .polygon)
                for ring in shape.rings {
                    guard let sub = projectedPath(ring, camera: camera,
                                                  size: size, viewport: viewport,
                                                  closed: closed)
                    else { continue }
                    if closed { areaPath.addPath(sub) }
                    outlinePath.addPath(sub)
                }
            }
        }

        if !areaPath.isEmpty {
            // Even-odd so inner rings punch holes in the outer ring.
            context.fill(areaPath, with: .color(boundary.fill), style: FillStyle(eoFill: true))
        }
        if !outlinePath.isEmpty {
            context.stroke(outlinePath, with: .color(.black.opacity(0.38)),
                           style: StrokeStyle(lineWidth: boundary.lineWidth + 2.2,
                                              lineCap: .round, lineJoin: .round))
            context.stroke(outlinePath, with: .color(boundary.stroke),
                           style: StrokeStyle(lineWidth: boundary.lineWidth,
                                              lineCap: .round, lineJoin: .round))
        }
        for dot in dots {
            let r = boundary.lineWidth + 2.0
            let rect = CGRect(x: dot.x - r, y: dot.y - r, width: r * 2, height: r * 2)
            context.fill(Path(ellipseIn: rect), with: .color(boundary.fill))
            context.stroke(Path(ellipseIn: rect), with: .color(.black.opacity(0.38)),
                           lineWidth: boundary.lineWidth + 1.6)
            context.stroke(Path(ellipseIn: rect), with: .color(boundary.stroke),
                           lineWidth: boundary.lineWidth)
        }
    }

    /// Project one ring/path, dropping vertices that land within ~0.6 pt
    /// of the previous one (a 40 000-vertex cadastral boundary zoomed out
    /// otherwise re-strokes the same pixels thousands of times). Returns
    /// nil when the whole ring sits off-screen.
    private static func projectedPath(_ ring: [CoordinateConversions.LatLon],
                                      camera: BasemapCamera,
                                      size: CGSize,
                                      viewport: CGRect,
                                      closed: Bool) -> Path? {
        guard ring.count >= 2 else { return nil }
        var points: [CGPoint] = []
        points.reserveCapacity(ring.count)
        var minX = Double.infinity, maxX = -Double.infinity
        var minY = Double.infinity, maxY = -Double.infinity
        var last: CGPoint?
        for p in ring {
            let pt = screenPoint(latitude: p.latitude, longitude: p.longitude,
                                 camera: camera, viewportSize: size)
            minX = min(minX, pt.x); maxX = max(maxX, pt.x)
            minY = min(minY, pt.y); maxY = max(maxY, pt.y)
            if let last, abs(pt.x - last.x) < 0.6, abs(pt.y - last.y) < 0.6 { continue }
            points.append(pt)
            last = pt
        }
        let bounds = CGRect(x: minX, y: minY,
                            width: max(maxX - minX, 0.01),
                            height: max(maxY - minY, 0.01))
        guard bounds.intersects(viewport) else { return nil }
        guard points.count >= 2 else { return nil }
        var path = Path()
        path.move(to: points[0])
        for pt in points.dropFirst() { path.addLine(to: pt) }
        if closed { path.closeSubpath() }
        return path
    }

    // MARK: Cruise areas

    /// Corner dot on a selected area. Purely a read-out — the draggable
    /// handles the editor puts up are views, far bigger, and live in the
    /// host. This just says "these are the corners you would be moving".
    private static let areaCornerRadius: CGFloat = 3.5

    private static func drawAreas(_ areas: [BasemapArea],
                                  style: BasemapStyle,
                                  camera: BasemapCamera,
                                  size: CGSize,
                                  context: inout GraphicsContext) {
        let pad = 64.0
        let viewport = CGRect(x: -pad, y: -pad,
                              width: size.width + pad * 2,
                              height: size.height + pad * 2)
        // Selected areas draw last so a smaller area sitting inside a
        // bigger one is never buried by the one the cruiser did not pick.
        for area in areas.sorted(by: { !$0.selected && $1.selected }) {
            var areaPath = Path()
            var outlinePath = Path()
            for ring in area.rings {
                guard let sub = projectedPath(ring, camera: camera, size: size,
                                              viewport: viewport, closed: true)
                else { continue }
                areaPath.addPath(sub)
                outlinePath.addPath(sub)
            }
            guard !outlinePath.isEmpty else { continue }
            context.fill(areaPath,
                         with: .color(area.selected
                                      ? style.areaFill.opacity(0.9)
                                      : style.areaFill),
                         style: FillStyle(eoFill: true))
            let width: Double = area.selected ? 4.0 : 2.5
            context.stroke(outlinePath, with: .color(.black.opacity(0.38)),
                           style: StrokeStyle(lineWidth: width + 2.2,
                                              lineCap: .round, lineJoin: .round))
            context.stroke(outlinePath, with: .color(style.areaStroke),
                           style: StrokeStyle(lineWidth: width,
                                              lineCap: .round, lineJoin: .round))
            guard area.selected, area.drawsCorners,
                  let outer = area.rings.first else { continue }
            for corner in outer {
                let pt = screenPoint(latitude: corner.latitude,
                                     longitude: corner.longitude,
                                     camera: camera, viewportSize: size)
                guard viewport.contains(pt) else { continue }
                let rect = CGRect(x: pt.x - areaCornerRadius,
                                  y: pt.y - areaCornerRadius,
                                  width: areaCornerRadius * 2,
                                  height: areaCornerRadius * 2)
                context.fill(Path(ellipseIn: rect), with: .color(style.areaStroke))
                context.stroke(Path(ellipseIn: rect),
                               with: .color(style.pinStroke), lineWidth: 1.5)
            }
        }
    }

    /// Which area a tap landed in — the SMALLEST one, by projected
    /// bounding box, so an area nested inside a larger one is still
    /// reachable. Whole-interior rather than an outline band: an area is
    /// selected by tapping the ground it covers, which is how a cruiser
    /// points at it, and a band would be unfindable on a stand the size
    /// of the screen.
    static func areaHitTest(_ areas: [BasemapArea],
                            at point: CGPoint,
                            camera: BasemapCamera,
                            size: CGSize) -> String? {
        var best: (id: String, extent: Double)?
        for area in areas {
            guard let outer = area.rings.first, outer.count >= 3 else { continue }
            let projected = outer.map {
                screenPoint(latitude: $0.latitude, longitude: $0.longitude,
                            camera: camera, viewportSize: size)
            }
            guard pointInScreenRing(point, ring: projected) else { continue }
            // Holes are holes: a tap in one is a tap on whatever is under
            // the area, exactly as the even-odd fill draws it.
            let inHole = area.rings.dropFirst().contains { hole in
                hole.count >= 3 && pointInScreenRing(point, ring: hole.map {
                    screenPoint(latitude: $0.latitude, longitude: $0.longitude,
                                camera: camera, viewportSize: size)
                })
            }
            if inHole { continue }
            // Widths and heights are pulled out and annotated rather than
            // multiplied inside one `Double(…)`. Written as a single
            // expression, the CGFloat-vs-Double overload search across four
            // optional-coalesced operands ran past the type checker's
            // per-expression budget and failed the build outright. Same
            // arithmetic, same result.
            let xs = projected.map(\.x), ys = projected.map(\.y)
            let width: Double = Double((xs.max() ?? 0) - (xs.min() ?? 0))
            let height: Double = Double((ys.max() ?? 0) - (ys.min() ?? 0))
            let extent = width * height
            if best == nil || extent < best!.extent {
                best = (area.id, extent)
            }
        }
        return best?.id
    }

    /// Crossing-number point-in-polygon in viewport points. Screen space
    /// rather than lat/lon so the answer matches what was drawn, including
    /// at the projection's extremes.
    private static func pointInScreenRing(_ p: CGPoint, ring: [CGPoint]) -> Bool {
        guard ring.count >= 3 else { return false }
        var inside = false
        var j = ring.count - 1
        for i in 0..<ring.count {
            let a = ring[i], b = ring[j]
            if (a.y > p.y) != (b.y > p.y),
               p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x {
                inside.toggle()
            }
            j = i
        }
        return inside
    }

    // MARK: Sampling plot

    /// Points on screen per metre on the ground for a plot-sized circle
    /// at `origin`. Derived by projecting a point one metre due NORTH of
    /// the origin and measuring the drop, so the scale comes from the
    /// live camera rather than from any hard-coded zoom assumption.
    /// Web mercator is conformal, so over a circle a few tens of metres
    /// across the same factor holds in every direction.
    private static func pointsPerMetre(at origin: CoordinateConversions.LatLon,
                                       camera: BasemapCamera,
                                       size: CGSize) -> Double {
        let a = screenPoint(latitude: origin.latitude,
                            longitude: origin.longitude,
                            camera: camera, viewportSize: size)
        let north = CoordinateConversions.toLatLon(
            enu: .init(east: 0, north: 1), origin: origin)
        let b = screenPoint(latitude: north.latitude,
                            longitude: north.longitude,
                            camera: camera, viewportSize: size)
        return abs(a.y - b.y)
    }

    /// Below this on-screen radius the rings, labels and compass badges
    /// are dropped: at a whole-block zoom they collapse into an
    /// unreadable smudge and the plot reads better as a plain disc.
    private static let plotDetailMinRadiusPt: Double = 26

    /// Minimum gap, in points, between two ring labels — and between the
    /// centre mark and the first of them.
    ///
    /// The labels all sit on the same 45° ray (chosen so they never
    /// collide with the N/E/S/W badges), so the distance between two of
    /// them IS the difference in their on-screen radii. A pill is about
    /// 15 pt tall and 26 pt wide; along a diagonal, clearing the taller
    /// dimension needs ≈ 15 × √2 ≈ 21 pt. Anything closer overlaps.
    ///
    /// At the low end of `plotDetailMinRadiusPt` the ring interval is
    /// only ~6 pt, which is exactly how four labels used to stack into
    /// one illegible block. Labels are now DROPPED — greedily, outward
    /// from the centre — until the survivors are this far apart, so a
    /// small plot shows its dashed rings with one or two labels (or
    /// none) instead of a smudge, and a large one is unchanged.
    private static let plotRingLabelMinGapPt: Double = 21

    /// Tap slop, in points — half a 48 pt target.
    private static let plotHitRadiusPt: Double = 24

    /// N / E / S / W, by TRUE bearing (0° = true north, clockwise). The
    /// renderer projects each bearing into a real coordinate, so the
    /// badges stay correct however the map is oriented and however the
    /// phone is held.
    private static let plotCompassBadges: [(bearingDeg: Double, text: String)] = [
        (0, "N"), (90, "E"), (180, "S"), (270, "W")
    ]

    /// A tap is ON the plot when it lands on the BOUNDARY BAND — within
    /// a finger's width either side of the drawn circle. Deliberately
    /// NOT the whole disc: zoomed in, the disc covers the screen and
    /// every tap anywhere would become a plot tap, with no way left to
    /// dismiss a peek or just touch the map. The band is also skipped
    /// while the circle is smaller than the band itself, where the plot
    /// is a blob under its own pin.
    static func plotHitTest(_ plot: BasemapPlotOverlay,
                            at point: CGPoint,
                            camera: BasemapCamera,
                            size: CGSize) -> Bool {
        let centre = screenPoint(latitude: plot.center.latitude,
                                 longitude: plot.center.longitude,
                                 camera: camera, viewportSize: size)
        let radiusPt = pointsPerMetre(at: plot.center, camera: camera,
                                      size: size) * plot.radiusM
        guard radiusPt.isFinite, radiusPt > plotHitRadiusPt else { return false }
        let dx = Double(point.x - centre.x)
        let dy = Double(point.y - centre.y)
        let d = (dx * dx + dy * dy).squareRoot()
        return abs(d - radiusPt) <= plotHitRadiusPt
    }

    /// Draw the sampling plot: translucent disc, concentric labelled
    /// range rings, the boundary, the centre mark, the true-bearing
    /// compass badges, and — while the cruiser is OUTSIDE — a dotted
    /// connector from the centre to where they are standing.
    ///
    /// THREE STATES, THREE DRAWINGS, no two of them alike:
    ///   • inside  — calm ink, solid 2.4 pt boundary;
    ///   • outside — warn ink, heavier 3.6 pt boundary, dotted connector
    ///     back to the centre;
    ///   • unknown — neutral grey ink, boundary DASHED (a broken line is
    ///     the one shape neither of the other two uses), and the plot
    ///     carries the host's "no position" words at its centre.
    /// The unknown state used to render exactly like inside, which made
    /// the map assert a position it did not have.
    ///
    /// Every stroke goes down twice: a dark halo first, the colour on
    /// top. That is the same treatment the imported boundary uses, and
    /// it is what keeps the plot legible on bright satellite imagery AND
    /// on the pale OpenStreetMap street base.
    private static func drawPlot(_ plot: BasemapPlotOverlay,
                                 camera: BasemapCamera,
                                 size: CGSize,
                                 context: inout GraphicsContext) {
        let centre = screenPoint(latitude: plot.center.latitude,
                                 longitude: plot.center.longitude,
                                 camera: camera, viewportSize: size)
        let scale = pointsPerMetre(at: plot.center, camera: camera, size: size)
        let radiusPt = scale * plot.radiusM
        guard centre.x.isFinite, centre.y.isFinite,
              radiusPt.isFinite, radiusPt > 0.5
        else { return }

        // Cull when neither the plot nor the cruiser is anywhere near
        // the viewport — the connector has to survive a centre that is
        // off-screen, so the you-point joins the culling box.
        let pad = 80.0
        let viewport = CGRect(x: -pad, y: -pad,
                              width: size.width + pad * 2,
                              height: size.height + pad * 2)
        var bounds = CGRect(x: centre.x - radiusPt, y: centre.y - radiusPt,
                            width: radiusPt * 2, height: radiusPt * 2)
        let youPoint: CGPoint? = plot.cruiser.map {
            screenPoint(latitude: $0.latitude, longitude: $0.longitude,
                        camera: camera, viewportSize: size)
        }
        if let youPoint { bounds = bounds.union(CGRect(origin: youPoint, size: .zero)) }
        guard bounds.intersects(viewport) else { return }

        let halo = Color.black.opacity(0.38)
        let edge = plot.edgeStroke
        let detail = plot.detailStroke
        let unknown = plot.state == .unknown
        let boundaryWidth: Double = plot.outside ? 3.6 : 2.4
        // A DASHED boundary is the unknown state's own shape. Colour
        // alone would not carry it: the map is drawn over satellite
        // imagery and over pale street tiles, and a grey ring on either
        // can read as just a differently-lit calm ring. A broken line
        // cannot be mistaken for a solid one at any zoom, in any light,
        // by anyone.
        let boundaryStyle = StrokeStyle(lineWidth: boundaryWidth,
                                        dash: unknown ? [7, 5] : [])
        let showDetail = radiusPt >= plotDetailMinRadiusPt

        func circle(_ r: Double) -> Path {
            Path(ellipseIn: CGRect(x: centre.x - r, y: centre.y - r,
                                   width: r * 2, height: r * 2))
        }

        // 1. Translucent disc — the imagery has to read through it.
        context.fill(circle(radiusPt), with: .color(plot.fill))

        // 2. Range rings inside the boundary, finely dashed so they can
        //    never be mistaken for the boundary itself.
        if showDetail {
            for ring in plot.rings {
                let r = scale * ring.radiusM
                guard r > 4, r < radiusPt - 1 else { continue }
                context.stroke(circle(r), with: .color(halo),
                               style: StrokeStyle(lineWidth: 2.6, dash: [5, 4]))
                context.stroke(circle(r), with: .color(detail.opacity(0.85)),
                               style: StrokeStyle(lineWidth: 1, dash: [5, 4]))
            }
        }

        // 3. The boundary. Heavier and in the warn colour when the
        //    cruiser is outside it — the emphasis IS the warning — and
        //    broken, in neutral grey, when there is no position to judge
        //    it against.
        context.stroke(circle(radiusPt), with: .color(halo),
                       style: StrokeStyle(lineWidth: boundaryWidth + 2.2,
                                          dash: boundaryStyle.dash))
        context.stroke(circle(radiusPt), with: .color(edge),
                       style: boundaryStyle)

        // 4. Dotted connector back to the centre, drawn only when the
        //    cruiser is known to be outside — the direction home.
        if plot.outside, let youPoint {
            var line = Path()
            line.move(to: centre)
            line.addLine(to: youPoint)
            context.stroke(line, with: .color(halo),
                           style: StrokeStyle(lineWidth: 4.4, lineCap: .round,
                                              dash: [1, 7]))
            context.stroke(line, with: .color(edge),
                           style: StrokeStyle(lineWidth: 2.4, lineCap: .round,
                                              dash: [1, 7]))
        }

        // 5. Centre mark — a cross, never a dot: a dot at this size is
        //    indistinguishable from a tree pin.
        var cross = Path()
        cross.move(to: CGPoint(x: centre.x - 8, y: centre.y))
        cross.addLine(to: CGPoint(x: centre.x + 8, y: centre.y))
        cross.move(to: CGPoint(x: centre.x, y: centre.y - 8))
        cross.addLine(to: CGPoint(x: centre.x, y: centre.y + 8))
        context.stroke(cross, with: .color(halo),
                       style: StrokeStyle(lineWidth: 4.4, lineCap: .round))
        context.stroke(cross, with: .color(edge),
                       style: StrokeStyle(lineWidth: 2, lineCap: .round))

        // 6. THE UNKNOWN MARKING, in words, on the drawing itself —
        //    drawn at EVERY zoom, before the `showDetail` gate, because
        //    a plot too small for range rings is exactly the case where
        //    the grey disc alone could still be read as "inside". Sits
        //    just under the centre cross, where nothing else is drawn.
        if unknown {
            drawPlotPill(plot.unknownLabel, font: unknownLabelFont,
                         at: CGPoint(x: centre.x, y: centre.y + 15),
                         plot: plot, context: &context)
        }

        guard showDetail else { return }

        // 7. Ring distance labels, set on the north-east diagonal so they
        //    never collide with the N/E/S/W badges — and thinned so they
        //    never collide with EACH OTHER. Rings arrive ascending, so
        //    one greedy pass outward from the centre keeps the innermost
        //    label that clears the centre mark and every later one that
        //    clears the last one kept; the rest are dropped rather than
        //    stacked into an unreadable pile at low zoom.
        var lastLabelRadiusPt: Double = 0
        for ring in plot.rings {
            let r = scale * ring.radiusM
            guard r > 4, r < radiusPt - 1 else { continue }
            guard r - lastLabelRadiusPt >= plotRingLabelMinGapPt else { continue }
            lastLabelRadiusPt = r
            let point = pointOnRing(centre: centre, origin: plot.center,
                                    distanceM: ring.radiusM, bearingDeg: 45,
                                    camera: camera, size: size)
            drawPlotPill(ring.label, font: ringLabelFont, at: point,
                         plot: plot, context: &context)
        }

        // 8. Compass badges ON the boundary, positioned by TRUE bearing —
        //    the projected point at that bearing and the plot radius, not
        //    a screen angle. Correct however the map is oriented.
        for badge in plotCompassBadges {
            let point = pointOnRing(centre: centre, origin: plot.center,
                                    distanceM: plot.radiusM,
                                    bearingDeg: badge.bearingDeg,
                                    camera: camera, size: size)
            drawPlotPill(badge.text, font: compassBadgeFont, at: point,
                         plot: plot, context: &context)
        }
    }

    /// Screen point at a TRUE bearing and ground distance from the plot
    /// centre. Falls back to the centre when the projection degenerates.
    private static func pointOnRing(centre: CGPoint,
                                    origin: CoordinateConversions.LatLon,
                                    distanceM: Double,
                                    bearingDeg: Double,
                                    camera: BasemapCamera,
                                    size: CGSize) -> CGPoint {
        let rad = bearingDeg * .pi / 180
        let target = CoordinateConversions.toLatLon(
            enu: .init(east: distanceM * sin(rad), north: distanceM * cos(rad)),
            origin: origin)
        let point = screenPoint(latitude: target.latitude,
                                longitude: target.longitude,
                                camera: camera, viewportSize: size)
        return (point.x.isFinite && point.y.isFinite) ? point : centre
    }

    /// Ring-distance label type — the map's own mono badge face.
    private static let ringLabelFont =
        Font.system(size: 9, weight: .bold, design: .monospaced)
    /// Compass-letter type — the marker-title face, one size down.
    private static let compassBadgeFont =
        Font.system(size: 9.5, weight: .heavy, design: .monospaced)
    /// The unknown-state words, a touch larger than the ring labels:
    /// this pill is a statement about the whole plot, not a tick mark on
    /// one ring, and it has to survive being glanced at.
    private static let unknownLabelFont =
        Font.system(size: 10, weight: .bold, design: .monospaced)

    /// One label on the plot, in the map's existing chip treatment:
    /// surface-filled rounded rect, hairline border, mono ink. Identical
    /// language to `badgeChip`, so the plot's labels and the pins' badges
    /// read as one family.
    private static func drawPlotPill(_ text: String,
                                     font: Font,
                                     at point: CGPoint,
                                     plot: BasemapPlotOverlay,
                                     context: inout GraphicsContext) {
        let resolved = context.resolve(
            Text(text).font(font).foregroundStyle(plot.ink))
        let textSize = resolved.measure(in: CGSize(width: 160, height: 40))
        let rect = CGRect(x: point.x - textSize.width / 2 - 5,
                          y: point.y - textSize.height / 2 - 2,
                          width: textSize.width + 10,
                          height: textSize.height + 4)
        let pill = Path(roundedRect: rect, cornerRadius: 3, style: .continuous)
        context.fill(pill, with: .color(plot.pillBackground))
        context.stroke(pill, with: .color(plot.pillBorder), lineWidth: 1)
        context.draw(resolved, at: point, anchor: .center)
    }

    // MARK: Gestures

    private func panGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                let base = dragStart ?? camera
                if dragStart == nil { dragStart = camera }
                let worldPts = Self.worldPoints(zoom: camera.zoom)
                let nx = Self.worldX(longitude: base.longitude)
                    - Double(value.translation.width) / worldPts
                let ny = Self.worldY(latitude: base.latitude)
                    - Double(value.translation.height) / worldPts
                camera.longitude = Self.longitude(fromWorldX: min(max(nx, 0), 1))
                camera.latitude = Self.latitude(fromWorldY: min(max(ny, 0), 1))
            }
            .onEnded { _ in
                dragStart = nil
                emitCamera(size: size)
            }
    }

    private func pinchGesture(size: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let base = zoomStart ?? camera.zoom
                if zoomStart == nil { zoomStart = camera.zoom }
                camera.zoom = Self.clampZoom(
                    base + log2(max(Double(value.magnification), 1e-3)))
            }
            .onEnded { _ in
                zoomStart = nil
                emitCamera(size: size)
            }
    }

    /// PRESS AND HOLD anywhere on the map — reports the ground under the
    /// finger once, on release.
    ///
    /// Sequenced long-press-then-drag rather than `.onLongPressGesture`
    /// because that modifier hands back no location, and a planning gesture
    /// whose coordinate is unknown is no gesture at all.
    ///
    /// TWO GUARDS, both about not planning a plot the cruiser did not mean:
    ///   • `maximumDistance` cancels the press if the finger moves while the
    ///     0.5 s is still running — that is a pan starting, not a hold.
    ///   • the translation test at the end rejects a press that completed
    ///     and THEN travelled. That press panned the map, so the camera is
    ///     no longer the one the coordinate would be computed against, and
    ///     the pin would land somewhere the cruiser never touched.
    private func longPressGesture(size: CGSize) -> some Gesture {
        LongPressGesture(minimumDuration: 0.5, maximumDistance: 12)
            .sequenced(before: DragGesture(minimumDistance: 0,
                                           coordinateSpace: .local))
            .onEnded { value in
                guard case .second(true, let drag?) = value else { return }
                guard hypot(drag.translation.width,
                            drag.translation.height) < 12 else { return }
                onMapLongPress(Self.coordinate(at: drag.location,
                                               camera: camera,
                                               viewportSize: size))
            }
    }

    /// Zoom one level in, keeping the tapped point stationary.
    private func doubleTapZoom(at point: CGPoint, size: CGSize) {
        let oldZoom = camera.zoom
        let newZoom = Self.clampZoom(oldZoom + 1)
        guard newZoom != oldZoom else { return }
        let worldPts = Self.worldPoints(zoom: oldZoom)
        let scale = pow(2, newZoom - oldZoom)
        let cx = Self.worldX(longitude: camera.longitude)
        let cy = Self.worldY(latitude: camera.latitude)
        let tx = cx + (Double(point.x) - Double(size.width) / 2) / worldPts
        let ty = cy + (Double(point.y) - Double(size.height) / 2) / worldPts
        let nx = tx + (cx - tx) / scale
        let ny = ty + (cy - ty) / scale
        camera.zoom = newZoom
        camera.longitude = Self.longitude(fromWorldX: min(max(nx, 0), 1))
        camera.latitude = Self.latitude(fromWorldY: min(max(ny, 0), 1))
        emitCamera(size: size)
    }

    private func emitCamera(size: CGSize) {
        baseLoader.retryFailed()
        overlayLoader.retryFailed()
        onCameraChange(camera, Self.visibleRegion(camera: camera,
                                                  viewportSize: size))
    }

    // MARK: Markers

    /// Project a coordinate to screen points; nil when far off-screen
    /// (80 pt margin keeps half-visible pins alive at the edges).
    private func screenPoint(latitude: Double, longitude: Double,
                             size: CGSize) -> CGPoint? {
        let worldPts = Self.worldPoints(zoom: camera.zoom)
        let x = (Self.worldX(longitude: longitude)
            - Self.worldX(longitude: camera.longitude)) * worldPts
            + Double(size.width) / 2
        let y = (Self.worldY(latitude: latitude)
            - Self.worldY(latitude: camera.latitude)) * worldPts
            + Double(size.height) / 2
        let margin = 80.0
        guard x > -margin, x < Double(size.width) + margin,
              y > -margin, y < Double(size.height) + margin
        else { return nil }
        return CGPoint(x: x, y: y)
    }

    /// The teardrop is bottom-anchored at its coordinate (dot above,
    /// badges at the anchor — the mock's translate(-50%, -100%)).
    /// `.position` centres, so shift up by half the pin's height.
    ///
    /// A ring is CENTRE-anchored (the mock's translate(-50%, -50%)):
    /// with no badge the offset is zero; with a badge hanging below,
    /// shift down so the ring circle itself stays on the coordinate
    /// (ring 34 + 3 gap + ~13 badge ⇒ offset = 17 − height/2 = −8).
    private func markerAnchorOffset(_ marker: BasemapMarker) -> CGFloat {
        let hasBadge = !(marker.badge ?? "").isEmpty
        switch marker.shape {
        case .teardrop:
            return (hasBadge ? 48 : 30) / 2
        case .ring:
            return hasBadge ? -8 : 0
        }
    }

    /// Teardrop from the mock: rounded square with one sharp corner,
    /// rotated -45° so the sharp corner becomes the downward tip. The
    /// label stays unrotated on top.
    private func teardrop(cornerStyle: RoundedCornerStyle = .continuous) -> UnevenRoundedRectangle {
        UnevenRoundedRectangle(
            cornerRadii: RectangleCornerRadii(topLeading: 15,
                                              bottomLeading: 4,
                                              bottomTrailing: 15,
                                              topTrailing: 15),
            style: cornerStyle)
    }

    private func markerView(_ marker: BasemapMarker) -> some View {
        Button {
            onMarkerTap(marker.id)
        } label: {
            VStack(spacing: 3) {
                switch marker.shape {
                case .teardrop: teardropHead(marker)
                case .ring(let dashed): ringHead(marker, dashed: dashed)
                }

                if let badge = marker.badge, !badge.isEmpty {
                    switch marker.shape {
                    case .teardrop:
                        // One chip per character — the D/H/C badges.
                        HStack(spacing: 2) {
                            ForEach(Array(badge.enumerated()), id: \.offset) { _, letter in
                                badgeChip(String(letter))
                            }
                        }
                    case .ring:
                        // Whole-string chip (e.g. a tally count).
                        badgeChip(badge)
                    }
                }
            }
            // The visual pin is 30 pt wide — pad the tappable shape out
            // to the 44 pt minimum hit target.
            .contentShape(Rectangle().inset(by: -8))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Map pin \(marker.title)")
    }

    private func teardropHead(_ marker: BasemapMarker) -> some View {
        ZStack {
            if marker.id == selectedMarkerID {
                // Mock `.pin.sel` — soft outline following the
                // teardrop shape itself.
                teardrop()
                    .stroke(style.selectionHalo, lineWidth: 3)
                    .rotationEffect(.degrees(-45))
            }
            teardrop()
                .fill(marker.tint)
                .overlay(teardrop().stroke(style.pinStroke, lineWidth: 2.5))
                .rotationEffect(.degrees(-45))
                .shadow(color: Color.black.opacity(0.3), radius: 4, y: 2)
            Text(marker.title)
                .font(.system(size: 10.5, weight: .heavy, design: .monospaced))
                .foregroundStyle(style.pinInk)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(width: 30, height: 30)
    }

    /// Plot ring (v3 mock `.plotpin .ringdot`): hollow 34 pt circle,
    /// border + label in the marker tint, surface-coloured fill (a bit
    /// translucent for the dashed "planned" style).
    private func ringHead(_ marker: BasemapMarker, dashed: Bool) -> some View {
        ZStack {
            if marker.id == selectedMarkerID {
                Circle()
                    .stroke(style.selectionHalo, lineWidth: 3)
                    .frame(width: 42, height: 42)
            }
            Circle()
                .fill(style.badgeBackground.opacity(dashed ? 0.8 : 1))
                .shadow(color: Color.black.opacity(0.25), radius: 4, y: 2)
            Circle()
                .stroke(marker.tint,
                        style: StrokeStyle(lineWidth: 3,
                                           dash: dashed ? [4, 3] : []))
            Text(marker.title)
                .font(.system(size: 10.5, weight: .heavy, design: .monospaced))
                .foregroundStyle(marker.tint)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
        }
        .frame(width: 34, height: 34)
    }

    private func badgeChip(_ text: String) -> some View {
        Text(text)
            .font(.system(size: 8.5, weight: .bold, design: .monospaced))
            .foregroundStyle(style.badgeText)
            .padding(.horizontal, 4)
            .padding(.vertical, 1)
            .background(
                RoundedRectangle(cornerRadius: 3)
                    .fill(style.badgeBackground))
            .overlay(
                RoundedRectangle(cornerRadius: 3)
                    .stroke(style.badgeBorder, lineWidth: 1))
    }

    private var youDot: some View {
        ZStack {
            // Expanding-fading ripple — r 8→18, alpha .30→0, restarting
            // every 1.6 s (linear).
            Circle()
                .fill(youBlue)
                .frame(width: 16, height: 16)
                .scaleEffect(pulsing ? 18.0 / 8.0 : 1)
                .opacity(pulsing ? 0 : 0.30)
                .animation(.linear(duration: 1.6).repeatForever(autoreverses: false),
                           value: pulsing)
            // Static halo (mock's r 13 ring @ .18).
            Circle()
                .fill(youBlue.opacity(0.18))
                .frame(width: 26, height: 26)
            Circle()
                .fill(youBlue)
                .frame(width: 16, height: 16)
                .overlay(Circle().stroke(style.pinStroke, lineWidth: 3))
        }
        .allowsHitTesting(false)
        .onAppear { pulsing = true }
        .accessibilityHidden(true)
    }

    // MARK: Web-mercator maths

    /// Total map edge length in points at a fractional zoom.
    private static func worldPoints(zoom: Double) -> Double {
        256 * pow(2, zoom)
    }

    /// Longitude → normalized world x in [0, 1].
    private static func worldX(longitude: Double) -> Double {
        (longitude + 180) / 360
    }

    /// Latitude → normalized world y in [0, 1] (0 = north pole cap).
    private static func worldY(latitude: Double) -> Double {
        let clamped = min(max(latitude, -85.05112878), 85.05112878)
        let latRad = clamped * .pi / 180
        return (1 - log(tan(latRad) + 1 / cos(latRad)) / .pi) / 2
    }

    private static func longitude(fromWorldX x: Double) -> Double {
        x * 360 - 180
    }

    private static func latitude(fromWorldY y: Double) -> Double {
        atan(sinh(.pi * (1 - 2 * y))) * 180 / .pi
    }

    private static func clampZoom(_ z: Double) -> Double {
        min(max(z, zoomRange.lowerBound), zoomRange.upperBound)
    }
}
