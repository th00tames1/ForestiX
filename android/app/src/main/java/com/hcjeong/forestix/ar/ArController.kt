// Bridge that lets Compose callers fire a screen-centre raycast against
// the live ARCore frame — the Android analogue of iOS ARCenterRaycaster +
// the camera-position / projection helpers used across the AR screens.
//
// ARCore's `Frame.hitTest(x, y)` already walks depth points (when the
// Depth API is enabled, the rough equivalent of iOS LiDAR scene-mesh
// raycasting) and detected planes, returning results ordered nearest-
// first. That replaces the hand-rolled Moller-Trumbore mesh walk on iOS
// with one stable platform call.

package com.hcjeong.forestix.ar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hcjeong.forestix.sensors.ArDepthFrame
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.sqrt

// The aim-offset instrument that used to live here (an `OpticalAxisProbe`
// reporting where the lens principal point lands against the view centre) is
// gone: it was built to settle whether the height anchor sphere sits off the
// crosshair, it was tested on device, and the answer was that it does not.
// Nothing measured, stored or exported ever read it.

class ArController {

    @Volatile var frame: Frame? = null
        internal set
    @Volatile var session: Session? = null
        internal set

    /// Viewport in pixels — updated by ArCameraView so screen-centre and
    /// world->screen projection use the right dimensions.
    @Volatile var viewWidthPx: Int = 0
    @Volatile var viewHeightPx: Int = 0

    /// True when the running session supports the Depth API — the closest
    /// Android equivalent of "device has LiDAR". Picks Distance's raw-vs-
    /// smoothed path, gates normal-mode DBH scanning, and enables the
    /// dev-only DBH method selector's Depth segment. Backed by Compose
    /// snapshot state so a Composable reading it recomposes when the
    /// session reports depth support.
    var supportsDepth: Boolean by mutableStateOf(false)
        internal set

    /// True once the running session has actually REPORTED depth capability
    /// — supportsDepth defaults to false before the session configures, so
    /// UI that blocks on missing depth (normal-mode DBH) must wait for this
    /// or it would flash the blocker on capable devices during AR startup.
    var depthSupportKnown: Boolean by mutableStateOf(false)
        internal set

    /// When true (LiDAR mode), screenCenterHit takes the nearest hit of any
    /// kind — including Depth API points. When false (AR mode), it filters
    /// to estimated-plane hits only, the analogue of the iOS raycaster's
    /// `preferLiDARMesh` flag. Pinned false on devices without the Depth API.
    @Volatile var preferDepth: Boolean = true

    /// Raw-capture recorder arm (developer-mode only, set by the scan
    /// screens from `developerMode && tc.rawCaptureEnabled`). While true,
    /// acquireDepthFrame ALSO keeps a de-strided copy of the native u16-mm
    /// depth plane on the returned frame (ArDepthFrame.rawDepthMm) so a
    /// replay bundle can store the sensor bytes verbatim. Zero cost when
    /// false — the copy is simply skipped, exactly the toggle-off contract.
    @Volatile var captureRawDepth: Boolean = false

    fun onUpdate(session: Session, frame: Frame) {
        this.session = session
        this.frame = frame
        if (frame.camera.trackingState != TrackingState.TRACKING) trackedNormalSinceWatch = false
    }

    // Per-window tracking latch — the Android counterpart of iOS
    // ARKitSessionManager.beginTrackingWatch. Reset at the start of a VIO
    // sweep so an earlier dropout doesn't permanently veto the green tier.
    @Volatile private var trackedNormalSinceWatch = true

    /// Begin a fresh per-window tracking watch (call at sweep start).
    fun beginTrackingWatch() {
        trackedNormalSinceWatch = frame?.camera?.trackingState == TrackingState.TRACKING
    }

    /// True if every frame since the last beginTrackingWatch() was TRACKING.
    fun trackingStayedNormalSinceWatch(): Boolean = trackedNormalSinceWatch

    private fun ready(): Boolean {
        val f = frame ?: return false
        return f.camera.trackingState == TrackingState.TRACKING && viewWidthPx > 1 && viewHeightPx > 1
    }

