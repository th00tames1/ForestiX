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

/// Where the camera OPTICAL AXIS lands on the view, and how far that is from
/// the geometric view centre the aim ring is drawn at. Developer instrument
/// only — see `ARCenterRaycaster.opticalAxisProbe()`. Nothing measured,
/// stored or exported reads any of these numbers.
///
/// `viewPoint` is in POINTS, because that is what a SwiftUI overlay is
/// positioned in. `dxPx`, `dyPx` and `rPx` are DEVICE PIXELS (points ×
/// the view's content scale) so the readout agrees with a ruler laid on a
/// screenshot, and so the number means the same thing as its Android twin.
/// `dxDeg/dyDeg/rDeg` are the same offset as an ANGLE: `rDeg` is the angle
/// between the ray through the view centre and the optical axis, and dx/dy
/// split it along the view axes in the same proportion as the pixel offset.
public struct OpticalAxisProbe: Equatable {
    public let viewPoint: CGPoint
    public let dxPx: CGFloat
    public let dyPx: CGFloat
    public let rPx: CGFloat
    public let dxDeg: Double
    public let dyDeg: Double
    public let rDeg: Double
}

#if canImport(ARKit) && os(iOS)
import ARKit
import RealityKit
import UIKit
import simd

@MainActor
public final class ARCenterRaycaster: ObservableObject {
    /// Populated by ARCameraView.makeUIView. Weak so we don't hold the
    /// view alive past its lifecycle.
    public weak var arview: ARView?

    /// When `true` (default), `screenCenterHit()` tries the LiDAR scene-
    /// mesh raycast first. Set `false` to force the AR (estimated-plane)
    /// path — the measurement screens flip this from the LiDAR/AR toggle
    /// so the cruiser can compare paths, and it's pinned to `false` on
    /// devices without LiDAR (where the mesh never exists anyway).
    public var preferLiDARMesh: Bool = true

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

