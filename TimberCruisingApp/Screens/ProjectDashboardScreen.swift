// Spec §3.1 REQ-PRJ-002/003/004. Project dashboard — strata list, planned-plot
// summary, entry points into CruiseDesign / PlotMap / Export / Settings.

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
            summarySection
            strataSection
            planSection
            toolsSection
        }
        .navigationTitle(viewModel.project.name)
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
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
                    Label("Import", systemImage: "square.and.arrow.down")
                }
                .accessibilityIdentifier("dashboard.importMenu")
            }
        }
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

    // MARK: - Sections

    private var summarySection: some View {
        Section("Summary") {
            LabeledContent("Units", value: viewModel.project.units.rawValue.capitalized)
            LabeledContent("Total area", value: formatAcres(viewModel.totalAcres))
            LabeledContent("Strata", value: "\(viewModel.strata.count)")
            LabeledContent("Planned plots", value: "\(viewModel.plannedPlots.count)")
        }
    }

    @ViewBuilder
    private var strataSection: some View {
        Section("Strata") {
            if viewModel.strata.isEmpty {
                Text("No strata yet. Import a GeoJSON or KML file to define boundaries.")
                    .foregroundStyle(.secondary)
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
            }
        }
    }

    private var planSection: some View {
        Section("Cruise Plan") {
            NavigationLink("Design cruise") {
                CruiseDesignScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.designCruise")
            NavigationLink("Plot map") {
                PlotMapScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.plotMap")
        }
    }

    private var toolsSection: some View {
        Section("Tools") {
            NavigationLink("Export plan") {
                ExportScreen(project: viewModel.project)
            }
            .accessibilityIdentifier("dashboard.export")
            NavigationLink("Settings") {
                SettingsScreen()
            }
            .accessibilityIdentifier("dashboard.settings")
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
