// App-scoped AR session hub — ONE ARCore session (inside one ARSceneView)
// shared by every AR measurement screen, following the LocationService
// static-singleton pattern.
//
// WHY: each screen used to build its own ArController + ARScene, so the
// ARCore world (and any world-space geometry, like the sampling plot) died
// on every navigation. Hoisting the session here gives all AR screens one
// continuous world frame: a plot centre anchored on the sampling screen
// stays valid — and renders — inside DBH and Height.
//
// LIFECYCLE: the shared ARSceneView is driven by a hub-owned
// LifecycleRegistry, NOT the activity lifecycle directly. The registry is
// RESUMED only while an AR screen is attached AND the activity is resumed;
// otherwise it drops to CREATED, which pauses the ARCore session and stops
// the render loop (camera off on the map home). It never drops below
// CREATED until the activity dies, so SceneView's internal ARCore.create()
// runs exactly once per session (calling it twice would leak a camera-
// holding session). ARCore keeps anchors across pause/resume; after a LONG
// pause relocalization may degrade and a resumed anchor can take a few
// seconds to re-pin (accepted trade-off, documented behaviour).
//
// ACTIVE SAMPLING PLOT: {ARCore Anchor + radius + placedAt}. The centre is
// a REAL session anchor, so ARCore's map corrections keep the pole/ring
// pinned to the physical spot (the old raw-Vec3 marker drifted with VIO
// error). The plot lives only as long as the AR session: it is NOT
// persisted across app restarts (the ARCore world it is defined in is
// gone) and clears itself if its anchor stops tracking permanently.
//
// RENDERING: the hub owns the plot's scene nodes directly and repositions
// them from the anchor pose every frame — screens don't carry the plot in
// their marker lists. Per-screen style: OWNER (sampling, full alpha),
// SUBDUED (DBH/Height overlay, 0.5 alpha, non-interactive), HIDDEN.
//
// PERF (sampling-lag fixes riding the same refactor):
//  - Marker colour MaterialInstances are cached per RGBA. Node.destroy()
//    frees geometry but NOT material instances, so the old rebuild path
//    leaked one instance per marker per ring-radius step.
//  - A radius change rewrites the ring's vertex buffer IN PLACE — no node,
//    geometry-buffer or material churn at all (see setPlotRadius).
//  - The per-frame plot pump is a no-op unless the anchor's corrected pose
//    actually moved (see applyPlotPose).
//  - Screens can turn the session's Depth API and the plane renderer off
//    (ARCore's ML depth-from-motion + per-frame plane geometry updates
//    were the dominant frame cost while walking a plot). The
//    sampling/plot-creation screens use this: depth serves the centre
//    hit-test while aiming and is switched off — with the plane grid — the
//    moment the centre lands.
//
// DEPTH OCCLUSION: per-screen flag that flips SceneView's
// ARCameraStream.isDepthOcclusionEnabled, so virtual geometry renders
// BEHIND real surfaces (the camera quad writes per-pixel scene depth from
// the ARCore depth image and depth-tests the scene against it). Requires
// Config.DepthMode.AUTOMATIC.
//
// NO SCREEN ENABLES IT TODAY. The sampling / plot-creation screens did, so
// the boundary ring would pass behind trunks, and that is what broke plot
// rendering on Android (field round 9). Two reasons, both structural:
//
//  1. The ring lies ON the ground and is viewed at grazing incidence from
//     eye height. Depth-testing a ground-coplanar surface against ARCore's
//     depth-from-motion image is a coin flip there: a few centimetres of
//     depth error along a near-tangential view ray moves the estimated
//     ground surface metres along the ray, so the ring — and the base of
//     the centre pole — was occluded by the very ground it sits on, in a
//     pattern that changed every frame. That is the flicker, and on a
//     low-texture patch it is a plot that never draws at all. iOS is
//     unaffected because ARKit's LiDAR scene depth is metric and stable.
//  2. It forces the ARCore depth pipeline to produce (and the camera
//     stream to consume) a depth image on EVERY frame for the whole
//     session, on phones that mostly have no depth sensor. That is the
//     plot-setup lag.
//
// DBH/Height never enabled it either — their treetop spheres + subdued ring
// overlay must stay visible through canopy. The flag reverts on every
// attach, so a future screen can still ask for it.

package com.hcjeong.forestix.ar

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Anchor
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.GeometryNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import kotlin.math.abs

object ArSessionHub {

    /// Logcat tag for the hub's own warnings (same convention as the rest of
    /// the app: SurveyBoundaryStore, BoundaryFilePicker, ForestixLogger).
    private const val TAG = "ArSessionHub"

    /// The ONE ArController every AR screen reads (hits, poses, depth
    /// frames). Screens must use this instead of constructing their own —
    /// only the hub's controller is fed frames from the shared session.
    val controller = ArController()

