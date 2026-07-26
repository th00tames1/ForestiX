// Survey-boundary import — Map settings → Survey boundary → "Import
// boundary file". Reads SHP (a .zip holding .shp/.dbf/.prj, or a bare .shp
// with a sibling .prj), KML, KMZ and GeoJSON, and normalises everything to
// WGS84 lon/lat polygons / polylines / points for the map renderer.
//
// ## The one hard rule: WGS84 only, and NEVER silently wrong
// A boundary drawn in the wrong CRS lands hundreds of kilometres from the
// stand and looks plausible at a glance, so this importer refuses anything
// it cannot confirm. What "confirm" means differs by format, and the
// difference is stated honestly here rather than papered over:
//   * SHP — CONFIRMED. The .prj must be a GEOGRAPHIC WGS84 CRS, and all
//     four of its declarations have to agree:
//       – the DATUM. When the WKT names one, IT decides, whatever the CRS
//         name or an AUTHORITY says: GEOGCS["WGS 84", DATUM["Tokyo",…]] is
//         a mislabelled export, not a WGS84 file. Only a WKT carrying no
//         datum at all falls back to the CRS name / an EPSG:4326 authority.
//       – the angular UNIT: degrees, not gradians.
//       – the PRIMEM: Greenwich, not Ferro or Paris.
//       – the root keyword: a projected CRS (UTM, Korea 2000 / EPSG:5186,
//         Lambert, State Plane, …), a geocentric or a local/engineering CRS
//         is refused outright.
//     A MISSING or EMPTY .prj is refused too, and so is one the WKT
//     parser cannot read — an unbracketed or truncated sidecar confirms
//     nothing, whatever names appear in it. Every refusal names what it
//     found. The WKT is parsed structurally into nodes: a `TOWGS84[…]`
//     shift block is NOT a WGS84 declaration, and a SPHEROID's or PRIMEM's
//     own AUTHORITY is never mistaken for the CRS's identity. Both WKT1
//     and WKT2 shapes are read, so a `GEOGCRS` whose angular unit sits on
//     an `AXIS` inside its `CS[…]` is gated exactly like a WKT1 `UNIT`.
//     In a .zip, every .shp is imported and each is gated on its OWN
//     `<stem>.prj` — a sibling's .prj is never borrowed, and no member is
//     dropped in silence.
//   * GeoJSON — CONFIRMED when the file says so, ASSUMED otherwise. RFC 7946
//     dropped the CRS member and fixes GeoJSON to WGS84, but real exports
//     still carry the GeoJSON-2008 `"crs": {"properties": {"name": …}}`
//     member; when it is there it is believed, and anything but a WGS84
//     spelling is refused naming what it declared. GeoJSON 2008 allowed
//     that member on ANY object, so it is read at EVERY level — the root,
//     each Feature, each geometry and each member of a GeometryCollection
//     — and one non-WGS84 declaration anywhere refuses the whole file. A
//     document with no `crs` member at any level keeps the RFC 7946
//     assumption.
//   * KML/KMZ — ASSUMED. KML has no CRS mechanism at all (KML §16.2 fixes
//     WGS84), so there is nothing to confirm and nothing to refuse on.
// The two assumed cases are not left bare: EVERY format additionally passes
// a coordinate RANGE check — |lon| > 180 or |lat| > 90 means the data is
// projected whatever the header claims, and it is refused the same way.
// That catches projected metres; it cannot catch a wrong geographic datum,
// which is why the shapefile and declared-crs paths confirm instead.
// Nothing is ever dropped quietly: every rejection throws a
// BoundaryImportError carrying the message the sheet shows.
//
// Kept free of android.* imports on purpose — java.util.zip,
// javax.xml.parsers (SAX) and kotlinx.serialization all run on the JVM
// too, so the parsers are exercisable in a plain JVM driver.

package com.hcjeong.forestix.geo

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.abs
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

// MARK: - Model

enum class BoundaryGeometryKind { POLYGON, POLYLINE, POINT }

/// One drawable boundary feature in WGS84 lon/lat.
/// POLYGON: `rings[0]` is the outer ring, the rest are holes.
/// POLYLINE: a single open path. POINT: a single one-element ring.
data class BoundaryGeometry(
    val kind: BoundaryGeometryKind,
    val rings: List<List<CoordinateConversions.LatLon>>,
    val name: String? = null,
)

/// A parsed boundary that has cleared every gate its format offers, ready
/// to store and draw: CRS-confirmed for a shapefile or a GeoJSON that
/// declared a `crs`, WGS84-assumed and range-checked for KML/KMZ and a
/// GeoJSON that declared nothing.
data class ImportedBoundary(
    val displayName: String,
    val geometries: List<BoundaryGeometry>,
    /// Normalised GeoJSON FeatureCollection — what gets persisted.
    val geoJSON: String,
) {
    val featureCount: Int get() = geometries.size
}

/// Every refusal the user can see. `message` is shown verbatim.
class BoundaryImportError(message: String) : Exception(message)

// MARK: - The WGS84 gate

object BoundaryCRS {

    /// The single refusal sentence, so every path says the same thing.
    fun notWgs84(found: String): String =
        "This file is not in WGS84 (found: $found). Convert it to WGS84 / " +
            "EPSG:4326 (for example in QGIS) and import again."

