// Breast-height guide — a drawn answer to "how does the phone know it is
// reading the stem AT breast height?".
//
// The cruiser puts the crosshair on the ground at the foot of the stem and
// places a base point. From it a white line rises to BREAST_HEIGHT_M with a
// flat ring at the top, so where breast height crosses the trunk is a thing
// on screen rather than a thing estimated by eye.
//
// IT IS A GUIDE AND ONLY A GUIDE. Nothing here is read by the estimator,
// reaches a QuickMeasureEntry, or appears in an export. The diameter capture
// runs byte for byte the flow it runs with the guide switched off.
//
// Twin of iOS AR/BreastHeightGuide.swift: same states, same API names, same
// shapes, same grace window. Deliberately NOT a singleton like
// ArSessionHub's plot — one instance per Diameter screen, so a base placed
// at one tree's foot cannot outlive the screen that placed it.

package com.hcjeong.forestix.ar

import android.os.SystemClock
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units

@Stable
class BreastHeightGuide(private val controller: ArController) {

    enum class Stage { OFF, AIMING, PLACED }

    var stage by mutableStateOf(Stage.OFF)
        private set

    /// The LIVE base point — the anchor's drift-corrected pose, re-read on
    /// every refresh, never the frozen hit coordinate the placement produced.
    /// Same rule the sampling plot follows (ArSessionHub.updatePlotNodes):
    /// an anchor translation is only meaningful in the world frame ARCore
    /// held when it was read, and that frame moves.
    var basePoint by mutableStateOf<Vec3?>(null)
        private set

    /// Where the assembly WOULD land, drawn translucent while aiming.
    var ghostPoint by mutableStateOf<Vec3?>(null)
        private set

    /// True once the pose has gone uncorrected for longer than the grace and
    /// the markers have been taken down rather than drawn in the wrong place.
    var trackingLost by mutableStateOf(false)
        private set

    private var anchor: Anchor? = null
    /// Monotonic clock, matching ArSessionHub's own choice — wall time can
    /// step and this window is half a second wide.
    private var poseStaleSinceMs = 0L

    /// Turn the guide on. Idempotent, so the Settings gate can call it on
    /// every recomposition without disturbing a base already placed.
    fun arm() {
        if (stage == Stage.OFF) stage = Stage.AIMING
    }

    /// Turn the guide off entirely: the toggle going off, developer mode
    /// going off, or the screen going away. Nothing is left anchored.
    fun disable() {
        clearAnchor()
        ghostPoint = null
        trackingLost = false
        stage = Stage.OFF
    }

    /// Drop the placed base and go back to aiming — the "Clear base" button,
    /// and the tree change in a cruise tally (the Diameter screen is reused
    /// across trees, so tree 7's foot must not still be drawn at tree 8).
    fun clearBase() {
        clearAnchor()
        trackingLost = false
        if (stage == Stage.PLACED) stage = Stage.AIMING
    }

    /// Anchor the base at a crosshair hit. False when the session cannot take
    /// an anchor, which is the same refusal ArSessionHub.placePlot gives.
    fun place(hit: Vec3): Boolean {
        val session = controller.session ?: return false
        val created = runCatching {
            session.createAnchor(Pose.makeTranslation(hit.x, hit.y, hit.z))
        }.getOrNull() ?: return false
        clearAnchor()
        anchor = created
        basePoint = hit
        ghostPoint = null
        trackingLost = false
        poseStaleSinceMs = 0L
        stage = Stage.PLACED
        return true
    }

    /// The ghost's live position while aiming. Null (a missed ray) draws
    /// nothing rather than freezing the preview somewhere stale.
    fun updateGhost(point: Vec3?) {
        ghostPoint = if (stage == Stage.AIMING) point else null
    }

