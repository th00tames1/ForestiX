// Phase 7.2 hardening — SwiftUI wrapper around RealityKit's ARView.
//
// The Phase 2 / 3 / 5 audit found that DBHScanScreen, HeightScanScreen,
// and ARBoundaryScreen were rendering only their overlay chrome over a
// black `Color.black.ignoresSafeArea()` placeholder — the actual camera
// feed never made it onto the screen. A real cruiser pointing the phone
// at a tree would just see a black rectangle with a guide line floating
// over it; nothing to align against.
//
// `ARCameraView` fixes that by attaching an `ARView` to the *same*
// `ARSession` instance the corresponding `ARKitSessionManager` is
// already running. The session's camera background renders through
// untouched, so the overlay chrome (guide line / crosshair / banner)
// floats over a live picture of the tree.
//
// World-anchored 3D markers
// -------------------------
// Callers can pass an `[ARSceneMarker]` array describing spheres /
// cylinders to render at specific world positions. Markers diff by id —
// the view keeps them attached to world-space anchors so they stay put
// when the cruiser moves the phone, even if the anchor briefly leaves
// the frame. Used by DBHScanScreen to overlay the fitted trunk cylinder
// and by HeightScanScreen to mark the anchor / top / base world points.

import SwiftUI

// MARK: - Public marker surface (cross-platform)

/// Declarative description of a 3D marker rendered inside the AR scene.
/// The concrete RealityKit entity is owned by `ARCameraView`'s
/// coordinator; callers just flip this value and SwiftUI diffs it.
public struct ARSceneMarker: Identifiable, Equatable {

    public enum Shape: Equatable {
        /// Filled sphere — used for anchor / top / base point pins.
        case sphere(radiusM: Float)
        /// Vertical cylinder (Y-up) — used for the fitted DBH trunk.
        /// `heightM` is the total visual height; the cylinder is centred
        /// on `worldPosition`.
        case cylinder(radiusM: Float, heightM: Float)
        /// Flat horizontal ring outline (annulus in the XZ plane, Y-up
        /// normal). Used for the sampling-plot boundary so we draw just
        /// the rim — a filled translucent disk that wide (up to 30 m
        /// radius) created huge transparent overdraw and tanked the frame
        /// rate. `radiusM` is the centre-line radius; `thicknessM` is the
        /// rim band width.
        case ring(radiusM: Float, thicknessM: Float)
    }

    public let id: UUID
    public var worldPosition: SIMD3<Float>
    public var shape: Shape
    /// sRGB colour, straight 0…1 channels. Alpha < 1 yields a translucent
    /// material so the camera feed shows through (useful for the DBH
    /// cylinder). Keep saturated for visibility against foliage.
    public var colorRGBA: SIMD4<Float>
    /// When true the marker grows with camera distance so its APPARENT
    /// (on-screen) size stays readable — a 8 cm sphere is fine at 3 m but
    /// nearly invisible from 15 m across a height walk-off. Natural size
    /// within the reference distance; linear growth beyond it, capped.
    public var scalesWithDistance: Bool

    public init(id: UUID = UUID(),
                worldPosition: SIMD3<Float>,
                shape: Shape,
                colorRGBA: SIMD4<Float>,
                scalesWithDistance: Bool = false) {
        self.id = id
        self.worldPosition = worldPosition
        self.shape = shape
        self.colorRGBA = colorRGBA
        self.scalesWithDistance = scalesWithDistance
    }
}

#if canImport(ARKit) && os(iOS)

import ARKit
import Combine
import RealityKit
import Sensors

public struct ARCameraView: UIViewRepresentable {

    public let session: ARSession
    public var debugMeshOverlay: Bool
    public var sceneMarkers: [ARSceneMarker]
    /// Optional raycaster that gets bound to the underlying ARView on
    /// creation so callers can fire screen-centre raycasts (Height
    /// scan uses this for Anchor / Aim Top / Aim Base).
    public var raycaster: ARCenterRaycaster?

