//
//  ForestixApp.swift
//  Forestix
//
//  Created by HC on 4/17/26.
//

import SwiftUI
import UI

@main
struct ForestixApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                // Appearance is user-selected (AppSettings.appearance,
                // default light) and applied in RootView; the splash
                // applies the same saved value itself (LaunchSplash).
                .tint(ForestixPalette.primary)
        }
    }
}
