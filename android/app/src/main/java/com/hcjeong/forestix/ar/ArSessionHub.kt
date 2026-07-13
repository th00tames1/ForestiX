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
//  - A radius change rebuilds ONLY the ring node (reusing its material),
//    not the whole 4-marker set.
//  - Screens can turn the session's Depth API and the plane renderer off
//    (sampling does both once the centre is placed — it never consumes
//    depth images, and ARCore's ML depth-from-motion + per-frame plane
//    geometry updates were the dominant frame cost while walking a plot).

package com.hcjeong.forestix.ar

import android.content.Context
import android.content.ContextWrapper
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
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode

object ArSessionHub {

    /// The ONE ArController every AR screen reads (hits, poses, depth
    /// frames). Screens must use this instead of constructing their own —
    /// only the hub's controller is fed frames from the shared session.
    val controller = ArController()

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
    private var overlay = PlotOverlay.HIDDEN

    // Generic marker pipeline (one screen's markers at a time).
    private val markerNodes = mutableListOf<Node>()
    private val builtMarkers = mutableListOf<ArSceneMarker>()
    private val scalingNodes = mutableListOf<Pair<Node, Vec3>>()

    // Plot overlay nodes: [centre sphere, pole, top sphere, ring].
    private val plotNodes = mutableListOf<Node>()
    private var plotRingNode: Node? = null

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
    ): Int {
        obtainView(context)
        controller.preferDepth = preferDepth
        screenWantsDepth = enableDepth
        overlay = plotOverlay
        clearMarkerNodes()
        sceneView?.planeRenderer?.isEnabled = planeRendererEnabled
        reconfigureSession()
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
    ) {
        if (attachedToken != token) return
        controller.preferDepth = preferDepth
        sceneView?.planeRenderer?.let { if (it.isEnabled != planeRendererEnabled) it.isEnabled = planeRendererEnabled }
        if (screenWantsDepth != enableDepth) {
            screenWantsDepth = enableDepth
            reconfigureSession()
        }
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
        lifecycleOwner?.registry?.let { registry ->
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
        dropViewState()
    }

    private fun dropViewState() {
        hostActivity?.lifecycle?.removeObserver(activityObserver)
        sceneView = null
        hostActivity = null
        lifecycleOwner = null
        markerNodes.clear()
        builtMarkers.clear()
        scalingNodes.clear()
        plotNodes.clear()
        plotRingNode = null
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
        config.depthMode =
            if (depthSupported && screenWantsDepth) Config.DepthMode.AUTOMATIC
            else Config.DepthMode.DISABLED
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
        config.lightEstimationMode = Config.LightEstimationMode.DISABLED
    }

    private fun reconfigureSession() {
        val session = sceneView?.session ?: return   // create-time hook will apply
        runCatching {
            val config = session.config
            applySessionConfig(session, config)
            session.configure(config)
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
            is MarkerShape.Ring -> buildRingNode(engine, shape, material)
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
        rebuildPlotNodes()
        return true
    }

    /// Update the plot radius (m). Rebuilds ONLY the ring node, reusing its
    /// cached material — the old whole-marker-set rebuild per slider step
    /// was a main lag source on the sampling screen.
    fun setPlotRadius(radiusM: Double) {
        if (plotRadiusM == radiusM) return
        plotRadiusM = radiusM
        val view = sceneView ?: return
        val ring = plotRingNode ?: return
        val position = ring.worldPosition
        view.removeChildNode(ring)
        runCatching { ring.destroy() }
        plotNodes.remove(ring)
        val newRing = buildMarkerNode(view, ringShape(), plotColor(RING_RGB))
        newRing.worldPosition = position
        newRing.isVisible = ring.isVisible
        plotRingNode = newRing
        plotNodes.add(newRing)
        view.addChildNode(newRing)
    }

    /// Drop the plot (sampling Reset, or its anchor stopped tracking
    /// permanently).
    fun clearPlot() {
        activePlot?.anchor?.let { runCatching { it.detach() } }
        activePlot = null
        destroyPlotNodes()
    }

    /// Drift-corrected plot centre — the anchor's CURRENT pose translation.
    /// Null when no plot exists or its anchor has stopped for good.
    fun plotCenterWorld(): Vec3? {
        val plot = activePlot ?: return null
        if (plot.anchor.trackingState == TrackingState.STOPPED) return null
        val t = plot.anchor.pose.translation
        return Vec3(t[0], t[1], t[2])
    }

    // Plot marker palette — same geometry/colours as the sampling screen's
    // original marker list; SUBDUED halves the alpha.
    private val CENTRE_RGB = floatArrayOf(1f, 0.25f, 0.25f)
    private val POLE_RGB = floatArrayOf(1f, 1f, 1f)
    private val TOP_RGB = floatArrayOf(1f, 0.85f, 0.15f)
    private val RING_RGB = floatArrayOf(0.2f, 0.85f, 1f)

    private fun plotAlpha(): Float = if (overlay == PlotOverlay.OWNER) 1f else 0.5f

    private fun plotColor(rgb: FloatArray) = floatArrayOf(rgb[0], rgb[1], rgb[2], plotAlpha())

    private fun ringShape() = MarkerShape.Ring(plotRadiusM.toFloat(), 0.4f)

    /// (Re)build the plot's nodes for the current overlay style. Cheap and
    /// rare: once per screen attach / placement / style change.
    private fun rebuildPlotNodes() {
        destroyPlotNodes()
        val view = sceneView ?: return
        val plot = activePlot ?: return
        if (overlay == PlotOverlay.HIDDEN) return
        val t = plot.anchor.pose.translation
        val base = Vec3(t[0], t[1], t[2])
        val centre = buildMarkerNode(view, MarkerShape.Sphere(0.07f), plotColor(CENTRE_RGB))
        val pole = buildMarkerNode(view, MarkerShape.Cylinder(0.05f, 1.2f), plotColor(POLE_RGB))
        val top = buildMarkerNode(view, MarkerShape.Sphere(0.12f), plotColor(TOP_RGB))
        val ring = buildMarkerNode(view, ringShape(), plotColor(RING_RGB))
        positionPlotNodes(centre, pole, top, ring, base)
        val tracking = plot.anchor.trackingState == TrackingState.TRACKING
        listOf(centre, pole, top, ring).forEach { node ->
            node.isVisible = tracking
            plotNodes.add(node)
            view.addChildNode(node)
        }
        plotRingNode = ring
    }

    private fun positionPlotNodes(centre: Node, pole: Node, top: Node, ring: Node, base: Vec3) {
        centre.worldPosition = Float3(base.x, base.y, base.z)
        pole.worldPosition = Float3(base.x, base.y + 0.6f, base.z)
        top.worldPosition = Float3(base.x, base.y + 1.2f, base.z)
        ring.worldPosition = Float3(base.x, base.y + 0.02f, base.z)
    }

    private fun destroyPlotNodes() {
        val view = sceneView
        plotNodes.forEach { node ->
            view?.removeChildNode(node)
            runCatching { node.destroy() }
        }
        plotNodes.clear()
        plotRingNode = null
    }

    /// Per-frame: follow the anchor's corrected pose; hide while tracking
    /// is paused; self-clear when ARCore stops the anchor permanently.
    private fun updatePlotNodes() {
        val plot = activePlot ?: return
        when (plot.anchor.trackingState) {
            TrackingState.STOPPED -> clearPlot()
            TrackingState.PAUSED -> plotNodes.forEach { it.isVisible = false }
            TrackingState.TRACKING -> {
                if (plotNodes.size == 4) {
                    val t = plot.anchor.pose.translation
                    positionPlotNodes(
                        plotNodes[0], plotNodes[1], plotNodes[2], plotNodes[3],
                        Vec3(t[0], t[1], t[2]),
                    )
                }
                plotNodes.forEach { it.isVisible = true }
            }
            else -> Unit
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

/// Builds a flat annulus (ring) node — the rim only — mirroring the iOS
/// generateRing(). Cheap vs a filled translucent disk, so a 30 m boundary
/// doesn't tank the frame rate.
internal fun buildRingNode(
    engine: Engine,
    shape: MarkerShape.Ring,
    material: MaterialInstance,
): Node {
    val segments = 96
    val half = maxOf(0.01f, shape.thicknessM / 2f)
    val inner = maxOf(0.001f, shape.radiusM - half)
    val outer = shape.radiusM + half
    val vertices = ArrayList<io.github.sceneview.geometries.Geometry.Vertex>(segments * 2)
    for (i in 0 until segments) {
        val a = (i.toFloat() / segments) * (2f * Math.PI.toFloat())
        val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
        vertices.add(io.github.sceneview.geometries.Geometry.Vertex(position = Float3(inner * ca, 0f, inner * sa)))
        vertices.add(io.github.sceneview.geometries.Geometry.Vertex(position = Float3(outer * ca, 0f, outer * sa)))
    }
    val indices = ArrayList<Int>(segments * 12)
    for (i in 0 until segments) {
        val i0 = i * 2; val i1 = i * 2 + 1
        val next = (i + 1) % segments
        val i2 = next * 2; val i3 = next * 2 + 1
        indices.addAll(listOf(i0, i1, i2, i2, i1, i3))   // front
        indices.addAll(listOf(i0, i2, i1, i2, i3, i1))   // reversed (double-sided)
    }
    val geometry = io.github.sceneview.geometries.Geometry.Builder()
        .vertices(vertices)
        .indices(indices)
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
