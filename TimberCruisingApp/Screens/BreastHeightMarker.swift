import SwiftUI
import Common

/// Small, non-interactive ticks at the projection of the anchored height.
struct BreastHeightMarker: View {
    let point: CGPoint
    let size: CGSize
    let stemLeft: CGFloat?
    let stemRight: CGFloat?
    let label: String

    var body: some View {
        if let layout = BreastHeightMarkerLayout(
            x: point.x, y: point.y, width: size.width, height: size.height,
            stemLeft: stemLeft.map(Double.init), stemRight: stemRight.map(Double.init)) {
            ZStack(alignment: .topLeading) {
                let ticks = Path { path in
                    for tick in [layout.leftTick, layout.rightTick].compactMap({ $0 }) {
                        path.move(to: CGPoint(x: tick.lowerBound, y: layout.y))
                        path.addLine(to: CGPoint(x: tick.upperBound, y: layout.y))
                    }
                }
                ticks.stroke(.black.opacity(0.65), style: StrokeStyle(lineWidth: 4, lineCap: .round))
                ticks.stroke(.white, style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
                if let x = layout.labelCenterX {
                    Text(label)
                        .font(.system(size: 12, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.black)
                        .frame(width: BreastHeightMarkerLayout.labelWidth,
                               height: BreastHeightMarkerLayout.labelHeight)
                        .background(.white.opacity(0.92), in: RoundedRectangle(cornerRadius: 4))
                        .position(x: x, y: layout.y)
                }
            }
            .frame(width: size.width, height: size.height)
            .allowsHitTesting(false)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Ground reference height, \(label)")
            .accessibilityIdentifier("dbhScan.breastHeightLabel")
        }
    }
}
