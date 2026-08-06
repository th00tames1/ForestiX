// Spec §7.10 + REQ-CAL-003/004. Hosts the Wall + Cylinder calibration
// procedures under a segmented picker. Keeps live ARKit driving to a
// minimum so the screen snapshot-renders deterministically on macOS.
//
// Phase 7.2 hardening: added the missing "Start scan" button (audit
// caught that the .idle state had no entry point), an "Apply to
// project" button so calibration results actually reach
// `Project.depthNoiseMm` / `dbhCorrectionAlpha` / `β`, and a "Use
// sensible defaults" shortcut for cruisers who want to skip the wall
// + round-post ritual.
//
// The CYLINDER procedure keeps its internal name (the maths fits a
// cylinder), but every word on screen calls it a round-post scan: the
// tab, the section header and the Apply helper text agree, and the
// fitted coefficients α / β / R² are developer-mode only. This screen
// sits in the ORDINARY Calibration group in Settings, so nothing on it
// may assume the reader knows what a regression coefficient is.

import SwiftUI
import Common
import Models
import Sensors
import Persistence

public struct CalibrationScreen: View {

    @StateObject private var viewModel: CalibrationViewModel
    @State private var selectedProcedure: CalibrationViewModel.Procedure
    @State private var appliedToast: String?

    /// The fitted coefficients (α, β, R²) are the AUTHOR's diagnostics, not
    /// the cruiser's — this screen hangs off the ordinary Calibration group
    /// in Settings, so they are hidden unless developer mode is on.
    ///
    /// Read from the key rather than an `@EnvironmentObject`: the screen is
    /// built by `CalibrationScreen()` from a NavigationLink and by the
    /// snapshot tests with no environment at all, and a missing
    /// `@EnvironmentObject` is a crash, not a fallback.
    @AppStorage(AppSettings.Keys.developerMode)
    private var developerMode: Bool = false

    /// The unit system the round-post boxes are labelled, read and fitted in.
    /// Read from the key for the same reason `developerMode` is: this screen
    /// is built with no environment at all by a NavigationLink and by the
    /// snapshot tests, and a missing `@EnvironmentObject` is a crash.
    @AppStorage(AppSettings.Keys.unitSystem)
    private var unitSystemRaw: String = UnitSystem.imperial.rawValue

    private var unitSystem: UnitSystem {
        UnitSystem(rawValue: unitSystemRaw) ?? .imperial
    }

