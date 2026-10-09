// Persistence for the imported survey boundary (Map settings → Survey
// boundary). ONE boundary at a time: importing another replaces it,
// Remove clears it.
//
// On disk, under filesDir/survey-boundary/:
//   * boundary.geojson — the NORMALISED WGS84 FeatureCollection that
//     BoundaryImporter produced (so the stored file opens in any GIS, and
//     the map re-reads it after relaunch rather than re-parsing a SHP).
//   * boundary.record  — display name, feature count, import date, source
//     format, origin; a flat key=value file so a partial write can never
//     look like a valid record.
//
// The store is a process singleton exposing a StateFlow, so the map and
// the settings sheet always agree about what is loaded.

package com.hcjeong.forestix.geo

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// What the sheet shows and the map draws.
data class StoredBoundary(
    val displayName: String,
    val featureCount: Int,
    /// When this boundary entered the app — the import for a file, the save
    /// for a drawn one.
    val importedAtMillis: Long,
    val sourceFormat: String,
    val geometries: List<BoundaryGeometry>,
    /// Survey or sketch — see BoundaryOrigin. Kept in the record as well as
    /// in the GeoJSON so the sheet's row can say which without
    /// deserialising the geometry.
    val origin: BoundaryOrigin = BoundaryOrigin.IMPORTED,
)

object SurveyBoundaryStore {

    private const val TAG = "SurveyBoundary"
    private const val DIR = "survey-boundary"
    private const val GEOJSON = "boundary.geojson"
    private const val RECORD = "boundary.record"

    private val _state = MutableStateFlow<StoredBoundary?>(null)
    val state: StateFlow<StoredBoundary?> = _state.asStateFlow()

    @Volatile private var loaded = false

    /// Read whatever survived the last run. Cheap and idempotent — the map
    /// calls it on first composition.
    fun loadIfNeeded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            _state.value = read(context)
        }
    }

    /// Replace the stored boundary with a freshly imported one. Returns the
    /// record now in effect; throws BoundaryImportError if it cannot be
    /// written (never silently keeps the old one).
    fun save(context: Context, boundary: ImportedBoundary, sourceFormat: String): StoredBoundary =
        write(
            context = context,
            displayName = boundary.displayName,
            geometries = boundary.geometries,
            geoJSON = boundary.geoJSON,
            sourceFormat = sourceFormat,
            origin = BoundaryOrigin.IMPORTED,
        )

    /// Replace the stored boundary with one the cruiser DREW. A separate
    /// entry point from `save` on purpose: there is no file, no CRS to
    /// confirm (the map handed the coordinates over already in WGS84) — and
    /// above all a different PROVENANCE, which is stamped here and travels
    /// with the geometry from now on.
    ///
    /// The CALLER is responsible for having asked first when something was
    /// already loaded (see MapSettingsSheet's replace confirmation); this
    /// method carries out a decision, it does not second-guess one.
    fun saveDrawn(
        context: Context,
        geometry: BoundaryGeometry,
        displayName: String,
    ): StoredBoundary =
        write(
            context = context,
            displayName = displayName,
            geometries = listOf(geometry),
            geoJSON = BoundaryGeoJSON.serialise(listOf(geometry), BoundaryOrigin.DRAWN),
            sourceFormat = BoundaryDraft.DRAWN_SOURCE_FORMAT,
            origin = BoundaryOrigin.DRAWN,
        )

    /// The one write path. Both entry points land here so a drawn boundary
    /// and an imported one can never drift apart in what gets persisted.
    private fun write(
        context: Context,
        displayName: String,
        geometries: List<BoundaryGeometry>,
        geoJSON: String,
        sourceFormat: String,
        origin: BoundaryOrigin,
    ): StoredBoundary {
        val dir = dir(context)
        try {
            dir.mkdirs()
            File(dir, GEOJSON).writeText(geoJSON)
            val importedAt = System.currentTimeMillis()
            File(dir, RECORD).writeText(
                buildString {
                    append("name=").append(displayName.replace('\n', ' ')).append('\n')
                    append("features=").append(geometries.size).append('\n')
                    append("importedAt=").append(importedAt).append('\n')
                    append("format=").append(sourceFormat).append('\n')
                    append("origin=").append(origin.raw).append('\n')
                }
            )
            val stored = StoredBoundary(
                displayName = displayName,
                featureCount = geometries.size,
                importedAtMillis = importedAt,
                sourceFormat = sourceFormat,
                geometries = geometries,
                origin = origin,
            )
            loaded = true
            _state.value = stored
            return stored
        } catch (e: Exception) {
            Log.w(TAG, "Boundary save failed", e)
            throw BoundaryImportError(
                "The boundary could not be saved (${e.message ?: "write failed"})."
            )
        }
    }

    /// Remove — drops both files and the in-memory state.
    fun clear(context: Context) {
        val dir = dir(context)
        File(dir, GEOJSON).delete()
        File(dir, RECORD).delete()
        dir.delete()
        loaded = true
        _state.value = null
    }

    // MARK: - Internal

    private fun dir(context: Context) = File(context.filesDir, DIR)

    private fun read(context: Context): StoredBoundary? {
        val dir = dir(context)
        val geojson = File(dir, GEOJSON)
        val record = File(dir, RECORD)
        if (!geojson.isFile || !record.isFile) return null
        return try {
            val fields = record.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
            val geometries = BoundaryGeoJSON.deserialise(geojson.readText())
            // A stored file still has to pass the WGS84 range gate — the
            // rule is about what may be DRAWN, not about when it arrived.
            BoundaryCRS.requireInRange(geometries)
            StoredBoundary(
                displayName = fields["name"]?.takeIf { it.isNotBlank() } ?: "Boundary",
                featureCount = fields["features"]?.toIntOrNull() ?: geometries.size,
                importedAtMillis = fields["importedAt"]?.toLongOrNull() ?: geojson.lastModified(),
                sourceFormat = fields["format"] ?: "",
                geometries = geometries,
                // A record written before drawing existed carries no
                // origin line, and every one of those came from a file.
                origin = BoundaryOrigin.fromRaw(fields["origin"]),
            )
        } catch (e: Exception) {
            // Unreadable = not drawn. Leave the files in place so the user
            // can re-import over them; never render a half-parsed boundary.
            Log.w(TAG, "Stored boundary unreadable", e)
            null
        }
    }
}
