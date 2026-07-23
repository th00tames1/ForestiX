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
            // One `Task.yield()` is NOT enough here: the continuation can
            // resume before Core Animation commits the splash frame, and
            // the main-actor-bound build below then blocks that very first
            // commit — the user stares at the system launch screen for the
            // whole build (field report: ~5 s of blank white). A short real
            // sleep lets the run loop finish a frame or two first; the
            // splash is static, so the main thread stalling AFTER it has
            // painted is invisible.
            try? await Task.sleep(for: .milliseconds(120))
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
/// for the 3 s minimum above). WHITE background with the AFSL lab logo
/// centered — the logo is black line-art, so the splash is always white
/// (it can't sit on the dark canvas), matching the system launch screen
/// (LaunchBackground = white, LaunchLogo). Larger (300 pt) than before so
/// the lab wordmark reads.
private struct LaunchSplash: View {
    var body: some View {
        ZStack {
            Color.white.ignoresSafeArea()
            Image("LabLogo")
                .resizable()
                .scaledToFit()
                .frame(width: 300, height: 300)
        }
        // Force light so the white splash + black logo never invert on a
        // dark-appearance launch.
        .preferredColorScheme(.light)
    }
}

#Preview {
    ContentView()
}