        // LiDAR mesh path first — but only when the cruiser hasn't forced
        // AR mode via the toggle. In AR mode we skip straight to the
        // estimated-plane raycast below.
        if preferLiDARMesh, let mesh = meshRaycastHit(at: center, in: view) {
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

    /// Raycast at an ARBITRARY screen point (generalises screenCenterHit).
    /// Used by the AR-caliper DBH path to get the distance to the trunk at
    /// the midpoint of the two edge taps. Tries vertical estimated plane
    /// (the trunk surface) first, then horizontal, then any.
    public func hit(at screenPoint: CGPoint) -> SIMD3<Float>? {
        guard let view = arview else { return nil }
        if preferLiDARMesh, let mesh = meshRaycastHit(at: screenPoint, in: view) {
            return mesh
        }
        for alignment in [ARRaycastQuery.TargetAlignment.vertical, .horizontal, .any] {
            if let hit = view.raycast(from: screenPoint,
                                      allowing: .estimatedPlane,
                                      alignment: alignment).first {
                return worldTranslation(from: hit)
            }
        }
        return nil
    }

    /// Unit world-space ray direction through a screen point, via the exact
    /// camera intrinsics ARKit would use (no manual unprojection). The two
    /// edge-tap directions' subtended angle drives the AR-caliper diameter.
    public func rayDirection(at screenPoint: CGPoint) -> SIMD3<Float>? {
        guard let view = arview,
              let q = view.makeRaycastQuery(from: screenPoint,
                                            allowing: .estimatedPlane,
                                            alignment: .any)
        else { return nil }
        return simd_length(q.direction) > 1e-6 ? simd_normalize(q.direction) : nil
    }

    /// Current camera world position (for the AR-caliper distance = |camera
    /// − trunk-hit|).
    public var cameraWorldPosition: SIMD3<Float>? {
        guard let frame = arview?.session.currentFrame else { return nil }
        let c = frame.camera.transform.columns.3
        return SIMD3<Float>(c.x, c.y, c.z)
    }

    /// Elevation (deg) of the camera's forward aim above the horizon —
    /// positive = aiming up. Logged by the developer-mode research CSV so
    /// distance/DBH accuracy can be analysed against aim angle.
    public var cameraPitchDeg: Double? {
        guard let frame = arview?.session.currentFrame else { return nil }
        let t = frame.camera.transform
        // ARKit camera looks down -Z of its transform.
        let fwd = SIMD3<Float>(-t.columns.2.x, -t.columns.2.y, -t.columns.2.z)
        let horiz = (fwd.x * fwd.x + fwd.z * fwd.z).squareRoot()
        return Double(atan2(fwd.y, horiz)) * 180.0 / .pi
    }

    /// WHERE THE OPTICAL AXIS LANDS ON THE VIEW — a developer-mode
    /// INSTRUMENT. It measures a discrepancy; it does not correct one, and no
    /// caller may feed it into a measurement.
    ///
    /// The aim ring is drawn at the geometric centre of the view. But the
    /// elevation the height tangent consumes is the elevation of the CAMERA
    /// OPTICAL AXIS (the IMU pitch buffer, and `cameraPitchDeg` above), and
    /// the base/top marker spheres are placed along that same axis
    /// (`forwardPointAtHorizontalDistance`) — while the anchor sphere comes
    /// from `screenCenterHit()`, fired at the view centre. The optical axis
    /// projects to the lens PRINCIPAL POINT, not to the image centre, and the
    /// captured image is then rotated and aspect-cropped into a tall view, so
    /// the axis need not land on the view centre at all. Field measurement on
    /// the Android twin (screenshot, device held still) put the height anchor
    /// sphere ~8 px below a 28 px crosshair radius, ~0.5°, with zero
    /// horizontal error; a capture manifest's intrinsics account for only
    /// ~0.19° of that. This reports what the geometry actually is so the
    /// remainder stops being argued.
    ///
    /// BOTH steps go through ARKit's own `displayTransform`, the mapping the
    /// camera background is drawn with — display rotation and crop included —
    /// rather than a hand-rolled projection:
    ///   • principal point (normalised image) → view gives the point to draw
    ///     and the pixel offset from the view centre;
    ///   • view centre → normalised image gives the ANGLE, straight off the
    ///     intrinsics (atan of the image offset over the focal length).
    ///     Converting the pixel offset to an angle instead would need a focal
    ///     length expressed in VIEW pixels — i.e. the crop factor that is
    ///     precisely what is in question here.
    ///
    /// Returns nil, never a plausible zero, when there is no frame, no laid-
    /// out view, no usable intrinsics, or a display transform that cannot be
    /// inverted.
    public func opticalAxisProbe() -> OpticalAxisProbe? {
        guard let view = arview else { return nil }
        let size = view.bounds.size
        guard size.width > 1, size.height > 1 else { return nil }
        guard let frame = view.session.currentFrame else { return nil }
        let resolution = frame.camera.imageResolution
        guard resolution.width > 1, resolution.height > 1 else { return nil }
        // Intrinsics are for the captured image at `imageResolution`, in the
        // same (unrotated, capture-orientation) space `displayTransform`
        // takes its input in.
        let k = frame.camera.intrinsics
        let fx = CGFloat(k.columns.0.x)
        let fy = CGFloat(k.columns.1.y)
        let cx = CGFloat(k.columns.2.x)
        let cy = CGFloat(k.columns.2.y)
        guard fx > 1, fy > 1, cx.isFinite, cy.isFinite else { return nil }
        // The orientation has to come from the window scene the view is
        // actually in — a hard-coded `.portrait` silently transposes the
        // mapping on a landscape device (same reasoning as
        // `ARCameraView.reportViewport`).
        let orientation = view.window?.windowScene?.interfaceOrientation
            ?? UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.interfaceOrientation }
                .first
            ?? .portrait
        let dt = frame.displayTransform(for: orientation, viewportSize: size)
        guard abs(dt.a * dt.d - dt.b * dt.c) > 1e-9 else { return nil }

        // Principal point → view points: the mark to draw.
        let axisNorm = CGPoint(x: cx / resolution.width,
                               y: cy / resolution.height).applying(dt)
        let axisPoint = CGPoint(x: axisNorm.x * size.width,
                                y: axisNorm.y * size.height)
        guard axisPoint.x.isFinite, axisPoint.y.isFinite else { return nil }

        // View centre → image pixels: the angle, off the intrinsics.
        let centreNorm = CGPoint(x: 0.5, y: 0.5).applying(dt.inverted())
        let u = centreNorm.x * resolution.width
        let v = centreNorm.y * resolution.height
        guard u.isFinite, v.isFinite else { return nil }
        let tx = Double((u - cx) / fx)
        let ty = Double((v - cy) / fy)
        let rDeg = atan((tx * tx + ty * ty).squareRoot()) * 180 / .pi
        guard rDeg.isFinite else { return nil }

        let scale = view.contentScaleFactor
        let dx = (axisPoint.x - size.width / 2) * scale
        let dy = (axisPoint.y - size.height / 2) * scale
        let rPx = (dx * dx + dy * dy).squareRoot()
        // The angle is a scalar; its DIRECTION on screen is the pixel
        // offset's, which keeps the per-axis degrees signed like the pixels.
        var dxDeg = 0.0
        var dyDeg = 0.0
        if rPx > 1e-6 {
            dxDeg = rDeg * Double(dx / rPx)
            dyDeg = rDeg * Double(dy / rPx)
        }
        return OpticalAxisProbe(viewPoint: axisPoint,
                                dxPx: dx,
                                dyPx: dy,
                                rPx: rPx,
                                dxDeg: dxDeg,
                                dyDeg: dyDeg,
                                rDeg: rDeg)
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

        // FIELD REPORT 9 — this used to walk every triangle of every mesh
        // anchor, fired at 10 Hz by the height anchor-aim sampler and the
        // sampling-plot preview poll, so by the tenth tree of a plot a
        // screen-centre raycast was tens of thousands of ray/triangle tests.
        //
        // Two rejections, no change to which surface is reported:
        //   1. Per-anchor slab test against a local-space AABB, rebuilt on
        //      every call from the SAME vertices the triangles are built
        //      from — so a box miss is a triangle miss, and a mesh ARKit
        //      has just refined can never be tested against a stale box.
        //      One linear vertex pass replaces the anchor's whole face list.
        //   2. Nearest box first, and any anchor whose box only starts
        //      BEYOND the closest hit found so far is skipped outright —
        //      it cannot produce a nearer hit, which is the only thing
        //      this function returns.
        //
        // BE PRECISE ABOUT WHAT THAT BOUGHT, because an earlier version of
        // this note claimed the growth was gone and it is not. Per call the
        // work is still Θ(all accumulated vertices): every anchor pays one
        // linear vertex pass to get its box, before anything is pruned.
        // What the pruning removes is the FACE walk — roughly two triangle
        // intersections per vertex, each far dearer than a simd_min/max —
        // for every anchor the ray misses, which in a forest is nearly all
        // of them. A large constant factor, not a change of order.
        //
        // Making it O(changed anchors) means caching the box per
        // `anchor.identifier` and invalidating it when ARKit refines that
        // anchor's geometry. That is the right fix and it is deliberately
        // NOT done here: this class holds a weak ARView and is not the
        // session delegate, so it has no update signal to invalidate on,
        // and ARKit's mesh buffers are documented as valid only for the
        // frame they were vended in. A box that silently goes stale rejects
        // an anchor the ray does hit, and this function then returns a
        // farther surface — or nil — with no way for the caller to know.
        // That is the exact anchor bias the class was written to remove, so
        // it does not get reintroduced without a device to verify it on.
        var candidates: [(anchor: ARMeshAnchor, tNear: Float)] = []
        candidates.reserveCapacity(meshAnchors.count)
        for anchor in meshAnchors {
            guard let inv = invertibleInverse(anchor.transform) else { continue }
            let localOrigin = transform(rayOrigin, by: inv)
            let localDir = rotate(rayDirection, by: inv)
            guard let box = localBounds(of: anchor) else { continue }
            guard let tNear = slabIntersection(origin: localOrigin,
                                               direction: localDir,
                                               boxMin: box.min,
                                               boxMax: box.max)
            else { continue }
            candidates.append((anchor, tNear))
        }
        candidates.sort { $0.tNear < $1.tNear }

        for candidate in candidates {
            // Everything left in the list starts further away than the hit
            // we already have; nothing nearer can come out of it.
            if candidate.tNear >= bestT { break }
            let anchor = candidate.anchor
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

    // MARK: - Bounding-volume rejection

    /// Axis-aligned bounds of an anchor's vertices, in the anchor's own
    /// local frame. One linear pass over the vertex buffer — cheap next to
    /// the face walk it guards, but NOT free: this pass is what keeps the
    /// raycast's cost proportional to the accumulated reconstruction. It is
    /// recomputed every call on purpose, so a refined mesh can never be
    /// tested against a stale box; see the note at the call site for why
    /// the cache that would remove the growth is not taken from here.
    private func localBounds(
        of anchor: ARMeshAnchor
    ) -> (min: SIMD3<Float>, max: SIMD3<Float>)? {
        let vertices = anchor.geometry.vertices
        guard vertices.count > 0 else { return nil }
        let ptr = vertices.buffer.contents()
            .advanced(by: vertices.offset)
            .assumingMemoryBound(to: UInt8.self)
        var lo = readVertex(ptr, index: 0, stride: vertices.stride)
        var hi = lo
        for i in 1..<vertices.count {
            let v = readVertex(ptr, index: i, stride: vertices.stride)
            lo = simd_min(lo, v)
            hi = simd_max(hi, v)
        }
        return (lo, hi)
    }

    /// Ray/AABB slab test. Returns the ray parameter at which the ray
    /// ENTERS the box (0 when the origin is already inside), or nil when it
    /// misses. `direction` need not be normalised — `t` stays in the same
    /// parameterisation as the direction it was built from, which is what
    /// lets the caller compare it against triangle hits from the same ray.
    private func slabIntersection(origin: SIMD3<Float>,
                                  direction: SIMD3<Float>,
                                  boxMin: SIMD3<Float>,
                                  boxMax: SIMD3<Float>) -> Float? {
        var tMin: Float = 0
        var tMax: Float = .greatestFiniteMagnitude
        for axis in 0..<3 {
            let d = direction[axis]
            let o = origin[axis]
            if abs(d) < 1e-9 {
                // Parallel to this pair of planes: a miss unless the origin
                // already lies between them.
                if o < boxMin[axis] || o > boxMax[axis] { return nil }
                continue
            }
            let inv = 1 / d
            var t0 = (boxMin[axis] - o) * inv
            var t1 = (boxMax[axis] - o) * inv
            if t0 > t1 { swap(&t0, &t1) }
            tMin = max(tMin, t0)
            tMax = min(tMax, t1)
            if tMin > tMax { return nil }
        }
        return tMin
    }

    /// Inverse of an anchor transform, or nil when it is degenerate.
    /// ARKit anchor transforms are rigid, so this is only ever nil for a
    /// transform that has not resolved.
    private func invertibleInverse(_ m: simd_float4x4) -> simd_float4x4? {
        let det = m.determinant
        guard det.isFinite, abs(det) > 1e-12 else { return nil }
        return m.inverse
    }

    /// Rotate a DIRECTION by a transform — translation deliberately
    /// dropped (w = 0), so the result is still a direction.
    private func rotate(_ v: SIMD3<Float>,
                        by m: simd_float4x4) -> SIMD3<Float> {
        let r = m * SIMD4<Float>(v.x, v.y, v.z, 0)
        return SIMD3<Float>(r.x, r.y, r.z)
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
        // ONE unaligned 12-byte copy, not three 4-byte ones: this is the
        // innermost read of both the bounds pass and the face walk.
        // SIMD3<Float> is 16 bytes wide in memory, so it can't be the memcpy
        // destination — the tuple is exactly the packed 3 × Float the mesh
        // buffer stores.
        var xyz: (Float, Float, Float) = (0, 0, 0)
        withUnsafeMutableBytes(of: &xyz) { dst in
            guard let base = dst.baseAddress else { return }
            memcpy(base, ptr.advanced(by: index * stride), 3 * MemoryLayout<Float>.size)
        }
        return SIMD3<Float>(xyz.0, xyz.1, xyz.2)
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
    public var preferLiDARMesh: Bool = true
    public init() {}
    public func screenCenterHit() -> SIMD3<Float>? { nil }
    public func forwardPointAtHorizontalDistance(_ d: Float) -> SIMD3<Float>? { nil }
    public func hit(at screenPoint: CGPoint) -> SIMD3<Float>? { nil }
    public func rayDirection(at screenPoint: CGPoint) -> SIMD3<Float>? { nil }
    public var cameraWorldPosition: SIMD3<Float>? { nil }
    public var cameraPitchDeg: Double? { nil }
    public func opticalAxisProbe() -> OpticalAxisProbe? { nil }
}

#endif
