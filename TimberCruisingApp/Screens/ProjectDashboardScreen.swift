// Spec §3.1 REQ-PRJ-002/003/004. Project dashboard — strata list, planned-plot
// summary, entry points into CruiseDesign / PlotMap / Export / Settings.
//
// Phase 7.4 redesign: whole screen now reads top-to-bottom as a
// step-by-step guide (①→②→③→④). Each step shows its own friendly
// description and a primary action. The strata step supports both
// "draw on map" (no file needed) and legacy "import from file" paths.

import SwiftUI
import Models
#if canImport(UniformTypeIdentifiers)
import UniformTypeIdentifiers
#endif

public struct ProjectDashboardScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: ProjectDashboardViewModel
    @State private var isPresentingImporter = false
    @State private var importFormat: ProjectDashboardViewModel.ImportFormat = .geoJSON

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue: ProjectDashboardViewModel(project: project))
    }

    public var body: some View {
        List {
            gettingStartedSection
            summarySection
            strataSection
            planSection
            cruiseSection
            toolsSection
        }
        .navigationTitle(viewModel.project.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        #if canImport(UniformTypeIdentifiers)
        .fileImporter(
            isPresented: $isPresentingImporter,
            allowedContentTypes: allowedTypes,
            allowsMultipleSelection: false
        ) { result in
            guard case .success(let urls) = result, let url = urls.first else { return }
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            viewModel.importStrata(fileURL: url, format: importFormat)
        }
        #endif
        .task {
            viewModel.configure(with: environment)
            viewModel.refresh()
        }
        .alert("Something went wrong",
               isPresented: Binding(
                    get: { viewModel.errorMessage != nil },
                    set: { if !$0 { viewModel.errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    // MARK: - Getting started (progress guide)

    @ViewBuilder
    private var gettingStartedSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                Text("How this works")
                    .font(.headline)
                stepRow(n: 1, done: !viewModel.strata.isEmpty,
                        title: "Define strata",
                        hint: "Draw boundaries on the map, or import GeoJSON / KML.")
                stepRow(n: 2, done: viewModel.design != nil && !viewModel.plannedPlots.isEmpty,
                        title: "Design cruise + generate plots",
                        hint: "Pick plot size and spacing — sample plots are generated automatically.")
                stepRow(n: 3, done: false,
                        title: "Measure in the field (Go Cruise)",
                        hint: "Walk to each plot and measure trees one by one. DBH via LiDAR, height via AR.")
                stepRow(n: 4, done: false,
                        title: "Review + export",
                        hint: "Check stand statistics and export to PDF / CSV / GeoJSON.")
            }
            .padding(.vertical, 4)
        } header: {
            Text("Guide")
        }
    }

    private func stepRow(n: Int, done: Bool, title: String, hint: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                Circle()
                    .fill(done ? Color.green : Color.accentColor.opacity(0.15))
                    .frame(width: 28, height: 28)
                if done {
                    Image(systemName: "checkmark")
                        .font(.caption.bold())
                        .foregroundStyle(.white)
                } else {
                    Text("\(n)")
                        .font(.caption.bold())
                        .foregroundStyle(Color.accentColor)
                }
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.bold())
                Text(hint).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
    }

    // MARK: - Summary

    private var summarySection: some View {
        Section("Summary") {
            LabeledContent("Units", value: viewModel.project.units.rawValue.capitalized)
            LabeledContent("Total area", value: formatAcres(viewModel.totalAcres))
            LabeledContent("Strata", value: "\(viewModel.strata.count)")
            LabeledContent("Planned plots", value: "\(viewModel.plannedPlots.count)")
        }
    }

    // MARK: - Strata (step 1)

    @ViewBuilder
    private var strataSection: some View {
        Section {
            if viewModel.strata.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("No strata yet", systemImage: "map")
                        .font(.subheadline.bold())
                    Text("A stratum is a cutting block you want to measure. Draw corners on the map, or import a prepared GeoJSON / KML file.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)

                    NavigationLink {
                        StratumDrawScreen(project: viewModel.project)
                    } label: {
                        Label("Draw stratum on map", systemImage: "pencil.and.outline")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("dashboard.drawStratum")

                    Menu {
                        Button("Import GeoJSON") {
                            importFormat = .geoJSON
                            isPresentingImporter = true
                        }
                        Button("Import KML") {
                            importFormat = .kml
                            isPresentingImporter = true
                        }
                    } label: {
                        Label("Import from file", systemImage: "square.and.arrow.down")
                            .frame(maxWidth: .infinity, minHeight: 36)
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("dashboard.importMenu")
                }
                .padding(.vertical, 4)
            } else {
                ForEach(viewModel.strata) { stratum in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(stratum.name)
                            Text(formatAcres(Double(stratum.areaAcres)))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                    }
                }
                .onDelete { idx in
                    for i in idx { viewModel.delete(stratumId: viewModel.strata[i].id) }
                }
                NavigationLink {
                    StratumDrawScreen(project: viewModel.project)
                } label: {
                    Label("Draw new stratum", systemImage: "plus")
                }
                .accessibilityIdentifier("dashboard.drawStratumExtra")
            }
        } header: {
            Text("① Strata")
        } footer: {
            if !viewModel.strata.isEmpty {
                Text("Swipe left to delete. Area is computed automatically from lat/lon.")
            }
        }
    }

    // MARK: - Plan (step 2)

    private var planSection: some View {
        Section {
            NavigationLink {
                CruiseDesignScreen(project: viewModel.project)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Cruise design", systemImage: "ruler")
                    Text("Choose plot size + sampling method → plots are generated automatically")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityIdentifier("dashboard.designCruise")
            .disabled(viewModel.strata.isEmpty)

            NavigationLink {
                PlotMapScreen(project: viewModel.project)
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Label("Plot map", systemImage: "map.fill")
                    Text("Review generated plot locations")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .accessibilityIdentifier("dashboard.plotMap")
        } header: {
            Text("② Cruise plan")
        } footer: {
            if viewModel.strata.isEmpty {
                Text("Register at least one stratum in ① first.")
            }
        }
    }

    // MARK: - Cruise (step 3)

    @ViewBuilder
    private var cruiseSection: some View {
        Section {
            if let design = viewModel.design {
                NavigationLink {
                    CruiseFlowScreen(project: viewModel.project,
                                     design: design)
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Label("Go Cruise",
                              systemImage: "figure.walk.circle.fill")
                            .font(.body.bold())
                        Text("Navigate to plot → record center → AR boundary → add trees")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("dashboard.goCruise")
            } else {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "lock.circle")
                        .foregroundStyle(.secondary)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Finish ② first")
                            .font(.subheadline.bold())
                        Text("You need a saved cruise design and generated plots before field measurement can begin.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
        } header: {
            Text("③ Field measurement")
        }
    }

    // MARK: - Tools (step 4 + misc)

    private var toolsSection: some View {
        Section {
            NavigationLink("Pre-field checklist") {
                PreFieldChecklistScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.preFieldCheck")
            NavigationLink("Calibrate this project") {
                CalibrationScreen(
                    viewModel: CalibrationViewModel(),
                    project: viewModel.project,
                    projectRepo: environment.projectRepository)
            }
            .accessibilityIdentifier("dashboard.calibrateProject")
            NavigationLink("Export results (PDF · CSV · GeoJSON)") {
                ExportScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.export")
            NavigationLink("Settings") {
                SettingsScreen()
            }
            .accessibilityIdentifier("dashboard.settings")
        } header: {
            Text("④ Tools")
        } footer: {
            Text("Calibration only needs to be done once — it noticeably improves accuracy.")
        }
    }

    // MARK: - Formatting

    private func formatAcres(_ value: Double) -> String {
        String(format: "%.2f acres", value)
    }

    #if canImport(UniformTypeIdentifiers)
    private var allowedTypes: [UTType] {
        switch importFormat {
        case .geoJSON:
            return [
                UTType(filenameExtension: "geojson") ?? .json,
                .json
            ]
        case .kml:
            return [UTType(filenameExtension: "kml") ?? .xml, .xml]
        }
    }
    #endif
}
