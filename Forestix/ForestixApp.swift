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
                // Direction B is a dark-only outdoor instrument — force
                // dark so system sheets/alerts/pickers match the canvas.
                .preferredColorScheme(.dark)
                .tint(ForestixPalette.primary)
        }
    }
}
