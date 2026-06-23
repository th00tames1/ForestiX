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
            do {
                environment = try AppEnvironment.live()
            } catch {
                assertionFailure("Failed to initialise live AppEnvironment: \(error)")
                environment = AppEnvironment.preview()
            }
        }
    }
}

/// Minimal launch placeholder shown while the environment is being built.
/// Uses the same canvas colour as the app so there's no white flash.
private struct LaunchSplash: View {
    var body: some View {
        ZStack {
            ForestixPalette.canvas.ignoresSafeArea()
            VStack(spacing: ForestixSpace.md) {
                Text("FORESTIX")
                    .font(.system(size: 16, weight: .semibold))
                    .tracking(3)
                    .foregroundStyle(ForestixPalette.primary)
                ProgressView()
            }
        }
    }
}

#Preview {
    ContentView()
}
