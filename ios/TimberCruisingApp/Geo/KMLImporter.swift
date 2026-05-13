// Spec §8 Geo/KMLImporter. REQ-PRJ-002: import stratum boundaries from a
// Google-Earth-style KML document. Only `Placemark` elements carrying a
// `Polygon` (optionally nested under `MultiGeometry`) are extracted; other
// geometry types (Point/LineString/Model/etc.) are skipped.
//
// KML coordinates are expressed as `lon,lat[,alt]` whitespace-separated
// tuples. We reuse `ImportedPolygon` from `GeoJSONImporter` so downstream
// CruiseDesign code sees a single shape.

import Foundation

public enum KMLImportError: Error, CustomStringConvertible {
    case malformedXML(String)
    case noPolygons

    public var description: String {
        switch self {
        case .malformedXML(let r): return "Malformed KML: \(r)"
        case .noPolygons: return "KML document contains no Polygon features"
        }
    }
}

public enum KMLImporter {

    public static func importStrata(from data: Data) throws -> [ImportedPolygon] {
        let parser = KMLParser()
        let xml = XMLParser(data: data)
        xml.delegate = parser
        xml.shouldProcessNamespaces = true
        guard xml.parse() else {
            let desc = xml.parserError?.localizedDescription ?? "parse failed"
            throw KMLImportError.malformedXML(desc)
        }
        if let err = parser.error { throw err }
        guard !parser.polygons.isEmpty else { throw KMLImportError.noPolygons }
        return parser.polygons
    }
}

// MARK: - SAX parser

private final class KMLParser: NSObject, XMLParserDelegate {
    private enum RingKind { case outer, inner }

    var polygons: [ImportedPolygon] = []
    var error: Error?

    // Placemark-level state.
    private var placemarkName: String?
    private var placemarkAreaAcres: Double?
    // Polygon-level state (a Placemark may contain several via MultiGeometry).
    private var currentPolygonRings: [[CoordinateConversions.LatLon]] = []
    // Ring-level state.
    private var currentRingKind: RingKind?
    private var inCoordinates = false
    private var coordinateBuffer = ""
    private var inName = false
    private var nameBuffer = ""
    // Simple stack of element names so we know what context we're in.
    private var elementStack: [String] = []

    func parser(_ parser: XMLParser,
                didStartElement elementName: String,
                namespaceURI: String?,
                qualifiedName qName: String?,
                attributes attributeDict: [String: String] = [:]) {
        elementStack.append(elementName)
        switch elementName {
        case "Placemark":
            placemarkName = nil
            placemarkAreaAcres = nil
            currentPolygonRings = []
        case "Polygon":
            currentPolygonRings = []
        case "outerBoundaryIs":
            currentRingKind = .outer
        case "innerBoundaryIs":
            currentRingKind = .inner
        case "coordinates":
            inCoordinates = true
            coordinateBuffer = ""
        case "name":
            if elementStack.dropLast().last == "Placemark" {
                inName = true
                nameBuffer = ""
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inCoordinates { coordinateBuffer.append(string) }
        if inName { nameBuffer.append(string) }
    }

    func parser(_ parser: XMLParser,
                didEndElement elementName: String,
                namespaceURI: String?,
                qualifiedName qName: String?) {
        defer {
            if elementStack.last == elementName { elementStack.removeLast() }
        }
        switch elementName {
        case "coordinates":
            inCoordinates = false
            guard let kind = currentRingKind else { break }
            do {
                let ring = try parseCoordinateList(coordinateBuffer)
                switch kind {
                case .outer:
                    currentPolygonRings.insert(ring, at: 0)   // outer first
                case .inner:
                    currentPolygonRings.append(ring)
                }
            } catch let err {
                self.error = err
                parser.abortParsing()
            }
        case "outerBoundaryIs", "innerBoundaryIs":
            currentRingKind = nil
        case "name":
            if inName {
                inName = false
                placemarkName = nameBuffer.trimmingCharacters(in: .whitespacesAndNewlines)
            }
        case "Polygon":
            flushPolygon()
        case "Placemark":
            // A Placemark containing a Polygon directly (no MultiGeometry) has
            // already been flushed. Reset state regardless.
            placemarkName = nil
            placemarkAreaAcres = nil
            currentPolygonRings = []
        default:
            break
        }
    }

    private func flushPolygon() {
        guard !currentPolygonRings.isEmpty, !currentPolygonRings[0].isEmpty else {
            currentPolygonRings = []
            return
        }
        let name = (placemarkName?.isEmpty == false) ? placemarkName! : "Stratum"
        let areaM2 = abs(GeoJSONImporter.signedPolygonAreaMetersSquared(rings: currentPolygonRings))
        let areaAcres = placemarkAreaAcres ?? GeoJSONImporter.metersSquaredToAcres(areaM2)
        let geoJSON = GeoJSONImporter.serialise(rings: currentPolygonRings)
        polygons.append(
            ImportedPolygon(
                name: name,
                areaAcres: areaAcres,
                rings: currentPolygonRings,
                geoJSONString: geoJSON
            )
        )
        currentPolygonRings = []
    }

    // MARK: - Coordinate list parsing

    /// Parse KML `coordinates` text: whitespace-separated `lon,lat[,alt]` tuples.
    private func parseCoordinateList(_ text: String) throws -> [CoordinateConversions.LatLon] {
        let tuples = text
            .split(whereSeparator: { $0.isWhitespace })
            .map(String.init)
        var ring: [CoordinateConversions.LatLon] = []
        ring.reserveCapacity(tuples.count)
        for tuple in tuples {
            let parts = tuple.split(separator: ",", omittingEmptySubsequences: true).map(String.init)
            guard parts.count >= 2,
                  let lon = Double(parts[0]),
                  let lat = Double(parts[1]) else {
                throw KMLImportError.malformedXML("Bad coordinate tuple: \(tuple)")
            }
            guard (-180...180).contains(lon), (-90...90).contains(lat) else {
                throw KMLImportError.malformedXML("lat/lon out of range in tuple: \(tuple)")
            }
            ring.append(CoordinateConversions.LatLon(latitude: lat, longitude: lon))
        }
        // KML does not require the ring to close; close it if needed.
        if let first = ring.first, let last = ring.last, first != last {
            ring.append(first)
        }
        if ring.count < 4 {
            throw KMLImportError.malformedXML("Ring has < 4 closed positions")
        }
        return ring
    }

    func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
        self.error = KMLImportError.malformedXML(parseError.localizedDescription)
    }
}