    /// Persistence hook for the Depth-API capability verdict — wired once
    /// by AppEnvironment to AppSettings (the hub has no settings access).
    /// Invoked with the DEFINITIVE `isDepthModeSupported` answer on every
    /// session (re)configure, from ANY AR screen: a negative lets the DBH
    /// gate show its unsupported blocker on future entries without
    /// starting a probe session, and a later positive (e.g. an ARCore
    /// update adding the device) clears that cached negative.
    var onDepthVerdict: ((Boolean) -> Unit)? = null

    /// How an attached screen renders the active sampling plot.
    enum class PlotOverlay {
        /// No plot geometry at all (Distance, boundary, offset, calibration).
        HIDDEN,

        /// Subdued ring + centre pole at 0.5 alpha under the screen's own
        /// measurement markers (DBH / Height).
        SUBDUED,

        /// Full-alpha plot rendering (the sampling screen itself).
        OWNER,
    }

    /// Active sampling plot: a REAL ARCore anchor + when it was placed.
    /// Radius lives in [plotRadiusM] so the slider default survives
    /// placement/reset cycles. Never persisted across app restarts.
    class ActivePlot(val anchor: Anchor, val placedAt: Long)

    /// Snapshot state so screens recompose when the plot appears/clears.
    var activePlot by mutableStateOf<ActivePlot?>(null)
        private set

    /// Sampling-plot radius (m). Also the slider position before placement.
    var plotRadiusM by mutableDoubleStateOf(8.0)
        private set

    /// Persisted cruise `Plot` whose centre the current anchor marks —
    /// stamped by the cruise Start-plot save, null for quick-measure
    /// rings. Lets the plot mini-map trust the anchor path only when the
    /// anchor actually belongs to the plot being measured (an "Add tree"
    /// can target an OLDER open plot than the last-placed ring). Mirror
    /// of iOS ActiveSamplingPlot.linkedCruisePlotID.
    @Volatile
    var linkedCruisePlotId: java.util.UUID? = null
        private set

    /// Associate the placed ring with the cruise Plot it was just saved
    /// as. No-op while nothing is placed.
    fun linkPlot(cruisePlotId: java.util.UUID) {
        if (activePlot == null) return
        linkedCruisePlotId = cruisePlotId
    }

    // MARK: - Raw-depth recorder arm (REF-COUNTED)

    /// `controller` is process-wide, and navigation-compose composes the
    /// INCOMING destination BEFORE disposing the outgoing one. A plain
    /// `onDispose { controller.captureRawDepth = false }` therefore let the
    /// DBH screen switch the recorder OFF for the whole Height session in the
    /// "Full measurement" DBH→Height chain — every chained height bundle
    /// silently lost its base/top aim frames while still self-checking
    /// "pass". Same hazard the attach-token guard already fixes for
    /// updateScreenConfig/detach; here a ref count is the natural shape: a
    /// disposing screen releases only its OWN token and can never clear state
    /// it no longer owns.
    private val rawDepthArms = HashSet<Int>()
    private var rawDepthArmSeq = 0

    /// True while any attached screen holds the arm — the scan screens' REC
    /// indicator reads THIS, so it shows the recorder's real state rather
    /// than one screen's wish. OBSERVABLE (snapshot state): the pill used to
    /// be fed from the Settings flag and stayed red through an arm clobber
    /// while nothing was being recorded.
    var rawDepthArmed by mutableStateOf(false)
        private set

    /// Arm the native u16 depth copy on every acquired frame. Returns the
    /// token that [releaseRawDepth] must present.
    @Synchronized
    fun armRawDepth(): Int {
        val token = ++rawDepthArmSeq
        rawDepthArms.add(token)
        syncRawDepthArm()
        return token
    }

    /// Release one arm. The recorder only switches off once the LAST holder
    /// has released.
    @Synchronized
    fun releaseRawDepth(token: Int) {
        if (rawDepthArms.remove(token)) syncRawDepthArm()
    }

    /// The ONE writer of the recorder flag + its observable mirror: both are
    /// always re-derived from the live token set, never set independently.
    @Synchronized
    private fun syncRawDepthArm() {
        val armed = rawDepthArms.isNotEmpty()
        controller.captureRawDepth = armed
        rawDepthArmed = armed
    }

    // MARK: - Internals

    private var sceneView: SharedArSceneView? = null
    private var hostActivity: ComponentActivity? = null
    private var lifecycleOwner: HubLifecycleOwner? = null

    /// Attach tokens: during a navigation transition the NEW screen
    /// attaches before the OLD one disposes, so detach() only acts when
    /// the token still names the current attachment.
    private var attachSeq = 0
    private var attachedToken: Int? = null

    // Per-screen session wishes (applied at attach + on live updates).
    private var screenWantsDepth = true
    private var screenWantsOcclusion = false
    private var overlay = PlotOverlay.HIDDEN

    // Generic marker pipeline (one screen's markers at a time).
    private val markerNodes = mutableListOf<Node>()
    private val builtMarkers = mutableListOf<ArSceneMarker>()
    private val scalingNodes = mutableListOf<Pair<Node, Vec3>>()

