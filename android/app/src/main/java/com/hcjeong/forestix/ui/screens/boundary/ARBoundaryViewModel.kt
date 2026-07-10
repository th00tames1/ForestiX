// Port of iOS ViewModels/ARBoundaryViewModel.swift (spec §5.1 ARBoundary +
// §7.8 + REQ-BND-001..004). Holds the plot center (set from the current
// camera pose), the fixed-area radius, and the live user pose so the screen
// can render a 72-vertex ring, classify stems as inside/outside/borderline,
// and raise the 15 m drift warn.
//
// iOS keeps a `GroundMeshSnapshot` refreshed from ARMeshAnchor updates so
// slope correction is testable; ARCore has no scene-mesh anchor stream, so
// the sampler always returns null and the ring stays flat at the center's Y
// (the documented iOS degradation path).

package com.hcjeong.forestix.ui.screens.boundary

import com.hcjeong.forestix.ar.ArController
import com.hcjeong.forestix.ar.PlotBoundaryRenderer
import com.hcjeong.forestix.ar.Vec2
import com.hcjeong.forestix.ar.Vec3
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

class ARBoundaryViewModel(
    /// Android analogue of the iOS ARKitSessionManager: the shared frame
    /// bridge the hosting ArCameraView keeps updated.
    val session: ArController,
) {

    // MARK: - Published surface

    /// World-space plot center. Null until the user taps to set it.
    private val _centerWorld = MutableStateFlow<Vec3?>(null)
    val centerWorld: StateFlow<Vec3?> = _centerWorld.asStateFlow()

    /// 72 ring vertices + closure vertex, slope-corrected against the
    /// ground sampler. Empty until center is set.
    private val _ringVertices = MutableStateFlow<List<Vec3>>(emptyList())
    val ringVertices: StateFlow<List<Vec3>> = _ringVertices.asStateFlow()

    /// Fixed-area plot radius. Default 11.28 m = sqrt(400/pi) -> 1/25 ha.
    private val _radiusM = MutableStateFlow(11.28f)
    val radiusM: StateFlow<Float> = _radiusM.asStateFlow()

    /// Live XZ distance from the current camera to the plot center.
    private val _userDistanceM = MutableStateFlow(0f)
    val userDistanceM: StateFlow<Float> = _userDistanceM.asStateFlow()

    /// REQ-BND-004: true when user has walked > 15 m from center.
    private val _isDrifted = MutableStateFlow(false)
    val isDrifted: StateFlow<Boolean> = _isDrifted.asStateFlow()

    /// Drift warn threshold in metres. Default 15 m.
    private val _driftRadiusM = MutableStateFlow(15f)
    val driftRadiusM: StateFlow<Float> = _driftRadiusM.asStateFlow()

    // MARK: - Lifecycle
    //
    // On iOS onAppear/onDisappear run and pause the ARKit session; on
    // Android the ArCameraView composable owns the ARCore session
    // lifecycle, so these are kept only for call-site parity.

    fun onAppear() {}

    fun onDisappear() {}

    // MARK: - Center placement

    /// Set the plot center at a world-space point.
    fun setCenter(point: Vec3) {
        _centerWorld.value = point
        refreshRingVertices()
    }

    /// Production convenience: drop the center at the current camera
    /// position. iOS additionally projects the camera XZ onto the LiDAR
    /// ground mesh; ARCore has no equivalent sampler, so this is the iOS
    /// fallback path (camera's own Y). Returns false if no AR frame has
    /// been received yet.
    fun setCenterAtCurrentCamera(): Boolean {
        val c = session.currentCameraPosition() ?: return false
        setCenter(c)
        return true
    }

    fun clearCenter() {
        _centerWorld.value = null
        _ringVertices.value = emptyList()
        _userDistanceM.value = 0f
        _isDrifted.value = false
    }

    fun setRadiusM(r: Float) {
        _radiusM.value = r
        refreshRingVertices()
    }

    // MARK: - Stem classification (REQ-BND-003)

    fun membership(forStemXZ: Vec2): PlotBoundaryRenderer.StemMembership? {
        val c = _centerWorld.value ?: return null
        return PlotBoundaryRenderer.membership(
            stemPositionXZ = forStemXZ,
            centerXZ = Vec2(c.x, c.z),
            radiusM = _radiusM.value,
        )
    }

    // MARK: - Internals

    private fun refreshRingVertices() {
        val center = _centerWorld.value
        if (center == null) {
            _ringVertices.value = emptyList()
            return
        }
        val flat = PlotBoundaryRenderer.ringVertices(
            center = center, radiusM = _radiusM.value)
        // No ground-mesh sampler on ARCore — flat ring (iOS sparse-mesh path).
        _ringVertices.value = PlotBoundaryRenderer.slopeCorrected(flat) { _, _ -> null }
    }

    /// Pump a camera position (polled from the AR frame by the screen).
    fun updateUserPosition(point: Vec3) {
        val c = _centerWorld.value
        if (c == null) {
            _userDistanceM.value = 0f
            _isDrifted.value = false
            return
        }
        val dx = point.x - c.x
        val dz = point.z - c.z
        _userDistanceM.value = sqrt(dx * dx + dz * dz)
        _isDrifted.value = PlotBoundaryRenderer.isDriftedBeyond(
            userXZ = Vec2(point.x, point.z),
            centerXZ = Vec2(c.x, c.z),
            driftRadiusM = _driftRadiusM.value,
        )
    }
}
