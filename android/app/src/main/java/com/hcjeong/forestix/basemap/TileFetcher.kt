// Tile fetcher — Android network/bitmap glue over TileCache, the
// counterpart of the iOS MKTileOverlay subclass (TileCache+MapKit) that
// MapKit drives on iOS. Serves the built-in Esri World Imagery satellite
// base (`esriWorldImagery`) and the user's AppSettings.tileURLTemplate
// overlay alike — one fetcher per layer, caches namespaced by providerId.
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
    /// Built-in providers pass these explicitly (the Esri template carries
    /// no ".jpg" suffix to infer from); user templates leave them null and
    /// get the inferred extension + hashed provider id.
    fileExtension: String? = null,
    providerId: String? = null,
) {

    val provider: TileCache.ProviderConfig = TileCache.ProviderConfig(
        urlTemplate = urlTemplate,
        fileExtension = fileExtension ?: fileExtension(urlTemplate),
        providerId = providerId ?: TileCache.ProviderConfig.providerId(forURLTemplate = urlTemplate),
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
            // A descriptive, identifying User-Agent is REQUIRED by the OSM
            // tile usage policy (a generic library UA gets blocked), so it
            // goes on every tile request regardless of provider.
            connection.setRequestProperty("User-Agent", USER_AGENT)
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
        /// Identifies the app to tile providers. The OSM tile policy
        /// requires a UA naming the application; keep it descriptive.
        const val USER_AGENT = "ForestiX/1.0 (Android forest-inventory field app)"

        /// Built-in satellite base layer — Esri World Imagery, shown by
        /// default so the map has real imagery with zero setup (mirror of
        /// the iOS built-in provider; note the {z}/{y}/{x} path order).
        const val ESRI_WORLD_IMAGERY_TEMPLATE =
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
        const val ESRI_WORLD_IMAGERY_PROVIDER_ID = "esri-world-imagery"
        /// Required by the imagery terms — always visible on the map.
        const val ESRI_WORLD_IMAGERY_ATTRIBUTION = "Esri · Maxar · Earthstar Geographics"

        /// Fetcher for the built-in satellite base. Same filesDir disk
        /// cache as user templates, so browsed tiles persist for offline
        /// use and TileCache stats/clear see them too.
        fun esriWorldImagery(context: Context): TileFetcher = TileFetcher(
            context = context,
            urlTemplate = ESRI_WORLD_IMAGERY_TEMPLATE,
            fileExtension = "jpg",
            providerId = ESRI_WORLD_IMAGERY_PROVIDER_ID,
        )

        /// Built-in street base layer — OpenStreetMap standard, the
        /// "Normal" map type. Same {z}/{x}/{y} order OSM publishes.
        const val OSM_STANDARD_TEMPLATE = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
        const val OSM_STANDARD_PROVIDER_ID = "osm-standard"
        /// Required by the ODbL — always visible while this base is drawn.
        const val OSM_STANDARD_ATTRIBUTION = "© OpenStreetMap contributors"

        fun osmStandard(context: Context): TileFetcher = TileFetcher(
            context = context,
            urlTemplate = OSM_STANDARD_TEMPLATE,
            fileExtension = "png",
            providerId = OSM_STANDARD_PROVIDER_ID,
        )

        /// The built-in base for a persisted `tc.mapType` — "normal" is
        /// OSM standard, anything else (the default) is Esri satellite.
        /// One factory so the map, the offline downloader and the cache
        /// stats can never disagree about which base is in use.
        fun builtInBase(context: Context, mapType: String): TileFetcher =
            if (mapType == "normal") osmStandard(context) else esriWorldImagery(context)

        /// Attribution line that must ride along with `builtInBase`.
        fun builtInAttribution(mapType: String): String =
            if (mapType == "normal") OSM_STANDARD_ATTRIBUTION else ESRI_WORLD_IMAGERY_ATTRIBUTION

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