    /// Inspect a .prj WKT. Returns null when the CRS is an acceptable
    /// geographic WGS84, otherwise the human name of what was found.
    ///
    /// A sidecar that was never there and one that is there but blank are
    /// different failures, and the cruiser is told which.
    fun rejectionFor(prj: String?): String? {
        val text = prj?.trim()?.removePrefix("﻿")?.trim()
            ?: return "no .prj file"
        if (text.isEmpty()) return "empty .prj file"
        // FAIL CLOSED on WKT the node parser cannot read. A sidecar whose
        // root keyword carries no bracket at all — `GEOGCS "WGS 84" …` —
        // and one whose bracket is never CLOSED — `GEOGCS["WGS 84"`, the
        // shape a .prj truncated mid-write takes — both used to be
        // classified from a name alone, so anything that merely SPELLED a
        // WGS84 name somewhere was waved through with the datum, unit and
        // meridian gates running against an empty child list. The file
        // whose real body was `DATUM["Tokyo",…]` is the same bytes up to
        // the cut, and it would have been drawn a few hundred metres off.
        // Unparseable WKT confirms nothing, so it is refused rather than
        // guessed at (see `WktNode.Cursor.node`).
        //
        // The `TOWGS84[…]` shift blocks come out BEFORE the parse, so no
        // later test can mistake one for a datum declaration.
        val body = withoutTowgs84(text)
        val root = WktNode.parse(body) ?: return "an unrecognised coordinate system"
        val keyword = root.keyword
        // The ROOT node's OWN name — never the first quoted string in the
        // file. `GEOGCS[SPHEROID["WGS 84",…],PRIMEM[…],UNIT[…]]` declares
        // no name of its own, and borrowing the spheroid's is precisely
        // the mistake the rest of this gate refuses to make: KGD2002 and
        // the ITRF realisations all ship `SPHEROID["WGS 84",…]` while
        // sitting on their own datum. A root with no name is SAID to have
        // none rather than quoting some inner node's back at the cruiser.
        val name = root.name
        return when {
            keyword.startsWith("PROJCS") || keyword.startsWith("PROJCRS") ->
                named("projected CRS", name) ?: "an unnamed projected CRS"
            keyword.startsWith("GEOCCS") || keyword.startsWith("GEOCENTRICCRS") ->
                named("geocentric CRS", name) ?: "an unnamed geocentric CRS"
            // Geodetic — WKT1 `GEOGCS` and the WKT2 spellings. The only
            // family that can be accepted, and the only one whose ROOT
            // KEYWORD does not settle what the file holds: WKT2 spells a
            // GEOCENTRIC CRS `GEODCRS` too and tells the two apart by the
            // coordinate system instead. `CS[Cartesian,3]` is earth-centred
            // X/Y/Z metres — EPSG:4978 — not lon/lat, and it declares no
            // `ANGLEUNIT` at all, so the unit gate reads it as WKT1's
            // default degrees and waves it through. Only the coordinate
            // range downstream stopped it, one gate late and naming the
            // symptom ("coordinates outside the WGS84 range") rather than
            // the cause. So the CS is read here, and a geocentric one is
            // refused by the same wording `GEOCCS` gets.
            keyword.startsWith("GEOGCS") || keyword.startsWith("GEOGCRS") ||
                keyword.startsWith("GEOGRAPHICCRS") ||
                keyword.startsWith("GEODCRS") || keyword.startsWith("GEODETICCRS") ->
                if (isGeocentric(root)) {
                    named("geocentric CRS", name) ?: "an unnamed geocentric CRS"
                } else {
                    geographicRejection(root, body, name)
                }
            keyword.startsWith("LOCAL_CS") || keyword.startsWith("ENGCRS") ||
                keyword.startsWith("ENGINEERINGCRS") ->
                named("local/engineering CRS", name) ?: "an unnamed local/engineering CRS"
            else -> "an unrecognised coordinate system"
        }
    }

    /// `kind "name"` for the refusal, or null when the node is unnamed and
    /// the caller has to say so in words instead.
    private fun named(kind: String, name: String?): String? {
        if (name.isNullOrEmpty()) return null
        return "$kind \"$name\""
    }

    /// The GEOGCS/GEOGCRS verdict: null when the body really is WGS 84 in
    /// degrees on Greenwich, otherwise the human name of what was found.
    ///
    /// Parsed STRUCTURALLY, never by substring over the whole WKT. Most
    /// GDAL/OGC WKT1 for a NON-WGS84 datum carries a `TOWGS84[…]` block —
    /// the seven-parameter shift *towards* WGS 84, not a claim of being in
    /// it — so a naive "does the text contain WGS84" test accepts a
    /// Korea 2000 or Tokyo-datum GEOGCS in degrees and draws it a few
    /// hundred metres off, silently: exactly the mis-placement this gate
    /// exists to prevent. The shift blocks are stripped first, then three
    /// things are asked in this order, which is the order of what can lie:
    ///
    ///  1. THE DATUM, when the WKT names one, decides ALONE — whatever the
    ///     GEOGCS name or an AUTHORITY claims. `GEOGCS["WGS 84",
    ///     DATUM["Tokyo", SPHEROID["Bessel 1841",…], TOWGS84[…]]]` is the
    ///     classic mislabelled export: the name was hand-edited or set by a
    ///     tool that only rewrote the label. Believing the name there puts
    ///     a Korean stand a few hundred metres out, in degrees, in range,
    ///     with nothing downstream able to notice. So the datum is quoted
    ///     back and the file refused.
    ///  2. Only a WKT carrying NO datum node at all falls back to the CRS
    ///     name and an explicit EPSG:4326 authority.
    ///  3. Then the two declarations nothing else re-reads — the angular
    ///     UNIT and the PRIMEM (see `axisRejection`).
    ///
    /// The SPHEROID is deliberately never consulted: KGD2002 and the ITRF
    /// realisations are routinely written `SPHEROID["WGS 84",…]` while
    /// sitting on their own datum. An ellipsoid is not a datum.
    private fun geographicRejection(root: WktNode, body: String, crsName: String?): String? {
        // 1 & 2 — the datum, or the name/authority when there is no datum.
        // The string scan is kept as a backstop for a datum the node
        // parser nested somewhere unexpected: it can only find MORE
        // datums, never fewer, so it can only make the gate stricter.
        val datumName = root.child(DATUM_KEYWORDS)?.let { it.name ?: "" }
            ?: quotedNameFor(body, DATUM_KEYWORDS)
        if (datumName != null) {
            if (!isWgs84Name(datumName)) {
                // A datum node with no name confirms nothing either, and
                // there is no name to quote back.
                return if (datumName.isEmpty()) "an unnamed datum"
                else "datum \"$datumName\""
            }
        } else {
            // The ROOT's own name, and nothing else. Scanning the whole
            // body for a WGS84 spelling accepted
            // `GEOGCS[SPHEROID["WGS 84",…],…]` on the SPHEROID's name —
            // the one node this gate has always said it must never
            // consult.
            val namedWgs84 = crsName != null && isWgs84Name(crsName)
            val authority = root.declaresAuthority("EPSG", "4326")
            if (!namedWgs84 && !authority) {
                return named("geographic CRS", crsName) ?: "an unnamed geographic CRS"
            }
        }

        // 3 — degrees, on Greenwich.
        return axisRejection(root)
    }

