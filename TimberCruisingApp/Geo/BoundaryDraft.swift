// AN OUTLINE BEING DRAGGED ON THE MAP — the editable shape behind both
// "Draw the boundary" (Map settings) and "Draw an area" (the home map's
// press-and-hold), before it becomes a `SurveyBoundary` or the ring of a
// cruise area.
//
// THE RULE THIS TYPE EXISTS TO KEEP: a coordinate a cruiser dragged on a
// satellite tile is not a coordinate anything measured. Nothing in here
// touches GPS, and the boundary it produces is stamped
// `BoundaryOrigin.drawn` so no downstream reader can mistake a fingertip
// sketch for surveyed geometry (see `SurveyBoundary.origin`). The stamp is
// the boundary-level analogue of `PositionSource.manual` on a plot centre:
// same idea, same reason — the repo has already had to undo a typed
// diameter that carried a sensor's sigma.
//
// Everything is WGS84 lon/lat, exactly like an imported boundary, and every
// metric quantity (area, edge midpoints, the self-intersection test) is
// computed in a local ENU plane anchored on the draft's first vertex —
// the same equirectangular approximation `SamplingGenerator` lays plots out
// in, so the area shown while drawing and the area the plots are generated
// against come from the same maths.
//
// The vertex list is OPEN (first != last). The closing repeat is added once,
// in `closedRing`, so the ring the store persists is byte-shaped exactly
// like the one `SurveyBoundaryGeoJSON.closeRings` produces for an import —
// every downstream consumer sees one kind of ring.

import Foundation
import Common

/// Why a drawn outline cannot be saved. `description` is the sentence the
/// screen shows verbatim; there is no generic fallback, because a refusal
/// the cruiser cannot act on is the same as no refusal at all.
public enum BoundaryDraftError: Error, Equatable, CustomStringConvertible {
    /// Fewer than three corners — no interior exists at all.
    case tooFewVertices
    /// Two edges cross, so "inside" is undefined: the in/out verdict and
    /// the area would both be arbitrary. Refused rather than stored.
    case selfIntersecting
    /// All the corners are collinear (or coincident): a line, not a stand.
    case zeroArea

    public var description: String {
        switch self {
        case .tooFewVertices:
            return "A boundary needs at least 3 corners."
        case .selfIntersecting:
            return "The outline crosses itself. Move a corner so no two edges cross."
        case .zeroArea:
            return "The outline has no area. Move a corner so it encloses ground."
        }
    }
}

/// The in-progress outline: an ordered, open list of WGS84 corners.
public struct BoundaryDraft: Equatable, Sendable {

    /// Smallest area we will call an area at all. A hand-drag can leave
    /// three points a few centimetres apart, which is arithmetically
    /// non-zero and forestrically meaningless; 1 m² is far below any real
    /// stand and far above float noise at cruise latitudes.
    public static let minimumAreaSquareMeters: Double = 1.0

    /// Corners in ring order, NOT closed (first != last).
    public private(set) var vertices: [CoordinateConversions.LatLon]

    public init(vertices: [CoordinateConversions.LatLon]) {
        self.vertices = vertices
    }

    /// Re-open a ring that was stored closed (first == last). The store is
    /// the only place a closing repeat exists — see the file header — so
    /// this is the one door back in, and it exists because an AREA is
    /// edited again and again after it is first drawn.
    public init(closedRing: [CoordinateConversions.LatLon]) {
        var open = closedRing
        if open.count >= 2, open.first == open.last { open.removeLast() }
        self.vertices = open
    }

    /// The ring a store holds: wound counter-clockwise (RFC 7946 §3.1.6)
    /// and closed. Shared by `surveyBoundary(displayName:createdAt:)` and
    /// by the area a cruise is laid out inside, so a shape saved by either
    /// door is byte-shaped the same on disk.
    public var closedRing: [CoordinateConversions.LatLon] {
        var ring = signedAreaSquareMeters < 0 ? vertices.reversed().map { $0 } : vertices
        if let first = ring.first { ring.append(first) }
        return ring
    }

    // MARK: - The starting rectangle

