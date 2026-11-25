package com.hcjeong.forestix.updates

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A release is available only after the matching APK is public and uploaded.
 * Test installs follow work prereleases; production installs never do. No
 * account, background polling, installer permissions or field data are used. */
internal object ApkUpdates {
    private const val repo = "th00tames1/ForestiX"
    private const val pageSize = 30
    data class Build(val code: Int, val url: String)

    private fun JsonObject.field(name: String) = (get(name) as? JsonPrimitive)?.content

    private fun candidate(item: JsonObject, work: Boolean): Build? {
        if (item.field("draft") != "false" || (!work && item.field("prerelease") != "false")) return null
        val prefix = if (work) "android-work-build-" else "android-build-"
        val tag = item.field("tag_name") ?: return null
        val code = tag.removePrefix(prefix).toIntOrNull() ?: return null
        if (code <= 0 || tag != prefix + code) return null
        val name = "ForestiX-${tag.replaceFirst("android", "Android")}.apk"
        val url = "https://github.com/$repo/releases/download/$tag/$name"
        val assets = item["assets"] as? JsonArray ?: return null
        return Build(code, url).takeIf {
            assets.filterIsInstance<JsonObject>().any { asset ->
                asset.field("state") == "uploaded" && asset.field("name") == name &&
                    asset.field("browser_download_url") == url &&
                    (asset.field("size")?.toLongOrNull() ?: 0) > 0
            }
        }
    }

    // Don't rely on GitHub's "latest" release (it excludes work prereleases)
    // or on publication date: an older build can be published out of order.
    internal suspend fun newerThan(installed: Int, work: Boolean, page: suspend (Int) -> String): Build? {
        var best: Build? = null
        var number = 1
        do {
            currentCoroutineContext().ensureActive()
            val items = Json.parseToJsonElement(page(number++)) as? JsonArray
                ?: throw IOException("Unexpected release response")
            for (item in items.filterIsInstance<JsonObject>()) {
                val build = candidate(item, work) ?: continue
                if (build.code > (best?.code ?: installed)) best = build
            }
        } while (items.size == pageSize)
        return best
    }

    suspend fun available(work: Boolean, installed: Int): Build? = withContext(Dispatchers.IO) {
        newerThan(installed, work) { number ->
            val url = URL("https://api.github.com/repos/$repo/releases?per_page=$pageSize&page=$number")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "ForestiX-Android")
                if (connection.responseCode != 200) throw IOException("Release check unavailable")
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
    }
}