    /// True when a WKT2 geodetic CRS describes GEOCENTRIC coordinates —
    /// earth-centred X/Y/Z metres — rather than the lon/lat this app
    /// draws.
    ///
    /// WKT1 wrote the two families under two keywords, `GEOGCS` and
    /// `GEOCCS`, so the root keyword was the whole answer. WKT2 writes
    /// BOTH as `GEODCRS`/`GEODETICCRS` and settles it in the coordinate
    /// system instead: `CS[Cartesian,3]` is geocentric (EPSG:4978),
    /// `CS[ellipsoidal,2]` and `CS[ellipsoidal,3]` are geographic
    /// (EPSG:4326, EPSG:4979). The CS type is a BARE unquoted word,
    /// which is the reason `WktNode` keeps those beside the numbers; a
    /// writer that quotes it anyway is read the same way.
    ///
    /// WKT1's `GEOGCS` is geographic by definition and carries no CS node,
    /// so it is exempt; WKT1's geocentric `GEOCCS` is already refused by
    /// keyword one branch above. A `GEOGCRS` that nonetheless declares a
    /// Cartesian CS is hand-edited, and is read as what it says it is.
    private fun isGeocentric(root: WktNode): Boolean {
        val keyword = root.keyword
        val isWkt2Geodetic = keyword == "GEODCRS" || keyword == "GEODETICCRS"
        // WKT2 REQUIRES a CS on a geodetic CRS, so a missing or unreadable one
        // means the axes cannot be confirmed as ellipsoidal — refuse instead of
        // assuming degrees. (Only the range check stood behind this, and it
        // reads lon/lat but never Z, so a geocentric triple near a pole slipped
        // through both gates.) WKT1's GEOGCS carries no CS node by design and
        // is geographic by definition, so it stays exempt.
        val cs = root.child(COORDINATE_SYSTEM_NODE) ?: return isWkt2Geodetic
        val type = cs.tokens.firstOrNull() ?: cs.quoted.firstOrNull()
            ?: return isWkt2Geodetic
        // A Cartesian CS is geocentric whichever geodetic keyword it wears —
        // a hand-edited GEOGCRS included.
        return normalisedName(type) == "CARTESIAN"
    }

    /// The node WKT2 states the coordinate-system type in.
    private val COORDINATE_SYSTEM_NODE = listOf("CS")

    /// The two declarations nothing downstream ever re-reads: the angular
    /// UNIT and the PRIMEM.
    ///
    /// A GEOGCS can sit on the WGS 84 datum and still be written in
    /// gradians (`UNIT["grad",0.0157…]`) or measured from Ferro
    /// (`PRIMEM["Ferro",-17.666…]`). Both pass every other gate, both stay
    /// inside ±180/±90 so the range check cannot see them, and both put the
    /// stand 1,200–1,600 km from where the cruiser is standing without a
    /// word. So a PRESENT unit must say degrees and a PRESENT prime
    /// meridian must say Greenwich; an ABSENT one keeps the ordinary
    /// degrees/Greenwich assumption, which is what every real .prj means.
    ///
    /// The PRIMEM is read as a DIRECT child of the CRS node, so a prime
    /// meridian nested inside some other node can never answer for the
    /// CRS's own; the angular unit is read from the CRS node and from the
    /// coordinate-system scaffolding beneath it (see `angularUnits`),
    /// because that is where WKT2 actually writes it. In both, the
    /// DECLARED NUMBER outranks the declared name whenever there
    /// is one: ESRI writes `UNIT["Decimal_Degree",0.01745…]`, whose name is
    /// on no short list but whose factor is exactly right, while a unit
    /// labelled "degree" carrying the gradian factor is the same
    /// contradiction the datum rule refuses. Only a node with no number at
    /// all is judged by its name.
    private fun axisRejection(root: WktNode): String? {
        // No UNIT at all keeps WKT1's default, degrees.
        for (unit in angularUnits(root)) {
            val name = unit.name ?: ""
            val factor = unit.numbers.firstOrNull()
            if (factor != null) {
                if (abs(factor - RADIANS_PER_DEGREE) > ANGLE_TOLERANCE) {
                    return "angular unit ${quoting(name, factor)}"
                }
            } else if (!DEGREE_UNIT_NAMES.contains(normalisedName(name))) {
                return "angular unit ${quoting(name, null)}"
            }
        }

        // No PRIMEM keeps WKT1's default, Greenwich.
        root.child(PRIME_MERIDIAN_KEYWORDS)?.let { primem ->
            val name = primem.name ?: ""
            val offset = primem.numbers.firstOrNull()
            if (offset != null) {
                if (abs(offset) > ANGLE_TOLERANCE) {
                    return "prime meridian ${quoting(name, offset)}"
                }
            } else if (normalisedName(name) != "GREENWICH") {
                return "prime meridian ${quoting(name, null)}"
            }
        }

        return null
    }

    /// Every node that can declare the CRS's angular unit. WKT1 puts a
    /// single `UNIT` directly under the GEOGCS; WKT2 puts an `ANGLEUNIT`
    /// under the CRS, under its `CS[…]`, or once per `AXIS[…]` — and PROJ
    /// writes the per-axis form, so reading only the direct child would
    /// wave a gradian WKT2 sidecar straight through. All of them are read;
    /// one non-degree unit anywhere in that set refuses.
    ///
    /// The search descends through the coordinate-system SCAFFOLDING at
    /// any depth — `CS[…]` and `AXIS[…]` — because writers disagree about
    /// whether the axes are siblings of `CS[…]` or nested inside it, and a
    /// `GEOGCRS` whose gradian `ANGLEUNIT` sat on an `AXIS` INSIDE its
    /// `CS[…]` was accepted while the byte-identical file with the axes
    /// hoisted one level was refused: the gate simply never saw the unit.
    ///
    /// Nothing else is descended into. A `PRIMEM`'s own `ANGLEUNIT` gives
    /// the units of the meridian OFFSET, not of the stored coordinates,
    /// and is judged with the prime meridian instead; a `DATUM`'s or a
    /// `BASEGEOGCRS`'s units answer for something that is not this CRS.
    private fun angularUnits(root: WktNode): List<WktNode> {
        val out = ArrayList<WktNode>()
        collectAngularUnits(root, out)
        return out
    }

    private fun collectAngularUnits(node: WktNode, out: MutableList<WktNode>) {
        for (child in node.children) {
            if (ANGULAR_UNIT_KEYWORDS.contains(child.keyword)) {
                out.add(child)
            } else if (COORDINATE_SYSTEM_KEYWORDS.contains(child.keyword)) {
                collectAngularUnits(child, out)
            }
        }
    }

