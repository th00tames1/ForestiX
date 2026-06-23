// Tree Height — Android, now using the SAME VIO walk-off tangent method as
// iOS (HeightEstimator, spec §7.2): anchor the trunk, walk back (live d_h),
// then capture the top + base aim angles; H = d_h·(tanα_top − tanα_base)
// with the identical guard rails, σ_H, and green/yellow/red tiers. α comes
// from the ARCore camera-forward elevation (the Android analogue of the
// iOS IMU pitch). Crown is folded in afterwards, reusing the measured d_h.

package com.hcjeong.forestix.ui.screens.height

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArCameraView
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.sensors.HeightEstimator
import com.hcjeong.forestix.sensors.HeightResult
import com.hcjeong.forestix.ui.screens.CenterCrosshair
import com.hcjeong.forestix.ui.screens.CenteredText
import com.hcjeong.forestix.ui.screens.MeasureBackButton
import com.hcjeong.forestix.ui.screens.MeasureControlColumn
import com.hcjeong.forestix.ui.screens.MeasureStatusPanel
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

// Aim BASE before TOP: from eye level you tilt down slightly to the base,
// then sweep up to the top — one continuous motion, less device rotation.
private enum class Stage { ANCHOR, WALKING, AIM_BASE, AIM_TOP, COMPUTED, REJECTED }
private enum class CrownStep { NONE, LEFT, RIGHT, TOP, BOTTOM, DONE }