    public init(session: ARSession,
                debugMeshOverlay: Bool = false,
                sceneMarkers: [ARSceneMarker] = [],
                raycaster: ARCenterRaycaster? = nil) {
        self.session = session
        self.debugMeshOverlay = debugMeshOverlay
        self.sceneMarkers = sceneMarkers
        self.raycaster = raycaster
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public final class Coordinator {
        /// Map marker.id → the AnchorEntity representing it in the scene.
        /// Used to diff on `updateUIView` so we don't tear down and
        /// rebuild every marker on every frame.
        var markerAnchors: [UUID: AnchorEntity] = [:]
        /// Cached shape per marker so we only rebuild the mesh when the
        /// shape itself changes (not when just the position moved).
        var markerShapes: [UUID: ARSceneMarker.Shape] = [:]
        /// World position the AnchorEntity was anchored at on creation.
        /// Needed because `AnchorEntity(world:)` pins the entity to that
        /// world point — mutating `transform.translation` afterwards
        /// adds an OFFSET relative to the anchored origin instead of
        /// repositioning, which doubled the rendered position on every
        /// rebuild and made previously-placed markers visibly jump.
        var markerPositions: [UUID: SIMD3<Float>] = [:]
        /// Ids of markers that keep a constant APPARENT size — their child
        /// entity is rescaled every frame from the camera distance.
        var scalingIds: Set<UUID> = []
        /// Per-frame scene-update subscription driving the distance scaling.
        var updateSub: (any Cancellable)?
    }

    /// Distance-compensated marker scaling: natural size out to this range…
    private static let scaleReferenceM: Float = 3.0
    /// …then linear growth with distance, capped so a marker seen from
    /// across a stand doesn't balloon absurdly.
    private static let scaleMax: Float = 6.0

    public func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero,
                          cameraMode: .ar,
                          automaticallyConfigureSession: false)
        // Attach to the externally-owned session — DO NOT call run() here;
        // the ARKitSessionManager owns the session lifecycle.
        view.session = session
        applyDebugOptions(to: view)
        view.renderOptions.insert(.disableMotionBlur)
        // Camera background fills behind any SwiftUI overlay we put
        // above this view — no need to set background colour.
        raycaster?.arview = view
        // Per-frame distance scaling for markers flagged
        // `scalesWithDistance` — keeps their apparent size readable when
        // the cruiser walks away (height walk-off can be 15–20 m out).
        let coordinator = context.coordinator
        coordinator.updateSub = view.scene.subscribe(to: SceneEvents.Update.self) { [weak view, weak coordinator] _ in
            guard let view, let coordinator, !coordinator.scalingIds.isEmpty else { return }
            let cam = view.cameraTransform.translation
            for id in coordinator.scalingIds {
                guard let anchor = coordinator.markerAnchors[id],
                      let pos = coordinator.markerPositions[id] else { continue }
                let d = simd_distance(cam, pos)
                let factor = min(Self.scaleMax, max(1, d / Self.scaleReferenceM))
                for child in anchor.children {
                    child.scale = SIMD3<Float>(repeating: factor)
                }
            }
        }
        return view
    }

    public func updateUIView(_ view: ARView, context: Context) {
        if view.session !== session {
            view.session = session
        }
        applyDebugOptions(to: view)
        // Rebind on every update in case the binding instance changed
        // (SwiftUI can recreate helpers across view updates).
        if raycaster?.arview !== view {
            raycaster?.arview = view
        }
        applyMarkers(to: view, coordinator: context.coordinator)
    }

    private func applyDebugOptions(to view: ARView) {
        if debugMeshOverlay {
            view.debugOptions.insert(.showSceneUnderstanding)
        } else {
            view.debugOptions.remove(.showSceneUnderstanding)
        }
        // Feature points overlay was removed — cruisers found it noisy
        // and the mesh alone gives the same "is the scene tracked?"
        // signal. ARView ships with featurePoints off by default, so
        // explicit clearing is no longer needed.
    }

    // MARK: - Marker diffing

    private func applyMarkers(to view: ARView, coordinator: Coordinator) {
        let newIds = Set(sceneMarkers.map(\.id))
        let oldIds = Set(coordinator.markerAnchors.keys)
        let newScalingIds = Set(
            sceneMarkers.filter(\.scalesWithDistance).map(\.id))
        // A marker that stops scaling (scalesWithDistance true → false while
        // keeping its id/position/shape) would otherwise stay frozen at the
        // last distance factor, since the per-frame updater only writes scale
        // for ids in `scalingIds`. Reset those entities back to natural size.
        for id in coordinator.scalingIds.subtracting(newScalingIds) {
            coordinator.markerAnchors[id]?.children.forEach { $0.scale = .one }
        }
        coordinator.scalingIds = newScalingIds

        // Remove anchors that have vanished from the list.
        for staleId in oldIds.subtracting(newIds) {
            if let anchor = coordinator.markerAnchors.removeValue(forKey: staleId) {
                view.scene.removeAnchor(anchor)
            }
            coordinator.markerShapes.removeValue(forKey: staleId)
            coordinator.markerPositions.removeValue(forKey: staleId)
        }

        // Add new, re-anchor moved, or refresh shape on existing anchors.
        // We re-anchor (remove + re-add) when the world position changes
        // because `AnchorEntity(world:)` pins the entity at the supplied
        // world point and `transform.translation` is then a *child-space*
        // offset relative to that anchored origin — assigning the world
        // position back into translation doubles the rendered location.
        for marker in sceneMarkers {
            if let existing = coordinator.markerAnchors[marker.id] {
                let storedPosition = coordinator.markerPositions[marker.id]
                // Dead-band: ignore sub-centimetre jitter so the live DBH
                // preview cylinder (whose centre wobbles a few mm at ~10 Hz)
                // doesn't tear down its anchor and regenerate its mesh every
                // frame — a real per-frame cost that showed up as scan-screen
                // churn. Only a ≥1 cm move re-anchors.
                let moved = storedPosition.map {
                    simd_distance($0, marker.worldPosition) >= 0.01
                } ?? true
                let shapeChanged = coordinator.markerShapes[marker.id] != marker.shape
                if moved {
                    // Reuse the existing child entity when the shape is
                    // unchanged so we move the mesh to the new anchor instead
                    // of rebuilding it (generateCylinder/Sphere is the costly
                    // part). AnchorEntity(world:) pins the entity, so the only
                    // way to reposition is to re-anchor.
                    let child: Entity
                    if !shapeChanged, let existingChild = existing.children.first {
                        existingChild.removeFromParent()
                        child = existingChild
                    } else {
                        child = Self.makeEntity(for: marker)
                    }
                    view.scene.removeAnchor(existing)
                    let anchor = AnchorEntity(world: marker.worldPosition)
                    anchor.addChild(child)
                    view.scene.addAnchor(anchor)
                    coordinator.markerAnchors[marker.id] = anchor
                    coordinator.markerShapes[marker.id] = marker.shape
                    coordinator.markerPositions[marker.id] = marker.worldPosition
                } else if shapeChanged {
                    for child in existing.children {
                        child.removeFromParent()
                    }
                    existing.addChild(Self.makeEntity(for: marker))
                    coordinator.markerShapes[marker.id] = marker.shape
                }
            } else {
                let anchor = AnchorEntity(world: marker.worldPosition)
                anchor.addChild(Self.makeEntity(for: marker))
                view.scene.addAnchor(anchor)
                coordinator.markerAnchors[marker.id] = anchor
                coordinator.markerShapes[marker.id] = marker.shape
                coordinator.markerPositions[marker.id] = marker.worldPosition
            }
        }
    }

    private static func makeEntity(for marker: ARSceneMarker) -> ModelEntity {
        // Rings render as flat unlit geometry (no normals needed, visible
        // from both sides). Spheres / cylinders keep the lit-vs-unlit
        // choice based on opacity.
        if case .ring(let r, let thickness) = marker.shape {
            let uiColor = UIColor(
                red:   CGFloat(marker.colorRGBA.x),
                green: CGFloat(marker.colorRGBA.y),
                blue:  CGFloat(marker.colorRGBA.z),
                alpha: CGFloat(marker.colorRGBA.w))
            let mesh = generateRing(radius: r, thickness: thickness, segments: 96)
            return ModelEntity(mesh: mesh, materials: [UnlitMaterial(color: uiColor)])
        }

        let mesh: MeshResource = {
            switch marker.shape {
            case .sphere(let r):
                return .generateSphere(radius: r)
            case .cylinder(let r, let h):
                return .generateCylinder(height: h, radius: r)
            case .ring:
                return .generateSphere(radius: 0.01) // unreachable (handled above)
            }
        }()
        let uiColor = UIColor(
            red:   CGFloat(marker.colorRGBA.x),
            green: CGFloat(marker.colorRGBA.y),
            blue:  CGFloat(marker.colorRGBA.z),
            alpha: CGFloat(marker.colorRGBA.w))

        // Opaque markers (alpha ≥ 1) get SimpleMaterial so they pick up
        // environment lighting and look like actual 3D balls. Translucent
        // markers (the DBH trunk cylinder at 0.45 alpha) need blending
        // that SimpleMaterial doesn't do — fall back to UnlitMaterial,
        // which renders the alpha correctly at the cost of flat shading.
        let isOpaque = marker.colorRGBA.w >= 0.999
        let materials: [any RealityKit.Material] = isOpaque
            ? [SimpleMaterial(color: uiColor,
                              roughness: 0.5,
                              isMetallic: false)]
            : [UnlitMaterial(color: uiColor)]
        return ModelEntity(mesh: mesh, materials: materials)
    }

    /// Builds a flat, double-sided annulus (ring) mesh in the XZ plane,
    /// centred at the entity origin. Cheap (≈ 4·segments triangles) so a
    /// 30 m boundary costs nothing versus a filled translucent disk.
    private static func generateRing(radius: Float,
                                     thickness: Float,
                                     segments: Int) -> MeshResource {
        let half = max(0.01, thickness / 2)
        let inner = max(0.001, radius - half)
        let outer = radius + half
        var positions: [SIMD3<Float>] = []
        positions.reserveCapacity(segments * 2)
        for i in 0..<segments {
            let a = Float(i) / Float(segments) * 2 * .pi
            let ca = cos(a), sa = sin(a)
            positions.append(SIMD3<Float>(inner * ca, 0, inner * sa))
            positions.append(SIMD3<Float>(outer * ca, 0, outer * sa))
        }
        var indices: [UInt32] = []
        indices.reserveCapacity(segments * 12)
        for i in 0..<segments {
            let i0 = UInt32(i * 2)
            let i1 = UInt32(i * 2 + 1)
            let next = (i + 1) % segments
            let i2 = UInt32(next * 2)
            let i3 = UInt32(next * 2 + 1)
            // Front-facing pair…
            indices.append(contentsOf: [i0, i1, i2, i2, i1, i3])
            // …plus reversed winding so the rim is visible from below too.
            indices.append(contentsOf: [i0, i2, i1, i2, i3, i1])
        }
        var desc = MeshDescriptor(name: "ring")
        desc.positions = MeshBuffers.Positions(positions)
        desc.primitives = .triangles(indices)
        return (try? MeshResource.generate(from: [desc])) ?? .generateSphere(radius: 0.01)
    }
}

