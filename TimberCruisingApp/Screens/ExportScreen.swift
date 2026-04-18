// Spec §3.1 plan-only export for Phase 1. Phase 6 adds tree/plot CSV + PDF.

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
            Section("Plan exports") {
                Button("Planned plots CSV") { viewModel.exportCSV() }
                    .accessibilityIdentifier("export.plannedCsv")
                Button("Strata CSV") { viewModel.exportStratumCSV() }
                    .accessibilityIdentifier("export.strataCsv")
                Button("Plan GeoJSON") { viewModel.exportGeoJSON() }
                    .accessibilityIdentifier("export.geojson")
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