    /// The scaffolding an angular unit may hide under — nothing else is
    /// followed, so the set can never grow to include a node that speaks
    /// for a different CRS.
    private val COORDINATE_SYSTEM_KEYWORDS = listOf("CS", "AXIS")

    /// One degree in radians — the factor a WGS84 `.prj` carries on its
    /// angular `UNIT`. Gradians (0.015707…), radians (1.0) and arc-seconds
    /// (4.848e-6) all sit orders of magnitude outside the window below,
    /// while the 15- and 17-digit spellings GDAL and ESRI write
    /// ("0.0174532925199433", "0.017453292519943295") sit well inside it.
    private const val RADIANS_PER_DEGREE = 0.017453292519943295

    /// Tight enough to separate degrees from every other angular unit in
    /// use, loose enough to absorb the digits a writer chose to keep. Also
    /// the window a PRIMEM offset must fall in to count as Greenwich —
    /// Ferro is -17.67 away and Paris 2.34.
    private const val ANGLE_TOLERANCE = 1e-9

    /// Only reached when a `UNIT`/`PRIMEM` declares no factor at all, which
    /// WKT1's grammar does not actually permit — kept so a malformed
    /// sidecar is refused by name instead of waved through.
    private val DEGREE_UNIT_NAMES = setOf("DEGREE", "DEGREES")

    /// What the refusal quotes back: the node's name when it has one,
    /// otherwise the number it carried. Always quoted, so every message in
    /// this family reads `… (found: angular unit "grad")`.
    private fun quoting(name: String, value: Double?): String {
        if (name.isNotEmpty()) return "\"$name\""
        if (value == null) return "\"\""
        return "\"${trim(value)}\""
    }

    /// Case, spaces and hyphens folded the same way `isWgs84Name` folds
    /// them, so "Degree", "degree" and "DEGREE" are one name.
    private fun normalisedName(raw: String): String =
        raw.uppercase().replace(' ', '_').replace('-', '_').trim('_')

    /// WKT1 `DATUM`, the WKT2 spellings `GEODETICDATUM`/`TRF`, and the
    /// WKT2:2019 `ENSEMBLE` PROJ now emits for WGS 84 in place of a datum.
    private val DATUM_KEYWORDS = listOf("GEODETICDATUM", "DATUM", "TRF", "ENSEMBLE")

    /// WKT1 `UNIT` and the WKT2 `ANGLEUNIT`, as a DIRECT child of a
    /// geographic CRS: its angular unit.
    private val ANGULAR_UNIT_KEYWORDS = listOf("UNIT", "ANGLEUNIT")

    /// WKT1 `PRIMEM` and the WKT2 `PRIMEMERIDIAN`.
    private val PRIME_MERIDIAN_KEYWORDS = listOf("PRIMEM", "PRIMEMERIDIAN")

    /// Recognised spellings of WGS 84 in ONE quoted name — OGC "WGS 84" /
    /// "WGS_1984", ESRI "GCS_WGS_1984" / "D_WGS_1984", and the WKT2
    /// "World Geodetic System 1984 ensemble". Substring matching is safe
    /// here precisely because it never sees the rest of the WKT.
    private fun isWgs84Name(name: String): Boolean {
        val squashed = squashedName(name)
        return squashed.contains("WGS1984") || squashed.contains("WGS84") ||
            squashed.contains("WORLDGEODETICSYSTEM1984")
    }

    /// Upper-cased, punctuation dropped, so "WGS 84", "WGS_84", "wgs-84"
    /// and "Degree" / "degree" all compare as one spelling.
    private fun squashedName(name: String): String =
        name.uppercase().filter { it.isLetterOrDigit() }

