// Tile fetcher — Android network/bitmap glue over TileCache, the
// counterpart of the iOS MKTileOverlay subclass (TileCache+MapKit) that
// MapKit drives on iOS. Honours the user's AppSettings.tileURLTemplate.
//
// Layering: in-memory LruCache (decoded Bitmaps, byte-budgeted) over the
// flat-file TileCache on disk over HttpURLConnection to the provider.
// All misses are resolved off the UI thread; `tilesVersion` ticks every
// time a new bitmap lands so the Compose MapView can redraw.

package com.hcjeong.forestix.basemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TileFetcher(
    context: Context,
    val urlTemplate: String,
    memoryBudgetBytes: Int = 32 * 1024 * 1024,
) {

    val provider: TileCache.ProviderConfig = TileCache.ProviderConfig(
        urlTemplate = urlTemplate,
        fileExtension = fileExtension(urlTemplate),
        providerId = TileCache.ProviderConfig.providerId(forURLTemplate = urlTemplate),
    )

    /// Same directory scheme as the iOS Application Support cache: one
    /// subtree per provider so switching templates doesn't collide.
    val cache: TileCache = TileCache(
        rootDir = File(context.filesDir, "basemap-tiles"),
        provider = provider,
    )

    private val memory = object : LruCache<TileCache.Key, Bitmap>(memoryBudgetBytes) {
        override fun sizeOf(key: TileCache.Key, value: Bitmap): Int = value.byteCount
    }

    private val inFlight = java.util.Collections.synchronizedSet(mutableSetOf<TileCache.Key>())
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<TileCache.Key>())

    /// Bumped whenever a tile bitmap becomes newly available so the
    /// map canvas knows to redraw.
    private val _tilesVersion = MutableStateFlow(0)
    val tilesVersion: StateFlow<Int> = _tilesVersion.asStateFlow()

    /// Non-blocking read for the draw path: returns the decoded bitmap
    /// when it is already in memory, otherwise kicks off an async
    /// disk-then-network resolve on `scope` and returns null. Keys that
    /// failed to download are not retried within this fetcher's
    /// lifetime (a fresh MapView recreates the fetcher).
    fun bitmapFor(key: TileCache.Key, scope: CoroutineScope): Bitmap? {
        memory.get(key)?.let { return it }
        if (failed.contains(key) || !inFlight.add(key)) return null
        scope.launch(Dispatchers.IO) {
            try {
                val bitmap = resolve(key)
                if (bitmap != null) {
                    memory.put(key, bitmap)
                    _tilesVersion.value = _tilesVersion.value + 1
                } else {
                    failed.add(key)
                }
            } finally {
                inFlight.remove(key)
            }
        }
        return null
    }

    /// Download a tile straight to the disk cache (skips decode) — the
    /// building block for the offline prefetch queue that executes an
    /// OfflineBasemapJob. Returns true when the tile is on disk after
    /// the call.
    suspend fun prefetch(key: TileCache.Key): Boolean = withContext(Dispatchers.IO) {
        if (cache.isCached(key)) return@withContext true
        val bytes = download(key) ?: return@withContext false
        try {
            cache.store(bytes, key)
            true
        } catch (_: Exception) {
            false
        }
    }

    // MARK: - Internal

    /// Disk hit -> decode; miss -> download + store + decode.
    private fun resolve(key: TileCache.Key): Bitmap? {
        cache.data(key)?.let { bytes ->
            decode(bytes)?.let { return it }
        }
        val bytes = download(key) ?: return null
        try {
            cache.store(bytes, key)
        } catch (_: Exception) {
            // Disk full / IO error — still serve from memory this session.
        }
        return decode(bytes)
    }

    private fun decode(bytes: ByteArray): Bitmap? =
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }

    private fun download(key: TileCache.Key): ByteArray? {
        val url = cache.resolvedURL(key) ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            // Identify ourselves per the OSM tile usage policy.
            connection.setRequestProperty("User-Agent", "ForestiX/1.0 (offline basemap)")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) null else bytes
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        /// Infer the tile file extension from the template ("…/{y}.png"
        /// -> "png"). Defaults to png when the template has none.
        fun fileExtension(urlTemplate: String): String {
            val afterY = urlTemplate.substringAfterLast("{y}", "")
            if (!afterY.startsWith(".")) return "png"
            val ext = afterY.drop(1).takeWhile { it.isLetterOrDigit() }
            return if (ext.isEmpty()) "png" else ext.lowercase()
        }
    }
}
