// AN OUTLINE BEING DRAGGED ON THE MAP — the editable shape behind both
// "Draw the boundary" (Map settings) and "Draw an area" (the home map's
// press-and-hold), before it becomes a stored boundary or the ring of a
// cruise area.
// 1:1 port of iOS Geo/BoundaryDraft.swift; every user-visible sentence and
// every persisted string in here is byte-identical to the sibling's.
//
// THE RULE THIS TYPE EXISTS TO KEEP: a coordinate a cruiser dragged on a
// satellite tile is not a coordinate anything measured. Nothing in here
// touches GPS, and the boundary it produces is stamped BoundaryOrigin.DRAWN
// so no downstream reader can mistake a fingertip sketch for surveyed
// geometry. The stamp is the boundary-level analogue of PositionSource
// `manual` on a plot centre: same idea, same reason.
//
// Everything is WGS84 lon/lat, exactly like an imported boundary, and every
// metric quantity (area, edge midpoints, the self-intersection test) is
// computed in a local ENU plane anchored on the draft's first vertex — the
// same equirectangular approximation SamplingGenerator lays plots out in,
// so the area shown while drawing and the area the plots are generated
// against come from the same maths.
//
// The vertex list is OPEN (first != last). The closing repeat is added once,
// in `closedRing`, so the ring the store persists is shaped exactly like the
// one the GeoJSON parser hands back for an import.

package com.hcjeong.forestix.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/// WHERE THE COORDINATES CAME FROM — a survey, or a fingertip.
///
/// An imported boundary was surveyed by someone with instruments and
/// arrived in a file; a drawn one was dragged across a satellite tile on a
/// phone. They are the same geometry type and they are not the same
/// evidence, so they are distinguishable forever: in the store's record
/// file (`origin=`), in the persisted GeoJSON (the `forestix:source`
/// member, which travels with the file into any GIS), and in the settings
/// sheet's current-state row.
///
/// `raw` is what is written to disk and shared with the iOS sibling — do
/// not rename these strings.
enum class BoundaryOrigin(val raw: String) {
    /// Read out of a file the cruiser supplied (SHP / KML / KMZ / GeoJSON).
    IMPORTED("imported"),

    /// Drawn on the map inside the app.
    DRAWN("drawn");

    companion object {
        /// A record written before drawing existed carries no origin, and
        /// every one of those came from a file — so an unknown or absent
        /// value means IMPORTED. Never a throw: a cruiser who updates the
        /// app mid-cruise must not lose the boundary they imported this
        /// morning.
        fun fromRaw(raw: String?): BoundaryOrigin =
            entries.firstOrNull { it.raw == raw } ?: IMPORTED
    }
}

/// Why a drawn outline cannot be saved. `message` is the sentence the
/// screen shows verbatim; there is no generic fallback, because a refusal
/// the cruiser cannot act on is the same as no refusal at all.
enum class BoundaryDraftError(val message: String) {
    /// Fewer than three corners — no interior exists at all.
    TOO_FEW_VERTICES("A boundary needs at least 3 corners."),

    /// Two edges cross, so "inside" is undefined: the in/out verdict and
    /// the area would both be arbitrary. Refused rather than stored.
    SELF_INTERSECTING("The outline crosses itself. Move a corner so no two edges cross."),

    /// All the corners are collinear (or coincident): a line, not a stand.
    ZERO_AREA("The outline has no area. Move a corner so it encloses ground."),
}

