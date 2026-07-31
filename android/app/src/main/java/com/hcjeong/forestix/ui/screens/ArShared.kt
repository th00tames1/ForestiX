// Shared AR-screen overlay chrome: the centre crosshair every AR
// measurement screen layers over the camera feed (mirrors the SwiftUI
// overlay code on iOS).

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.ArSceneMarker
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.ar.MarkerShape
import com.hcjeong.forestix.ar.Vec3
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace
import java.util.UUID
import kotlin.math.sqrt

@Composable
fun CenterCrosshair(modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).border(4.dp, Color.Black.copy(alpha = 0.5f), CircleShape),
        )
        Box(Modifier.size(32.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape))
        // Dark backing bars under the white ticks for sun-glare contrast —
        // iOS DistanceMeasureScreen crosshair (14×3 black 0.6 halos).
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(Color.Black.copy(alpha = 0.6f)))
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(Color.Black.copy(alpha = 0.6f)))
        Box(Modifier.size(width = 12.dp, height = 1.5.dp).background(Color.White))
        Box(Modifier.size(width = 1.5.dp, height = 12.dp).background(Color.White))
    }
}


/// The plot pillar the tap WOULD plant, drawn translucent at the live
/// crosshair hit (FIELD REPORT 8).
///
/// The aiming state used to be a crosshair and nothing else, so the cruiser
/// was placing the plot centre blind and only found out where it had landed
/// after the tap. Both plot-centre screens (quick sampling and the cruise
/// plot start) call this, so the ghost cannot drift from one to the other.
///
/// Same four pieces, same sizes, same colours as the placed assembly the hub
/// builds in rebuildPlotNodes — a preview that looked different would be
/// teaching the cruiser the wrong thing. Only the alpha changes, and the
/// ring is included because the radius slider is right there: the cruiser
/// can see how much ground a 5.6 m plot actually covers before committing to
/// a centre, which is the part that is hardest to judge by eye.
///
/// These are plain world positions, not anchored — there is no ARCore anchor
/// yet, that is what the tap creates — so the ghost rides the aim.
fun plotPillarPreviewMarkers(centre: Vec3?, radiusM: Float): List<ArSceneMarker> {
    if (centre == null) return emptyList()
    val ghost = 0.35f
    return listOf(
        ArSceneMarker(centre, MarkerShape.Sphere(0.07f), floatArrayOf(1f, 0.25f, 0.25f, ghost)),
        ArSceneMarker(
            Vec3(centre.x, centre.y + 0.6f, centre.z),
            MarkerShape.Cylinder(0.03f, 1.2f), floatArrayOf(1f, 1f, 1f, ghost)),
        ArSceneMarker(
            Vec3(centre.x, centre.y + 1.2f, centre.z),
            MarkerShape.Sphere(0.12f), floatArrayOf(1f, 0.85f, 0.15f, ghost)),
        ArSceneMarker(
            Vec3(centre.x, centre.y + 0.02f, centre.z),
            MarkerShape.Ring(radiusM, 0.4f), floatArrayOf(0.2f, 0.85f, 1f, ghost)),
    )
}

// MARK: - "Pin centre" offer (field report 14 × 17)

