import SwiftUI
import UI

struct ContentView: View {
    @StateObject private var environment: AppEnvironment = {
        do {
            return try AppEnvironment.live()
        } catch {
            assertionFailure("Failed to initialise live AppEnvironment: \(error)")
            return AppEnvironment.preview()
        }
    }()

    var body: some View {
        RootView(environment: environment)
    }
}

#Preview {
    ContentView()
}
