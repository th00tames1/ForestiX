// Settings surface — reorganized into eight ordered groups (iOS/Android parity):
//   1. Region & units       — Country, Region (US), Units, Log rule (US)
//   2. Display              — Light/Dark appearance
//   3. Calibration          — wall + cylinder fit wizard
//   4. Data & backup        — .tcproj backup/restore (per-project exports live on the project screen)
//   5. Advanced             — Basemap tiles overlay, behind a navigation row (ordinary
//                             field setup, so it sits ABOVE the developer group)
//   6. Developer & research — gated by developer mode: DBH algorithm, research CSV /
//                             diagnostic log EXPORTS, raw-capture recorder + Raw captures
//   7. Clear developer data — gated; the destructive Clears, kept in their own section
//                             away from the Export rows, each confirmed
//   8. Danger zone          — erase all data (always last)
// This is a re-grouping + gating pass: every binding, action, and string is the
// same one committed before; only where it lives changed.

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
    /// The quick-measure log — the readings ground-truth recovery writes into.
    @EnvironmentObject private var history: QuickMeasureHistory
    @StateObject private var backup = BackupViewModel()

    @State private var tileTemplate: String = ""
    @State private var providerLabel: String = ""
    @State private var unitSystem: UnitSystem = .imperial
    @State private var providerAck: Bool = false

    // Destructive flows
    @State private var isPresentingResetStep1 = false
    @State private var isPresentingResetStep2 = false
    @State private var resetError: String?

    // Developer-data clears — confirmed, and deliberately housed in their own
    // section away from the Export rows (a mis-tap used to wipe the corpus).
    @State private var isPresentingClearResearch = false
    @State private var isPresentingClearEvents = false
    /// Bumped after a clear so the row count / disabled state re-reads.
    @State private var storeRefresh = 0
    /// Ground-truth recovery: the run is in flight, and what the last one did.
    @State private var truthBackfillRunning = false
    @State private var truthBackfillResult: String?
    /// Ground-truth unit repair. The PLAN is held between the preview and the
    /// confirm so what the cruiser agreed to is what gets written — recomputing
    /// it after the tap would let the corpus move under the sentence they read.
    /// Research-CSV export: the run is in flight, and what the last one held
    /// back. The sentence stays on screen after the share sheet closes — the
    /// counts are the point, and a toast the cruiser dismissed is not a record.
    @State private var researchExportRunning = false
    @State private var researchExportResult: String?
    @State private var truthRepairRunning = false
    @State private var truthRepairPlan: TruthUnitRepair.Plan?
    @State private var truthRepairPreview: String?
    @State private var truthRepairResult: String?

    #if os(iOS)
    @State private var isPresentingImport = false
    #endif

    public init() {}

    public var body: some View {
        Form {
            regionAndUnitsSection
            displaySection
            measuringSection
            calibrationSection
            dataBackupSection
            // Basemap tiles is ordinary field setup, not developer tooling —
            // it sits ABOVE the developer group (Android matches).
            advancedSection
            developerSection
            clearDeveloperDataSection
            dangerZoneSection
        }
        .navigationTitle("Settings")
        // THE CONFIRM THE REPAIR MUST PASS. On the Form, not on the Section
        // that raises it: an alert presented from inside a Form row is not
        // reliably shown, and a repair whose confirm never appears would be
        // either a silent write or a dead button.
        .alert("Repair imperial ground truths",
               isPresented: Binding(
                get: { truthRepairPreview != nil },
                set: { if !$0 { truthRepairPreview = nil; truthRepairPlan = nil } })
        ) {
            Button("Cancel", role: .cancel) {
                truthRepairPreview = nil
                truthRepairPlan = nil
            }
            // Offered only when there IS something to write — a confirm on an
            // empty plan invites a tap that means nothing.
            if let plan = truthRepairPlan, !plan.isEmpty {
                Button("Repair") { applyTruthRepair(plan) }
            }
        } message: {
            Text(truthRepairPreview ?? "")
        }
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
        // Developer-data clears — each Clear is confirmed, and the Clears
        // themselves live in their own section, never under an Export row.
        //
        // ALERTS, not confirmationDialogs. A destructive confirmation is read
        // before an irreversible write, so it is centred and it looks the same
        // wherever the row that raised it happens to sit — an action sheet
        // raised from a Form row becomes a popover pinned to that row in a
        // regular size class, and a two-step reset whose first sheet appears
        // against the top edge and whose second appears somewhere else is the
        // worst possible place for a wandering dialog. Same rule as every
        // other delete in this app; see the field log's delete for the full
        // argument.
        .alert(
            "Clear research CSV?",
            isPresented: $isPresentingClearResearch
        ) {
            Button("Clear", role: .destructive) {
                ResearchLog.shared.clear()
                storeRefresh += 1
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes every research row on this device. Anything not already exported is gone for good.")
        }
        .alert(
            "Clear diagnostic log?",
            isPresented: $isPresentingClearEvents
        ) {
            Button("Clear", role: .destructive) {
                ForestixLogger.clear()
                storeRefresh += 1
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes every logged event on this device. Anything not already exported is gone for good.")
        }
        .alert(
            "Reset Forestix data?",
            isPresented: $isPresentingResetStep1
        ) {
            Button("Continue", role: .destructive) {
                isPresentingResetStep2 = true
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes every project, plot, tree, photo, and scan. Back up anything you need to keep first. This cannot be undone.")
        }
        .alert(
            "Are you absolutely sure?",
            isPresented: $isPresentingResetStep2
        ) {
            Button("Delete everything", role: .destructive) {
                performFullReset()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Last chance to back out. All local data will be erased.")
        }
    }

    // MARK: - 1. Region & units
    // Merges the former Country/Region, Units, and Log-rule sections into one
    // dependency-ordered group: the country drives units, species and the
    // volume standard; Units and the US-only Log rule are overrides below it.
    private var regionAndUnitsSection: some View {
        Section(
            header: Text("Region & units"),
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

            // The read-only "Volume standard" row lived here. Dropped as
            // redundant: it only restated what the Log rule picker below
            // already says. Country.volumeStandard and the volume logic
            // behind it are untouched.

            // Units override — display DBH, height and distance in metric or
            // imperial regardless of the country default above.
            Picker("Default units", selection: $unitSystem) {
                Text("Imperial").tag(UnitSystem.imperial)
                Text("Metric").tag(UnitSystem.metric)
            }
            .pickerStyle(.segmented)
            .onChange(of: unitSystem) { _, new in settings.unitSystem = new }

            // Log rule — US only, and an override of the region default derived
            // above (West→Scribner, East→Doyle). Board-foot is North-America
            // only, so the metric countries never show this row.
            if settings.country.usesLogRule {
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
                Text("Scribner (West), Doyle (East), or International ¼″ (most accurate).")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 2. Display
    // LOCKED strings/placement (Android matches): "Light" | "Dark" segmented
    // control. Pulled out of the region block into its own small group.
    private var displaySection: some View {
        Section(header: Text("Display")) {
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

    // MARK: - 2b. Measuring
    // FIELD REPORT F10 — the cruise tally chains diameter → height by
    // default. Cruisers who only want diameters turn it off here. Strings
    // and the stored key are identical on Android.
    private var measuringSection: some View {
        Section(
            header: Text("Measuring"),
            footer: Text("After you accept a diameter in cruise, Height opens for the same tree. Skip returns to the tally.")
        ) {
            Toggle("Measure height after diameter",
                   isOn: Binding(
                    get: { settings.measureHeightAfterDiameter },
                    set: { settings.measureHeightAfterDiameter = $0 }))
                .accessibilityIdentifier("settings.measureHeightAfterDiameter")
        }
    }

    // MARK: - 3. Calibration
    private var calibrationSection: some View {
        Section(
            header: Text("Calibration"),
            footer: Text("Scan a flat wall, then a round post you have " +
                         "measured. This tells Forestix how far off your " +
                         "phone's depth sensor runs, so diameters come out " +
                         "right. Do both before your first cruise.")
        ) {
            NavigationLink("Run Calibration") { CalibrationScreen() }
                .accessibilityIdentifier("settings.calibrationLink")
        }
    }

    // MARK: - 4. Data & backup
    // The existing backup/restore surface, relocated here. Per-project exports
    // (PDF/CSV/GeoJSON) live on each project's screen.
    private var dataBackupSection: some View {
        Section(
            header: Text("Data & backup"),
            footer: Text("Saves every project, photo, and scan to a .tcproj file you can restore on any device. Per-project exports (PDF, CSV, GeoJSON) live on each project's screen.")
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

    // MARK: - 6. Developer & research
    // Gated behind developer mode: the toggle is the only always-visible row.
    // When on, this is the single home for every dev/study tool — the DBH
    // algorithm picker (moved in from its own section), Research CSV, the
    // diagnostic log (gated here too, matching Android), and the raw-capture
    // recorder + Raw captures browser. The destructive Clears live in their
    // own section AFTER this one.
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
                // DBH algorithm — developer-only; normal users get the single
                // blessed DBH path. Moved in from its former standalone section.
                Picker("DBH algorithm",
                       selection: Binding(
                        get: { settings.dbhMeasurementMethod },
                        set: { settings.dbhMeasurementMethod = $0 })
                ) {
                    ForEach(DBHMeasurementMethod.allCases, id: \.self) { m in
                        Text(m.displayName).tag(m)
                    }
                }
                .accessibilityIdentifier("settings.dbhMethod")
                Text("Chord is the stable default for hand-held scans. Switch to Partial-arc circle fit for irregular trunks the silhouette under-reads.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)

                // Research CSV — the per-measurement diagnostic rows
                // (value, true value, error, distance, pitch/α, n, σ, tier)
                // the accuracy study analyses. Rows are appended by the
                // scan/distance screens whenever developer mode is on.
                //
                // Field fix: Clear used to sit DIRECTLY under Export for both
                // logs, so one mis-tap destroyed the research corpus. Only the
                // non-destructive Exports live here now; both Clears moved to
                // their own "Clear developer data" section below, behind a
                // confirmation.
                HStack {
                    Text("Research CSV")
                    Spacer()
                    Text("\(ResearchLog.shared.rowCount()) rows")
                        .foregroundStyle(ForestixPalette.textSecondary)
                }
                .id(storeRefresh)
                // THE EXPORT IS CLASSIFIED, NOT COPIED. The log is append-only,
                // so it still holds the row a retake replaced and the ground
                // truth a correction moved on from. `ResearchExport` splits it
                // into what the FIELD LOG shows and what it no longer does,
                // ships BOTH files in one archive, and returns the counts —
                // which are put on screen below, because a quiet filter is how
                // someone later concludes data went missing. Nothing on disk is
                // touched.
                Button {
                    runResearchExport()
                } label: {
                    Label(researchExportRunning
                          ? "Exporting…" : "Export research CSV",
                          systemImage: "square.and.arrow.up")
                }
                .disabled(!ResearchLog.shared.hasData || researchExportRunning)
                .accessibilityIdentifier("settings.exportResearch")
                if let result = researchExportResult {
                    Text(result)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .accessibilityIdentifier("settings.exportResearchResult")
                }

                // Diagnostic log — gated here to match Android (it used to be a
                // standalone, always-visible section). Share only; the Clear
                // lives in the separate section below.
                Button {
                    backup.shareURL = ForestixLogger.currentLogURL
                } label: {
                    Label("Export diagnostic log", systemImage: "square.and.arrow.up")
                }
                .accessibilityIdentifier("settings.exportLog")
                Text("All logs stay on this device. Export here if Forestix support asks you to.")
                    .font(ForestixType.caption)
                    .foregroundStyle(ForestixPalette.textSecondary)

                // RAW-CAPTURE REPLAY — record the exact raw inputs each
                // measurement consumes so estimator code can be re-run on
                // stored field data offline. Off by default.
                Toggle(isOn: Binding(
                    get: { settings.rawCaptureEnabled },
                    set: { settings.rawCaptureEnabled = $0 })
                ) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Record raw captures")
                        Text("Store raw depth, intrinsics, poses and calibration for each measurement so the estimator can be re-run offline. Off by default.")
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

                // GROUND-TRUTH RECOVERY. A truth typed for a CAPTURE (on a
                // scan screen before the truth moved onto the reading, or in
                // the raw-captures console at any time since) lives only in
                // that capture's manifest, so the field log shows a blank True
                // field for a tree the cruiser taped. This attaches those to
                // the readings they belong to.
                //
                // DELIBERATELY NOT A LAUNCH MIGRATION. It reads every manifest
                // on disk — a few hundred after two field days, each one a
                // whole JSON document with pose trails in it — and a blocking
                // pass on a cold morning is its own bug. It also has no end
                // date: the console can strand a new truth today, so a
                // run-once-at-version-N flag would be wrong by design. It is
                // idempotent, so running it again is free and changes nothing.
                Button {
                    runTruthBackfill()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Recover ground truths", systemImage: "arrow.uturn.down")
                        Text("Attach truths typed for a raw capture to the reading they belong to. Never overwrites a truth already on a reading.")
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
                .disabled(truthBackfillRunning)
                .accessibilityIdentifier("settings.recoverGroundTruths")
                if let result = truthBackfillResult {
                    // The corpus was just rewritten — say by how much, so the
                    // cruiser can check it against their own backup. A silent
                    // repair of research data is the wrong shape even when the
                    // arithmetic is right.
                    Text(result)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .accessibilityIdentifier("settings.recoverGroundTruthsResult")
                }

                // GROUND-TRUTH UNIT REPAIR. Before the truth field had a unit
                // toggle the cruiser typed inches and feet off the tape into a
                // field the app stored as centimetres and metres, so a stem
                // taped at 27 in went into the corpus as 27 cm. This multiplies
                // those back into the base they meant.
                //
                // PREVIEW, THEN WRITE. It rewrites research data in three
                // stores at once and the correction cannot be read back out of
                // the number afterwards, so the cruiser sees the counts and
                // worked examples first and nothing moves until they confirm.
                //
                // Like the recovery above it is an action rather than a launch
                // migration, and for the same reason: it reads every manifest
                // on disk. It is one-shot by construction, not by a version
                // flag — repairing a truth records the unit it was typed in,
                // which is the very marker that selects an unrepaired one.
                Button {
                    previewTruthRepair()
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Repair imperial ground truths",
                              systemImage: "ruler")
                        Text("Ground truths typed before the unit toggle were stored as if the digits were metric. This re-bases them — inches to centimetres, feet to metres. A truth that records the unit it was typed in is never touched.")
                            .font(ForestixType.caption)
                            .foregroundStyle(ForestixPalette.textSecondary)
                    }
                }
                .disabled(truthRepairRunning)
                .accessibilityIdentifier("settings.repairTruthUnits")
                if let result = truthRepairResult {
                    // Same reason the recovery says what it did: research data
                    // moved, so say by how much and where the full list is.
                    Text(result)
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textSecondary)
                        .accessibilityIdentifier("settings.repairTruthUnitsResult")
                }
            }
        } header: {
            Text("Developer & research")
        }
    }

    /// Read all three stores and show what WOULD change. Writes nothing.
    ///
    /// Detached for the same reason the recovery pass is: it parses every
    /// manifest on disk and the whole research CSV, and neither belongs on the
    /// main actor.
    private func previewTruthRepair() {
        guard !truthRepairRunning else { return }
        truthRepairRunning = true
        truthRepairResult = nil
        Task.detached(priority: .userInitiated) {
            let plan = await TruthUnitRepair.preview(history: history)
            let text = TruthUnitRepair.previewText(plan)
            await MainActor.run {
                truthRepairPlan = plan
                truthRepairPreview = text
                truthRepairRunning = false
            }
        }
    }

    /// Write the plan the cruiser just read. The plan is passed in rather than
    /// recomputed, so what they confirmed is what lands.
    private func applyTruthRepair(_ plan: TruthUnitRepair.Plan) {
        truthRepairPreview = nil
        truthRepairPlan = nil
        truthRepairRunning = true
        Task.detached(priority: .userInitiated) {
            let result = await TruthUnitRepair.applyPlan(plan, history: history)
            let text = TruthUnitRepair.resultText(result)
            await MainActor.run {
                truthRepairResult = text
                truthRepairRunning = false
            }
        }
    }

    /// Classify the research log against the field log and hand the archive to
    /// the share sheet. Detached for the same reason the two repair passes
    /// beside it are: it parses the whole research CSV, which is a field
    /// season's worth of rows, and that does not belong on the main actor.
    ///
    /// The share sheet is only raised when a file was actually written — an
    /// empty log and a failed write both leave the sentence on screen and no
    /// sheet, rather than sharing a file that is not there.
    private func runResearchExport() {
        guard !researchExportRunning else { return }
        researchExportRunning = true
        researchExportResult = nil
        Task.detached(priority: .userInitiated) {
            let outcome = await ResearchExport.run(history: history)
            await MainActor.run {
                researchExportResult = outcome.message
                if let url = outcome.url { backup.shareURL = url }
                researchExportRunning = false
            }
        }
    }

    /// Off the main actor for the manifest sweep; the history write inside
    /// `TruthBackfill.run` hops back on its own, once.
    private func runTruthBackfill() {
        guard !truthBackfillRunning else { return }
        truthBackfillRunning = true
        truthBackfillResult = nil
        // Detached, like every other sweep over the raw-capture tree on this
        // screen's sibling console: the pass reads a few hundred manifests and
        // must not be on the main actor to do it.
        Task.detached(priority: .userInitiated) {
            let text = await TruthBackfill.run(history: history)
            await MainActor.run {
                truthBackfillResult = text
                truthBackfillRunning = false
            }
        }
    }

    /// "N · X MB · Y GB free" summary for the raw-captures row. Free space is
    /// shown here because a phone that runs out mid-plot stops recording, and
    /// there is no recovering the trees already walked past.
    ///
    /// The count is READABLE bundles. When the folder holds bundles whose
    /// manifest won't decode, the gap is called out here too ("N · 2 unreadable
    /// · …") — that class used to be missing from this number with nothing on
    /// screen to say so.
    private var rawCaptureSummaryText: String {
        let inv = RawCaptureStore.inventory()
        let bytes = RawCaptureStore.totalSizeBytes()
        let size = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
        var bits = ["\(inv.parsed)"]
        if inv.unparseable > 0 { bits.append("\(inv.unparseable) unreadable") }
        bits.append(size)
        bits.append(RawCaptureStore.freeSpaceText())
        return bits.joined(separator: " · ")
    }

    // MARK: - 7. Clear developer data (gated)
    // The two destructive Clears used to sit immediately under their own Export
    // rows, one mis-tap from wiping the research corpus. They live here now — a
    // separate section, away from every Export, each behind a confirmation.
    @ViewBuilder
    private var clearDeveloperDataSection: some View {
        if settings.developerMode {
            Section(
                header: Text("Clear developer data"),
                footer: Text("Clearing is permanent. Export first — the exported file is the only copy once these are cleared.")
            ) {
                Button(role: .destructive) {
                    isPresentingClearResearch = true
                } label: {
                    Label("Clear research CSV", systemImage: "trash")
                }
                .disabled(!ResearchLog.shared.hasData)
                .accessibilityIdentifier("settings.clearResearch")

                Button(role: .destructive) {
                    isPresentingClearEvents = true
                } label: {
                    Label("Clear diagnostic log", systemImage: "trash")
                }
                .accessibilityIdentifier("settings.clearLog")
            }
            .id(storeRefresh)
        }
    }

    // MARK: - 5. Advanced
    // The default satellite basemap works with nothing set, so the custom XYZ
    // overlay controls sit behind a navigation row here rather than on the
    // main list. Ordinary field setup, so it sits ABOVE the developer group.
    private var advancedSection: some View {
        Section(header: Text("Advanced")) {
            NavigationLink {
                Form { basemapSection }
                    .navigationTitle("Basemap tiles")
                    #if os(iOS)
                    .navigationBarTitleDisplayMode(.inline)
                    #endif
            } label: {
                Text("Basemap tiles")
            }
            .accessibilityIdentifier("settings.basemapLink")
        }
    }

    private var basemapSection: some View {
        Section(
            header: Text("Basemap tiles"),
            footer: Text("Paste an XYZ template ({z}/{x}/{y}) to draw contour or forest-service tiles over the map base. It shows only after you confirm the provider's usage policy below.")
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
            // Draw/hide the configured overlay. It lives here beside the
            // template it belongs to — the map's own sheet keeps to map
            // type, boundary and offline maps.
            Toggle("Show overlay on the map",
                   isOn: Binding(get: { settings.overlayEnabled },
                                 set: { settings.overlayEnabled = $0 }))
                .disabled(settings.tileURLTemplate == nil)
                .accessibilityIdentifier("settings.overlayEnabled")
        }
    }

    // MARK: - 8. Danger zone (always last)
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
