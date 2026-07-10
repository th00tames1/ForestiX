// Port of iOS Export/TrackLogRepository.swift (spec §8, REQ-NAV-004).
//
// Per-session NDJSON breadcrumb log. Writes one JSON object per line
// to `<filesDir>/tracklogs/{session-uuid}.ndjson`. Append-only so a
// crash mid-cruise can't corrupt prior fixes. Read-back helpers
// materialise the file into GPXTrackPoints for export.
//
// Line format matches Swift JSONEncoder with .iso8601 dates:
//   {"lat":..,"lon":..,"timestamp":"2026-07-06T12:34:56Z","horizontalAccuracyM":..}
// (`horizontalAccuracyM` omitted when null, like Swift's nil optional.)

package com.hcjeong.forestix.export

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.Date
import java.util.UUID

data class TrackLogEntry(
    val lat: Double,
    val lon: Double,
    val timestamp: Long,
    val horizontalAccuracyM: Double? = null,
)

interface TrackLogRepository {
    fun append(sessionId: UUID, entry: TrackLogEntry)
    fun readAll(sessionId: UUID): List<TrackLogEntry>
    fun deleteSession(sessionId: UUID)
    fun fileURL(sessionId: UUID): File
}

class FileTrackLogRepository(
    val rootDirectory: File,
) : TrackLogRepository {

    companion object {
        /// Convenience: writes under `<filesDir>/tracklogs/` (the Android
        /// analogue of the iOS Documents directory).
        fun default(context: Context): FileTrackLogRepository =
            FileTrackLogRepository(File(context.filesDir, "tracklogs"))
    }

    override fun fileURL(sessionId: UUID): File {
        ensureDir()
        return File(rootDirectory, "${sessionId.uuidString}.ndjson")
    }

    override fun append(sessionId: UUID, entry: TrackLogEntry) {
        val file = fileURL(sessionId)
        val line = encode(entry) + "\n"
        file.appendText(line, Charsets.UTF_8)
    }

    override fun readAll(sessionId: UUID): List<TrackLogEntry> {
        val file = fileURL(sessionId)
        if (!file.exists()) return emptyList()
        return file.readText(Charsets.UTF_8)
            .split("\n")
            .filter { it.isNotEmpty() }
            .map { decode(it) }
    }

    override fun deleteSession(sessionId: UUID) {
        val file = fileURL(sessionId)
        if (file.exists()) file.delete()
    }

    private fun ensureDir() {
        rootDirectory.mkdirs()
    }

    // MARK: - JSON line codec

    private fun encode(entry: TrackLogEntry): String {
        val obj = JSONObject()
        obj.put("lat", entry.lat)
        obj.put("lon", entry.lon)
        obj.put("timestamp", iso8601(entry.timestamp))
        entry.horizontalAccuracyM?.let { obj.put("horizontalAccuracyM", it) }
        return obj.toString()
    }

    private fun decode(line: String): TrackLogEntry {
        val obj = JSONObject(line)
        return TrackLogEntry(
            lat = obj.getDouble("lat"),
            lon = obj.getDouble("lon"),
            timestamp = parseIso8601(obj.getString("timestamp")),
            horizontalAccuracyM =
                if (obj.has("horizontalAccuracyM") && !obj.isNull("horizontalAccuracyM"))
                    obj.getDouble("horizontalAccuracyM")
                else null,
        )
    }

    private fun iso8601(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(epochMs))
    }

    private fun parseIso8601(s: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        )
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(s)?.time ?: continue
            } catch (_: Exception) {
                // try the next pattern
            }
        }
        throw IllegalArgumentException("Unparseable ISO-8601 timestamp: $s")
    }
}