    // Plot overlay nodes. Held BY ROLE, not by list index: the old
    // index-addressed list only worked while the set was exactly four nodes
    // in a fixed order, and the radius rebuild silently depended on the ring
    // staying last. `plotNodes` is now just the iteration/teardown list.
    private val plotNodes = mutableListOf<Node>()
    private var plotCentreNode: Node? = null
    private var plotPoleNode: Node? = null
    private var plotTopNode: Node? = null
    private var plotRingHaloNode: GeometryNode? = null
    private var plotRingNode: GeometryNode? = null

    // Last anchor translation actually pushed into the plot nodes. The
    // per-frame pump compares against it and does nothing when the corrected
    // pose has not moved — each node's `worldPosition` setter rebuilds a
    // Transform and walks the Filament transform manager, so doing it for
    // five nodes on every frame was pure waste (see updatePlotNodes).
    private var plotPoseX = Float.NaN
    private var plotPoseY = Float.NaN
    private var plotPoseZ = Float.NaN

    // RGBA -> MaterialInstance cache. Node.destroy() does NOT free material
    // instances (only the view-level MaterialLoader.destroy() does), so
    // creating one per rebuild leaked instances for the whole app session.
    private data class ColorKey(val r: Float, val g: Float, val b: Float, val a: Float)
    private val materialCache = HashMap<ColorKey, MaterialInstance>()

    // MARK: - View / lifecycle plumbing

