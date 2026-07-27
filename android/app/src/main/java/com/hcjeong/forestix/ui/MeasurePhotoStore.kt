// Measure-photo store — JPEG snapshots auto-captured at the moment a DBH
// or Height reading is Accepted (map-home feature). Mirror of the iOS
// MeasurePhotoStore: one file per entry under filesDir/measure-photos/,
// QuickMeasureEntry.photoPath holds just the filename.
//
// WHAT IS IN THE PHOTO: the AR SURFACE — the camera feed with the rendered
// measurement geometry drawn into it (the markers, the cylinder, the plot
// ring). The scan screens still hide their 2D chrome for the capture frame,
// so no panels or buttons are baked in and the photo can't be mistaken for
// a live screen. That is the same content the iOS snapshot carries, and it
// is what makes the photo evidence of what was measured.
//
// WHY NOT THE WINDOW (field report: every saved photo was BLACK on Android
// while iOS was fine). This used to be PixelCopy.request(activity.window,
// …). The AR view is SceneView's ARSceneView, and io.github.sceneview.
// SceneView extends android.view.SurfaceView. A SurfaceView's content lives
// in its OWN compositor layer, not in the window's drawing: the view
// hierarchy punches a transparent hole where the surface sits, and the two
// layers are composited at display time. A WINDOW-targeted PixelCopy reads
// back the window layer only, so it returned that hole — fully transparent
// pixels, which JPEG (no alpha channel) writes out as black. With the
// chrome hidden for the shot the hole IS the whole frame, so the file was
// uniformly black. iOS is unaffected because its snapshot goes through one
// layer tree.
//
// So the copy targets the AR SurfaceView itself. Every failure returns null
// (callers already store "no photo" for null) and names the reason in
// logcat, so a missing photo is never silently indistinguishable from a
// saved one. Crucially, PixelCopy reporting SUCCESS is NOT on its own
// evidence that a picture came back — the window copy reported SUCCESS for
// every black frame in the field report — so the copied bitmap is sampled
// before it is written and an empty frame is treated as a failure.

package com.hcjeong.forestix.ui

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.hcjeong.forestix.ar.ArSessionHub
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

object MeasurePhotoStore {

    private const val TAG = "MeasurePhotoStore"

    /// Grid used by [hasContent]: (STEPS + 1)² samples across the frame.
    private const val CONTENT_SAMPLE_STEPS = 16

    /// How many of those samples must carry a drawn pixel. 3 of 289 is ~1 %:
    /// enough that a stray decode artefact can't pass a blank frame, low
    /// enough that only a blank frame fails.
    private const val CONTENT_MIN_SAMPLES = 3

    /// A sample counts as drawn when it is opaque enough to end up in the
    /// JPEG AND is not pure black.
    ///
    /// Both thresholds sit at "not nothing", NOT at "bright enough". The
    /// frame being rejected is one nothing was rendered into — a cleared
    /// buffer, which reads as either fully transparent (the punched
    /// SurfaceView hole the window copy used to return) or exact 0,0,0.
    /// An auto-exposed camera frame carries sensor black-level offset and
    /// noise in every pixel, so even a dusk-under-canopy scan clears 2/255
    /// easily; setting this any higher would start throwing away real dark
    /// photos, which is a worse trade than storing a dim one.
    private const val CONTENT_MIN_ALPHA = 16
    private const val CONTENT_MIN_LEVEL = 2

    fun directory(activity: Activity): File =
        File(activity.filesDir, "measure-photos").apply { mkdirs() }

    fun file(activity: Activity, name: String): File = File(directory(activity), name)

    fun delete(activity: Activity, name: String) {
        File(directory(activity), name).delete()
    }

    /// Snapshot the AR surface and save it as JPEG (quality 80). Returns the
    /// stored filename, or null when there is nothing to copy, the copy
    /// fails, the copied frame carries no picture, or the write fails.
    /// Safe to call from the main thread — PixelCopy is async.
    suspend fun captureScene(activity: Activity): String? {
        // The AR view, not the window. Null while no AR screen owns the
        // shared session — nothing to photograph.
        val view = ArSessionHub.currentView()
        if (view == null) {
            Log.w(TAG, "no photo: no live AR view to copy")
            return null
        }
        // Not laid out / not on screen yet: PixelCopy would either throw or
        // hand back an undefined frame.
        if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) {
            Log.w(TAG, "no photo: AR view not ready " +
                "(attached=${view.isAttachedToWindow}, ${view.width}x${view.height})")
            return null
        }
        // The surface can outlive/precede the view's own validity (it is
        // created and destroyed by the compositor, not by the view).
        if (!view.holder.surface.isValid) {
            Log.w(TAG, "no photo: the AR surface is not ready")
            return null
        }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val status = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    view, bitmap,
                    { result -> cont.resume(result) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (t: Throwable) {
                // IllegalArgumentException when the surface went away between
                // the check above and the request.
                Log.w(TAG, "no photo: the AR surface copy was refused", t)
                cont.resume(PixelCopy.ERROR_UNKNOWN)
            }
        }
        if (status != PixelCopy.SUCCESS) {
            bitmap.recycle()
            Log.w(TAG, "no photo: the AR surface copy failed (PixelCopy status $status)")
            return null
        }
        // SUCCESS with nothing in it is exactly the bug being fixed here, so
        // it is a failure, not a photo.
        if (!hasContent(bitmap)) {
            bitmap.recycle()
            Log.w(TAG, "no photo: the copied AR frame was empty (blank/black) — " +
                "nothing had been rendered into the surface")
            return null
        }
        val name = "m-${UUID.randomUUID()}.jpg"
        return try {
            FileOutputStream(file(activity, name)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
            name
        } catch (t: Exception) {
            Log.w(TAG, "no photo: writing $name failed", t)
            null
        } finally {
            bitmap.recycle()
        }
    }

    /// Cheap "did a picture actually come back?" check on the copied frame.
    ///
    /// A black JPEG and a good one are both valid JPEGs, and both come from
    /// a PixelCopy that said SUCCESS, so the only way to tell them apart is
    /// to look at the pixels. Sample a coarse grid (289 points, a few
    /// hundred microseconds) and count the ones that were actually drawn:
    /// not transparent, not pure black. Anything that clears the bar is a
    /// picture; a frame where nothing was rendered cannot.
    private fun hasContent(bitmap: Bitmap): Boolean {
        val maxX = bitmap.width - 1
        val maxY = bitmap.height - 1
        if (maxX < 0 || maxY < 0) return false
        var drawn = 0
        for (iy in 0..CONTENT_SAMPLE_STEPS) {
            val y = maxY * iy / CONTENT_SAMPLE_STEPS
            for (ix in 0..CONTENT_SAMPLE_STEPS) {
                val pixel = bitmap.getPixel(maxX * ix / CONTENT_SAMPLE_STEPS, y)
                if ((pixel ushr 24 and 0xFF) < CONTENT_MIN_ALPHA) continue   // the hole
                val r = pixel shr 16 and 0xFF
                val g = pixel shr 8 and 0xFF
                val b = pixel and 0xFF
                if (maxOf(r, g, b) < CONTENT_MIN_LEVEL) continue             // cleared
                drawn++
                if (drawn >= CONTENT_MIN_SAMPLES) return true
            }
        }
        return false
    }
}
