// Calibration math — 1:1 port of iOS Sensors/LiDARCalibration.swift
// (spec §7.10 + REQ-CAL-003/004). Pure-math calibration engines:
//   WallCalibration     — PCA plane fit on merged depth points ->
//                         (depth_noise_mm, depth_bias_mm).
//   CylinderCalibration — linear regression on (DBH_measured, DBH_true)
//                         pairs -> (alpha, beta).
//
// Neither engine touches ARCore; screens collect the raw inputs and hand
// them to these functions. Tests can feed synthetic point clouds and
// calibration pairs without any sensor dependency. Swift's
// `Result<_, Failure>` maps onto `kotlin.Result` with the failures as
// exception subclasses (following the ExportBundleError pattern).

package com.hcjeong.forestix.sensors

import kotlin.math.abs
import kotlin.math.sqrt

// MARK: - Point type

/// Double-precision 3-vector (Swift `SIMD3<Double>`) for the plane fit.
data class Vec3d(val x: Double, val y: Double, val z: Double)

// MARK: - Results

data class WallCalibrationResult(
    val depthNoiseMm: Double,
    val depthBiasMm: Double,
    /// Plane normal in world frame (unit length).
    val planeNormal: Vec3d,
    /// Centroid of the input points.
    val planeCentroid: Vec3d,
    /// Number of points that contributed to the fit.
    val pointCount: Int,
)

data class CylinderCalibrationResult(
    /// DBH_true_cm = alpha + beta * DBH_measured_cm.
    val alpha: Double,
    val beta: Double,
    /// Coefficient of determination (R^2) for diagnostics.
    val rSquared: Double,
    /// Number of (measured, true) sample pairs.
    val sampleCount: Int,
)

// MARK: - Wall calibration (PCA plane fit)

object WallCalibration {

    sealed class Failure(message: String) : Exception(message) {
        data class TooFewPoints(val count: Int, val minimum: Int) :
            Failure("tooFewPoints(count: $count, minimum: $minimum)")
    }

    /// Fit a plane to world-space points using PCA (smallest-eigenvalue
    /// eigenvector of the covariance matrix = plane normal). Returns
    /// noise (RMS of signed residuals) and bias (mean signed residual).
    ///
    /// The minimum point count is 30 — matches §7.10 Step 2 (30 frames)
    /// and ensures covariance is numerically well-conditioned.
    fun fit(points: List<Vec3d>): Result<WallCalibrationResult> {
        if (points.size < 30) {
            return Result.failure(Failure.TooFewPoints(count = points.size, minimum = 30))
        }

        // Centroid.
        var cx = 0.0; var cy = 0.0; var cz = 0.0
        for (p in points) { cx += p.x; cy += p.y; cz += p.z }
        val n = points.size.toDouble()
        val centroid = Vec3d(cx / n, cy / n, cz / n)

        // Covariance (symmetric 3x3).
        var xx = 0.0; var yy = 0.0; var zz = 0.0
        var xy = 0.0; var xz = 0.0; var yz = 0.0
        for (p in points) {
            val dx = p.x - centroid.x
            val dy = p.y - centroid.y
            val dz = p.z - centroid.z
            xx += dx * dx; yy += dy * dy; zz += dz * dz
            xy += dx * dy; xz += dx * dz; yz += dy * dz
        }
        xx /= n; yy /= n; zz /= n
        xy /= n; xz /= n; yz /= n

        // Row-major symmetric 3x3.
        val cov = doubleArrayOf(
            xx, xy, xz,
            xy, yy, yz,
            xz, yz, zz,
        )

        val normal = smallestEigenvector(cov)

        // Signed residuals: dot(point - centroid, normal).
        var sum = 0.0
        var sumSq = 0.0
        for (p in points) {
            val d = (p.x - centroid.x) * normal.x +
                (p.y - centroid.y) * normal.y +
                (p.z - centroid.z) * normal.z
            sum += d
            sumSq += d * d
        }
        val mean = sum / n
        val rms = sqrt(sumSq / n)

        return Result.success(
            WallCalibrationResult(
                depthNoiseMm = rms * 1000,
                depthBiasMm = mean * 1000,
                planeNormal = normal,
                planeCentroid = centroid,
                pointCount = points.size,
            )
        )
    }

