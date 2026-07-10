// v1.5 §7.3 strategy D — external RTK receiver. REQ-CTR-004.
//
// v0.4 scope: protocol surface + a non-available stub so Screen/
// evaluator code can reference externalRTK as a strategy source
// today without platform-gated imports. Real NTRIP / Bluetooth /
// serial RTK integration ships in v1.5 when we have hardware to
// test against.

import Foundation
import Models

/// A source of high-accuracy RTK/RTK-float fixes. Implementations
/// stream `CLLocationSnapshot`-equivalent fixes with sub-metre
/// accuracy. The evaluator treats these as highest-priority when
/// available (see `PositionTierEvaluator.sourcePriority`).
public protocol ExternalRTKReceiver: Sendable {
    /// True when the receiver is connected AND reporting RTK-fixed
    /// (not RTK-float or standalone GPS) fixes. The UI should only
    /// show the "external RTK" tier when this is true.
    var isAvailable: Bool { get }

    /// Latest fix, if one has arrived within the staleness budget.
    /// Returns nil when the receiver is disconnected or all cached
    /// fixes are older than `maxAgeS`.
    func latestFix(maxAgeS: TimeInterval) async -> PlotCenterResult?
}

/// v0.4 placeholder: always unavailable, never returns a fix.
/// Wired into the positioning pipeline so the evaluator can ignore
/// RTK entirely until v1.5 replaces this with a real adapter.
public struct UnavailableRTKReceiver: ExternalRTKReceiver {
    public init() {}
    public var isAvailable: Bool { false }
    public func latestFix(maxAgeS: TimeInterval) async -> PlotCenterResult? {
        nil
    }
}
