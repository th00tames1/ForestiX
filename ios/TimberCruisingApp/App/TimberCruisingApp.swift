// Root navigation entry exposed by the UI library to the hosting iOS app
// (Forestix.xcodeproj). `ForestixApp.swift` keeps the `@main` attribute; this
// view is what its `ContentView` should host.
//
// The library is NOT an executable, so no `@App`/`@main` lives here.
//
// Phase 7: unified mode. Forestix used to ship two parallel UIs —
// "Quick Measure" for one-off scans and an "Advanced" project /
// plot / cruise workflow gated behind a Settings toggle. Cruisers
// pointed out the obvious: just make the project workflow as
// approachable as Quick Measure, and there's no need for two homes.
// QuickMeasureHomeScreen now serves as THE home, with projects
// surfaced as a supporting spoke. The `advancedMode` property is
// retained for back-compat but no longer drives view selection.

import SwiftUI

public struct RootView: View {

    @StateObject private var environment: AppEnvironment

    public init(environment: AppEnvironment) {
        _environment = StateObject(wrappedValue: environment)
    }

    public var body: some View {
        QuickMeasureHomeScreen()
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
        }
    }

    private func plotNameFor(link: PendingPlotLink) -> String {
        String(format: "Plot @ %.4f, %.4f", link.lat, link.lon)
    }
}
