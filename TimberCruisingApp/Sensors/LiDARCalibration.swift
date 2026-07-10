// Spec §7.10 + REQ-CAL-003/004. Pure-math calibration engines:
//   WallCalibration    — PCA plane fit on merged depth points →
//                        (depth_noise_mm, depth_bias_mm).
//   CylinderCalibration — linear regression on (DBH_measured, DBH_true)
//                        pairs → (alpha, beta).
//
// Neither engine touches ARKit; screens collect the raw inputs and hand
// them to these functions. Tests can feed synthetic point clouds and
// calibration pairs without any sensor dependency.

import Foundation
import simd

// MARK: - Results

public struct WallCalibrationResult: Equatable, Sendable {
    public let depthNoiseMm: Double
    public let depthBiasMm: Double
    /// Plane normal in world frame (unit length).
    public let planeNormal: SIMD3<Double>
    /// Centroid of the input points.
    public let planeCentroid: SIMD3<Double>
    /// Number of points that contributed to the fit.
    public let pointCount: Int
}

public struct CylinderCalibrationResult: Equatable, Sendable {
    /// DBH_true_cm = alpha + beta · DBH_measured_cm.
    public let alpha: Double
    public let beta: Double
    /// Coefficient of determination (R²) for diagnostics.
    public let rSquared: Double
    /// Number of (measured, true) sample pairs.
    public let sampleCount: Int
}

// MARK: - Wall calibration (PCA plane fit)

public enum WallCalibration {

    public enum Failure: Error, Equatable {
        case tooFewPoints(count: Int, minimum: Int)
    }

    /// Fit a plane to world-space points using PCA (smallest-eigenvalue
    /// eigenvector of the covariance matrix = plane normal). Returns
    /// noise (RMS of signed residuals) and bias (mean signed residual).
    ///
    /// The minimum point count is 30 — matches §7.10 Step 2 (30 frames)
    /// and ensures covariance is numerically well-conditioned.
    public static func fit(points: [SIMD3<Double>]) -> Result<WallCalibrationResult, Failure> {
        guard points.count >= 30 else {
            return .failure(.tooFewPoints(count: points.count, minimum: 30))
        }

        // Centroid.
        var cx = 0.0, cy = 0.0, cz = 0.0
        for p in points { cx += p.x; cy += p.y; cz += p.z }
        let n = Double(points.count)
        let centroid = SIMD3<Double>(cx / n, cy / n, cz / n)

        // Covariance (symmetric 3×3).
        var xx = 0.0, yy = 0.0, zz = 0.0
        var xy = 0.0, xz = 0.0, yz = 0.0
        for p in points {
            let dx = p.x - centroid.x
            let dy = p.y - centroid.y
            let dz = p.z - centroid.z
            xx += dx * dx; yy += dy * dy; zz += dz * dz
            xy += dx * dy; xz += dx * dz; yz += dy * dz
        }
        xx /= n; yy /= n; zz /= n
        xy /= n; xz /= n; yz /= n

        let cov = matrix_double3x3(
            SIMD3(xx, xy, xz),
            SIMD3(xy, yy, yz),
            SIMD3(xz, yz, zz))

        let normal = smallestEigenvector(of: cov)

        // Signed residuals: dot(point - centroid, normal).
        var sum = 0.0
        var sumSq = 0.0
        for p in points {
            let d = (p.x - centroid.x) * normal.x
                  + (p.y - centroid.y) * normal.y
                  + (p.z - centroid.z) * normal.z
            sum += d
            sumSq += d * d
        }
        let mean = sum / n
        let rms  = (sumSq / n).squareRoot()

        return .success(WallCalibrationResult(
            depthNoiseMm: rms * 1000,
            depthBiasMm:  mean * 1000,
            planeNormal:  normal,
            planeCentroid: centroid,
            pointCount: points.count))
    }

    /// Smallest-eigenvalue eigenvector of a 3×3 symmetric PSD matrix via
    /// inverse power iteration around shift 0. Falls back to the Y axis
    /// if the matrix is singular to zero.
    private static func smallestEigenvector(
        of m: matrix_double3x3
    ) -> SIMD3<Double> {
        // Shift toward the smallest eigenvalue: add ε·I to keep the
        // inverse well-defined, then inverse-power-iterate.
        let eps = 1e-12
        let shifted = matrix_double3x3(
            SIMD3(m[0, 0] + eps, m[0, 1],      m[0, 2]),
            SIMD3(m[1, 0],      m[1, 1] + eps, m[1, 2]),
            SIMD3(m[2, 0],      m[2, 1],      m[2, 2] + eps))
        let inv = shifted.inverse

        // Start from a vector unlikely to be orthogonal to the target.
        var v = SIMD3<Double>(1, 1, 1) / 3.0.squareRoot()
        for _ in 0..<64 {
            var next = inv * v
            let len = (next.x * next.x + next.y * next.y + next.z * next.z).squareRoot()
            if len < 1e-18 {
                return SIMD3(0, 1, 0)   // degenerate: fall back to vertical
            }
            next /= len
            // Sign convention: keep dot(next, prev) ≥ 0 for stable output.
            if next.x * v.x + next.y * v.y + next.z * v.z < 0 { next = -next }
            let diff = next - v
            v = next
            if (diff.x * diff.x + diff.y * diff.y + diff.z * diff.z) < 1e-24 {
                break
            }
        }
        return v
    }
}

// MARK: - Cylinder calibration (linear regression)

public enum CylinderCalibration {

    public enum Failure: Error, Equatable {
        case tooFewSamples(count: Int, minimum: Int)
        case degenerateX
    }

    public struct Sample: Equatable, Sendable {
        public let dbhMeasuredCm: Double
        public let dbhTrueCm: Double
        public init(dbhMeasuredCm: Double, dbhTrueCm: Double) {
            self.dbhMeasuredCm = dbhMeasuredCm
            self.dbhTrueCm = dbhTrueCm
        }
    }

    /// OLS fit of DBH_true = α + β · DBH_measured. Requires at least 2
    /// distinct measured values — spec §7.10 recommends three target
    /// diameters (10/20/30 cm) as the minimum meaningful set.
    public static func fit(samples: [Sample])
        -> Result<CylinderCalibrationResult, Failure>
    {
        guard samples.count >= 2 else {
            return .failure(.tooFewSamples(count: samples.count, minimum: 2))
        }
        let n = Double(samples.count)
        var sumX = 0.0, sumY = 0.0, sumXX = 0.0, sumXY = 0.0, sumYY = 0.0
        for s in samples {
            let x = s.dbhMeasuredCm
            let y = s.dbhTrueCm
            sumX  += x; sumY  += y
            sumXX += x * x; sumXY += x * y; sumYY += y * y
        }
        let meanX = sumX / n
        let meanY = sumY / n
        let ssxx  = sumXX - n * meanX * meanX
        let ssxy  = sumXY - n * meanX * meanY
        let ssyy  = sumYY - n * meanY * meanY

        guard ssxx > 1e-12 else {
            return .failure(.degenerateX)
        }
        let beta  = ssxy / ssxx
        let alpha = meanY - beta * meanX
        let rSq   = ssyy > 1e-18
            ? max(0, min(1, (beta * ssxy) / ssyy))
            : 1.0
        return .success(CylinderCalibrationResult(
            alpha: alpha, beta: beta,
            rSquared: rSq,
            sampleCount: samples.count))
    }
}
