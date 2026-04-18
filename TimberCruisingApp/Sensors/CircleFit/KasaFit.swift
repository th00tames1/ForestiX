// Spec §7.1 Step 7 — algebraic circle fit by Kåsa. Used as the fast
// hypothesis generator inside RANSAC (3-point exact solution) and as a
// fall-back closed-form estimator for any N>=3 points in 2D. Biased on
// short arcs — Taubin refines afterwards.

import Foundation

public struct Circle2D: Equatable {
    public let cx: Double
    public let cy: Double
    public let radius: Double

    public init(cx: Double, cy: Double, radius: Double) {
        self.cx = cx
        self.cy = cy
        self.radius = radius
    }
}

public enum KasaFit {

    /// Closed-form Kåsa fit. Minimises Σ (x²+y² + Dx + Ey + F)² via a linear
    /// normal-equation solve. Returns nil for collinear inputs.
    public static func fit(points: [SIMD2<Double>]) -> Circle2D? {
        guard points.count >= 3 else { return nil }

        var sxx = 0.0, syy = 0.0, sxy = 0.0
        var sx = 0.0, sy = 0.0
        var sxz = 0.0, syz = 0.0, sz = 0.0
        let n = Double(points.count)
        for p in points {
            let z = p.x * p.x + p.y * p.y
            sxx += p.x * p.x
            syy += p.y * p.y
            sxy += p.x * p.y
            sx  += p.x
            sy  += p.y
            sxz += p.x * z
            syz += p.y * z
            sz  += z
        }

        // Normal equations for [D, E, F]:
        // | sxx  sxy  sx | | D |   | -sxz |
        // | sxy  syy  sy | | E | = | -syz |
        // |  sx   sy   n | | F |   |  -sz |
        let a = [
            [sxx, sxy, sx],
            [sxy, syy, sy],
            [sx,   sy,  n]
        ]
        let b = [-sxz, -syz, -sz]
        guard let x = solve3x3(a: a, b: b) else { return nil }
        let D = x[0], E = x[1], F = x[2]
        let cx = -D / 2
        let cy = -E / 2
        let rSq = cx * cx + cy * cy - F
        guard rSq > 0 else { return nil }
        return Circle2D(cx: cx, cy: cy, radius: sqrt(rSq))
    }

    /// Exact 3-point circle through non-collinear points. Returns nil if
    /// the three points are collinear within numerical tolerance.
    public static func fit(
        _ p0: SIMD2<Double>, _ p1: SIMD2<Double>, _ p2: SIMD2<Double>
    ) -> Circle2D? {
        let ax = p0.x, ay = p0.y
        let bx = p1.x, by = p1.y
        let cx0 = p2.x, cy0 = p2.y

        let d = 2 * (ax * (by - cy0) + bx * (cy0 - ay) + cx0 * (ay - by))
        if abs(d) < 1e-12 { return nil }

        let aSq = ax * ax + ay * ay
        let bSq = bx * bx + by * by
        let cSq = cx0 * cx0 + cy0 * cy0

        let ux = (aSq * (by - cy0) + bSq * (cy0 - ay) + cSq * (ay - by)) / d
        let uy = (aSq * (cx0 - bx) + bSq * (ax - cx0) + cSq * (bx - ax)) / d
        let rx = ax - ux, ry = ay - uy
        let r = sqrt(rx * rx + ry * ry)
        return Circle2D(cx: ux, cy: uy, radius: r)
    }

    private static func solve3x3(a: [[Double]], b: [Double]) -> [Double]? {
        let det =
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1])
          - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0])
          + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        guard abs(det) > 1e-18 else { return nil }
        let inv = 1.0 / det
        func subMat(_ col: Int) -> [[Double]] {
            var m = a
            for row in 0..<3 { m[row][col] = b[row] }
            return m
        }
        func det3(_ m: [[Double]]) -> Double {
            m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
          - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
          + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        }
        return [det3(subMat(0)) * inv, det3(subMat(1)) * inv, det3(subMat(2)) * inv]
    }
}
