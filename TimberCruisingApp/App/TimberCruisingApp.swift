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
        .onOpenURL { url in
            // External `forestix://plot?…` deep-links create or
            // activate a Quick Measure plot at the supplied
            // coordinates. The cruiser hands the resulting plot to
            // their next scan via the active-plot mechanism.
            guard let link = URLRouter.parse(url) else { return }
            let history = environment.quickMeasureHistory
            // Try to match an existing plot by name + coordinates;
            // if none, create a new one.
            if let name = link.name,
               let existing = history.plots.first(where: { $0.name == name }) {
                history.setActivePlot(id: existing.id)
            } else {
                let plot = history.createPlot(
                    name: link.name ?? plotNameFor(link: link),
                    unitName: link.unit ?? "",
                    acres: link.acres,
                    typeRaw: "fixed")
                history.setActivePlot(id: plot.id)
            }
            // Force home view ON so the cruiser sees the just-set
            // active plot reflected in the masthead.
            settings.advancedMode = false
        }
    }

    private func plotNameFor(link: PendingPlotLink) -> String {
        String(format: "Plot @ %.4f, %.4f", link.lat, link.lon)
    }
}