    private class HubLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /// Mirrors the ACTIVITY lifecycle into the view registry (pause when the
    /// app backgrounds while an AR screen is up; destroy with the activity).
    private val activityObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = syncLifecycle()
        override fun onPause(owner: LifecycleOwner) = syncLifecycle()
        override fun onDestroy(owner: LifecycleOwner) = teardown()
    }

    private fun Context.findActivity(): ComponentActivity? {
        var c: Context = this
        while (true) {
            if (c is ComponentActivity) return c
            c = (c as? ContextWrapper)?.baseContext ?: return null
        }
    }

    /// Get (or lazily build) the shared AR view. Callers have already
    /// passed the camera-permission + ARCore-availability gates.
    fun obtainView(context: Context): ARSceneView {
        sceneView?.let { existing ->
            if (!existing.destroyed && hostActivity === context.findActivity()) return existing
            // Stale view (activity recreated): its observers already
            // destroyed it; just drop our references and rebuild.
            if (!existing.destroyed) runCatching { existing.destroy() }
            dropViewState()
        }
        val activity = requireNotNull(context.findActivity()) { "AR screens need a ComponentActivity host" }
        val owner = HubLifecycleOwner()
        val view = SharedArSceneView(
            context = activity,
            hostActivity = activity,
            hostLifecycle = owner.registry,
            configureSession = { session, config -> applySessionConfig(session, config) },
            onFrame = { session, frame -> onSessionFrame(session, frame) },
        )
        sceneView = view
        hostActivity = activity
        lifecycleOwner = owner
        activity.lifecycle.addObserver(activityObserver)
        return view
    }

    /// A screen enters: record its session wishes, wake the session, and
    /// restyle the plot overlay. Returns the token detach() must present.
    fun attach(
        context: Context,
        preferDepth: Boolean,
        enableDepth: Boolean,
        planeRendererEnabled: Boolean,
        plotOverlay: PlotOverlay,
        depthOcclusion: Boolean = false,
    ): Int {
        obtainView(context)
        controller.preferDepth = preferDepth
        screenWantsDepth = enableDepth
        screenWantsOcclusion = depthOcclusion
        overlay = plotOverlay
        clearMarkerNodes()
        sceneView?.planeRenderer?.isEnabled = planeRendererEnabled
        reconfigureSession()
        applyOcclusion()
        rebuildPlotNodes()
        val token = ++attachSeq
        attachedToken = token
        syncLifecycle()
        return token
    }

    /// Live per-screen config change (sampling flips depth + plane grid off
    /// once the centre is placed). No-op when values are already current.
    /// Token-gated: an EXITING screen's effects can still fire during the
    /// navigation transition and must not clobber the new screen's config.
    fun updateScreenConfig(
        token: Int,
        preferDepth: Boolean,
        enableDepth: Boolean,
        planeRendererEnabled: Boolean,
        plotOverlay: PlotOverlay,
        depthOcclusion: Boolean = false,
    ) {
        if (attachedToken != token) return
        controller.preferDepth = preferDepth
        sceneView?.planeRenderer?.let { if (it.isEnabled != planeRendererEnabled) it.isEnabled = planeRendererEnabled }
        if (screenWantsDepth != enableDepth) {
            screenWantsDepth = enableDepth
            reconfigureSession()
        }
        screenWantsOcclusion = depthOcclusion
        applyOcclusion()
        if (overlay != plotOverlay) {
            overlay = plotOverlay
            rebuildPlotNodes()
        }
    }

    /// A screen leaves. Only the CURRENT attachment may pause the session —
    /// during navigation the next AR screen attached before this dispose.
    fun detach(token: Int) {
        if (attachedToken != token) return
        attachedToken = null
        clearMarkerNodes()
        controller.frame = null
        syncLifecycle()
    }

    private fun syncLifecycle() {
        val registry = lifecycleOwner?.registry ?: return
        val activity = hostActivity ?: return
        val target =
            if (attachedToken != null && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                Lifecycle.State.RESUMED
            } else {
                Lifecycle.State.CREATED
            }
        if (registry.currentState != target) registry.currentState = target
    }

    /// Activity is going away: let the view destroy itself (engine, ARCore
    /// session, materials) through its observers, then forget everything.
    /// The active plot dies with the session — its world frame is gone.
    private fun teardown() {
        attachedToken = null
        activePlot?.anchor?.let { runCatching { it.detach() } }
        activePlot = null
        linkedCruisePlotId = null
        lifecycleOwner?.registry?.let { registry ->
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
        dropViewState()
    }

    private fun dropViewState() {
        // The raw-depth ARM TOKENS are deliberately NOT cleared here: they
        // belong to the SCREENS that took them, not to the view. Dropping the
        // set on an activity recreation (rotation, returning from a permission
        // dialog) orphaned the incoming composition's freshly-taken token —
        // the recorder went off and never came back, so every later capture on
        // that screen failed until the operator navigated away and back.
        // Only the view/session state dies here; the arm is re-derived from
        // whatever tokens are still registered.
        syncRawDepthArm()
        hostActivity?.lifecycle?.removeObserver(activityObserver)
        sceneView = null
        hostActivity = null
        lifecycleOwner = null
        markerNodes.clear()
        builtMarkers.clear()
        scalingNodes.clear()
        plotNodes.clear()
        plotCentreNode = null
        plotPoleNode = null
        plotTopNode = null
        plotRingHaloNode = null
        plotRingNode = null
        plotPoseX = Float.NaN; plotPoseY = Float.NaN; plotPoseZ = Float.NaN
        materialCache.clear()
        controller.frame = null
        controller.session = null
    }

    // MARK: - Session config

    /// Applied both by SceneView's create-time sessionConfiguration hook and
    /// by manual reconfigures on screen switches. Depth runs only when the
    /// attached screen consumes it — ARCore's ML depth pipeline is a large
    /// per-frame cost on non-ToF devices.
    private fun applySessionConfig(session: Session, config: Config) {
        val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        controller.supportsDepth = depthSupported
        controller.depthSupportKnown = true
        onDepthVerdict?.invoke(depthSupported)
        config.depthMode =
            if (depthSupported && screenWantsDepth) Config.DepthMode.AUTOMATIC
            else Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        // Depth support may only become known here (first session create),
        // so re-derive the occlusion material choice with it.
        applyOcclusion()
    }

    private fun reconfigureSession() {
        val session = sceneView?.session ?: return   // create-time hook will apply
        runCatching {
            val config = session.config
            applySessionConfig(session, config)
            session.configure(config)
        }
    }

    /// Flip SceneView's camera-stream material between flat and
    /// depth-occlusion (ARCameraStream.isDepthOcclusionEnabled). Only
    /// meaningful while the session actually produces depth images —
    /// with DepthMode.DISABLED the occlusion material's depth texture
    /// would never update, so the flag is gated on the screen's depth
    /// wish AND device support. Idempotent (the stream setter no-ops on
    /// an unchanged value), called from attach / live updates / the
    /// session-config hook.
    private fun applyOcclusion() {
        val view = sceneView ?: return
        val enable = screenWantsOcclusion && screenWantsDepth && controller.supportsDepth
        view.cameraStream?.let {
            if (it.isDepthOcclusionEnabled != enable) it.isDepthOcclusionEnabled = enable
        }
    }

    // MARK: - Per-frame pump

    private fun onSessionFrame(session: Session, frame: Frame) {
        val view = sceneView ?: return
        if (view.width > 0 && view.height > 0) {
            controller.viewWidthPx = view.width
            controller.viewHeightPx = view.height
        }
        controller.onUpdate(session, frame)
        // Distance-compensated marker scaling (same policy as before the
        // shared-session refactor — see MARKER_SCALE_* docs below).
        if (scalingNodes.isNotEmpty()) {
            val cam = frame.camera.pose
            val camPos = Vec3(cam.tx(), cam.ty(), cam.tz())
            scalingNodes.forEach { (node, worldPos) ->
                val d = distance(camPos, worldPos)
                val factor = (d / MARKER_SCALE_REFERENCE_M)
                    .coerceIn(MARKER_SCALE_MIN, MARKER_SCALE_MAX)
                node.scale = Float3(factor, factor, factor)
            }
        }
        updatePlotNodes()
    }

    // MARK: - Generic marker pipeline (per-screen markers)

    /// Sync a screen's marker list to scene nodes. Position-only changes
    /// MOVE the existing nodes; Filament geometry is rebuilt only when the
    /// marker STRUCTURE (count, shape size, colour, scaling flag) changes.
    /// Materials come from the RGBA cache — structural rebuilds allocate
    /// geometry only. Token-gated: an exiting screen's marker effects can
    /// still fire during the navigation transition (e.g. DBH's preview loop
    /// nulling its cylinder) and must not clobber the new screen's markers.
    fun syncMarkers(token: Int, markers: List<ArSceneMarker>) {
        if (attachedToken != token) return
        val view = sceneView ?: return
        val moveOnly = markers.size == builtMarkers.size && markers.indices.all { i ->
            val new = markers[i]; val old = builtMarkers[i]
            new.shape == old.shape && new.colorRGBA.contentEquals(old.colorRGBA) &&
                new.scalesWithDistance == old.scalesWithDistance
        }
        if (moveOnly) {
            scalingNodes.clear()
            markers.forEachIndexed { i, marker ->
                markerNodes[i].worldPosition = Float3(
                    marker.worldPosition.x, marker.worldPosition.y, marker.worldPosition.z)
                if (marker.scalesWithDistance) scalingNodes.add(markerNodes[i] to marker.worldPosition)
            }
        } else {
            clearMarkerNodes()
            markers.forEach { marker ->
                val node = buildMarkerNode(view, marker.shape, marker.colorRGBA)
                node.worldPosition = Float3(
                    marker.worldPosition.x, marker.worldPosition.y, marker.worldPosition.z)
                markerNodes.add(node)
                view.addChildNode(node)
                if (marker.scalesWithDistance) scalingNodes.add(node to marker.worldPosition)
            }
        }
        builtMarkers.clear()
        builtMarkers.addAll(markers)
    }

    private fun clearMarkerNodes() {
        val view = sceneView
        markerNodes.forEach { node ->
            view?.removeChildNode(node)
            runCatching { node.destroy() }
        }
        markerNodes.clear()
        scalingNodes.clear()
        builtMarkers.clear()
    }

    private fun materialFor(view: ARSceneView, rgba: FloatArray): MaterialInstance {
        val key = ColorKey(rgba[0], rgba[1], rgba[2], rgba[3])
        return materialCache.getOrPut(key) {
            view.materialLoader.createColorInstance(
                color = Float4(rgba[0], rgba[1], rgba[2], rgba[3]),
                metallic = 0f, roughness = 1f, reflectance = 0f,
            )
        }
    }

    private fun buildMarkerNode(view: ARSceneView, shape: MarkerShape, rgba: FloatArray): Node {
        val engine = view.engine
        val material = materialFor(view, rgba)
        return when (shape) {
            is MarkerShape.Sphere -> SphereNode(engine, radius = shape.radiusM, materialInstance = material)
            is MarkerShape.Cylinder -> CylinderNode(engine, radius = shape.radiusM, height = shape.heightM, materialInstance = material)
            is MarkerShape.Ring -> buildRingNode(engine, shape.radiusM, shape.thicknessM, material)
        }
    }

    // MARK: - Active sampling plot

    /// Anchor the plot centre at `center` (world). Replaces any previous
    /// plot. Returns false while the session isn't running (no hit could
    /// have been produced then anyway).
    fun placePlot(center: Vec3): Boolean {
        val session = sceneView?.session ?: return false
        val anchor = runCatching {
            session.createAnchor(Pose.makeTranslation(center.x, center.y, center.z))
        }.getOrNull() ?: return false
        activePlot?.anchor?.let { runCatching { it.detach() } }
        activePlot = ActivePlot(anchor, System.currentTimeMillis())
        linkedCruisePlotId = null   // fresh ring — not yet saved as a cruise plot
        rebuildPlotNodes()
        return true
    }

    /// Update the plot radius (m). Rewrites the EXISTING ring geometry's
    /// vertex buffer in place — the node, its Filament vertex/index buffers
    /// and its material all survive.
    ///
    /// This was the dominant lag on the plot-setup screens. The old path
    /// destroyed the ring node and built a fresh 96-segment annulus for every
    /// slider step: 192 Vertex objects, 1 152 BOXED Int indices (the index
    /// list was `ArrayList<Int>` fed by `addAll(listOf(...))`, so each segment
    /// also allocated two throwaway lists), a new Filament VertexBuffer +
    /// IndexBuffer, a renderable teardown and a scene-graph add — all on the
    /// render thread, all while the cruiser drags a slider. The segment count
    /// never changes, so the indices never change either: only the 192
    /// positions do.
    ///
    /// THE STORED RADIUS IS COMMITTED LAST, and only once the drawn ring has
    /// actually moved. It used to be assigned FIRST, with the two geometry
    /// writes inside a bare `runCatching {}` that had no failure branch: a
    /// throw from Filament — or a null ring node — left [plotRadiusM] saying
    /// 12 m while the circle on the ground stayed at 8 m, and the
    /// early-return above then made every later attempt at that same value a
    /// no-op, so the disagreement was permanent AND silent. Everything
    /// downstream trusts this number (the plot mini-map, the in/out call,
    /// the saved cruise Plot), so it may not run ahead of the geometry.
    fun setPlotRadius(radiusM: Double) {
        if (plotRadiusM == radiusM) return
        val halo = plotRingHaloNode
        val ring = plotRingNode
        // Nothing is drawn (no plot placed yet, or the overlay is HIDDEN):
        // the radius is just the slider position, and rebuildPlotNodes reads
        // it when the ring appears — there is no geometry to disagree with.
        if (halo == null && ring == null) {
            plotRadiusM = radiusM
            return
        }
        // Half-built pair (the two are created and destroyed together, so
        // this means an earlier build broke). Don't write half the ring:
        // say so, then rebuild from the new radius, which restores both.
        if (halo == null || ring == null) {
            Log.w(TAG, "plot ring half-built (halo=${halo != null}, rim=${ring != null}) — " +
                "rebuilding at $radiusM m instead of a partial geometry write")
            plotRadiusM = radiusM
            runCatching { rebuildPlotNodes() }
            return
        }
        val r = radiusM.toFloat()
        runCatching {
            halo.updateGeometry(vertices = ringVertices(r, HALO_THICKNESS_M))
            ring.updateGeometry(vertices = ringVertices(r, RING_THICKNESS_M))
        }.onSuccess {
            plotRadiusM = radiusM
        }.onFailure { t ->
            // Keep the old radius: it is the one the cruiser can still SEE.
            // (The slider is bound to plotRadiusM, so it snaps back — the
            // failure is visible on screen, not only in logcat.) The halo
            // may already have taken the new radius before the rim threw, so
            // redraw both from the value we kept; a rebuild that fails too
            // has nothing left to try, and the warning above stands.
            Log.w(TAG, "plot ring geometry update to $radiusM m failed — " +
                "keeping $plotRadiusM m so the stored radius matches the drawn ring", t)
            runCatching { rebuildPlotNodes() }
        }
    }

    /// Drop the plot (sampling Reset, or its anchor stopped tracking
    /// permanently).
    fun clearPlot() {
        activePlot?.anchor?.let { runCatching { it.detach() } }
        activePlot = null
        linkedCruisePlotId = null
        destroyPlotNodes()
    }

    /// Drift-corrected plot centre — the anchor's CURRENT pose translation.
    /// Null when no plot exists or its anchor has stopped for good.
    fun plotCenterWorld(): Vec3? {
        val plot = activePlot ?: return null
        if (plot.anchor.trackingState == TrackingState.STOPPED) return null
        val pose = plot.anchor.pose
        return Vec3(pose.tx(), pose.ty(), pose.tz())
    }

    // Plot marker palette — same geometry/colours as the sampling screen's
    // original marker list; SUBDUED halves the alpha.
    private val CENTRE_RGB = floatArrayOf(1f, 0.25f, 0.25f)
    private val POLE_RGB = floatArrayOf(1f, 1f, 1f)
    private val TOP_RGB = floatArrayOf(1f, 0.85f, 0.15f)
    private val RING_RGB = floatArrayOf(0.2f, 0.85f, 1f)

    /// Dark halo drawn UNDER the bright rim. ANDROID-ONLY — iOS draws the
    /// sampling plot as a single cyan ring (SamplingPlotScreen /
    /// ActiveSamplingPlot, one .ring marker, no halo), so this is an
    /// addition here, not parity.
    ///
    /// It earns its place: iOS gives its ring an UnlitMaterial, so the
    /// stroke keeps its colour whatever the light is doing. SceneView has
    /// no unlit colour material — the rim is shaded by the scene, and a lit
    /// bright stroke washes out on sun-lit litter and sinks into deep shade
    /// at the far side of the same plot. The dark halo gives it its own
    /// contrast wherever the ground happens to be.
    private val RING_HALO_RGB = floatArrayOf(0.03f, 0.06f, 0.08f)

    private fun plotAlpha(): Float = if (overlay == PlotOverlay.OWNER) 1f else 0.5f

    private fun plotColor(rgb: FloatArray) = floatArrayOf(rgb[0], rgb[1], rgb[2], plotAlpha())

    /// (Re)build the plot's nodes for the current overlay style. Cheap and
    /// rare: once per screen attach / placement / style change — a radius
    /// change goes through setPlotRadius, which never rebuilds anything.
    private fun rebuildPlotNodes() {
        destroyPlotNodes()
        val view = sceneView ?: return
        val plot = activePlot ?: return
        if (overlay == PlotOverlay.HIDDEN) return
        val r = plotRadiusM.toFloat()
        val centre = buildMarkerNode(view, MarkerShape.Sphere(0.07f), plotColor(CENTRE_RGB))
        val pole = buildMarkerNode(view, MarkerShape.Cylinder(0.05f, 1.2f), plotColor(POLE_RGB))
        val top = buildMarkerNode(view, MarkerShape.Sphere(0.12f), plotColor(TOP_RGB))
        val halo = buildRingNode(view.engine, r, HALO_THICKNESS_M, materialFor(view, plotColor(RING_HALO_RGB)))
        val ring = buildRingNode(view.engine, r, RING_THICKNESS_M, materialFor(view, plotColor(RING_RGB)))
        plotCentreNode = centre
        plotPoleNode = pole
        plotTopNode = top
        plotRingHaloNode = halo
        plotRingNode = ring
        // The anchor's pose is readable while merely PAUSED (it is the last
        // known one), so the geometry is placed and shown straight away —
        // waiting for TRACKING left the plot invisible on entry whenever the
        // session was mid-relocalization.
        val pose = plot.anchor.pose
        applyPlotPose(pose.tx(), pose.ty(), pose.tz(), force = true)
        listOf(centre, pole, top, halo, ring).forEach { node ->
            plotNodes.add(node)
            view.addChildNode(node)
        }
    }

    /// Push the anchor translation into the plot nodes. Skipped entirely
    /// when the corrected pose has not moved by ≥ 1 mm since the last push.
    private fun applyPlotPose(x: Float, y: Float, z: Float, force: Boolean = false) {
        if (!force &&
            abs(x - plotPoseX) < POSE_EPSILON_M &&
            abs(y - plotPoseY) < POSE_EPSILON_M &&
            abs(z - plotPoseZ) < POSE_EPSILON_M
        ) return
        plotCentreNode?.worldPosition = Float3(x, y, z)
        plotPoleNode?.worldPosition = Float3(x, y + 0.6f, z)
        plotTopNode?.worldPosition = Float3(x, y + 1.2f, z)
        plotRingHaloNode?.worldPosition = Float3(x, y + HALO_Y_M, z)
        plotRingNode?.worldPosition = Float3(x, y + RING_Y_M, z)
        plotPoseX = x; plotPoseY = y; plotPoseZ = z
    }

    private fun destroyPlotNodes() {
        val view = sceneView
        plotNodes.forEach { node ->
            view?.removeChildNode(node)
            runCatching { node.destroy() }
        }
        plotNodes.clear()
        plotCentreNode = null
        plotPoleNode = null
        plotTopNode = null
        plotRingHaloNode = null
        plotRingNode = null
        plotPoseX = Float.NaN; plotPoseY = Float.NaN; plotPoseZ = Float.NaN
    }

    /// Per-frame: follow the anchor's corrected pose; self-clear when ARCore
    /// stops the anchor permanently.
    ///
    /// A PAUSED anchor does NOT hide the plot any more. That single line was
    /// the flicker: ARCore drops an anchor to PAUSED on every routine
    /// tracking dip — a fast pan, a low-texture patch of litter, sun in the
    /// lens, the camera swinging past a trunk — which is a continuous
    /// occurrence while somebody walks a plot boundary. The pillar and ring
    /// blinked on and off with it, and on a bad stretch never came back for
    /// long enough to be seen. PAUSED means "the pose is not being corrected
    /// right now", not "the plot is gone": the world coordinates from the
    /// last update are still the best estimate, so the geometry simply stays
    /// where it was until corrections resume. Only STOPPED — which ARCore
    /// documents as permanent — clears the plot.
    private fun updatePlotNodes() {
        val plot = activePlot ?: return
        when (plot.anchor.trackingState) {
            TrackingState.STOPPED -> clearPlot()
            TrackingState.TRACKING -> {
                val pose = plot.anchor.pose
                applyPlotPose(pose.tx(), pose.ty(), pose.tz())
            }
            else -> Unit   // PAUSED: hold the last good pose, stay on screen
        }
    }
}

