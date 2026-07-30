// Measure-photo store — JPEG snapshots auto-captured at the moment a DBH or
// Height measurement COMPLETES (map-home feature). Mirror of the iOS
// MeasurePhotoStore: one file per entry under filesDir/measure-photos/,
// QuickMeasureEntry.photoPath holds just the filename.
//
// WHEN THE SHUTTER FIRES: the instant the 5-frame burst finishes (Diameter)
// or the treetop sighting produces a height (Height) — NOT at Accept. By
// Accept the cruiser has lowered the phone to read the result panel and
// decide, so every photo in the field log was leaf litter and boots. The scan
// screens hold the file in Compose state and attach it to the reading at
// Accept; they own its lifetime until then (see `heldPhoto` on either
// screen). iOS moved the same shutter to the same two moments.
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
//
// WHAT THIS COSTS, AND WHERE IT IS SPENT. The shutter fires at the exact
// instant the cruiser is watching the result land, so every millisecond on
// the main thread is a millisecond of frozen screen. PixelCopy itself is
// async and cheap here — but it delivers on the main looper, so everything
// AFTER it used to run in a main-thread continuation:
//
//     hasContent, 289 getPixel        ~0.5-1 ms   (JNI per call, not "a few
//                                                  hundred microseconds")
//     compress JPEG at 2.6 Mpx        40-80 ms
//     write                            2-5 ms
//
// The same 40-80 ms stall iOS had, on the same screen, at the same instant.
// Two changes below: the destination bitmap is HALF-SIZE (PixelCopy scales
// into whatever it is given, so a quarter of the pixels costs a quarter of
// the encode), and the sampling, the encode and the write all move to
// Dispatchers.IO. Nothing measurable is left on the main thread.
//
// This still hands the caller a name only once the bytes are down, where
// iOS hands its name over early. The asymmetry is deliberate: iOS renders
// the picture synchronously and therefore KNOWS it exists before it names
// it, while here the frame's existence is only known when the copy resolves
// — and a blank frame, the bug this file is built around, is a real
// outcome. Naming a photo before the copy has answered would be claiming
// evidence that may never arrive.

