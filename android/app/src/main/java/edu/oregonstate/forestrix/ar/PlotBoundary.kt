package edu.oregonstate.forestrix.ar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class Vec2(val x: Float, val z: Float)
data class BoundaryVertex(val x: Float, val y: Float, val z: Float)

enum class StemMembership { INSIDE, OUTSIDE, BORDERLINE }

object PlotBoundary {
    const val DefaultVertexCount = 72

    fun ringVertices(
        center: BoundaryVertex,
        radiusM: Float,
        count: Int = DefaultVertexCount
    ): List<BoundaryVertex> {
        require(count >= 3)
        return (0..count).map { i ->
            val theta = (i.toDouble() * 2.0 * PI / count).toFloat()
            BoundaryVertex(
                x = center.x + radiusM * cos(theta),
                y = center.y,
                z = center.z + radiusM * sin(theta)
            )
        }
    }

    fun slopeCorrected(
        vertices: List<BoundaryVertex>,
        groundYAt: (Float, Float) -> Float?
    ): List<BoundaryVertex> =
        vertices.map { v ->
            val y = groundYAt(v.x, v.z)
            if (y == null) v else v.copy(y = y)
        }

    fun membership(stem: Vec2, center: Vec2, radiusM: Float, borderlineBandM: Float = 0.2f): StemMembership {
        val dx = stem.x - center.x
        val dz = stem.z - center.z
        val distance = sqrt(dx * dx + dz * dz)
        return when {
            abs(distance - radiusM) <= borderlineBandM -> StemMembership.BORDERLINE
            distance < radiusM -> StemMembership.INSIDE
            else -> StemMembership.OUTSIDE
        }
    }

    fun membership(distanceToStemM: Float, limitDistanceM: Float, borderlineBandM: Float = 0.2f): StemMembership =
        when {
            abs(distanceToStemM - limitDistanceM) <= borderlineBandM -> StemMembership.BORDERLINE
            distanceToStemM < limitDistanceM -> StemMembership.INSIDE
            else -> StemMembership.OUTSIDE
        }

    fun isDriftedBeyond(user: Vec2, center: Vec2, driftRadiusM: Float = 15f): Boolean {
        val dx = user.x - center.x
        val dz = user.z - center.z
        return sqrt(dx * dx + dz * dz) > driftRadiusM
    }
}
