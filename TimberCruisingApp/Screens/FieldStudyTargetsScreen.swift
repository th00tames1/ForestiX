import SwiftUI
import UniformTypeIdentifiers
import Models
import Geo

public struct FieldStudyTargetsScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var store: FieldStudyStore
    @State private var importer = false
    @State private var source: FieldTargetSource?
    @State private var encoding = "auto"
    @State private var busy = false
    @State private var message: String?
    @State private var addTarget = false
    public var body: some View {
        List {
            Section {
                Text("Study: \(store.configuration.studyId)")
                Picker("DBF encoding", selection: $encoding) {
                    Text("Automatic (.cpg)").tag("auto")
                    Text("UTF-8").tag("UTF-8"); Text("Windows-1252").tag("Windows-1252"); Text("CP949").tag("CP949")
                }
                Button("Import SHP ZIP / GeoJSON") { importer = true }
                Button("Add target") { addTarget = true }
                Text("SHP ZIP requires matching .shp, .dbf and WGS 84 .prj files. Select attribute fields before importing.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            if let message { Section { Text(message).foregroundStyle(.secondary) } }
            Section("Targets (\(store.activeTargets.count))") {
                ForEach(store.activeTargets.sorted { $0.treeId.localizedStandardCompare($1.treeId) == .orderedAscending }) { target in
                    NavigationLink {
                        FieldStudyTargetDetail(store: store,targetId: target.id)
                    } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(target.label)
                                Text(target.treeId).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            if target.id == store.selectedTargetId { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                            if target.latitude == nil { Image(systemName: "location.slash").foregroundStyle(.secondary) }
                        }
                    }
                    .accessibilityIdentifier("fieldStudy.target."+target.treeId)
                }
            }
        }
        .navigationTitle("Target catalog")
        .disabled(busy || !store.loaded || store.configuration.studyId.isEmpty)
        .overlay { if busy { ProgressView() } }
        .fileImporter(isPresented: $importer,allowedContentTypes: [.zip,.json,UTType(filenameExtension: "geojson") ?? .json]) { result in
            switch result { case .success(let url): load(url); case .failure(let e): message = e.localizedDescription }
        }
        .sheet(isPresented: Binding(get: { source != nil },set: { if !$0 { source = nil } })) {
            if let source { NavigationStack { FieldTargetMappingScreen(store: store,source: source) } }
        }
        .sheet(isPresented: $addTarget) { NavigationStack { FieldStudyManualTargetScreen(store: store) } }
    }
    private func load(_ url: URL) {
        busy = true; message = nil; let codec = encoding == "auto" ? nil : encoding
        Task {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() }; busy = false }
            do {
                source = try await Task.detached(priority: .userInitiated) {
                    let file = try FileHandle(forReadingFrom: url); defer { try? file.close() }
                    let data = try file.read(upToCount: FieldTargetFileReader.maximumBytes+1) ?? Data()
                    return try FieldTargetFileReader.read(data: data,filename: url.lastPathComponent,encodingOverride: codec)
                }.value
            } catch { message = error.localizedDescription }
        }
    }
}