    /// The live frame ONLY while ARCore is actually tracking — the gate every
    /// pose reader below goes through.
    ///
    /// `Frame.camera.pose` is undefined once the camera's tracking state
    /// drops to PAUSED, and in practice it collapses toward the identity
    /// pose. The readers turn that into a world position, an aim angle or a
    /// distance with nothing in the return value to say the pose was never
    /// real. That is the height walk-off's vanishing anchor (field round 9):
    /// the anchoring pose sits near the session origin, so a distance
    /// measured from an identity pose to it is ~0 and "Walked back" drops to
    /// 0.00 while the cruiser is still walking — and the same untracked pose
    /// is what the base/top aim taps would have read for d_h.
    ///
    /// Null is the honest answer and the contract the hit tests have always
    /// had: it means "no pose", never "a pose that happens to be wrong".
    /// Every caller in the app already treats it that way (`?.let` / `?:`).
    private fun trackingFrame(): Frame? =
        frame?.takeIf { it.camera.trackingState == TrackingState.TRACKING }

    /// Last screenCenterHit's trackable type + distance — dev-HUD readout so
    /// a field run can see WHAT the anchor landed on ("depth 1.62m").
    @Volatile var lastCenterHitInfo: String? = null
        private set

    /// Hit the scene at the screen centre. Returns the nearest hit's world
    /// translation, or null when tracking isn't ready / nothing is hit.
    /// Callers must treat null as "tracking not ready", never substitute
    /// the camera position (which biases measurements) — same contract as
    /// the iOS raycaster.
    ///
    /// HIT SELECTION (field round 7): depth mode accepts only SURFACE hits —
    /// DepthPoint (the depth-image sample ON the cast ray, the ARCore
    /// analogue of the iOS LiDAR scene-mesh raycast) or Plane. Bare Point
    /// (sparse feature-point) hits are rejected: ARCore returns feature
    /// points NEAR the ray with the hit pose AT the point itself, so taking
    /// the nearest-of-any-kind could anchor on a bark/foliage feature point
    /// off the crosshair — the height-anchor sphere then renders visibly off
    /// the aim and d_h (→ H) is computed from that wrong point. iOS never
    /// consumes feature-point hits (mesh raycast → estimated planes only).
    fun screenCenterHit(): Vec3? {
        if (!ready()) { lastCenterHitInfo = null; return null }
        val f = frame ?: return null
        val cx = viewWidthPx / 2f
        val cy = viewHeightPx / 2f
        val hits = try { f.hitTest(cx, cy) } catch (_: Throwable) { return null }
        // LiDAR mode: nearest SURFACE hit (depth-image points + planes).
        // AR mode (no Depth API): estimated-plane hits first, then anything —
        // the dev-only caliper/motion arms need some distance to work with.
        val hit = if (preferDepth) hits.firstOrNull { it.trackable is DepthPoint || it.trackable is Plane }
        else hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull()
        lastCenterHitInfo = hit?.let {
            val kind = when (it.trackable) {
                is DepthPoint -> "depth"
                is Plane -> "plane"
                is Point -> "point"
                else -> "other"
            }
            String.format(Locale.US, "%s %.2fm", kind, it.distance)
        }
        hit ?: return null
        val t = hit.hitPose.translation
        return Vec3(t[0], t[1], t[2])
    }

