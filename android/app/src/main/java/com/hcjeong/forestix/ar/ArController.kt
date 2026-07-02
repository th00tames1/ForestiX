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
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.hcjeong.forestix.sensors.ArDepthFrame
import java.nio.ByteOrder
import kotlin.math.sqrt

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
    /// Android equivalent of "device has LiDAR". Drives the LiDAR/AR toggle
    /// default in DistanceMeasureScreen and the DBH method selector's Depth
    /// segment. Backed by Compose snapshot state so a Composable reading it
    /// recomposes when the session reports depth support (otherwise the
    /// Depth segment stayed disabled after entering in caliper mode).
    var supportsDepth: Boolean by mutableStateOf(false)
        internal set

    /// When true (LiDAR mode), screenCenterHit takes the nearest hit of any
    /// kind — including Depth API points. When false (AR mode), it filters
    /// to estimated-plane hits only, the analogue of the iOS raycaster's
    /// `preferLiDARMesh` flag. Pinned false on devices without the Depth API.
    @Volatile var preferDepth: Boolean = true

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

    /// Snapshot the current ARCore point cloud as world-space feature points
    /// keyed by their stable point id (so a sweep can accumulate a de-duped
    /// union). Empty when nothing is tracked. Mirrors the iOS VIO feature
    /// stream (which the AR-motion DBH method circle-fits).
    fun acquireFeaturePointsWorld(): List<Pair<Int, Vec3>> {
        val f = frame ?: return emptyList()
        val pc = try { f.acquirePointCloud() } catch (_: Throwable) { return emptyList() }
        return try {
            val pts = pc.points          // 4 floats per point: x,y,z,confidence (world)
            val ids = pc.ids             // one int id per point
            val n = ids.limit()
            val out = ArrayList<Pair<Int, Vec3>>(n)
            for (m in 0 until n) {
                val base = m * 4
                out.add(ids.get(m) to Vec3(pts.get(base), pts.get(base + 1), pts.get(base + 2)))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        } finally {
            pc.release()
        }
    }

    private fun ready(): Boolean {
        val f = frame ?: return false
        return f.camera.trackingState == TrackingState.TRACKING && viewWidthPx > 1 && viewHeightPx > 1
    }

    /// Hit the scene at the screen centre. Returns the nearest hit's world
    /// translation, or null when tracking isn't ready / nothing is hit.
    /// Callers must treat null as "tracking not ready", never substitute
    /// the camera position (which biases measurements) — same contract as
    /// the iOS raycaster.
    fun screenCenterHit(): Vec3? {
        if (!ready()) return null
        val f = frame ?: return null
        val cx = viewWidthPx / 2f
        val cy = viewHeightPx / 2f
        val hits = try { f.hitTest(cx, cy) } catch (_: Throwable) { return null }
        // LiDAR mode: nearest hit of any kind (depth points + planes).
        // AR mode: estimated-plane hits only, so the behaviour matches the
        // device that has no Depth API.
        val hit = if (preferDepth) hits.firstOrNull()
        else hits.firstOrNull { it.trackable is Plane } ?: hits.firstOrNull()
        hit ?: return null
        val t = hit.hitPose.translation
        return Vec3(t[0], t[1], t[2])
    }

    /// Project the camera-forward ray to a world point at exactly `d`
    /// metres of horizontal distance — fallback when no surface is hit
    /// (sky / canopy). Direct port of forwardPointAtHorizontalDistance.
    fun forwardPointAtHorizontalDistance(d: Float): Vec3? {
        val f = frame ?: return null
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
        val f = frame ?: return null
        val t = f.camera.pose.translation
        return Vec3(t[0], t[1], t[2])
    }

    /// Elevation angle (radians) of the camera's forward aim ray above the
    /// horizon — the Android analogue of the iOS IMU pitch α used by the
    /// height walk-off tangent. Positive = aiming up. Forward = -zAxis.
    fun cameraForwardElevationRad(): Float? {
        val f = frame ?: return null
        val z = f.camera.pose.zAxis
        val fwd = Vec3(-z[0], -z[1], -z[2])
        val horiz = sqrt(fwd.x * fwd.x + fwd.z * fwd.z)
        return kotlin.math.atan2(fwd.y, horiz)
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

    /// True when the latest frame is tracking — for the dev HUD.
    fun trackingOk(): Boolean = frame?.camera?.trackingState == TrackingState.TRACKING

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
            val depth = FloatArray(w * h)
            val conf = ByteArray(w * h)
            for (y in 0 until h) {
                val base = y * rowStrideShorts
                for (x in 0 until w) {
                    val mm = shorts.get(base + x).toInt() and 0xFFFF
                    val idx = y * w + x
                    if (mm in 1..8000) { depth[idx] = mm / 1000f; conf[idx] = 2 }
                    else { depth[idx] = 0f; conf[idx] = 0 }
                }
            }
            // Scale the full-res texture intrinsics down to the depth image.
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
            val pose = FloatArray(16)
            f.camera.pose.toMatrix(pose, 0)
            // Negate column 1 (Y) and column 2 (Z) — OpenCV -> ARCore frame.
            for (i in 4..7) pose[i] = -pose[i]
            for (i in 8..11) pose[i] = -pose[i]
            return ArDepthFrame(w, h, depth, conf, fx, fy, cx, cy, pose)
        } finally {
            image.close()
        }
    }

    /// Unproject a screen tap (pixels) to a WORLD-space ray direction. Only
    /// the angle between two such rays matters for the AR-caliper, and that
    /// angle is invariant under any consistent frame, so we always use the
    /// world frame here. NDC = (2·sx/W − 1, 1 − 2·sy/H); unproject both clip
    /// depths (near z=−1, far z=+1) through inverse(proj·view); direction =
    /// normalize(worldFar − worldNear). Validate: the screen centre must map
    /// to roughly the camera forward (−zAxis).
    fun screenToWorldRayDirection(sx: Float, sy: Float, viewW: Int, viewH: Int): Vec3? {
        val f = frame ?: return null
        if (viewW <= 0 || viewH <= 0) return null
        val view = FloatArray(16)
        val proj = FloatArray(16)
        f.camera.getViewMatrix(view, 0)
        f.camera.getProjectionMatrix(proj, 0, 0.01f, 100f)
        val vp = FloatArray(16)
        multiplyMM(vp, proj, view)
        val invVp = invertM(vp) ?: return null

        val ndcX = 2f * sx / viewW - 1f
        val ndcY = 1f - 2f * sy / viewH
        val near = unprojectNdc(invVp, ndcX, ndcY, -1f) ?: return null
        val far = unprojectNdc(invVp, ndcX, ndcY, 1f) ?: return null
        val dir = (far - near).normalize()
        if (dir.length() < 1e-6f || !dir.x.isFinite()) return null
        return dir
    }

    /// Unproject a clip-space NDC point at depth `z` to a world point via the
    /// supplied inverse(proj·view); null if the result is at infinity (w≈0).
    private fun unprojectNdc(invVp: FloatArray, ndcX: Float, ndcY: Float, z: Float): Vec3? {
        val out = FloatArray(4)
        multiplyMV(out, invVp, floatArrayOf(ndcX, ndcY, z, 1f))
        val w = out[3]
        if (kotlin.math.abs(w) < 1e-9f) return null
        val inv = 1f / w
        return Vec3(out[0] * inv, out[1] * inv, out[2] * inv)
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

    /// Inverse of a column-major 4x4 (cofactor method, matching the layout
    /// android.opengl.Matrix / ARCore use). Returns null when singular.
    /// Self-contained so the ray unprojection needs no kotlin-math dep.
    private fun invertM(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        var det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (det == 0f || !det.isFinite()) return null
        det = 1f / det
        for (i in 0 until 16) inv[i] *= det
        return inv
    }
}
