// Spec §8 Export/GPXExporter. REQ-NAV-004 + §4.1 Export.
//
// GPX 1.1 emitter for cruise-session artefacts:
//   * `waypoints` — one <wpt> per recorded plot center, tagged with
//     the tier and source in <desc>.
//   * `track`     — one <trk><trkseg> of the session's breadcrumb
//     NDJSON from TrackLogRepository.
//
// Pure String output, no dependencies beyond Foundation. The caller
// (ExportScreen) writes the result to disk / share sheet.

import Foundation

public struct GPXWaypoint: Sendable, Equatable {
    public let lat: Double
    public let lon: Double
    public let name: String
    public let description: String?
    public let timestamp: Date?

    public init(
        lat: Double, lon: Double,
        name: String,
        description: String? = nil,
        timestamp: Date? = nil
    ) {
        self.lat = lat; self.lon = lon
        self.name = name
        self.description = description
        self.timestamp = timestamp
    }
}

public struct GPXTrackPoint: Sendable, Equatable {
    public let lat: Double
    public let lon: Double
    public let timestamp: Date
    public let horizontalAccuracyM: Double?

    public init(
        lat: Double, lon: Double,
        timestamp: Date,
        horizontalAccuracyM: Double? = nil
    ) {
        self.lat = lat; self.lon = lon
        self.timestamp = timestamp
        self.horizontalAccuracyM = horizontalAccuracyM
    }
}

public enum GPXExporter {

    public static func gpx(
        creator: String = "Forestix",
        waypoints: [GPXWaypoint] = [],
        trackName: String? = nil,
        trackPoints: [GPXTrackPoint] = []
    ) -> String {
        var out = """
        <?xml version="1.0" encoding="UTF-8"?>
        <gpx version="1.1" creator="\(escape(creator))" xmlns="http://www.topografix.com/GPX/1/1">
        """
        for wp in waypoints {
            out += "\n  <wpt lat=\"\(fmt(wp.lat))\" lon=\"\(fmt(wp.lon))\">"
            out += "\n    <name>\(escape(wp.name))</name>"
            if let d = wp.description {
                out += "\n    <desc>\(escape(d))</desc>"
            }
            if let ts = wp.timestamp {
                out += "\n    <time>\(iso8601(ts))</time>"
            }
            out += "\n  </wpt>"
        }
        if !trackPoints.isEmpty {
            out += "\n  <trk>"
            if let n = trackName {
                out += "\n    <name>\(escape(n))</name>"
            }
            out += "\n    <trkseg>"
            for p in trackPoints {
                out += "\n      <trkpt lat=\"\(fmt(p.lat))\" lon=\"\(fmt(p.lon))\">"
                out += "\n        <time>\(iso8601(p.timestamp))</time>"
                if let h = p.horizontalAccuracyM {
                    out += "\n        <hdop>\(fmt(h))</hdop>"
                }
                out += "\n      </trkpt>"
            }
            out += "\n    </trkseg>\n  </trk>"
        }
        out += "\n</gpx>\n"
        return out
    }

    // MARK: - Helpers

    private static func fmt(_ x: Double) -> String {
        String(format: "%.7f", x)
    }

    private static func iso8601(_ d: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: d)
    }

    private static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;")
         .replacingOccurrences(of: "<", with: "&lt;")
         .replacingOccurrences(of: ">", with: "&gt;")
         .replacingOccurrences(of: "\"", with: "&quot;")
    }
}
