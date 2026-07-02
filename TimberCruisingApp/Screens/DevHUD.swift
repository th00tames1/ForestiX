// Developer / research HUD — a translucent label:value panel surfacing the
// live measurement internals on the AR screens when Developer Mode is on
// (Settings). The on-device window into everything the validation study
// logs: depth source, camera intrinsics (depth-map-scaled), depth-map size,
// point/inlier counts, raw chord/diameter, distance, σ, angles. Pinned
// top-right so it clears the nav bar and the bottom status panel.

import SwiftUI

public struct DevHUD: View {

    public struct Line: Identifiable {
        public let id = UUID()
        public let label: String
        public let value: String
    }

    private let title: String
    private let lines: [Line]

    public init(title: String, lines: [(String, String)]) {
        self.title = title
        self.lines = lines.map { Line(label: $0.0, value: $0.1) }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text("DEV · \(title)")
                .font(.system(size: 9, weight: .bold, design: .monospaced))
                .tracking(1)
                .foregroundStyle(Color(red: 0.42, green: 0.88, blue: 0.54))
            ForEach(lines) { line in
                HStack(spacing: 8) {
                    Text(line.label)
                        .font(.system(size: 10, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.7))
                    Spacer(minLength: 0)
                    Text(line.value)
                        .font(.system(size: 10, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white)
                }
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(maxWidth: 230, alignment: .leading)
        .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(Color(red: 0.29, green: 0.6, blue: 0.36).opacity(0.5), lineWidth: 0.5))
    }
}

public extension View {
    /// Pins a DevHUD to the top-right of an AR screen when `enabled`.
    @ViewBuilder
    func devHUDOverlay(_ enabled: Bool, title: String, lines: @autoclosure () -> [(String, String)]) -> some View {
        if enabled {
            self.overlay(alignment: .topTrailing) {
                DevHUD(title: title, lines: lines())
                    .padding(.top, 8)
                    .padding(.trailing, 8)
            }
        } else {
            self
        }
    }
}
