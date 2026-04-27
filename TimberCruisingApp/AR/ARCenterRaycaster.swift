// Bridge class that lets SwiftUI callers fire a screen-centre raycast
// against the ARView hosted by `ARCameraView`.
//
// Why this exists: HeightScanScreen needs to translate "the cruiser
// tapped Anchor Here while the crosshair was on the tree base" into a
// world coordinate, but the view model is deliberately sensor-layer-
// agnostic and the view itself can't reach into a UIViewRepresentable's
// ARView. Pass an ARCenterRaycaster into ARCameraView — it captures a
// weak reference to the underlying ARView on `makeUIView`, and the
// screen calls `screenCenterHit()` when a button fires.
//
// Phase 8 — anchor-bias fix:
//   `screenCenterHit()` now tries the LiDAR scene-reconstruction mesh
//   FIRST (Möller-Trumbore ray/triangle intersection against every
//   ARMeshAnchor's geometry) and only falls back to ARKit's plane
//   raycast when the device has no LiDAR or no mesh has been built yet.
//   This eliminates the systematic anchor bias that previously snapped
//   to the camera position whenever the plane raycast missed — for tree
//   bases against canopy / leaf litter the plane fit was unreliable, so
//   the anchor was silently the cruiser's standing position and d_h was
//   biased by the initial cruiser-to-tree offset.

import Foundation

#if canImport(ARKit) && os(iOS)
import ARKit
import RealityKit
import simd

@MainActor
public final class ARCenterRaycaster: ObservableObject {
    /// Populated by ARCameraView.makeUIView. Weak so we don't hold the
    /// view alive past its lifecycle.
    public weak var arview: ARView?

    public init() {}

    /// Raycasts from the centre of the current view bounds. Tries paths
    /// in this order:
    ///   1. LiDAR scene-mesh raycast — deterministic on every reconstructed
    ///      surface, including tree trunks and uneven forest floor.
    ///   2. Estimated horizontal plane raycast — works on non-LiDAR
    ///      devices and on LiDAR devices that haven't yet built mesh.
    ///   3. Estimated plane (any alignment) — last-resort plane fit.
    /// Returns nil only when every path fails. Callers must treat nil
    /// as "tracking not ready yet" rather than silently substituting
    /// the camera position (which biases downstream measurements).
    public func screenCenterHit() -> SIMD3<Float>? {
        guard let view = arview else { return nil }
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return nil }
        let center = CGPoint(x: bounds.midX, y: bounds.midY)

        if let mesh = meshRaycastHit(at: center, in: view) {
            return mesh
        }