    /// Drop every `TOWGS84[…]` datum-shift block, keyword and all, so no
    /// later test can mistake it for a datum declaration.
    private fun withoutTowgs84(text: String): String {
        val upper = text.uppercase()
        if (!upper.contains("TOWGS84")) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if (!upper.startsWith("TOWGS84", i)) {
                out.append(text[i])
                i++
                continue
            }
            var j = i + "TOWGS84".length
            while (j < text.length && text[j].isWhitespace()) j++
            if (j < text.length && (text[j] == '[' || text[j] == '(')) {
                val open = text[j]
                val close = if (open == '[') ']' else ')'
                var depth = 0
                while (j < text.length) {
                    if (text[j] == open) {
                        depth++
                    } else if (text[j] == close) {
                        depth--
                        if (depth <= 0) { j++; break }
                    }
                    j++
                }
            }
            i = j   // keyword and its bracket group are both dropped
        }
        return out.toString()
    }

    /// The name quoted first inside `KEYWORD[…]`, matched as a WHOLE WKT
    /// keyword so `DATUM` never fires inside `GEODETICDATUM`. Returns ""
    /// when the keyword is present but unnamed — which reads as "not
    /// WGS 84", the safe answer — and null when it is absent entirely.
    private fun quotedNameFor(text: String, keywords: List<String>): String? {
        val upper = text.uppercase()
        for (keyword in keywords) {
            var from = 0
            while (true) {
                val at = upper.indexOf(keyword, from)
                if (at < 0) break
                from = at + keyword.length
                val before = if (at == 0) ' ' else text[at - 1]
                if (before.isLetterOrDigit() || before == '_') continue
                var i = from
                while (i < text.length && text[i].isWhitespace()) i++
                if (i >= text.length || (text[i] != '[' && text[i] != '(')) continue
                var j = i + 1
                while (j < text.length && text[j].isWhitespace()) j++
                if (j >= text.length || text[j] != '"') return ""
                val close = text.indexOf('"', j + 1)
                return if (close > j) text.substring(j + 1, close) else ""
            }
        }
        return null
    }

    /// Range gate — the check that catches projected metres wearing a
    /// WGS84 label. Throws with the same refusal sentence.
    fun requireInRange(geometries: List<BoundaryGeometry>) {
        for (geometry in geometries) {
            for (ring in geometry.rings) {
                for (p in ring) {
                    if (!p.longitude.isFinite() || !p.latitude.isFinite()) {
                        throw BoundaryImportError(
                            notWgs84("a coordinate that is not a number")
                        )
                    }
                    if (abs(p.longitude) > 180.0 || abs(p.latitude) > 90.0) {
                        throw BoundaryImportError(
                            notWgs84(
                                "coordinates outside the WGS84 range — " +
                                    "lon ${trim(p.longitude)}, lat ${trim(p.latitude)}"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble() && abs(v) < 1e15) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.6f", v).trimEnd('0').trimEnd('.')
}

// MARK: - The GeoJSON `crs` member

/// RFC 7946 §4 deleted the CRS member and declares every GeoJSON to be
/// WGS84 lon/lat, but deleting a member from a spec does not delete it
/// from the files already on disk, and a Korean export carrying
///
///     "crs": {"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::4737"}}
///
/// is degrees, inside |lon| ≤ 180 / |lat| ≤ 90, and a few hundred metres
/// from where the cruiser is standing. Ignoring the member is what let it
/// through; reading it is the only way the file names itself.
///
/// Absence — at EVERY level of the document, not just the root — keeps
/// the RFC 7946 assumption; that is the overwhelmingly common case and
/// refusing it would refuse almost every valid GeoJSON. Presence anywhere
/// must be one of the WGS84 spellings or it is refused, INCLUDING a `crs`
/// object we cannot read a name out of: "present but unreadable" is not a
/// confirmation.
object GeoJSONCRS {

    /// The WHOLE document's verdict: null when every level of it may be
    /// treated as WGS84 lon/lat, otherwise the text to quote back at the
    /// cruiser for the first declaration that is not.
    ///
    /// GeoJSON 2008 §3 permitted `crs` on ANY GeoJSON object, not only the
    /// root, and older QGIS/ArcGIS exports put it on the Feature. Reading
    /// only the root meant a document with
    ///
    ///     "crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::5186"}}
    ///
    /// on its Feature — or on the geometry, or on one member of a
    /// GeometryCollection — was ACCEPTED and drawn a few hundred metres
    /// off in silence, while the byte-identical file with that same member
    /// at the root was correctly refused. The declaration means the same
    /// thing wherever it sits, so it is read wherever it sits, with the
    /// same allow-list and the same sentence.
    fun rejectionAnywhere(root: JsonObject): String? = walk(root)

    /// Follows only the members that hold GeoJSON OBJECTS — a Feature
    /// collection's `features`, a Feature's `geometry`, a
    /// GeometryCollection's `geometries`. `properties` is deliberately not
    /// followed: a user attribute that happens to be called "crs" is
    /// stand data, not a declaration about the file's coordinates.
    private fun walk(obj: JsonObject): String? {
        rejection(obj)?.let { return it }
        (obj["features"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { feature -> walk(feature)?.let { return it } }
        }
        (obj["geometry"] as? JsonObject)?.let { geometry -> walk(geometry)?.let { return it } }
        (obj["geometries"] as? JsonArray)?.forEach { element ->
            (element as? JsonObject)?.let { geometry -> walk(geometry)?.let { return it } }
        }
        return null
    }

    /// One object's own `crs` member. Private on purpose: every caller
    /// must go through `rejectionAnywhere`, so no future entry point can
    /// re-open the root-only hole.
    private fun rejection(root: JsonObject): String? {
        // `"crs": null` is GeoJSON 2008's own way of saying "no CRS
        // stated", so it reads exactly like an absent member.
        val crs = root["crs"] ?: return null
        if (crs is JsonNull) return null
        val declared = declaredName(crs) ?: return "an unrecognised GeoJSON crs member"
        val normalised = declared.trim().uppercase()
        return if (WGS84_SPELLINGS.contains(normalised)) null
        else "GeoJSON crs \"$declared\""
    }

    /// Every way of spelling WGS84 lon/lat that a `crs` member uses — the
    /// EPSG short form, both URN forms, and the two CRS84 spellings.
    /// Whole strings, upper-cased: `urn:ogc:def:crs:EPSG::4737` differs
    /// from the accepted URN by three characters and must not match.
    private val WGS84_SPELLINGS = setOf(
        "EPSG:4326",
        "URN:OGC:DEF:CRS:EPSG::4326",
        "URN:OGC:DEF:CRS:OGC:1.3:CRS84",
        "CRS:84",
        "CRS84",
    )

    /// What the member declares: a named CRS's `name`, a linked CRS's
    /// `href`, or the bare string some writers emit instead of an object.
    /// Null when the member says nothing we can read.
    private fun declaredName(crs: JsonElement): String? {
        if (crs is JsonPrimitive && crs.isString) return crs.content.ifEmpty { null }
        val obj = crs as? JsonObject ?: return null
        val properties = obj["properties"] as? JsonObject ?: return null
        text(properties["name"])?.let { return it }
        return text(properties["href"])
    }

    private fun text(v: JsonElement?): String? =
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifEmpty { null }
}

// MARK: - Minimal WKT node parser

/// One node of an OGC WKT string — `KEYWORD["quoted", 123, CHILD[…]]`.
///
/// Deliberately tiny: the gate needs node NAMES, node NUMBERS and node
/// IDENTITY, not a CRS model. Parsing rather than substring-matching is
/// the entire point — once the text is a tree, a `TOWGS84[…]`
/// transformation block is just a child node nobody asks about, a
/// SPHEROID's or PRIMEM's own `AUTHORITY` can never be mistaken for the
/// CRS's identity, and a `UNIT` nested inside another node can never
/// answer for the CRS's own angular unit.
internal class WktNode(
    /// Upper-cased keyword: "GEOGCS", "DATUM", "UNIT"…
    val keyword: String,
    /// Quoted arguments in order, quotes removed.
    val quoted: List<String>,
    /// Bare numeric arguments in order — a UNIT's conversion factor, a
    /// PRIMEM's offset.
    val numbers: List<Double>,
    /// Bare NON-numeric arguments in order. WKT2 writes the
    /// coordinate-system type as one of these — `CS[Cartesian,3]` versus
    /// `CS[ellipsoidal,2]` — and that single unquoted word is all that
    /// separates a geocentric `GEODCRS` from a geographic one, so it is
    /// kept instead of dropped on the floor with the axis directions.
    val tokens: List<String>,
    val children: List<WktNode>,
) {
    /// A WKT node's name is its first quoted argument.
    val name: String? get() = quoted.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun child(keywords: List<String>): WktNode? =
        children.firstOrNull { keywords.contains(it.keyword) }

    /// `AUTHORITY["EPSG","4326"]` / `ID["EPSG",4326]` as a DIRECT child of
    /// this node — the node's own identity, never one borrowed from a
    /// nested spheroid, primem or unit.
    fun declaresAuthority(authority: String, code: String): Boolean =
        children.any { c ->
            (c.keyword == "AUTHORITY" || c.keyword == "ID") &&
                c.quoted.firstOrNull()?.equals(authority, ignoreCase = true) == true &&
                (c.quoted.getOrNull(1)?.trim() == code ||
                    c.numbers.firstOrNull() == code.toDoubleOrNull())
        }

    companion object {
        /// Parse the outermost node; null when the text is not a bracketed
        /// WKT node at all.
        fun parse(wkt: String): WktNode? = Cursor(wkt).node()
    }

    private class Cursor(val s: String) {
        var i = 0

        /// Null unless the node is COMPLETE — keyword, opening bracket,
        /// arguments AND the closing bracket. Running out of input part
        /// way through used to yield the node built so far, so
        /// `GEOGCS["WGS 84"` — a .prj truncated mid-write, whose real body
        /// carried `DATUM["Tokyo",…]` — parsed as if it were a whole
        /// declaration and met the datum, unit and meridian gates with an
        /// empty child list, clearing every one of them. A node nobody
        /// finished writing states nothing, so it parses as nothing and
        /// `rejectionFor` refuses the file.
        fun node(): WktNode? {
            skipSpace()
            val keyStart = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
            val keyword = s.substring(keyStart, i).uppercase()
            if (keyword.isEmpty()) return null
            skipSpace()
            // WKT permits either bracket flavour.
            if (i >= s.length || (s[i] != '[' && s[i] != '(')) return null
            val closing = if (s[i] == '[') ']' else ')'
            i++

            val quoted = ArrayList<String>()
            val numbers = ArrayList<Double>()
            val tokens = ArrayList<String>()
            val children = ArrayList<WktNode>()
            var closed = false
            while (i < s.length) {
                skipSpace()
                if (i >= s.length) break
                val c = s[i]
                if (c == closing) { i++; closed = true; break }
                if (c == ',') { i++; continue }
                if (c == '"') {
                    i++
                    val sb = StringBuilder()
                    while (i < s.length) {
                        if (s[i] == '"') {
                            // A literal quote inside a name is doubled.
                            if (i + 1 < s.length && s[i + 1] == '"') {
                                sb.append('"'); i += 2; continue
                            }
                            i++
                            break
                        }
                        sb.append(s[i])
                        i++
                    }
                    quoted.add(sb.toString())
                    continue
                }
                if (c.isLetter() || c == '_') {
                    val save = i
                    val child = node()
                    if (child != null) { children.add(child); continue }
                    i = save
                }
                // A bare token — a number, a coordinate-system type, an
                // axis direction, or a keyword with no bracket. Numbers
                // carry the UNIT/PRIMEM verdict; the words carry the
                // `CS[Cartesian,3]` one; both are kept, apart.
                val start = i
                while (i < s.length && s[i] != ',' && s[i] != closing && s[i] != '"') i++
                val token = s.substring(start, i).trim()
                val number = token.toDoubleOrNull()
                if (number != null) numbers.add(number)
                else if (token.isNotEmpty()) tokens.add(token)
            }
            if (!closed) return null
            return WktNode(keyword, quoted, numbers, tokens, children)
        }

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}

// MARK: - Entry point

object BoundaryImporter {

    /// Formats offered in the sheet's hint line.
    const val FORMAT_HINT = "SHP (.shp + .prj, or .zip) · KML/KMZ · GeoJSON"

    /// Parse `bytes` picked as `fileName`. `sidecarPrj` carries the text of
    /// a sibling .prj when the caller could resolve one for a bare .shp;
    /// null means "none found", which is itself a refusal reason.
    fun import(
        fileName: String,
        bytes: ByteArray,
        sidecarPrj: String? = null,
    ): ImportedBoundary {
        if (bytes.isEmpty()) throw BoundaryImportError("That file is empty.")
        val display = displayName(fileName)
        val geometries = when (detect(fileName, bytes)) {
            Format.ZIP -> fromZip(bytes)
            Format.KML -> KmlBoundaryParser.parse(bytes)
            Format.GEOJSON -> GeoJsonBoundaryParser.parse(bytes.toString(Charsets.UTF_8))
            Format.SHP -> {
                val rejection = BoundaryCRS.rejectionFor(sidecarPrj)
                if (rejection != null) throw BoundaryImportError(BoundaryCRS.notWgs84(rejection))
                ShapefileReader.read(bytes)
            }
        }
        return finish(display, geometries)
    }

    /// Shared tail: nothing reaches the map without passing the range gate.
    private fun finish(display: String, geometries: List<BoundaryGeometry>): ImportedBoundary {
        val kept = geometries.filter { g -> g.rings.any { it.isNotEmpty() } }
        if (kept.isEmpty()) {
            throw BoundaryImportError("No polygons, lines or points were found in that file.")
        }
        BoundaryCRS.requireInRange(kept)
        return ImportedBoundary(
            displayName = display,
            geometries = kept,
            geoJSON = BoundaryGeoJSON.serialise(kept),
        )
    }

    private enum class Format { ZIP, KML, GEOJSON, SHP }

    private fun detect(fileName: String, bytes: ByteArray): Format {
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "zip", "kmz" -> return Format.ZIP
            "kml" -> return Format.KML
            "geojson", "json" -> return Format.GEOJSON
            "shp" -> return Format.SHP
        }
        // Unknown / missing extension (some providers hand back a display
        // name with none) — sniff the magic bytes instead of guessing.
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            return Format.ZIP
        }
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
            bytes[2] == 0x27.toByte() && bytes[3] == 0x0A.toByte()
        ) {
            return Format.SHP
        }
        val head = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart()
        if (head.startsWith("{") || head.startsWith("[")) return Format.GEOJSON
        if (head.startsWith("<")) return Format.KML
        throw BoundaryImportError(
            "Unsupported file type. Use $FORMAT_HINT."
        )
    }

    /// A .kmz or a zipped shapefile — decided by what is inside.
    private fun fromZip(bytes: ByteArray): List<BoundaryGeometry> {
        val entries = readZip(bytes)
        if (entries.isEmpty()) throw BoundaryImportError("That archive is empty.")

        val kml = entries.keys.firstOrNull { it.endsWith("doc.kml", ignoreCase = true) }
            ?: entries.keys.firstOrNull { it.endsWith(".kml", ignoreCase = true) }
        val shps = entries.keys
            .filter { it.endsWith(".shp", ignoreCase = true) }
            .sortedBy { it.lowercase() }

        if (shps.isNotEmpty()) {
            // EVERY shapefile in the archive is imported, each gated on ITS
            // OWN `<stem>.prj`. Borrowing a different member's .prj would
            // classify one shapefile's CRS from an unrelated file, and
            // importing only the first would drop the rest in silence —
            // both are ways to draw a boundary nobody confirmed.
            val out = ArrayList<BoundaryGeometry>()
            for (shp in shps) {
                val stem = shp.dropLast(4)
                val prjKey = entries.keys.firstOrNull { it.equals("$stem.prj", ignoreCase = true) }
                val prjText = prjKey?.let { entries[it]!!.toString(Charsets.UTF_8) }
                val rejection = BoundaryCRS.rejectionFor(prjText)
                if (rejection != null) throw BoundaryImportError(BoundaryCRS.notWgs84(rejection))
                out += ShapefileReader.read(entries[shp]!!)
            }
            return out
        }
        if (kml != null) return KmlBoundaryParser.parse(entries[kml]!!)

        val geojson = entries.keys.firstOrNull {
            it.endsWith(".geojson", ignoreCase = true) || it.endsWith(".json", ignoreCase = true)
        }
        if (geojson != null) {
            return GeoJsonBoundaryParser.parse(entries[geojson]!!.toString(Charsets.UTF_8))
        }
        throw BoundaryImportError(
            "That archive holds no .shp, .kml or .geojson — use $FORMAT_HINT."
        )
    }

    /// java.util.zip handles both STORED and DEFLATE for us; directory
    /// entries and the __MACOSX resource forks are skipped.
    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory ||
                        name.startsWith("__MACOSX/") ||
                        name.substringAfterLast('/').startsWith("._")
                    ) {
                        zip.closeEntry()
                        continue
                    }
                    out[name] = zip.readBytes()
                    zip.closeEntry()
                }
            }
        } catch (e: Exception) {
            throw BoundaryImportError("That archive could not be read (${e.message ?: "zip error"}).")
        }
        return out
    }

    /// Display name for the boundary record — the file's base name.
    private fun displayName(fileName: String): String {
        val base = fileName.substringAfterLast('/').substringBeforeLast('.').trim()
        return if (base.isEmpty()) "Boundary" else base
    }
}

