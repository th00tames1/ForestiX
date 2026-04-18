// Spec §7.10 + REQ-CAL-003/004. Hosts the Wall + Cylinder calibration
// procedures under a segmented picker. Keeps live ARKit driving to a
// minimum so the screen snapshot-renders deterministically on macOS.

import SwiftUI
import Sensors

public struct CalibrationScreen: View {

    @StateObject private var viewModel: CalibrationViewModel
    @State private var selectedProcedure: CalibrationViewModel.Procedure

    public init(
        viewModel: @autoclosure @escaping () -> CalibrationViewModel,
        initialProcedure: CalibrationViewModel.Procedure = .wall
    ) {
        _viewModel = StateObject(wrappedValue: viewModel())
        _selectedProcedure = State(initialValue: initialProcedure)
    }

    public init() {
        _viewModel = StateObject(wrappedValue: CalibrationViewModel())
        _selectedProcedure = State(initialValue: .wall)
    }

    public var body: some View {
        Form {
            Section {
                Picker("Procedure", selection: $selectedProcedure) {
                    Text("Wall").tag(CalibrationViewModel.Procedure.wall)
                    Text("Cylinder").tag(CalibrationViewModel.Procedure.cylinder)
                }
                .pickerStyle(.segmented)
            }

            switch selectedProcedure {
            case .wall:     wallSection
            case .cylinder: cylinderSection
            }
        }
        .navigationTitle("Calibration")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    // MARK: - Wall

    @ViewBuilder
    private var wallSection: some View {
        Section(header: Text("Wall plane (depth noise + bias)"),
                footer: Text("Point the phone at a flat wall 1–2 m away and " +
                             "hold while capturing 30 frames.")) {
            switch viewModel.wall {
            case .idle:
                Text("Not calibrated yet.")
            case .scanning(let p):
                ProgressView(value: p)
                Text("Scanning wall… \(Int(p * 100))%")
            case .computed(let r):
                HStack {
                    Text("Depth noise")
                    Spacer()
                    Text(String(format: "%.2f mm", r.depthNoiseMm))
                }
                HStack {
                    Text("Depth bias")
                    Spacer()
                    Text(String(format: "%.2f mm", r.depthBiasMm))
                }
                HStack {
                    Text("Points")
                    Spacer()
                    Text("\(r.pointCount)")
                }
                Button("Reset") { viewModel.resetWall() }
            case .failed(let msg):
                Text(msg).foregroundStyle(.red)
                Button("Retry") { viewModel.resetWall() }
            }
        }
    }

    // MARK: - Cylinder

    @ViewBuilder
    private var cylinderSection: some View {
        Section(header: Text("Cylinder correction (α + β · raw DBH)"),
                footer: Text("Scan PVC pipes of known diameter. Enter the " +
                             "measured DBH from the scan and the true diameter.")) {
            HStack {
                TextField("Measured DBH (cm)", text: $viewModel.newMeasuredCm)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .accessibilityIdentifier("calibration.cylinder.measured")
                TextField("True DBH (cm)", text: $viewModel.newTrueCm)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .accessibilityIdentifier("calibration.cylinder.true")
                Button("Add") { viewModel.addCylinderSample() }
                    .buttonStyle(.borderedProminent)
            }

            switch viewModel.cylinder {
            case .idle:
                Text("No samples yet.")
            case .collecting(let s):
                sampleList(s)
                Button("Compute α, β") { viewModel.computeCylinderCalibration() }
                    .disabled(s.count < 2)
            case .computed(let r, let s):
                sampleList(s)
                HStack { Text("α"); Spacer(); Text(String(format: "%.3f", r.alpha)) }
                HStack { Text("β"); Spacer(); Text(String(format: "%.4f", r.beta)) }
                HStack { Text("R²"); Spacer(); Text(String(format: "%.4f", r.rSquared)) }
                Button("Reset") { viewModel.resetCylinder() }
            case .failed(let msg):
                Text(msg).foregroundStyle(.red)
                Button("Reset") { viewModel.resetCylinder() }
            }
        }
    }

    @ViewBuilder
    private func sampleList(_ samples: [CylinderCalibration.Sample]) -> some View {
        ForEach(Array(samples.enumerated()), id: \.offset) { _, s in
            HStack {
                Text("raw \(String(format: "%.1f", s.dbhMeasuredCm)) cm")
                Spacer()
                Text("true \(String(format: "%.1f", s.dbhTrueCm)) cm")
            }
            .font(.caption.monospacedDigit())
        }
    }
}
