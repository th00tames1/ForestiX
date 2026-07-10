// Spec §8 (Common/HapticFeedback.swift) + §2.3 design principle 5
// ("Haptic on all state transitions"). Phase 7 adds a fourth distinct
// pattern for plot-close so a cruiser wearing gloves can distinguish:
//
//   • arrival     — single heavy thump (navigation within 5 m)
//   • success     — single success notification (scan saved, etc.)
//   • plotClose   — medium impact followed ~250 ms later by a success
//                   notification (a two-beat "ta-daa" unique to plot end)
//   • failure     — single error notification (red-tier / invalid input)
//
// iOS-only implementation; on non-iOS platforms (e.g. macOS test host)
// the calls are no-ops so `swift test` runs without a haptic engine.

import Foundation

#if canImport(UIKit)
import UIKit
#endif

public enum HapticFeedback {

    public enum Pattern: Sendable {
        /// Measurement saved, tree saved, etc.
        case success
        /// Red-tier scan, invalid input, etc.
        case failure
        /// REQ-NAV-002 arrival pulse (within 5 m of next plot).
        case arrival
        /// Plot closed — distinct two-beat pattern (Phase 7).
        case plotClose
    }

    public static func play(_ pattern: Pattern) {
        #if os(iOS)
        Task { @MainActor in
            switch pattern {
            case .success:
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            case .failure:
                UINotificationFeedbackGenerator().notificationOccurred(.error)
            case .arrival:
                let gen = UIImpactFeedbackGenerator(style: .heavy)
                gen.prepare()
                gen.impactOccurred()
            case .plotClose:
                let impact = UIImpactFeedbackGenerator(style: .medium)
                impact.prepare()
                impact.impactOccurred()
                try? await Task.sleep(nanoseconds: 250_000_000)
                UINotificationFeedbackGenerator().notificationOccurred(.success)
            }
        }
        #else
        _ = pattern   // no-op on non-iOS platforms
        #endif
    }
}