    /// The shape a draw gesture starts from: an axis-aligned rectangle spanning
    /// two opposite corners, wound counter-clockwise so it already obeys
    /// RFC 7946 §3.1.6 before the cruiser touches it.
    ///
    /// The caller passes two corners derived from the VISIBLE viewport
    /// rather than a fixed ground size: a fixed 200 m box is invisible at
    /// zoom 12 and larger than the screen at zoom 20, and a cruiser who
    /// cannot see the rectangle they were just given assumes nothing
    /// happened.
    public static func rectangle(corner a: CoordinateConversions.LatLon,
                                 opposite b: CoordinateConversions.LatLon) -> BoundaryDraft {
        let minLat = Swift.min(a.latitude, b.latitude)
        let maxLat = Swift.max(a.latitude, b.latitude)
        let minLon = Swift.min(a.longitude, b.longitude)
        let maxLon = Swift.max(a.longitude, b.longitude)
        // SW → SE → NE → NW is counter-clockwise on an east/north plane.
        return BoundaryDraft(vertices: [
            CoordinateConversions.LatLon(latitude: minLat, longitude: minLon),
            CoordinateConversions.LatLon(latitude: minLat, longitude: maxLon),
            CoordinateConversions.LatLon(latitude: maxLat, longitude: maxLon),
            CoordinateConversions.LatLon(latitude: maxLat, longitude: minLon)
        ])
    }

    // MARK: - Editing

    /// Move one corner. Out-of-range indices are ignored rather than
    /// trapped: a drag can outlive the vertex it started on when an undo
    /// lands mid-gesture, and losing the shape to a crash is the outcome
    /// this whole screen exists to avoid.
    public mutating func move(vertexAt index: Int,
                              to coordinate: CoordinateConversions.LatLon) {
        guard vertices.indices.contains(index) else { return }
        vertices[index] = coordinate
    }

    /// The midpoint of every edge, in edge order: `midpoints[i]` sits
    /// between `vertices[i]` and `vertices[i + 1]` (wrapping at the end).
    /// Computed in ENU so the handle sits where the eye expects it on the
    /// projected map, not where naive lon/lat averaging would put it.
    public var edgeMidpoints: [CoordinateConversions.LatLon] {
        guard vertices.count >= 2, let origin = vertices.first else { return [] }
        return vertices.indices.map { i in
            let a = CoordinateConversions.toENU(point: vertices[i], origin: origin)
            let b = CoordinateConversions.toENU(point: vertices[(i + 1) % vertices.count],
                                                origin: origin)
            return CoordinateConversions.toLatLon(
                enu: CoordinateConversions.ENU(east: (a.east + b.east) / 2,
                                               north: (a.north + b.north) / 2),
                origin: origin)
        }
    }

    /// Split edge `index` at `coordinate`, returning the new corner's
    /// index. This is what dragging an edge handle does: the handle is not
    /// a vertex until it moves, and then it becomes one.
    @discardableResult
    public mutating func insertVertex(onEdgeAt index: Int,
                                      at coordinate: CoordinateConversions.LatLon) -> Int? {
        guard vertices.indices.contains(index) else { return nil }
        let inserted = index + 1
        vertices.insert(coordinate, at: inserted)
        return inserted
    }

    /// Delete a corner (the long-press). Refused at three corners — the
    /// deletion that would leave a line is the one a gloved thumb makes by
    /// accident, and it would silently destroy the shape.
    @discardableResult
    public mutating func removeVertex(at index: Int) -> Bool {
        guard vertices.indices.contains(index), vertices.count > 3 else { return false }
        vertices.remove(at: index)
        return true
    }

    // MARK: - Area

    /// Signed shoelace area in m² on the ENU plane anchored at the first
    /// vertex. Positive = counter-clockwise = an RFC 7946 exterior ring.
    public var signedAreaSquareMeters: Double {
        guard vertices.count >= 3, let origin = vertices.first else { return 0 }
        let plane = vertices.map { CoordinateConversions.toENU(point: $0, origin: origin) }
        var sum = 0.0
        for i in plane.indices {
            let a = plane[i]
            let b = plane[(i + 1) % plane.count]
            sum += a.east * b.north - b.east * a.north
        }
        return sum / 2
    }

    /// Enclosed ground in m², sign discarded — the number the readout is
    /// built from.
    public var areaSquareMeters: Double { abs(signedAreaSquareMeters) }

    /// Enclosed ground in acres. Acres are the app's canonical stored
    /// areal basis (see `AreaUnit`); hectares are a display conversion on
    /// top, so the conversion lives at the display and not here.
    public var areaAcres: Double {
        GeoJSONImporter.metersSquaredToAcres(areaSquareMeters)
    }

    // MARK: - Validity

    /// nil when the draft may be saved, otherwise the reason it may not.
    /// Checked live while dragging so the cruiser sees the refusal at the
    /// moment they cause it, not at the moment they try to save.
    public var validationFailure: BoundaryDraftError? {
        guard vertices.count >= 3 else { return .tooFewVertices }
        if isSelfIntersecting { return .selfIntersecting }
        guard areaSquareMeters >= Self.minimumAreaSquareMeters else { return .zeroArea }
        return nil
    }

