import SwiftUI
import Models
#if os(iOS)
import UniformTypeIdentifiers
import UIKit
#endif

struct SpeciesSettingsScreen: View {
    @ObservedObject var catalog: SpeciesCatalogStore
    var onSelect: ((SpeciesRecord) -> Void)?
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var adding = false
    @State private var commonName = ""
    @State private var scientificName = ""
    @State private var message: String?
    #if os(iOS)
    @State private var importing = false
    private struct SpeciesShare: Identifiable { let url: URL; var id: URL { url } }
    @State private var shareURL: SpeciesShare?
    #endif

    var body: some View {
        List {
            if let error = catalog.loadError {
                Section { Text(error); Button("Retry") { catalog.refresh() } }
            }
            ForEach(catalog.records.filter { $0.matches(search) }) { species in
                if let onSelect {
                    Button { onSelect(species); dismiss() } label: { row(species) }
                } else {
                    NavigationLink { detail(species) } label: { row(species) }
                }
            }
        }
        .navigationTitle("Species")
        .searchable(text: $search, prompt: "Common name, scientific name, or code")
        .toolbar {
            if onSelect != nil { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            ToolbarItem(placement: .primaryAction) {
                Button { commonName = ""; scientificName = ""; adding = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add species").accessibilityIdentifier("species.add")
            }
            #if os(iOS)
            ToolbarItem(placement: .secondaryAction) {
                Menu {
                    Button("Export registered species", systemImage: "square.and.arrow.up") { exportSpecies() }
                    Button("Import registered species", systemImage: "square.and.arrow.down") { importing = true }
                } label: { Image(systemName: "ellipsis.circle") }
            }
            #endif
        }
        .sheet(isPresented: $adding) {
            NavigationStack {
                Form {
                    TextField("Common name", text: $commonName)
                        .autocorrectionDisabled().accessibilityIdentifier("species.commonName")
                    TextField("Scientific name (optional)", text: $scientificName)
                        .autocorrectionDisabled().accessibilityIdentifier("species.scientificName")
                    Text("Volume is unavailable until a suitable equation is configured.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                .navigationTitle("Add species")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { adding = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") {
                            do {
                                let saved = try catalog.add(commonName: commonName, scientificName: scientificName)
                                adding = false
                                if let onSelect { onSelect(saved); dismiss() }
                            } catch { message = error.localizedDescription }
                        }.disabled(commonName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                            .accessibilityIdentifier("species.save")
                    }
                }
                .alert("Species", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                    Button("OK") { message = nil }
                } message: { Text(message ?? "") }
            }
        }
        .alert("Species", isPresented: Binding(get: { message != nil && !adding }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        } message: { Text(message ?? "") }
        #if os(iOS)
        .sheet(item: $shareURL) { SpeciesShareSheet(url: $0.url) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.json]) { result in
            do {
                let url = try result.get(), scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize,
                      size <= 8 * 1024 * 1024 else { throw SpeciesRegistrationError.invalidArchive }
                let count = try catalog.restore(Data(contentsOf: url))
                message = "Imported \(count) species. Existing entries were preserved."
            } catch { message = error.localizedDescription }
        }
        #endif
    }
    private func row(_ s: SpeciesRecord) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(s.commonName).foregroundStyle(.primary)
            if !s.scientificName.isEmpty { Text(s.scientificName).font(.caption).italic().foregroundStyle(.secondary) }
        }.accessibilityElement(children: .combine)
    }
    private func detail(_ s: SpeciesRecord) -> some View {
        Form {
            LabeledContent("Common name", value: s.commonName)
            if !s.scientificName.isEmpty { LabeledContent("Scientific name", value: s.scientificName) }
            LabeledContent("Species code", value: s.code).textSelection(.enabled)
            if s.code.hasPrefix("USR-") {
                LabeledContent("Volume equation", value: "Not configured")
            }
        }.navigationTitle("Species")
    }
    #if os(iOS)
    private func exportSpecies() {
        do {
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("SpeciesExports", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let url = directory.appendingPathComponent("forestix-species-\(UUID().uuidString).json")
            try catalog.archiveData().write(to: url, options: .atomic)
            shareURL = SpeciesShare(url: url)
        } catch { message = error.localizedDescription }
    }
    #endif
}

#if os(iOS)
private struct SpeciesShareSheet: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: [url], applicationActivities: nil)
    }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
#endif