        // Horizontal plane next (ground); then any alignment.
        if let hit = view.raycast(
            from: center,
            allowing: .estimatedPlane,
            alignment: .horizontal
        ).first {
            return worldTranslation(from: hit)
        }
        if let hit = view.raycast(
            from: center,
            allowing: .estimatedPlane,
            alignment: .any
        ).first {
            return worldTranslation(from: hit)
        }
        return nil
    }

    /// Projects the camera forward ray to a world point at exactly
    /// `horizontalDistanceM` metres of horizontal distance. Used as a
    /// fallback for top / base taps where no plane exists in the
    /// direction the cruiser is aiming (sky, tree canopy) so the raw
    /// raycast comes back empty — we still want to drop a sphere so
    /// the cruiser sees *something* at roughly the aim point.
    public func forwardPointAtHorizontalDistance(_ d: Float) -> SIMD3<Float>? {
        guard let view = arview, let frame = view.session.currentFrame
        else { return nil }
        let t = frame.camera.transform
        // Column 2 is the camera's +Z (pointing backwards in ARKit);
        // -column2 is the forward direction.
        let forward = SIMD3<Float>(-t.columns.2.x,
                                   -t.columns.2.y,
                                   -t.columns.2.z)
        let origin = SIMD3<Float>(t.columns.3.x,
                                  t.columns.3.y,
                                  t.columns.3.z)
        let horizontal = (forward.x * forward.x + forward.z * forward.z).squareRoot()
        guard horizontal > 1e-4 else { return nil }
        // Scale the forward ray so its horizontal projection equals d.
        let scale = d / horizontal
        return origin + forward * scale
    }

    private func worldTranslation(from hit: ARRaycastResult) -> SIMD3<Float> {
        let c = hit.worldTransform.columns.3
        return SIMD3<Float>(c.x, c.y, c.z)
    }

    // MARK: - LiDAR mesh raycast

    /// Iterates every `ARMeshAnchor` in the current frame and runs a
    /// Möller-Trumbore ray/triangle intersection against each face.
    /// Returns the closest world-space hit point along the ray, or nil
    /// when:
    ///   • the device doesn't have LiDAR (no mesh anchors arrive),
    ///   • the scene reconstruction hasn't yet built any mesh in the
    ///     direction the cruiser is aiming,
    ///   • the screen point can't be unprojected to a world ray.
    private func meshRaycastHit(at screenPoint: CGPoint,
                                in view: ARView) -> SIMD3<Float>? {
        guard let frame = view.session.currentFrame else { return nil }

        // Build the world-space ray. ARFrame.raycastQuery gives the
        // exact origin/direction ARKit would use for its own raycast
        // through the supplied screen point — same camera intrinsics,
        // same display orientation, no manual unprojection. Fall back
        // to the camera-forward axis when the query can't be built
        // (rare; happens during the first frame or two before the
        // camera is calibrated).
        let cam = frame.camera.transform
        let rayOrigin: SIMD3<Float>
        let rayDirection: SIMD3<Float>
        if let q = view.makeRaycastQuery(from: screenPoint,
                                         allowing: .estimatedPlane,
                                         alignment: .any) {
            rayOrigin = q.origin
            let d = q.direction
            rayDirection = simd_length(d) > 1e-6
                ? simd_normalize(d)
                : SIMD3<Float>(-cam.columns.2.x,
                               -cam.columns.2.y,
                               -cam.columns.2.z)
        } else {
            rayOrigin = SIMD3<Float>(cam.columns.3.x,
                                     cam.columns.3.y,
                                     cam.columns.3.z)
            rayDirection = SIMD3<Float>(-cam.columns.2.x,
                                        -cam.columns.2.y,
                                        -cam.columns.2.z)
        }

        // Collect every ARMeshAnchor — these only exist on LiDAR
        // devices with sceneReconstruction = .mesh.
        let meshAnchors = frame.anchors.compactMap { $0 as? ARMeshAnchor }
        guard !meshAnchors.isEmpty else { return nil }

        var bestT: Float = .greatestFiniteMagnitude
        var bestHit: SIMD3<Float>?

        for anchor in meshAnchors {
            let geometry = anchor.geometry
            let anchorTransform = anchor.transform
            // Vertices are in the anchor's local frame — transform once
            // per face, not once per ray.
            let vertexCount = geometry.vertices.count
            let vertexBuffer = geometry.vertices.buffer
            let vertexStride = geometry.vertices.stride
            let vertexOffset = geometry.vertices.offset

            let faces = geometry.faces
            let faceCount = faces.count
            let indexBuffer = faces.buffer
            let indicesPerFace = faces.indexCountPerPrimitive
            // ARMeshGeometry.faces.bytesPerIndex was deprecated; the
            // primitive type is always .triangle and indices are uint32
            // on shipping LiDAR devices. Read bytes directly.
            let bytesPerIndex = MemoryLayout<UInt32>.size

            // Pull both buffers as raw pointers once.
            let vertexPtr = vertexBuffer.contents()
                .advanced(by: vertexOffset)
                .assumingMemoryBound(to: UInt8.self)
            let indexPtr = indexBuffer.contents()
                .assumingMemoryBound(to: UInt8.self)

            for face in 0..<faceCount {
                // Read three vertex indices for this triangle.
                let base = face * indicesPerFace * bytesPerIndex
                let i0 = readUInt32(indexPtr, offset: base)
                let i1 = readUInt32(indexPtr, offset: base + bytesPerIndex)
                let i2 = readUInt32(indexPtr, offset: base + 2 * bytesPerIndex)
                guard Int(i0) < vertexCount,
                      Int(i1) < vertexCount,
                      Int(i2) < vertexCount
                else { continue }

                let v0Local = readVertex(vertexPtr,
                                         index: Int(i0),
                                         stride: vertexStride)
                let v1Local = readVertex(vertexPtr,
                                         index: Int(i1),
                                         stride: vertexStride)
                let v2Local = readVertex(vertexPtr,
                                         index: Int(i2),
                                         stride: vertexStride)

                let v0 = transform(v0Local, by: anchorTransform)
                let v1 = transform(v1Local, by: anchorTransform)
                let v2 = transform(v2Local, by: anchorTransform)

                if let t = mollerTrumbore(origin: rayOrigin,
                                          direction: rayDirection,
                                          v0: v0, v1: v1, v2: v2),
                   t > 0, t < bestT {
                    bestT = t
                    bestHit = rayOrigin + rayDirection * t
                }
            }
        }

        return bestHit
    }

    // MARK: - Mesh-buffer plumbing

    private func readUInt32(_ ptr: UnsafePointer<UInt8>, offset: Int) -> UInt32 {
        // Avoid an unaligned load by copying through a scratch UInt32.
        var v: UInt32 = 0
        memcpy(&v, ptr.advanced(by: offset), MemoryLayout<UInt32>.size)
        return v
    }

    private func readVertex(_ ptr: UnsafePointer<UInt8>,
                            index: Int,
                            stride: Int) -> SIMD3<Float> {
        var x: Float = 0, y: Float = 0, z: Float = 0
        let base = index * stride
        memcpy(&x, ptr.advanced(by: base), MemoryLayout<Float>.size)
        memcpy(&y, ptr.advanced(by: base + MemoryLayout<Float>.size),
               MemoryLayout<Float>.size)
        memcpy(&z, ptr.advanced(by: base + 2 * MemoryLayout<Float>.size),
               MemoryLayout<Float>.size)
        return SIMD3<Float>(x, y, z)
    }

    private func transform(_ p: SIMD3<Float>,
                           by m: simd_float4x4) -> SIMD3<Float> {
        let v = m * SIMD4<Float>(p.x, p.y, p.z, 1)
        return SIMD3<Float>(v.x, v.y, v.z)
    }

    /// Möller-Trumbore ray/triangle intersection. Returns the ray
    /// parameter `t` such that `origin + direction · t` is the hit
    /// point, or nil for parallel rays / out-of-triangle hits. No
    /// back-face culling — the cruiser may be looking at a stem from
    /// either side of a reconstructed surface and we want both to hit.
    private func mollerTrumbore(origin: SIMD3<Float>,
                                direction: SIMD3<Float>,
                                v0: SIMD3<Float>,
                                v1: SIMD3<Float>,
                                v2: SIMD3<Float>) -> Float? {
        let edge1 = v1 - v0
        let edge2 = v2 - v0
        let h = simd_cross(direction, edge2)
        let a = simd_dot(edge1, h)
        if abs(a) < 1e-7 { return nil }
        let f = 1 / a
        let s = origin - v0
        let u = f * simd_dot(s, h)
        if u < 0 || u > 1 { return nil }
        let q = simd_cross(s, edge1)
        let v = f * simd_dot(direction, q)
        if v < 0 || (u + v) > 1 { return nil }
        let t = f * simd_dot(edge2, q)
        return t > 1e-5 ? t : nil
    }
}

#else

/// macOS stub — callers treat every raycast as a miss.
@MainActor
public final class ARCenterRaycaster: ObservableObject {
    public init() {}
    public func screenCenterHit() -> SIMD3<Float>? { nil }
    public func forwardPointAtHorizontalDistance(_ d: Float) -> SIMD3<Float>? { nil }
}

#endif
