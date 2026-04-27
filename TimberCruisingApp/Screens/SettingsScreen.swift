// Phase 1 settings surface. Phase 7 extends with:
//   • Pre-field check shortcut (per project)
//   • Calibration wizard entry (existing CalibrationScreen)
//   • Backup + restore (.tcproj)
//   • Analytics log export
//   • Data reset with 2-step confirmation

import SwiftUI
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif
import Common
import Models
import Sensors

public struct SettingsScreen: View {

    @EnvironmentObject private var settings: AppSettings
    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var backup = BackupViewModel()

    @State private var tileTemplate: String = ""
    @State private var providerLabel: String = ""
    @State private var unitSystem: UnitSystem = .imperial
    @State private var providerAck: Bool = false

    // Destructive flows
    @State private var isPresentingResetStep1 = false
    @State private var isPresentingResetStep2 = false
    @State private var resetError: String?

    #if os(iOS)
    @State private var isPresentingImport = false
    #endif

    public init() {}

    public var body: some View {
        Form {
            // modeSection (Advanced mode toggle) intentionally
            // hidden — Phase 7 unified the two homes so the toggle
            // no longer drives anything. The `advancedMode` property
            // on AppSettings is preserved for back-compat but the
            // Settings UI doesn't expose it any more.
            regionSection
            unitsSection
            logRuleSection
            calibrationSection
            basemapSection
            backupSection
            analyticsSection
            dangerZoneSection
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
            backup.configure(with: environment)
        }
        #if os(iOS)
        .sheet(item: Binding(
            get: { backup.shareURL.map(ShareURLWrapper.init) },
            set: { backup.shareURL = $0?.url })
        ) { wrapper in
            ShareSheet(url: wrapper.url)
        }
        .fileImporter(isPresented: $isPresentingImport,
                      allowedContentTypes: [.zip, .data],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let first = urls.first { backup.restore(from: first) }
            case .failure(let err):
                backup.errorMessage = "Import failed: \(err.localizedDescription)."
            }
        }
        #endif
        .alert("Something went wrong",
               isPresented: Binding(
                get: { backup.errorMessage != nil || resetError != nil },
                set: { if !$0 { backup.errorMessage = nil; resetError = nil } })
        ) {
            Button("OK", role: .cancel) {
                backup.errorMessage = nil
                resetError = nil
            }
        } message: {
            Text(backup.errorMessage ?? resetError ?? "")
        }
        .alert("Restore complete",
               isPresented: Binding(
                get: { backup.restoreSummary != nil },
                set: { if !$0 { backup.restoreSummary = nil } })
        ) {
            Button("OK", role: .cancel) { backup.restoreSummary = nil }
        } message: {
            Text(backup.restoreSummary ?? "")
        }
        .confirmationDialog(
            "Reset Forestix data?",
            isPresented: $isPresentingResetStep1,
            titleVisibility: .visible
        ) {
            Button("Continue", role: .destructive) {
                isPresentingResetStep2 = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes every project, plot, tree, photo, and scan. Back up anything you need to keep first. This cannot be undone.")
        }
        .confirmationDialog(
            "Are you absolutely sure?",
            isPresented: $isPresentingResetStep2,
            titleVisibility: .visible
        ) {
            Button("Delete everything", role: .destructive) {
                performFullReset()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Last chance to back out. All local data will be erased.")
        }
    }

    // MARK: - Sections

    private var regionSection: some View {
        Section(
            header: Text("Region"),
            footer: Text("Pre-loads the FIA species set for your timber region — affects which species appear in scan-time pickers.")
        ) {
            Picker("Region",
                   selection: Binding(
                    get: { settings.region ?? .all },
                    set: { settings.region = $0; settings.regionPickerSeen = true })
            ) {
                ForEach(Region.allCases) { r in
                    Text(r.displayName).tag(r)
                }
            }
            .accessibilityIdentifier("settings.region")
        }
    }

    private var modeSection: some View {
        Section(
            header: Text("Mode"),
            footer: Text("Advanced mode unlocks the full Forestix workflow — projects, stratum drawing, cruise design, and plot-level stand summaries. Leave it off to keep the app focused on one-off DBH / Height measurements.")
        ) {
            Toggle(isOn: Binding(
                get: { settings.advancedMode },
                set: { settings.advancedMode = $0 })
            ) {
                Label("Advanced mode", systemImage: "gear.badge")
            }
            .accessibilityIdentifier("settings.advancedMode")
        }
    }

    private var logRuleSection: some View {
        Section(
            header: Text("Log rule"),
            footer: Text("Determines board-foot volume from DBH + height. Scribner is the USFS Western default; Doyle dominates the Eastern US; International ¼″ is the most accurate but rarely used in practice.")
        ) {
            Picker("Log rule",
                   selection: Binding(
                    get: { settings.logRule },
                    set: { settings.logRule = $0 })
            ) {
                ForEach(LogRule.allCases, id: \.self) { r in
                    Text(r.displayName).tag(r)
                }
            }
            .accessibilityIdentifier("settings.logRule")
        }
    }

    private var unitsSection: some View {
        Section("Units") {
            Picker("Default units", selection: $unitSystem) {
                Text("Imperial").tag(UnitSystem.imperial)
                Text("Metric").tag(UnitSystem.metric)
            }
            .pickerStyle(.segmented)
            .onChange(of: unitSystem) { _, new in settings.unitSystem = new }
        }
    }

    private var calibrationSection: some View {
        Section(
            header: Text("Calibration"),
            footer: Text("Wall fit captures the LiDAR depth noise and bias; " +
                         "cylinder fit estimates a linear DBH correction. " +
                         "Run both before your first field pilot.")
        ) {
            NavigationLink("Run Calibration") { CalibrationScreen() }
                .accessibilityIdentifier("settings.calibrationLink")
        }
    }

    private var basemapSection: some View {
        Section(
            header: Text("Basemap tiles"),
            footer: Text("No tile provider ships by default. Paste an XYZ template that substitutes {z}/{x}/{y}. Confirm you've reviewed the provider's usage policy before downloading.")
        ) {
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

    private var backupSection: some View {
        Section(
            header: Text("Backup & Restore"),
            footer: Text("Backups include every project's Core Data, photos, and raw scans, packaged into a .tcproj file. Restore on this device or another.")
        ) {
            Button {
                backup.backupAllProjects()
            } label: {
                Label("Back up all projects", systemImage: "arrow.up.doc")
            }
            .disabled(backup.isBackingUp)
            .accessibilityIdentifier("settings.backupAll")

            #if os(iOS)
            Button {
                isPresentingImport = true
            } label: {
                Label("Restore from .tcproj…", systemImage: "arrow.down.doc")
            }
            .accessibilityIdentifier("settings.restore")
            #endif

            if !backup.recentBackups.isEmpty {
                ForEach(backup.recentBackups) { b in
                    Button {
                        backup.shareURL = b.url
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(b.url.lastPathComponent).font(.subheadline)
                            Text("\(ByteCountFormatter.string(fromByteCount: b.byteSize, countStyle: .file))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
    }

    private var analyticsSection: some View {
        Section(
            header: Text("Diagnostics"),
            footer: Text("All logs stay on this device. Export here if Forestix support asks you to.")
        ) {
            Button {
                backup.shareURL = ForestixLogger.currentLogURL
            } label: {
                Label("Export analytics log", systemImage: "square.and.arrow.up")
            }
            .accessibilityIdentifier("settings.exportLog")
            Button(role: .destructive) {
                ForestixLogger.clear()
            } label: {
                Label("Clear analytics log", systemImage: "trash")
            }
            .accessibilityIdentifier("settings.clearLog")
        }
    }

    private var dangerZoneSection: some View {
        Section(
            header: Text("Danger zone"),
            footer: Text("Two-step confirmation. Used for onboarding a new cruiser on a shared device.")
        ) {
            Button(role: .destructive) {
                isPresentingResetStep1 = true
            } label: {
                Label("Erase all Forestix data", systemImage: "xmark.octagon")
            }
            .accessibilityIdentifier("settings.reset")
        }
    }

    // MARK: - Destructive reset

    private func performFullReset() {
        do {
            // Delete every project through the repository; cascades to
            // stratum / design / planned / plot / tree rows by FK.
            for p in try environment.projectRepository.list() {
                try environment.projectRepository.delete(id: p.id)
            }
            // Wipe attachments + exports + backups + logs.
            let fm = FileManager.default
            if let docs = try? fm.url(for: .documentDirectory,
                                      in: .userDomainMask,
                                      appropriateFor: nil, create: false) {
                for sub in ["Attachments", "Exports", "Backups", "exports"] {
                    try? fm.removeItem(at: docs.appendingPathComponent(sub))
                }
            }
            ForestixLogger.clear()
        } catch {
            resetError = "Reset failed: \(error.localizedDescription). Some data may remain; try again or reinstall the app."
        }
    }
}

#if os(iOS)
private struct ShareURLWrapper: Identifiable {
    let url: URL
    var id: URL { url }
}

private struct ShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_: UIActivityViewController, context: Context) {}
}
#endif
