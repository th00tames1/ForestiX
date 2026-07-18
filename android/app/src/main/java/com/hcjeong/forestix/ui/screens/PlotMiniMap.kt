// Plot mini-map — a small NORTH-UP schematic of the ACTIVE plot floating
// top-right over the AR measurement screens (locked design, matches iOS):
// a rounded-SQUARE dark-glass card (ForestixRadius.card, deliberately NOT
// the competitor's circular radar; no walked-trail dots) showing the plot
// ring in the AR-ring cyan, the cruiser's own position (YOU dot + heading
// wedge), and the plot's measured trees tinted by confidence.
//
// POSITION DATA (mirrors iOS PlotMiniMapWidget)
//  * YOU — camera offset from the plot's AR anchor whenever the shared
//    ArSessionHub still holds it AND the anchor belongs to the plot being
//    measured (hub.linkedCruisePlotId — an "Add tree" can target an OLDER
//    open plot than the last-placed ring), rotated from the arbitrary-yaw
//    ARCore world frame into north-up ENU. iOS gets that alignment for
//    free (ARKit `.gravityAndHeading` builds a north-aligned world); the
//    Android robust equivalent, reusing the pattern OffsetFlowScreen
//    established: pair a camera-forward compass azimuth (rotation vector
//    remapped for the upright AR posture + declination) with the
//    same-instant ArController.cameraWorldYawDeg(), then run the delta
//    through a circular EMA so a single noisy compass sample can't swing
//    the map. When the AR path is unavailable (no/foreign anchor,
//    tracking lost, compass dead) it falls back to the GPS-ENU offset of
//    the live fix against the plot centre lat/lon; with neither, the YOU
//    dot is simply omitted (never drawn at a made-up angle).
//  * TREES — GPS-ENU offsets of each measured tree's Accept-time fix
//    against the plot centre; dots outside the ring clamp to the ring
//    edge (YOU clamps the same way — iOS point()). Quick-plot mode
//    (ActiveSamplingPlot without a cruise plot) shows ring + YOU only.
//
// CHEAP: one 5 Hz tick (the same cadence as the sampling boundary check)
// updates a single state holder, quantized to 5 cm / 2° so sub-jitter
// movement doesn't invalidate the card (iOS parity); tree offsets are
// derived only when the tree list changes; everything draws in one
// Canvas. Non-interactive.

package com.hcjeong.forestix.ui.screens

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.LocalAppEnvironment
import com.hcjeong.forestix.ar.ArSessionHub
import com.hcjeong.forestix.common.Units
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.positioning.GeoMath
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.sensors.ConfidenceTier
import com.hcjeong.forestix.ui.screens.cruise.CruiseCapture
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf

// Locked geometry/palette (same values on iOS).
private val CARD_SIZE = 116.dp
private const val RING_FRACTION = 0.78f            // ring diameter vs card
private val RING_CYAN = Color(red = 0.2f, green = 0.85f, blue = 1f)
private val YOU_BLUE = Color(0xFF3B82C4)

/// True when the DBH/Height mini-map will be shown — lets those screens
/// push the developer HUD below it (both live top-right).
@Composable
fun scanPlotMiniMapVisible(): Boolean =
    CruiseCapture.target != null || ArSessionHub.activePlot != null

