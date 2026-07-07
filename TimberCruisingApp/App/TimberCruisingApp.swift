// Root navigation entry exposed by the UI library to the hosting iOS app
// (Forestix.xcodeproj). `ForestixApp.swift` keeps the `@main` attribute; this
// view is what its `ContentView` should host.
//
// Mode-selection landing: two large stacked buttons.
//   • Tree Measurement — direct measurement tools (DBH, Height, Crown,
//     Sampling Plots, Distance). No project / cruise design overhead.
//   • Timber Cruising  — full standard cruising workflow that also
//     contains Tree Measurement as a sub-tool.

import SwiftUI

public struct RootView: View {

    @StateObject private var environment: AppEnvironment

    public init(environment: AppEnvironment) {
        _environment = StateObject(wrappedValue: environment)
    }

    public var body: some View {
        ModeSelectionScreen()
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
