// Shared LiDAR / AR source toggle used by every AR measurement screen.
//
// Behaviour:
//   • On LiDAR devices — a live segmented control bound to
//     `AppSettings.measurementSource`. The cruiser flips between the
//     scene-mesh (LiDAR) path and the estimated-plane (AR) path and the
//     change applies to the next capture.
//   • On devices WITHOUT LiDAR — the control is locked to AR and a short
//     caption explains why, so the cruiser understands the device was
//     checked and the high-accuracy path simply isn't available here.
//
// `AppSettings.measurementSource` already hard-clamps to `.ar` on
// non-LiDAR devices, so this view only has to reflect that state.

import SwiftUI

public struct MeasurementSourceToggle: View {

    @EnvironmentObject private var settings: AppSettings

    public init() {}

    public var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Picker("Source", selection: Binding(
                get: { settings.measurementSource },
                set: { settings.measurementSource = $0 })
            ) {
                Text("LiDAR").tag(MeasurementSource.lidar)
                Text("AR").tag(MeasurementSource.ar)
            }
            .pickerStyle(.segmented)
            .disabled(!settings.deviceSupportsLiDAR)
            .accessibilityIdentifier("measurement.sourceToggle")

            if !settings.deviceSupportsLiDAR {
                Text("No LiDAR sensor on this device — using AR.")
                    .font(ForestixType.caption)
                    .foregroundStyle(.white.opacity(0.85))
            }
        }
    }
}