/// DBH/Height entry: full widget while a cruise plot is ACTIVE (ring +
/// YOU + measured-tree dots); quick ActiveSamplingPlot-only sessions get
/// ring + YOU with no tree dots. Emits nothing when neither exists.
/// Callers hide it during the accept-snapshot chrome blackout.
@Composable
fun BoxScope.ScanPlotMiniMap() {
    // The cruise target is armed before navigation and cleared after the
    // chain pops back to the map — constant for this screen's lifetime.
    val cruise = remember { CruiseCapture.target }
    val quickPlot = ArSessionHub.activePlot
    if (cruise == null && quickPlot == null) return
    val env = LocalAppEnvironment.current
    val treesFlow = remember {
        cruise?.let { env.treeRepository.observeByPlot(it.plotId) } ?: flowOf(emptyList<Tree>())
    }
    val trees by treesFlow.collectAsStateWithLifecycle(emptyList())
    // Cruise plots know their real radius (denormalized area) — the hub's
    // slider radius is only authoritative for the quick sampling plot.
    var radiusOverrideM by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        val c = cruise ?: return@LaunchedEffect
        radiusOverrideM = runCatching { env.plotRepository.read(c.plotId) }.getOrNull()
            ?.plotAreaAcres?.toDouble()
            ?.let { sqrt(Units.acresToSquareMeters(it) / Math.PI) }
            ?.takeIf { it.isFinite() && it > 0.5 }
    }
    // (0,0) is the "no fix at plot save" sentinel — same guard as
    // CruiseCapture.recordDbh's bearing/distance computation.
    val hasCentre = cruise != null && (cruise.plotCenterLat != 0.0 || cruise.plotCenterLon != 0.0)
    PlotMiniMapCard(
        plotNumber = cruise?.plotNumber,
        trees = if (cruise != null) trees else emptyList(),
        plotCenterLat = if (hasCentre) cruise?.plotCenterLat else null,
        plotCenterLon = if (hasCentre) cruise?.plotCenterLon else null,
        radiusOverrideM = radiusOverrideM,
        // The anchor may only stand in for THIS plot's centre when the
        // cruise Start-plot save linked them (quick mode: any anchor).
        requireLinkedPlotId = cruise?.plotId,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 22.dp, end = 16.dp),   // GPS-badge row (leading side)
    )
}

/// Sampling/plot-creation entry (callers gate on centre placement): ring +
/// YOU only. Sits BELOW the full-width radius-slider card those screens
/// keep top-centre — the DBH/Height top-22 slot is occupied there. With no
/// `plotNumber` the header renders a plain "PLOT" (iOS parity — the
/// creation screens don't show a number either).
@Composable
fun BoxScope.SamplingPlotMiniMap(plotNumber: Int? = null) {
    PlotMiniMapCard(
        plotNumber = plotNumber,
        trees = emptyList(),
        plotCenterLat = null,
        plotCenterLon = null,
        radiusOverrideM = null,
        requireLinkedPlotId = null,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(top = 172.dp, end = 16.dp),
    )
}

// MARK: - Card

private data class MiniYou(val eastM: Float, val northM: Float, val facingDeg: Float?)
private data class MiniTreeDot(val eastM: Float, val northM: Float, val warn: Boolean)