    /// The unit a round-post width is TYPED in. Both boxes and both read-back
    /// columns carry it, and `addCylinderSample(unit:)` converts with it —
    /// the fitted offset α is added in CENTIMETRES to every diameter this
    /// phone measures, so a fit taken on inch-scale numbers biases the whole
    /// project and nothing downstream says so.
    private var postUnit: TruthInput.Unit {
        TruthInput.defaultUnit(.diameter, imperial: unitSystem == .imperial)
    }

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
                // The tab, the section header below it and the Apply
                // helper text all name the same thing now: a round post.
                // "Cylinder" was the geometry the maths uses.
                Picker("Procedure", selection: $selectedProcedure) {
                    Text("Wall").tag(CalibrationViewModel.Procedure.wall)
                    Text("Round post").tag(CalibrationViewModel.Procedure.cylinder)
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
        .alert("Saved to this project",
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
            // This screen hangs off a NON-developer settings group, so what a
            // cruiser reads here is words: what the two scans are for, and
            // whether this project is running on a scan or on the standard
            // settings. The fitted numbers themselves (α / β and the wall's
            // mm figures) say nothing a cruiser can act on, so they moved
            // behind developer mode. The stored fields (depthNoiseMm /
            // lidarBiasMm / dbhCorrectionAlpha / dbhCorrectionBeta) and the
            // export are UNCHANGED — this is a display change only.
            Section(
                header: Text("How this phone is set up"),
                footer: Text("Two short scans tune the app to this phone. The wall scan learns how steady its distance readings are; the round-post scan corrects the widths it measures. Both are saved with this project.")
            ) {
                // Identity correction (α = 0, β = 1) is exactly the untouched /
                // "standard settings" state — see sensibleDefaultsApplied.
                //
                // THREE STATES, not two. A round-post scan corrects for
                // whatever the width estimator got wrong on the day it was
                // fitted, so when that estimator changes the correction is
                // answering a question that no longer exists and is no longer
                // applied. Saying "corrected" there would be a plain lie, and
                // saying "not corrected" would hide a scan the cruiser
                // remembers doing — so the stale case gets its own sentence
                // and asks for the one action that fixes it.
                //
                // Through `DBHCalibration.state` rather than re-derived here.
                // This screen's copy of the test was the correct one and the
                // PDF's was not, which is the whole argument for there being
                // one copy.
                let stale = DBHCalibration.state(of: p) == .ignoredStale
                let calibrated = DBHCalibration.state(of: p) != .none
                Text(stale
                     ? "Your round-post scan was made with an earlier version of the width measurement and no longer applies, so it is being ignored. Run the round-post scan again to correct widths on this project."
                     : calibrated
                     ? "Widths are being corrected using your round-post scan."
                     : "Widths are being used exactly as the phone measures them — no round-post scan has been applied yet.")
                    .fixedSize(horizontal: false, vertical: true)
                    .foregroundStyle(stale ? ForestixPalette.confidenceWarn
                                           : ForestixPalette.textPrimary)
                    .accessibilityIdentifier("calibration.widthStatus")
                if developerMode {
                    HStack {
                        Text("Depth reading spread")
                        Spacer()
                        Text(String(format: "%.2f mm", p.depthNoiseMm))
                    }
                    HStack {
                        Text("Depth sensor offset")
                        Spacer()
                        Text(String(format: "%.2f mm", p.lidarBiasMm))
                    }
                    HStack {
                        Text("Fitted α (cm)")
                        Spacer()
                        Text(String(format: "%.3f", p.dbhCorrectionAlpha))
                    }
                    HStack {
                        Text("Fitted β")
                        Spacer()
                        Text(String(format: "%.4f", p.dbhCorrectionBeta))
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var applySection: some View {
        Section(
            header: Text("Apply"),
            footer: Text("Applies your wall and round-post scans to this project. No scans yet? Use the standard settings to start measuring now and scan later.")
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
                Label("Use the standard settings (skip the scans)",
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
            // The rest of this screen talks about what the two scans DO —
            // "the wall scan learns how steady its distance readings are;
            // the round-post scan corrects the widths it measures". The
            // confirmation used to answer in the storage layer's voice
            // ("values written to project"), which told the cruiser nothing
            // about what had just changed for them.
            appliedToast = "Your scans are now in use for this project. Widths and distances measured from here on are corrected with them."
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
            appliedToast = "Standard settings applied. The app will measure with its usual allowances and will not correct this phone's widths. Run the wall and round-post scans later to tune it to this phone."
            project = updated
        } catch {
            appliedToast = "Couldn't save: \(error.localizedDescription). Try again from Settings."
        }
    }

    // MARK: - Wall

    @ViewBuilder
    private var wallSection: some View {
        // The footer now says what the scan is FOR, not just what to do
        // with the phone — a cruiser standing in front of a wall had no
        // way to tell why the app wanted this.
        Section(header: Text("Wall scan"),
                // The standing distance follows the cruiser's units like every
                // other instruction on the scan screens; the range itself is
                // the depth camera's and does not move.
                footer: Text("Shows the app how steady this phone's distance readings are. Point it at a flat wall "
                             + MeasurementFormatter.guidanceRange(
                                 fromM: 1, toM: 2, in: unitSystem)
                             + " away and hold still until the bar fills.")) {
            switch viewModel.wall {
            case .idle:
                Text("No wall scan yet.")
                Button {
                    viewModel.startWallScan()
                } label: {
                    Label("Start wall scan",
                          systemImage: "scanner.fill")
                        .frame(maxWidth: .infinity, minHeight: 56)
                }
                .buttonStyle(.forestixProminent)
                .accessibilityIdentifier("calibration.wall.start")
            case .scanning(let p):
                ProgressView(value: p)
                Text("Scanning wall… \(Int(p * 100))%")
                Button("Cancel") { viewModel.cancelWallScan() }
                    .accessibilityIdentifier("calibration.wall.cancel")
            case .computed(let r):
                // What the scan FOUND, in words plus the one number that is a
                // real quantity in a real unit. depthBiasMm / pointCount are
                // inputs to the estimator, not something a cruiser acts on,
                // so they sit behind developer mode with the fit coefficients.
                Text("Wall scan done. Across \(r.pointCount) points on the wall this "
                     + "phone's distance readings varied by about "
                     + String(format: "%.1f mm", r.depthNoiseMm) + ".")
                    .fixedSize(horizontal: false, vertical: true)
                if developerMode {
                    HStack {
                        Text("Depth reading spread")
                        Spacer()
                        Text(String(format: "%.2f mm", r.depthNoiseMm))
                    }
                    HStack {
                        Text("Depth sensor offset")
                        Spacer()
                        Text(String(format: "%.2f mm", r.depthBiasMm))
                    }
                    HStack {
                        Text("Points")
                        Spacer()
                        Text("\(r.pointCount)")
                    }
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
        // Was "Cylinder correction (α + β · raw DBH)" — the fit's own
        // formula, printed as a section header on a screen a cruiser
        // reaches from Settings › Calibration. The header, the tab above
        // and the Apply helper now all say "round post", and the section
        // opens by saying what the scan buys you.
        Section(header: Text("Round-post scan"),
                footer: Text("Corrects the widths this phone measures. Scan round posts you have already measured by hand, then enter both widths for each one — the app works out how far the scan runs wide or narrow and takes it off every tree.")) {
            HStack {
                TextField("Scanned (\(postUnit.rawValue))",
                          text: $viewModel.newMeasuredText)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .accessibilityIdentifier("calibration.cylinder.measured")
                TextField("By hand (\(postUnit.rawValue))",
                          text: $viewModel.newTrueText)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .accessibilityIdentifier("calibration.cylinder.true")
                Button("Add") { viewModel.addCylinderSample(unit: postUnit) }
                    .buttonStyle(.forestixProminent)
            }

            switch viewModel.cylinder {
            case .idle:
                Text("No posts entered yet.")
            case .collecting(let s):
                sampleList(s)
                Button("Work out the correction") {
                    viewModel.computeCylinderCalibration()
                }
                .disabled(s.count < 2)
            case .computed(let r, let s):
                sampleList(s)
                // α / β / R² are the fitted coefficients — nothing a cruiser
                // can act on, and R² is a grade rather than a quantity. What
                // a cruiser needs is how close the CORRECTED width now lands
                // to the hand measurement, in centimetres. Display only: the
                // fit, its thresholds and what gets stored are untouched.
                // Stated in the unit the posts were measured in — this is the
                // one number that tells a cruiser how good the correction is,
                // and it is useless in a unit they did not type.
                Text("Correction worked out from \(s.count) posts. Corrected widths "
                     + "land within "
                     + String(format: "%.1f %@",
                              TruthInput.fromBase(
                                  meanAbsResidualCm(result: r, samples: s),
                                  unit: postUnit),
                              postUnit.rawValue)
                     + " of your hand measurements on average.")
                    .fixedSize(horizontal: false, vertical: true)
                if developerMode {
                    HStack {
                        Text("Fitted α")
                        Spacer()
                        Text(String(format: "%.3f cm", r.alpha))
                    }
                    HStack {
                        Text("Fitted β")
                        Spacer()
                        Text(String(format: "%.4f", r.beta))
                    }
                    HStack {
                        Text("R²")
                        Spacer()
                        Text(String(format: "%.4f", r.rSquared))
                    }
                }
                Button("Reset") { viewModel.resetCylinder() }
            case .failed(let msg):
                Text(msg).foregroundStyle(.red)
                Button("Reset") { viewModel.resetCylinder() }
            }
        }
    }

    /// Mean |corrected − hand-measured| over the entered posts, in cm — the
    /// one number that tells a cruiser how good the correction is, in the
    /// unit they measured in. Read-only: it re-applies the fit that was
    /// already computed and changes no check, threshold or stored value.
    private func meanAbsResidualCm(
        result: CylinderCalibrationResult,
        samples: [CylinderCalibration.Sample]
    ) -> Double {
        guard !samples.isEmpty else { return 0 }
        let a = result.alpha
        let b = result.beta
        let total = samples.reduce(0.0) {
            $0 + abs(a + b * $1.dbhMeasuredCm - $1.dbhTrueCm)
        }
        return total / Double(samples.count)
    }

    @ViewBuilder
    private func sampleList(_ samples: [CylinderCalibration.Sample]) -> some View {
        ForEach(Array(samples.enumerated()), id: \.offset) { _, s in
            // "raw" / "true" are the fit's names for the two columns; the
            // cruiser typed a scanned diameter and a hand measurement.
            // Read back in the unit they were typed in — a post entered as
            // 12 in must not reappear as "30.5 cm" on the same screen.
            HStack {
                Text("scanned "
                     + MeasurementFormatter.diameter(cm: s.dbhMeasuredCm, in: unitSystem))
                Spacer()
                Text("by hand "
                     + MeasurementFormatter.diameter(cm: s.dbhTrueCm, in: unitSystem))
            }
            .font(.caption.monospacedDigit())
        }
    }
}
