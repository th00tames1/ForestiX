// ARCore camera host — the Android analogue of iOS ARCameraView. Wraps
// SceneView's `ARScene` composable, forwards each tracked frame to the
// shared ArController, and renders the supplied world-anchored markers as
// SceneView nodes (sphere / cylinder), matching ARSceneMarker on iOS.
//
// NOTE: SceneView's Filament-backed node API evolves between minor
// versions; the marker-node construction below targets arsceneview 2.2.x
// and should be validated on-device. The hit-testing / measurement math
// lives in ArController and is independent of the rendering layer.

package com.hcjeong.forestix.ar

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.ar.core.Config
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.ar.ARScene
import io.github.sceneview.ar.rememberARCameraNode
import io.github.sceneview.node.CylinderNode
import io.github.sceneview.node.Node
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberNodes
import io.github.sceneview.rememberView

@Composable
fun ArCameraView(
    controller: ArController,
    markers: List<ArSceneMarker>,
    preferDepth: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Runtime CAMERA permission gate. ARCore/SceneView opens the camera the
    // moment ARScene is composed; without a granted runtime permission that
    // crashes immediately (manifest <uses-permission> alone is NOT enough on
    // Android 6+). Request it here and only host the AR scene once granted.
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
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant camera access")
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
        arOk -> ArSceneHost(controller, markers, preferDepth, modifier)
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
private fun ArSceneHost(
    controller: ArController,
    markers: List<ArSceneMarker>,
    preferDepth: Boolean,
    modifier: Modifier,
) {
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberView(engine)
    val cameraNode = rememberARCameraNode(engine)
    val childNodes = rememberNodes()
    val androidView = LocalView.current

    // Rebuild marker nodes whenever the marker set changes. Done in a
    // side-effect (not inline during composition) so we don't mutate the
    // snapshot node list mid-composition.
    androidx.compose.runtime.LaunchedEffect(markers) {
        childNodes.clear()
        markers.forEach { marker ->
            val color = marker.colorRGBA
            val material = materialLoader.createColorInstance(
                color = dev.romainguy.kotlin.math.Float4(color[0], color[1], color[2], color[3]),
                metallic = 0f, roughness = 1f, reflectance = 0f,
            )
            val node: Node = when (val s = marker.shape) {
                is MarkerShape.Sphere -> SphereNode(engine, radius = s.radiusM, materialInstance = material)
                is MarkerShape.Cylinder -> CylinderNode(engine, radius = s.radiusM, height = s.heightM, materialInstance = material)
                is MarkerShape.Ring -> buildRingNode(engine, s, material)
            }
            node.worldPosition = Float3(marker.worldPosition.x, marker.worldPosition.y, marker.worldPosition.z)
            childNodes.add(node)
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.frame = null }
    }

    ARScene(
        modifier = modifier,
        engine = engine,
        view = view,
        materialLoader = materialLoader,
        cameraNode = cameraNode,
        childNodes = childNodes,
        planeRenderer = true,
        sessionConfiguration = { session, config ->
            // Enable the Depth API when available (LiDAR-equivalent) so
            // hit-tests resolve against depth points, not just planes.
            val depthSupported = session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            controller.supportsDepth = depthSupported
            config.depthMode = if (depthSupported && preferDepth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
            config.lightEstimationMode = Config.LightEstimationMode.DISABLED
        },
        onSessionUpdated = { session, frame ->
            controller.viewWidthPx = androidView.width
            controller.viewHeightPx = androidView.height
            controller.onUpdate(session, frame)
        },
    )
}

/// Builds a flat annulus (ring) node — the rim only — mirroring the iOS
/// generateRing(). Cheap vs a filled translucent disk, so a 30 m boundary
/// doesn't tank the frame rate. NOTE: SceneView's custom-geometry API is
/// version-sensitive; validate on-device alongside the rest of the AR
/// rendering layer.
private fun buildRingNode(
    engine: com.google.android.filament.Engine,
    shape: MarkerShape.Ring,
    material: com.google.android.filament.MaterialInstance,
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
