// Spec §7.1 Step 4 — pinhole back-projection of a depth pixel to 3-D.
// Depth is metric, along the optical axis (ARKit convention). Intrinsics
// are the simd_float3x3 column-major K from ARCamera.
//
// Camera frame → world frame uses `cameraPoseWorld` (T_world_camera).
// For circle fitting we keep only (x, z) in the world frame because the
// tree trunk is assumed vertical (the ARKit world is gravity-aligned,
// so trunk runs along +Y).

import Foundation
import simd

public enum BackProjection {

    /// Unproject a depth pixel into the camera frame.
    ///
    /// `intrinsics` is column-major simd (Apple convention); the pinhole
    /// model reads `fx = K[0,0]`, `fy = K[1,1]`, `cx = K[2,0]`, `cy = K[2,1]`.
    @inlinable
    public static func cameraPoint(
        x: Double, y: Double, depth: Double, intrinsics K: simd_float3x3
    ) -> SIMD3<Double> {
        let fx = Double(K[0, 0])
        let fy = Double(K[1, 1])
        let cx = Double(K[2, 0])
        let cy = Double(K[2, 1])
        let Xc = (x - cx) * depth / fx
        let Yc = (y - cy) * depth / fy
        let Zc = depth
        return SIMD3(Xc, Yc, Zc)
    }

    /// Unproject to camera then apply the 4×4 pose to get a world-space
    /// point. `cameraPoseWorld` is T_world_camera in column-major simd.
    @inlinable
    public static func worldPoint(
        x: Double, y: Double, depth: Double,
        intrinsics K: simd_float3x3,
        cameraPoseWorld T: simd_float4x4
    ) -> SIMD3<Double> {
        let pc = cameraPoint(x: x, y: y, depth: depth, intrinsics: K)
        // Promote to Double-precision 4×4 multiply — depth pixels are
        // noisy enough that the extra precision is free insurance.
        let m = T
        let r0 = SIMD4<Double>(Double(m[0,0]), Double(m[1,0]),
                               Double(m[2,0]), Double(m[3,0]))
        let r1 = SIMD4<Double>(Double(m[0,1]), Double(m[1,1]),
                               Double(m[2,1]), Double(m[3,1]))
        let r2 = SIMD4<Double>(Double(m[0,2]), Double(m[1,2]),
                               Double(m[2,2]), Double(m[3,2]))
        let v = SIMD4<Double>(pc.x, pc.y, pc.z, 1)
        return SIMD3((r0 * v).sum(), (r1 * v).sum(), (r2 * v).sum())
    }

    /// World-space (x, z) projection used by §7.1 Step 4. Assumes gravity
    /// world alignment so trunk points collapse onto the horizontal plane.
    @inlinable
    public static func worldXZ(
        x: Double, y: Double, depth: Double,
        intrinsics K: simd_float3x3,
        cameraPoseWorld T: simd_float4x4
    ) -> SIMD2<Double> {
        let p = worldPoint(x: x, y: y, depth: depth,
                           intrinsics: K, cameraPoseWorld: T)
        return SIMD2(p.x, p.z)
    }
}