private struct FieldTargetMappingScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: FieldStudyStore
    let source: FieldTargetSource
    @State private var mapping = FieldTargetMapping(treeIdField: "")
    @State private var busy = false
    @State private var message: String?
    var body: some View {
        Form {
            Section(source.name) {
                Text("\(source.features.count) targets · \(source.skippedRecords) null/deleted records excluded")
                field("Tree ID",value: $mapping.treeIdField,required: true)
                optionalField("Label",value: $mapping.labelField)
                optionalField("Altitude",value: $mapping.altitudeField)
                Picker("Altitude unit",selection: $mapping.altitudeUnit) { Text("m").tag(FieldReferenceUnit.m); Text("ft").tag(FieldReferenceUnit.ft) }
                optionalField("Horizontal uncertainty (m)",value: $mapping.accuracyField)
            }
            if settings.developerMode {
                Section("Reference attributes") {
                    ForEach(store.configuration.referenceMethods.filter(\.enabled)) { method in
                        field("\(method.name) (\(method.unit.rawValue))",value: Binding(
                            get: { mapping.referenceFields[method.id] ?? "" },
                            set: { if $0.isEmpty { mapping.referenceFields.removeValue(forKey: method.id) } else { mapping.referenceFields[method.id] = $0 } }))
                    }
                    Picker("Planned repetition",selection: Binding(get: { mapping.referencePlannedRepeat ?? 0 },set: { mapping.referencePlannedRepeat = $0 == 0 ? nil : $0 })) {
                        Text("All repetitions").tag(0)
                        ForEach(1...store.configuration.plannedRepetitions,id: \.self) { Text(String($0)).tag($0) }
                    }
                }
            }
            if let feature = source.features.first {
                Section("Preview · first feature") {
                    ForEach(source.fields,id: \.self) { name in LabeledContent(name,value: feature.value(name) ?? "—") }
                }
            }
            if let message { Section { Text(message) } }
            Section { Button("Import targets") { commit() }.disabled(mapping.treeIdField.isEmpty) }
        }
        .navigationTitle("Attribute mapping").disabled(busy)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) } }
    }
    private func optionalField(_ title: String,value: Binding<String?>) -> some View {
        field(title,value: Binding(get: { value.wrappedValue ?? "" },set: { value.wrappedValue = $0.isEmpty ? nil : $0 }))
    }
    private func field(_ title: String,value: Binding<String>,required: Bool = false) -> some View {
        Picker(title,selection: value) {
            Text(required ? "Select field" : "None").tag("")
            ForEach(source.fields,id: \.self) { Text($0).tag($0) }
        }
    }
    private func commit() {
        busy = true; message = nil
        Task {
            do {
                let config = store.configuration; let developer = settings.developerMode
                var safeMapping = mapping; if !developer { safeMapping.referenceFields = [:] }
                let finalMapping = safeMapping; let importSource = source
                let batch = try await Task.detached(priority: .userInitiated) {
                    try FieldTargetMaterializer.make(source: importSource,mapping: finalMapping,configuration: config,developerMode: developer)
                }.value
                try await store.commitImport(batch,developerMode: settings.developerMode); dismiss()
            } catch { message = error.localizedDescription }; busy = false
        }
    }
}

private struct FieldStudyManualTargetScreen: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: FieldStudyStore
    @State private var treeId = ""
    @State private var label = ""
    @State private var latitude = ""
    @State private var longitude = ""
    @State private var message: String?
    @State private var busy = false
    var body: some View {
        Form {
            TextField("Tree ID",text: $treeId); TextField("Label",text: $label)
            TextField("Latitude (optional)",text: $latitude).keyboardType(.numbersAndPunctuation)
            TextField("Longitude (optional)",text: $longitude).keyboardType(.numbersAndPunctuation)
            if let message { Text(message) }
            Button("Add target") {
                busy = true
                Task {
                    do {
                        let lat = latitude.trimmingCharacters(in: .whitespacesAndNewlines)
                        let lon = longitude.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard (lat.isEmpty && lon.isEmpty) || (Double(lat) != nil && Double(lon) != nil)
                        else { throw FieldStudyError.invalid("latitude/longitude pair") }
                        let id = treeId.trimmingCharacters(in: .whitespacesAndNewlines)
                        let target = FieldStudyTarget(studyId: store.configuration.studyId,treeId: id,label: label.isEmpty ? id : label,
                            latitude: lat.isEmpty ? nil : Double(lat),longitude: lon.isEmpty ? nil : Double(lon))
                        try await store.saveTargets(store.targets+[target]); dismiss()
                    } catch { message = error.localizedDescription }; busy = false
                }
            }.disabled(treeId.isEmpty)
            .accessibilityIdentifier("fieldStudy.addTarget")
        }.navigationTitle("New target").disabled(busy)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) } }
    }
}

