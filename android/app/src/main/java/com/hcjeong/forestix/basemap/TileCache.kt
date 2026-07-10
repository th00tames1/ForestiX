// Tile cache — 1:1 port of iOS Basemap/TileCache.swift
// (spec §8, REQ-PRJ-006 pre-download basemap tiles).
//
// A content-addressed on-disk cache: each tile is stored at
//     <cacheRoot>/<providerId>/<z>/<x>/<y>.<ext>
// where `providerId` is a slug derived from the tile URL template so users
// can switch providers without the caches colliding. The cache is plain
// flat files — no database — because offline-first cruises routinely copy
// project folders between devices and a single file tree survives that.
//
// The network + bitmap glue (TileFetcher, the Android analogue of the
// MKTileOverlay subclass) lives in TileFetcher.kt. Keeping the pure-IO
// layer separate mirrors the iOS library/UI target split.

package com.hcjeong.forestix.basemap

import com.hcjeong.forestix.geo.CoordinateConversions
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

class TileCache(
    val rootDir: File,
    val provider: ProviderConfig,
) {

    // MARK: - Identity

    data class Key(val z: Int, val x: Int, val y: Int)

    data class ProviderConfig(
        val urlTemplate: String,       // e.g. https://tile.example/{z}/{x}/{y}.png
        val fileExtension: String,     // "png", "jpg"
        val providerId: String,        // slug used as subdirectory
    ) {
        companion object {
            /// Build a provider id from a URL template if the caller doesn't have
            /// a stable name for it (e.g. user-pasted templates in Settings).
            /// Same SHA-256 recipe as the iOS CryptoKit path so both platforms
            /// bucket a given template into the same directory name.
            fun providerId(forURLTemplate: String): String {
                val lowered = forURLTemplate.lowercase()
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(lowered.toByteArray(Charsets.UTF_8))
                val hex = digest.joinToString("") { String.format(Locale.US, "%02x", it) }
                return "tpl-" + hex.take(16)
            }
        }
    }

    // MARK: - State

    val providerDirectory: File = File(rootDir, provider.providerId)

    init {
        providerDirectory.mkdirs()
    }

    fun fileURL(key: Key): File =
        File(
            File(File(providerDirectory, "${key.z}"), "${key.x}"),
            "${key.y}.${provider.fileExtension}",
        )

    // MARK: - Reads / writes

    fun isCached(key: Key): Boolean = fileURL(key).exists()

    fun data(key: Key): ByteArray? {
        val file = fileURL(key)
        if (!file.exists()) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    fun store(data: ByteArray, key: Key) {
        val file = fileURL(key)
        file.parentFile?.mkdirs()
        // Write-then-rename for the atomicity iOS gets from `.atomic`.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) {
            file.writeBytes(data)
            tmp.delete()
        }
    }

    fun remove(key: Key) {
        val file = fileURL(key)
        if (file.exists()) file.delete()
    }

    // MARK: - URL building

    fun resolvedURL(key: Key): URL? {
        val resolved = provider.urlTemplate
            .replace("{z}", "${key.z}")
            .replace("{x}", "${key.x}")
            .replace("{y}", "${key.y}")
        return try {
            URL(resolved)
        } catch (_: MalformedURLException) {
            null
        }
    }

    // MARK: - Cache stats

    data class Stats(val fileCount: Int, val byteCount: Long)

    fun stats(): Stats {
        var files = 0
        var bytes = 0L
        providerDirectory.walkTopDown().forEach { f ->
            if (f.isFile && !f.name.startsWith(".")) {
                files += 1
                bytes += f.length()
            }
        }
        return Stats(fileCount = files, byteCount = bytes)
    }

    fun clear() {
        if (providerDirectory.exists()) {
            providerDirectory.deleteRecursively()
        }
        providerDirectory.mkdirs()
    }
}

// MARK: - Tile maths

/// Slippy-map tile conversions (OSM / XYZ scheme).
object TileMath {

    /// Convert a lat/lon to the tile that contains it at zoom `z`.
    fun tile(point: CoordinateConversions.LatLon, zoom: Int): TileCache.Key {
        val n = (1 shl zoom).toDouble()
        val latRad = point.latitude * Math.PI / 180
        val x = floor((point.longitude + 180) / 360 * n).toInt()
        val y = floor((1 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) / 2 * n).toInt()
        val maxIdx = (1 shl zoom) - 1
        return TileCache.Key(
            z = zoom,
            x = max(0, min(maxIdx, x)),
            y = max(0, min(maxIdx, y)),
        )
    }

    /// North-west corner lat/lon of a tile.
    fun northwestCorner(key: TileCache.Key): CoordinateConversions.LatLon {
        val n = (1 shl key.z).toDouble()
        val lon = key.x / n * 360 - 180
        val latRad = atan(sinh(Math.PI * (1 - 2 * key.y / n)))
        return CoordinateConversions.LatLon(latitude = latRad * 180 / Math.PI, longitude = lon)
    }
}