    /// Height-anchor raycast (locked spec, field round 8). At eye level the
    /// aim ray is near-horizontal, so when no DepthPoint is available the
    /// plain surface filter above happily returns the ray's intersection
    /// with ARCore's detected GROUND plane — tens of metres out (field:
    /// 12.78 / 31.11 / 44.33 m as the pitch wobbled 7°→2° from ~1.6 m eye
    /// height). Policy here: nearest DepthPoint within `maxDistM`; a Plane
    /// hit is accepted ONLY when it is BOTH within the gate AND facing the
    /// ray (plane normal within 30° of the reverse ray — a grazed ground
    /// plane is ~88° off and can never anchor). Sparse Point hits never
    /// anchor. `lastCenterHitInfo` reports what was accepted, or the first
    /// rejection with its reason ("plane 31.2m rejected") for the dev HUD.
    fun screenCenterAnchorHit(maxDistM: Float): Vec3? {
        if (!ready()) { lastCenterHitInfo = null; return null }
        val f = frame ?: return null
        val hits = try { f.hitTest(viewWidthPx / 2f, viewHeightPx / 2f) } catch (_: Throwable) { return null }
        val camT = f.camera.pose.translation
        var rejected: String? = null
        for (h in hits) {
            val trackable = h.trackable
            val d = h.distance
            when (trackable) {
                is DepthPoint -> {
                    if (d <= maxDistM) {
                        lastCenterHitInfo = String.format(Locale.US, "depth %.2fm", d)
                        val t = h.hitPose.translation
                        return Vec3(t[0], t[1], t[2])
                    }
                    if (rejected == null) rejected = String.format(Locale.US, "depth %.1fm rejected", d)
                }
                is Plane -> {
                    if (d > maxDistM) {
                        if (rejected == null) rejected = String.format(Locale.US, "plane %.1fm rejected", d)
                        continue
                    }
                    // Facing check: plane normal (hit pose +Y) must be
                    // within 30° of the REVERSE ray. dot(n, ray) ≤ −cos30°.
                    val t = h.hitPose.translation
                    val n = h.hitPose.yAxis
                    val rx = t[0] - camT[0]; val ry = t[1] - camT[1]; val rz = t[2] - camT[2]
                    val len = sqrt(rx * rx + ry * ry + rz * rz)
                    if (len < 1e-4f) continue
                    val dot = (n[0] * rx + n[1] * ry + n[2] * rz) / len
                    if (dot <= -0.866f) {
                        lastCenterHitInfo = String.format(Locale.US, "plane %.2fm", d)
                        return Vec3(t[0], t[1], t[2])
                    }
                    if (rejected == null) rejected = String.format(Locale.US, "plane %.1fm oblique", d)
                }
                else -> { /* sparse Point / other: never an anchor */ }
            }
        }
        lastCenterHitInfo = rejected
        return null
    }

    /// Project the camera-forward ray to a world point at exactly `d`
    /// metres of horizontal distance — fallback when no surface is hit
    /// (sky / canopy). Direct port of forwardPointAtHorizontalDistance.
    fun forwardPointAtHorizontalDistance(d: Float): Vec3? {
        val f = trackingFrame() ?: return null
        val pose = f.camera.pose
        // ARCore camera looks down -Z of its pose, like ARKit.
        val zAxis = pose.zAxis            // forward = -zAxis
        val forward = Vec3(-zAxis[0], -zAxis[1], -zAxis[2])
        val t = pose.translation
        val origin = Vec3(t[0], t[1], t[2])
        val horizontal = sqrt(forward.x * forward.x + forward.z * forward.z)
        if (horizontal < 1e-4f) return null
        val scale = d / horizontal
        return origin + forward * scale
    }

    /// Distance from the device (camera) to whatever the screen centre
    /// hits — the live readout in DistanceMeasureScreen.
    fun cameraToCenterDistance(): Double? {
        val hit = screenCenterHit() ?: return null
        val f = frame ?: return null
        val t = f.camera.pose.translation
        val cam = Vec3(t[0], t[1], t[2])
        return distance(cam, hit).toDouble()
    }

    fun currentCameraPosition(): Vec3? {
        val f = trackingFrame() ?: return null
        val t = f.camera.pose.translation
        return Vec3(t[0], t[1], t[2])
    }

    /// Elevation angle (radians) of the camera's forward aim ray above the
    /// horizon — the Android analogue of the iOS IMU pitch α used by the
    /// height walk-off tangent. Positive = aiming up. Forward = -zAxis.
    fun cameraForwardElevationRad(): Float? {
        val f = trackingFrame() ?: return null
        val z = f.camera.pose.zAxis
        val fwd = Vec3(-z[0], -z[1], -z[2])
        val horiz = sqrt(fwd.x * fwd.x + fwd.z * fwd.z)
        return kotlin.math.atan2(fwd.y, horiz)
    }

