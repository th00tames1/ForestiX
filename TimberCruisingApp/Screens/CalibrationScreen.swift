// Spec §7.10 + REQ-CAL-003/004. Hosts the Wall + Cylinder calibration
// procedures under a segmented picker. Keeps live ARKit driving to a
// minimum so the screen snapshot-renders deterministically on macOS.
//
// Phase 7.2 hardening: added the missing "Start scan" button (audit
// caught that the .idle state had no entry point), an "Apply to
// project" button so calibration results actually reach
// `Project.depthNoiseMm` / `dbhCorrectionAlpha` / `β`, and a "Use
// sensible defaults" shortcut for cruisers who want to skip the wall
// + cylinder ritual.

import SwiftUI
import Models
import Sensors
import Persistence

public struct CalibrationScreen: View {

    @StateObject private var viewModel: CalibrationViewModel
    @State private var selectedProcedure: CalibrationViewModel.Procedure
    @State private var appliedToast: String?

    /// Optional — when set, the "Apply" buttons persist back into Core
    /// Data via this repository. `@State` so the projectStatusSection
    /// re-renders after a successful apply.
    @State private var project: Project?
    private let projectRepo: (any ProjectRepository)?

    public init(
        viewModel: @autoclosure @escaping () -> CalibrationViewModel,
        initialProcedure: CalibrationViewModel.Procedure = .wall,
        project: Project? = nil,
        projectRepo: (any ProjectRepository)? = nil
    ) {
        _viewModel = StateObject(wrappedValue: viewModel())
        _selectedProcedure = State(initialValue: initialProcedure)
        _project = State(initialValue: project)
        self.projectRepo = projectRepo
    }

    public init() {
        _viewModel = StateObject(wrappedValue: CalibrationViewModel())
        _selectedProcedure = State(initialValue: .wall)
        _project = State(initialValue: nil)
        self.projectRepo = nil
    }

    public var body: some View {
        Form {
            if project != nil {
                projectStatusSection
            }
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

            if project != nil {
                applySection
            }
        }
        .navigationTitle("Calibration")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .alert("Calibration applied",
               isPresented: Binding(
                get: { appliedToast != nil },
                set: { if !$0 { appliedToast = nil } })
        ) {
            Button("OK", role: .cancel) { appliedToast = nil }
        } message: {
            Text(appliedToast ?? "")
        }
    }

    @ViewBuilder
    private var projectStatusSection: some View {
        if let p = project {
            Section("Current project values") {
                LabeledContent("Depth noise") {
                    Text(String(format: "%.2f mm", p.depthNoiseMm))
                }
                LabeledContent("LiDAR bias") {
                    Text(String(format: "%.2f mm", p.lidarBiasMm))
                }
                LabeledContent("DBH α") {
                    Text(String(format: "%.3f", p.dbhCorrectionAlpha))
                }
                LabeledContent("DBH β") {
                    Text(String(format: "%.4f", p.dbhCorrectionBeta))
                }
            }
        }
    }

    @ViewBuilder
    private var applySection: some View {
        Section(
            header: Text("Apply"),
            footer: Text("Wall + cylinder results write to this project's depth noise, LiDAR bias, and DBH α/β. The defaults shortcut applies the spec §7.10 identity values without scanning — useful for getting into the field on a freshly installed phone.")
        ) {
            Button {
                applyComputed()
            } label: {
                Label("Apply scanned values to project",
                      systemImage: "square.and.arrow.down")
            }
            .disabled(!hasAnyComputed)
            .accessibilityIdentifier("calibration.apply.scanned")

            Button {
                applySensibleDefaults()
            } label: {
                Label("Use sensible defaults (skip scan)",
                      systemImage: "wand.and.stars")
            }
            .accessibilityIdentifier("calibration.apply.defaults")
        }
    }

    private var hasAnyComputed: Bool {
        if case .computed = viewModel.wall { return true }
        if case .computed = viewModel.cylinder { return true }
        return false
    }

    private func applyComputed() {
        guard let p = project, let repo = projectRepo else { return }
        let updated = viewModel.applyTo(project: p)
        do {
            _ = try repo.update(updated)
            appliedToast = "Calibration values written to project."
            project = updated
        } catch {
            appliedToast = "Couldn't save: \(error.localizedDescription). Try again from Settings."
        }
    }

    private func applySensibleDefaults() {
        guard let p = project, let repo = projectRepo else { return }
        let updated = CalibrationViewModel.sensibleDefaultsApplied(to: p)
        do {
            _ = try repo.update(updated)
            appliedToast = "Sensible defaults applied — depth noise 5 mm, identity DBH correction. Run the wall + cylinder later for higher precision."
            project = updated
        } catch {
            appliedToast = "Couldn't save: \(error.localizedDescription). Try again from Settings."
        }
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
                Button {
                    viewModel.startWallScan()
                } label: {
                    Label("Start wall scan",
                          systemImage: "scanner.fill")
                        .frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("calibration.wall.start")
            case .scanning(let p):
                ProgressView(value: p)
                Text("Scanning wall… \(Int(p * 100))%")
                Button("Cancel") { viewModel.cancelWallScan() }
                    .accessibilityIdentifier("calibration.wall.cancel")
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
