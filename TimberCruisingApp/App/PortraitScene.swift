import SwiftUI
import Common

#if os(iOS)
import UIKit
import Sensors

/// One app window. UIKit owns the real hosting controller so the system can
/// query its public orientation-lock preference, including full-screen covers.
@MainActor
public final class PortraitSceneState: ObservableObject {
    public static let shared = PortraitSceneState()
    @Published public private(set) var phase: ScenePhase = .inactive
    @Published public private(set) var pendingURL: URL?
    public private(set) weak var window: UIWindow?
    private var transitions = 0

    public func connect(window: UIWindow) { self.window = window }
    public func setPhase(_ phase: ScenePhase) {
        if self.phase != phase {
            invalidateGeometry()
            self.phase = phase
        }
    }
    public func receive(url: URL) { pendingURL = url }
    public func consume(url: URL) { if pendingURL == url { pendingURL = nil } }
    public func invalidateGeometry() { ARKitSessionManager.shared.invalidateViewport() }
    /// UIKit can drop the outgoing cover's lock before asking the restored
    /// presenter again. Re-query the actual visible owner after dismissal.
    public func refreshOrientationPreference() {
        var controller = window?.rootViewController
        while let presented = controller?.presentedViewController, !presented.isBeingDismissed {
            controller = presented
        }
        (controller as? PortraitHostingController)?.refreshOrientationPreference(retry: true)
    }
    func beginTransition() { transitions += 1; invalidateGeometry() }
    func endTransition() { transitions = max(0, transitions - 1); invalidateGeometry() }

    public func captureStatus() -> PortraitCaptureSafety.Status {
        guard let window, let scene = window.windowScene else { return .inactive }
        let sceneRect = scene.coordinateSpace.convert(scene.coordinateSpace.bounds,
                                                       to: scene.screen.coordinateSpace)
        let screenRect = scene.screen.bounds
        let full = abs(sceneRect.minX - screenRect.minX) < 1
            && abs(sceneRect.minY - screenRect.minY) < 1
            && abs(sceneRect.width - screenRect.width) < 1
            && abs(sceneRect.height - screenRect.height) < 1
        let modern: Bool
        let locked: Bool
        if #available(iOS 26.0, *) {
            modern = true
            locked = scene.effectiveGeometry.isInterfaceOrientationLocked
        } else { modern = false; locked = false }
        return PortraitCaptureSafety.status(isPad: scene.traitCollection.userInterfaceIdiom == .pad,
            requiresSceneLock: modern, isActive: scene.activationState == .foregroundActive,
            isPortrait: scene.interfaceOrientation == .portrait, isFullScreen: full,
            isLocked: locked, isTransitioning: transitions > 0)
    }
}

/// Keeps scenePhase-driven AR pause/resume working in the UIKit scene and covers.
private struct PortraitSceneContent<Content: View>: View {
    @ObservedObject private var scene = PortraitSceneState.shared
    let content: Content
    var body: some View { content.environment(\.scenePhase, scene.phase) }
}

@MainActor
public final class PortraitHostingController: UIHostingController<AnyView> {
    public var onDismiss: (() -> Void)?
    private var requestedPortrait = false

    public init<Content: View>(content: Content) {
        super.init(rootView: AnyView(PortraitSceneContent(content: content)))
    }
    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("Use init(content:)") }
    public func updateContent<Content: View>(_ content: Content) {
        rootView = AnyView(PortraitSceneContent(content: content))
    }
    public override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        // Modern iPad windowing must not enter the fixed-aspect compatibility
        // mode. Request portrait, then lock the actual scene instead.
        if #available(iOS 26.0, *), traitCollection.userInterfaceIdiom == .pad { return .all }
        return .portrait
    }
    public override var preferredInterfaceOrientationForPresentation: UIInterfaceOrientation { .portrait }
    public override var shouldAutorotate: Bool {
        if #available(iOS 26.0, *), traitCollection.userInterfaceIdiom == .pad { return true }
        return false
    }
    @available(iOS 26.0, *)
    public override var childForInterfaceOrientationLock: UIViewController? { nil }
    @available(iOS 26.0, *)
    public override var prefersInterfaceOrientationLocked: Bool {
        viewIfLoaded?.window?.windowScene?.interfaceOrientation == .portrait
    }
    public override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        refreshOrientationPreference(retry: true)
    }
    public override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        if isBeingDismissed || presentingViewController == nil { onDismiss?() }
    }
    public override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        refreshOrientationPreference()
    }
    public override func viewWillTransition(to size: CGSize,
                                            with coordinator: UIViewControllerTransitionCoordinator) {
        PortraitSceneState.shared.beginTransition()
        super.viewWillTransition(to: size, with: coordinator)
        coordinator.animate(alongsideTransition: nil) { [weak self] _ in
            PortraitSceneState.shared.endTransition()
            self?.requestedPortrait = false
            self?.refreshOrientationPreference()
        }
    }
    public func refreshOrientationPreference(retry: Bool = false) {
        if retry { requestedPortrait = false }
        guard let scene = viewIfLoaded?.window?.windowScene else { return }
        #if DEBUG
        if retry {
            print("ForestiX portrait refresh: orientation=\(scene.interfaceOrientation.rawValue) activation=\(scene.activationState.rawValue)")
        }
        #endif
        if scene.interfaceOrientation == .portrait {
            requestedPortrait = false
            if #available(iOS 26.0, *) { setNeedsUpdateOfPrefersInterfaceOrientationLocked() }
        } else if !requestedPortrait, scene.activationState == .foregroundActive {
            requestedPortrait = true
            setNeedsUpdateOfSupportedInterfaceOrientations()
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait)) { error in
                #if DEBUG
                print("ForestiX portrait request denied: \(error)")
                #endif
                // A denied request leaves measurement blocked; retry on the
                // next activation/geometry change, not on every render frame.
            }
        }
    }
}
#endif
