// `forestix://` deep-link router — adopted from Arboreal Forest's
// `arborealforest://log?lat=…&lon=…&name=…` scheme. Lets a crew
// lead build a plot list in any GIS (QGIS, Avenza, ArcGIS Field
// Maps) and hand the crew a tappable list instead of typing
// coordinates at every plot.
//
// URL grammar:
//   forestix://plot?lat=47.123&lon=-122.345&name=Plot+7&unit=BlockA&acres=1.0
//   forestix://plot?lat=47.123&lon=-122.345&name=Plot+7
//
// All query params are optional except `lat` + `lon`. The router
// parses safely (returns nil on bad input rather than crashing) so
// a malformed link from e.g. an external app's URL pasteboard
// won't kill the app.

import Foundation
import CoreLocation

public struct PendingPlotLink: Equatable {
    public let lat: Double
    public let lon: Double
    public let name: String?
    public let unit: String?
    public let acres: Double?
    public let comment: String?

    public init(lat: Double, lon: Double,
                name: String? = nil, unit: String? = nil,
                acres: Double? = nil, comment: String? = nil) {
        self.lat = lat; self.lon = lon
        self.name = name; self.unit = unit
        self.acres = acres; self.comment = comment
    }

    /// CLLocationCoordinate2D conversion — convenience for callers
    /// that want to feed straight into MapKit / CoreLocation.
    public var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}

public enum URLRouter {

    /// Parses a `forestix://plot?…` URL into a `PendingPlotLink`.
    /// Returns nil on:
    ///   • wrong scheme / wrong host
    ///   • missing or malformed lat / lon
    ///   • lat outside −90…90 or lon outside −180…180
    public static func parse(_ url: URL) -> PendingPlotLink? {
        guard url.scheme?.lowercased() == "forestix" else { return nil }
        guard url.host?.lowercased() == "plot" else { return nil }
        guard let comps = URLComponents(url: url,
                                         resolvingAgainstBaseURL: false),
              let items = comps.queryItems
        else { return nil }

        func value(_ key: String) -> String? {
            items.first { $0.name.lowercased() == key.lowercased() }?
                .value?
                .removingPercentEncoding
        }

        guard let latStr = value("lat"), let lat = Double(latStr),
              let lonStr = value("lon"), let lon = Double(lonStr),
              lat >= -90, lat <= 90, lon >= -180, lon <= 180
        else { return nil }

        return PendingPlotLink(
            lat: lat, lon: lon,
            name: value("name"),
            unit: value("unit"),
            acres: value("acres").flatMap(Double.init),
            comment: value("comment"))
    }

    /// Builds a sharable `forestix://plot?…` URL for a plot with
    /// known coordinates. Used by export flows so the cruiser can
    /// hand a colleague a deep-link instead of plain coordinates.
    public static func plotURL(lat: Double,
                                lon: Double,
                                name: String? = nil,
                                unit: String? = nil,
                                acres: Double? = nil) -> URL? {
        var comps = URLComponents()
        comps.scheme = "forestix"
        comps.host = "plot"
        var items: [URLQueryItem] = [
            URLQueryItem(name: "lat", value: String(format: "%.6f", lat)),
            URLQueryItem(name: "lon", value: String(format: "%.6f", lon)),
        ]
        if let n = name, !n.isEmpty { items.append(.init(name: "name", value: n)) }
        if let u = unit, !u.isEmpty { items.append(.init(name: "unit", value: u)) }
        if let a = acres { items.append(.init(name: "acres",
                                               value: String(format: "%.3f", a))) }
        comps.queryItems = items
        return comps.url
    }
}
