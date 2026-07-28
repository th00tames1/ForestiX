// THE GPS chip — one definition, shown on the map home and on both
// measurement screens. Port of iOS Screens/GPSFixChip.swift.
//
// FIELD REPORT 11/12. Two things came back from the stand:
//
//   • The map's chip was the readout the cruiser trusted, and it was only
//     on the map. Measuring a tree meant a different widget entirely —
//     GPSAccuracyBadge, a "GPS good / fair / check" pill built on
//     horizontal accuracy. Two vocabularies for one question ("can the app
//     see where I am right now?"), and the one shown at the moment a
//     position is actually STORED on a measurement was the vaguer of the
//     two. That badge is gone; this is what all three screens show.
//
//   • The stale colour read as brown. It was confidenceWarn, which is
//     deepened to #9A6414 so it can carry text on paper — correct for a
//     tier label, wrong for a 7 dp dot. The dots now come from the
//     dedicated fixStale / fixLost pair.
//
// WHAT THE DOT MEANS:
//
//   green   the fix is live (<= PLOT_FIX_MAX_AGE_MS, 5 s) — this is also
//           exactly the window in which the app will draw a position and
//           judge inside/outside, so the dot and the map agree
//   amber   stale, but there WAS a fix within the last minute — ordinary
//           under-canopy dropout, keep working
//   red     no fix at all, or nothing for over a minute — stop trusting
//           any position on screen and step out for sky
//
// The age is a MAGNITUDE (abs). Location.time is satellite UTC for the GPS
// provider, so a device clock running behind it makes the plain difference
// negative, and clamping that to zero used to paint a green dot on a fix of
// any age while the map beside it drew nothing.

package com.hcjeong.forestix.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.hcjeong.forestix.positioning.CLLocationSnapshot
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.screens.cruise.PLOT_FIX_LOST_AGE_MS
import com.hcjeong.forestix.ui.screens.cruise.PLOT_FIX_MAX_AGE_MS
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs

/// What the dot says. Named states rather than a bare colour, so the spoken
/// label and the drawn dot cannot drift apart.
internal enum class GpsFixState { LIVE, STALE, LOST }

internal fun gpsFixState(ageMs: Long?): GpsFixState = when {
    ageMs == null -> GpsFixState.LOST
    ageMs > PLOT_FIX_LOST_AGE_MS -> GpsFixState.LOST
    ageMs > PLOT_FIX_MAX_AGE_MS -> GpsFixState.STALE
    else -> GpsFixState.LIVE
}

/// "12s" / "3m". Field-requested over the old "12 s ago": the chip is
/// glanced at, not read, and the two words carried nothing the number beside
/// a stale dot did not already say. Same strings on iOS.
internal fun gpsFixAgeText(ageMs: Long): String {
    val sec = ageMs / 1_000
    return if (sec < 60) "${sec}s" else "${sec / 60}m"
}

/**
 * The GPS chip.
 *
 * @param fix the host screen's live snapshot, when it already collects one
 *   (the map does). Null falls back to the shared service's own flow, then
 *   to the last fix any screen saved.
 * @param acquiresService true on the AR measurement screens: the chip is the
 *   only thing asking for location in its corner there, and holding the
 *   service alive for the length of a scan is what puts a fix on the stored
 *   reading. The map already owns an acquire for its own lifetime.
 */
@Composable
fun GpsFixChip(
    fix: CLLocationSnapshot? = null,
    acquiresService: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val location = remember { LocationService.shared(context) }

    if (acquiresService) {
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            // FINE + COARSE requested together (Android 12+ requirement);
            // either grant is enough to run, coarse just widens the fix.
            val granted = results.values.any { it }
            location.onPermissionResult(granted)
            if (granted) location.start()
        }
        LaunchedEffect(Unit) {
            if (!LocationService.hasLocationPermission(context)) {
                launcher.launch(LocationService.PERMISSIONS)
            }
        }
        DisposableEffect(Unit) {
            location.acquire()
            onDispose { location.release() }
        }
    }

    val own by location.latestSnapshot.collectAsStateWithLifecycle()
    val snap = fix ?: own ?: LocationService.lastGlobalFix

    // 1 s tick so the age suffix and the dot tier move while the chip is on
    // screen — a frozen "3s" on a fix that died two minutes ago is the
    // failure this whole chip exists to prevent.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val ageMs = snap?.let { abs(nowMs - it.timestamp) }
    val state = gpsFixState(ageMs)

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .clip(ForestixRadius.control)
            .background(colors.surface)
            .border(1.dp, colors.divider, ForestixRadius.control)
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .semantics { contentDescription = gpsAccessibilityText(snap, ageMs, state) },
    ) {
        if (snap != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GpsDot(state)
                GpsCoordRow("X", String.format(Locale.US, "%.5f", snap.latitude))
            }
            Box(Modifier.padding(start = 13.dp)) {
                GpsCoordRow("Y", String.format(Locale.US, "%.5f", snap.longitude))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(start = 13.dp),
            ) {
                GpsCoordRow(
                    "Z",
                    snap.altitudeM?.let { String.format(Locale.US, "%.0f m", it) } ?: "—",
                )
                // The age only appears once it MATTERS. A live fix is the
                // normal case and does not need a number ticking beside it;
                // anything else does.
                if (state != GpsFixState.LIVE && ageMs != null) {
                    Text(
                        "· ${gpsFixAgeText(ageMs)}",
                        style = type.dataSmall.copy(fontSize = 11.sp),
                        color = colors.textTertiary,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                GpsDot(GpsFixState.LOST)
                Text(
                    "no fix",
                    style = type.dataSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
    }
}

/// The status dot. The hairline ring is load-bearing, not decoration: amber
/// on a white surface is a shape the eye can lose, and the ring gives it an
/// edge in both appearances without darkening the hue back towards the brown
/// this replaced.
@Composable
private fun GpsDot(state: GpsFixState) {
    val colors = Forestix.colors
    val fill = when (state) {
        GpsFixState.LIVE -> colors.confidenceOk
        GpsFixState.STALE -> colors.fixStale
        GpsFixState.LOST -> colors.fixLost
    }
    Box(
        Modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(fill)
            .border(0.5.dp, colors.textPrimary.copy(alpha = 0.25f), CircleShape),
    )
}

/// One labelled coordinate line — dim mono axis label, semibold mono value.
/// X = latitude, Y = longitude, Z = altitude, the axis convention this crew
/// asked for. iOS `coordRow` 1:1.
@Composable
private fun GpsCoordRow(label: String, value: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            style = type.dataSmall.copy(fontSize = 11.sp),
            color = colors.textTertiary,
            maxLines = 1,
        )
        Text(
            value,
            style = type.dataSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = colors.textPrimary,
            maxLines = 1,
        )
    }
}

/// TalkBack gets the long form — it is heard once, not glanced at, so the
/// abbreviation that helps the eye would only cost the ear.
private fun gpsAccessibilityText(
    snap: CLLocationSnapshot?,
    ageMs: Long?,
    state: GpsFixState,
): String {
    if (snap == null) return "No GPS fix"
    var out = String.format(Locale.US, "GPS fix %.5f, %.5f", snap.latitude, snap.longitude)
    snap.altitudeM?.let { out += String.format(Locale.US, ", altitude %.0f metres", it) }
    if (state != GpsFixState.LIVE && ageMs != null) {
        val sec = ageMs / 1_000
        out += if (sec < 60) ", $sec seconds old" else ", ${sec / 60} minutes old"
    }
    return out
}