/// Distance-compensated marker scaling: natural (1×) size AT this range,
/// factor = distance / reference — 1 m → 0.4×, 2.5 m → 1×, 20 m → 8×,
/// 35 m → 14×. (Moved here from ArCameraView with the shared-session hub;
/// same values, mirror of iOS ARCameraView.)
private const val MARKER_SCALE_REFERENCE_M = 2.5f
private const val MARKER_SCALE_MIN = 0.4f
private const val MARKER_SCALE_MAX = 14f

/// Sampling-plot ring geometry — the rim only, mirroring the iOS
/// generateRing(). Cheap vs a filled translucent disk, so a 30 m boundary
/// doesn't tank the frame rate.
private const val RING_SEGMENTS = 96

/// Bright rim width (m) — 0.4 m, the iOS ring thickness — and the wider
/// dark halo under it, which is an ANDROID-ONLY addition (see RING_HALO_RGB).
internal const val RING_THICKNESS_M = 0.4f
internal const val HALO_THICKNESS_M = RING_THICKNESS_M + 0.36f

/// Ground clearance for the two rims. 2 cm apart so the bright stroke always
/// wins the depth test over its halo, at every plot radius.
internal const val HALO_Y_M = 0.01f
internal const val RING_Y_M = 0.03f

/// Movement below this (1 mm) is not worth re-transforming five nodes for.
internal const val POSE_EPSILON_M = 0.001f

