// Phase 7 (Android) — project backup / restore as a single `.tcproj` file.
// Kotlin sibling of iOS Persistence/BackupArchive.swift.
//
// ## Why a custom zip
// Forestix ships a Room SQLite cruise store, cover photos, and raw scan
// blobs. A field pilot is a terrible place to learn you can't recover a
// corrupted database, so we give the cruiser a one-button backup (shared via
// the Android share sheet to Drive / Files / email) and a one-tap restore.
//
// The archive is a STORED (uncompressed) PKZIP written by the shared
// export/ZipWriter — the same writer the Shapefile bundles use, so the
// on-disk container is structurally identical to the one iOS emits.
//
// ## On-disk layout inside the `.tcproj`
//
//   manifest.json                  # {schemaVersion, projectId:"all", exportedAt, appVersion}
//   db/timber-cruising.db          # WAL-checkpointed single-file Room DB (all projects)
//   photos/<tree-uuid>.<ext>       # every Tree.photoPath that exists on disk
//   scans/<tree-uuid>.<ext>        # every Tree.rawScanPath that exists on disk
//   covers/<plot-uuid>.<ext>       # every Plot.coverPhotoPath that exists
//   panoramas/<plot-uuid>.<ext>    # every Plot.panoramaPath that exists
//
// Unlike iOS (which exports one project per file), the Android cruise store
// is a single Room database holding every project, so one archive captures
// them all and `manifest.projectId` is the sentinel "all".
//
// ## Restore behaviour — LIVE DB IS NEVER SWAPPED
// The app's `AppEnvironment.cruiseDatabase` is a process singleton whose DAOs
// are held by every open screen. Closing and file-swapping it underneath a
// live Compose tree would leave those screens holding a closed handle, so we
// take the safe route iOS also takes: the archive's DB is opened as a SECOND,
// throwaway Room instance and its rows are copied INTO the live store through
// the ordinary repositories. The live DB is only ever appended to, never
// replaced, so a restore can't corrupt field-collected data.
//
// Conflict on restore: if a backed-up project id already exists in the live
// store we mint a FRESH UUID for the imported copy and rewrite every
// descendant foreign key, so the two sit side-by-side rather than one
// clobbering the other (matching iOS — accidentally overwriting field data is
// the worst possible outcome; a duplicate is merely annoying).
//
// ## Cross-platform scope
// A `.tcproj` produced by iOS is NOT restorable here and vice-versa: the
// Core Data and Room schemas differ at the SQLite level. This archive is
// Android-restorable ONLY for now. Restoring a foreign archive will surface a
// schema/open error rather than corrupting anything.

package com.hcjeong.forestix.backup

import android.content.Context
import androidx.room.Room
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.CruiseDatabase
import com.hcjeong.forestix.data.cruise.RoomCruiseDesignRepository
import com.hcjeong.forestix.data.cruise.RoomHeightDiameterFitRepository
import com.hcjeong.forestix.data.cruise.RoomPlannedPlotRepository
import com.hcjeong.forestix.data.cruise.RoomPlotRepository
import com.hcjeong.forestix.data.cruise.RoomProjectRepository
import com.hcjeong.forestix.data.cruise.RoomSpeciesConfigRepository
import com.hcjeong.forestix.data.cruise.RoomStratumRepository
import com.hcjeong.forestix.data.cruise.RoomTreeRepository
import com.hcjeong.forestix.data.cruise.RoomVolumeEquationRepository
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.export.ZipWriter
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/// Backup / restore failure surface. Every case carries a user-facing
/// message; callers show it in an alert and never crash.
sealed class BackupError(message: String) : Exception(message) {
    class NoProjects :
        BackupError("No projects to back up. Create a project first, then try again.")
    class MissingDatabase :
        BackupError("Backup archive has no cruise database file.")
    class ArchiveCorrupt(detail: String) :
        BackupError("Backup archive is corrupt or unreadable: $detail")
    class ManifestUnsupported(got: Int, want: Int) :
        BackupError("Backup schema version $got is not supported (this build expects $want).")
    class IoFailed(detail: String) :
        BackupError("Filesystem I/O failed: $detail")
}

