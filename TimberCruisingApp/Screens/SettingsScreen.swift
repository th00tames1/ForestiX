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
            countryRegionSection
            appearanceSection
            unitsSection
            // Board-foot log rules are North-America-only — hidden for the
            // metric countries, which never scale in board feet.
            if settings.country.usesLogRule {
                logRuleSection
            }
            developerSection
            if settings.developerMode {
                dbhMethodSection
            }
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

    private var countryRegionSection: some View {
        Section(
            header: Text("Country & region"),
            footer: Text("Sets your units, species list, and volume standard. The US uses board-foot log rules; elsewhere is cubic metres.")
        ) {
            Picker("Country",
                   selection: Binding(
                    get: { settings.country },
                    set: { newCountry in
                        settings.country = newCountry
                        settings.regionPickerSeen = true
                        // Selecting a country drives the unit system (makes the
                        // "Country sets your units" footer true). The manual
                        // Units toggle below stays an override the cruiser can
                        // still flip afterwards; keep its control in sync here.
                        settings.unitSystem = newCountry.defaultUnitSystem
                        unitSystem = newCountry.defaultUnitSystem
                        // Metric countries use a single implicit region.
                        if !newCountry.hasRegions { settings.region = nil }
                    })
            ) {
                ForEach(Country.allCases) { c in
                    Text(c.displayName).tag(c)
                }
            }
            .accessibilityIdentifier("settings.country")

            // Region row — US only (the country owns the 11-region map).
            if settings.country.hasRegions {
                Picker("Region",
                       selection: Binding(
                        get: { settings.region ?? .all },
                        set: { r in
                            settings.region = r
                            // Derive the US default log rule (West→Scribner,
                            // East→Doyle) so the cruiser never sets it by hand.
                            settings.logRule = r.defaultLogRule
                            settings.regionPickerSeen = true
                        })
                ) {
                    ForEach(settings.country.regions) { r in
                        Text(r.displayName).tag(r)
                    }
                }
                .accessibilityIdentifier("settings.region")
            }

            // Read-only volume standard, derived from the selection.
            HStack {
                Text("Volume standard")
                Spacer()
                Text(settings.country.volumeStandard.displayName)
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)
                    .multilineTextAlignment(.trailing)
            }
            .accessibilityIdentifier("settings.volumeStandard")
        }
    }

    // LOCKED strings/placement (Android matches): "Light" | "Dark"
    // segmented control, right after Region, no footer. Replaces the
    // retired sun/moon button on the map's top row.
    private var appearanceSection: some View {
        Section(header: Text("Appearance")) {
            Picker("Appearance",
                   selection: Binding(
                    get: { settings.appearance },
                    set: { settings.appearance = $0 })
            ) {
                Text("Light").tag("light")
                Text("Dark").tag("dark")
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("settings.appearance")
        }
    }

    private var developerSection: some View {
        Section {
            Toggle(isOn: Binding(
                get: { settings.developerMode },
                set: { settings.developerMode = $0 })
            ) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Developer / research mode")
                    Text("Overlay live measurement internals on the AR screens for the validation study.")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
            }
            .accessibilityIdentifier("settings.developerMode")
            if settings.developerMode {
                // Research CSV — the per-measurement diagnostic rows
                // (value, true value, error, distance, pitch/α, n, σ, tier)
                // the accuracy study analyses. Rows are appended by the
                // scan/distance screens whenever developer mode is on.
                HStack {
                    Text("Research CSV")
                    Spacer()
                    Text("\(ResearchLog.shared.rowCount()) rows")
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                Button {
                    backup.shareURL = ResearchLog.shared.fileURL
                } label: {
                    Label("Export research CSV", systemImage: "square.and.arrow.up")
                }
                .disabled(!ResearchLog.shared.hasData)
                .accessibilityIdentifier("settings.exportResearch")
                Button(role: .destructive) {
                    ResearchLog.shared.clear()
                } label: {
                    Label("Clear research CSV", systemImage: "trash")
                }
                .disabled(!ResearchLog.shared.hasData)

                // RAW-CAPTURE REPLAY — record the exact raw inputs each
                // measurement consumes so estimator code can be re-run on
                // stored field data offline. Off by default.
                Toggle(isOn: Binding(
                    get: { settings.rawCaptureEnabled },
                    set: { settings.rawCaptureEnabled = $0 })
                ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Record raw captures")
                        Text("Save each scan's raw depth and pose data so measurements can be re-run offline. Off by default.")
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
                .accessibilityIdentifier("settings.rawCaptureEnabled")
                NavigationLink {
                    RawCapturesScreen()
                } label: {
                    HStack {
                        Label("Raw captures", systemImage: "shippingbox")
                        Spacer()
                        Text(rawCaptureSummaryText)
                            .font(ForestixType.dataSmall)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
                .accessibilityIdentifier("settings.rawCaptures")
            }
        } header: {
            Text("Developer")
        }
    }

    /// "N · X MB" summary for the raw-captures row.
    private var rawCaptureSummaryText: String {
        let n = RawCaptureStore.count()
        let bytes = RawCaptureStore.totalSizeBytes()
        let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        return "\(n) · \(size)"
    }

    // Developer-only — normal users get the single blessed DBH path;
    // the algorithm picker rides below the Developer section and is
    // visible only while developer mode is on (same gating on Android).
    private var dbhMethodSection: some View {
        Section(
            header: Text("DBH algorithm"),
            footer: Text("Chord is the stable default for hand-held scans. Switch to Partial-arc circle fit for irregular trunks the silhouette under-reads.")
        ) {
            Picker("Method",
                   selection: Binding(
                    get: { settings.dbhMeasurementMethod },
                    set: { settings.dbhMeasurementMethod = $0 })
            ) {
                ForEach(DBHMeasurementMethod.allCases, id: \.self) { m in
                    Text(m.displayName).tag(m)
                }
            }
            .accessibilityIdentifier("settings.dbhMethod")
        }
    }

    private var logRuleSection: some View {
        Section(
            header: Text("Log rule"),
            footer: Text("Sets board-foot volume from DBH and height. Scribner (West), Doyle (East), or International ¼″ (most accurate).")
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
        Section(
            header: Text("Units"),
            footer: Text("Display DBH, height and distance in metric or imperial.")
        ) {
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
            footer: Text("Paste an XYZ template ({z}/{x}/{y}) to draw contour or forest-service tiles over the satellite base. It shows only after you confirm the provider's usage policy below.")
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
            footer: Text("Saves every project, photo, and scan to a .tcproj file you can restore on any device.")
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
                Label("Export diagnostic log", systemImage: "square.and.arrow.up")
            }
            .accessibilityIdentifier("settings.exportLog")
            Button(role: .destructive) {
                ForestixLogger.clear()
            } label: {
                Label("Clear diagnostic log", systemImage: "trash")
            }
            .accessibilityIdentifier("settings.clearLog")
        }
    }

    private var dangerZoneSection: some View {
        Section(
            header: Text("Danger zone"),
            footer: Text("Permanently erases every project on this device.")
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
