// Circle-fit suite — 1:1 port of iOS Sensors/CircleFit/{KasaFit,TaubinFit,
// RANSACCircle}.swift (spec §7.1 Step 7). Kåsa closed-form + 3-point exact
// hypothesis, Taubin algebraic refit, and a 500-iteration stratified RANSAC
// with a deterministic SplitMix64 RNG — identical to the iOS DBH pipeline.

package com.hcjeong.forestix.sensors

import kotlin.math.abs
import kotlin.math.sqrt

/// 2-D point in the world XZ plane (Double precision, like iOS SIMD2<Double>).
data class V2(val x: Double, val y: Double)

data class Circle2D(val cx: Double, val cy: Double, val radius: Double)

data class CircleFitResult(val circle: Circle2D, val inliers: List<V2>, val inlierIndices: List<Int>)

object KasaFit {

    fun fit(points: List<V2>): Circle2D? {
        if (points.size < 3) return null
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        val n = points.size.toDouble()
        for (p in points) {
            val z = p.x * p.x + p.y * p.y
            sxx += p.x * p.x; syy += p.y * p.y; sxy += p.x * p.y
            sx += p.x; sy += p.y
            sxz += p.x * z; syz += p.y * z; sz += z
        }
        val a = arrayOf(
            doubleArrayOf(sxx, sxy, sx),
            doubleArrayOf(sxy, syy, sy),
            doubleArrayOf(sx, sy, n),
        )
        val b = doubleArrayOf(-sxz, -syz, -sz)
        val x = solve3x3(a, b) ?: return null
        val cx = -x[0] / 2; val cy = -x[1] / 2
        val rSq = cx * cx + cy * cy - x[2]
        if (rSq <= 0) return null
        return Circle2D(cx, cy, sqrt(rSq))
    }

    fun fit3(p0: V2, p1: V2, p2: V2): Circle2D? {
        val ax = p0.x; val ay = p0.y
        val bx = p1.x; val by = p1.y
        val cx0 = p2.x; val cy0 = p2.y
        val d = 2 * (ax * (by - cy0) + bx * (cy0 - ay) + cx0 * (ay - by))
        if (abs(d) < 1e-12) return null
        val aSq = ax * ax + ay * ay
        val bSq = bx * bx + by * by
        val cSq = cx0 * cx0 + cy0 * cy0
        val ux = (aSq * (by - cy0) + bSq * (cy0 - ay) + cSq * (ay - by)) / d
        val uy = (aSq * (cx0 - bx) + bSq * (ax - cx0) + cSq * (bx - ax)) / d
        val rx = ax - ux; val ry = ay - uy
        return Circle2D(ux, uy, sqrt(rx * rx + ry * ry))
    }

    private fun solve3x3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val det = a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) -
            a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) +
            a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        if (abs(det) < 1e-18) return null
        val inv = 1.0 / det
        fun subMat(col: Int): Array<DoubleArray> {
            val m = Array(3) { a[it].copyOf() }
            for (row in 0 until 3) m[row][col] = b[row]
            return m
        }
        fun det3(m: Array<DoubleArray>) =
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
                m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) +
                m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        return doubleArrayOf(det3(subMat(0)) * inv, det3(subMat(1)) * inv, det3(subMat(2)) * inv)
    }
}

object TaubinFit {

    fun fit(points: List<V2>): Circle2D? {
        if (points.size < 3) return null
        val n = points.size.toDouble()
        var xMean = 0.0; var yMean = 0.0
        for (p in points) { xMean += p.x; yMean += p.y }
        xMean /= n; yMean /= n

        var mxx = 0.0; var myy = 0.0; var mxy = 0.0
        var mxz = 0.0; var myz = 0.0; var mzz = 0.0
        for (p in points) {
            val dx = p.x - xMean; val dy = p.y - yMean
            val z = dx * dx + dy * dy
            mxx += dx * dx; myy += dy * dy; mxy += dx * dy
            mxz += dx * z; myz += dy * z; mzz += z * z
        }
        mxx /= n; myy /= n; mxy /= n; mxz /= n; myz /= n; mzz /= n

        val mz = mxx + myy
        val covXY = mxx * myy - mxy * mxy
        val a3 = 4 * mz
        val a2 = -3 * mz * mz - mzz
        val a1 = mzz * mz + 4 * covXY * mz - mxz * mxz - myz * myz - mz * mz * mz
        val a0 = mxz * mxz * myy + myz * myz * mxx - mzz * covXY - 2 * mxz * myz * mxy + mz * mz * covXY

        var x = 0.0
        var y = a0
        for (iter in 0 until 200) {
            val dyd = a1 + x * (2 * a2 + x * 3 * a3)
            val xNew = x - y / dyd
            if (abs(xNew - x) < 1e-14) { x = xNew; break }
            val yNew = a0 + xNew * (a1 + xNew * (a2 + xNew * a3))
            if (abs(yNew) >= abs(y)) break
            x = xNew; y = yNew
        }

        val det = x * x - x * mz + covXY
        if (abs(det) < 1e-18) return null
        val cxLoc = (mxz * (myy - x) - myz * mxy) / (2 * det)
        val cyLoc = (myz * (mxx - x) - mxz * mxy) / (2 * det)
        val radius = sqrt(cxLoc * cxLoc + cyLoc * cyLoc + mz + 2 * x)
        return Circle2D(cxLoc + xMean, cyLoc + yMean, radius)
    }
}

object RANSACCircle {

    fun fit(
        points: List<V2>,
        inlierTol: Double,
        iterations: Int = 500,
        minInliers: Int = 20,
        seed: Long = 0xDBC172F15BEEF000uL.toLong(),
    ): CircleFitResult? {
        if (points.size < 3) return null
        val rng = SplitMix64(seed)
        var bestIndices: List<Int> = emptyList()
        var bestCircle: Circle2D? = null

        val n = points.size
        val third = maxOf(1, n / 3)
        repeat(iterations) {
            val i = (rng.next() % third.toULong()).toInt()
            val j = third + (rng.next() % maxOf(1, third).toULong()).toInt()
            val k = 2 * third + (rng.next() % maxOf(1, n - 2 * third).toULong()).toInt()
            val hypo = KasaFit.fit3(points[i], points[j], points[k]) ?: return@repeat
            val idx = ArrayList<Int>(points.size / 2)
            for (m in points.indices) {
                val dx = points[m].x - hypo.cx
                val dy = points[m].y - hypo.cy
                val r = sqrt(dx * dx + dy * dy)
                if (abs(r - hypo.radius) <= inlierTol) idx.add(m)
            }
            if (idx.size > bestIndices.size) { bestIndices = idx; bestCircle = hypo }
        }

        val seedCircle = bestCircle ?: return null
        if (bestIndices.size < minInliers) return null
        val bestPoints = bestIndices.map { points[it] }
        val refined = TaubinFit.fit(bestPoints) ?: seedCircle
        return CircleFitResult(refined, bestPoints, bestIndices)
    }
}

/// Deterministic SplitMix64 — identical sequence to the iOS RNG.
class SplitMix64(seed: Long) {
    private var state: ULong = seed.toULong()
    fun next(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }
}
