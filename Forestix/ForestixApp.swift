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
                // default light) and applied in RootView; the splash just
                // takes the light default via the dynamic tokens.
                .tint(ForestixPalette.primary)
        }
    }
}
