// Spec §7.1 Step 6 — statistical outlier removal on the back-projected
// stem-strip point set.
//
// Algorithm (PCL-style):
//   1. For every point, find its k nearest neighbors (brute force — the
//      input is a few hundred 2-D points per frame burst, so O(n²) is
//      comfortably under 1 ms on an A14).
//   2. Let `mi` be the mean distance from point i to those k neighbors.
//   3. Let μ, σ be the mean and stddev of `mi` across all points.
//   4. Drop any point with `mi > μ + σ_mult · σ`.
//
// Default parameters (k = 8, σ_mult = 2.0) match §7.1 Step 6 verbatim.

import Foundation

public enum OutlierRemoval {

    /// Statistical outlier removal for 2-D points (world XZ plane).
    /// Returns the retained subset preserving input order.
    public static func statistical(
        points: [SIMD2<Double>],
        k: Int = 8,
        sigmaMult: Double = 2.0
    ) -> [SIMD2<Double>] {
        let keepMask = statisticalMask(points: points, k: k, sigmaMult: sigmaMult)
        var out: [SIMD2<Double>] = []
        out.reserveCapacity(points.count)
        for (i, p) in points.enumerated() where keepMask[i] {
            out.append(p)
        }
        return out
    }

    /// Same as `statistical(points:)` but returns a per-index bitmask so
    /// callers can keep side-car arrays (e.g. which frame a point came
    /// from) aligned with the retained set.
    public static func statisticalMask(
        points: [SIMD2<Double>],
        k: Int = 8,
        sigmaMult: Double = 2.0
    ) -> [Bool] {
        let n = points.count
        if n <= k + 1 { return Array(repeating: true, count: n) }

        // Step 1–2: mean distance to k nearest neighbors per point.
        var meanDist = [Double](repeating: 0, count: n)
        var neighbourBuf = [Double](repeating: .infinity, count: k)
        for i in 0..<n {
            for slot in 0..<k { neighbourBuf[slot] = .infinity }
            let pi = points[i]
            for j in 0..<n where j != i {
                let d = distance(pi, points[j])
                // Insertion into a k-sized max-heap replacement: replace
                // the current worst-of-top-k if d is smaller.
                var worstIdx = 0
                var worstVal = neighbourBuf[0]
                for s in 1..<k where neighbourBuf[s] > worstVal {
                    worstVal = neighbourBuf[s]
                    worstIdx = s
                }
                if d < worstVal { neighbourBuf[worstIdx] = d }
            }
            var sum = 0.0
            for s in 0..<k { sum += neighbourBuf[s] }
            meanDist[i] = sum / Double(k)
        }

        // Step 3: global μ, σ.
        var mu = 0.0
        for v in meanDist { mu += v }
        mu /= Double(n)
        var variance = 0.0
        for v in meanDist {
            let d = v - mu
            variance += d * d
        }
        variance /= Double(n)
        let sigma = sqrt(variance)
        let cutoff = mu + sigmaMult * sigma

        // Step 4: accept where mi ≤ cutoff. sigma == 0 keeps everything.
        var mask = [Bool](repeating: true, count: n)
        if sigma > 0 {
            for i in 0..<n { mask[i] = meanDist[i] <= cutoff }
        }
        return mask
    }

    @inlinable
    internal static func distance(_ a: SIMD2<Double>, _ b: SIMD2<Double>) -> Double {
        let dx = a.x - b.x
        let dy = a.y - b.y
        return (dx * dx + dy * dy).squareRoot()
    }
}