// MARK: - GeoJSON (RFC 7946 — always lon, lat, unless the file says otherwise)

internal object GeoJsonBoundaryParser {

    fun parse(text: String): List<BoundaryGeometry> {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
                ?: throw BoundaryImportError("That GeoJSON's top level is not an object.")
        } catch (e: BoundaryImportError) {
            throw e
        } catch (e: Exception) {
            throw BoundaryImportError("Malformed GeoJSON: ${e.message ?: "parse failed"}")
        }
        // The declared CRS, when there is one — at ANY level of the
        // document, because GeoJSON 2008 allowed the member on every
        // object and exporters used that. A legacy `crs` member naming
        // anything but WGS84 is believed and refused — its positions are
        // degrees and in range, so nothing downstream could catch it.
        GeoJSONCRS.rejectionAnywhere(root)?.let {
            throw BoundaryImportError(BoundaryCRS.notWgs84(it))
        }
        val out = ArrayList<BoundaryGeometry>()
        collect(root, null, out)
        return out
    }

    private fun collect(obj: JsonObject, name: String?, out: MutableList<BoundaryGeometry>) {
        when (str(obj["type"])) {
            "FeatureCollection" ->
                (obj["features"] as? JsonArray)?.forEach {
                    (it as? JsonObject)?.let { f -> collect(f, name, out) }
                }

            "Feature" -> {
                val props = obj["properties"] as? JsonObject
                val featureName = listOf("name", "Name", "NAME", "title")
                    .firstNotNullOfOrNull { key -> props?.get(key)?.let { str(it) } }
                    ?.trim()?.takeIf { it.isNotEmpty() }
                (obj["geometry"] as? JsonObject)?.let { collect(it, featureName ?: name, out) }
            }

            "GeometryCollection" ->
                (obj["geometries"] as? JsonArray)?.forEach {
                    (it as? JsonObject)?.let { g -> collect(g, name, out) }
                }

            "Polygon" -> rings(obj["coordinates"])?.let {
                out.add(BoundaryGeometry(BoundaryGeometryKind.POLYGON, it, name))
            }

            "MultiPolygon" -> (obj["coordinates"] as? JsonArray)?.forEach { poly ->
                rings(poly)?.let {
                    out.add(BoundaryGeometry(BoundaryGeometryKind.POLYGON, it, name))
                }
            }

            "LineString" -> positions(obj["coordinates"])?.let {
                out.add(BoundaryGeometry(BoundaryGeometryKind.POLYLINE, listOf(it), name))
            }

            "MultiLineString" -> (obj["coordinates"] as? JsonArray)?.forEach { line ->
                positions(line)?.let {
                    out.add(BoundaryGeometry(BoundaryGeometryKind.POLYLINE, listOf(it), name))
                }
            }

            "Point" -> position(obj["coordinates"])?.let {
                out.add(BoundaryGeometry(BoundaryGeometryKind.POINT, listOf(listOf(it)), name))
            }

            "MultiPoint" -> positions(obj["coordinates"])?.forEach {
                out.add(BoundaryGeometry(BoundaryGeometryKind.POINT, listOf(listOf(it)), name))
            }

            null -> throw BoundaryImportError("That GeoJSON object has no \"type\".")
            else -> Unit   // unknown member types are ignored, not fatal
        }
    }

    private fun str(v: Any?): String? =
        (v as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun rings(v: Any?): List<List<CoordinateConversions.LatLon>>? {
        val array = v as? JsonArray ?: return null
        val out = array.mapNotNull { positions(it) }.filter { it.isNotEmpty() }
        return out.ifEmpty { null }
    }

    private fun positions(v: Any?): List<CoordinateConversions.LatLon>? {
        val array = v as? JsonArray ?: return null
        val out = array.mapNotNull { position(it) }
        return out.ifEmpty { null }
    }

    private fun position(v: Any?): CoordinateConversions.LatLon? {
        val pair = v as? JsonArray ?: return null
        if (pair.size < 2) return null
        val lon = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lat = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return null
        return CoordinateConversions.LatLon(latitude = lat, longitude = lon)
    }
}

