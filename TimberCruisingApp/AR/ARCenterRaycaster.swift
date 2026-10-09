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

// The aim-offset instrument that used to live here (an `OpticalAxisProbe`
// reporting where the lens principal point lands against the view centre) is
// gone: it was built to settle whether the height anchor sphere sits off the
// crosshair, it was tested on device, and the answer was that it does not.
// Nothing measured, stored or exported ever read it.

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

    /// Per-ARFrame memo of `localBounds(of:)`, keyed by anchor identifier,
    /// with the timestamp of the frame it was built from.
    ///
    /// THE ONLY CACHE KEY THAT CANNOT GO STALE. Two raycasts that read the
    /// same `ARFrame` are looking at the same vended geometry by definition —
    /// ARKit built that frame's anchor snapshot once — so a box computed for
    /// frame T is exact for every other call that also reads frame T, and is
    /// thrown away the instant the timestamp moves. It buys nothing across
    /// frames and is not meant to; see the note in `meshRaycastHit` for why
    /// a cross-frame cache is refused.
    ///
    /// What it does buy: a TAP that lands in the same frame as a poll tick no
    /// longer pays the vertex walk twice. That is the stall the cruiser feels,
    /// because the tap is the moment they are waiting on.
    private var boundsMemo: [UUID: (min: SIMD3<Float>, max: SIMD3<Float>)] = [:]
    private var boundsMemoFrameTime: TimeInterval?

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

    /// Screen-centre raycast for placing the HEIGHT TRUNK ANCHOR — the one
    /// hit in the app that must land on the STEM and nothing else.
    ///
    /// FIELD ROUND 10. The cruiser re-shoots the anchor over and over on iOS
    /// and almost never on Android. It is not an aim offset — that was
    /// instrumented on device and closed (see the note at the top of this
    /// file). It is the SELECTION POLICY, and `screenCenterHit()` above has
    /// the wrong one for this job in three separate ways:
    ///
    ///   • GROUND FIRST. Its plane fallback tries `.horizontal` before
    ///     `.any`. The anchor is aimed at eye level, so the ray is
    ///     near-horizontal and its intersection with ARKit's estimated GROUND
    ///     plane is tens of metres out. Android hit exactly this in field
    ///     round 8 and measured it: 12.78 / 31.11 / 44.33 m as the pitch
    ///     wobbled a few degrees at ~1.6 m eye height.
    ///   • NO FACING TEST. An estimated plane is unbounded, so a ray that
    ///     merely grazes it counts as a hit. A ground plane met at ~2° of
    ///     incidence is not a surface the cruiser aimed at; a trunk face met
    ///     near-perpendicular is.
    ///   • THE RANGE GATE LIVES SOMEWHERE ELSE. `anchorHereNow` checks ≤ 4 m
    ///     AFTER this returns, so a 31 m ground hit does not fall through to
    ///     the next candidate — it consumes the tap and the "+" does nothing.
    ///     That silent no-op IS the re-shooting.
    ///
    /// So this is `ArController.screenCenterAnchorHit(maxDistM)` ported: the
    /// gate is inside the selection, candidates are tried in order rather than
    /// the first path winning outright, a plane must be BOTH inside the gate
    /// AND facing the ray (normal within 30° of the reverse ray, i.e.
    /// dot ≤ −cos30°), and a miss is a miss rather than a far surface.
    ///
    /// `screenCenterHit()` is deliberately left alone: the aim-top / aim-base
    /// taps and the plot-centre pin want far surfaces and the ground, which is
    /// precisely what this refuses. Android keeps both functions for the same
    /// split.
    public func screenCenterAnchorHit(maxDistM: Float) -> SIMD3<Float>? {
        guard let view = arview else { return nil }
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return nil }
        let center = CGPoint(x: bounds.midX, y: bounds.midY)
        guard let frame = view.session.currentFrame else { return nil }
        let camT = frame.camera.transform.columns.3
        let cam = SIMD3<Float>(camT.x, camT.y, camT.z)

        // LiDAR mesh first, exactly as the general raycast does — a
        // reconstructed stem surface is the best anchor target there is. The
        // difference is that a mesh hit BEYOND the gate no longer ends the
        // search: the ray may have skimmed past the trunk into distant
        // reconstruction, and a plane candidate can still be right.
        if preferLiDARMesh, let mesh = meshRaycastHit(at: center, in: view),
           simd_distance(cam, mesh) <= maxDistM {
            return mesh
        }

        // `.any` alignment, NOT horizontal-then-any: the facing test below is
        // what decides which surface is acceptable, and asking for horizontal
        // first is the ground-first bias this function exists to remove.
        for hit in view.raycast(from: center,
                                allowing: .estimatedPlane,
                                alignment: .any) {
            let t = hit.worldTransform
            let p = SIMD3<Float>(t.columns.3.x, t.columns.3.y, t.columns.3.z)
            guard simd_distance(cam, p) <= maxDistM else { continue }
            // +Y of an ARKit raycast result is the surface normal, the same
            // convention as ARCore's `hitPose.yAxis`.
            let n = SIMD3<Float>(t.columns.1.x, t.columns.1.y, t.columns.1.z)
            let ray = p - cam
            let len = simd_length(ray)
            guard len > 1e-4 else { continue }
            if simd_dot(n, ray / len) <= -0.866 { return p }
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

    /// What a surface the cruiser POINTED AT is expected to be, which decides
    /// the order estimated planes are tried in.
    ///
    /// It is not a preference. A ray through a screen point usually meets both
    /// a vertical and a horizontal candidate — the trunk in front and the
    /// ground under it — and whichever is asked for first wins. Asking in the
    /// wrong order does not fail; it silently returns the other surface, at a
    /// completely different distance.
    public enum SurfaceIntent {
        /// The trunk face. Vertical first — the AR-caliper edge taps.
        case upright
        /// The ground. HORIZONTAL FIRST: a tap at the foot of a stem is a ray
        /// that grazes the bark on its way down, so vertical-first hands back
        /// a point on the trunk and a base placed at chest height would carry
        /// the whole breast-height guide up with it.
        case ground
    }

    /// Raycast at an ARBITRARY screen point (generalises screenCenterHit).
    public func hit(at screenPoint: CGPoint,
                    intent: SurfaceIntent = .upright) -> SIMD3<Float>? {
        guard let view = arview else { return nil }
        let camera = cameraWorldPosition

        /// Inside the gate? `.ground` refuses anything past six metres; every
        /// other intent is unchanged. Fails OPEN with no camera pose, because
        /// a raycast that returned a hit without one is a state this cannot
        /// reason about and refusing would be worse than allowing.
        func withinGate(_ p: SIMD3<Float>) -> Bool {
            guard intent == .ground, let camera else { return true }
            return simd_distance(p, camera) <= Self.groundTapMaxM
        }

        // THE MESH PATH IS GATED TOO. It used to return unconditionally, and
        // `preferLiDARMesh` is true on every LiDAR phone in the default depth
        // mode — so on exactly the hardware this app is built for, the first
        // thing a ground tap did was take a mesh hit at any range whatsoever
        // and the six-metre rule below never ran once.
        if preferLiDARMesh, let mesh = meshRaycastHit(at: screenPoint, in: view),
           withinGate(mesh) {
            return mesh
        }
        // GROUND ALSO GETS A DISTANCE GATE, for the reason field round 8
        // documented on the other platform and the height anchor already
        // guards against: a tap near the foot of a stem sends a ray down at a
        // shallow angle, and a horizontal estimated plane accepted at any
        // range is the ray's intersection with the ground TENS OF METRES OUT
        // (12.78 / 31.11 / 44.33 m were measured, as the pitch wobbled a few
        // degrees). Planting a breast-height guide out there is the same
        // defect wearing a different hat. Six metres is generous for a
        // cruiser standing at the tree — the DBH band itself is 0.5–3 m — and
        // nowhere near what a grazed plane returns.
        //
        // AND `.ground` MEANS HORIZONTAL, not "horizontal if one is handy".
        // The fallback used to widen to `.any` after `.horizontal`, so a tap
        // at the foot of a stem where ARKit had fitted the TRUNK FACE but no
        // ground yet planted the base on the bark at the tapped height and
        // carried the whole 1.37 m assembly up with it — which is the precise
        // failure `SurfaceIntent` was introduced to prevent, left in as a
        // fallback. Android refuses it outright (`Plane.Type` must be
        // HORIZONTAL_*) and says so in `lastCenterHitInfo`; this now matches.
        let order: [ARRaycastQuery.TargetAlignment] = intent == .ground
            ? [.horizontal]
            : [.vertical, .horizontal, .any]
        for alignment in order {
            for hit in view.raycast(from: screenPoint,
                                    allowing: .estimatedPlane,
                                    alignment: alignment) {
                let p = worldTranslation(from: hit)
                guard withinGate(p) else { continue }
                return p
            }
        }
        return nil
    }

    /// The crosshair's own GROUND hit — the "Place base" button and the
    /// ghost preview, which aim at the same thing the tap does and had been
    /// left on the ungated `screenCenterHit()` when the tap was fixed.
    public func screenCenterGroundHit() -> SIMD3<Float>? {
        guard let view = arview else { return nil }
        let c = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        return hit(at: c, intent: .ground)
    }

    /// How far a GROUND tap may land, in metres. See `hit(at:intent:)`.
    /// Android `ArController.GROUND_TAP_MAX_M` 1:1.
    static let groundTapMaxM: Float = 6

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

    /// Where a world point lands on the AR view, in AR-VIEW coordinates —
    /// the inverse direction of every other call here, and the way 2D chrome
    /// is pinned to a world position without a world-space text renderer.
    ///
    /// It hangs off this class because this class already holds the view: a
    /// label placed from `arview.project` and the raycast that produced the
    /// point it labels are then reading the same rect by construction, which
    /// is exactly what goes wrong when a caller measures against the safe
    /// area instead. Callers must position from a full-bleed reader.
    ///
    /// nil when the point is behind the camera (RealityKit's own answer) or
    /// while no view is bound — both mean "draw nothing", which is the hide
    /// condition a projected label wants anyway.
    public func projectToScreen(_ world: SIMD3<Float>) -> CGPoint? {
        guard let view = arview else { return nil }
        return view.project(world)
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

        // Frame-scoped bounds memo — see `boundsMemo`. A new frame means new
        // vended geometry, so every box from the old one is dropped whole.
        if boundsMemoFrameTime != frame.timestamp {
            boundsMemoFrameTime = frame.timestamp
            boundsMemo.removeAll(keepingCapacity: true)
        }

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
        // anchor. NAME THE CALLERS HONESTLY, because the earlier version of
        // this note named two of the four and picked the slower one:
        //   • DistanceMeasureScreen live readout — a 0.05 s timer, 20 Hz,
        //     the highest rate in the app. It is not in the old list at all.
        //     It is now throttled to 10 Hz on this path; see the note on
        //     `lidarLivePollIntervalSec` there for why the timer itself could
        //     not simply be re-rated.
        //   • HeightScanScreen anchor-aim sampler — 10 Hz while aiming.
        //   • SamplingPlotScreen ghost-centre preview — 5 Hz while aiming.
        //   • DBHScanScreen — on TAP only. Not a poll.
        // And the volume was understated by orders of magnitude, not by a
        // little: a shared session that is never reset (deliberately — see
        // ARKitSessionManager.applyConfiguration) accumulates mesh from app
        // launch across every tree, plot and walk of the day. Reconstruction
        // carries roughly two triangles per vertex, so a hundred thousand
        // accumulated vertices is a couple of HUNDRED thousand ray/triangle
        // tests per call, and a million vertices is a couple of MILLION —
        // not "tens of thousands", and not per plot but per raycast.
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
        // `anchor.identifier` ACROSS frames and invalidating it when ARKit
        // refines that anchor's geometry. That is still deliberately NOT done,
        // and the reason is now stronger than "no signal is wired up":
        //
        //   • `ARMeshAnchor` carries no revision, version or generation — the
        //     anchor cannot tell you its geometry changed. Checked; there is
        //     no honest key on the object itself.
        //   • The one documented refinement signal is the session delegate's
        //     `session(_:didUpdate:anchors:)`. `ARKitSessionManager` IS the
        //     delegate, so it could be forwarded here. It still does not help,
        //     because nothing orders that callback against `session.currentFrame`.
        //     `currentFrame` advances on ARKit's own queue; the callback waits
        //     its turn on the delegate queue. A poll that reads `currentFrame`
        //     can therefore see refined geometry several frames before the
        //     invalidation arrives — and the lag is LONGEST exactly when the
        //     main thread is stalling, which is the only situation the cache
        //     would have been worth having.
        //
        // A box that silently goes stale rejects an anchor the ray does hit,
        // and this function then returns a farther surface — or nil — with no
        // way for the caller to know. That is the exact anchor bias the class
        // was written to remove. A correct walk beats a fast wrong one.
        //
        // What IS taken is the cache that cannot go stale: the frame-scoped
        // memo in `boundsMemo`, plus a constant-factor cut inside the vertex
        // pass itself (`localBounds`). Neither changes which surface is
        // reported. The Θ(accumulated vertices) growth per call survives both,
        // and it cannot be removed here — its cause is the never-reset shared
        // session, which is a deliberate trade made elsewhere.
        var candidates: [(anchor: ARMeshAnchor, tNear: Float)] = []
        candidates.reserveCapacity(meshAnchors.count)
        for anchor in meshAnchors {
            guard let inv = invertibleInverse(anchor.transform) else { continue }
            let localOrigin = transform(rayOrigin, by: inv)
            let localDir = rotate(rayDirection, by: inv)
            guard let box = memoizedLocalBounds(of: anchor) else { continue }
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

    /// `localBounds(of:)` behind the frame-scoped memo. Within one ARFrame
    /// the answer cannot change, so the second and later raycasts against
    /// that frame read it back instead of re-walking every vertex.
    private func memoizedLocalBounds(
        of anchor: ARMeshAnchor
    ) -> (min: SIMD3<Float>, max: SIMD3<Float>)? {
        if let cached = boundsMemo[anchor.identifier] { return cached }
        // An empty anchor costs nothing to re-answer, so it is not stored —
        // that keeps the memo a plain non-optional dictionary.
        guard let box = localBounds(of: anchor) else { return nil }
        boundsMemo[anchor.identifier] = box
        return box
    }

    /// Axis-aligned bounds of an anchor's vertices, in the anchor's own
    /// local frame. One linear pass over the vertex buffer.
    ///
    /// DO NOT read "cheap next to the face walk it guards" into this, which
    /// is what the previous note said and it is backwards in the case that
    /// matters. The pruning at the call site exists precisely because the ray
    /// misses nearly every anchor in a forest — and for every anchor it
    /// misses, this pass is not an adjunct to the cost, it IS the whole cost.
    /// Summed over a day's accumulated reconstruction that is the entire
    /// remaining Θ(all vertices) term of a raycast.
    ///
    /// So it is worth the constant factor below, and it is recomputed on
    /// every new frame on purpose: a refined mesh can never be tested against
    /// a stale box. See the note at the call site for why the cross-frame
    /// cache that would remove the growth is still refused.
    private func localBounds(
        of anchor: ARMeshAnchor
    ) -> (min: SIMD3<Float>, max: SIMD3<Float>)? {
        let vertices = anchor.geometry.vertices
        let count = vertices.count
        guard count > 0 else { return nil }
        let stride = vertices.stride
        let base = UnsafeRawPointer(vertices.buffer.contents())
            .advanced(by: vertices.offset)

        // FAST PATH — one 16-byte vector load per vertex instead of a
        // 12-byte memcpy through a stack tuple. This is the innermost loop of
        // the whole raycast; the memcpy form does not reliably stay in
        // registers, and the difference is a small multiple per vertex over
        // millions of vertices.
        //
        // Both preconditions are CHECKED, never assumed:
        //   • `stride == 16` — the shipping ARKit float3 layout pads each
        //     vertex to 16 bytes, so a 16-byte load lands exactly on one
        //     vertex slot and lane w is that slot's own padding.
        //   • the buffer really is long enough for `count` whole slots, so
        //     reading the last slot's padding is in bounds rather than
        //     "probably fine because Metal rounds up to a page".
        // Lane w is padding — it may be anything, including a NaN, and it is
        // discarded. simd_min/simd_max are lanewise, so x/y/z are unaffected
        // by whatever w holds. The result is bit-identical to the exact path.
        if stride == 16,
           vertices.buffer.length >= vertices.offset + count * stride {
            var lo4 = base.loadUnaligned(as: SIMD4<Float>.self)
            var hi4 = lo4
            for i in 1..<count {
                let v = base.loadUnaligned(fromByteOffset: i * stride,
                                           as: SIMD4<Float>.self)
                lo4 = simd_min(lo4, v)
                hi4 = simd_max(hi4, v)
            }
            return (SIMD3<Float>(lo4.x, lo4.y, lo4.z),
                    SIMD3<Float>(hi4.x, hi4.y, hi4.z))
        }

        // EXACT PATH — the original 12-byte-copy walk: any stride, any
        // alignment, no over-read past the last vertex. Kept as the fallback
        // rather than deleted, because if a future device vends a layout the
        // fast path's checks reject, the raycast must still be right.
        let ptr = vertices.buffer.contents()
            .advanced(by: vertices.offset)
            .assumingMemoryBound(to: UInt8.self)
        var lo = readVertex(ptr, index: 0, stride: stride)
        var hi = lo
        for i in 1..<count {
            let v = readVertex(ptr, index: i, stride: stride)
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
        // innermost read of the face walk and of `localBounds`' exact
        // fallback path.
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
    public func screenCenterAnchorHit(maxDistM: Float) -> SIMD3<Float>? { nil }
    public enum SurfaceIntent { case upright, ground }
    public func forwardPointAtHorizontalDistance(_ d: Float) -> SIMD3<Float>? { nil }
    public func hit(at screenPoint: CGPoint,
                    intent: SurfaceIntent = .upright) -> SIMD3<Float>? { nil }
    public func rayDirection(at screenPoint: CGPoint) -> SIMD3<Float>? { nil }
    public func projectToScreen(_ world: SIMD3<Float>) -> CGPoint? { nil }
    public var cameraWorldPosition: SIMD3<Float>? { nil }
    public var cameraPitchDeg: Double? { nil }
}

#endif
