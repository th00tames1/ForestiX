package com.hcjeong.forestix.updates

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkUpdatesTest {
    private fun entry(code: Int, work: Boolean = true): String {
        val build = (if (work) "work-build-" else "build-") + code
        val tag = "android-$build"
        val name = "ForestiX-Android-$build.apk"
        return """{"tag_name":"$tag","draft":false,"prerelease":$work,"assets":[
            {"name":"$name","state":"uploaded","size":1024,
             "browser_download_url":"https://github.com/th00tames1/ForestiX/releases/download/$tag/$name"}]}"""
    }

    private fun check(vararg items: String, installed: Int = 2, work: Boolean = true) =
        runBlocking { ApkUpdates.newerThan(installed, work) { items.joinToString(",", "[", "]") } }

    @Test fun downloadIsPinnedToTheNewestMatchingAsset() {
        assertEquals(ApkUpdates.Build(10, "https://github.com/th00tames1/ForestiX/releases/download/" +
            "android-work-build-10/ForestiX-Android-work-build-10.apk"),
            check(entry(3), entry(10), entry(9)))
    }
    @Test fun currentOlderAndNonAndroidReleasesAreNotOffered() {
        assertNull(check(entry(2), entry(1), """{"tag_name":"ios-build-9","draft":false}"""))
        assertNull(check())
    }
    @Test fun stableAndTestBuildsNeverCrossChannels() {
        assertEquals(5, check(entry(5), entry(7, false))?.code)
        assertEquals(7, check(entry(5), entry(7, false), work = false)?.code)
        assertNull(check(entry(7, false).replace("\"prerelease\":false", "\"prerelease\":true"), work = false))
    }
    @Test fun ignoreDraftEmptyUnfinishedAndMismatchedAssets() {
        val ok = entry(3)
        for (bad in listOf(ok.replace("\"draft\":false", "\"draft\":true"),
            ok.replace("uploaded", "pending"), ok.replace("1024", "0"),
            ok.replace("github.com/", "example.com/"), ok.replace(".apk", ".zip"),
            ok.replace("build-3", "build-03"))) assertNull(check(bad))
    }
    @Test fun laterPagesAndOutOfOrderPublication() = runBlocking {
        val pages = mutableListOf<Int>()
        val result = ApkUpdates.newerThan(2, true) { n ->
            pages.add(n)
            if (n == 1) List(30) { entry(3) }.joinToString(",", "[", "]") else "[${entry(8)}]"
        }
        assertEquals(listOf(1, 2), pages)
        assertEquals(8, result?.code)
    }
    @Test(expected = IOException::class)
    fun failedCheckIsNotReportedAsUpToDate() {
        runBlocking { ApkUpdates.newerThan(2, true) { throw IOException("offline") } }
    }
    @Test(expected = IOException::class)
    fun errorPayloadIsNotReportedAsUpToDate() {
        runBlocking { ApkUpdates.newerThan(2, true) { """{"message":"rate limited"}""" } }
    }
}