    public var isValid: Bool { validationFailure == nil }

    /// True when any two NON-ADJACENT edges of the closed ring touch or
    /// cross. A polygon that crosses itself has no defined interior, so
    /// `SamplingGenerator`'s crossing-number test and the area above would
    /// both return something — just not something that means anything.
    ///
    /// Run on the ENU plane so the tolerance is in metres: `epsilon` is a
    /// cross product of two metre vectors, i.e. an area, and 1e-6 m² is
    /// far below anything a fingertip can express and far above the
    /// rounding of a degree-to-metre conversion.
    public var isSelfIntersecting: Bool {
        guard vertices.count >= 4, let origin = vertices.first else { return false }
        let p = vertices.map { CoordinateConversions.toENU(point: $0, origin: origin) }
        let n = p.count
        for i in 0..<n {
            let a1 = p[i], a2 = p[(i + 1) % n]
            // Only j > i, and never the two edges that share a corner with
            // edge i (its successor, and its predecessor when i is the
            // first edge) — those touch by construction.
            for j in (i + 1)..<n where !(j == i + 1) && !(i == 0 && j == n - 1) {
                let b1 = p[j], b2 = p[(j + 1) % n]
                if Self.segmentsIntersect(a1, a2, b1, b2) { return true }
            }
        }
        return false
    }

    private static let epsilon: Double = 1e-6

    /// Segment intersection including touching — a corner sitting exactly
    /// on another edge is as undefined an interior as a clean crossing.
    private static func segmentsIntersect(_ p1: CoordinateConversions.ENU,
                                          _ p2: CoordinateConversions.ENU,
                                          _ p3: CoordinateConversions.ENU,
                                          _ p4: CoordinateConversions.ENU) -> Bool {
        let d1 = cross(p3, p4, p1)
        let d2 = cross(p3, p4, p2)
        let d3 = cross(p1, p2, p3)
        let d4 = cross(p1, p2, p4)
        if ((d1 > epsilon && d2 < -epsilon) || (d1 < -epsilon && d2 > epsilon)) &&
            ((d3 > epsilon && d4 < -epsilon) || (d3 < -epsilon && d4 > epsilon)) {
            return true
        }
        if abs(d1) <= epsilon, onSegment(p3, p4, p1) { return true }
        if abs(d2) <= epsilon, onSegment(p3, p4, p2) { return true }
        if abs(d3) <= epsilon, onSegment(p1, p2, p3) { return true }
        if abs(d4) <= epsilon, onSegment(p1, p2, p4) { return true }
        return false
    }

    /// z of (b − a) × (c − a) — positive when a→b→c turns left.
    private static func cross(_ a: CoordinateConversions.ENU,
                              _ b: CoordinateConversions.ENU,
                              _ c: CoordinateConversions.ENU) -> Double {
        (b.east - a.east) * (c.north - a.north) - (b.north - a.north) * (c.east - a.east)
    }

    /// Whether collinear point `c` lies within segment a→b's bounding box.
    private static func onSegment(_ a: CoordinateConversions.ENU,
                                  _ b: CoordinateConversions.ENU,
                                  _ c: CoordinateConversions.ENU) -> Bool {
        c.east >= Swift.min(a.east, b.east) - epsilon &&
        c.east <= Swift.max(a.east, b.east) + epsilon &&
        c.north >= Swift.min(a.north, b.north) - epsilon &&
        c.north <= Swift.max(a.north, b.north) + epsilon
    }

    // MARK: - Becoming a survey boundary

    /// The one `SurveyBoundary` this draft stands for — the SAME type an
    /// import produces, so plot generation, the area readout, the in/out
    /// verdict and the exports all keep working untouched. Throws the
    /// refusal rather than returning a shape nothing downstream can use.
    ///
    /// The winding and closing normalisations are `closedRing`'s, so the
    /// ring a boundary is persisted with and the ring an area is persisted
    /// with come out of one place.
    public func surveyBoundary(displayName: String,
                               createdAt: Date = Date()) throws -> SurveyBoundary {
        if let failure = validationFailure { throw failure }
        let ring = closedRing
        let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let feature = BoundaryFeature(kind: .polygon, rings: [ring],
                                      name: name.isEmpty ? nil : name)
        return SurveyBoundary(displayName: name.isEmpty ? "Drawn boundary" : name,
                              importedAt: createdAt,
                              sourceFormat: SurveyBoundary.drawnSourceFormat,
                              origin: .drawn,
                              features: [feature])
    }
}