@Composable
private fun PlotMiniMapCard(
    plotNumber: Int?,
    trees: List<Tree>,
    plotCenterLat: Double?,
    plotCenterLon: Double?,
    radiusOverrideM: Double?,
    requireLinkedPlotId: UUID?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val controller = ArSessionHub.controller
    val radiusM = radiusOverrideM ?: ArSessionHub.plotRadiusM

    // Shared GPS (ref-counted subscriber) + camera-forward compass. No
    // permission launcher here: the scan screens' GPS badge / the cruise
    // screens already own the request; without a grant the widget just
    // loses the GPS fallback + declination.
    val location = remember { LocationService.shared(context) }
    val compass = remember { CameraFacingCompass(context, location) }
    val aligner = remember { ArNorthAligner() }
    DisposableEffect(Unit) {
        location.acquire()
        compass.start()
        onDispose {
            compass.stop()
            location.release()
        }
    }

    var you by remember { mutableStateOf<MiniYou?>(null) }

    // 5 Hz tick — the same cadence as the sampling screens' boundary check.
    // Updates ONE state holder; quantized to 5 cm / 2° so sub-jitter
    // movement doesn't recompose the card (snapshot equality drops
    // no-change assignments). The Canvas below is the only reader.
    LaunchedEffect(plotCenterLat, plotCenterLon, requireLinkedPlotId) {
        while (true) {
            val camYaw = controller.cameraWorldYawDeg()
            aligner.update(compass.azimuthTrueDeg, camYaw)
            val deltaDeg = aligner.deltaDeg
            // The anchor may only stand in for the plot centre when it
            // actually marks THIS plot (iOS anchorMatchesPlot rule).
            val anchorMatchesPlot = requireLinkedPlotId == null ||
                ArSessionHub.linkedCruisePlotId == requireLinkedPlotId
            val anchor = if (anchorMatchesPlot) ArSessionHub.plotCenterWorld() else null
            val cam = controller.currentCameraPosition()
            val next = if (anchor != null && cam != null && deltaDeg != null) {
                // AR path: rotate the AR-frame displacement (X≈E0, −Z≈N0)
                // into true ENU — same math as OffsetFlow confirmPlotCenter.
                val east0 = (cam.x - anchor.x).toDouble()
                val north0 = (-(cam.z - anchor.z)).toDouble()
                val th = Math.toRadians(deltaDeg)
                val east = east0 * cos(th) + north0 * sin(th)
                val north = north0 * cos(th) - east0 * sin(th)
                // Facing = AR yaw re-aligned by the smoothed delta (the AR
                // yaw is the low-noise term); raw compass when the aim is
                // too vertical for a stable AR yaw this tick.
                val facing = if (camYaw != null) camYaw + deltaDeg else compass.azimuthTrueDeg
                MiniYou(quantizeM(east), quantizeM(north), facing?.let { quantizeDeg(it) })
            } else {
                // GPS-ENU fallback: live fix vs the plot centre lat/lon.
                val fix = location.latestSnapshot.value
                if (fix != null && plotCenterLat != null && plotCenterLon != null) {
                    val d = GeoMath.distanceM(plotCenterLat, plotCenterLon, fix.latitude, fix.longitude)
                    val b = Math.toRadians(GeoMath.bearingDeg(plotCenterLat, plotCenterLon, fix.latitude, fix.longitude))
                    MiniYou(
                        quantizeM(d * sin(b)),
                        quantizeM(d * cos(b)),
                        compass.azimuthTrueDeg?.let { quantizeDeg(it) },
                    )
                } else {
                    null
                }
            }
            you = next
            delay(200)
        }
    }

    // Tree ENU offsets — derived only when the tree list / centre change
    // (Accept-time events), never on the tick.
    val treeDots = remember(trees, plotCenterLat, plotCenterLon) {
        if (plotCenterLat == null || plotCenterLon == null) {
            emptyList()
        } else {
            trees.mapNotNull { t ->
                val lat = t.latitude ?: return@mapNotNull null
                val lon = t.longitude ?: return@mapNotNull null
                val d = GeoMath.distanceM(plotCenterLat, plotCenterLon, lat, lon)
                val b = Math.toRadians(GeoMath.bearingDeg(plotCenterLat, plotCenterLon, lat, lon))
                val warn = t.dbhConfidence != ConfidenceTier.GREEN ||
                    (t.heightConfidence != null && t.heightConfidence != ConfidenceTier.GREEN)
                MiniTreeDot((d * sin(b)).toFloat(), (d * cos(b)).toFloat(), warn)
            }
        }
    }

    val okColor = Forestix.colors.confidenceOk
    val warnColor = Forestix.colors.confidenceWarn

    Box(
        modifier
            .size(CARD_SIZE)
            .clip(ForestixRadius.card)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), ForestixRadius.card),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val ringR = size.minDimension * RING_FRACTION / 2f
            val mToPx = (ringR / radiusM.coerceAtLeast(0.5)).toFloat()

            // Plot boundary ring (AR-ring cyan) + centre dot — the map is
            // plot-relative, not user-centred.
            drawCircle(RING_CYAN.copy(alpha = 0.9f), radius = ringR, center = c, style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(Color.White, radius = 1.5.dp.toPx(), center = c)

            // Measured trees — confidence-tinted, clamped to the ring edge.
            treeDots.forEach { t ->
                val p = enToPx(t.eastM, t.northM, mToPx, ringR, c)
                drawCircle(if (t.warn) warnColor else okColor, radius = 2.5.dp.toPx(), center = p)
            }

            // YOU — 7 dp you-blue dot + heading wedge (iOS youMark: a
            // 7×5 triangle orbiting 6.5 dp out at the heading angle).
            you?.let { u ->
                val p = enToPx(u.eastM, u.northM, mToPx, ringR, c)
                u.facingDeg?.let { f ->
                    val a = Math.toRadians(f.toDouble())
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val perp = Offset(-dir.y, dir.x)
                    val tip = p + dir * 9.dp.toPx()
                    val base = p + dir * 4.dp.toPx()
                    val b1 = base + perp * 3.5.dp.toPx()
                    val b2 = base - perp * 3.5.dp.toPx()
                    val wedge = Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(b1.x, b1.y)
                        lineTo(b2.x, b2.y)
                        close()
                    }
                    drawPath(wedge, YOU_BLUE)
                }
                drawCircle(YOU_BLUE, radius = 3.5.dp.toPx(), center = p)
                drawCircle(Color.White.copy(alpha = 0.85f), radius = 3.5.dp.toPx(), center = p, style = Stroke(width = 1.dp.toPx()))
            }
        }

        // Header: "PLOT 2 · 5" (plot number · tree count); plain "PLOT"
        // for the number-less quick ring (iOS headerText).
        Text(
            if (plotNumber != null) "PLOT $plotNumber · ${trees.size}" else "PLOT",
            style = MiniMapHeaderStyle,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 6.dp),
        )
        // North tick — just inside the ring's top point (ring top sits at
        // 58 − 45.2 ≈ 13 dp; iOS centres the glyph 7 pt below that).
        Text(
            "N",
            style = MiniMapLabelStyle,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
        )
        Text(
            String.format(Locale.US, "r %.0f m", radiusM),
            style = MiniMapLabelStyle,
            color = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 5.dp),
        )
    }
}

