// Port of iOS Export/GPXExporter.swift (spec §8, REQ-NAV-004 + §4.1 Export).
//
// GPX 1.1 emitter for cruise-session artefacts:
//   * `waypoints` — one <wpt> per recorded plot center, tagged with
//     the tier and source in <desc>.
//   * `track`     — one <trk><trkseg> of the session's breadcrumb
//     NDJSON from TrackLogRepository.
//
// Pure String output, no dependencies beyond the JDK. The caller
// (export screen) writes the result to disk / share sheet.

package com.hcjeong.forestix.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class GPXWaypoint(
    val lat: Double,
    val lon: Double,
    val name: String,
    val description: String? = null,
    val timestamp: Long? = null,
)

data class GPXTrackPoint(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val horizontalAccuracyM: Double? = null,
)

object GPXExporter {

    fun gpx(
        creator: String = "Forestix",
        waypoints: List<GPXWaypoint> = emptyList(),
        trackName: String? = null,
        trackPoints: List<GPXTrackPoint> = emptyList(),
    ): String {
        val out = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<gpx version=\"1.1\" creator=\"${escape(creator)}\" " +
                "xmlns=\"http://www.topografix.com/GPX/1/1\">"
        )
        for (wp in waypoints) {
            out.append("\n  <wpt lat=\"${fmt(wp.lat)}\" lon=\"${fmt(wp.lon)}\">")
            out.append("\n    <name>${escape(wp.name)}</name>")
            wp.description?.let { d ->
                out.append("\n    <desc>${escape(d)}</desc>")
            }
            wp.timestamp?.let { ts ->
                out.append("\n    <time>${iso8601(ts)}</time>")
            }
            out.append("\n  </wpt>")
        }
        if (trackPoints.isNotEmpty()) {
            out.append("\n  <trk>")
            trackName?.let { n ->
                out.append("\n    <name>${escape(n)}</name>")
            }
            out.append("\n    <trkseg>")
            for (p in trackPoints) {
                out.append("\n      <trkpt lat=\"${fmt(p.lat)}\" lon=\"${fmt(p.lon)}\">")
                out.append("\n        <time>${iso8601(p.timestamp)}</time>")
                p.horizontalAccuracyM?.let { h ->
                    out.append("\n        <hdop>${fmt(h)}</hdop>")
                }
                out.append("\n      </trkpt>")
            }
            out.append("\n    </trkseg>\n  </trk>")
        }
        out.append("\n</gpx>\n")
        return out.toString()
    }

    // MARK: - Helpers

    private fun fmt(x: Double): String = String.format(Locale.US, "%.7f", x)

    /// ISO-8601 with fractional seconds and trailing "Z" — matches iOS
    /// ISO8601DateFormatter(.withInternetDateTime, .withFractionalSeconds).
    private fun iso8601(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
