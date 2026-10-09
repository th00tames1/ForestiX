import Foundation

/// Screen-space chrome around a projected world point, in points (dp on
/// Android). Never moves the height to keep an off-screen marker visible.
public struct BreastHeightMarkerLayout {
    public var leftTick: ClosedRange<Double>?
    public var rightTick: ClosedRange<Double>?
    public var labelCenterX: Double?
    public let y: Double
    public static let labelWidth: Double = 64
    public static let labelHeight: Double = 22

    public init?(x: Double, y: Double, width: Double, height: Double,
                 stemLeft: Double? = nil, stemRight: Double? = nil) {
        guard [x, y, width, height].allSatisfy({ $0.isFinite }),
              width > 24, height > 24, x >= 0, x <= width,
              y >= 12, y <= height - 12 else { return nil }
        self.y = y
        var left = x - 24
        var right = x + 24
        // Near the measurement row, keep ticks and text outside the bracket.
        if let l = stemLeft, let r = stemRight, l.isFinite, r.isFinite,
           l < r, x >= l, x <= r, abs(y - height / 2) <= 44 {
            left = min(left, l - 8)
            right = max(right, r + 8)
        }
        leftTick = left - 12 >= 12 ? (left - 12)...left : nil
        rightTick = right + 12 <= width - 12 ? right...(right + 12) : nil
        guard leftTick != nil || rightTick != nil else { return nil }
        if let tick = rightTick, tick.upperBound + 6 + Self.labelWidth <= width - 12 {
            labelCenterX = tick.upperBound + 6 + Self.labelWidth / 2
        } else if let tick = leftTick, tick.lowerBound - 6 - Self.labelWidth >= 12 {
            labelCenterX = tick.lowerBound - 6 - Self.labelWidth / 2
        }
    }
}
