// Phase 1 settings surface. Exposes only the cruiser-facing knobs used by
// Phase 1 screens: tile URL template + usage-policy acknowledgement and
// display unit preference. Phase 7 will extend this with calibration
// procedures (REQ-CAL-001..005).

import SwiftUI
import Models

public struct SettingsScreen: View {

    @EnvironmentObject private var settings: AppSettings
    @State private var tileTemplate: String = ""
    @State private var providerLabel: String = ""
    @State private var unitSystem: UnitSystem = .imperial
    @State private var providerAck: Bool = false

    public init() {}

    public var body: some View {
        Form {
            Section("Units") {
                Picker("Default units", selection: $unitSystem) {
                    Text("Imperial").tag(UnitSystem.imperial)
                    Text("Metric").tag(UnitSystem.metric)
                }
                .pickerStyle(.segmented)
                .onChange(of: unitSystem) { _, new in settings.unitSystem = new }
            }

            Section(header: Text("Calibration"),
                    footer: Text("Wall fit captures the LiDAR depth noise and " +
                                 "bias; cylinder fit estimates a linear DBH correction.")) {
                NavigationLink("LiDAR Calibration") { CalibrationScreen() }
                    .accessibilityIdentifier("settings.calibrationLink")
            }

            Section(header: Text("Basemap tiles"),
                    footer: Text("No tile provider ships by default. Paste an XYZ template that substitutes {z}/{x}/{y}. Confirm you've reviewed the provider's usage policy before downloading.")) {
                TextField("https://tile.example.com/{z}/{x}/{y}.png", text: $tileTemplate)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    #endif
                    .accessibilityIdentifier("settings.tileTemplate")
                    .onChange(of: tileTemplate) { _, new in
                        settings.tileURLTemplate = new.isEmpty ? nil : new
                    }
                TextField("Provider name (optional)", text: $providerLabel)
                    .accessibilityIdentifier("settings.providerLabel")
                    .onChange(of: providerLabel) { _, new in
                        settings.tileProviderLabel = new.isEmpty ? nil : new
                    }
                Toggle("I have reviewed this provider's usage policy",
                       isOn: $providerAck)
                    .accessibilityIdentifier("settings.providerAck")
                    .onChange(of: providerAck) { _, new in
                        settings.providerUsageAcknowledged = new
                    }
            }
        }
        .navigationTitle("Settings")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            tileTemplate = settings.tileURLTemplate ?? ""
            providerLabel = settings.tileProviderLabel ?? ""
            unitSystem = settings.unitSystem
            providerAck = settings.providerUsageAcknowledged
        }
    }
}