    /// Re-read the anchor's corrected pose.
    ///
    /// The pose test is the one updatePlotNodes applies, for the reason given
    /// there: an anchor can report TRACKING for a frame or two after the
    /// camera has lost the world, so the camera's own state is part of it.
    /// A routine sub-second dip is ridden out — hiding on the first PAUSED
    /// frame makes the guide blink continuously — and past
    /// [PLOT_POSE_GRACE_MS] the assembly comes down and the screen says why.
    fun refresh() {
        val a = anchor ?: return
        if (a.trackingState == TrackingState.STOPPED) {
            // ARCore has given up on this anchor for good; there is no pose
            // left to come back to.
            clearBase()
            return
        }
        val corrected = a.trackingState == TrackingState.TRACKING &&
            controller.frame?.camera?.trackingState == TrackingState.TRACKING
        if (corrected) {
            poseStaleSinceMs = 0L
            val p = a.pose
            basePoint = Vec3(p.tx(), p.ty(), p.tz())
            trackingLost = false
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (poseStaleSinceMs == 0L) poseStaleSinceMs = now
        if (now - poseStaleSinceMs >= PLOT_POSE_GRACE_MS) trackingLost = true
    }

    /// Where the ring sits — the point the 2D label is projected from.
    ///
    /// Null while aiming even though the ghost draws a ring there. The ghost
    /// says where the base WOULD go; putting a hard "1.37 m" on it would
    /// claim a height had been established when nothing has been anchored
    /// yet.
    fun ringWorldPoint(): Vec3? =
        if (stage != Stage.PLACED) null
        else drawnBase()?.let { Vec3(it.x, it.y + Units.BREAST_HEIGHT_M.toFloat(), it.z) }

    /// The value pill's text.
    ///
    /// The two strings are the SAME HEIGHT, not conversions of one another:
    /// 4.5 ft is the US definition and 1.37 m is that height written in
    /// metres. Deriving the imperial side from [Units.BREAST_HEIGHT_M] would
    /// print "4.49 ft" and invite the reader to think the app had rounded a
    /// standard. (ScanMetadataSheet spells the identical pair for the same
    /// reason.)
    fun label(system: UnitSystem): String =
        if (system == UnitSystem.METRIC) "1.37 m" else "4.5 ft"

    /// The three world shapes, or none when there is nothing to draw.
    ///
    /// FIXED ORDER AND FIXED LENGTH within a state, because
    /// ArSessionHub.syncMarkers diffs by INDEX: it moves nodes only when
    /// count, shape, colour and scaling flag all match position for position.
    /// A list whose order wobbled per poll would destroy and rebuild Filament
    /// geometry at 5 Hz. Ghost → placed changes the alpha, so that transition
    /// costs one structural rebuild, which is what already happens when the
    /// trunk cylinder appears.
    fun markers(): List<ArSceneMarker> {
        val base = drawnBase() ?: return emptyList()
        val placed = stage == Stage.PLACED
        // 0.35 while aiming — the alpha plotPillarPreviewMarkers uses for the
        // plot ghost, for the same reason: the cruiser sees where the base
        // will land before committing to it.
        val a = if (placed) 1f else GHOST_ALPHA
        val white = floatArrayOf(1f, 1f, 1f, a)
        val h = Units.BREAST_HEIGHT_M.toFloat()
        return listOf(
            // ONLY the base sphere scales with distance. It marks a place, so
            // growing it keeps it findable from across a stand. The line and
            // the ring ARE the measurement — markerDistanceScale scales a
            // node whole, so a scaled cylinder would draw a height that is
            // not 1.37 m, which is the exact error this guide exists to
            // prevent.
            ArSceneMarker(base, MarkerShape.Sphere(0.06f), white, scalesWithDistance = true),
            // SceneView's CylinderNode extends ±height/2 about its position,
            // so a pole standing on the base sits at half its height.
            ArSceneMarker(
                Vec3(base.x, base.y + h / 2f, base.z),
                MarkerShape.Cylinder(0.012f, h), white,
            ),
            // Thick annulus, not a hairline circle: the cruiser brings it
            // round the trunk and reads where it crosses the bark. Built by
            // buildRingNode, whose vertices carry +Y normals — a hand-built
            // normal-less annulus renders BLACK under SceneView's lit colour
            // material (see ringVertices). 0.05 m clears its 0.01 half-width
            // floor.
            ArSceneMarker(
                Vec3(base.x, base.y + h, base.z),
                MarkerShape.Ring(0.35f, 0.05f), white,
            ),
        )
    }

    /// The point the assembly is drawn from: the anchor once placed, the aim
    /// while placing, and nothing at all once tracking has been lost long
    /// enough to distrust the pose.
    private fun drawnBase(): Vec3? = when (stage) {
        Stage.OFF -> null
        Stage.AIMING -> ghostPoint
        Stage.PLACED -> if (trackingLost) null else basePoint
    }

    private fun clearAnchor() {
        anchor?.let { runCatching { it.detach() } }
        anchor = null
        basePoint = null
        poseStaleSinceMs = 0L
    }

    companion object {
        private const val GHOST_ALPHA = 0.35f

        /// Said when the placement ray finds no ground — the words every
        /// other crosshair-to-ground placement in the app already uses.
        const val AIM_PROMPT = "Aim at the tree base"
        const val PLACE_BUTTON = "Place base"
        const val CLEAR_BUTTON = "Clear base"
        const val TRACKING_LOST_HINT =
            "Tracking lost — the guide is hidden rather than drawn in the wrong place."
    }
}
