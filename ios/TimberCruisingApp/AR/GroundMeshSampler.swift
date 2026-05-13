// Spec §7.8 Step 2 + REQ-BND-002. Samples ground Y at an arbitrary (x, z)
// column by vertical-raycasting against an ARMeshAnchor soup and falling
// back to nearest-vertex interpolation when no triangle contains the
// query column.
//
// The sampler is split into two layers so the math is testable on macOS:
//
//   • `GroundMeshSnapshot` — POD holding world-space vertices + flat
//     triangle indices. The iOS ARMeshAnchor bridge builds one of these
//     from `ARMeshAnchor.geometry` at render time.
//   • `GroundMeshSampler`  — pure `sampleGroundY(atX:z:snapshot:)` plus
//     `nearestVertexY`. Both are deterministic and unit-tested with
//     synthetic meshes.

import Foundation
import simd

// MARK: - Snapshot

/// World-space mesh soup captured from ARMeshAnchor instances. Triangle
/// winding is irrelevant for the point-in-triangle test; callers can
/// flatten in any order.
public struct GroundMeshSnapshot: Sendable, Equatable {
    public let vertices: [SIMD3<Float>]
    /// Flat index buffer, 3 indices per triangle. Empty → `sampleGroundY`
    /// is forced into the nearest-vertex fallback.
    public let triangles: [UInt32]

    public init(vertices: [SIMD3<Float>], triangles: [UInt32]) {
        self.vertices = vertices
        self.triangles = triangles
    }

    public static let empty = GroundMeshSnapshot(vertices: [], triangles: [])
}

// MARK: - Sampler

public enum GroundMeshSampler {

    /// Vertical-raycast `atX, z` against the snapshot. Returns the
    /// interpolated ground Y for the first triangle whose (x, z)
    /// projection contains the query. If no triangle hits, falls back to
    /// `nearestVertexY`.
    public static func sampleGroundY(
        atX x: Float,
        z: Float,
        snapshot: GroundMeshSnapshot
    ) -> Float? {
        if let y = rayCastY(x: x, z: z, snapshot: snapshot) {
            return y
        }
        return nearestVertexY(atX: x, z: z, snapshot: snapshot)
    }

    /// Strict vertical-ray hit — returns nil if the column does not fall
    /// inside any triangle. Exposed separately so tests can distinguish
    /// ray hits from nearest-vertex fallbacks.
    public static func rayCastY(
        x: Float,
        z: Float,
        snapshot: GroundMeshSnapshot
    ) -> Float? {
        guard snapshot.triangles.count >= 3 else { return nil }
        let tri = snapshot.triangles
        let v   = snapshot.vertices
        var i = 0
        while i + 2 < tri.count {
            let a = v[Int(tri[i])]
            let b = v[Int(tri[i + 1])]
            let c = v[Int(tri[i + 2])]
            if let y = interpolateY(x: x, z: z, a: a, b: b, c: c) {
                return y
            }
            i += 3
        }
        return nil
    }

    /// Nearest-vertex fallback. Returns Y of the vertex with the
    /// smallest (x, z) 2-D distance to the query; nil if snapshot empty.
    public static func nearestVertexY(
        atX x: Float,
        z: Float,
        snapshot: GroundMeshSnapshot
    ) -> Float? {
        guard !snapshot.vertices.isEmpty else { return nil }
        var bestY: Float = 0
        var bestSq: Float = .greatestFiniteMagnitude
        for v in snapshot.vertices {
            let dx = v.x - x
            let dz = v.z - z
            let d2 = dx * dx + dz * dz
            if d2 < bestSq {
                bestSq = d2
                bestY = v.y
            }
        }
        return bestY
    }

    // MARK: - 2D barycentric test

    /// Returns interpolated Y if (x, z) falls inside triangle ABC's (x, z)
    /// projection (edge-inclusive), else nil. Uses signed-area (shoelace)
    /// barycentric coordinates; resilient to any triangle winding.
    @inlinable
    static func interpolateY(
        x: Float, z: Float,
        a: SIMD3<Float>, b: SIMD3<Float>, c: SIMD3<Float>
    ) -> Float? {
        // 2× signed area in the XZ plane. Shoelace with the Y axis
        // dropped.
        let denom = (b.x - a.x) * (c.z - a.z) - (c.x - a.x) * (b.z - a.z)
        if abs(denom) < 1e-12 { return nil }      // degenerate triangle
        let wB = ((c.z - a.z) * (x - a.x) + (a.x - c.x) * (z - a.z)) / denom
        let wC = ((a.z - b.z) * (x - a.x) + (b.x - a.x) * (z - a.z)) / denom
        let wA = 1 - wB - wC
        // Edge-inclusive — accept points on the boundary.
        let eps: Float = -1e-5
        if wA < eps || wB < eps || wC < eps { return nil }
        return wA * a.y + wB * b.y + wC * c.y
    }
}
