// Local-only analytics + structured logging — Android counterpart of iOS
// Common/ForestixLogger.swift (Phase 7).
//
// ## Privacy model: "Collect local crash log only (no server upload)"
//
// Every event is appended as one JSON line to a rotating JSONL file at
//   <filesDir>/logs/events.jsonl
// Nothing is ever sent to the network. The file is bounded to 10 MB: when a
// write would exceed it, we rotate to `events.prev.jsonl` and start fresh.
// Settings can share the current log and clear both files.
//
// ## What we log
// Only project / plot UUIDs and numeric stats. Never owner names, free-text
// notes, photo/scan paths, or precise coordinates.
//
// ## Event surface (locked)
// EXACTLY four events, matching the iOS sibling's kept set:
//   • plotOpened            — a plot is opened (created or resumed)
//   • backupCreated         — a full project export/backup artefact is written
//   • backupRestored        — a project backup is restored (no Android UI yet)
//   • crashRecoveryPrompted — the crash-recovery resume prompt is shown
//
// iOS's logger is a stateless static enum. The Android file writer needs an
// app Context, so `init(context)` is called once from AppEnvironment; after
// that the emit methods are Context-free, just like the iOS call sites. Writes
// run on a single background thread so the field UI never blocks on IO
// (mirroring the iOS serial `forestix.logger` queue).

package com.hcjeong.forestix.common

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

object ForestixLogger {

    private const val TAG = "Forestix"
    private const val ROTATION_THRESHOLD_BYTES = 10L * 1024 * 1024  // 10 MB

    @Volatile private var appContext: Context? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "forestix.logger").apply { isDaemon = true }
    }
    private val iso8601 = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /// Bind the application context once (from AppEnvironment). Idempotent.
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // MARK: - Public event surface (exactly four events)

    /// A plot was opened — created fresh or resumed from the crash-recovery
    /// prompt. iOS event "plot.opened".
    fun plotOpened(plotId: UUID, projectId: UUID) {
        log {
            put("event", "plot.opened")
            put("plotId", plotId.toString())
            put("projectId", projectId.toString())
        }
    }

    /// A full project backup/export artefact was written to disk. iOS event
    /// "backup.created".
    fun backupCreated(projectId: UUID, bytes: Long) {
        log {
            put("event", "backup.created")
            put("projectId", projectId.toString())
            put("bytes", bytes)
        }
    }

    /// A project backup was restored. iOS event "backup.restored" — logs the
    /// source file's last path component only, never a full user path.
    fun backupRestored(projectId: UUID, fromPath: String) {
        log {
            put("event", "backup.restored")
            put("projectId", projectId.toString())
            put("fromName", File(fromPath).name)
        }
    }

    /// The crash-recovery resume prompt was shown for a candidate open plot.
    /// iOS event "crash.recovery.prompted".
    fun crashRecoveryPrompted(projectId: UUID, plotId: UUID) {
        log {
            put("event", "crash.recovery.prompted")
            put("projectId", projectId.toString())
            put("plotId", plotId.toString())
        }
    }

    // MARK: - Log access (Settings → share / clear)

    /// Current events log file, or null before init(). May not exist yet if
    /// nothing has been logged.
    fun currentLogFile(): File? = logsDir()?.let { File(it, "events.jsonl") }

    /// Rotated (previous) events log file, or null before init().
    fun previousLogFile(): File? = logsDir()?.let { File(it, "events.prev.jsonl") }

    /// True when there is a non-empty events log to share/clear.
    fun hasEvents(): Boolean {
        val f = currentLogFile() ?: return false
        return f.exists() && f.length() > 0L
    }

    /// Copy the events log into the shared-export cache and return a shareable
    /// content Uri (same FileProvider flow as ResearchExport.write — the only
    /// provider-configured path is `cacheDir/Exports/`).
    fun exportUri(context: Context): Uri? {
        val src = currentLogFile() ?: return null
        if (!src.exists() || src.length() == 0L) return null
        val dir = File(context.cacheDir, "Exports").apply { mkdirs() }
        val dst = File(dir, "forestix_events.jsonl")
        src.copyTo(dst, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dst)
    }

    /// Clears both current and rotated logs.
    fun clear() {
        writeExecutor.execute {
            currentLogFile()?.delete()
            previousLogFile()?.delete()
        }
    }

    // MARK: - Private

    private fun logsDir(): File? =
        appContext?.let { File(it.filesDir, "logs").apply { mkdirs() } }

    private inline fun log(build: JSONObject.() -> Unit) {
        if (appContext == null) return
        val obj = JSONObject().apply {
            put("t", iso8601.format(Date()))
            build()
        }
        val line = obj.toString()
        writeExecutor.execute {
            try {
                val current = currentLogFile() ?: return@execute
                if (current.exists() && current.length() > ROTATION_THRESHOLD_BYTES) {
                    val prev = previousLogFile()
                    prev?.delete()
                    if (prev != null) current.renameTo(prev)
                }
                current.appendText(line + "\n", Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "event log write failed", e)
            }
        }
    }
}