    /// Yaw (degrees, 0…360, clockwise from world −Z toward world +X) of the
    /// camera's forward direction projected onto the horizontal plane — the
    /// ARCore-world "pseudo-heading". Paired with a same-instant compass
    /// azimuth it yields the yaw between the (arbitrary-yaw) ARCore world
    /// frame and true north, which the offset flow needs because ARCore has
    /// no `.gravityAndHeading` world alignment like ARKit. Null while not
    /// tracking or when the forward ray is near-vertical (no stable yaw).
    fun cameraWorldYawDeg(): Double? {
        val f = trackingFrame() ?: return null
        val z = f.camera.pose.zAxis
        val fwd = Vec3(-z[0], -z[1], -z[2])
        val horiz = sqrt(fwd.x * fwd.x + fwd.z * fwd.z)
        if (horiz < 1e-3f) return null
        val yaw = Math.toDegrees(kotlin.math.atan2(fwd.x, -fwd.z).toDouble())
        return (yaw + 360.0) % 360.0
    }

    /// Horizontal (XZ) distance from the current camera to a world point —
    /// the live walk-off distance d_h from a trunk anchor.
    fun horizontalDistanceTo(p: Vec3): Float? {
        val cam = currentCameraPosition() ?: return null
        val dx = cam.x - p.x
        val dz = cam.z - p.z
        return sqrt(dx * dx + dz * dz)
    }

    /// Full-resolution camera focal length (x) + image width, used to
    /// project a real-world width (m) to on-screen pixels for the live HUD.

    /// True when the latest frame is tracking, i.e. when every pose reader
    /// above will actually return something. Read live by the height
    /// walk-off so a dropout is announced while it is happening, not
    /// inferred afterwards from a frozen number.
    fun trackingOk(): Boolean = trackingFrame() != null

    /// Camera-forward elevation in degrees (the dev-HUD readout of the
    /// pitch the height tangent uses).
    fun cameraPitchDeg(): Float? = cameraForwardElevationRad()?.let { it * 180f / Math.PI.toFloat() }