package com.hcjeong.forestix.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import com.hcjeong.forestix.ar.ArSessionHub
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

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

    // MARK: - Finding a stored photo
    //
    // ONE RESOLVER, AND IT TAKES A Context, NOT AN Activity.
    //
    // These three used to demand an Activity, which made every caller write
    // `LocalContext.current as? Activity` and made that cast part of the
    // lookup. It is not a safe cast in Compose: inside a ModalBottomSheet or
    // a Dialog the composition runs in an AbstractComposeView owned by a
    // ComponentDialog built on `ContextThemeWrapper(activity, …)`, so
    // LocalContext there is a ContextThemeWrapper and the cast yields null.
    // The field log reads its photos from inside its detail bottom sheet and
    // therefore resolved null for every one of them — the "file missing" the
    // cruiser saw on every row — while the map peek worked only because
    // MapHomeScreen captured the Activity at its own top level, outside any
    // dialog window, and threaded it down as a parameter.
    //
    // Nothing here ever needed an Activity: `filesDir` is app-scoped and any
    // Context returns the same directory, ContextThemeWrapper included. So
    // the lookup takes a Context, the casts are gone from every caller, and
    // there is no longer a second way to find one of these files.

    fun directory(context: Context): File =
        File(context.filesDir, "measure-photos").apply { mkdirs() }

    fun file(context: Context, name: String): File = File(directory(context), name)

    fun delete(context: Context, name: String) {
        File(directory(context), name).delete()
    }

    /// Load a stored photo, downsampled for a `targetPx` long edge. The one
    /// loader every viewer uses, so a photo that is on disk cannot render on
    /// one screen and fail on another.
    ///
    /// Suspends on Dispatchers.IO: a decode is tens of milliseconds and the
    /// field log used to do it inline while composing a row. Returns null
    /// when the file is genuinely absent or will not decode — and callers
    /// must say so on screen rather than draw an empty frame.
    suspend fun loadBitmap(context: Context, name: String, targetPx: Int): Bitmap? =
        withContext(Dispatchers.IO) { decodeSampled(file(context, name), targetPx) }

    /// Bounds-first JPEG decode: read the header, halve `inSampleSize` until
    /// one more halving would drop below `targetPx`, then decode once at
    /// that scale. A 96 dp thumbnail has no use for the full frame, and the
    /// full frame is what used to be allocated for it.
    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx &&
                bounds.outHeight / (sample * 2) >= targetPx
            ) {
                sample *= 2
            }
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        } catch (_: Exception) {
            null
        }
    }

    /// Half-size destination for the copy: PixelCopy scales the surface into
    /// whatever bitmap it is handed, and both the JPEG encode and the
    /// transient allocation fall with the pixel count. A 1080x2400 surface
    /// becomes 540x1200 — a quarter of the pixels, ~2.5 MB of bitmap instead
    /// of ~10 MB, and a quarter of the encode.
    ///
    /// The photo is EVIDENCE, not a print — a cruiser looking back at the log
    /// to confirm the cylinder sat on the tree they think they measured, on a
    /// phone screen, usually as a 96 dp thumbnail first. The viewer decodes
    /// for a ~1600 px long edge anyway. Nothing measures this image, so its
    /// resolution enters no computation: the reading, its sigma and its
    /// capture mode are identical either way. Matches the iOS render scale
    /// (native 3.0 -> 1.5), so the two platforms' photos stay comparable.
    private const val CAPTURE_DOWNSCALE = 2

    /// Snapshot the AR surface and save it as JPEG (quality 80). Returns the
    /// stored filename, or null when there is nothing to copy, the copy
    /// fails, the copied frame carries no picture, or the write fails.
    ///
    /// Safe to call from the main thread: PixelCopy is async, and everything
    /// after it — the emptiness check, the encode, the write — is dispatched
    /// to Dispatchers.IO. The suspension costs the caller wall-clock time,
    /// not main-thread time, which is the difference between waiting and
    /// freezing.
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
        val bitmap = Bitmap.createBitmap(
            (view.width / CAPTURE_DOWNSCALE).coerceAtLeast(1),
            (view.height / CAPTURE_DOWNSCALE).coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
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
        val name = "m-${UUID.randomUUID()}.jpg"
        // OFF THE MAIN THREAD FROM HERE. The PixelCopy callback is delivered
        // on the main looper, so this continuation resumes on the main
        // thread; the pixel sampling, the JPEG encode and the write used to
        // run there, 40-80 ms of frozen screen at the moment the result
        // panel appeared. None of it needs the main thread.
        return withContext(Dispatchers.IO) {
            try {
                // SUCCESS with nothing in it is exactly the bug being fixed
                // here, so it is a failure, not a photo.
                if (!hasContent(bitmap)) {
                    Log.w(TAG, "no photo: the copied AR frame was empty (blank/black) — " +
                        "nothing had been rendered into the surface")
                    return@withContext null
                }
                // Resolved here rather than on the way in: `file` creates the
                // directory, and that is a filesystem call the main thread
                // has no reason to make.
                val target = file(activity, name)
                FileOutputStream(target).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                // THE SCREEN CAN BE LEFT WHILE THE BYTES ARE GOING DOWN. The
                // caller's scope is cancelled with the composition, so this
                // name would never reach `heldPhoto` and no `onDispose` could
                // ever delete the file — an orphan in the photo store. The
                // write has no suspension point inside it, so cancellation
                // cannot interrupt it; clean up after ourselves instead.
                if (!isActive) {
                    target.delete()
                    return@withContext null
                }
                name
            } catch (t: Exception) {
                Log.w(TAG, "no photo: writing $name failed", t)
                null
            } finally {
                bitmap.recycle()
            }
        }
    }

    /// Cheap "did a picture actually come back?" check on the copied frame.
    ///
    /// A black JPEG and a good one are both valid JPEGs, and both come from
    /// a PixelCopy that said SUCCESS, so the only way to tell them apart is
    /// to look at the pixels. Sample a coarse grid (289 points — a JNI hop
    /// each, so of the order of half a millisecond, not the "few hundred
    /// microseconds" once claimed here; it runs off the main thread now
    /// either way) and count the ones that were actually drawn:
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
