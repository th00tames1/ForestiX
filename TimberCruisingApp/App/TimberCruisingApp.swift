// Root navigation entry exposed by the UI library to the hosting iOS app
// (Forestix.xcodeproj). `ForestixApp.swift` keeps the `@main` attribute; this
// view is what its `ContentView` should host.
//
// The library is NOT an executable, so no `@App`/`@main` lives here.

import SwiftUI

public struct RootView: View {

    @StateObject private var environment: AppEnvironment

    public init(environment: AppEnvironment) {
        _environment = StateObject(wrappedValue: environment)
    }

    public var body: some View {
        HomeScreen()
            .environmentObject(environment)
            .environmentObject(environment.settings)
    }
}
