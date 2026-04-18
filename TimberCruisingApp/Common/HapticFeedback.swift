// Spec §8 (Common/HapticFeedback.swift) + §2.3 design principle 5 ("Haptic on
// all state transitions"). Three patterns: success / failure / arrival
// (REQ-NAV-002 "Haptic pulse when within 5 m").
//
// iOS-only implementation; on non-iOS platforms (e.g. macOS test host) the
// calls are no-ops so `swift test` runs without a haptic engine.

import Foundation

#if canImport(UIKit)
import UIKit
#endif

public enum HapticFeedback {

    public enum Pattern: Sendable {
        /// Measurement saved, plot closed, etc.
        case success
        /// Red-tier scan, invalid input, etc.
        case failure
        /// REQ-NAV-002 arrival pulse (within 5 m of next plot).
        case arrival
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
            }
        }
        #else
        _ = pattern   // no-op on non-iOS platforms
        #endif
    }
}
