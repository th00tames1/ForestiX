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
    }

    public let id: UUID
    public var worldPosition: SIMD3<Float>
    public var shape: Shape
    /// sRGB colour, straight 0…1 channels. Alpha < 1 yields a translucent
    /// material so the camera feed shows through (useful for the DBH
    /// cylinder). Keep saturated for visibility against foliage.
    public var colorRGBA: SIMD4<Float>

    public init(id: UUID = UUID(),
                worldPosition: SIMD3<Float>,
                shape: Shape,
                colorRGBA: SIMD4<Float>) {
        self.id = id
        self.worldPosition = worldPosition
        self.shape = shape
        self.colorRGBA = colorRGBA
    }
}

#if canImport(ARKit) && os(iOS)

import ARKit
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
    }

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

        // Remove anchors that have vanished from the list.
        for staleId in oldIds.subtracting(newIds) {
            if let anchor = coordinator.markerAnchors.removeValue(forKey: staleId) {
                view.scene.removeAnchor(anchor)
            }
            coordinator.markerShapes.removeValue(forKey: staleId)
        }

        // Add new or update existing anchors in place.
        for marker in sceneMarkers {
            if let existing = coordinator.markerAnchors[marker.id] {
                existing.transform.translation = marker.worldPosition
                // Rebuild the model only if the shape actually changed —
                // colour / scale changes share the same mesh template.
                if coordinator.markerShapes[marker.id] != marker.shape {
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
            }
        }
    }

    private static func makeEntity(for marker: ARSceneMarker) -> ModelEntity {
        let mesh: MeshResource = {
            switch marker.shape {
            case .sphere(let r):
                return .generateSphere(radius: r)
            case .cylinder(let r, let h):
                return .generateCylinder(height: h, radius: r)
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
