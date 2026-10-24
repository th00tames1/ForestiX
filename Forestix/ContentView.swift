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
    @State private var startupError: String?
    @State private var startupAttempt = 0
    #if DEBUG && targetEnvironment(simulator)
    @State private var injectedStartupFailure = false
    #endif

    var body: some View {
        Group {
            if let environment {
                RootView(environment: environment)
            } else if let startupError {
                VStack(spacing: 18) {
                    Image(systemName: "externaldrive.badge.exclamationmark").font(.largeTitle)
                    Text("Unable to open saved data").font(.headline)
                    Text("Measurement is unavailable until storage opens. No temporary store has been substituted. Do not delete or reinstall the app to troubleshoot this error.")
                        .multilineTextAlignment(.center)
                    Text(startupError).font(.caption).foregroundStyle(.secondary)
                    Button("Retry") { self.startupError = nil; startupAttempt += 1 }
                        .buttonStyle(.borderedProminent).accessibilityIdentifier("startup.retry")
                }.padding(32)
                    .accessibilityElement(children: .contain)
                    .accessibilityIdentifier("startup.storageFailure")
            } else {
                LaunchSplash()
            }
        }
        .task(id: startupAttempt) {
            guard environment == nil else { return }
            // One `Task.yield()` is NOT enough here: the continuation can
            // resume before Core Animation commits the splash frame, and
            // the main-actor-bound build below then blocks that very first
            // commit — the user stares at the system launch screen for the
            // whole build (field report: ~5 s of blank white). A short real
            // sleep lets the run loop finish a frame or two first; the
            // splash is static, so the main thread stalling AFTER it has
            // painted is invisible.
            do { try await Task.sleep(for: .milliseconds(120)) } catch { return }
            // The splash stays up for a MINIMUM of 3 s — long enough to
            // cover the map home's first tile load, so the logo hands off
            // to imagery instead of a canvas-coloured flash of grid.
            let start = ContinuousClock.now
            let built: AppEnvironment
            do {
                #if DEBUG && targetEnvironment(simulator)
                // Fault injection exists only in simulator Debug builds. It
                // never changes a physical device's files or release startup.
                let fault = ProcessInfo.processInfo.environment["FORESTIX_UI_TEST_STARTUP_FAILURE"]
                if fault == "always" || (fault == "once" && !injectedStartupFailure) {
                    injectedStartupFailure = true
                    throw NSError(domain: "ForestiX.SyntheticPreflight",code: 1,
                        userInfo: [NSLocalizedDescriptionKey:"Synthetic storage-opening failure"])
                }
                #endif
                built = try AppEnvironment.live()
            } catch {
                // Never make field measurements appear saved in a preview /
                // in-memory environment after a real persistent-store error.
                startupError = error.localizedDescription
                return
            }
            let remaining: Duration = .seconds(3) - start.duration(to: .now)
            if remaining > .zero {
                do { try await Task.sleep(for: remaining) } catch { return }
            }
            guard !Task.isCancelled else { return }
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