/// Mirror of iOS BackupManifest. `projectId` is a UUID string or the sentinel
/// "all" (Android bundles every project into one archive).
data class BackupManifest(
    val schemaVersion: Int,
    val projectId: String,
    val exportedAt: String,   // ISO-8601, UTC
    val appVersion: String,
)

data class BackupResult(
    val file: File,
    val byteSize: Long,
    val manifest: BackupManifest,
    val projectIds: List<UUID>,
)

data class RestoreResult(
    val importedProjectIds: List<UUID>,
    val plotCount: Int,
    val treeCount: Int,
    val sourceName: String,
)

object BackupArchive {

    const val SCHEMA_VERSION = 1
    const val FILE_EXTENSION = "tcproj"

    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DB_ENTRY = "db/timber-cruising.db"

    /// Directory (under filesDir) where restored photos/scans are re-materialised.
    /// Absolute paths into it are stored back on the Tree/Plot rows; the media
    /// loaders read those paths directly, so any stable app-private dir works.
    private fun restoredMediaDir(context: Context): File =
        File(context.filesDir, "restored-media").apply { mkdirs() }

    private fun iso8601(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    // MARK: - Export

    /// Build a `.tcproj` capturing every project + its media, write it into
    /// `cacheDir/Exports/` (the only FileProvider-exposed path — so the caller
    /// can hand the file to the share sheet), and return its location.
    suspend fun export(
        context: Context,
        env: AppEnvironment,
        appVersion: String,
        now: Long = System.currentTimeMillis(),
    ): BackupResult {
        val projects = env.projectRepository.list()
        if (projects.isEmpty()) throw BackupError.NoProjects()

        // 1. Fold the WAL into the main DB file so a plain byte copy is complete.
        try {
            env.cruiseDatabase.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } catch (_: Exception) {
            // Best-effort: an un-checkpointed WAL only risks a slightly stale
            // snapshot, never a broken one. Continue with the copy.
        }

        // 2. Snapshot the single Room DB file.
        val dbFile = context.getDatabasePath(CruiseDatabase.NAME)
        val dbBytes = try {
            dbFile.readBytes()
        } catch (e: Exception) {
            throw BackupError.IoFailed("could not read the cruise database: ${e.message}")
        }

        // 3. Assemble entries (order preserved by ZipWriter).
        val manifest = BackupManifest(
            schemaVersion = SCHEMA_VERSION,
            projectId = "all",
            exportedAt = iso8601().format(Date(now)),
            appVersion = appVersion)

        val files = mutableListOf<Pair<String, ByteArray>>()
        files.add(MANIFEST_ENTRY to manifestJson(manifest).toByteArray(Charsets.UTF_8))
        files.add(DB_ENTRY to dbBytes)

        val projectIds = mutableListOf<UUID>()
        for (p in projects) {
            projectIds.add(p.id)
            for (plot in env.plotRepository.listByProject(p.id)) {
                plot.coverPhotoPath?.let { addMediaFile(files, "covers", plot.id, it) }
                plot.panoramaPath?.let { addMediaFile(files, "panoramas", plot.id, it) }
                // includeDeleted: a soft-deleted tree's photo is still field
                // evidence worth preserving, matching iOS's includeDeleted:true.
                for (tree in env.treeRepository.listByPlot(plot.id, includeDeleted = true)) {
                    tree.photoPath?.let { addMediaFile(files, "photos", tree.id, it) }
                    tree.rawScanPath?.let { addMediaFile(files, "scans", tree.id, it) }
                }
            }
        }

        val archive = ZipWriter.storedArchive(files)

        // 4. Write into the share-exposed cache dir.
        val dir = File(context.cacheDir, "Exports").apply { mkdirs() }
        val stamp = iso8601().format(Date(now)).replace(":", "-")
        val out = File(dir, "Forestix_$stamp.$FILE_EXTENSION")
        try {
            out.writeBytes(archive)
        } catch (e: Exception) {
            throw BackupError.IoFailed("could not write the backup file: ${e.message}")
        }

        return BackupResult(
            file = out,
            byteSize = archive.size.toLong(),
            manifest = manifest,
            projectIds = projectIds)
    }

    private fun addMediaFile(
        files: MutableList<Pair<String, ByteArray>>,
        subdir: String,
        ownerId: UUID,
        path: String,
    ) {
        val f = File(path)
        if (!f.exists() || !f.isFile) return
        val bytes = try { f.readBytes() } catch (_: Exception) { return }
        val ext = f.extension.ifEmpty { "bin" }
        files.add("$subdir/$ownerId.$ext" to bytes)
    }

    // MARK: - Restore

    /// Parse a `.tcproj`'s bytes and merge its projects (and descendants) into
    /// the live store WITHOUT ever touching the live DB file. See the file
    /// header for why a merge, not a swap, is the safe pattern here.
    suspend fun restore(
        context: Context,
        env: AppEnvironment,
        archiveBytes: ByteArray,
        sourceName: String,
    ): RestoreResult {
        val entries = try {
            ZipReader.readStoredEntries(archiveBytes)
        } catch (e: Exception) {
            throw BackupError.ArchiveCorrupt(e.message ?: e.toString())
        }

        val manifestBytes = entries[MANIFEST_ENTRY]
            ?: throw BackupError.ArchiveCorrupt("missing manifest.json")
        val manifest = parseManifest(manifestBytes)
        if (manifest.schemaVersion != SCHEMA_VERSION) {
            throw BackupError.ManifestUnsupported(manifest.schemaVersion, SCHEMA_VERSION)
        }

        val dbBytes = entries[DB_ENTRY] ?: throw BackupError.MissingDatabase()

        // Write the archived DB into the app's databases dir under a throwaway
        // name and open it as a SECOND Room instance. Room only mounts a store
        // located at getDatabasePath(name), hence the copy.
        val tempName = "restore-${UUID.randomUUID()}.db"
        val tempFile = context.getDatabasePath(tempName)
        tempFile.parentFile?.mkdirs()
        try {
            tempFile.writeBytes(dbBytes)
        } catch (e: Exception) {
            throw BackupError.IoFailed("could not stage the archive database: ${e.message}")
        }
        // Any stale sidecars from a crashed prior restore would confuse the open.
        File(tempFile.path + "-wal").delete()
        File(tempFile.path + "-shm").delete()

        val srcDb = try {
            Room.databaseBuilder(context.applicationContext, CruiseDatabase::class.java, tempName)
                .addMigrations(CruiseDatabase.MIGRATION_1_2, CruiseDatabase.MIGRATION_2_3)
                .build()
        } catch (e: Exception) {
            tempFile.delete()
            throw BackupError.ArchiveCorrupt("could not open the archived database: ${e.message}")
        }

        try {
            return copyInto(env, srcDb, entries, restoredMediaDir(context), sourceName)
        } catch (e: BackupError) {
            throw e
        } catch (e: Exception) {
            // A schema mismatch (e.g. an iOS archive) surfaces here as a Room
            // open/validation failure — reported cleanly, never a crash.
            throw BackupError.ArchiveCorrupt(e.message ?: e.toString())
        } finally {
            try { srcDb.close() } catch (_: Exception) {}
            tempFile.delete()
            File(tempFile.path + "-wal").delete()
            File(tempFile.path + "-shm").delete()
        }
    }

    /// Copy every domain object out of the throwaway [srcDb] into the live
    /// store via [env]'s repositories, re-homing media and rewriting ids on
    /// collision. Insert order follows the FK dependency chain.
    private suspend fun copyInto(
        env: AppEnvironment,
        srcDb: CruiseDatabase,
        entries: Map<String, ByteArray>,
        mediaDir: File,
        sourceName: String,
    ): RestoreResult {
        val srcProjectRepo = RoomProjectRepository(srcDb.projectDao())
        val srcStratumRepo = RoomStratumRepository(srcDb.stratumDao())
        val srcDesignRepo = RoomCruiseDesignRepository(srcDb.cruiseDesignDao())
        val srcPlannedRepo = RoomPlannedPlotRepository(srcDb.plannedPlotDao())
        val srcPlotRepo = RoomPlotRepository(srcDb.plotDao())
        val srcTreeRepo = RoomTreeRepository(srcDb.treeDao())
        val srcSpeciesRepo = RoomSpeciesConfigRepository(srcDb.speciesConfigDao())
        val srcVolRepo = RoomVolumeEquationRepository(srcDb.volumeEquationDao())
        val srcHDFitRepo = RoomHeightDiameterFitRepository(srcDb.heightDiameterFitDao())

        val liveProjectIds = env.projectRepository.list().map { it.id }.toSet()

        val imported = mutableListOf<UUID>()
        var plotCount = 0
        var treeCount = 0

        for (srcProject in srcProjectRepo.list()) {
            val collides = liveProjectIds.contains(srcProject.id)
            val rewrite = collides
            val finalProjectId = if (collides) UUID.randomUUID() else srcProject.id

            env.projectRepository.create(srcProject.copy(id = finalProjectId))
            imported.add(finalProjectId)

            // Strata.
            val stratumIdMap = HashMap<UUID, UUID>()
            for (s in srcStratumRepo.listByProject(srcProject.id)) {
                val newId = if (rewrite) UUID.randomUUID() else s.id
                stratumIdMap[s.id] = newId
                env.stratumRepository.create(s.copy(id = newId, projectId = finalProjectId))
            }

            // Cruise designs.
            for (d in srcDesignRepo.forProject(srcProject.id)) {
                val newId = if (rewrite) UUID.randomUUID() else d.id
                env.cruiseDesignRepository.create(d.copy(id = newId, projectId = finalProjectId))
            }

            // Planned plots (remap stratum FK).
            val plannedIdMap = HashMap<UUID, UUID>()
            for (pp in srcPlannedRepo.listByProject(srcProject.id)) {
                val newId = if (rewrite) UUID.randomUUID() else pp.id
                plannedIdMap[pp.id] = newId
                env.plannedPlotRepository.create(pp.copy(
                    id = newId,
                    projectId = finalProjectId,
                    stratumId = pp.stratumId?.let { stratumIdMap[it] ?: it }))
            }

            // Plots + trees + media.
            for (plot in srcPlotRepo.listByProject(srcProject.id)) {
                val newPlotId = if (rewrite) UUID.randomUUID() else plot.id
                val cover = restoreMedia(entries, "covers", plot.id, newPlotId, mediaDir)
                    ?: plot.coverPhotoPath
                val panorama = restoreMedia(entries, "panoramas", plot.id, newPlotId, mediaDir)
                    ?: plot.panoramaPath
                env.plotRepository.create(plot.copy(
                    id = newPlotId,
                    projectId = finalProjectId,
                    plannedPlotId = plot.plannedPlotId?.let { plannedIdMap[it] ?: it },
                    coverPhotoPath = cover,
                    panoramaPath = panorama))
                plotCount++

                for (tree in srcTreeRepo.listByPlot(plot.id, includeDeleted = true)) {
                    val newTreeId = if (rewrite) UUID.randomUUID() else tree.id
                    val photo = restoreMedia(entries, "photos", tree.id, newTreeId, mediaDir)
                        ?: tree.photoPath
                    val scan = restoreMedia(entries, "scans", tree.id, newTreeId, mediaDir)
                        ?: tree.rawScanPath
                    env.treeRepository.create(rebuildTree(
                        tree, newTreeId, newPlotId, photo, scan))
                    treeCount++
                }
            }

            // H–D fits (fresh ids — they are per (project, species), no cross-refs).
            for (fit in srcHDFitRepo.listByProject(srcProject.id)) {
                env.heightDiameterFitRepository.create(
                    fit.copy(id = UUID.randomUUID(), projectId = finalProjectId))
            }
        }

        // Species + volume equations are GLOBAL, keyed by code / id — insert
        // only those the live store lacks, so a restore never overwrites a
        // user's edited species config.
        val liveSpecies = env.speciesConfigRepository.list().map { it.code }.toSet()
        for (sp in srcSpeciesRepo.list()) {
            if (!liveSpecies.contains(sp.code)) env.speciesConfigRepository.create(sp)
        }
        val liveVol = env.volumeEquationRepository.list().map { it.id }.toSet()
        for (v in srcVolRepo.list()) {
            if (!liveVol.contains(v.id)) env.volumeEquationRepository.create(v)
        }

        return RestoreResult(
            importedProjectIds = imported,
            plotCount = plotCount,
            treeCount = treeCount,
            sourceName = sourceName)
    }

    /// Tree.id / plotId are val constructor params; data-class copy still
    /// overrides them. parentTreeId is left as-is (matching iOS rebuildTree):
    /// it is an unenforced nullable field, so a stale reference is harmless.
    private fun rebuildTree(
        t: Tree,
        newId: UUID,
        newPlotId: UUID,
        photoPath: String?,
        rawScanPath: String?,
    ): Tree = t.copy(
        id = newId,
        plotId = newPlotId,
        photoPath = photoPath,
        rawScanPath = rawScanPath)

    /// Write a media entry (if present) into [mediaDir] under the new owner id
    /// and return its absolute path; null when the archive carried no such file
    /// (the caller then keeps the original — possibly stale — path).
    private fun restoreMedia(
        entries: Map<String, ByteArray>,
        subdir: String,
        originalId: UUID,
        newId: UUID,
        mediaDir: File,
    ): String? {
        val prefix = "$subdir/$originalId."
        val match = entries.entries.firstOrNull { it.key.startsWith(prefix) } ?: return null
        val ext = match.key.substringAfterLast('.', "bin")
        val dst = File(mediaDir, "$newId.$ext")
        return try {
            dst.parentFile?.mkdirs()
            dst.writeBytes(match.value)
            dst.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Manifest JSON

    private fun manifestJson(m: BackupManifest): String =
        JSONObject().apply {
            put("schemaVersion", m.schemaVersion)
            put("projectId", m.projectId)
            put("exportedAt", m.exportedAt)
            put("appVersion", m.appVersion)
        }.toString()

    private fun parseManifest(bytes: ByteArray): BackupManifest =
        try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            BackupManifest(
                schemaVersion = o.getInt("schemaVersion"),
                projectId = o.optString("projectId", "all"),
                exportedAt = o.optString("exportedAt", ""),
                appVersion = o.optString("appVersion", ""))
        } catch (e: Exception) {
            throw BackupError.ArchiveCorrupt("manifest decode: ${e.message}")
        }
}

// MARK: - Stored-PKZIP reader
//
// Read side of export/ZipWriter — stored (method 0) entries only, which is
// all `.tcproj` ever contains. Mirrors the iOS Persistence-local ZipReader so
// the two platforms parse each other's container structure identically (the
// row *contents* still differ, hence Android-only restore).

private object ZipReader {

    fun readStoredEntries(data: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var i = 0
        while (i + 30 <= data.size) {
            val sig = readLE32(data, i)
            if (sig != 0x04034b50L) break            // reached the central directory
            val method = readLE16(data, i + 8)
            val compSize = readLE32(data, i + 18).toInt()
            val nameLen = readLE16(data, i + 26)
            val extraLen = readLE16(data, i + 28)
            val nameStart = i + 30
            val dataStart = nameStart + nameLen + extraLen
            if (method != 0) throw IllegalStateException("only stored ZIP entries are supported")
            if (dataStart + compSize > data.size) throw IllegalStateException("truncated ZIP entry")
            val name = String(data, nameStart, nameLen, Charsets.UTF_8)
            out[name] = data.copyOfRange(dataStart, dataStart + compSize)
            i = dataStart + compSize
        }
        return out
    }

    private fun readLE32(d: ByteArray, i: Int): Long =
        (d[i].toLong() and 0xFF) or
            ((d[i + 1].toLong() and 0xFF) shl 8) or
            ((d[i + 2].toLong() and 0xFF) shl 16) or
            ((d[i + 3].toLong() and 0xFF) shl 24)

    private fun readLE16(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)
}