/// The in-progress outline: an ordered, open list of WGS84 corners.
///
/// Immutable — every edit returns a new draft. That is what makes the
/// screen's undo stack a list of drafts rather than a replay log, and it
/// removes any chance of a gesture mutating a shape another gesture is
/// mid-way through reading.
/// `circle` is set only while the draft is a CIRCLE, and then `vertices`
/// holds the densified ring `circle()` builds rather than corners anybody
/// dragged. Keeping the ring as the one truth is the whole design:
/// `closedRing`, `areaAcres`, `validationFailure` and everything downstream
/// of them — plot layout, the exporters, the statistics, both stored schemas
/// — see an ordinary polygon and learn nothing about circles.
data class BoundaryDraft(
    val vertices: List<CoordinateConversions.LatLon>,
    val circle: Circle? = null,
) {

    /// The centre and ground radius a circular draft is regenerated from.
    /// It rides alongside the ring so a saved circle can be re-OPENED as a
    /// circle; it is never what anything measures. iOS
    /// `BoundaryDraft.Circle`.
    data class Circle(
        val centre: CoordinateConversions.LatLon,
        val radiusMeters: Double,
    )

    /// The ring a store holds: wound counter-clockwise (RFC 7946 §3.1.6)
    /// and closed. Shared by `toGeometry` and by the area a cruise is laid
    /// out inside, so a shape saved by either door is shaped the same on
    /// disk. iOS `BoundaryDraft.closedRing`.
    val closedRing: List<CoordinateConversions.LatLon>
        get() {
            if (vertices.isEmpty()) return emptyList()
            val wound = if (signedAreaSquareMeters < 0) vertices.reversed() else vertices
            return wound + wound.first()
        }

    // MARK: - The circle

    /// Drag the centre handle: the whole circle travels, the radius does
    /// not change. Returns `this` for a polygon draft, which has no centre
    /// to move.
    fun movingCentre(to: CoordinateConversions.LatLon): BoundaryDraft {
        val c = circle ?: return this
        return circle(to, c.radiusMeters)
    }

    /// Drag the ring handle, or type into the radius field. Clamped to
    /// MIN_RADIUS_METERS rather than refused, because the thumb that
    /// overshoots inwards is still mid-gesture and a shape that vanishes
    /// under the finger cannot be dragged back out.
    fun withRadius(radiusMeters: Double): BoundaryDraft {
        val c = circle ?: return this
        return circle(c.centre, radiusMeters)
    }

    // MARK: - Editing

    /// Move one corner. An out-of-range index is ignored rather than
    /// thrown on: a drag can outlive the vertex it started on when an undo
    /// lands mid-gesture, and losing the shape to a crash is the outcome
    /// this whole screen exists to avoid.
    ///
    /// A CIRCLE has no corners to move — see `movingCentre` and
    /// `withRadius`. Guarded here as well as in the host so no future
    /// caller can leave the ring and the circle it claims to be describing
    /// saying different things: `copy()` would otherwise carry the stale
    /// `circle` forward onto a ring that no longer matches it.
    fun moving(index: Int, to: CoordinateConversions.LatLon): BoundaryDraft {
        if (circle != null) return this
        if (index !in vertices.indices) return this
        return copy(vertices = vertices.toMutableList().also { it[index] = to })
    }

    /// The midpoint of every edge, in edge order: `edgeMidpoints[i]` sits
    /// between `vertices[i]` and `vertices[i + 1]` (wrapping at the end).
    /// Computed in ENU so the handle sits where the eye expects it on the
    /// projected map, not where naive lon/lat averaging would put it.
    ///
    /// Empty for a circle. A circle's edges are not a cruiser's to split,
    /// and offering 128 handles that add a corner to a shape which cannot
    /// hold one is worse than offering none.
    val edgeMidpoints: List<CoordinateConversions.LatLon>
        get() {
            if (circle != null) return emptyList()
            if (vertices.size < 2) return emptyList()
            val origin = vertices.first()
            return vertices.indices.map { i ->
                val a = CoordinateConversions.toENU(vertices[i], origin)
                val b = CoordinateConversions.toENU(vertices[(i + 1) % vertices.size], origin)
                CoordinateConversions.toLatLon(
                    CoordinateConversions.ENU(
                        east = (a.east + b.east) / 2,
                        north = (a.north + b.north) / 2,
                    ),
                    origin,
                )
            }
        }

    /// Split edge `index` at `at`, returning the new draft and the new
    /// corner's index. This is what dragging an edge handle does: the
    /// handle is not a vertex until it moves, and then it becomes one.
    /// Index -1 back when the edge does not exist.
    fun insertingOnEdge(
        index: Int,
        at: CoordinateConversions.LatLon,
    ): Pair<BoundaryDraft, Int> {
        if (circle != null) return this to -1
        if (index !in vertices.indices) return this to -1
        val inserted = index + 1
        val next = vertices.toMutableList().also { it.add(inserted, at) }
        return copy(vertices = next) to inserted
    }

    /// Delete a corner (the long-press), or null when the deletion is
    /// refused. Refused at three corners — the deletion that would leave a
    /// line is the one a gloved thumb makes by accident, and it would
    /// silently destroy the shape.
    fun removing(index: Int): BoundaryDraft? {
        if (circle != null) return null
        if (index !in vertices.indices || vertices.size <= 3) return null
        return copy(vertices = vertices.toMutableList().also { it.removeAt(index) })
    }

    // MARK: - Area

    /// Signed shoelace area in m² on the ENU plane anchored at the first
    /// vertex. Positive = counter-clockwise = an RFC 7946 exterior ring.
    val signedAreaSquareMeters: Double
        get() {
            if (vertices.size < 3) return 0.0
            val origin = vertices.first()
            val plane = vertices.map { CoordinateConversions.toENU(it, origin) }
            var sum = 0.0
            for (i in plane.indices) {
                val a = plane[i]
                val b = plane[(i + 1) % plane.size]
                sum += a.east * b.north - b.east * a.north
            }
            return sum / 2
        }

    /// Enclosed ground in m², sign discarded — the number the readout is
    /// built from.
    val areaSquareMeters: Double get() = abs(signedAreaSquareMeters)

    /// Enclosed ground in acres. Acres are the app's canonical stored areal
    /// basis (see AreaUnit); hectares are a display conversion on top, so
    /// that conversion lives at the display and not here.
    val areaAcres: Double get() = areaSquareMeters / SQUARE_METERS_PER_ACRE

    // MARK: - Validity

    /// null when the draft may be saved, otherwise the reason it may not.
    /// Checked live while dragging so the cruiser sees the refusal at the
    /// moment they cause it, not at the moment they try to save.
    val validationFailure: BoundaryDraftError?
        get() = when {
            vertices.size < 3 -> BoundaryDraftError.TOO_FEW_VERTICES
            isSelfIntersecting -> BoundaryDraftError.SELF_INTERSECTING
            areaSquareMeters < MIN_AREA_SQUARE_METERS -> BoundaryDraftError.ZERO_AREA
            else -> null
        }

    val isValid: Boolean get() = validationFailure == null

    /// True when any two NON-ADJACENT edges of the closed ring touch or
    /// cross. A polygon that crosses itself has no defined interior, so
    /// SamplingGenerator's crossing-number test and the area above would
    /// both return something — just not something that means anything.
    ///
    /// Run on the ENU plane so the tolerance is in metres: EPSILON is a
    /// cross product of two metre vectors, i.e. an area, and 1e-6 m² is far
    /// below anything a fingertip can express and far above the rounding of
    /// a degree-to-metre conversion.
    ///
    /// SKIPPED ENTIRELY FOR A CIRCLE, and that is a requirement rather than
    /// an optimisation. A convex ring cannot cross itself, so the answer is
    /// known; the loop below is O(n²) and `validationFailure` is read on
    /// every frame of a drag, which at 128 vertices is some 8,000 segment
    /// tests per frame under the thumb.
    val isSelfIntersecting: Boolean
        get() {
            if (circle != null) return false
            if (vertices.size < 4) return false
            val origin = vertices.first()
            val p = vertices.map { CoordinateConversions.toENU(it, origin) }
            val n = p.size
            for (i in 0 until n) {
                val a1 = p[i]
                val a2 = p[(i + 1) % n]
                // Only j > i, and never the two edges that share a corner
                // with edge i (its successor, and its predecessor when i is
                // the first edge) — those touch by construction.
                for (j in (i + 1) until n) {
                    if (j == i + 1) continue
                    if (i == 0 && j == n - 1) continue
                    if (segmentsIntersect(a1, a2, p[j], p[(j + 1) % n])) return true
                }
            }
            return false
        }

    // MARK: - Becoming a stored boundary

    /// The polygon this draft stands for — the SAME BoundaryGeometry an
    /// import produces, so plot generation, the area readout, the in/out
    /// verdict and the exports all keep working untouched. Throws the
    /// refusal rather than returning a shape nothing downstream can use.
    ///
    /// The winding and closing normalisations are `closedRing`'s, so the
    /// ring a boundary is persisted with and the ring an area is persisted
    /// with come out of one place.
    fun toGeometry(displayName: String): BoundaryGeometry {
        validationFailure?.let { throw BoundaryImportError(it.message) }
        val ring = closedRing
        val name = displayName.trim()
        return BoundaryGeometry(
            kind = BoundaryGeometryKind.POLYGON,
            rings = listOf(ring),
            name = name.ifEmpty { null },
        )
    }

    companion object {

        /// `sourceFormat` for a boundary drawn in the app. The field
        /// otherwise names a FILE format ("shp", "kml", …); a drawn
        /// boundary has no file, and saying so is more use than leaving
        /// the slot blank. Byte-identical to iOS
        /// `SurveyBoundary.drawnSourceFormat`.
        const val DRAWN_SOURCE_FORMAT: String = "Drawn"

        /// The default name a drawn boundary is saved under.
        const val DEFAULT_NAME: String = "Drawn boundary"

        /// 1 acre = 43560 ft² = 4046.8564224 m² (exact). Mirrors
        /// GeoJSONImporter.metersSquaredPerAcre on iOS.
        const val SQUARE_METERS_PER_ACRE: Double = 4046.8564224

        /// Smallest area we will call an area at all. A hand-drag can leave
        /// three points a few centimetres apart, which is arithmetically
        /// non-zero and forestrically meaningless; 1 m² is far below any
        /// real stand and far above float noise at cruise latitudes.
        const val MIN_AREA_SQUARE_METERS: Double = 1.0

        /// How many segments a circle is drawn with. 128 is fine enough
        /// that the ring reads as a curve at any zoom a cruiser frames a
        /// stand at, and small enough that the stored geometry stays a few
        /// kilobytes.
        ///
        /// It is also part of the PERSISTED shape, so it is a constant and
        /// not a tuning knob: iOS uses the same 128 so the two platforms
        /// write the same bytes for the same circle.
        const val CIRCLE_SEGMENTS: Int = 128

        /// Below this a circle is not a stand. MIN_AREA_SQUARE_METERS would
        /// refuse far smaller (π·2² is 12.6 m²), but the radius is what the
        /// cruiser is holding, so the clamp is stated in the radius and the
        /// area refusal stays unreachable for a circle — one mistake must
        /// not be able to produce two different sentences.
        const val MIN_RADIUS_METERS: Double = 2.0

        private const val EPSILON: Double = 1e-6

        /// Re-open a ring that was stored closed (first == last). The one
        /// door back in, and it exists because an AREA is edited again and
        /// again after it is first drawn.
        fun fromClosedRing(ring: List<CoordinateConversions.LatLon>): BoundaryDraft {
            val open = if (ring.size >= 2 && ring.first() == ring.last()) {
                ring.dropLast(1)
            } else {
                ring
            }
            return BoundaryDraft(open)
        }

        /// The shape a draw gesture starts from: an axis-aligned rectangle
        /// spanning two opposite corners, wound counter-clockwise so it
        /// already obeys RFC 7946 §3.1.6 before the cruiser touches it.
        ///
        /// The caller passes two corners derived from the VISIBLE viewport
        /// rather than a fixed ground size: a fixed 200 m box is invisible
        /// at zoom 12 and larger than the screen at zoom 20, and a cruiser
        /// who cannot see the rectangle they were just given assumes
        /// nothing happened.
        fun rectangle(
            corner: CoordinateConversions.LatLon,
            opposite: CoordinateConversions.LatLon,
        ): BoundaryDraft {
            val minLat = min(corner.latitude, opposite.latitude)
            val maxLat = max(corner.latitude, opposite.latitude)
            val minLon = min(corner.longitude, opposite.longitude)
            val maxLon = max(corner.longitude, opposite.longitude)
            // SW → SE → NE → NW is counter-clockwise on an east/north plane.
            return BoundaryDraft(
                listOf(
                    CoordinateConversions.LatLon(minLat, minLon),
                    CoordinateConversions.LatLon(minLat, maxLon),
                    CoordinateConversions.LatLon(maxLat, maxLon),
                    CoordinateConversions.LatLon(maxLat, minLon),
                )
            )
        }

        /// A circle, as the ring it will be stored as.
        ///
        /// THE RADIUS IS INFLATED BEFORE THE RING IS BUILT. An inscribed
        /// n-gon under-measures its circle by (2π/n)²/6 — about 0.04 % at
        /// n = 128 — so a ring built at `radiusMeters` would enclose
        /// slightly less ground than the πr² the cruiser was shown, and the
        /// app would then hold two different areas for one circle. Building
        /// at r' = r·√((2π/n)/sin(2π/n)) makes the ring's own shoelace area
        /// equal πr² exactly, which matters because that shoelace is what
        /// `Stratum.areaAcres` stores and StandStatistics expands the whole
        /// cruise by.
        ///
        /// Wound counter-clockwise (angle increasing on an east/north
        /// plane), so it obeys RFC 7946 §3.1.6 before anything touches it —
        /// the same promise `rectangle` makes.
        ///
        /// The ENU plane is anchored on the CENTRE, which puts vertex 0 at
        /// the centre's own latitude; `signedAreaSquareMeters` re-anchors on
        /// vertex 0 and therefore reads back the identical plane, so the
        /// area above is exact and not merely close.
        fun circle(
            centre: CoordinateConversions.LatLon,
            radiusMeters: Double,
        ): BoundaryDraft {
            val r = max(radiusMeters, MIN_RADIUS_METERS)
            return BoundaryDraft(densifiedRing(centre, r), Circle(centre, r))
        }

        private fun densifiedRing(
            centre: CoordinateConversions.LatLon,
            r: Double,
        ): List<CoordinateConversions.LatLon> {
            val step = 2 * PI / CIRCLE_SEGMENTS
            val ringRadius = r * sqrt(step / sin(step))
            return (0 until CIRCLE_SEGMENTS).map { k ->
                val theta = step * k
                CoordinateConversions.toLatLon(
                    CoordinateConversions.ENU(
                        east = ringRadius * cos(theta),
                        north = ringRadius * sin(theta),
                    ),
                    centre,
                )
            }
        }

        /// Segment intersection including touching — a corner sitting
        /// exactly on another edge is as undefined an interior as a clean
        /// crossing.
        private fun segmentsIntersect(
            p1: CoordinateConversions.ENU,
            p2: CoordinateConversions.ENU,
            p3: CoordinateConversions.ENU,
            p4: CoordinateConversions.ENU,
        ): Boolean {
            val d1 = cross(p3, p4, p1)
            val d2 = cross(p3, p4, p2)
            val d3 = cross(p1, p2, p3)
            val d4 = cross(p1, p2, p4)
            if (((d1 > EPSILON && d2 < -EPSILON) || (d1 < -EPSILON && d2 > EPSILON)) &&
                ((d3 > EPSILON && d4 < -EPSILON) || (d3 < -EPSILON && d4 > EPSILON))
            ) {
                return true
            }
            if (abs(d1) <= EPSILON && onSegment(p3, p4, p1)) return true
            if (abs(d2) <= EPSILON && onSegment(p3, p4, p2)) return true
            if (abs(d3) <= EPSILON && onSegment(p1, p2, p3)) return true
            if (abs(d4) <= EPSILON && onSegment(p1, p2, p4)) return true
            return false
        }

        /// z of (b − a) × (c − a) — positive when a→b→c turns left.
        private fun cross(
            a: CoordinateConversions.ENU,
            b: CoordinateConversions.ENU,
            c: CoordinateConversions.ENU,
        ): Double =
            (b.east - a.east) * (c.north - a.north) - (b.north - a.north) * (c.east - a.east)

        /// Whether collinear point `c` lies within segment a→b's bounding box.
        private fun onSegment(
            a: CoordinateConversions.ENU,
            b: CoordinateConversions.ENU,
            c: CoordinateConversions.ENU,
        ): Boolean =
            c.east >= min(a.east, b.east) - EPSILON &&
                c.east <= max(a.east, b.east) + EPSILON &&
                c.north >= min(a.north, b.north) - EPSILON &&
                c.north <= max(a.north, b.north) + EPSILON
    }
}
