//
//  ForestixApp.swift
//  Forestix
//
//  Created by HC on 4/17/26.
//

import SwiftUI
import UI
import UIKit

@main
@MainActor
final class ForestixApp: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession,
                     options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: "Forestix", sessionRole: session.role)
        configuration.sceneClass = UIWindowScene.self
        configuration.delegateClass = ForestixSceneDelegate.self
        return configuration
    }

    func application(_ application: UIApplication,
                     supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        if #available(iOS 26.0, *),
           (window?.traitCollection.userInterfaceIdiom ?? UIDevice.current.userInterfaceIdiom) == .pad {
            return .all
        }
        return .portrait
    }
}

@MainActor
final class ForestixSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(_ scene: UIScene, willConnectTo session: UISceneSession,
               options connectionOptions: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        let window = UIWindow(windowScene: scene)
        window.rootViewController = PortraitHostingController(
            content: ContentView().tint(ForestixPalette.primary))
        self.window = window
        PortraitSceneState.shared.connect(window: window)
        window.makeKeyAndVisible()
        for context in connectionOptions.urlContexts { PortraitSceneState.shared.receive(url: context.url) }
    }
    func sceneDidBecomeActive(_ scene: UIScene) {
        PortraitSceneState.shared.setPhase(.active)
        refreshOrientation()
    }
    func sceneWillResignActive(_ scene: UIScene) { PortraitSceneState.shared.setPhase(.inactive) }
    func sceneDidEnterBackground(_ scene: UIScene) { PortraitSceneState.shared.setPhase(.background) }
    func scene(_ scene: UIScene, openURLContexts contexts: Set<UIOpenURLContext>) {
        for context in contexts { PortraitSceneState.shared.receive(url: context.url) }
    }
    func windowScene(_ windowScene: UIWindowScene, didUpdate previousCoordinateSpace: UICoordinateSpace,
                     interfaceOrientation previousInterfaceOrientation: UIInterfaceOrientation,
                     traitCollection previousTraitCollection: UITraitCollection) {
        PortraitSceneState.shared.invalidateGeometry()
        refreshOrientation()
    }
    @available(iOS 26.0, *)
    func windowScene(_ windowScene: UIWindowScene, didUpdateEffectiveGeometry previousGeometry: UIWindowScene.Geometry) {
        PortraitSceneState.shared.invalidateGeometry()
        refreshOrientation()
    }
    private func refreshOrientation() {
        var controller = window?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        (controller as? PortraitHostingController)?.refreshOrientationPreference(retry: true)
    }
}
