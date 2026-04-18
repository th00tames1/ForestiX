// Spec §7.1 Step 7 — 500-iteration RANSAC wrapper around Kåsa 3-point
// hypothesis + Taubin refit on the best inlier set.

import Foundation

public struct CircleFitResult {
    public let circle: Circle2D
    public let inliers: [SIMD2<Double>]
    public let inlierIndices: [Int]
}

public enum RANSACCircle {

    public static func fit(
        points: [SIMD2<Double>],
        inlierTol: Double,
        iterations: Int = 500,
        minInliers: Int = 20,
        seed: UInt64 = 0xDBC1_72F1_5BEE_F000
    ) -> CircleFitResult? {
        guard points.count >= 3 else { return nil }
        var rng = SplitMix64(seed: seed)

        var bestIndices: [Int] = []
        var bestCircle: Circle2D?

        // Stratified 3-point sampling: pick one from each third of the
        // ordered point set. On short arcs this prevents three adjacent
        // points collapsing the 3-point circle to a random radius.
        let n = points.count
        let third = max(1, n / 3)
        for _ in 0..<iterations {
            let i = Int(rng.next() % UInt64(third))
            let j = third + Int(rng.next() % UInt64(max(1, third)))
            let k = 2 * third + Int(rng.next() % UInt64(max(1, n - 2 * third)))
            guard let hypo = KasaFit.fit(points[i], points[j], points[k])
            else { continue }

            var idx: [Int] = []
            idx.reserveCapacity(points.count / 2)
            for n in 0..<points.count {
                let dx = points[n].x - hypo.cx
                let dy = points[n].y - hypo.cy
                let r = sqrt(dx * dx + dy * dy)
                if abs(r - hypo.radius) <= inlierTol { idx.append(n) }
            }
            if idx.count > bestIndices.count {
                bestIndices = idx
                bestCircle  = hypo
            }
        }

        guard let seedCircle = bestCircle, bestIndices.count >= minInliers
        else { return nil }

        let bestPoints = bestIndices.map { points[$0] }
        let refined = TaubinFit.fit(points: bestPoints) ?? seedCircle
        return CircleFitResult(
            circle: refined,
            inliers: bestPoints,
            inlierIndices: bestIndices
        )
    }
}

// MARK: - Deterministic RNG

private struct SplitMix64 {
    private var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}
