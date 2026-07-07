import SwiftUI
import UI

struct ContentView: View {
    // Built AFTER the first paint (in `.task`) instead of synchronously in a
    // @StateObject initialiser. `AppEnvironment.live()` loads the Core Data
    // store, seeds species/volume tables on first launch, and sweeps old
    // exports — doing that inline blocked SwiftUI's first frame, so the app
    // showed a blank white window for several seconds (and only painted once
    // a scene event, e.g. app-switching back, forced a redraw). Now a light
    // branded splash paints immediately and the environment is constructed
    // on the next run-loop turn.
    @State private var environment: AppEnvironment?

    var body: some View {
        Group {
            if let environment {
                RootView(environment: environment)
            } else {
                LaunchSplash()
            }
        }
        .task {
            guard environment == nil else { return }
            // Yield once so the splash actually paints before we do the
            // (main-actor-bound) Core Data + seed work.
            await Task.yield()
            // The splash stays up for a MINIMUM of 3 s — long enough to
            // cover the map home's first tile load, so the logo hands off
            // to imagery instead of a canvas-coloured flash of grid.
            let start = ContinuousClock.now
            let built: AppEnvironment
            do {
                built = try AppEnvironment.live()
            } catch {
                assertionFailure("Failed to initialise live AppEnvironment: \(error)")
                built = AppEnvironment.preview()
            }
            let remaining: Duration = .seconds(3) - start.duration(to: .now)
            if remaining > .zero {
                try? await Task.sleep(for: remaining)
            }
            environment = built
        }
    }
}

/// Launch placeholder shown while the environment is being built (and
/// for the 3 s minimum above). Uses the same canvas colour as the app
/// so there's no white flash, with the lab logo centered.
private struct LaunchSplash: View {
    var body: some View {
        ZStack {
            ForestixPalette.canvas.ignoresSafeArea()
            Image("LabLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 180, height: 180)
        }
    }
}

#Preview {
    ContentView()
}
