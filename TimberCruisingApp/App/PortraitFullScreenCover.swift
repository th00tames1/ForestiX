import SwiftUI

/// A real UIKit hosting controller owns modern iPad covers. A representable
/// embedded in a system SwiftUI cover cannot override that cover's lock policy.
public extension View {
    @ViewBuilder
    func portraitFullScreenCover<Content: View>(isPresented: Binding<Bool>,
        onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping () -> Content) -> some View {
        #if os(iOS)
        if #available(iOS 26.0, *), UIDevice.current.userInterfaceIdiom == .pad {
            let item = Binding<PortraitCoverToken?>(get: { isPresented.wrappedValue ? .init() : nil },
                set: { isPresented.wrappedValue = $0 != nil })
            modifier(PortraitCoverModifier(item: item, onDismiss: onDismiss, cover: { _ in content() }))
        } else { fullScreenCover(isPresented: isPresented, onDismiss: onDismiss, content: content) }
        #else
        sheet(isPresented: isPresented, onDismiss: onDismiss, content: content)
        #endif
    }

    @ViewBuilder
    func portraitFullScreenCover<Item: Identifiable, Content: View>(item: Binding<Item?>,
        onDismiss: (() -> Void)? = nil, @ViewBuilder content: @escaping (Item) -> Content) -> some View {
        #if os(iOS)
        if #available(iOS 26.0, *), UIDevice.current.userInterfaceIdiom == .pad {
            modifier(PortraitCoverModifier(item: item, onDismiss: onDismiss, cover: content))
        } else { fullScreenCover(item: item, onDismiss: onDismiss, content: content) }
        #else
        sheet(item: item, onDismiss: onDismiss, content: content)
        #endif
    }
}

#if os(iOS)
import UIKit

private struct PortraitCoverToken: Identifiable { let id = 0 }

private struct PortraitCoverModifier<Item: Identifiable, Cover: View>: ViewModifier {
    @Binding var item: Item?
    let onDismiss: (() -> Void)?
    let cover: (Item) -> Cover
    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var history: QuickMeasureHistory

    func body(content: Content) -> some View {
        content.background {
            PortraitCoverPresenter(item: $item, onDismiss: onDismiss) { value in
                AnyView(cover(value).id(value.id)
                    .environmentObject(environment).environmentObject(settings).environmentObject(history)
                    .preferredColorScheme(settings.appearance == "dark" ? .dark : .light)
                    .tint(ForestixPalette.primary))
            }.frame(width: 0, height: 0)
        }
    }
}

private struct PortraitCoverPresenter<Item: Identifiable>: UIViewControllerRepresentable {
    @Binding var item: Item?
    let onDismiss: (() -> Void)?
    let cover: (Item) -> AnyView

    func makeCoordinator() -> Coordinator { Coordinator(parent: self) }
    func makeUIViewController(context: Context) -> AnchorController {
        let controller = AnchorController()
        controller.becameVisible = { [weak coordinator = context.coordinator, weak controller] in
            guard let controller else { return }; coordinator?.refresh(from: controller)
        }
        return controller
    }
    func updateUIViewController(_ controller: AnchorController, context: Context) {
        context.coordinator.parent = self
        DispatchQueue.main.async { [weak coordinator = context.coordinator, weak controller] in
            guard let controller else { return }; coordinator?.refresh(from: controller)
        }
    }
    static func dismantleUIViewController(_ controller: AnchorController, coordinator: Coordinator) {
        coordinator.dismiss()
    }

    final class AnchorController: UIViewController {
        var becameVisible: (() -> Void)?
        override func loadView() { view = UIView(); view.isUserInteractionEnabled = false }
        override func viewDidAppear(_ animated: Bool) { super.viewDidAppear(animated); becameVisible?() }
    }

    @MainActor
    final class Coordinator {
        var parent: PortraitCoverPresenter
        private var hosting: PortraitHostingController?
        private var dismissing = false
        init(parent: PortraitCoverPresenter) { self.parent = parent }

        func refresh(from anchor: UIViewController) {
            guard !dismissing else { return }
            guard let item = parent.item else { dismiss(); return }
            if let hosting {
                hosting.updateContent(parent.cover(item))
                return
            }
            guard anchor.viewIfLoaded?.window != nil else { return }
            var owner = anchor
            while let ancestor = owner.parent { owner = ancestor }
            if owner.presentedViewController != nil {
                // A sheet's onDismiss can fire just before UIKit releases its
                // presenter. Retry when that transition completes; do not
                // present over an unrelated modal that is still open.
                if let transition = owner.transitionCoordinator {
                    transition.animate(alongsideTransition: nil) { [weak self, weak anchor] _ in
                        DispatchQueue.main.async {
                            guard let anchor else { return }; self?.refresh(from: anchor)
                        }
                    }
                }
                return
            }
            let controller = PortraitHostingController(content: parent.cover(item))
            controller.modalPresentationStyle = .fullScreen
            controller.onDismiss = { [weak self] in self?.finishedDismissal() }
            hosting = controller
            owner.present(controller, animated: true)
        }
        func dismiss() {
            guard let hosting, !dismissing else { return }
            dismissing = true
            hosting.dismiss(animated: true) { [weak self] in
                self?.finishedDismissal()
                // viewDidDisappear can finish the binding before UIKit has
                // restored the presenter's window. Refresh after the actual
                // dismissal completion, not just that earlier callback.
                DispatchQueue.main.async {
                    PortraitSceneState.shared.refreshOrientationPreference()
                }
            }
        }
        private func finishedDismissal() {
            guard hosting != nil else { return }
            hosting = nil; dismissing = false
            parent.item = nil
            parent.onDismiss?()
        }
    }
}
#endif