@Composable
fun HeightScanScreen(nav: NavController) {
    val env = LocalAppEnvironment.current
    val controller = remember { ArController() }
    val pendingTree = remember { env.history.suggestedNextTreeNumber }

    var stage by remember { mutableStateOf(Stage.ANCHOR) }
    var anchorPt by remember { mutableStateOf<Vec3?>(null) }
    var standingLocked by remember { mutableStateOf<Vec3?>(null) }
    var alphaBase by remember { mutableStateOf<Float?>(null) }
    var alphaTop by remember { mutableStateOf<Float?>(null) }
    var topMarker by remember { mutableStateOf<Vec3?>(null) }
    var baseMarker by remember { mutableStateOf<Vec3?>(null) }
    var dhLive by remember { mutableStateOf(0f) }
    var result by remember { mutableStateOf<HeightResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    var crownStep by remember { mutableStateOf(CrownStep.NONE) }
    var cL by remember { mutableStateOf<Vec3?>(null) }
    var cR by remember { mutableStateOf<Vec3?>(null) }
    var cT by remember { mutableStateOf<Vec3?>(null) }
    var cB by remember { mutableStateOf<Vec3?>(null) }
    var crownW by remember { mutableStateOf<Double?>(null) }
    var crownH by remember { mutableStateOf<Double?>(null) }

    // Live walk-off distance while walking back.
    LaunchedEffect(stage) {
        while (stage == Stage.WALKING) {
            anchorPt?.let { a -> controller.horizontalDistanceTo(a)?.let { dhLive = it } }
            delay(100)
        }
    }

    fun markers(): List<ArSceneMarker> {
        val out = mutableListOf<ArSceneMarker>()
        anchorPt?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.08f), floatArrayOf(1f, 0.30f, 0.30f, 1f))) }
        // Tree top (yellow) + base (green) spheres on the anchor's vertical
        // axis at the alpha-derived height — same as iOS rebuildSceneMarkers.
        topMarker?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.08f), floatArrayOf(1f, 0.85f, 0.15f, 1f))) }
        baseMarker?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.08f), floatArrayOf(0.25f, 0.85f, 0.35f, 1f))) }
        val yellow = floatArrayOf(1f, 0.85f, 0.15f, 1f); val cyan = floatArrayOf(0.2f, 0.7f, 1f, 1f)
        cL?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), yellow)) }
        cR?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), yellow)) }
        cT?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), cyan)) }
        cB?.let { out.add(ArSceneMarker(it, MarkerShape.Sphere(0.05f), cyan)) }
        return out
    }

    fun crownProjDistance(): Float = (result?.dHm ?: dhLive).let { if (it > 0.5f) it else 8f }

    fun computeCrown() {
        cL?.let { l -> cR?.let { r -> val dx = l.x - r.x; val dz = l.z - r.z; crownW = kotlin.math.sqrt((dx * dx + dz * dz).toDouble()) } }
        cT?.let { t -> cB?.let { b -> crownH = abs(t.y - b.y).toDouble() } }
        crownStep = CrownStep.DONE
    }

    fun captureCrown() {
        controller.preferDepth = true
        val hit = controller.screenCenterHit() ?: controller.forwardPointAtHorizontalDistance(crownProjDistance())
        if (hit == null) { failure = "Couldn't read scene depth. Move slightly, then tap again."; return }
        failure = null
        when (crownStep) {
            CrownStep.LEFT -> { cL = hit; crownStep = CrownStep.RIGHT }
            CrownStep.RIGHT -> { cR = hit; crownStep = CrownStep.TOP }
            CrownStep.TOP -> { cT = hit; crownStep = CrownStep.BOTTOM }
            CrownStep.BOTTOM -> { cB = hit; computeCrown() }
            else -> {}
        }
    }

    fun captureHeight() {
        when (stage) {
            Stage.ANCHOR -> {
                controller.preferDepth = true
                val hit = controller.screenCenterHit()
                if (hit == null) {
                    failure = "Couldn't find the trunk at the crosshair. Aim at the trunk at eye level and try again."
                    return
                }
                failure = null; anchorPt = hit; dhLive = 0f; stage = Stage.WALKING
            }
            Stage.WALKING -> { stage = Stage.AIM_BASE }
            Stage.AIM_BASE -> {
                val a = controller.cameraForwardElevationRad()
                val s = controller.currentCameraPosition()
                val anchor = anchorPt
                if (a == null || s == null || anchor == null) { failure = "AR tracking not ready — try again."; return }
                // Lock the standing pose on the first aim; both angles must
                // come from the same spot (the §7.2 formula assumes it).
                failure = null; alphaBase = a; standingLocked = s
                val dh = kotlin.math.sqrt((s.x - anchor.x) * (s.x - anchor.x) + (s.z - anchor.z) * (s.z - anchor.z))
                baseMarker = Vec3(anchor.x, s.y + dh * kotlin.math.tan(a), anchor.z)
                stage = Stage.AIM_TOP
            }
            Stage.AIM_TOP -> {
                val aTop = controller.cameraForwardElevationRad()
                val anchor = anchorPt; val standing = standingLocked; val aBase = alphaBase
                if (aTop == null || anchor == null || standing == null || aBase == null) {
                    failure = "AR tracking not ready — try again."; return
                }
                failure = null; alphaTop = aTop
                val dh = kotlin.math.sqrt((standing.x - anchor.x) * (standing.x - anchor.x) + (standing.z - anchor.z) * (standing.z - anchor.z))
                topMarker = Vec3(anchor.x, standing.y + dh * kotlin.math.tan(aTop), anchor.z)
                val r = HeightEstimator.estimate(
                    anchorX = anchor.x, anchorZ = anchor.z,
                    standingX = standing.x, standingZ = standing.z,
                    alphaTopRad = aTop, alphaBaseRad = aBase,
                    trackingStateWasNormalThroughout = true,
                )
                result = r
                stage = if (r.confidence == ConfidenceTier.RED) Stage.REJECTED else Stage.COMPUTED
            }
            else -> {}
        }
    }

    fun resetCrown() { crownStep = CrownStep.NONE; cL = null; cR = null; cT = null; cB = null; crownW = null; crownH = null }
    fun resetAll() {
        stage = Stage.ANCHOR; anchorPt = null; standingLocked = null
        alphaBase = null; alphaTop = null; topMarker = null; baseMarker = null
        dhLive = 0f; result = null; failure = null; resetCrown()
    }

    val crownActive = crownStep != CrownStep.NONE && crownStep != CrownStep.DONE
    val showCapture = stage in listOf(Stage.ANCHOR, Stage.WALKING, Stage.AIM_TOP, Stage.AIM_BASE) || crownActive

    Box(Modifier.fillMaxSize()) {
        ArCameraView(controller, markers(), modifier = Modifier.fillMaxSize())
        if (showCapture) CenterCrosshair(Modifier.align(Alignment.Center))
        MeasureBackButton { nav.popBackStack() }
        if (showCapture) MeasureControlColumn(onCapture = { if (crownActive) captureCrown() else captureHeight() })

        MeasureStatusPanel {
            failure?.let { CenteredText(it, dim = true) }
            CenteredText(statusText(stage, crownStep, dhLive))
            result?.let { r ->
                CenteredText(String.format(Locale.US, "Height %.1f m  \u00B1%.1f m", r.heightM, r.sigmaHm), large = true)
                CenteredText("d_h ${String.format(Locale.US, "%.1f", r.dHm)} m \u00B7 ${r.confidence.raw.uppercase()}", dim = true)
                r.rejectionReason?.let { CenteredText(it, dim = true) }
            }
            if (crownStep == CrownStep.DONE && crownW != null && crownH != null) {
                CenteredText(String.format(Locale.US, "Crown %.2f m wide \u00B7 %.2f m tall", crownW, crownH))
            }
            if (stage == Stage.COMPUTED || stage == Stage.REJECTED) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (stage == Stage.COMPUTED) {
                        if (crownStep == CrownStep.NONE) {
                            OutlinedButton(onClick = { crownStep = CrownStep.LEFT }, modifier = Modifier.fillMaxWidth()) { Text("Measure crown") }
                        } else if (crownStep == CrownStep.DONE) {
                            OutlinedButton(onClick = { resetCrown() }, modifier = Modifier.fillMaxWidth()) { Text("Redo crown") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { resetAll() }, modifier = Modifier.weight(1f)) { Text("Retake") }
                        if (stage == Stage.COMPUTED) {
                            Button(onClick = {
                                val r = result ?: return@Button
                                env.history.append(
                                    QuickMeasureEntry(
                                        kind = MeasureKind.HEIGHT, value = r.heightM.toDouble(),
                                        sigma = r.sigmaHm.toDouble(), confidenceRaw = r.confidence.raw,
                                        method = r.method.raw, treeNumber = pendingTree,
                                        plotID = env.history.activePlotID.value,
                                    )
                                )
                                if (crownStep == CrownStep.DONE) {
                                    val w = crownW; val ch = crownH
                                    if (w != null && ch != null) {
                                        env.history.append(
                                            QuickMeasureEntry(
                                                kind = MeasureKind.CROWN, value = w, secondaryValue = ch,
                                                sigma = null, confidenceRaw = "green", method = "ar.crown.dh",
                                                treeNumber = pendingTree, plotID = env.history.activePlotID.value,
                                            )
                                        )
                                    }
                                }
                                nav.popBackStack()
                            }, modifier = Modifier.weight(1f)) { Text("Accept") }
                        }
                    }
                }
            }
        }
    }
}

private fun statusText(stage: Stage, crown: CrownStep, dh: Float): String = when {
    crown == CrownStep.LEFT -> "Crown: aim at the LEFT edge, tap +"
    crown == CrownStep.RIGHT -> "Crown: aim at the RIGHT edge, tap +"
    crown == CrownStep.TOP -> "Crown: aim at the HIGHEST branch, tap +"
    crown == CrownStep.BOTTOM -> "Crown: aim at the LOWEST branch, tap +"
    stage == Stage.ANCHOR -> "Aim at the trunk at eye level, tap + to anchor"
    stage == Stage.WALKING -> "Walk back (${String.format(Locale.US, "%.1f", dh)} m), tap + to continue"
    stage == Stage.AIM_TOP -> "Aim at the treetop, tap +"
    stage == Stage.AIM_BASE -> "Aim at the tree base, tap +"
    stage == Stage.REJECTED -> "Rejected \u2014 retake"
    else -> "Height computed"
}
