import Foundation

/// Height of the AR guide above the tapped ground point. Display units do
/// not select a different physical height. This is independent of the
/// constants used inside published volume and height–diameter equations.
public enum BreastHeightGuideHeight: String, CaseIterable, Sendable {
    case meters130 = "1.30"
    case meters137 = "1.37"

    public static func fromRaw(_ raw: String?) -> Self {
        raw.flatMap(Self.init(rawValue:)) ?? .meters130
    }

    public var meters: Double {
        switch self {
        case .meters130: return 1.30
        case .meters137: return Units.breastHeightM
        }
    }

    public var metricLabel: String { "\(rawValue) m" }
    public var imperialLabel: String {
        self == .meters130 ? "4.27 ft" : "4.5 ft"
    }

    /// AR world Y is gravity-aligned: only elevation changes, regardless of
    /// camera tilt, viewing distance, or the local ground slope.
    public func point(above ground: SIMD3<Float>) -> SIMD3<Float> {
        ground + SIMD3<Float>(0, Float(meters), 0)
    }
}