// MARK: - KML / KMZ (SAX; WGS84 ASSUMED — KML has no CRS to read — then
// range-checked like every other format)

internal object KmlBoundaryParser {

    fun parse(bytes: ByteArray): List<BoundaryGeometry> {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
        } catch (e: BoundaryImportError) {
            throw e
        } catch (e: Exception) {
            throw BoundaryImportError("Malformed KML: ${e.message ?: "parse failed"}")
        }
        if (handler.out.isEmpty()) {
            throw BoundaryImportError(
                "That KML holds no polygons, lines or points."
            )
        }
        return handler.out
    }

    private class Handler : DefaultHandler() {
        val out = ArrayList<BoundaryGeometry>()

        private val stack = ArrayList<String>()
        private var placemarkName: String? = null
        private var polygonRings = ArrayList<List<CoordinateConversions.LatLon>>()
        private var inInnerRing = false
        private var text = StringBuilder()
        private var capturing = false

        override fun startElement(uri: String?, local: String?, q: String?, a: Attributes?) {
            val name = tag(local, q)
            stack.add(name)
            when (name) {
                "Placemark" -> { placemarkName = null; polygonRings = ArrayList() }
                "Polygon" -> polygonRings = ArrayList()
                "innerBoundaryIs" -> inInnerRing = true
                "outerBoundaryIs" -> inInnerRing = false
                "coordinates", "name" -> { capturing = true; text = StringBuilder() }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if (capturing && ch != null) text.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, local: String?, q: String?) {
            val name = tag(local, q)
            when (name) {
                "name" -> {
                    // Only the Placemark's own <name>, not a Style's.
                    if (capturing && stack.getOrNull(stack.size - 2) == "Placemark") {
                        placemarkName = text.toString().trim().takeIf { it.isNotEmpty() }
                    }
                    capturing = false
                }

                "coordinates" -> {
                    capturing = false
                    val points = parseCoordinates(text.toString())
                    if (points.isNotEmpty()) {
                        val owner = stack.lastOrNull { it == "Polygon" || it == "LineString" || it == "Point" }
                        when (owner) {
                            "Polygon" ->
                                if (inInnerRing) polygonRings.add(closed(points))
                                else polygonRings.add(0, closed(points))
                            "LineString" ->
                                out.add(BoundaryGeometry(
                                    BoundaryGeometryKind.POLYLINE, listOf(points), placemarkName))
                            "Point" ->
                                out.add(BoundaryGeometry(
                                    BoundaryGeometryKind.POINT,
                                    listOf(listOf(points.first())), placemarkName))
                        }
                    }
                }

                "innerBoundaryIs", "outerBoundaryIs" -> inInnerRing = false

                "Polygon" -> {
                    if (polygonRings.isNotEmpty()) {
                        out.add(BoundaryGeometry(
                            BoundaryGeometryKind.POLYGON, polygonRings.toList(), placemarkName))
                    }
                    polygonRings = ArrayList()
                }

                "Placemark" -> {
                    // Late <name> (KML allows it after the geometry): back-fill
                    // the features this Placemark just produced.
                    placemarkName?.let { pm ->
                        for (i in out.indices.reversed()) {
                            if (out[i].name != null) break
                            out[i] = out[i].copy(name = pm)
                        }
                    }
                    placemarkName = null
                    polygonRings = ArrayList()
                }
            }
            if (stack.lastOrNull() == name) stack.removeAt(stack.size - 1)
        }

        private fun tag(local: String?, q: String?): String =
            local?.takeIf { it.isNotEmpty() } ?: (q ?: "").substringAfterLast(':')

        /// KML `coordinates`: whitespace-separated `lon,lat[,alt]` tuples.
        private fun parseCoordinates(raw: String): List<CoordinateConversions.LatLon> {
            val out = ArrayList<CoordinateConversions.LatLon>()
            for (tuple in raw.trim().split(Regex("\\s+"))) {
                if (tuple.isEmpty()) continue
                val parts = tuple.split(',')
                if (parts.size < 2) {
                    throw BoundaryImportError("Malformed KML: bad coordinate \"$tuple\".")
                }
                val lon = parts[0].trim().toDoubleOrNull()
                val lat = parts[1].trim().toDoubleOrNull()
                if (lon == null || lat == null) {
                    throw BoundaryImportError("Malformed KML: bad coordinate \"$tuple\".")
                }
                out.add(CoordinateConversions.LatLon(latitude = lat, longitude = lon))
            }
            return out
        }

        private fun closed(
            ring: List<CoordinateConversions.LatLon>,
        ): List<CoordinateConversions.LatLon> =
            if (ring.size >= 2 && ring.first() != ring.last()) ring + ring.first() else ring
    }
}

