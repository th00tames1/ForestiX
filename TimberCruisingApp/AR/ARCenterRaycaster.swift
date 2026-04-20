// Bridge class that lets SwiftUI callers fire a screen-centre raycast
// against the ARView hosted by `ARCameraView`.
//
// Why this exists: HeightScanScreen needs to translate "the cruiser
// tapped Aim Top while the crosshair was on the treetop" into a world
// coordinate, but the view model is deliberately sensor-layer-agnostic
// and the view itself can't reach into a UIViewRepresentable's ARView.
// Pass an ARCenterRaycaster into ARCameraView — it captures a weak
// reference to the underlying ARView on `makeUIView`, and the screen
// calls `screenCenterHit()` when a button fires.

import Foundation

#if canImport(ARKit) && os(iOS)
import ARKit
import RealityKit

@MainActor
public final class ARCenterRaycaster: ObservableObject {
    /// Populated by ARCameraView.makeUIView. Weak so we don't hold the
    /// view alive past its lifecycle.
    public weak var arview: ARView?

    public init() {}

    /// Raycasts from the centre of the current view bounds. Prefers
    /// horizontal estimated planes (ground) so anchor taps land on the
    /// tree base; falls back to any alignment so vertical surfaces
    /// (tree trunks) can still register.
    public func screenCenterHit() -> SIMD3<Float>? {
        guard let view = arview else { return nil }
        let bounds = view.bounds
        guard bounds.width > 1, bounds.height > 1 else { return nil }
        let center = CGPoint(x: bounds.midX, y: bounds.midY)

        // Horizontal first (ground); if nothing, accept any alignment.
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