private val MiniMapHeaderStyle = TextStyle(
    fontSize = 9.sp,
    fontWeight = FontWeight.SemiBold,
    fontFamily = FontFamily.Monospace,
    letterSpacing = 0.5.sp,
)

private val MiniMapLabelStyle = TextStyle(
    fontSize = 8.sp,
    fontWeight = FontWeight.Medium,
    fontFamily = FontFamily.Monospace,
)

/// Quantize a metre offset to 5 cm (iOS parity) so sub-jitter movement
/// doesn't invalidate the card.
private fun quantizeM(v: Double): Float = (Math.round(v * 20.0) / 20.0).toFloat()

/// Quantize a heading to 2°, normalized to 0…360 (iOS parity).
private fun quantizeDeg(v: Double): Float {
    val n = ((v % 360.0) + 360.0) % 360.0
    return (Math.round(n / 2.0) * 2.0).toFloat()
}

/// North-up ENU metres → card pixels (+E right, +N up), clamped to the
/// ring edge when outside the plot (iOS point()).
private fun enToPx(
    eastM: Float,
    northM: Float,
    mToPx: Float,
    ringR: Float,
    c: Offset,
): Offset {
    var x = eastM * mToPx
    var y = -northM * mToPx
    val r = sqrt(x * x + y * y)
    if (r > ringR && r > 0f) {
        val s = ringR / r
        x *= s
        y *= s
    }
    return Offset(c.x + x, c.y + y)
}

// MARK: - AR→north alignment

/// Circular EMA over (compass azimuth − AR camera yaw) samples. One noisy
/// compass reading can't swing the schematic; the smoothed delta converges
/// in ~1 s at the 5 Hz tick (α 0.15). Vector-averaged so the 0°/360° wrap
/// is handled correctly.
private class ArNorthAligner {
    private var s = 0.0
    private var c = 0.0
    private var primed = false

    fun update(azimuthTrueDeg: Double?, camYawDeg: Double?) {
        if (azimuthTrueDeg == null || camYawDeg == null) return
        val v = Math.toRadians(azimuthTrueDeg - camYawDeg)
        if (!primed) {
            s = sin(v)
            c = cos(v)
            primed = true
        } else {
            s = s * (1 - ALPHA) + sin(v) * ALPHA
            c = c * (1 - ALPHA) + cos(v) * ALPHA
        }
    }

    val deltaDeg: Double?
        get() = if (primed) Math.toDegrees(atan2(s, c)) else null

    private companion object {
        const val ALPHA = 0.15
    }
}

/// Camera-forward compass for the upright AR posture — the same rotation-
/// vector remap + declination rules as OffsetFlowScreen's AnchorCompass
/// (LocationService.headingTrueDeg skips the remap because the cruise map
/// holds the phone flat; un-remapped azimuth is ill-conditioned upright).
private class CameraFacingCompass(
    context: Context,
    private val location: LocationService,
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /// Latest camera-forward azimuth in degrees true north (0…360);
    /// null before the first sensor sample.
    @Volatile
    var azimuthTrueDeg: Double? = null
        private set

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.remapCoordinateSystem(
            rotationMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble())
        location.latestSnapshot.value?.let { snap ->
            deg += GeomagneticField(
                snap.latitude.toFloat(),
                snap.longitude.toFloat(),
                0f,
                System.currentTimeMillis(),
            ).declination
        }
        azimuthTrueDeg = (deg + 360.0) % 360.0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
