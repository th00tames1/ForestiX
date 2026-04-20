// Root navigation entry exposed by the UI library to the hosting iOS app
// (Forestix.xcodeproj). `ForestixApp.swift` keeps the `@main` attribute; this
// view is what its `ContentView` should host.
//
// The library is NOT an executable, so no `@App`/`@main` lives here.
//
// Routing: when `AppSettings.advancedMode == false` (the default) the
// app opens into QuickMeasureHomeScreen — direct DBH / Height scans
// without project setup. Flipping the toggle on in Settings swaps to
// the full HomeScreen (project list → plots → cruise workflow).

import SwiftUI

public struct RootView: View {

    @StateObject private var environment: AppEnvironment
    @ObservedObject private var settings: AppSettings

    public init(environment: AppEnvironment) {
        _environment = StateObject(wrappedValue: environment)
        _settings = ObservedObject(wrappedValue: environment.settings)
    }

    public var body: some View {
        Group {
            if settings.advancedMode {
                HomeScreen()
            } else {
                QuickMeasureHomeScreen()
            }
        }
        .environmentObject(environment)
        .environmentObject(environment.settings)
        .environmentObject(environment.quickMeasureHistory)
    }
}
