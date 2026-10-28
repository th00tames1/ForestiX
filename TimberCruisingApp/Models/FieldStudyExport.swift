import Foundation
import CryptoKit
import Common

public struct FieldExportApplicationResult: Codable,Equatable,Sendable {
    public let id: String
    public let store: String
    public let kind: FieldStudyKind
    public let value: Double
    public let measurementId: String?
    public let deleted: Bool
    public init(id: String,store: String,kind: FieldStudyKind,value: Double,measurementId: String? = nil,deleted: Bool = false) {
        self.id = id; self.store = store; self.kind = kind; self.value = value
        self.measurementId = measurementId; self.deleted = deleted
    }
}
public struct FieldExportIssue: Codable,Equatable,Sendable {
    public let code: String
    public let path: String
    public let measurementId: String?
    public init(_ code: String,_ path: String,_ id: String? = nil) { self.code = code; self.path = path; measurementId = id }
}
public struct FieldExportFileProof: Codable,Equatable,Sendable {
    public let path: String
    public let bytes: UInt64
    public let sha256: String
}
public struct FieldStudyExportReport: Codable,Sendable {
    public let schema: Int
    public let exportId: String
    public let createdAt: String
    public let measurementCount: Int
    public let referenceCount: Int
    public let rawBundleCount: Int
    public let applicationResultCount: Int
    public let issues: [FieldExportIssue]
    public let integrityPassed: Bool
    public init(exportId: String,createdAt: String,measurementCount: Int,referenceCount: Int,rawBundleCount: Int,applicationResultCount: Int,issues: [FieldExportIssue]) {
        schema = 1; self.exportId = exportId; self.createdAt = createdAt; self.measurementCount = measurementCount; self.referenceCount = referenceCount
        self.rawBundleCount = rawBundleCount; self.applicationResultCount = applicationResultCount; self.issues = issues; integrityPassed = issues.isEmpty
    }
}
public struct FieldStudyExportArtifact: Sendable { public let url: URL; public let report: FieldStudyExportReport }

/// Fail closed on unreadable directories and symlinks. Never silently omit a
/// subtree or follow a link outside the application's selected source roots.
enum FieldExportFileTree {
    struct File: Equatable { let path: String; let url: URL; let bytes: UInt64; let inode: UInt64; let modified: Date }
    static func file(_ url: URL,path: String) throws -> File {
        let attr = try FileManager.default.attributesOfItem(atPath: url.path)
        guard attr[.type] as? FileAttributeType == .typeRegular,let size = attr[.size] as? NSNumber,
              let inode = attr[.systemFileNumber] as? NSNumber,let modified = attr[.modificationDate] as? Date else {
            throw FieldStudyError.invalid("export non-regular file: "+path)
        }
        return .init(path: path,url: url,bytes: size.uint64Value,inode: inode.uint64Value,modified: modified)
    }
    static func files(in root: URL) throws -> [File] {
        let fm = FileManager.default
        guard fm.fileExists(atPath: root.path) else { return [] }
        var result: [File] = []
        func walk(_ dir: URL,_ prefix: String) throws {
            let values = try dir.resourceValues(forKeys: [.isDirectoryKey,.isSymbolicLinkKey])
            guard values.isDirectory == true,values.isSymbolicLink != true else { throw FieldStudyError.invalid("export source directory") }
            for url in try fm.contentsOfDirectory(at: dir,includingPropertiesForKeys: nil).sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                let name = url.lastPathComponent
                guard !name.isEmpty,!name.contains("\\"),name != ".",name != ".." else { throw FieldStudyError.invalid("export file name") }
                let path = prefix+name,attr = try fm.attributesOfItem(atPath: url.path)
                if attr[.type] as? FileAttributeType == .typeDirectory { try walk(url,path+"/") }
                else {
                    guard attr[.type] as? FileAttributeType == .typeRegular,
                          let size = attr[.size] as? NSNumber,let inode = attr[.systemFileNumber] as? NSNumber,
                          let modified = attr[.modificationDate] as? Date else { throw FieldStudyError.invalid("export non-regular file: "+path) }
                    result.append(.init(path: path,url: url,bytes: size.uint64Value,inode: inode.uint64Value,modified: modified))
                }
            }
        }
        try walk(root,""); return result.sorted { $0.path < $1.path }
    }
}

