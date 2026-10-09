import Foundation

/// Committed measurement only; live preview can continue updating normally.
public enum DBHCaptureMode: String, CaseIterable, Sendable {
    case single
    case multi5

    public static func fromRaw(_ raw: String?) -> Self {
        raw.flatMap(Self.init(rawValue:)) ?? .single
    }

    public func frameCount(developerMode: Bool) -> Int {
        developerMode && self == .multi5 ? 5 : 1
    }
}
