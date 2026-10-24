import SwiftUI
import Models
import Common

public struct FieldStudySettingsScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var environment: AppEnvironment
    @ObservedObject private var store: FieldStudyStore
    @State private var draft: FieldStudyConfiguration
    @State private var saving = false
    @State private var message: String?
    @State private var exporting = false
    @State private var exportURL: URL?
    public init(store: FieldStudyStore) {
        self.store = store; _draft = State(initialValue: store.configuration)
    }
    public var body: some View {
        Form {
            Section("Study protocol") {
                Toggle("Record field-study measurements", isOn: $draft.enabled)
                TextField("Study ID", text: $draft.studyId)
                TextField("Operator ID", text: $draft.operatorId)
                TextField("Protocol version", text: $draft.protocolVersion)
                Toggle("Lock protocol", isOn: $draft.protocolLocked)
                Stepper("Planned repetitions: \(draft.plannedRepetitions)", value: $draft.plannedRepetitions, in: 1...999)
                Stepper("Current repetition: \(draft.plannedRepeat)", value: $draft.plannedRepeat, in: 1...max(1,draft.plannedRepetitions))
                Text("After recording starts, change the protocol version before changing measurement settings.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Section("Recording") {
                Toggle("Record elapsed time", isOn: $draft.recordTiming)
                Toggle("Record failed attempts", isOn: $draft.recordFailedAttempts)
                Toggle("Preserve AI input and mask", isOn: $draft.preserveAiInputs)
            }
            Section("Target map layer") {
                NavigationLink("Target catalog / Reference observations") { FieldStudyTargetsScreen(store: store) }
                    .disabled(store.configuration.studyId.isEmpty)
                Toggle("Display target layer", isOn: $draft.mapOverlayEnabled)
                Toggle("Hide completed targets", isOn: $draft.hideCompletedTargets)
            }
            Section("AR target overlay") {
                Toggle("Display nearby targets in AR", isOn: $draft.ar.enabled)
                Stepper(value: $draft.ar.maximumDistanceM, in: 1...100, step: 1) {
                    Text("Maximum distance: \(distanceLabel(draft.ar.maximumDistanceM))")
                }
                Stepper("Maximum targets: \(draft.ar.maximumPoints)", value: $draft.ar.maximumPoints, in: 1...20)
                Stepper(value: $draft.ar.maximumHorizontalAccuracyM, in: 0.1...100, step: 0.5) {
                    Text("Maximum horizontal uncertainty: \(distanceLabel(draft.ar.maximumHorizontalAccuracyM))")
                }
                Stepper(value: $draft.ar.maximumFixAgeSeconds, in: 0.1...10, step: 0.5) {
                    Text("Maximum position age: \(draft.ar.maximumFixAgeSeconds, specifier: "%.1f") s")
                }
                Stepper(value: $draft.ar.maximumHeadingAgeSeconds, in: 0.1...10, step: 0.5) {
                    Text("Maximum heading age: \(draft.ar.maximumHeadingAgeSeconds, specifier: "%.1f") s")
                }
                Stepper(value: $draft.ar.maximumHeadingErrorDegrees, in: 0.1...45, step: 1) {
                    Text("Maximum heading uncertainty: \(draft.ar.maximumHeadingErrorDegrees, specifier: "%.1f")°")
                }
                Stepper(value: $draft.ar.distanceHysteresisM, in: 0...10, step: 0.5) {
                    Text("Range buffer: \(distanceLabel(draft.ar.distanceHysteresisM))")
                }
                Toggle("Display target distances", isOn: $draft.ar.showDistances)
                Text("Approximate locations. Poor GPS hides markers, not measurement.").font(.footnote).foregroundStyle(.secondary)
            }
            if settings.developerMode {
                methodsSection(.dbh, title: "DBH reference methods")
                methodsSection(.height, title: "Height reference methods")
                Section("Reference visibility") {
                    Toggle("Allow reference preview during measurement", isOn: $draft.showReferencesDuringMeasurement)
                }
            }
            if let error = store.error { Section { Text(error).foregroundStyle(.red) } }
            if !store.issues.isEmpty {
                Section("Archive integrity") {
                    Text("\(store.issues.count) archive integrity issues. Original files have been retained.").foregroundStyle(.orange)
                }
            }
            if let message { Section { Text(message).accessibilityIdentifier("fieldStudySettingsMessage") } }
            Section("Complete field-study export") {
                Button(exporting ? "Exporting…" : "Export records, raw and integrity audit") { export() }
                    .disabled(exporting || saving || !store.loaded)
                    .accessibilityIdentifier("fieldStudy.exportAll")
                Text("Includes records and raw files. Missing files are listed in audit.json.")
                    .font(.footnote).foregroundStyle(.secondary)
                if let exportURL {
                    ShareLink("Share field-study ZIP",item: exportURL).accessibilityIdentifier("fieldStudy.shareExport")
                }
            }
            Section {
                Button(saving ? "Saving…" : "Save configuration") { save() }
                    .disabled(saving || !store.loaded || store.error != nil)
                    .accessibilityIdentifier("fieldStudy.saveConfiguration")
            }
        }
        .navigationTitle("Field study")
        .disabled(!store.loaded || saving || exporting)
        .onChange(of: store.configuration) { _, value in draft = value }
        .onChange(of: store.loaded) { _, loaded in if loaded { draft = store.configuration } }
        .onChange(of: draft.plannedRepetitions) { _, count in draft.plannedRepeat = min(draft.plannedRepeat,count) }
    }
    private func distanceLabel(_ meters: Double) -> String {
        settings.unitSystem == .imperial
            ? String(format: "%.1f ft", Units.metersToFeet(meters))
            : String(format: "%.1f m", meters)
    }
    @ViewBuilder private func methodsSection(_ kind: FieldStudyKind, title: String) -> some View {
        Section(title) {
            ForEach($draft.referenceMethods) { $method in
                if method.kind == kind {
                    VStack(alignment: .leading) {
                        TextField("Method name", text: $method.name)
                            .accessibilityIdentifier("fieldStudy.referenceMethodName."+method.id)
                        Toggle("Enabled", isOn: $method.enabled)
                        Picker("Unit", selection: $method.unit) {
                            ForEach(FieldReferenceUnit.allCases.filter { $0.supports(kind) }, id: \.self) { u in Text(u.rawValue).tag(u) }
                        }
                        Picker("Role", selection: $method.role) {
                            Text("Ground truth").tag(FieldReferenceRole.groundTruth)
                            Text("Comparison").tag(FieldReferenceRole.comparison)
                        }
                    }
                }
            }
            Button("Add reference method") {
                draft.referenceMethods.append(.init(name: "Comparison \(draft.referenceMethods.filter { $0.kind == kind }.count+1)",
                                                    kind: kind, unit: kind == .dbh ? .cm : .m))
            }.disabled(draft.referenceMethods.count >= 50)
            .accessibilityIdentifier("fieldStudy.addReferenceMethod."+kind.rawValue)
        }
    }
    private func save() {
        saving = true; message = nil
        let next = FieldProtocolBinding.preparing(draft,from: store.configuration); let developer = settings.developerMode
        Task {
            do { try await store.saveConfiguration(next, developerMode: developer); message = "Configuration saved." }
            catch { message = error.localizedDescription }
            saving = false
        }
    }
    private func export() {
        guard !exporting else { return }; exporting = true; exportURL = nil; message = nil
        Task {
            do {
                let output = try await FieldStudyExportApplication.build(environment: environment)
                exportURL = output.url
                message = "Exported \(output.report.measurementCount) attempts, \(output.report.referenceCount) reference observations and \(output.report.rawBundleCount) raw bundles. Integrity issues: \(output.report.issues.count). See audit.json."
            } catch { message = "Export failed: "+error.localizedDescription }
            exporting = false
        }
    }
}