/// Bounded-memory ZIP, exact UUID joins, all outcomes, no source mutation.
/// Metadata is snapshotted under the archive lock; raw changes during export
/// refuse the export rather than claiming a consistent corpus.
public enum FieldStudyExport {
    public static func build(archive: FieldStudyArchive,rawRoot: URL,results: [FieldExportApplicationResult],
                             applicationPayloads: [(String,Data)] = [],outputDirectory: URL,
                             applicationFiles: [(String,URL)] = [],
                             applicationDirectories: [(String,URL)] = [],
                             progress: ((String) throws -> Void)? = nil) throws -> FieldStudyExportArtifact {
        let fm = FileManager.default,id = UUID().uuidString
        for root in [archive.root,rawRoot]+applicationDirectories.map(\.1) {
            let source = root.resolvingSymlinksInPath().standardizedFileURL.path,destination = outputDirectory.resolvingSymlinksInPath().standardizedFileURL.path
            guard destination != source,!destination.hasPrefix(source+"/") else { throw FieldStudyError.invalid("export destination overlaps source") }
        }
        try fm.createDirectory(at: outputDirectory,withIntermediateDirectories: true)
        try? FieldExportRetention.prune(directory: outputDirectory)
        let snapshot = outputDirectory.appendingPathComponent("snapshot-"+id,isDirectory: true)
        let output = outputDirectory.appendingPathComponent("forestix-field-study-"+id+".zip")
        var succeeded = false
        defer { try? fm.removeItem(at: snapshot); if !succeeded { try? fm.removeItem(at: output) } }
        try archive.copyReadOnlySnapshot(to: snapshot)
        let metadata = try FieldExportFileTree.files(in: snapshot),raw = try FieldExportFileTree.files(in: rawRoot)
        let appDirectories = try applicationDirectories.map { name,url -> (String,URL,[FieldExportFileTree.File]) in
            guard !name.isEmpty,!name.contains("/"),!name.contains("\\"),name != ".",name != ".." else { throw FieldStudyError.invalid("application export directory name") }
            return (name,url,try FieldExportFileTree.files(in: url))
        }
        let appFiles = try applicationFiles.map { name,url in
            guard !name.isEmpty,!name.contains("/"),!name.contains("\\"),name != ".",name != ".." else { throw FieldStudyError.invalid("application export name") }
            return try FieldExportFileTree.file(url,path: name)
        }+appDirectories.flatMap { name,_,entries in entries.map { .init(path: name+"/"+$0.path,url: $0.url,bytes: $0.bytes,inode: $0.inode,modified: $0.modified) } }
        let writer = try ZipStreamWriter(url: output)
        var files: [FieldExportFileProof] = []
        var names = Set<String>()
        func add(_ name: String,_ data: Data) throws {
            guard names.insert(name).inserted else { throw FieldStudyError.conflict("duplicate export path") }
            try writer.add(name: name,data: data)
            files.append(.init(path: name,bytes: UInt64(data.count),sha256: hash(data)))
        }
        do {
            for (prefix,entries) in [("field-study/",metadata),("raw-captures/",raw),("application/",appFiles)] {
                for file in entries {
                    try Task.checkCancellation()
                    let name = prefix+file.path
                    guard names.insert(name).inserted else { throw FieldStudyError.conflict("duplicate export path") }
                    var sha = SHA256(),count: UInt64 = 0
                    try writer.add(name: name,fileURL: file.url) { bytes in sha.update(data: bytes); count += UInt64(bytes.count) }
                    guard count == file.bytes else { throw FieldStudyError.conflict("source changed during export") }
                    files.append(.init(path: name,bytes: count,sha256: sha.finalize().map { String(format: "%02x",$0) }.joined()))
                    try progress?(name)
                }
            }
            guard raw == (try FieldExportFileTree.files(in: rawRoot)) else { throw FieldStudyError.conflict("raw files changed during export; retry after recording finishes") }
            for (name,data) in applicationPayloads {
                guard !name.isEmpty,!name.contains("/"),!name.contains("\\"),name != ".",name != ".." else { throw FieldStudyError.invalid("application export name") }
                try add("application/"+name,data)
            }
            try add("application/result_index.json",FieldStudyJSON.encoder().encode(results))
            let inventory = Dictionary(uniqueKeysWithValues: files.map { ($0.path,$0) })
            var audited = try audit(metadata: metadata,raw: raw,proofs: inventory,results: results)
            for (_,data) in applicationPayloads { if let json = try? JSONSerialization.jsonObject(with: data) { checkPhotos(json,proofs: inventory,issues: &audited.issues) } }
            for archived in audited.replacements { if let data = archived.snapshot.payloadJSON.data(using: .utf8),let json = try? JSONSerialization.jsonObject(with: data) { checkPhotos(json,proofs: inventory,issues: &audited.issues) } }
            for archived in audited.inventoryReplacements { if let data = archived.snapshot.payloadJSON.data(using: .utf8),let json = try? JSONSerialization.jsonObject(with: data) { checkPhotos(json,proofs: inventory,issues: &audited.issues) } }
            var seenIssues = Set<String>()
            audited.issues = audited.issues.filter { seenIssues.insert($0.code+"\u{0}"+$0.path+"\u{0}"+($0.measurementId ?? "")).inserted }
                .sorted { ($0.code,$0.path,$0.measurementId ?? "") < ($1.code,$1.path,$1.measurementId ?? "") }
            if !audited.replacements.isEmpty { try add("application/replaced_result_index.json",FieldStudyJSON.encoder().encode(audited.replacements)) }
            if !audited.inventoryReplacements.isEmpty { try add("application/replaced_inventory_index.json",FieldStudyJSON.encoder().encode(audited.inventoryReplacements)) }
            let report = FieldStudyExportReport(exportId: id,createdAt: FieldStudyJSON.timestamp(),measurementCount: audited.records.count,
                referenceCount: audited.references.count,rawBundleCount: Set(raw.filter { $0.path.contains("/") }.map { $0.path.components(separatedBy: "/")[0] }).count,
                applicationResultCount: results.count,issues: audited.issues)
            try add("measurement_index.csv",Data(measurementCSV(audited.records,issues: audited.issues).utf8))
            try add("reference_index.csv",Data(referenceCSV(audited.references).utf8))
            try add("audit.json",FieldStudyJSON.encoder().encode(report))
            try add("README.txt",Data(readme.utf8))
            // Inventory covers every payload except this self-referential file.
            try writer.add(name: "file_inventory.json",data: FieldStudyJSON.encoder().encode(files))
            guard raw == (try FieldExportFileTree.files(in: rawRoot)) else { throw FieldStudyError.conflict("raw files changed during validation; retry") }
            guard appFiles == (try appFiles.map { try FieldExportFileTree.file($0.url,path: $0.path) }) else { throw FieldStudyError.conflict("application files changed during export; retry") }
            for (_,root,initial) in appDirectories where try FieldExportFileTree.files(in: root) != initial { throw FieldStudyError.conflict("application directory changed during export; retry") }
            try writer.finish()
            succeeded = true
            return .init(url: output,report: report)
        } catch {
            writer.cancel(); try? fm.removeItem(at: output); throw error
        }
    }
    private static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x",$0) }.joined() }
    private static func key(_ id: String) -> String { id.lowercased() }
    private struct TargetCatalog: Decodable { let schema: Int; let targets: [FieldStudyTarget] }
    private struct ReplacedResult: Encodable { let sourceMeasurementId: String; let storageState = "replaced_snapshot"; let snapshot: FieldQuickReplacementSnapshot }
    private struct ReplacedInventory: Encodable { let sourceMeasurementId: String; let storageState = "replaced_inventory_snapshot"; let snapshot: FieldInventoryReplacementSnapshot }
    private struct Audit { var records: [FieldMeasurementRecord] = []; var references: [FieldReferenceObservation] = []; var targets: [FieldStudyTarget] = []; var issues: [FieldExportIssue] = []; var replacements: [ReplacedResult] = []; var inventoryReplacements: [ReplacedInventory] = [] }
    private static func audit(metadata: [FieldExportFileTree.File],raw: [FieldExportFileTree.File],proofs: [String:FieldExportFileProof],results: [FieldExportApplicationResult]) throws -> Audit {
        var out = Audit()
        var completedSaves: [FieldResultSaveIntent] = []
        var bindings: [String:FieldProtocolBinding] = [:]
        for file in metadata where file.path.hasSuffix(".json") {
            guard file.bytes <= 64*1024*1024 else { out.issues.append(.init("metadata_too_large_to_parse","field-study/"+file.path)); continue }
            do {
                let data = try Data(contentsOf: file.url)
                if file.path.hasPrefix("measurements/") {
                    let r = try FieldStudyJSON.decoder().decode(FieldMeasurementRecord.self,from: data); try r.validate()
                    guard key((file.path as NSString).lastPathComponent) == key(r.id+".json") else { throw FieldStudyError.invalid("measurement file identity") }
                    out.records.append(r)
                } else if file.path.hasPrefix("protocol_bindings/") {
                    let b = try FieldStudyJSON.decoder().decode(FieldProtocolBinding.self,from: data);try b.validate()
                    guard file.path == "protocol_bindings/"+b.id+".json" else { throw FieldStudyError.invalid("protocol binding file identity") }
                    bindings[b.id] = b
                } else if file.path.hasPrefix("references/") {
                    let r = try FieldStudyJSON.decoder().decode(FieldReferenceObservation.self,from: data); try r.validate(developerMode: true)
                    guard key((file.path as NSString).lastPathComponent) == key(r.id+".json") else { throw FieldStudyError.invalid("reference file identity") }
                    out.references.append(r)
                } else if file.path.hasPrefix("imports/") {
                    let batch = try FieldStudyJSON.decoder().decode(FieldTargetImportBatch.self,from: data); try batch.validate(developerMode: true)
                    guard key((file.path as NSString).lastPathComponent) == key(batch.id+".json") else { throw FieldStudyError.invalid("import file identity") }
                    out.references += batch.references; out.targets += batch.targets
                } else if file.path == "targets.json" {
                    let catalog = try FieldStudyJSON.decoder().decode(TargetCatalog.self,from: data)
                    guard catalog.schema == 1 else { throw FieldStudyError.invalid("target catalog schema") }
                    try catalog.targets.forEach { try $0.validate() }; out.targets += catalog.targets
                } else if file.path == "configuration.json" {
                    try FieldStudyJSON.decoder().decode(FieldStudyConfiguration.self,from: data).validate()
                } else if file.path.hasPrefix("result-saves/") {
                    let intent = try FieldStudyJSON.decoder().decode(FieldResultSaveIntent.self,from: data); try intent.validate()
                    guard key((file.path as NSString).lastPathComponent) == key(intent.id+".json") else { throw FieldStudyError.invalid("Result save file UUID") }
                    if !intent.completed { out.issues.append(.init("unresolved_application_save","field-study/"+file.path,intent.id)) }
                    else { completedSaves.append(intent) }
                } else if file.path.hasPrefix("inventory-undos/") {
                    let u = try FieldInventoryUndoIntent.decode(data)
                    if u.phase != .complete { out.issues.append(.init("unresolved_inventory_undo","field-study/"+file.path)) }
                } else if file.path.hasPrefix("application-undos/") {
                    let u = try FieldStudyJSON.decoder().decode(FieldApplicationUndoBatch.self,from: data); try u.validate()
                    if !u.completed { out.issues.append(.init("unresolved_application_undo","field-study/"+file.path)) }
                }
            } catch { out.issues.append(.init("corrupt_metadata","field-study/"+file.path)) }
        }
        for intent in completedSaves where !intent.replacementSnapshots.isEmpty || intent.inventoryReplacement != nil {
            let record = out.records.filter { key($0.id) == key(intent.id) }
            if record.count == 1,let r = record.first,r.outcome == .result,
               r.selection == .accepted || r.selection == .superseded || r.wasApplicationUndone,
               r.resultStore == intent.expected.store,key(r.resultRecordId ?? "") == key(intent.expected.id),
               r.kind == intent.expected.kind,r.liveValue == intent.expected.value {
                if r.wasApplicationUndone { continue } // Kept as original metadata, not a current replacement proof.
                out.replacements += intent.replacementSnapshots.map { .init(sourceMeasurementId: intent.id,snapshot: $0) }
                if let inventory = intent.inventoryReplacement { out.inventoryReplacements.append(.init(sourceMeasurementId: intent.id,snapshot: inventory)) }
            } else { out.issues.append(.init("replacement_receipt_binding_mismatch","field-study/result-saves/"+intent.id+".json",intent.id)) }
        }
        for group in Dictionary(grouping: out.records,by: { key($0.id) }).values where group.count > 1 {
            out.issues.append(.init("duplicate_measurement_id","field-study/measurements",group[0].id))
        }
        for group in Dictionary(grouping: out.references,by: { key($0.id) }).values where group.count > 1 {
            out.issues.append(.init("duplicate_reference_id","field-study/references"))
        }
        if !out.records.isEmpty,!metadata.contains(where: { $0.path == "configuration.json" }) { out.issues.append(.init("configuration_missing","field-study/configuration.json")) }
        if Set(out.targets.map(\.id)).count != out.targets.count { out.issues.append(.init("duplicate_target_id","field-study/targets.json")) }
        for ref in out.references where ref.supersedesId != nil && !out.references.contains(where: { $0.id == ref.supersedesId }) {
            out.issues.append(.init("reference_parent_missing","field-study/references/"+ref.id+".json"))
        }
        let bundles = Dictionary(grouping: raw.filter { $0.path.contains("/") },by: { key($0.path.components(separatedBy: "/")[0]) })
        for file in raw where !file.path.contains("/") { out.issues.append(.init("unscoped_raw_file","raw-captures/"+file.path)) }
        var manifests: [String:[String:Any]] = [:]
        for (id,entries) in bundles {
            if UUID(uuidString: id) == nil { out.issues.append(.init("raw_bundle_id_invalid","raw-captures/"+id)) }
            let roots = Set(entries.map { $0.path.components(separatedBy: "/")[0] })
            if roots.count > 1 { out.issues.append(.init("duplicate_raw_bundle_id","raw-captures/"+id,id)); continue }
            guard let file = entries.first(where: { $0.path.components(separatedBy: "/").dropFirst().joined(separator: "/") == "manifest.json" }) else {
                out.issues.append(.init("raw_manifest_missing","raw-captures/"+id,id)); continue
            }
            guard file.bytes <= 64*1024*1024 else { out.issues.append(.init("raw_manifest_too_large_to_parse","raw-captures/"+file.path,id)); continue }
            let data = try Data(contentsOf: file.url)
            guard hash(data) == proofs["raw-captures/"+file.path]?.sha256 else { throw FieldStudyError.conflict("raw manifest changed during export") }
            guard let manifest = (try? JSONSerialization.jsonObject(with: data)) as? [String:Any] else {
                out.issues.append(.init("raw_manifest_corrupt","raw-captures/"+file.path,id)); continue
            }
            manifests[id] = manifest
            let kind = manifest["kind"] as? String
            let schema = (manifest["schema"] as? NSNumber)?.intValue
            if schema != 1 && schema != 2 || !["dbh","height"].contains(kind ?? "") || manifest["context"] as? [String:Any] == nil || manifest["settings"] as? [String:Any] == nil || manifest["result_live"] as? [String:Any] == nil || manifest[kind ?? ""] as? [String:Any] == nil {
                out.issues.append(.init("raw_manifest_incomplete_or_unsupported","raw-captures/"+file.path,id))
            }
            let folder = file.path.components(separatedBy: "/")[0]
            checkFiles(manifest,folder: folder,proofs: proofs,id: id,issues: &out.issues)
            if manifest["measurement_id"] == nil { out.issues.append(.init("legacy_raw_identity_unrecorded","raw-captures/"+file.path,id)) }
            else if key(manifest["measurement_id"] as? String ?? "") != id { out.issues.append(.init("raw_identity_mismatch","raw-captures/"+file.path,id)) }
            if let evidence = manifest["capture_evidence"] as? [String:Any],key(evidence["measurement_id"] as? String ?? "") != id {
                out.issues.append(.init("raw_evidence_identity_mismatch","raw-captures/"+file.path,id))
            }
            if !out.records.contains(where: { key($0.id) == id }) { out.issues.append(.init("raw_without_measurement","raw-captures/"+folder,id)) }
        }
        let refs = Dictionary(grouping: out.references,by: { key($0.id) })
        for record in out.records {
            let id = record.id,path = "field-study/measurements/"+id+".json"
            if record.outcome == .started { out.issues.append(.init("unfinished_attempt",path,id)) }
            if !record.protocolConforms { out.issues.append(.init("protocol_runtime_mismatch",path,id)) }
            if let binding = record.protocolBinding,bindings[binding.id] != binding {
                out.issues.append(.init("protocol_baseline_missing_or_mismatch",path,id))
            }
            if let target = record.targetId,!out.targets.contains(where: { $0.id == target }) { out.issues.append(.init("target_missing",path,id)) }
            for ref in record.referenceIds {
                guard let matching = refs[key(ref)],matching.count == 1 else { out.issues.append(.init("reference_missing_or_duplicate",path,id)); continue }
                if matching[0].targetId != record.targetId || matching[0].method.kind != record.kind {
                    out.issues.append(.init("reference_binding_mismatch",path,id))
                }
                if let frozen = record.measurementScope?.references.first(where: { $0.id == ref }),frozen != matching[0] {
                    out.issues.append(.init("reference_revision_mismatch",path,id))
                }
            }
            if record.rawStatus != .notRequested {
                if record.rawStatus != .saved { out.issues.append(.init("raw_status_"+record.rawStatus.rawValue,path,id)) }
                if let manifest = manifests[key(id)] {
                    if key(manifest["measurement_id"] as? String ?? "") != key(id) || manifest["kind"] as? String != record.kind.rawValue {
                        out.issues.append(.init("measurement_raw_binding_mismatch",path,id))
                    }
                    if let evidence = manifest["capture_evidence"] as? [String:Any] {
                        if record.outcome == .result {
                            let captured = (evidence["displayed_result"] as? [String:Any])?["value"] as? Double
                            if captured.flatMap({ v in record.liveValue.map { abs(v-$0) <= max(0.000001,abs($0)*0.000001) } }) != true {
                                out.issues.append(.init("raw_displayed_result_mismatch",path,id))
                            }
                        }
                        if record.configuration.preserveAiInputs,record.ai.inferenceRan,evidence["ai_inference"] as? [String:Any] == nil {
                            out.issues.append(.init("ai_input_unavailable",path,id))
                        }
                    } else { out.issues.append(.init("raw_evidence_unrecorded",path,id)) }
                } else { out.issues.append(.init("measurement_raw_missing_or_corrupt",path,id)) }
            }
            if record.selection == .accepted {
                let current = results.filter { $0.store == record.resultStore && key($0.id) == key(record.resultRecordId ?? "") && $0.kind == record.kind }
                let expected = FieldExportApplicationResult(id: record.resultRecordId ?? "",store: record.resultStore ?? "",kind: record.kind,value: record.liveValue ?? .nan,measurementId: id)
                let matching = FieldResultSaveIntent.proofCandidates(expected: expected,native: current,
                    archived: out.replacements.map(\.snapshot.result)+out.inventoryReplacements.flatMap(\.snapshot.results))
                if matching.count != 1 { out.issues.append(.init("application_result_missing_or_duplicate",path,id)) }
                else if matching[0].deleted || !matching[0].value.isFinite || record.liveValue.map({ abs($0-matching[0].value) > max(0.000001,abs($0)*0.000001) }) != false {
                    out.issues.append(.init("application_result_mismatch",path,id))
                } else if matching[0].measurementId == nil {
                    out.issues.append(.init("application_measurement_identity_unrecorded",path,id))
                } else if key(matching[0].measurementId ?? "") != key(id) {
                    out.issues.append(.init("application_measurement_identity_mismatch",path,id))
                }
            }
        }
        for result in results {
            if let measurement = result.measurementId {
                let matching = out.records.filter { key($0.id) == key(measurement) && $0.kind == result.kind && $0.resultStore == result.store && key($0.resultRecordId ?? "") == key(result.id) }
                if matching.count != 1 { out.issues.append(.init("application_without_measurement","application/result_index.json",measurement)) }
            } else if result.store == "quick_history" {
                out.issues.append(.init("application_identity_unrecorded","application/result_index.json"))
            } else if !result.deleted,!out.records.contains(where: { $0.selection == .accepted && $0.resultStore == result.store && key($0.resultRecordId ?? "") == key(result.id) && $0.kind == result.kind }) {
                out.issues.append(.init("application_without_measurement","application/"+result.store+"/"+result.id))
            }
        }
        let accepted = out.records.filter { $0.selection == .accepted }
        for group in Dictionary(grouping: accepted,by: { ($0.resultStore ?? "")+":"+key($0.resultRecordId ?? "")+":"+$0.kind.rawValue }).values where group.count > 1 {
            for r in group where group.contains(where: { $0.id != r.id && FieldMeasurementReplacementScope.matches($0,r) }) {
                out.issues.append(.init("multiple_accepted_results","field-study/measurements/"+r.id+".json",r.id))
            }
        }
        out.records.sort { $0.id < $1.id }; out.references.sort { $0.id < $1.id }
        out.issues = Array(Set(out.issues.map { $0.code+"\u{0}"+$0.path+"\u{0}"+($0.measurementId ?? "") })).sorted().map {
            let p = $0.components(separatedBy: "\u{0}"); return .init(p[0],p[1],p[2].isEmpty ? nil : p[2])
        }
        return out
    }
    private static func checkPhotos(_ object: Any,proofs: [String:FieldExportFileProof],issues: inout [FieldExportIssue]) {
        if let list = object as? [Any] { for value in list { checkPhotos(value,proofs: proofs,issues: &issues) }; return }
        guard let row = object as? [String:Any] else { return }
        if let name = row["photoPath"] as? String,!name.isEmpty {
            let path = "application/measure-photos/"+name
            if name.contains("/") || name.contains("\\") || name == "." || name == ".." { issues.append(.init("unsafe_application_photo_reference",path)) }
            else if proofs[path] == nil { issues.append(.init("application_photo_missing",path)) }
        }
        for value in row.values { if value is [Any] || value is [String:Any] { checkPhotos(value,proofs: proofs,issues: &issues) } }
    }
    private static func checkFiles(_ object: Any,folder: String,proofs: [String:FieldExportFileProof],id: String,issues: inout [FieldExportIssue]) {
        if let array = object as? [Any] { for o in array { checkFiles(o,folder: folder,proofs: proofs,id: id,issues: &issues) }; return }
        guard let object = object as? [String:Any] else { return }
        for key in ["file","depth_file","rgb_file","confidence_file"] {
            if let name = object[key] as? String {
                let path = "raw-captures/"+folder+"/"+name
                guard !name.isEmpty,!name.contains("/"),!name.contains("\\"),name != ".",name != ".." else { issues.append(.init("unsafe_raw_file_reference",path,id)); continue }
                guard let proof = proofs[path] else { issues.append(.init("raw_file_missing",path,id)); continue }
                if key == "file",let bytes = object["bytes"] as? NSNumber,bytes.uint64Value != proof.bytes { issues.append(.init("raw_file_size_mismatch",path,id)) }
                if key == "file",let sha = object["sha256"] as? String,sha.lowercased() != proof.sha256 { issues.append(.init("raw_file_checksum_mismatch",path,id)) }
                if key == "depth_file" {
                    guard let width = object["width"] as? Int,let height = object["height"] as? Int,width > 0,height > 0,width <= 4096,height <= 4096,let format = object["format"] as? String else {
                        issues.append(.init("raw_depth_size_or_format_mismatch",path,id)); continue
                    }
                    let bytes = format == "f32m" ? 4 : format == "u16mm" ? 2 : 0
                    if bytes == 0 || proof.bytes != UInt64(width*height*bytes) { issues.append(.init("raw_depth_size_or_format_mismatch",path,id)) }
                }
                if key == "confidence_file" {
                    guard let width = object["width"] as? Int, let height = object["height"] as? Int,
                          (1...4096).contains(width), (1...4096).contains(height),
                          proof.bytes == UInt64(width*height) else {
                        issues.append(.init("raw_confidence_size_mismatch",path,id)); continue
                    }
                }
            }
        }
        for value in object.values { if value is [String:Any] || value is [Any] { checkFiles(value,folder: folder,proofs: proofs,id: id,issues: &issues) } }
    }
    private static func csv(_ rows: [[String]]) -> String {
        rows.map { $0.map { "\""+$0.replacingOccurrences(of: "\"",with: "\"\"")+"\"" }.joined(separator: ",") }.joined(separator: "\r\n")+"\r\n"
    }
    private static func measurementCSV(_ records: [FieldMeasurementRecord],issues: [FieldExportIssue]) -> String {
        let head = ["measurement_id","session_id","study_id","tree_id","target_id","operator_id","kind","unit","planned_repeat","attempt_number","outcome","selection","displayed_value","displayed_sigma","replay_value","result_record_id","result_store","raw_status","raw_bundle_id","ai_enabled","ai_inference_ran","ai_mask_detected","ai_boundary_applied","user_adjusted_boundary","protocol_version","protocol_fingerprint","runtime_fingerprint","measurement_scope_id","reference_ids","ui_flow_id","screen_to_result_seconds","screen_to_accepted_seconds","attempt_elapsed_seconds","failure_reason","integrity_issues"]
        var rows = [head+["displayed_sigma_unit","protocol_conforms","procedure_fingerprint","protocol_binding_id"]]
        for r in records {
            var row: [String] = [r.id,r.sessionId,r.studyId,r.treeId,r.targetId ?? "",r.operatorId,r.kind.rawValue,r.kind == .dbh ? "cm" : "m"]
            row += [String(r.plannedRepeat),String(r.attemptNumber),r.outcome.rawValue,r.selection.rawValue]
            row += [r.liveValue?.description ?? "",r.liveSigma?.description ?? "",r.replayValue?.description ?? "",r.resultRecordId ?? "",r.resultStore ?? "",r.rawStatus.rawValue,r.rawBundleId ?? ""]
            row += [String(r.ai.enabled),String(r.ai.inferenceRan),String(r.ai.maskDetected),String(r.ai.boundaryApplied),String(r.ai.userAdjustedBoundary)]
            row += [r.configuration.protocolVersion,r.configuration.protocolFingerprint,FieldFingerprint.settings(r.runtimeSettings),r.measurementScope?.id ?? "",r.referenceIds.joined(separator: "|"),r.uiFlowTiming?.id ?? ""]
            row += [r.uiFlowTiming?.atResult?.elapsedSeconds.description ?? "",r.uiFlowTiming?.atAccepted?.elapsedSeconds.description ?? "",r.timing?.attemptElapsedSeconds?.description ?? "",r.failureReason ?? ""]
            row.append(issues.filter { $0.measurementId?.lowercased() == r.id.lowercased() }.map(\.code).joined(separator: "|"))
            row += [r.kind == .dbh ? "mm" : "m",String(r.protocolConforms),r.protocolBinding?.procedureFingerprint ?? "",r.protocolBinding?.id ?? ""]
            rows.append(row)
        }
        return csv(rows)
    }
    private static func referenceCSV(_ refs: [FieldReferenceObservation]) -> String {
        csv([["reference_id","target_id","method_id","method_name","kind","role","input_value","input_unit","base_value","base_unit","operator_id","planned_repeat","reference_repeat","supersedes_id","recorded_at"]]+refs.map { r in
            [r.id,r.targetId,r.method.id,r.method.name,r.method.kind.rawValue,r.method.role.rawValue,String(r.inputValue),r.method.unit.rawValue,String(r.baseValue),r.method.kind == .dbh ? "cm" : "m",r.operatorId,r.plannedRepeat?.description ?? "",r.referenceRepeat.description,r.supersedesId ?? "",r.recordedAt]
        })
    }
    private static let readme = """
    ForestiX field-study export, schema 1
    All attempts and original metadata/raw bytes are retained, including failed,
    cancelled, superseded, Undo, corrupt and partial records. No repair is run.
    audit.json reports integrity issues; a ZIP is NOT proof of analysis readiness.
    measurement_index.csv: displayed/captured result, NOT a replacement replay.
    reference_index.csv includes every original GT/comparison revision and unit.
    field-study/ includes full protocol, scope, UI-flow and Undo snapshots.
    application/ contains application-store snapshots; formats are named by platform.
    Journal metadata is an atomic read-only snapshot. Application-store snapshots
    are collected separately, not a single cross-database transaction. The audit
    reports exact identity/value mismatches. Integrity passed is not a replay,
    accuracy, export re-import or field-readiness certification.
    Exact UUID foreign keys only. Legacy records are never joined by value/time/label.
    file_inventory.json gives SHA-256 and byte count for the exact bytes in every
    ZIP payload, except file_inventory.json itself. ZIP CRC also covers each entry.
    Raw changes during export cause failure; retry after recording has finished.
    DBH base unit cm; height base unit m; elapsed durations are seconds.
    """
}
