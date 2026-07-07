// Root navigation entry exposed by the UI library to the hosting iOS app
// (Forestix.xcodeproj). `ForestixApp.swift` keeps the `@main` attribute; this
// view is what its `ContentView` should host.
//
// Map-first home (design/forestix-redesign-v2-maphome.html): the root is
// MapHomeScreen — pins over the basemap, big (+) capture action, Cruise/
// Log side circles. ModeSelectionScreen remains in the tree for direct
// use but is no longer the landing surface.

import SwiftUI

public struct RootView: View {

    @StateObject private var environment: AppEnvironment

    public init(environment: AppEnvironment) {
        _environment = StateObject(wrappedValue: environment)
    }

    public var body: some View {
        MapHomeScreen()
            .environmentObject(environment)
            .environmentObject(environment.settings)
            .environmentObject(environment.quickMeasureHistory)
            // Field High-Contrast ships two appearances sharing one
            // identity; "light" is the default. Driving the scheme here
            // (where AppSettings is observed) flips the trait-dynamic
            // palette AND system sheets/alerts together.
            .preferredColorScheme(environment.settings.appearance == "dark" ? .dark : .light)
            .onOpenURL { url in
                guard let link = URLRouter.parse(url) else { return }
                let history = environment.quickMeasureHistory
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
            }
    }

    private func plotNameFor(link: PendingPlotLink) -> String {
        String(format: "Plot @ %.4f, %.4f", link.lat, link.lon)
    }
}