    /// Smallest-eigenvalue eigenvector of a 3x3 symmetric PSD matrix via
    /// inverse power iteration around shift 0. Falls back to the Y axis
    /// if the matrix is singular to zero. `m` is row-major (symmetric, so
    /// row/column order is interchangeable).
    private fun smallestEigenvector(m: DoubleArray): Vec3d {
        // Shift toward the smallest eigenvalue: add eps*I to keep the
        // inverse well-defined, then inverse-power-iterate.
        val eps = 1e-12
        val shifted = doubleArrayOf(
            m[0] + eps, m[1], m[2],
            m[3], m[4] + eps, m[5],
            m[6], m[7], m[8] + eps,
        )
        val inv = invert3x3(shifted) ?: return Vec3d(0.0, 1.0, 0.0)

        // Start from a vector unlikely to be orthogonal to the target.
        var vx = 1.0 / sqrt(3.0); var vy = vx; var vz = vx
        for (i in 0 until 64) {
            var nx = inv[0] * vx + inv[1] * vy + inv[2] * vz
            var ny = inv[3] * vx + inv[4] * vy + inv[5] * vz
            var nz = inv[6] * vx + inv[7] * vy + inv[8] * vz
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-18) {
                return Vec3d(0.0, 1.0, 0.0)   // degenerate: fall back to vertical
            }
            nx /= len; ny /= len; nz /= len
            // Sign convention: keep dot(next, prev) >= 0 for stable output.
            if (nx * vx + ny * vy + nz * vz < 0) { nx = -nx; ny = -ny; nz = -nz }
            val dx = nx - vx; val dy = ny - vy; val dz = nz - vz
            vx = nx; vy = ny; vz = nz
            if (dx * dx + dy * dy + dz * dz < 1e-24) break
        }
        return Vec3d(vx, vy, vz)
    }

    /// Inverse of a row-major 3x3 via the adjugate. Null when singular.
    private fun invert3x3(m: DoubleArray): DoubleArray? {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[3]; val e = m[4]; val f = m[5]
        val g = m[6]; val h = m[7]; val i = m[8]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (abs(det) < 1e-300 || !det.isFinite()) return null
        val invDet = 1.0 / det
        return doubleArrayOf(
            (e * i - f * h) * invDet, (c * h - b * i) * invDet, (b * f - c * e) * invDet,
            (f * g - d * i) * invDet, (a * i - c * g) * invDet, (c * d - a * f) * invDet,
            (d * h - e * g) * invDet, (b * g - a * h) * invDet, (a * e - b * d) * invDet,
        )
    }
}

// MARK: - Cylinder calibration (linear regression)

object CylinderCalibration {

    sealed class Failure(message: String) : Exception(message) {
        data class TooFewSamples(val count: Int, val minimum: Int) :
            Failure("tooFewSamples(count: $count, minimum: $minimum)")

        object DegenerateX : Failure("degenerateX") {
            @Suppress("unused")
            private fun readResolve(): Any = DegenerateX
        }
    }

    data class Sample(
        val dbhMeasuredCm: Double,
        val dbhTrueCm: Double,
    )

    /// OLS fit of DBH_true = alpha + beta * DBH_measured. Requires at
    /// least 2 distinct measured values — spec §7.10 recommends three
    /// target diameters (10/20/30 cm) as the minimum meaningful set.
    fun fit(samples: List<Sample>): Result<CylinderCalibrationResult> {
        if (samples.size < 2) {
            return Result.failure(Failure.TooFewSamples(count = samples.size, minimum = 2))
        }
        val n = samples.size.toDouble()
        var sumX = 0.0; var sumY = 0.0; var sumXX = 0.0; var sumXY = 0.0; var sumYY = 0.0
        for (s in samples) {
            val x = s.dbhMeasuredCm
            val y = s.dbhTrueCm
            sumX += x; sumY += y
            sumXX += x * x; sumXY += x * y; sumYY += y * y
        }
        val meanX = sumX / n
        val meanY = sumY / n
        val ssxx = sumXX - n * meanX * meanX
        val ssxy = sumXY - n * meanX * meanY
        val ssyy = sumYY - n * meanY * meanY

        if (ssxx <= 1e-12) {
            return Result.failure(Failure.DegenerateX)
        }
        val beta = ssxy / ssxx
        val alpha = meanY - beta * meanX
        val rSq = if (ssyy > 1e-18) {
            ((beta * ssxy) / ssyy).coerceIn(0.0, 1.0)
        } else 1.0
        return Result.success(
            CylinderCalibrationResult(
                alpha = alpha, beta = beta,
                rSquared = rSq,
                sampleCount = samples.size,
            )
        )
    }
}
