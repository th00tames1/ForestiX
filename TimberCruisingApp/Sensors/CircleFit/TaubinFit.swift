// Spec §7.1 Step 7 — Taubin algebraic circle fit. Small-arc bias is far
// better than Kåsa so it is used for the final refit on the RANSAC
// inliers. Implementation follows Chernov & Lesort (2005) reduction to a
// 3×3 generalised eigenvalue problem on the centred coordinates.

import Foundation

public enum TaubinFit {

    public static func fit(points: [SIMD2<Double>]) -> Circle2D? {
        guard points.count >= 3 else { return nil }

        let n = Double(points.count)
        var xMean = 0.0, yMean = 0.0
        for p in points { xMean += p.x; yMean += p.y }
        xMean /= n; yMean /= n

        var mxx = 0.0, myy = 0.0, mxy = 0.0
        var mxz = 0.0, myz = 0.0, mzz = 0.0
        for p in points {
            let dx = p.x - xMean
            let dy = p.y - yMean
            let z = dx * dx + dy * dy
            mxx += dx * dx
            myy += dy * dy
            mxy += dx * dy
            mxz += dx * z
            myz += dy * z
            mzz += z  * z
        }
        mxx /= n; myy /= n; mxy /= n
        mxz /= n; myz /= n; mzz /= n

        let mz = mxx + myy
        let covXY = mxx * myy - mxy * mxy
        let a3 = 4 * mz
        let a2 = -3 * mz * mz - mzz
        let a1 = mzz * mz + 4 * covXY * mz
                - mxz * mxz - myz * myz - mz * mz * mz
        let a0 = mxz * mxz * myy + myz * myz * mxx
               - mzz * covXY - 2 * mxz * myz * mxy + mz * mz * covXY

        // Newton's method from x=0 — guaranteed to converge to the root
        // closest to zero, which is the smallest-positive root Taubin
        // prescribes.
        var x = 0.0
        var y = a0
        for _ in 0..<200 {
            let dy = a1 + x * (2 * a2 + x * 3 * a3)
            let xNew = x - y / dy
            if abs(xNew - x) < 1e-14 { x = xNew; break }
            let yNew = a0 + xNew * (a1 + xNew * (a2 + xNew * a3))
            if abs(yNew) >= abs(y) { break }
            x = xNew; y = yNew
        }

        let det = x * x - x * mz + covXY
        guard abs(det) > 1e-18 else { return nil }
        let cxLoc = (mxz * (myy - x) - myz * mxy) / (2 * det)
        let cyLoc = (myz * (mxx - x) - mxz * mxy) / (2 * det)
        let radius = sqrt(cxLoc * cxLoc + cyLoc * cyLoc + mz + 2 * x)
        return Circle2D(cx: cxLoc + xMean, cy: cyLoc + yMean, radius: radius)
    }
}
