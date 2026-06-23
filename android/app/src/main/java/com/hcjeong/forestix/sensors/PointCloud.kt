// Point-cloud helpers — 1:1 port of iOS Sensors/PointCloud/{BackProjection,
// OutlierRemoval}.swift (spec §7.1 Steps 4 + 6). Pinhole back-projection of
// depth pixels into world XZ + PCL-style statistical outlier removal.

package com.hcjeong.forestix.sensors

import kotlin.math.sqrt

object BackProjection {

    /// Unproject a depth pixel to world-space (x, z). Intrinsics are the
    /// pinhole fx/fy/cx/cy; `pose` is the column-major 4×4 T_world_camera
    /// (ARCore Pose.toMatrix layout, identical element order to the iOS
    /// simd column-major matrix used by the original).
    fun worldXZ(
        x: Double, y: Double, depth: Double,
        fx: Double, fy: Double, cx: Double, cy: Double,
        pose: FloatArray,
    ): V2 {
        val xc = (x - cx) * depth / fx
        val yc = (y - cy) * depth / fy
        val zc = depth
        // Column-major: element(col,row) = pose[col*4 + row].
        // worldX = row0 · (xc,yc,zc,1); worldZ = row2 · (xc,yc,zc,1).
        val worldX = pose[0] * xc + pose[4] * yc + pose[8] * zc + pose[12]
        val worldZ = pose[2] * xc + pose[6] * yc + pose[10] * zc + pose[14]
        return V2(worldX, worldZ)
    }
}

object OutlierRemoval {

    /// Statistical outlier removal for 2-D points (k = 8, σ_mult = 2.0).
    /// Returns the retained subset preserving input order.
    fun statistical(points: List<V2>, k: Int = 8, sigmaMult: Double = 2.0): List<V2> {
        val mask = statisticalMask(points, k, sigmaMult)
        val out = ArrayList<V2>(points.size)
        for (i in points.indices) if (mask[i]) out.add(points[i])
        return out
    }

    fun statisticalMask(points: List<V2>, k: Int = 8, sigmaMult: Double = 2.0): BooleanArray {
        val n = points.size
        if (n <= k + 1) return BooleanArray(n) { true }

        val meanDist = DoubleArray(n)
        val neighbourBuf = DoubleArray(k)
        for (i in 0 until n) {
            for (slot in 0 until k) neighbourBuf[slot] = Double.POSITIVE_INFINITY
            val pi = points[i]
            for (j in 0 until n) {
                if (j == i) continue
                val d = dist(pi, points[j])
                var worstIdx = 0
                var worstVal = neighbourBuf[0]
                for (s in 1 until k) if (neighbourBuf[s] > worstVal) { worstVal = neighbourBuf[s]; worstIdx = s }
                if (d < worstVal) neighbourBuf[worstIdx] = d
            }
            var sum = 0.0
            for (s in 0 until k) sum += neighbourBuf[s]
            meanDist[i] = sum / k
        }

        var mu = 0.0
        for (v in meanDist) mu += v
        mu /= n
        var variance = 0.0
        for (v in meanDist) { val d = v - mu; variance += d * d }
        variance /= n
        val sigma = sqrt(variance)
        val cutoff = mu + sigmaMult * sigma

        val mask = BooleanArray(n) { true }
        if (sigma > 0) for (i in 0 until n) mask[i] = meanDist[i] <= cutoff
        return mask
    }

    private fun dist(a: V2, b: V2): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
