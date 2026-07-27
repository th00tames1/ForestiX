// Port of iOS Export/GeoJSONExporter.swift (spec §8). Phase 1 shipped
// plan-only export (strata polygons + planned-plot points). Phase 6 adds a
// full-cruise export:
//
//   * Stratum polygons (unchanged, re-exported for map validation).
//   * PlannedPlot points with `visited: Bool` property so downstream GIS
//     tools can distinguish visited vs. skipped plots at a glance.
//   * Measured-Plot points for every Plot that has been recorded, with the
//     full position-tier + tally metadata in their `properties`. A plot
//     with no recorded centre is still a feature — it keeps its properties
//     so the tally is not lost — but an UNLOCATED one, `"geometry": null`
//     per RFC 7946 §3.2, never a Point at the (0,0) sentinel.
//
// All coordinates are WGS84 decimal degrees. Keys are sorted so two runs
// with identical inputs yield byte-identical files (important for
// reviewer diffs and golden-file tests). The serializer mirrors Apple's
// JSONSerialization [.sortedKeys, .prettyPrinted] output shape: two-space
// indent, `"key" : value` separators, and `/` escaped as `\/`.

package com.hcjeong.forestix.export

import com.hcjeong.forestix.data.cruise.PlannedPlot
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.Stratum
import com.hcjeong.forestix.data.cruise.hasCentre
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

class GeoJSONExporterError(message: String) : Exception(message) {
    companion object {
        fun serializationFailed() = GeoJSONExporterError("serializationFailed")
    }
}

object GeoJSONExporter {

    // MARK: - Phase 1 plan-only export

    fun plan(
        strata: List<Stratum>,
        plannedPlots: List<PlannedPlot>,
    ): String {
        val features = mutableListOf<Map<String, Any?>>()
        features.addAll(strata.mapNotNull(::stratumFeature))
        features.addAll(plannedPlots.map(::plannedPlotFeature))
        return serialize(features)
    }

    // MARK: - Phase 6 full-cruise export

    /// Full cruise export. Includes:
    ///   * strata polygons,
    ///   * all planned plots (with `visited` distinguishing planned-and-
    ///     visited from planned-and-skipped),
    ///   * every measured plot centre as its own Point feature.
    fun cruise(
        strata: List<Stratum>,
        plannedPlots: List<PlannedPlot>,
        plots: List<Plot>,
    ): String {
        val features = mutableListOf<Map<String, Any?>>()
        features.addAll(strata.mapNotNull(::stratumFeature))
        features.addAll(plannedPlots
            .sortedBy { it.plotNumber }
            .map(::plannedPlotFeature))
        features.addAll(plots
            .sortedBy { it.plotNumber }
            .map(::measuredPlotFeature))
        return serialize(features)
    }

    // MARK: - Features

    internal fun stratumFeature(s: Stratum): Map<String, Any?>? {
        val geometry = parseGeometry(s.polygonGeoJSON) ?: return null
        return mapOf(
            "type" to "Feature",
            "geometry" to geometry,
            "properties" to mapOf(
                "kind" to "stratum",
                "id" to s.id.uuidString,
                "name" to s.name,
                "areaAcres" to s.areaAcres.toDouble(),
            ),
        )
    }

    internal fun plannedPlotFeature(p: PlannedPlot): Map<String, Any?> = mapOf(
        "type" to "Feature",
        "geometry" to mapOf(
            "type" to "Point",
            "coordinates" to listOf(p.plannedLon, p.plannedLat),
        ),
        "properties" to mapOf(
            "kind" to "plannedPlot",
            "id" to p.id.uuidString,
            "plotNumber" to p.plotNumber,
            "stratumId" to (p.stratumId?.uuidString),
            "visited" to p.visited,
            "skipped" to p.skipped,
        ),
    )

