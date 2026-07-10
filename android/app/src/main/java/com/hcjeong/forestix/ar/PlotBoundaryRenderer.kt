// Plot boundary math — port of iOS AR/PlotBoundaryRenderer.swift
// (spec §7.8 AR Plot Boundary Rendering + REQ-BND-001..004).
//
// iOS splits this file into a math layer and a RealityKit rendering
// layer. On Android only the math layer lives here — the rendering is
// handled by ArCameraView's MarkerShape.Ring node (ARBoundarySceneView
// builds the marker), so `makeRingEntity` has no Kotlin counterpart.
// `ringVertices` / `slopeCorrected` are still ported 1:1 because the
// view model publishes the vertex list for stem classification and for
// parity with the iOS published surface.

package com.hcjeong.forestix.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/// Float 2-vector (Swift `SIMD2<Float>`) for the XZ-plane membership math.
data class Vec2(val x: Float, val y: Float)

object PlotBoundaryRenderer {

    /// Default vertex count for the ring polyline. Spec §7.8 uses 72.
    const val defaultVertexCount: Int = 72

    /// Ring material parameters (spec §7.8 step 3): green emissive,
    /// alpha = 0.6, line width ~ 2 cm.
    data class RingStyle(
        val color: FloatArray = floatArrayOf(0f, 1f, 0f, 0.6f),   // rgba, 0..1
        val lineWidthM: Float = 0.02f,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RingStyle) return false
            return color.contentEquals(other.color) && lineWidthM == other.lineWidthM
        }
        override fun hashCode(): Int = 31 * color.contentHashCode() + lineWidthM.hashCode()
    }

    // MARK: - Math

    /// `count` points on the horizontal circle of radius R centered at
    /// `center` in the gravity-aligned AR world frame. The returned
    /// list has `count + 1` entries — the last vertex coincides with
    /// the first so callers can emit a closed line strip.
    fun ringVertices(
        center: Vec3,
        radiusM: Float,
        count: Int = defaultVertexCount,
    ): List<Vec3> {
        require(count >= 3) { "ring needs >= 3 vertices" }
        val out = ArrayList<Vec3>(count + 1)
        for (i in 0..count) {
            val theta = i.toFloat() * 2f * PI.toFloat() / count.toFloat()
            val x = center.x + radiusM * cos(theta)
            val z = center.z + radiusM * sin(theta)
            out.add(Vec3(x, center.y, z))
        }
        return out
    }

    /// Project each input vertex onto the ground by asking `sampler` for
    /// Y at (vertex.x, vertex.z). Vertices for which the sampler returns
    /// null keep their original Y — the ring degrades to a flat circle
    /// when the mesh is sparse rather than disappearing. (ARCore has no
    /// scene-mesh anchor stream, so the boundary view model always
    /// passes a null-returning sampler today.)
    fun slopeCorrected(
        vertices: List<Vec3>,
        sampler: (Float, Float) -> Float?,
    ): List<Vec3> = vertices.map { v ->
        val y = sampler(v.x, v.z)
        if (y != null) Vec3(v.x, y, v.z) else v
    }

    // MARK: - REQ-BND-003 in/out/borderline

    enum class StemMembership {
        INSIDE,
        OUTSIDE,
        BORDERLINE,
    }

    /// Fixed-area plot membership. Borderline band is +-0.2 m around the
    /// radius so field crews catch ambiguous calls.
    fun membership(
        stemPositionXZ: Vec2,
        centerXZ: Vec2,
        radiusM: Float,
        borderlineBandM: Float = 0.2f,
    ): StemMembership {
        val dx = stemPositionXZ.x - centerXZ.x
        val dz = stemPositionXZ.y - centerXZ.y
        val d = sqrt(dx * dx + dz * dz)
        if (abs(d - radiusM) <= borderlineBandM) return StemMembership.BORDERLINE
        return if (d < radiusM) StemMembership.INSIDE else StemMembership.OUTSIDE
    }

    /// Variable-radius-plot membership (REQ-BND-003). Callers compute
    /// the per-stem `limitDistanceM` from DBH and BAF and hand it here
    /// along with the measured `distanceToStemM`.
    fun membership(
        distanceToStemM: Float,
        limitDistanceM: Float,
        borderlineBandM: Float = 0.2f,
    ): StemMembership {
        if (abs(distanceToStemM - limitDistanceM) <= borderlineBandM) {
            return StemMembership.BORDERLINE
        }
        return if (distanceToStemM < limitDistanceM) StemMembership.INSIDE
        else StemMembership.OUTSIDE
    }

    // MARK: - REQ-BND-004 drift warn

    /// True when the user has walked farther than `driftRadiusM` (spec
    /// default 15 m) from the plot center.
    fun isDriftedBeyond(
        userXZ: Vec2,
        centerXZ: Vec2,
        driftRadiusM: Float = 15f,
    ): Boolean {
        val dx = userXZ.x - centerXZ.x
        val dz = userXZ.y - centerXZ.y
        return sqrt(dx * dx + dz * dz) > driftRadiusM
    }
}