    /// Acquire the ARCore Depth-API frame as the platform-independent
    /// ArDepthFrame the DBH estimator consumes — the Android analogue of
    /// ARKit sceneDepth. Returns null when depth isn't available this frame.
    ///
    /// Convention bridge: the depth pixel unprojects in an OpenCV-style
    /// camera frame (+X right, +Y down, +Z forward). ARCore's camera frame
    /// is +Y up / -Z forward, so we post-multiply the pose by diag(1,-1,-1,1)
    /// (negate the Y and Z columns) before back-projection, matching the
    /// world-XZ the iOS pipeline produces. NOTE: depth-image orientation +
    /// intrinsics scaling are device/rotation sensitive — validate on-device.
    ///
    /// GEOMETRY CONTRACT (round-7 audit): the ARCore depth image is
    /// registered to the GPU texture's TEXTURE_NORMALIZED space — the depth
    /// developer guide indexes depth pixels with IMAGE_PIXELS→
    /// TEXTURE_NORMALIZED coordinates, and Google's own BackgroundRenderer
    /// samples the depth texture with the NDC→TEXTURE_NORMALIZED UVs it uses
    /// for the camera texture. So texture intrinsics × depth/texture size
    /// ratio PER AXIS is the correct depth-grid intrinsics — but when the
    /// depth aspect ≠ texture aspect the result has fx ≠ fy (non-square
    /// depth pixels) and consumers MUST pick the focal matching their walk
    /// axis. The depth-px↔view-px affine below comes from ARCore's own
    /// transformCoordinates2d, so tap + overlay mapping track the display
    /// rotation/crop exactly. Re-validate on any ARCore bump (dev "geom"
    /// HUD line: WxH + focal used + raw/smoothed distance).
    fun acquireDepthFrame(): ArDepthFrame? {
        val f = frame ?: return null
        if (f.camera.trackingState != TrackingState.TRACKING) return null
        val image = try { f.acquireDepthImage16Bits() } catch (_: Throwable) { return null }
        try {
            val w = image.width
            val h = image.height
            val plane = image.planes[0]
            val shorts = plane.buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
            val rowStrideShorts = plane.rowStride / 2
            // D_16 is a packed 16-bit plane (pixelStride 2 → 1 short); honour
            // the reported stride anyway so a padded layout can't shear the
            // grid (a stride bug skews the whole silhouette extraction).
            val pixelStrideShorts = (plane.pixelStride / 2).coerceAtLeast(1)
            val depth = FloatArray(w * h)
            val conf = ByteArray(w * h)
            // Raw-capture arm: keep a compact, de-strided row-major u16-mm
            // copy so a replay bundle stores exactly the bytes the estimator
            // saw (honouring the reported row/pixel stride here means the
            // .bin is a clean w*h grid, never a padded/sheared one).
            val rawMm: ShortArray? = if (captureRawDepth) ShortArray(w * h) else null
            for (y in 0 until h) {
                val base = y * rowStrideShorts
                for (x in 0 until w) {
                    val raw = shorts.get(base + x * pixelStrideShorts)
                    val mm = raw.toInt() and 0xFFFF
                    val idx = y * w + x
                    // Single shared ingest rule (replay uses the identical one).
                    ArDepthFrame.ingestMmInto(mm, idx, depth, conf)
                    if (rawMm != null) rawMm[idx] = raw
                }
            }
            // Scale the full-res texture intrinsics down to the depth image
            // (per axis — see the geometry-contract note above).
            val ti = f.camera.textureIntrinsics
            val dims = ti.imageDimensions   // [texW, texH]
            val fl = ti.focalLength         // [fx, fy]
            val pp = ti.principalPoint      // [cx, cy]
            val sx = w.toDouble() / dims[0]
            val sy = h.toDouble() / dims[1]
            val fx = fl[0] * sx
            val fy = fl[1] * sy
            val cx = pp[0] * sx
            val cy = pp[1] * sy
            // Diagnostic alternative: CPU-image intrinsics scaled the same
            // way — the correct values IF a device registered its depth to
            // the CPU image instead of the texture. HUD-only.
            var fxImg = 0.0
            var fyImg = 0.0
            try {
                val ii = f.camera.imageIntrinsics
                val idims = ii.imageDimensions
                if (idims[0] > 0 && idims[1] > 0) {
                    fxImg = ii.focalLength[0] * w.toDouble() / idims[0]
                    fyImg = ii.focalLength[1] * h.toDouble() / idims[1]
                }
            } catch (_: Throwable) { /* diagnostics only */ }
            // VIEW-px -> depth-px affine from ARCore's own coordinate
            // transform (VIEW → TEXTURE_NORMALIZED is affine: display
            // rotation + centred aspect crop — three points pin it down).
            var depthFromView: FloatArray? = null
            val vw = viewWidthPx.toFloat()
            val vh = viewHeightPx.toFloat()
            if (vw > 1f && vh > 1f) {
                try {
                    val viewPts = floatArrayOf(0f, 0f, vw, 0f, 0f, vh)
                    val tex = FloatArray(6)
                    f.transformCoordinates2d(
                        Coordinates2d.VIEW, viewPts,
                        Coordinates2d.TEXTURE_NORMALIZED, tex,
                    )
                    val u0 = tex[0] * w; val v0 = tex[1] * h
                    val u1 = tex[2] * w; val v1 = tex[3] * h
                    val u2 = tex[4] * w; val v2 = tex[5] * h
                    val m = floatArrayOf(
                        (u1 - u0) / vw, (u2 - u0) / vh, u0,
                        (v1 - v0) / vw, (v2 - v0) / vh, v0,
                    )
                    if (m.all { it.isFinite() }) depthFromView = m
                } catch (_: Throwable) { /* mapping unavailable this frame */ }
            }
            val pose = FloatArray(16)
            f.camera.pose.toMatrix(pose, 0)
            // Negate column 1 (Y) and column 2 (Z) — OpenCV -> ARCore frame.
            for (i in 4..7) pose[i] = -pose[i]
            for (i in 8..11) pose[i] = -pose[i]
            return ArDepthFrame(
                w, h, depth, conf, fx, fy, cx, cy, pose,
                fxImg = fxImg, fyImg = fyImg, depthFromViewAffine = depthFromView,
                rawDepthMm = rawMm,
            )
        } finally {
            image.close()
        }
    }

