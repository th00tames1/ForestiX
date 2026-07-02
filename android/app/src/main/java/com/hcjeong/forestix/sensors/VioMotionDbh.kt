// AR-motion DBH — circle-fit to ARCore sparse VIO feature points, for the
// NON-depth path (and the within-device depth-vs-AR comparison). 1:1 port
// of iOS Sensors/VIOMotionDBH.swift. The cruiser sweeps the phone slightly
// across the trunk; visual-inertial odometry accumulates metric world-space
// feature points on the bark. We filter them to a breast-height trunk band
// and run the SAME RANSAC + Taubin circle fit the depth path uses — so the
// only thing that differs from the depth method is the point SOURCE (VIO
// features vs depth map).
//
// VIO points are far sparser and noisier than depth, so σ_R is larger and
// the confidence tier is correspondingly more cautious — the honest,
// intended behaviour.

package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.ar.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

object VioMotionDbh {

    /// Provisional per-point noise (m) seeding σ_R. VIO features are noisier
    /// than depth; PLACEHOLDER to be calibrated by the field study.
    const val pointNoiseMeters: Double = 0.02

    /// `featurePoints` are world-space metric VIO points accumulated over the
    /// sweep. `anchorWorld` is an approximate trunk-surface point at the aim
    /// height (the centre raycast hit) used to centre the trunk-band ROI.
    fun estimate(
        featurePoints: List<Vec3>,
        anchorWorld: Vec3,
        trackingStayedNormal: Boolean,
    ): DBHResult? {
        if (featurePoints.size < 12) return null

        // 1. Filter to a vertical trunk band around the aim height + a
        //    generous horizontal radius (tightened by the fit itself).
        val ax = anchorWorld.x.toDouble()
        val ay = anchorWorld.y.toDouble()
        val az = anchorWorld.z.toDouble()
        val vertBand = 0.25       // ±25 cm around the aim height
        val horizR = 0.7          // keep points within 70 cm horizontally
        val xz = ArrayList<V2>(featurePoints.size)
        for (p in featurePoints) {
            if (abs(p.y.toDouble() - ay) > vertBand) continue
            val dx = p.x.toDouble() - ax
            val dz = p.z.toDouble() - az
            if (sqrt(dx * dx + dz * dz) > horizR) continue
            xz.add(V2(p.x.toDouble(), p.z.toDouble()))
        }
        if (xz.size < 10) return null

        // 2. Statistical outlier removal (same as the depth strip).
        val cleaned = OutlierRemoval.statistical(xz, k = 8, sigmaMult = 2.0)
        if (cleaned.size < 6) return null

        // 3. RANSAC + Taubin circle fit (reused verbatim).
        val result = RANSACCircle.fit(cleaned, inlierTol = 0.02, iterations = 400, minInliers = 6)
            ?: return null

        val c = result.circle
        val radiusM = c.radius
        val diameterCm = (radiusM * 2 * 100).toFloat()
        if (diameterCm <= 1f || diameterCm >= 400f) return null

        // 4. Metrics — radial RMSE + arc coverage from the inliers.
        val inliers = result.inliers
        val n = inliers.size
        var sumSq = 0.0
        val angles = ArrayList<Double>(n)
        for (p in inliers) {
            val dx = p.x - c.cx
            val dy = p.y - c.cy
            val r = sqrt(dx * dx + dy * dy)
            sumSq += (r - radiusM) * (r - radiusM)
            angles.add(atan2(dy, dx))
        }
        val rmseM = sqrt(sumSq / n)
        val arcDeg = arcSpanDeg(angles)

        // 5. σ_R — point noise / √n inflated by poor arc coverage.
        val arcRad = maxOf(arcDeg * PI / 180.0, 0.05)
        val sigmaRm = pointNoiseMeters / sqrt(n.toDouble()) / maxOf(sin(arcRad / 2), 0.05)
        val ratio = if (radiusM > 0) sigmaRm / radiusM else 1.0

        // 6. Confidence tier via the shared §7.9 framework.
        val tier = combineChecks(
            listOf(
                check(arcDeg >= 25, Severity.REJECT, "arc too narrow"),
                check(ratio <= 0.12, Severity.REJECT, "σ_R too high"),
                check(n >= 8, Severity.WARN, "few VIO points"),
                check(arcDeg >= 45, Severity.WARN, "narrow arc"),
                check(ratio <= 0.05, Severity.WARN, "σ_R high"),
                check(trackingStayedNormal, Severity.WARN, "tracking interrupted"),
            ),
        )

        return DBHResult(
            diameterCm = diameterCm,
            centerX = c.cx.toFloat(),
            centerZ = c.cy.toFloat(),
            arcCoverageDeg = arcDeg.toFloat(),
            rmseMm = (rmseM * 1000).toFloat(),
            sigmaRmm = (sigmaRm * 1000).toFloat(),
            nInliers = n,
            confidence = tier,
            method = DBHMethod.AR_VIO_CIRCLE_FIT,
            rejectionReason = if (tier == ConfidenceTier.RED) "AR-motion: sparse / narrow VIO fit" else null,
        )
    }

    /// Angular span (deg) covered by the inliers — total circle minus the
    /// largest empty gap between consecutive angles.
    private fun arcSpanDeg(angles: List<Double>): Double {
        if (angles.size <= 1) return 0.0
        val sorted = angles.sorted()
        var maxGap = 0.0
        for (i in 1 until sorted.size) maxGap = maxOf(maxGap, sorted[i] - sorted[i - 1])
        val wrap = (sorted.first() + 2 * PI) - sorted.last()
        maxGap = maxOf(maxGap, wrap)
        return (2 * PI - maxGap) * 180.0 / PI
    }
}
