// Spec §3.1. Phase 1 shipped the plan-only buttons; Phase 6 adds the
// full-cruise export section (tree/plot/stand CSV, cruise GeoJSON,
// shapefile, PDF report, Export-All), with a progress bar fed by the
// FullCruiseExporter's progress callback.

import SwiftUI
import Models

public struct ExportScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: ExportViewModel

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue: ExportViewModel(project: project))
    }

    public var body: some View {
        List {
            Section("Full cruise export") {
                Button {
                    viewModel.exportAll()
                } label: {
                    HStack {
                        Label("Export all (PDF, CSV, GeoJSON, Shapefile)",
                              systemImage: "square.and.arrow.up.on.square")
                        if viewModel.isExporting { Spacer(); ProgressView() }
                    }
                }
                .accessibilityIdentifier("export.all")
                .disabled(viewModel.isExporting)

                if viewModel.isExporting || viewModel.progress > 0 {
                    VStack(alignment: .leading, spacing: 4) {
                        ProgressView(value: viewModel.progress)
                        Text(viewModel.progressLabel)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }

            Section("Individual formats") {
                Button("PDF report")          { viewModel.exportPDFReport() }
                    .accessibilityIdentifier("export.pdf")
                Button("Trees (CSV)")         { viewModel.exportTreesCSV() }
                    .accessibilityIdentifier("export.treesCsv")
                Button("Plots (CSV)")         { viewModel.exportPlotsCSV() }
                    .accessibilityIdentifier("export.plotsCsv")
                Button("Stand summary (CSV)") { viewModel.exportStandSummaryCSV() }
                    .accessibilityIdentifier("export.standCsv")
                Button("Cruise (GeoJSON)")    { viewModel.exportCruiseGeoJSON() }
                    .accessibilityIdentifier("export.cruiseGeojson")
                Button("Plot centres (Shapefile ZIP)") {
                    viewModel.exportShapefilePlots()
                }
                .accessibilityIdentifier("export.plotsShapefile")
            }

            Section("Plan exports") {
                Button("Planned plots CSV") { viewModel.exportCSV() }
                    .accessibilityIdentifier("export.plannedCsv")
                Button("Strata CSV")        { viewModel.exportStratumCSV() }
                    .accessibilityIdentifier("export.strataCsv")
                Button("Plan GeoJSON")      { viewModel.exportGeoJSON() }
                    .accessibilityIdentifier("export.geojson")
            }

            if let folder = viewModel.lastSessionFolder {
                Section("Last export folder") {
                    Button {
                        viewModel.shareURL = folder
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Open")
                            Text(folder.lastPathComponent)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            if !viewModel.exportedFiles.isEmpty {
                Section("Recent files") {
                    ForEach(viewModel.exportedFiles) { file in
                        Button {
                            viewModel.shareURL = file.url
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(file.displayName)
                                Text(file.url.lastPathComponent)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Export")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { viewModel.configure(with: environment) }
        #if os(iOS)
        .sheet(item: Binding(
            get: { viewModel.shareURL.map(ShareURL.init) },
            set: { viewModel.shareURL = $0?.url })
        ) { wrapper in
            ShareSheet(url: wrapper.url)
        }
        #endif
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
}

#if os(iOS)
private struct ShareURL: Identifiable {
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
