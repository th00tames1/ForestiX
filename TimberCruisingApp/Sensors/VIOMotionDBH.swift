// AR-motion DBH — circle-fit to ARKit sparse VIO feature points, for the
// NON-LiDAR path (and the within-device LiDAR-vs-AR comparison). The
// cruiser sweeps the phone slightly across the trunk; visual-inertial
// odometry accumulates metric world-space feature points on the bark.
// We filter them to a breast-height trunk band and run the SAME RANSAC +
// Taubin circle fit the LiDAR depth path uses — so the only thing that
// differs from the LiDAR method is the point SOURCE (VIO features vs depth
// map), making it the cleanest "same algorithm, different sensor" arm.
//
// VIO points are far sparser and noisier than LiDAR depth, so σ_R is
// larger and the confidence tier is correspondingly more cautious — which
// is the honest, intended behaviour.

import Foundation
import simd
import Common
import Models

public enum VIOMotionDBH {

    /// Provisional per-point noise (m) seeding σ_R. VIO features are noisier
    /// than LiDAR depth; PLACEHOLDER to be calibrated by the field study.
    public static let pointNoiseMeters: Double = 0.02

    /// `featurePoints` are world-space metric VIO points accumulated over the
    /// sweep. `anchorWorld` is an approximate trunk-surface point at the aim
    /// height (e.g. the centre raycast hit) used to centre the trunk-band ROI.
    public static func estimate(
        featurePoints: [SIMD3<Float>],
        anchorWorld: SIMD3<Float>,
        trackingStayedNormal: Bool
    ) -> DBHResult? {
        guard featurePoints.count >= 12 else { return nil }

        // 1. Filter to a vertical trunk band around the aim height + a
        //    generous horizontal radius (tightened by the fit itself).
        let ax = Double(anchorWorld.x)
        let ay = Double(anchorWorld.y)
        let az = Double(anchorWorld.z)
        let vertBand = 0.25       // ±25 cm around the aim height
        let horizR   = 0.7        // keep points within 70 cm horizontally
        var xz: [SIMD2<Double>] = []
        xz.reserveCapacity(featurePoints.count)
        for p in featurePoints {
            if abs(Double(p.y) - ay) > vertBand { continue }
            let dx = Double(p.x) - ax
            let dz = Double(p.z) - az
            if (dx * dx + dz * dz).squareRoot() > horizR { continue }
            xz.append(SIMD2(Double(p.x), Double(p.z)))
        }
        guard xz.count >= 10 else { return nil }

        // 2. Statistical outlier removal (same as the depth strip).
        let cleaned = OutlierRemoval.statistical(points: xz, k: 8, sigmaMult: 2.0)
        guard cleaned.count >= 6 else { return nil }

        // 3. RANSAC + Taubin circle fit (reused verbatim).
        guard let result = RANSACCircle.fit(
            points: cleaned,
            inlierTol: 0.02,
            iterations: 400,
            minInliers: 6
        ) else { return nil }

        let c = result.circle
        let radiusM = c.radius
        let diameterCm = Float(radiusM * 2 * 100)
        guard diameterCm > 1, diameterCm < 400 else { return nil }

        // 4. Metrics — radial RMSE + arc coverage from the inliers.
        let inliers = result.inliers
        let n = inliers.count
        var sumSq = 0.0
        var angles: [Double] = []
        angles.reserveCapacity(n)
        for p in inliers {
            let dx = p.x - c.cx
            let dy = p.y - c.cy
            let r = (dx * dx + dy * dy).squareRoot()
            sumSq += (r - radiusM) * (r - radiusM)
            angles.append(atan2(dy, dx))
        }
        let rmseM = (sumSq / Double(n)).squareRoot()
        let arcDeg = arcSpanDeg(angles)

        // 5. σ_R — point noise / √n inflated by poor arc coverage.
        let arcRad = max(arcDeg * .pi / 180.0, 0.05)
        let sigmaRm = pointNoiseMeters / Double(n).squareRoot() / max(sin(arcRad / 2), 0.05)
        let ratio = radiusM > 0 ? sigmaRm / radiusM : 1.0

        // 6. Confidence tier via the shared §7.9 framework.
        let tier = combineChecks([
            check(arcDeg >= 25, sev: .reject, reason: "arc too narrow"),
            check(ratio <= 0.12, sev: .reject, reason: "σ_R too high"),
            check(n >= 8,         sev: .warn, reason: "few VIO points"),
            check(arcDeg >= 45,   sev: .warn, reason: "narrow arc"),
            check(ratio <= 0.05,  sev: .warn, reason: "σ_R high"),
            check(trackingStayedNormal, sev: .warn, reason: "tracking interrupted"),
        ])

        return DBHResult(
            diameterCm: diameterCm,
            centerXZ: SIMD2<Float>(Float(c.cx), Float(c.cy)),
            arcCoverageDeg: Float(arcDeg),
            rmseMm: Float(rmseM * 1000),
            sigmaRmm: Float(sigmaRm * 1000),
            nInliers: n,
            confidence: tier,
            method: .arVioCircleFit,
            rawPointsPath: nil,
            rejectionReason: tier == .red ? "AR-motion: sparse / narrow VIO fit" : nil)
    }

    /// Angular span (deg) covered by the inliers — total circle minus the
    /// largest empty gap between consecutive angles.
    private static func arcSpanDeg(_ angles: [Double]) -> Double {
        guard angles.count > 1 else { return 0 }
        let sorted = angles.sorted()
        var maxGap = 0.0
        for i in 1..<sorted.count {
            maxGap = max(maxGap, sorted[i] - sorted[i - 1])
        }
        let wrap = (sorted.first! + 2 * .pi) - sorted.last!
        maxGap = max(maxGap, wrap)
        return (2 * .pi - maxGap) * 180.0 / .pi
    }
}
