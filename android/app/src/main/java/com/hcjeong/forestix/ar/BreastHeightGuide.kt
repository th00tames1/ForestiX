// Breast-height guide — a drawn answer to "how does the phone know it is
// reading the stem AT breast height?".
//
// The cruiser puts the crosshair on the ground at the foot of the stem and
// places a base point. A thin riser and height ring persist at its live pose;
// no timer hides the guide while the cruiser is lining up the measurement.
//
// IT IS A GUIDE AND ONLY A GUIDE. Nothing here is read by the estimator,
// reaches a QuickMeasureEntry, or appears in an export. The diameter capture
// runs byte for byte the flow it runs with the guide switched off.
//
// Twin of iOS App/BreastHeightGuide.swift: same states, same API names, same
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
import com.hcjeong.forestix.common.BreastHeightGuideHeight

@Stable
class BreastHeightGuide(private val controller: ArController) {

    enum class Stage { OFF, AIMING, PLACED }

    var stage by mutableStateOf(Stage.OFF)
        private set

    var height by mutableStateOf(BreastHeightGuideHeight.METERS_130)

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

    /// Turn the guide off when its toggle goes off or the screen goes away.
    /// Nothing is left anchored.
    fun disable() {
        clearAnchor()
        ghostPoint = null
        trackingLost = false
        stage = Stage.OFF
    }

    /// Drop the placed base and go back to aiming — the "Reset ground" button,
    /// and the tree change in a cruise tally (the Diameter screen is reused
    /// across trees, so tree 7's foot must not still be drawn at tree 8).
    fun clearBase() {
        ghostPoint = null
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
    /// [PLOT_POSE_GRACE_MS] the guide is hidden until tracking recovers.
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

    /// Selected height above the live base. Ticks and label share this
    /// projection; no height is shown until a base has been anchored.
    fun heightWorldPoint(): Vec3? =
        if (stage != Stage.PLACED) null
        else drawnBase()?.let { Vec3(it.x, it.y + height.meters.toFloat(), it.z) }

    /// Label and world geometry always use the same selected height.
    fun label(system: UnitSystem): String =
        if (system == UnitSystem.METRIC) height.metricLabel else height.imperialLabel

    /// Preview dot while aiming; riser and ring persist at the tracked base.
    /// Tracking loss still hides them, but elapsed time alone never does.
    fun markers(): List<ArSceneMarker> {
        val base = drawnBase() ?: return emptyList()
        if (stage == Stage.PLACED) {
            return placementMarkers(base, height)
        }
        return listOf(ArSceneMarker(base, MarkerShape.Sphere(0.015f),
            floatArrayOf(1f, 1f, 1f, 0.45f)))
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
        const val PLACE_BUTTON = "Set ground"
        const val CLEAR_BUTTON = "Reset ground"

        internal fun placementMarkers(base: Vec3, height: BreastHeightGuideHeight): List<ArSceneMarker> {
            val h = height.meters.toFloat()
            val white = floatArrayOf(1f, 1f, 1f, 0.9f)
            return listOf(
                ArSceneMarker(base, MarkerShape.Sphere(0.015f), white),
                ArSceneMarker(Vec3(base.x, base.y + h / 2f, base.z),
                    MarkerShape.Cylinder(0.004f, h), white),
                ArSceneMarker(Vec3(base.x, base.y + h, base.z),
                    MarkerShape.Torus(0.35f, 0.01f), white),
            )
        }
    }
}