#else

// Non-iOS hosts (macOS test runner) keep building. The placeholder is
// the same black rectangle DBHScanScreen used to show on its own.
public struct ARCameraView: View {
    public init(session: Any,
                debugMeshOverlay: Bool = false,
                sceneMarkers: [ARSceneMarker] = [],
                raycaster: ARCenterRaycaster? = nil) {}
    public var body: some View { Color.black }
}

#endif

// MARK: - Convenience initialiser bridged to ARKitSessionManager

#if canImport(ARKit) && os(iOS)
extension ARCameraView {
    public init(manager: ARKitSessionManager,
                debugMeshOverlay: Bool = false,
                sceneMarkers: [ARSceneMarker] = [],
                raycaster: ARCenterRaycaster? = nil) {
        self.init(session: manager.session,
                  debugMeshOverlay: debugMeshOverlay,
                  sceneMarkers: sceneMarkers,
                  raycaster: raycaster)
    }
}
#else
extension ARCameraView {
    public init(manager: Any,
                debugMeshOverlay: Bool = false,
                sceneMarkers: [ARSceneMarker] = [],
                raycaster: ARCenterRaycaster? = nil) {
        self.init(session: manager,
                  debugMeshOverlay: debugMeshOverlay,
                  sceneMarkers: sceneMarkers,
                  raycaster: raycaster)
    }
}
#endif
