// Spec §7.7. User-provided volume table with bilinear interpolation on
// (DBH, H). The coefficient record encodes the grid as a flat dictionary:
//
//   "dbh_0", "dbh_1", ..., "dbh_{n-1}"         monotonic DBH grid (cm)
//   "h_0",   "h_1",   ..., "h_{m-1}"           monotonic H grid (m)
//   "v_{i}_{j}" for i in 0..<n, j in 0..<m     V(cm, m) = m³
//
// Out-of-range queries clamp to the nearest edge.

import Foundation

public struct TableLookup: VolumeEquation {

    private let dbhGrid: [Float]     // ascending cm
    private let hGrid:   [Float]     // ascending m
    private let volumes: [[Float]]   // [i][j] = V at (dbhGrid[i], hGrid[j])
    private let merchFraction: Float

    public init(coefficients: [String: Float]) {
        let dbhCount = Self.count(prefix: "dbh_", in: coefficients)
        let hCount   = Self.count(prefix: "h_",   in: coefficients)
        guard dbhCount >= 2, hCount >= 2 else {
            fatalError("TableLookup needs at least 2 DBH and 2 H grid points")
        }
        let dbh = (0..<dbhCount).map { CoefficientLookup.required(coefficients, "dbh_\($0)") }
        let h   = (0..<hCount).map   { CoefficientLookup.required(coefficients, "h_\($0)") }
        var vols: [[Float]] = Array(repeating: Array(repeating: 0, count: hCount),
                                    count: dbhCount)
        for i in 0..<dbhCount {
            for j in 0..<hCount {
                vols[i][j] = CoefficientLookup.required(coefficients, "v_\(i)_\(j)")
            }
        }
        self.dbhGrid = dbh
        self.hGrid = h
        self.volumes = vols
        self.merchFraction = CoefficientLookup.optional(coefficients, "merchFraction", default: 0.85)
    }

    public func totalVolumeM3(dbhCm: Float, heightM: Float) -> Float {
        guard dbhCm > 0, heightM > 0 else { return 0 }
        let (i0, i1, tI) = Self.bracket(dbhCm, in: dbhGrid)
        let (j0, j1, tJ) = Self.bracket(heightM, in: hGrid)
        let v00 = volumes[i0][j0]
        let v01 = volumes[i0][j1]
        let v10 = volumes[i1][j0]
        let v11 = volumes[i1][j1]
        let v0 = v00 + (v01 - v00) * tJ
        let v1 = v10 + (v11 - v10) * tJ
        return v0 + (v1 - v0) * tI
    }

    public func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                                     topDibCm: Float, stumpHeightCm: Float) -> Float {
        totalVolumeM3(dbhCm: dbhCm, heightM: heightM) * merchFraction
    }

    // MARK: - helpers

    /// Number of sequential keys `<prefix>0, <prefix>1, ...` present in `dict`.
    private static func count(prefix: String, in dict: [String: Float]) -> Int {
        var n = 0
        while dict["\(prefix)\(n)"] != nil { n += 1 }
        return n
    }

    /// Return (low-index, high-index, t) for bracketing `x` within `grid`.
    /// Clamps at both ends.
    private static func bracket(_ x: Float, in grid: [Float]) -> (Int, Int, Float) {
        if x <= grid.first! { return (0, min(1, grid.count - 1), 0) }
        if x >= grid.last!  { let last = grid.count - 1
                              return (max(0, last - 1), last, 1) }
        var lo = 0, hi = grid.count - 1
        while hi - lo > 1 {
            let mid = (hi + lo) / 2
            if grid[mid] <= x { lo = mid } else { hi = mid }
        }
        let t = (x - grid[lo]) / (grid[hi] - grid[lo])
        return (lo, hi, t)
    }
}