/// Winding for a [RING_SEGMENTS] annulus. Constant — the radius changes the
/// POSITIONS, never the topology — so it is computed once for the whole app
/// run instead of allocating 1 152 boxed Ints per radius step.
private val RING_INDICES: List<Int> by lazy {
    val out = ArrayList<Int>(RING_SEGMENTS * 12)
    for (i in 0 until RING_SEGMENTS) {
        val i0 = i * 2; val i1 = i * 2 + 1
        val next = (i + 1) % RING_SEGMENTS
        val i2 = next * 2; val i3 = next * 2 + 1
        // front
        out.add(i0); out.add(i1); out.add(i2); out.add(i2); out.add(i1); out.add(i3)
        // reversed (double-sided)
        out.add(i0); out.add(i2); out.add(i1); out.add(i2); out.add(i3); out.add(i1)
    }
    out
}

/// Annulus vertices in the XZ plane at the given centre-line radius.
///
/// NORMALS ARE NOT OPTIONAL HERE, and their absence is why the ring rendered
/// BLACK. SceneView's Geometry.Builder only declares the Filament TANGENTS
/// vertex attribute when at least one vertex carries a normal
/// (`List<Vertex>.hasNormals`), and MaterialLoader.createColorInstance hands
/// back an instance of the LIT `opaque_colored` / `transparent_colored`
/// material. A lit shader with no tangent frame shades against an undefined
/// normal — which is why the spheres and the pole (SceneView's own Sphere /
/// Cylinder geometries, both of which emit normals) looked right while this
/// hand-built annulus came out black. iOS sidesteps the same trap by giving
/// rings an explicit UnlitMaterial; SceneView has no unlit colour material,
/// so the ring gets a real +Y tangent frame instead.
internal fun ringVertices(
    radiusM: Float,
    thicknessM: Float,
): List<io.github.sceneview.geometries.Geometry.Vertex> {
    val half = maxOf(0.01f, thicknessM / 2f)
    val inner = maxOf(0.001f, radiusM - half)
    val outer = radiusM + half
    val up = Float3(0f, 1f, 0f)
    val out = ArrayList<io.github.sceneview.geometries.Geometry.Vertex>(RING_SEGMENTS * 2)
    for (i in 0 until RING_SEGMENTS) {
        val u = i.toFloat() / RING_SEGMENTS
        val a = u * (2f * Math.PI.toFloat())
        val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
        out.add(io.github.sceneview.geometries.Geometry.Vertex(
            position = Float3(inner * ca, 0f, inner * sa),
            normal = up,
            uvCoordinate = dev.romainguy.kotlin.math.Float2(u, 0f),
        ))
        out.add(io.github.sceneview.geometries.Geometry.Vertex(
            position = Float3(outer * ca, 0f, outer * sa),
            normal = up,
            uvCoordinate = dev.romainguy.kotlin.math.Float2(u, 1f),
        ))
    }
    return out
}