// MARK: - Normalised GeoJSON (what we persist)

internal object BoundaryGeoJSON {

    /// Compact FeatureCollection, sorted keys — same shape the app's
    /// GeoJSON exporter writes, so the stored file opens in any GIS.
    fun serialise(geometries: List<BoundaryGeometry>): String {
        val sb = StringBuilder()
        sb.append("{\"features\":[")
        geometries.forEachIndexed { i, g ->
            if (i > 0) sb.append(',')
            sb.append("{\"geometry\":{\"coordinates\":")
            when (g.kind) {
                BoundaryGeometryKind.POLYGON -> {
                    sb.append('[')
                    g.rings.forEachIndexed { ri, ring ->
                        if (ri > 0) sb.append(',')
                        appendRing(sb, ring)
                    }
                    sb.append("],\"type\":\"Polygon\"}")
                }
                BoundaryGeometryKind.POLYLINE -> {
                    appendRing(sb, g.rings.first())
                    sb.append(",\"type\":\"LineString\"}")
                }
                BoundaryGeometryKind.POINT -> {
                    appendPoint(sb, g.rings.first().first())
                    sb.append(",\"type\":\"Point\"}")
                }
            }
            sb.append(",\"properties\":{")
            g.name?.let { sb.append("\"name\":\"").append(escape(it)).append('"') }
            sb.append("},\"type\":\"Feature\"}")
        }
        sb.append("],\"type\":\"FeatureCollection\"}")
        return sb.toString()
    }

    /// Read back what `serialise` wrote (relaunch path).
    fun deserialise(text: String): List<BoundaryGeometry> = GeoJsonBoundaryParser.parse(text)

    private fun appendRing(sb: StringBuilder, ring: List<CoordinateConversions.LatLon>) {
        sb.append('[')
        ring.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            appendPoint(sb, p)
        }
        sb.append(']')
    }

    private fun appendPoint(sb: StringBuilder, p: CoordinateConversions.LatLon) {
        sb.append('[').append(number(p.longitude)).append(',').append(number(p.latitude)).append(']')
    }

    private fun number(v: Double): String =
        if (v == v.toLong().toDouble() && abs(v) < 1e15) v.toLong().toString() else v.toString()

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ")
}
