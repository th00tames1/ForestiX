// ARCore camera host — the Android analogue of iOS ARCameraView. Binds the
// screen to the app-scoped SHARED AR session (ArSessionHub): one ARCore
// Session + one render surface reparented across the AR screens, so world
// coordinates — and the anchored sampling plot — survive navigation.
//
// Per-screen knobs applied on entry (and live on change):
//  - preferDepth: hit-test filtering policy on the shared ArController.
//  - enableDepth: whether the SESSION runs the Depth API at all. Screens
//    that never consume depth images can turn it off — ARCore's ML
//    depth-from-motion is a large per-frame cost on non-ToF phones.
//  - planeRenderer: SceneView's detected-plane grid visualization.
//  - plotOverlay: how the active sampling plot renders on this screen
//    (OWNER full alpha / SUBDUED 0.5 alpha / HIDDEN).
//  - depthOcclusion: camera-stream depth occlusion (virtual geometry hides
//    behind real surfaces). Sampling/plot-creation only — needs enableDepth
//    and burns a depth image per frame (see ArSessionHub docs).
//
// The permission + availability gates stay per-screen; the session itself
// pauses whenever no AR screen is attached (map home) and resumes on the
// next one. ARCore keeps anchors across that pause; relocalization after a
// long pause may take a few seconds (accepted, documented in ArSessionHub).

package com.hcjeong.forestix.ar

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.hcjeong.forestix.ui.theme.ForestixProminentButton

@Composable
fun ArCameraView(
    controller: ArController,
    markers: List<ArSceneMarker>,
    preferDepth: Boolean = true,
    modifier: Modifier = Modifier,
    enableDepth: Boolean = true,
    planeRenderer: Boolean = true,
    plotOverlay: ArSessionHub.PlotOverlay = ArSessionHub.PlotOverlay.HIDDEN,
    depthOcclusion: Boolean = false,
) {
    // Screens must pass the shared controller — the hub only feeds frames
    // into ArSessionHub.controller.
    check(controller === ArSessionHub.controller) {
        "ArCameraView requires ArSessionHub.controller (shared AR session)"
    }

    // Runtime CAMERA permission gate. ARCore opens the camera the moment
    // the session resumes; without a granted runtime permission that
    // crashes immediately (manifest <uses-permission> alone is NOT enough
    // on Android 6+). Request it here and only bind the shared AR session
    // once granted.
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("Camera access is needed for AR measurement.", color = Color.White)
                ForestixProminentButton(label = "Grant camera access") {
                    launcher.launch(Manifest.permission.CAMERA)
                }
            }
        }
        return
    }

    // Capability gate via ARCore's official availability check (NOT a
    // throwaway session resume — that gave false negatives on capable
    // devices). With HIGH_SAMPLING_RATE_SENSORS now declared, a supported
    // device resumes cleanly; unsupported devices / emulators get a graceful
    // message instead of SceneView's uncatchable resume crash.
    var arChecked by remember { mutableStateOf(false) }
    var arOk by remember { mutableStateOf(false) }
    var arError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        var avail = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)
        var guard = 0
        while (avail == com.google.ar.core.ArCoreApk.Availability.UNKNOWN_CHECKING && guard < 50) {
            kotlinx.coroutines.delay(200)
            avail = com.google.ar.core.ArCoreApk.getInstance().checkAvailability(context)
            guard++
        }
        arOk = avail.isSupported
        arError = if (avail.isSupported) null else avail.name
        arChecked = true
    }

    when {
        !arChecked -> Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
        arOk -> SharedArHost(markers, preferDepth, enableDepth, planeRenderer, plotOverlay, depthOcclusion, modifier)
        else -> Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text("AR isn't available on this device.", color = Color.White)
                Text(
                    "AR measurement needs a physical ARCore-supported phone with a working camera + motion sensors. Emulators usually can't run AR.",
                    color = Color.White.copy(alpha = 0.7f),
                )
                arError?.let { Text(it, color = Color.White.copy(alpha = 0.5f)) }
            }
        }
    }
}

@Composable
private fun SharedArHost(
    markers: List<ArSceneMarker>,
    preferDepth: Boolean,
    enableDepth: Boolean,
    planeRenderer: Boolean,
    plotOverlay: ArSessionHub.PlotOverlay,
    depthOcclusion: Boolean,
    modifier: Modifier,
) {
    val context = LocalContext.current

    // Attach this screen to the shared session (resumes it, applies the
    // screen's session config + overlay style). The token keeps a
    // navigation transition safe: the NEXT AR screen attaches before this
    // one disposes, so only the current attachment may pause the session or
    // touch the shared scene — an exiting screen's still-running effects
    // present a stale token and are ignored by the hub.
    var attachToken by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(Unit) {
        val token = ArSessionHub.attach(
            context, preferDepth, enableDepth, planeRenderer, plotOverlay,
            depthOcclusion = depthOcclusion,
        )
        attachToken = token
        onDispose { ArSessionHub.detach(token) }
    }

    // Live per-screen knob changes (e.g. sampling turns the plane grid
    // off once the plot centre is placed).
    LaunchedEffect(attachToken, preferDepth, enableDepth, planeRenderer, plotOverlay, depthOcclusion) {
        val token = attachToken ?: return@LaunchedEffect
        ArSessionHub.updateScreenConfig(
            token, preferDepth, enableDepth, planeRenderer, plotOverlay,
            depthOcclusion = depthOcclusion,
        )
    }

    // Sync this screen's marker list into the shared scene. In-place moves
    // for position-only changes; structural changes rebuild via the hub's
    // cached materials (see ArSessionHub.syncMarkers).
    LaunchedEffect(attachToken, markers) {
        val token = attachToken ?: return@LaunchedEffect
        ArSessionHub.syncMarkers(token, markers)
    }

    AndroidView(
        modifier = modifier,
        factory = {
            // The shared view may still be parented to the PREVIOUS AR
            // screen's holder during a navigation transition — steal it.
            ArSessionHub.obtainView(context).also { view ->
                (view.parent as? ViewGroup)?.removeView(view)
            }
        },
    )
}