    /// Full 4x4 column-major camera pose (raw ARCore world frame, +Y up /
    /// −Z forward — NOT the depth-frame's OpenCV-negated matrix). The
    /// raw-capture recorder stores this for the height anchor/base/top +
    /// the 5 Hz pose-sample trail. Null while not tracking.
    fun currentCameraPose(): FloatArray? {
        val f = trackingFrame() ?: return null
        val m = FloatArray(16)
        f.camera.pose.toMatrix(m, 0)
        return m
    }

    /// One reference RGB JPEG of the current camera frame for a raw-capture
    /// bundle (the estimator never consumes it — it's a human/field
    /// reference). Converts ARCore's YUV_420_888 camera image to NV21 then
    /// JPEG at `quality`. Returns false on any failure (bundle still saves,
    /// just without an rgb_file). Best-effort + fully guarded so a recorder
    /// hiccup can never crash the AR screen.
    fun captureCameraJpeg(dest: java.io.File, quality: Int = 80): Boolean {
        val f = frame ?: return false
        return try {
            val image = f.acquireCameraImage()
            try {
                if (image.format != android.graphics.ImageFormat.YUV_420_888) return false
                val w = image.width
                val h = image.height
                val nv21 = yuv420ToNv21(image, w, h)
                val yuv = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
                java.io.FileOutputStream(dest).use { out ->
                    yuv.compressToJpeg(android.graphics.Rect(0, 0, w, h), quality, out)
                }
                true
            } finally {
                image.close()
            }
        } catch (_: Throwable) {
            false
        }
    }

    /// Pack a YUV_420_888 Image into an NV21 byte array (Y plane, then
    /// interleaved V/U), honouring row + pixel strides. Standard conversion
    /// used only by the raw-capture reference JPEG.
    private fun yuv420ToNv21(image: android.media.Image, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        var pos = 0
        val yRowStride = yPlane.rowStride
        val yPixStride = yPlane.pixelStride
        for (row in 0 until h) {
            var col = row * yRowStride
            for (x in 0 until w) {
                out[pos++] = yBuf.get(col)
                col += yPixStride
            }
        }
        // Chroma: NV21 wants V,U interleaved at half resolution.
        val uRowStride = uPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixStride = vPlane.pixelStride
        val cw = w / 2
        val ch = h / 2
        for (row in 0 until ch) {
            var uCol = row * uRowStride
            var vCol = row * vRowStride
            for (x in 0 until cw) {
                out[pos++] = vBuf.get(vCol)
                out[pos++] = uBuf.get(uCol)
                uCol += uPixStride
                vCol += vPixStride
            }
        }
        return out
    }

    /// World -> screen-pixel projection so two-point markers track the
    /// scene as the camera moves. Uses ARCore's view * projection matrices
    /// (the platform analogue of ARView.project on iOS). Returns null when
    /// the point is behind the camera or off-screen math fails.
    fun projectToScreen(p: Vec3): Pair<Float, Float>? {
        val f = frame ?: return null
        if (viewWidthPx <= 0 || viewHeightPx <= 0) return null
        val view = FloatArray(16)
        val proj = FloatArray(16)
        f.camera.getViewMatrix(view, 0)
        f.camera.getProjectionMatrix(proj, 0, 0.01f, 100f)
        val vp = FloatArray(16)
        multiplyMM(vp, proj, view)
        val clip = FloatArray(4)
        multiplyMV(clip, vp, floatArrayOf(p.x, p.y, p.z, 1f))
        val w = clip[3]
        if (w <= 0f) return null   // behind camera
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        val sx = (ndcX * 0.5f + 0.5f) * viewWidthPx
        val sy = (1f - (ndcY * 0.5f + 0.5f)) * viewHeightPx
        return sx to sy
    }

    // Column-major 4x4 helpers (match android.opengl.Matrix conventions).
    private fun multiplyMM(out: FloatArray, a: FloatArray, b: FloatArray) {
        for (c in 0 until 4) for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += a[k * 4 + r] * b[c * 4 + k]
            out[c * 4 + r] = s
        }
    }

    private fun multiplyMV(out: FloatArray, m: FloatArray, v: FloatArray) {
        for (r in 0 until 4) {
            var s = 0f
            for (k in 0 until 4) s += m[k * 4 + r] * v[k]
            out[r] = s
        }
    }
}
