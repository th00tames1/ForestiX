// Measure-photo store — JPEG snapshots auto-captured at the moment a DBH
// or Height reading is Accepted (map-home feature). Mirror of the iOS
// MeasurePhotoStore: one file per entry under filesDir/measure-photos/,
// QuickMeasureEntry.photoPath holds just the filename.
//
// The capture is the WHOLE window via PixelCopy — but the scan screens
// hide their 2D chrome (panels, buttons, badges) for the capture frame, so
// the JPEG is the AR feed + measurement overlays only: evidence of what
// was aimed at, without fake-looking buttons in the photo viewer
// (matches the iOS window snapshot).

package com.hcjeong.forestix.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object MeasurePhotoStore {

    fun directory(activity: Activity): File =
        File(activity.filesDir, "measure-photos").apply { mkdirs() }

    fun file(activity: Activity, name: String): File = File(directory(activity), name)

    fun delete(activity: Activity, name: String) {
        File(directory(activity), name).delete()
    }

    /// Snapshot the whole window and save as JPEG (quality 80). Returns the
    /// stored filename, or null when capture/write fails (e.g. window not
    /// yet drawn). Safe to call from the main thread — PixelCopy is async.
    suspend fun captureWindow(activity: Activity): String? {
        val decor = activity.window?.decorView ?: return null
        if (decor.width <= 0 || decor.height <= 0) return null
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    activity.window, bitmap,
                    { result -> cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (_: Exception) {
                cont.resume(false)
            }
        }
        if (!ok) { bitmap.recycle(); return null }
        val name = "m-${UUID.randomUUID()}.jpg"
        return try {
            FileOutputStream(file(activity, name)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            name
        } catch (_: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}
