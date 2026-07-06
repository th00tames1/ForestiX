// KML stratum importer — 1:1 port of iOS Geo/KMLImporter.swift (spec §8,
// REQ-PRJ-002): import stratum boundaries from a Google-Earth-style KML
// document. Only `Placemark` elements carrying a `Polygon` (optionally nested
// under `MultiGeometry`) are extracted; other geometry types
// (Point/LineString/Model/etc.) are skipped.
//
// KML coordinates are expressed as `lon,lat[,alt]` whitespace-separated
// tuples. We reuse `ImportedPolygon` from `GeoJSONImporter` so downstream
// CruiseDesign code sees a single shape.
//
// Platform note: iOS uses the SAX-style XMLParser delegate; here the same
// state machine is driven from an XmlPullParser event loop (namespace-aware,
// so element names are local names — matching shouldProcessNamespaces).

package com.hcjeong.forestix.geo

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.IOException

/// Import failure reasons; `description` strings match the iOS
/// `KMLImportError` cases byte-for-byte.
sealed class KMLImportError(val description: String) : Exception(description) {
    class MalformedXML(reason: String) : KMLImportError("Malformed KML: $reason")
    class NoPolygons : KMLImportError("KML document contains no Polygon features")
}

object KMLImporter {

    fun importStrata(data: ByteArray): List<ImportedPolygon> {
        val parser = KMLParser()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val xml = factory.newPullParser()
            xml.setInput(ByteArrayInputStream(data), null)
            var event = xml.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> parser.didStartElement(xml.name)
                    XmlPullParser.TEXT -> parser.foundCharacters(xml.text ?: "")
                    XmlPullParser.END_TAG -> parser.didEndElement(xml.name)
                }
                event = xml.next()
            }
        } catch (e: KMLImportError) {
            throw e
        } catch (e: XmlPullParserException) {
            throw KMLImportError.MalformedXML(e.message ?: "parse failed")
        } catch (e: IOException) {
            throw KMLImportError.MalformedXML(e.message ?: "parse failed")
        }
        if (parser.polygons.isEmpty()) throw KMLImportError.NoPolygons()
        return parser.polygons
    }
}

// MARK: - SAX-style parser state machine

private class KMLParser {
    private enum class RingKind { OUTER, INNER }

    val polygons = mutableListOf<ImportedPolygon>()

    // Placemark-level state.
    private var placemarkName: String? = null
    private var placemarkAreaAcres: Double? = null

    // Polygon-level state (a Placemark may contain several via MultiGeometry).
    private var currentPolygonRings = mutableListOf<List<CoordinateConversions.LatLon>>()

    // Ring-level state.
    private var currentRingKind: RingKind? = null
    private var inCoordinates = false
    private var coordinateBuffer = StringBuilder()
    private var inName = false
    private var nameBuffer = StringBuilder()

    // Simple stack of element names so we know what context we're in.
    private val elementStack = mutableListOf<String>()

    fun didStartElement(elementName: String) {
        elementStack.add(elementName)
        when (elementName) {
            "Placemark" -> {
                placemarkName = null
                placemarkAreaAcres = null
                currentPolygonRings = mutableListOf()
            }
            "Polygon" -> currentPolygonRings = mutableListOf()
            "outerBoundaryIs" -> currentRingKind = RingKind.OUTER
            "innerBoundaryIs" -> currentRingKind = RingKind.INNER
            "coordinates" -> {
                inCoordinates = true
                coordinateBuffer = StringBuilder()
            }
            "name" -> {
                if (elementStack.getOrNull(elementStack.size - 2) == "Placemark") {
                    inName = true
                    nameBuffer = StringBuilder()
                }
            }
        }
    }

    fun foundCharacters(string: String) {
        if (inCoordinates) coordinateBuffer.append(string)
        if (inName) nameBuffer.append(string)
    }

    fun didEndElement(elementName: String) {
        try {
            when (elementName) {
                "coordinates" -> {
                    inCoordinates = false
                    val kind = currentRingKind
                    if (kind != null) {
                        val ring = parseCoordinateList(coordinateBuffer.toString())
                        when (kind) {
                            RingKind.OUTER -> currentPolygonRings.add(0, ring)   // outer first
                            RingKind.INNER -> currentPolygonRings.add(ring)
                        }
                    }
                }
                "outerBoundaryIs", "innerBoundaryIs" -> currentRingKind = null
                "name" -> {
                    if (inName) {
                        inName = false
                        placemarkName = nameBuffer.toString().trim()
                    }
                }
                "Polygon" -> flushPolygon()
                "Placemark" -> {
                    // A Placemark containing a Polygon directly (no MultiGeometry)
                    // has already been flushed. Reset state regardless.
                    placemarkName = null
                    placemarkAreaAcres = null
                    currentPolygonRings = mutableListOf()
                }
            }
        } finally {
            if (elementStack.lastOrNull() == elementName) {
                elementStack.removeAt(elementStack.size - 1)
            }
        }
    }

    private fun flushPolygon() {
        if (currentPolygonRings.isEmpty() || currentPolygonRings[0].isEmpty()) {
            currentPolygonRings = mutableListOf()
            return
        }
        val trimmedName = placemarkName
        val name = if (!trimmedName.isNullOrEmpty()) trimmedName else "Stratum"
        val areaM2 = kotlin.math.abs(
            GeoJSONImporter.signedPolygonAreaMetersSquared(currentPolygonRings)
        )
        val areaAcres = placemarkAreaAcres ?: GeoJSONImporter.metersSquaredToAcres(areaM2)
        val geoJSON = GeoJSONImporter.serialise(currentPolygonRings)
        polygons.add(
            ImportedPolygon(
                name = name,
                areaAcres = areaAcres,
                rings = currentPolygonRings,
                geoJSONString = geoJSON,
            )
        )
        currentPolygonRings = mutableListOf()
    }

    // MARK: - Coordinate list parsing

    /// Parse KML `coordinates` text: whitespace-separated `lon,lat[,alt]` tuples.
    private fun parseCoordinateList(text: String): List<CoordinateConversions.LatLon> {
        val tuples = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val ring = ArrayList<CoordinateConversions.LatLon>(tuples.size)
        for (tuple in tuples) {
            val parts = tuple.split(',').filter { it.isNotEmpty() }
            if (parts.size < 2) {
                throw KMLImportError.MalformedXML("Bad coordinate tuple: $tuple")
            }
            val lon = parts[0].toDoubleOrNull()
            val lat = parts[1].toDoubleOrNull()
            if (lon == null || lat == null) {
                throw KMLImportError.MalformedXML("Bad coordinate tuple: $tuple")
            }
            if (lon !in -180.0..180.0 || lat !in -90.0..90.0) {
                throw KMLImportError.MalformedXML("lat/lon out of range in tuple: $tuple")
            }
            ring.add(CoordinateConversions.LatLon(latitude = lat, longitude = lon))
        }
        // KML does not require the ring to close; close it if needed.
        val first = ring.firstOrNull()
        val last = ring.lastOrNull()
        if (first != null && last != null && first != last) {
            ring.add(first)
        }
        if (ring.size < 4) {
            throw KMLImportError.MalformedXML("Ring has < 4 closed positions")
        }
        return ring
    }
}
