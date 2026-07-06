// Three-tier GPS accuracy badge — port of iOS Screens/GPSAccuracyBadge.swift.
//
// Renders a compact pill on the scan camera overlay so the cruiser
// can tell at a glance whether they're standing under a clean sky
// or under canopy. Three tiers map onto the existing confidence
// palette (Good / Fair / Check) so the colour vocabulary across
// the app stays unified:
//
//   • ≤ 5 m   → "GPS good"   (confidenceOk)
//   • 5–15 m  → "GPS fair"   (confidenceWarn)
//   • > 15 m / unknown → "GPS check" (confidenceBad)
//
// The badge owns a local `LocationService` so it works without any
// AppEnvironment plumbing — it lights up regardless of which scan
// screen hosts it. Without a granted location permission the service
// never produces a fix, so the badge silently falls back to "check"
// (the iOS macOS-stub behaviour).

package com.hcjeong.forestix.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hcjeong.forestix.positioning.LocationService
import com.hcjeong.forestix.ui.theme.Forestix

@Composable
fun GPSAccuracyBadge(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val location = remember { LocationService(context) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        location.onPermissionResult(granted)
        if (granted) location.start()
    }
    LaunchedEffect(Unit) {
        if (LocationService.hasLocationPermission(context)) {
            location.start()
        } else {
            launcher.launch(LocationService.PERMISSION)
        }
    }
    DisposableEffect(Unit) {
        onDispose { location.stop() }
    }

    val snapshot by location.latestSnapshot.collectAsStateWithLifecycle()

    // MARK: - Tier classification. Mirror of the iOS rule, including
    // the negative-accuracy "no fix" guard.
    val colors = Forestix.colors
    val acc = snapshot?.horizontalAccuracyM
    val (label, color) = when {
        acc == null || acc <= 0 -> "GPS check" to colors.confidenceBad
        acc <= 5 -> "GPS good" to colors.confidenceOk
        acc <= 15 -> "GPS fair" to colors.confidenceWarn
        else -> "GPS check" to colors.confidenceBad
    }

    val capsule = RoundedCornerShape(percent = 50)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(capsule)
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.20f), capsule)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { contentDescription = "GPS ${label.removePrefix("GPS ").lowercase()}" },
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
                .border(0.5.dp, Color.Black.copy(alpha = 0.4f), CircleShape),
        )
        Text(label, style = Forestix.type.dataSmall, color = Color.White)
    }
}
