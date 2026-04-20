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

import SwiftUI

#if canImport(ARKit) && os(iOS)

import ARKit
import RealityKit
import Sensors

public struct ARCameraView: UIViewRepresentable {

    public let session: ARSession
    public var debugMeshOverlay: Bool

    public init(session: ARSession, debugMeshOverlay: Bool = false) {
        self.session = session
        self.debugMeshOverlay = debugMeshOverlay
    }

    public func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero,
                          cameraMode: .ar,
                          automaticallyConfigureSession: false)
        // Attach to the externally-owned session — DO NOT call run() here;
        // the ARKitSessionManager owns the session lifecycle.
        view.session = session
        if debugMeshOverlay {
            view.debugOptions.insert(.showSceneUnderstanding)
        }
        view.renderOptions.insert(.disableMotionBlur)
        // Camera background fills behind any SwiftUI overlay we put
        // above this view — no need to set background colour.
        return view
    }

    public func updateUIView(_ view: ARView, context: Context) {
        if view.session !== session {
            view.session = session
        }
        if debugMeshOverlay {
            view.debugOptions.insert(.showSceneUnderstanding)
        } else {
            view.debugOptions.remove(.showSceneUnderstanding)
        }
    }
}

#else

// Non-iOS hosts (macOS test runner) keep building. The placeholder is
// the same black rectangle DBHScanScreen used to show on its own.
public struct ARCameraView: View {
    public init(session: Any, debugMeshOverlay: Bool = false) {}
    public var body: some View { Color.black }
}

#endif

// MARK: - Convenience initialiser bridged to ARKitSessionManager

#if canImport(ARKit) && os(iOS)
extension ARCameraView {
    public init(manager: ARKitSessionManager, debugMeshOverlay: Bool = false) {
        self.init(session: manager.session,
                  debugMeshOverlay: debugMeshOverlay)
    }
}
#else
extension ARCameraView {
    public init(manager: Any, debugMeshOverlay: Bool = false) {
        self.init(session: manager, debugMeshOverlay: debugMeshOverlay)
    }
}
#endif
