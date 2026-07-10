// Phase 7.3 — boundary ring rendering.
//
// The Phase 3 `ARBoundaryViewModel` publishes `ringVertices` (72
// slope-corrected world-space points) but the Phase 3 screen was a
// black placeholder + chrome — the ring itself never made it onto
// the RealityKit scene. Spec §7.8 calls out the ring visualisation as
// the user-facing payoff of the boundary feature; without it a
// cruiser can't tell whether a borderline tree is "in" or "out".
//
// This view is a specialisation of `ARCameraView` that:
//   1. renders the live camera feed (same as ARCameraView),
//   2. subscribes to the ViewModel's `ringVertices` + `centerWorld`,
//   3. rebuilds a `ModelEntity` via `PlotBoundaryRenderer.makeRingEntity`
//      and re-anchors it in the AR scene whenever the data changes.

import SwiftUI
import AR

#if canImport(ARKit) && canImport(RealityKit) && os(iOS)

import ARKit
import RealityKit
import Combine
import simd

public struct ARBoundarySceneView: UIViewRepresentable {

    @ObservedObject public var viewModel: ARBoundaryViewModel
    public var style: PlotBoundaryRenderer.RingStyle

    public init(viewModel: ARBoundaryViewModel,
                style: PlotBoundaryRenderer.RingStyle = PlotBoundaryRenderer.RingStyle()) {
        self.viewModel = viewModel
        self.style = style
    }

    // MARK: - Coordinator owns the live ARView + any anchors it has
    // added. Lets SwiftUI re-render without leaking entities.

    public final class Coordinator {
        weak var arView: ARView?
        var currentAnchor: AnchorEntity?
        var lastVertexCount: Int = 0
        var lastCenter: SIMD3<Float>? = nil
    }

    public func makeCoordinator() -> Coordinator { Coordinator() }

    public func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero,
                          cameraMode: .ar,
                          automaticallyConfigureSession: false)
        view.session = viewModel.session.session
        view.renderOptions.insert(.disableMotionBlur)
        context.coordinator.arView = view
        // First-frame sync in case `onAppear` already populated the
        // vertices before this view made it onto the tree.
        syncRing(into: view, coordinator: context.coordinator)
        return view
    }

    public func updateUIView(_ view: ARView, context: Context) {
        if view.session !== viewModel.session.session {
            view.session = viewModel.session.session
        }
        syncRing(into: view, coordinator: context.coordinator)
    }

    // MARK: - Ring sync

    private func syncRing(into view: ARView, coordinator: Coordinator) {
        let vertices = viewModel.ringVertices
        let center = viewModel.centerWorld

        // No center → make sure no leftover ring is showing.
        guard let center = center, !vertices.isEmpty else {
            detachRing(coordinator: coordinator)
            return
        }

        // Skip work when nothing changed — an updateUIView gets called
        // on every state mutation from SwiftUI.
        if vertices.count == coordinator.lastVertexCount,
           let prevCenter = coordinator.lastCenter,
           simd_distance(prevCenter, center) < 1e-4 {
            return
        }

        detachRing(coordinator: coordinator)

        // Express vertices in an anchor-local frame so subsequent
        // slope / drift updates don't shift the anchor itself.
        let localVertices = vertices.map { $0 - center }
        let ring = PlotBoundaryRenderer.makeRingEntity(
            vertices: localVertices, style: style)

        let anchor = AnchorEntity(world: center)
        anchor.addChild(ring)
        view.scene.addAnchor(anchor)

        coordinator.currentAnchor = anchor
        coordinator.lastVertexCount = vertices.count
        coordinator.lastCenter = center
    }

    private func detachRing(coordinator: Coordinator) {
        if let anchor = coordinator.currentAnchor {
            coordinator.arView?.scene.removeAnchor(anchor)
        }
        coordinator.currentAnchor = nil
        coordinator.lastVertexCount = 0
        coordinator.lastCenter = nil
    }
}

#else

// macOS test host: reuse the black-camera fallback so snapshot tests
// keep rendering deterministically.
public struct ARBoundarySceneView: View {
    public init(viewModel: Any,
                style: PlotBoundaryRenderer.RingStyle = PlotBoundaryRenderer.RingStyle()) {}
    public var body: some View { Color.black }
}

#endif
