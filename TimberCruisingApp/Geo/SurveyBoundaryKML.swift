// KML → BoundaryFeature.
//
// Separate from the polygon-only `KMLImporter` (stratum import) because a
// survey boundary is routinely a LineString traverse or a mixed document,
// and Placemark points must survive too.
//
// KML §16.2 fixes coordinates at WGS84 lon,lat[,alt] — which is why the
// only thing this parser does about CRS is nothing, and why the caller
// still runs the absolute lon/lat range check afterwards.

import Foundation

public enum SurveyBoundaryKML {

    public static func parse(_ data: Data) throws -> [BoundaryFeature] {
        let delegate = BoundaryKMLParser()
        let xml = XMLParser(data: data)
        xml.delegate = delegate
        xml.shouldProcessNamespaces = true
        guard xml.parse() else {
            if let err = delegate.error { throw err }
            let desc = xml.parserError?.localizedDescription ?? "parse failed"
            throw BoundaryImportError.malformed("KML could not be parsed (\(desc))")
        }
        if let err = delegate.error { throw err }
        return delegate.features
    }
}

// MARK: - SAX parser

private final class BoundaryKMLParser: NSObject, XMLParserDelegate {

    var features: [BoundaryFeature] = []
    var error: Error?

    private enum Geometry { case polygon, line, point }

    private var placemarkName: String?
    private var geometry: Geometry?
    private var ringKind: RingKind = .outer
    private var polygonRings: [[CoordinateConversions.LatLon]] = []
    private var elementStack: [String] = []
    private var inCoordinates = false
    private var coordinateBuffer = ""
    private var inName = false
    private var nameBuffer = ""

    private enum RingKind { case outer, inner }

    func parser(_ parser: XMLParser, didStartElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?,
                attributes attributeDict: [String: String] = [:]) {
        elementStack.append(elementName)
        switch elementName {
        case "Placemark":
            placemarkName = nil
            polygonRings = []
            geometry = nil
        case "Polygon":
            geometry = .polygon
            polygonRings = []
        case "LineString", "LinearRing":
            // A LinearRing INSIDE a Polygon is a boundary ring, not a
            // standalone geometry — only promote the bare form.
            if geometry != .polygon { geometry = .line }
        case "Point":
            geometry = .point
        case "outerBoundaryIs": ringKind = .outer
        case "innerBoundaryIs": ringKind = .inner
        case "coordinates":
            inCoordinates = true
            coordinateBuffer = ""
        case "name":
            if elementStack.dropLast().last == "Placemark" {
                inName = true
                nameBuffer = ""
            }
        default: break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if inCoordinates { coordinateBuffer.append(string) }
        if inName { nameBuffer.append(string) }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String,
                namespaceURI: String?, qualifiedName qName: String?) {
        defer { if elementStack.last == elementName { elementStack.removeLast() } }
        switch elementName {
        case "coordinates":
            inCoordinates = false
            do {
                let pts = try Self.parseCoordinateList(coordinateBuffer)
                guard !pts.isEmpty else { break }
                switch geometry {
                case .polygon:
                    var ring = pts
                    if let first = ring.first, ring.last != first { ring.append(first) }
                    guard ring.count >= 4 else { break }
                    if ringKind == .outer { polygonRings.insert(ring, at: 0) }
                    else { polygonRings.append(ring) }
                case .line:
                    guard pts.count >= 2 else { break }
                    features.append(BoundaryFeature(kind: .line, rings: [pts],
                                                    name: placemarkName))
                case .point:
                    features.append(BoundaryFeature(kind: .point, rings: [[pts[0]]],
                                                    name: placemarkName))
                case nil:
                    break
                }
            } catch {
                self.error = error
                parser.abortParsing()
            }
        case "outerBoundaryIs", "innerBoundaryIs":
            ringKind = .outer
        case "name":
            if inName {
                inName = false
                let trimmed = nameBuffer.trimmingCharacters(in: .whitespacesAndNewlines)
                placemarkName = trimmed.isEmpty ? nil : trimmed
                // A Placemark's <name> often follows its geometry inside a
                // MultiGeometry document — back-fill the features this
                // Placemark already emitted.
                if let placemarkName {
                    for idx in features.indices.reversed() where features[idx].name == nil {
                        features[idx].name = placemarkName
                    }
                }
            }
        case "Polygon":
            if !polygonRings.isEmpty {
                features.append(BoundaryFeature(kind: .polygon, rings: polygonRings,
                                                name: placemarkName))
            }
            polygonRings = []
            geometry = nil
        case "LineString", "Point":
            geometry = nil
        case "Placemark":
            placemarkName = nil
            polygonRings = []
            geometry = nil
        default: break
        }
    }

    func parser(_ parser: XMLParser, parseErrorOccurred parseError: Error) {
        if error == nil {
            error = BoundaryImportError.malformed(
                "KML could not be parsed (\(parseError.localizedDescription))")
        }
    }

    /// KML coordinate text: whitespace-separated `lon,lat[,alt]` tuples.
    /// The altitude ordinate is read and discarded.
    static func parseCoordinateList(_ text: String) throws -> [CoordinateConversions.LatLon] {
        var out: [CoordinateConversions.LatLon] = []
        for tuple in text.split(whereSeparator: { $0.isWhitespace }) {
            let parts = tuple.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count >= 2,
                  let lon = Double(parts[0]), let lat = Double(parts[1]) else {
                throw BoundaryImportError.malformed(
                    "KML coordinate tuple could not be read: \(tuple)")
            }
            out.append(CoordinateConversions.LatLon(latitude: lat, longitude: lon))
        }
        return out
    }
}