struct FieldStudyTargetDetail: View {
    @EnvironmentObject private var settings: AppSettings
    @ObservedObject var store: FieldStudyStore
    let targetId: String
    var onMeasure: ((FieldStudyKind) -> Void)? = nil
    @State private var editing = false
    @State private var revise: FieldReferenceObservation?
    @State private var message: String?
    private var target: FieldStudyTarget? { store.targets.first { $0.id == targetId } }
    var body: some View {
        Form {
            if let target {
                Section("Target") {
                    LabeledContent("Tree ID",value: target.treeId)
                    if let lat = target.latitude, let lon = target.longitude { LabeledContent("WGS 84",value: String(format: "%.7f, %.7f",lat,lon)) }
                    Button(target.id == store.selectedTargetId ? "Selected for measurement" : "Select for measurement") {
                        do { try store.selectTarget(target.id); message = "Target selected." } catch { message = error.localizedDescription }
                    }
                }
                if let onMeasure {
                    Section("Measure log") {
                        Button("Measure DBH") { onMeasure(.dbh) }
                        Button("Measure height") { onMeasure(.height) }
                        Text(store.configuration.enabled ? "Target measurements are saved in Measure log." :
                            "Enable field-study recording in Settings to link target measurements.")
                            .font(.caption).foregroundStyle(.secondary)
                    }.disabled(!store.loaded || store.error != nil || !store.configuration.enabled || target.studyId != store.configuration.studyId)
                }
                if settings.developerMode {
                    ForEach([FieldStudyKind.dbh,.height],id: \.self) { kind in
                        Section(kind == .dbh ? "DBH references" : "Height references") {
                            ForEach(store.references(targetId: targetId,kind: kind)) { ref in
                                Button { revise = ref; editing = true } label: {
                                    LabeledContent("\(ref.method.name) · \(ref.referenceRepeat)",value: "\(ref.inputValue) \(ref.method.unit.rawValue)")
                                }
                            }
                        }
                    }
                    Section { Button("Add reference observation") { revise = nil; editing = true } }
                }
            }
            if let message { Section { Text(message) } }
        }.navigationTitle(target?.label ?? "Target")
        .sheet(isPresented: $editing) { NavigationStack { FieldReferenceEntryScreen(store: store,targetId: targetId,revision: revise) } }
    }
}

private struct FieldReferenceEntryScreen: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var store: FieldStudyStore
    let targetId: String
    let revision: FieldReferenceObservation?
    @State private var methodId = ""
    @State private var value = ""
    @State private var plannedRepeat = 0
    @State private var referenceRepeat = 1
    @State private var message: String?
    @State private var busy = false
    private var methods: [FieldReferenceMethod] { store.configuration.referenceMethods.filter(\.enabled) }
    var body: some View {
        Form {
            if settings.developerMode {
                Picker("Method",selection: $methodId) {
                    Text("Select method").tag("")
                    ForEach(methods) { Text("\($0.name) (\($0.unit.rawValue))").tag($0.id) }
                }.disabled(revision != nil)
                .accessibilityIdentifier("fieldStudy.referenceMethod")
                TextField("Reference value",text: $value).keyboardType(.decimalPad)
                Picker("Planned repetition",selection: $plannedRepeat) {
                    Text("All repetitions").tag(0)
                    ForEach(1...store.configuration.plannedRepetitions,id: \.self) { Text(String($0)).tag($0) }
                }.disabled(revision != nil)
                Stepper("Reference repetition: \(referenceRepeat)",value: $referenceRepeat,in: 1...999).disabled(revision != nil)
                Button(revision == nil ? "Save observation" : "Save revision") { save() }.disabled(methodId.isEmpty || value.isEmpty)
            }
            if let message { Text(message) }
        }.navigationTitle("Reference observation").disabled(busy)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() }.disabled(busy) } }
        .onAppear {
            if let revision { methodId = revision.method.id; value = String(revision.inputValue); plannedRepeat = revision.plannedRepeat ?? 0; referenceRepeat = revision.referenceRepeat }
        }
    }
    private func save() {
        busy = true
        Task {
            do {
                guard let number = Double(value), let method = revision?.method ?? methods.first(where: { $0.id == methodId })
                else { throw FieldStudyError.invalid("reference value or method") }
                let ref = FieldReferenceObservation(targetId: targetId,method: method,inputValue: number,
                    plannedRepeat: plannedRepeat == 0 ? nil : plannedRepeat,referenceRepeat: referenceRepeat,
                    operatorId: store.configuration.operatorId,supersedesId: revision?.id)
                try await store.appendReference(ref,developerMode: settings.developerMode); dismiss()
            } catch { message = error.localizedDescription }; busy = false
        }
    }
}
