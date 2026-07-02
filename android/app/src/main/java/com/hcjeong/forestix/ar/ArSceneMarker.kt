// World-anchored AR marker — port of the iOS ARSceneMarker value type.
// A sphere or flat/tall cylinder placed at a world position with an RGBA
// tint, rendered by ArCameraView as a SceneView node.

package com.hcjeong.forestix.ar

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun length() = kotlin.math.sqrt(dot(this))

    /// Unit vector in the same direction; returns zero when degenerate so
    /// callers must guard length before use (the AR-caliper does).
    fun normalize(): Vec3 {
        val len = length()
        if (len < 1e-9f) return Vec3(0f, 0f, 0f)
        val inv = 1f / len
        return Vec3(x * inv, y * inv, z * inv)
    }
}

fun distance(a: Vec3, b: Vec3) = (a - b).length()

sealed class MarkerShape {
    data class Sphere(val radiusM: Float) : MarkerShape()
    data class Cylinder(val radiusM: Float, val heightM: Float) : MarkerShape()

    /// Flat horizontal ring outline (annulus). Used for the sampling-plot
    /// boundary so we draw only the rim — a filled translucent disk that
    /// wide created huge overdraw and tanked the frame rate (mirror of the
    /// iOS ring fix). `radiusM` is the centre-line radius, `thicknessM` the
    /// rim band width.
    data class Ring(val radiusM: Float, val thicknessM: Float) : MarkerShape()
}

data class ArSceneMarker(
    val worldPosition: Vec3,
    val shape: MarkerShape,
    val colorRGBA: FloatArray,   // [r,g,b,a] 0..1
    /// When true the marker grows with camera distance so its APPARENT
    /// (on-screen) size stays readable — an 8 cm sphere is fine at 3 m but
    /// nearly invisible from 15 m across a height walk-off. Natural size
    /// within the reference distance; linear growth beyond it, capped.
    /// (Mirror of the iOS ARSceneMarker.scalesWithDistance.)
    val scalesWithDistance: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArSceneMarker) return false
        return worldPosition == other.worldPosition && shape == other.shape &&
            colorRGBA.contentEquals(other.colorRGBA) && scalesWithDistance == other.scalesWithDistance
    }
    override fun hashCode(): Int =
        31 * (31 * (31 * worldPosition.hashCode() + shape.hashCode()) + colorRGBA.contentHashCode()) +
            scalesWithDistance.hashCode()
}