internal fun buildRingNode(
    engine: Engine,
    radiusM: Float,
    thicknessM: Float,
    material: MaterialInstance,
): io.github.sceneview.node.GeometryNode {
    val geometry = io.github.sceneview.geometries.Geometry.Builder()
        .vertices(ringVertices(radiusM, thicknessM))
        .indices(RING_INDICES)
        .build(engine)
    return io.github.sceneview.node.GeometryNode(engine, geometry, material)
}

/// The shared AR surface. Two deviations from stock ARSceneView:
///  1. onDetachedFromWindow does NOT self-destroy — the hub reparents this
///     view across screens (detach → attach), and stock SceneView destroys
///     the engine + ARCore session on every window detach.
///  2. Lifecycle comes from the hub's own registry so the ARCore session
///     resumes only while an AR screen is attached (see ArSessionHub docs).
internal class SharedArSceneView(
    context: Context,
    hostActivity: ComponentActivity,
    hostLifecycle: Lifecycle,
    configureSession: (Session, Config) -> Unit,
    onFrame: (Session, Frame) -> Unit,
) : ARSceneView(
    context = context,
    sharedActivity = hostActivity,
    sharedLifecycle = hostLifecycle,
    sessionConfiguration = configureSession,
    onSessionUpdated = onFrame,
) {
    val destroyed: Boolean get() = isDestroyed

    override fun onDetachedFromWindow() {
        // Suppress SceneView's destroy-on-detach (it guards on isDestroyed)
        // so the hub can move this view between screens. Real destruction
        // happens via the hub lifecycle registry reaching DESTROYED.
        val restore = !isDestroyed
        isDestroyed = true
        super.onDetachedFromWindow()
        if (restore) isDestroyed = false
    }
}