    internal fun measuredPlotFeature(p: Plot): Map<String, Any?> {
        val props = mutableMapOf<String, Any?>(
            "kind" to "measuredPlot",
            "id" to p.id.uuidString,
            "plotNumber" to p.plotNumber,
            "plannedPlotId" to (p.plannedPlotId?.uuidString),
            "positionSource" to p.positionSource.raw,
            "positionTier" to p.positionTier.raw,
            "gpsNSamples" to p.gpsNSamples,
            "gpsMedianHAccuracyM" to p.gpsMedianHAccuracyM.toDouble(),
            "gpsSampleStdXyM" to p.gpsSampleStdXyM.toDouble(),
            "offsetWalkM" to (p.offsetWalkM?.toDouble()),
            "slopeDeg" to p.slopeDeg.toDouble(),
            "aspectDeg" to p.aspectDeg.toDouble(),
            "plotAreaAcres" to p.plotAreaAcres.toDouble(),
            "startedAt" to iso8601(p.startedAt),
            "closedAt" to (p.closedAt?.let { iso8601(it) }),
            "closedBy" to p.closedBy,
        )
        if (p.notes.isNotEmpty()) props["notes"] = p.notes
        return mapOf(
            "type" to "Feature",
            // A plot with NO RECORDED CENTRE is an UNLOCATED FEATURE:
            // `"geometry": null`, which RFC 7946 §3.2 defines for exactly
            // this case. The plot keeps its row and its whole tally in the
            // file — a reader can still join it to trees.csv — but the file
            // makes no claim about where it is. Emitting the (0,0) sentinel
            // as a Point instead dropped the plot in the Gulf of Guinea and
            // called it a measurement.
            "geometry" to if (p.hasCentre) {
                mapOf(
                    "type" to "Point",
                    "coordinates" to listOf(p.centerLon, p.centerLat),
                )
            } else {
                null
            },
            "properties" to props,
        )
    }

    // MARK: - Helpers

    private fun serialize(featureCollection: List<Map<String, Any?>>): String {
        val collection: Map<String, Any?> = mapOf(
            "type" to "FeatureCollection",
            "features" to featureCollection,
        )
        val sb = StringBuilder()
        writeJson(collection, sb, indent = 0)
        return sb.toString()
    }

    /// Strata store their polygon as a GeoJSON Geometry string. Round-trip it
    /// through org.json so the output is guaranteed-valid JSON — we don't
    /// want a malformed string in one stratum to poison the whole file.
    private fun parseGeometry(text: String): Map<String, Any?>? {
        val dict = try {
            toPlain(JSONObject(text)) as? Map<String, Any?>
        } catch (_: Exception) {
            null
        } ?: return null
        if (dict["type"] !is String) return null
        return dict
    }

    /// Recursively convert org.json containers to plain Kotlin maps/lists.
    private fun toPlain(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val out = LinkedHashMap<String, Any?>()
            for (key in value.keys()) out[key] = toPlain(value.opt(key))
            out
        }
        is JSONArray -> {
            val out = ArrayList<Any?>(value.length())
            for (i in 0 until value.length()) out.add(toPlain(value.opt(i)))
            out
        }
        else -> value
    }

    private fun iso8601(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }

    // MARK: - Apple-style pretty JSON writer

    private fun pad(n: Int): String = "  ".repeat(n)

    @Suppress("UNCHECKED_CAST")
    private fun writeJson(value: Any?, sb: StringBuilder, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is Map<*, *> -> {
                val map = value as Map<String, Any?>
                if (map.isEmpty()) {
                    sb.append("{\n\n").append(pad(indent)).append("}")
                    return
                }
                sb.append("{\n")
                val keys = map.keys.sorted()
                for ((i, key) in keys.withIndex()) {
                    sb.append(pad(indent + 1))
                        .append(escapeJson(key))
                        .append(" : ")
                    writeJson(map[key], sb, indent + 1)
                    if (i < keys.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(pad(indent)).append("}")
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[\n\n").append(pad(indent)).append("]")
                    return
                }
                sb.append("[\n")
                for ((i, item) in value.withIndex()) {
                    sb.append(pad(indent + 1))
                    writeJson(item, sb, indent + 1)
                    if (i < value.size - 1) sb.append(",")
                    sb.append("\n")
                }
                sb.append(pad(indent)).append("]")
            }
            is String -> sb.append(escapeJson(value))
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int -> sb.append(value.toString())
            is Long -> sb.append(value.toString())
            is Float -> sb.append(numberString(value.toDouble()))
            is Double -> sb.append(numberString(value))
            else -> sb.append(escapeJson(value.toString()))
        }
    }

    /// Numbers print like NSNumber: integral doubles without a decimal point,
    /// everything else the shortest round-trip decimal (never exponent form
    /// for the coordinate/area magnitudes this exporter emits).
    private fun numberString(v: Double): String {
        if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
        val s = v.toString()
        return if (s.contains('E') || s.contains('e')) {
            BigDecimal.valueOf(v).toPlainString()
        } else {
            s
        }
    }

    private fun escapeJson(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '/' -> sb.append("\\/")   // Apple JSONSerialization quirk
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') sb.append(String.format(Locale.US, "\\u%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.append("\"").toString()
    }
}