/// The one act behind the scan screens' `PlotPinCentreCard`: plant the plot
/// centre where the crosshair meets the ground and hand the ring to the
/// cruise plot being tallied.
///
/// WHY THIS EXISTS. The subdued ring + pillar are drawn from an ARCore
/// ANCHOR, and only the AR "Start plot" route creates one. A plot opened
/// from a planned pin ("Start plot now" — the one-tap route field report 17
/// introduced, and now the only one that card offers) and any plot
/// carried across an app restart have a centre that is a lat/lon and nothing
/// else, so the scan screens showed a bare camera feed with a plot active.
/// The ring is not synthesised from that lat/lon: a fix under canopy is
/// worth several metres and an anchor is worth centimetres, and a boundary
/// drawn in the wrong place is worse than none. The cruiser standing at the
/// centre and pinning it is the SAME act the AR route performs, so the ring
/// it produces is worth the same.
///
/// The stored `Plot.centerLat/centerLon` are deliberately NOT rewritten —
/// the card says so — because a control that silently moved a plot centre to
/// wherever the cruiser was standing is exactly the invisible data loss
/// `CruiseStartPlotScreen.applyEdit` refuses.
///
/// Mirror of the iOS `DBHScanScreen.pinPlotCentre()` / `HeightScanScreen`
/// pair; the words come from `MeasureControls.kt`, so they cannot drift.
@Stable
class PlotPinCentre internal constructor(
    private val plotId: UUID,
    private val radiusM: Double,
    private val controller: ArController,
) {
    /// Why the last tap planted nothing. Rendered on the card.
    var failure by mutableStateOf<String?>(null)
        private set

    /// Waved off for this visit. Not persisted: the offer is an offer, and a
    /// cruiser measuring from outside the plot should not be nagged for a
    /// whole tally. Re-entering the screen asks once more — by then they may
    /// be standing at the centre.
    var dismissed by mutableStateOf(false)
        private set

    fun dismiss() {
        failure = null
        dismissed = true
    }

    /// NO forward-ray fallback, unlike the two placement screens. There the
    /// ghost preview shows the cruiser where a 3 m-forward fallback point
    /// lands before they commit; here there is no preview (a per-poll hit
    /// test is the cost field report 9 was about), so a fallback would plant
    /// the centre in mid-air with nothing to warn them. Refusing is the
    /// honest half of that trade, and the message says what to do.
    fun pin() {
        val hit = controller.screenCenterHit()
        if (hit == null) {
            failure = PLOT_GROUND_NOT_SEEN
            return
        }
        // The plot's OWN radius, not whatever the sampling slider was last
        // left at — this ring stands for a saved cruise plot. Set BEFORE the
        // placement so `rebuildPlotNodes` builds the ring at the right size
        // instead of building one and immediately rewriting it.
        ArSessionHub.setPlotRadius(radiusM)
        if (!ArSessionHub.placePlot(hit)) {
            failure = PLOT_GROUND_NOT_SEEN
            return
        }
        failure = null
        // placePlot nulls the link (a fresh ring belongs to nobody), so the
        // stamp has to come after it.
        ArSessionHub.linkPlot(plotId)
    }
}

/// The offer for the plot this scan screen is tallying into, or null when
/// there is nothing to offer.
///
/// Null in four cases, each for its own reason:
///  * no cruise session — a quick sampling ring has no stored plot behind it
///    to draw, and nothing survives a restart to be missing;
///  * the ring already IS this plot's (`linkedCruisePlotId`), so the overlay
///    is up and the offer would be noise. That is the same linked-id test the
///    border chip and the mini-map use: an "Add tree" can target an OLDER
///    open plot than the last-placed ring, and that ring is not this plot's
///    centre;
///  * the cruiser waved it off;
///  * the plot's stored area does not yield a sane radius. There is no
///    honest radius to invent in its place, so the offer stands down rather
///    than drawing a ring of a made-up size. (Same > 0.5 m floor the plot
///    mini-map applies to the same field.)
///
/// Deliberately NOT gated on tracking loss: a dip hides a ring that EXISTS
/// and already says so in its own words (`PLOT_TRACKING_LOST_HINT`); this
/// card would be a second, wrong explanation for it.
@Composable
fun rememberPlotPinCentreOffer(controller: ArController): PlotPinCentre? {
    val env = LocalAppEnvironment.current
    // The cruise target is armed before navigation and cleared after the
    // chain pops back to the map — constant for this screen's lifetime, the
    // same `remember` ScanPlotMiniMap takes.
    val cruise = remember { CruiseCapture.target }
    var radiusM by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(cruise?.plotId) {
        val c = cruise ?: return@LaunchedEffect
        radiusM = runCatching { env.plotRepository.read(c.plotId) }.getOrNull()
            ?.plotAreaAcres?.toDouble()
            ?.let { sqrt(Units.acresToSquareMeters(it) / Math.PI) }
            ?.takeIf { it.isFinite() && it > 0.5 }
    }
    val offer = remember(cruise?.plotId, radiusM) {
        val c = cruise ?: return@remember null
        val r = radiusM ?: return@remember null
        PlotPinCentre(c.plotId, r, controller)
    } ?: return null
    // `activePlot` is Compose state, so placing (or field report 7 dropping)
    // a ring recomposes this and the card appears/disappears with it;
    // `linkedCruisePlotId` is a plain volatile, read on the back of that.
    val ringIsThisPlot =
        ArSessionHub.activePlot != null &&
            ArSessionHub.linkedCruisePlotId == cruise?.plotId
    if (ringIsThisPlot || offer.dismissed) return null
    return offer
}
