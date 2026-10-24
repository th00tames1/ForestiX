import Foundation

public struct FieldArchiveIssue: Codable, Equatable, Sendable {
    public var file: String
    public var reason: String
    public init(file: String, reason: String) { self.file = file; self.reason = reason }
}
public struct FieldArchiveScan<Value> {
    public let values: [Value]
    public let issues: [FieldArchiveIssue]
    public var total: Int? { issues.contains(where: { $0.reason == "directory_unreadable" }) ? nil : values.count + issues.count }
}

/// Small atomic records, not whole-history rewrites. Mutations are serialized;
/// callers schedule disk operations off the UI/camera worker. Read errors remain visible.
public final class FieldStudyArchive: @unchecked Sendable {
    public let root: URL
    private let fm: FileManager
    // Multiple view/store instances must not race on the same application archive.
    private static let processLock = NSRecursiveLock()
    public init(root: URL, fileManager: FileManager = .default) { self.root = root; fm = fileManager }
    private func synchronized<T>(_ work: () throws -> T) rethrows -> T {
        Self.processLock.lock(); defer { Self.processLock.unlock() }; return try work()
    }
    /// Exact bytes, including corrupt/partial records and Undo receipts. No
    /// recovery or repair is performed by export. Destination must be new.
    public func copyReadOnlySnapshot(to destination: URL) throws {
        try synchronized {
            guard !fm.fileExists(atPath: destination.path),
                  destination.resolvingSymlinksInPath().standardizedFileURL != root.resolvingSymlinksInPath().standardizedFileURL,
                  !destination.resolvingSymlinksInPath().standardizedFileURL.path.hasPrefix(root.resolvingSymlinksInPath().standardizedFileURL.path+"/") else {
                throw FieldStudyError.invalid("export snapshot destination")
            }
            try fm.createDirectory(at: destination,withIntermediateDirectories: true)
            for file in try FieldExportFileTree.files(in: root) {
                let target = destination.appendingPathComponent(file.path)
                try fm.createDirectory(at: target.deletingLastPathComponent(),withIntermediateDirectories: true)
                try fm.copyItem(at: file.url,to: target)
            }
        }
    }
    private func write<T: Encodable>(_ value: T, to url: URL) throws {
        let data = try FieldStudyJSON.encoder().encode(value)
        try writeData(data,to: url)
    }
    private func writeData(_ data: Data,to url: URL) throws {
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        // Flush the completed atomic file before a later application-store
        // mutation may depend on it. Do not report a successful write on error.
        let handle = try FileHandle(forWritingTo: url); defer { try? handle.close() }
        try handle.synchronize()
    }
    private func read<T: Decodable>(_ type: T.Type, from url: URL) throws -> T {
        do { return try FieldStudyJSON.decoder().decode(type, from: Data(contentsOf: url)) }
        catch { throw FieldStudyError.corrupt(url.lastPathComponent) }
    }
    private func recordURL(_ id: String, folder: String) throws -> URL {
        guard let uuid = UUID(uuidString: id) else { throw FieldStudyError.invalid("record UUID") }
        return root.appendingPathComponent(folder, isDirectory: true).appendingPathComponent(uuid.uuidString+".json")
    }
    public func loadConfiguration() throws -> FieldStudyConfiguration {
        try synchronized {
            var directory: ObjCBool = false
            if fm.fileExists(atPath: root.path,isDirectory: &directory), !directory.boolValue {
                throw FieldStudyError.corrupt("archive root is not a directory")
            }
            let url = root.appendingPathComponent("configuration.json")
            guard fm.fileExists(atPath: url.path) else { return FieldStudyConfiguration() }
            let config = try read(FieldStudyConfiguration.self, from: url); try config.validate(); return config
        }
    }
    public func saveConfiguration(_ configuration: FieldStudyConfiguration, developerMode: Bool) throws {
        try synchronized {
            try configuration.validateTransition(from: loadConfiguration(), developerMode: developerMode)
            try write(configuration, to: root.appendingPathComponent("configuration.json"))
        }
    }
    /// Called before sensor work. An existing baseline is never replaced, even
    /// when it is unreadable. Each attempt embeds the same immutable proof.
    public func bindProtocol(_ candidate: FieldProtocolBinding) throws -> FieldProtocolBinding {
        try synchronized {
            try candidate.validate()
            let url = root.appendingPathComponent("protocol_bindings").appendingPathComponent(candidate.id+".json")
            if fm.fileExists(atPath: url.path) {
                let old = try read(FieldProtocolBinding.self,from: url); try old.validate()
                guard old == candidate else { throw FieldStudyError.conflict("Protocol settings changed. Restore the locked settings or use a new protocol version.") }
                return old
            }
            try write(candidate,to: url); return candidate
        }
    }
    private struct Catalog: Codable { var schema: Int; var targets: [FieldStudyTarget] }
    public func scanImports() -> FieldArchiveScan<FieldTargetImportBatch> {
        scan(folder: "imports", type: FieldTargetImportBatch.self) { value, name in
            try value.validate(developerMode: true)
            guard value.id.uppercased() == name else { throw FieldStudyError.invalid("import file ID mismatch") }
        }
    }
    public func loadTargets() throws -> [FieldStudyTarget] {
        try synchronized {
            let url = root.appendingPathComponent("targets.json")
            var targets: [FieldStudyTarget] = []
            if fm.fileExists(atPath: url.path) {
                let catalog = try read(Catalog.self, from: url)
                guard catalog.schema == 1 else { throw FieldStudyError.unsupportedSchema(catalog.schema) }
                targets = catalog.targets
            }
            let imports = scanImports()
            guard imports.issues.isEmpty else { throw FieldStudyError.corrupt("target import inventory") }
            targets += imports.values.flatMap(\.targets)
            try validateTargets(targets); return targets
        }
    }
    private func validateTargets(_ targets: [FieldStudyTarget]) throws {
        guard targets.count <= 10000, Set(targets.map(\.id)).count == targets.count
        else { throw FieldStudyError.invalid("target count or duplicate tree IDs") }
        try targets.forEach { try $0.validate() }
    }
    public func saveTargets(_ targets: [FieldStudyTarget]) throws {
        try synchronized {
            try validateTargets(targets)
            let imports = scanImports()
            guard imports.issues.isEmpty else { throw FieldStudyError.corrupt("target import inventory") }
            let imported = imports.values.flatMap(\.targets)
            let importedIds = Set(imported.map(\.id)); let nextById = Dictionary(uniqueKeysWithValues: targets.map { ($0.id,$0) })
            guard imported.allSatisfy({ nextById[$0.id] == $0 }) else { throw FieldStudyError.conflict("imported target coordinates are immutable") }
            try write(Catalog(schema: 1, targets: targets.filter { !importedIds.contains($0.id) }), to: root.appendingPathComponent("targets.json"))
        }
    }
    public func commitImport(_ batch: FieldTargetImportBatch, developerMode: Bool) throws {
        try synchronized {
            try batch.validate(developerMode: developerMode)
            let config = try loadConfiguration()
            guard batch.studyId == config.studyId, batch.protocolFingerprint == config.protocolFingerprint
            else { throw FieldStudyError.conflict("configuration changed during import") }
            let url = try recordURL(batch.id, folder: "imports")
            if fm.fileExists(atPath: url.path) {
                guard try read(FieldTargetImportBatch.self, from: url) == batch else { throw FieldStudyError.conflict("import UUID already exists") }
                return
            }
            let existing = try loadTargets(); try validateTargets(existing+batch.targets)
            let refs = scanReferences()
            guard refs.issues.isEmpty, Set((refs.values+batch.references).map(\.id)).count == refs.values.count+batch.references.count
            else { throw FieldStudyError.conflict("reference import inventory") }
            try write(batch, to: url)
        }
    }
    public func appendReference(_ observation: FieldReferenceObservation, developerMode: Bool) throws {
        try synchronized {
            try observation.validate(developerMode: developerMode)
            let url = try recordURL(observation.id, folder: "references")
            guard !fm.fileExists(atPath: url.path) else { throw FieldStudyError.conflict("reference UUID already exists") }
            guard try loadTargets().contains(where: { $0.id == observation.targetId })
            else { throw FieldStudyError.missing("reference target") }
            let inventory = scanReferences()
            guard inventory.issues.isEmpty else { throw FieldStudyError.corrupt("reference inventory") }
            guard !inventory.values.contains(where: { $0.id.caseInsensitiveCompare(observation.id) == .orderedSame })
            else { throw FieldStudyError.conflict("reference UUID already exists") }
            let superseded = Set(inventory.values.compactMap(\.supersedesId))
            let active = inventory.values.filter { !superseded.contains($0.id) }
            if observation.supersedesId == nil, active.contains(where: {
                $0.targetId == observation.targetId && $0.method.id == observation.method.id &&
                $0.plannedRepeat == observation.plannedRepeat && $0.referenceRepeat == observation.referenceRepeat
            }) { throw FieldStudyError.conflict("reference repeat already exists; supersede it or use a new repeat number") }
            if let parent = observation.supersedesId {
                guard let previous = inventory.values.first(where: { $0.id == parent }) else { throw FieldStudyError.missing("superseded reference") }
                guard previous.targetId == observation.targetId, previous.method.id == observation.method.id,
                      previous.method.kind == observation.method.kind, previous.plannedRepeat == observation.plannedRepeat,
                      previous.referenceRepeat == observation.referenceRepeat,
                      !superseded.contains(parent)
                else { throw FieldStudyError.conflict("reference supersession") }
            }
            try write(observation, to: url)
        }
    }
    private func scan<T: Decodable>(folder: String, type: T.Type,decode: ((URL) throws -> T)? = nil,
                                    validate: (T, String) throws -> Void) -> FieldArchiveScan<T> {
        synchronized {
            let dir = root.appendingPathComponent(folder, isDirectory: true)
            guard fm.fileExists(atPath: dir.path) else { return .init(values: [], issues: []) }
            let files: [URL]
            do { files = try fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent } }
            catch { return .init(values: [], issues: [.init(file: folder, reason: "directory_unreadable")]) }
            var values: [T] = []; var issues: [FieldArchiveIssue] = []
            for file in files {
                do {
                    let value: T
                    if let decode { value = try decode(file) } else { value = try read(type,from: file) }
                    try validate(value,file.deletingPathExtension().lastPathComponent); values.append(value)
                }
                catch { issues.append(.init(file: folder+"/"+file.lastPathComponent, reason: String(describing: error))) }
            }
            return .init(values: values, issues: issues)
        }
    }
    public func scanReferences() -> FieldArchiveScan<FieldReferenceObservation> {
        synchronized {
            let typed = scan(folder: "references", type: FieldReferenceObservation.self) { value, name in
                try value.validate(developerMode: true)
                guard value.id.uppercased() == name else { throw FieldStudyError.invalid("reference file ID mismatch") }
            }
            let imports = scanImports(); let all = typed.values+imports.values.flatMap(\.references)
            var issues = typed.issues+imports.issues
            if Set(all.map(\.id)).count != all.count { issues.append(.init(file: "references", reason: "duplicate_reference_id")) }
            return .init(values: all, issues: issues)
        }
    }
    public func scanMeasurements() -> FieldArchiveScan<FieldMeasurementRecord> {
        synchronized {
        do { try recoverApplicationUndos() } catch { return .init(values: [],issues: [.init(file: "application-undos",reason: String(describing: error))]) }
        return scan(folder: "measurements", type: FieldMeasurementRecord.self) { value, name in
            try value.validate()
            guard value.id.uppercased() == name else { throw FieldStudyError.invalid("measurement file ID mismatch") }
        }
        }
    }
    public func createMeasurement(_ record: FieldMeasurementRecord) throws {
        try synchronized {
            try record.validate()
            let url = try recordURL(record.id, folder: "measurements")
            guard record.revision == 0, !fm.fileExists(atPath: url.path)
            else { throw FieldStudyError.conflict("measurement UUID already exists") }
            try write(record, to: url)
        }
    }
    public func measurement(id: String) throws -> FieldMeasurementRecord {
        try synchronized {
            try recoverApplicationUndos()
            let url = try recordURL(id, folder: "measurements")
            guard fm.fileExists(atPath: url.path) else { throw FieldStudyError.missing("measurement") }
            let record = try read(FieldMeasurementRecord.self, from: url); try record.validate()
            guard record.id.caseInsensitiveCompare(id) == .orderedSame else { throw FieldStudyError.invalid("measurement file ID mismatch") }
            return record
        }
    }
    @discardableResult public func updateMeasurement(id: String, expectedRevision: Int? = nil,
                                                     _ update: (inout FieldMeasurementRecord) throws -> Void) throws -> FieldMeasurementRecord {
        try synchronized {
            let previous = try measurement(id: id)
            if let revision = expectedRevision, revision != previous.revision { throw FieldStudyError.conflict("stale measurement revision") }
            var next = previous; try update(&next)
            guard next.id == previous.id, next.sessionId == previous.sessionId, next.studyId == previous.studyId,
                  next.treeId == previous.treeId, next.targetId == previous.targetId, next.targetSnapshot == previous.targetSnapshot,
                  next.operatorId == previous.operatorId, next.kind == previous.kind, next.plannedRepeat == previous.plannedRepeat,
                  next.attemptNumber == previous.attemptNumber, next.startedAt == previous.startedAt,
                  next.configuration == previous.configuration, next.runtimeSettings == previous.runtimeSettings,
                  next.measurementScope == previous.measurementScope,
                  (previous.measurementScope == nil || next.referenceIds == previous.referenceIds),
                  Array(next.events.prefix(previous.events.count)) == previous.events,
                  (previous.outcome == .started || next.outcome == previous.outcome),
                  (previous.selection == .pending || next.selection == previous.selection ||
                   previous.selection == .accepted && next.selection == .superseded),
                  (previous.liveValue == nil || next.liveValue == previous.liveValue),
                  (previous.liveSigma == nil || next.liveSigma == previous.liveSigma),
                  (previous.outcome == .started || next.ai == previous.ai),
                  (previous.resultRecordId == nil || next.resultRecordId == previous.resultRecordId && next.resultStore == previous.resultStore),
                  (previous.selection == .pending || next.referenceIds == previous.referenceIds),
                  (previous.uiFlowTiming == nil || next.uiFlowTiming?.continues(previous.uiFlowTiming) == true)
            else { throw FieldStudyError.conflict("immutable capture metadata or terminal state") }
            next.revision = previous.revision+1; try next.validate()
            try write(next, to: recordURL(id, folder: "measurements")); return next
        }
    }
    /// Merge a screen-owned lifecycle snapshot without overwriting concurrent
    /// raw-writer evidence or deleting asynchronously appended events.
    public func mergeSessionSnapshot(_ snapshot: FieldMeasurementRecord) throws {
        try synchronized {
            let url = try recordURL(snapshot.id,folder: "measurements")
            if !fm.fileExists(atPath: url.path) { var first = snapshot; first.revision = 0; try createMeasurement(first); return }
            try updateMeasurement(id: snapshot.id) { next in
                guard next.sessionId == snapshot.sessionId, next.studyId == snapshot.studyId, next.treeId == snapshot.treeId,
                      next.targetId == snapshot.targetId, next.operatorId == snapshot.operatorId, next.kind == snapshot.kind,
                      next.plannedRepeat == snapshot.plannedRepeat, next.attemptNumber == snapshot.attemptNumber,
                      next.startedAt == snapshot.startedAt, next.targetSnapshot == snapshot.targetSnapshot,
                      next.configuration == snapshot.configuration, next.runtimeSettings == snapshot.runtimeSettings,
                      next.measurementScope == snapshot.measurementScope,next.referenceIds == snapshot.referenceIds
                else { throw FieldStudyError.conflict("capture snapshot identity") }
                next.outcome = snapshot.outcome
                if !((next.selection == .superseded || next.wasApplicationUndone) && snapshot.selection == .accepted) { next.selection = snapshot.selection }
                next.failureReason = snapshot.failureReason
                next.liveValue = snapshot.liveValue; next.liveSigma = snapshot.liveSigma; next.ai = snapshot.ai
                next.timing = snapshot.timing; next.referenceIds = snapshot.referenceIds
                next.uiFlowTiming = snapshot.uiFlowTiming ?? next.uiFlowTiming
                next.resultRecordId = snapshot.resultRecordId; next.resultStore = snapshot.resultStore
                if next.rawStatus == .pending || next.rawStatus == .notRequested {
                    next.rawStatus = snapshot.rawStatus; next.rawBundleId = snapshot.rawBundleId; next.rawFailureReason = snapshot.rawFailureReason
                }
                for event in snapshot.events where !next.events.contains(event) { next.events.append(event) }
            }
        }
    }
    public func updateRawEvidence(id: String, status: FieldRawStatus, reason: String?, replayValue: Double? = nil) throws {
        try updateMeasurement(id: id) { record in
            record.rawStatus = status; record.rawBundleId = id; record.rawFailureReason = reason
            if let replayValue,replayValue.isFinite,replayValue > 0 { record.replayValue = replayValue }
            record.events.append(.init(action: "raw_"+status.rawValue,detail: reason))
        }
    }
    @discardableResult public func supersedeAcceptedMeasurement(id: String, by replacement: String) throws -> Bool {
        try synchronized {
            guard id.lowercased() != replacement.lowercased() else { return false }
            guard fm.fileExists(atPath: try recordURL(id,folder: "measurements").path) else { return false }
            let previous = try measurement(id: id)
            guard previous.selection == .accepted else { return false }
            let newer = try measurement(id: replacement)
            guard newer.selection == .accepted,newer.outcome == .result,newer.resultRecordId != nil else {
                throw FieldStudyError.conflict("Replacement must be an accepted application result")
            }
            guard FieldMeasurementReplacementScope.matches(previous,newer) else {
                try updateMeasurement(id: id) { $0.events.append(.init(action: "application_row_replaced_retained",detail: replacement)) }
                return false
            }
            try updateMeasurement(id: id) { next in
                next.selection = .superseded; next.events.append(.init(action: "superseded",detail: replacement))
            }; return true
        }
    }
    /// Caller must first conditionally restore/delete the exact application row.
    /// General updateMeasurement deliberately cannot authorize these transitions.
    public func validateApplicationUndo(operationId: String,resultRecordId: String,measurementIds: [String]) throws {
        try synchronized {
            let inventory = scanMeasurements()
            guard inventory.issues.isEmpty else { throw FieldStudyError.corrupt("Undo measurement inventory") }
            _ = try FieldApplicationUndoBatch.prepare(id: operationId,resultRecordId: resultRecordId,measurementIds: measurementIds,records: inventory.values)
        }
    }
    public func undoApplicationMeasurements(operationId: String,resultRecordId: String,measurementIds: [String]) throws -> [FieldMeasurementRecord] {
        try synchronized {
            try recoverApplicationUndos()
            let url = try recordURL(operationId,folder: "application-undos")
            if fm.fileExists(atPath: url.path) {
                let old = try read(FieldApplicationUndoBatch.self,from: url); try old.validate()
                guard old.resultRecordId.caseInsensitiveCompare(resultRecordId) == .orderedSame,
                      Set(old.measurementIds.map { $0.lowercased() }) == Set(measurementIds.map { $0.lowercased() }) else { throw FieldStudyError.conflict("Undo operation UUID reused") }
                return try old.changes.map { try measurement(id: $0.after.id) }
            }
            let inventory = scanMeasurements()
            guard inventory.issues.isEmpty else { throw FieldStudyError.corrupt("Undo measurement inventory") }
            let batch = try FieldApplicationUndoBatch.prepare(id: operationId,resultRecordId: resultRecordId,measurementIds: measurementIds,records: inventory.values)
            try write(batch,to: url); try recoverApplicationUndos()
            return try batch.changes.map { try measurement(id: $0.after.id) }
        }
    }
    private func recoverApplicationUndos() throws {
        let dir = root.appendingPathComponent("application-undos",isDirectory: true)
        guard fm.fileExists(atPath: dir.path) else { return }
        let files = try fm.contentsOfDirectory(at: dir,includingPropertiesForKeys: nil).filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
        for url in files {
            var batch = try read(FieldApplicationUndoBatch.self,from: url); try batch.validate()
            guard batch.id.uppercased() == url.deletingPathExtension().lastPathComponent else { throw FieldStudyError.invalid("Undo file UUID") }
            if batch.completed { continue }
            // Validate the whole batch before writing any member. A concurrent
            // or corrupt record must not trigger a partial new adoption change.
            for change in batch.changes {
                let file = try recordURL(change.before.id,folder: "measurements")
                let current = try read(FieldMeasurementRecord.self,from: file)
                guard current == change.before || current == change.after else { throw FieldStudyError.conflict("Interrupted Undo journal changed") }
            }
            for change in batch.changes { try write(change.after,to: recordURL(change.after.id,folder: "measurements")) }
            batch.completed = true; try write(batch,to: url)
        }
    }
    public func scanInventoryUndoIntents() -> FieldArchiveScan<FieldInventoryUndoIntent> {
        scan(folder: "inventory-undos",type: FieldInventoryUndoIntent.self,decode: { try FieldInventoryUndoIntent.decode(Data(contentsOf: $0)) }) { value,name in
            try value.validate()
            guard value.id.uppercased() == name else { throw FieldStudyError.invalid("Inventory Undo file UUID") }
        }
    }
    public func prepareInventoryUndo(id: String,saved: Tree,original: Tree?,measurementIds: [String]) throws -> FieldInventoryUndoIntent {
        try synchronized {
            let candidate = try FieldInventoryUndoIntent(id: id,saved: saved,original: original,measurementIds: measurementIds)
            let url = try recordURL(id,folder: "inventory-undos")
            if fm.fileExists(atPath: url.path) {
                let existing = try FieldInventoryUndoIntent.decode(Data(contentsOf: url))
                guard existing.matches(id: id,saved: saved,original: original,measurementIds: measurementIds) else { throw FieldStudyError.conflict("Inventory Undo UUID reused") }
                return existing
            }
            let inventory = scanInventoryUndoIntents()
            guard inventory.issues.isEmpty else { throw FieldStudyError.corrupt("Inventory Undo intent inventory") }
            guard !inventory.values.contains(where: { $0.saved.id == saved.id && ($0.phase == .prepared || $0.phase == .applicationApplied) }) else {
                throw FieldStudyError.conflict("Another Undo is pending for this inventory row")
            }
            if !measurementIds.isEmpty { try validateApplicationUndo(operationId: id,resultRecordId: saved.id.uuidString,measurementIds: measurementIds) }
            try writeInventoryUndo(candidate,to: url); return candidate
        }
    }
    public func transitionInventoryUndo(id: String,to phase: FieldInventoryUndoPhase,reason: String? = nil) throws -> FieldInventoryUndoIntent {
        try synchronized {
            let url = try recordURL(id,folder: "inventory-undos")
            var value = try FieldInventoryUndoIntent.decode(Data(contentsOf: url))
            guard value.phase == phase || value.phase == .prepared && (phase == .applicationApplied || phase == .refused) ||
                  value.phase == .applicationApplied && phase == .complete else { throw FieldStudyError.conflict("Inventory Undo phase transition") }
            if value.phase == phase { return value }
            value.phase = phase; value.failureReason = reason; try value.validate(); try writeInventoryUndo(value,to: url); return value
        }
    }
    private func writeInventoryUndo(_ value: FieldInventoryUndoIntent,to url: URL) throws {
        let data = try value.encoded()
        try writeData(data,to: url)
    }
    public func scanResultSaves() -> FieldArchiveScan<FieldResultSaveIntent> {
        scan(folder: "result-saves",type: FieldResultSaveIntent.self) { value,name in
            try value.validate()
            guard value.id.uppercased() == name else { throw FieldStudyError.invalid("Result save file UUID") }
        }
    }
    public func prepareResultSave(_ value: FieldResultSaveIntent) throws {
        try synchronized {
            try value.validate(); let url = try recordURL(value.id,folder: "result-saves")
            if fm.fileExists(atPath: url.path) {
                let old = try read(FieldResultSaveIntent.self,from: url); try old.validate()
                guard old.matches(value) else { throw FieldStudyError.conflict("Prepared result save changed") }
                return
            }
            try write(value,to: url)
        }
    }
    /// Called after the ordered accepted snapshot, never before native save.
    public func completeResultSave(id: String,resultRecordId: String,resultStore: String) throws {
        try synchronized {
            let url = try recordURL(id,folder: "result-saves")
            guard fm.fileExists(atPath: url.path) else { return } // older callers
            var value = try read(FieldResultSaveIntent.self,from: url); try value.validate()
            let record = try measurement(id: id)
            guard value.expected.id.caseInsensitiveCompare(resultRecordId) == .orderedSame,value.expected.store == resultStore,
                  record.resultRecordId?.caseInsensitiveCompare(resultRecordId) == .orderedSame,record.resultStore == resultStore,
                  record.liveValue == value.expected.value,
                  FieldResultSaveIntent.capture(record) == FieldResultSaveIntent.capture(value.snapshot),
                  record.selection == .accepted || record.selection == .superseded || record.wasApplicationUndone else {
                throw FieldStudyError.conflict("Result save confirmation does not match journal")
            }
            if !value.completed {
                try applyQuickReplacementLineage(value)
                if record.selection == .accepted,!record.events.contains(where: { $0.action == "application_row_replaced_retained" }) {
                    for old in scanMeasurements().values where old.id != id && old.selection == .accepted &&
                        old.kind == value.expected.kind && old.resultStore == resultStore &&
                        old.resultRecordId?.caseInsensitiveCompare(resultRecordId) == .orderedSame &&
                        !old.events.contains(where: { $0.action == "application_row_replaced_retained" && $0.detail == id }) {
                        _ = try supersedeAcceptedMeasurement(id: old.id,by: id)
                    }
                }
                value.completed = true; try write(value,to: url)
            }
        }
    }
    private func applyQuickReplacementLineage(_ intent: FieldResultSaveIntent) throws {
        let sources = intent.replacementSnapshots.map(\.result)+(intent.inventoryReplacement?.results.filter { $0.kind == intent.expected.kind } ?? [])
        guard !sources.isEmpty else { return }
        let newer = try measurement(id: intent.id)
        guard intent.hasCommittedBinding(newer) else { return }
        for source in sources {
            guard let oldId = source.measurementId,UUID(uuidString: oldId) != nil,
                  fm.fileExists(atPath: try recordURL(oldId,folder: "measurements").path) else { continue }
            let old = try measurement(id: oldId)
            // An older torn save may be recovered from this original native
            // snapshot in the next pass; do not fabricate its lifecycle here.
            guard old.outcome == .result,old.resultRecordId != nil else { continue }
            guard old.kind == source.kind,old.resultStore == source.store,
                  old.resultRecordId?.caseInsensitiveCompare(source.id) == .orderedSame,
                  old.liveValue == source.value else { throw FieldStudyError.conflict("replacement journal identity mismatch") }
            guard old.selection == .accepted else { continue }
            let matches = FieldMeasurementReplacementScope.matches(old,newer)
            if !matches,old.events.contains(where: { $0.action == "application_row_replaced_retained" && $0.detail == intent.id }) { continue }
            _ = try updateMeasurement(id: oldId) { next in
                if matches { next.selection = .superseded }
                next.events.append(.init(action: matches ? "superseded" : "application_row_replaced_retained",detail: intent.id))
            }
        }
    }
    public func completedReplacementProofs(for intent: FieldResultSaveIntent) throws -> [FieldExportApplicationResult] {
        try synchronized {
            let inventory = scanResultSaves()
            guard inventory.issues.isEmpty else { throw FieldStudyError.corrupt("replacement receipt inventory") }
            let committed = try inventory.values.filter { value in
                guard value.completed,fm.fileExists(atPath: try recordURL(value.id,folder: "measurements").path) else { return false }
                return value.hasCommittedBinding(try measurement(id: value.id))
            }
            return committed.flatMap { $0.replacementSnapshots.map(\.result)+($0.inventoryReplacement?.results ?? []) }.filter {
                $0.id.caseInsensitiveCompare(intent.expected.id) == .orderedSame && $0.store == intent.expected.store && $0.kind == intent.expected.kind
            }
        }
    }
    public func reconcileCompletedReplacements() throws {
        try synchronized { for intent in scanResultSaves().values where intent.completed { try applyQuickReplacementLineage(intent) } }
    }
    private func reconcileInventoryReplacement(of intent: FieldResultSaveIntent) throws {
        guard intent.expected.store == "cruise" else { return }
        let scan = scanResultSaves(); guard scan.issues.isEmpty else { throw FieldStudyError.corrupt("inventory replacement receipts") }
        for newer in scan.values where newer.completed && newer.expected.kind == intent.expected.kind &&
            newer.inventoryReplacement?.results.contains(where: { $0.kind == intent.expected.kind && $0.measurementId?.caseInsensitiveCompare(intent.id) == .orderedSame }) == true {
            try applyQuickReplacementLineage(newer)
        }
    }
    /// Native rows are read by the platform adapter. No application row is
    /// created, edited or deleted by recovery. Missing accepted elapsed time
    /// stays nil: the restart instant is not the measurement completion time.
    public func recoverResultSave(id: String,results: [FieldExportApplicationResult]) throws {
        try synchronized {
            let url = try recordURL(id,folder: "result-saves")
            let intent = try read(FieldResultSaveIntent.self,from: url); try intent.validate()
            if intent.completed { return }
            try reconcileInventoryReplacement(of: intent)
            let resultURL = try recordURL(id,folder: "measurements")
            let current = fm.fileExists(atPath: resultURL.path) ? try measurement(id: id) : nil
            if let r = current,r.selection == .accepted || r.selection == .superseded || r.wasApplicationUndone {
                try completeResultSave(id: id,resultRecordId: intent.expected.id,resultStore: intent.expected.store); return
            }
            guard intent.proves(results) else { throw FieldStudyError.conflict(results.isEmpty ? "application_result_missing" : "application_result_identity_mismatch") }
            if let r = current {
                guard r.selection == .pending,r.outcome == .started || r.outcome == .result else { throw FieldStudyError.conflict("prepared attempt was retired") }
                if r.outcome == .result {
                    guard FieldResultSaveIntent.capture(r) == FieldResultSaveIntent.capture(intent.snapshot) else { throw FieldStudyError.conflict("prepared attempt changed") }
                } else { try mergeSessionSnapshot(intent.snapshot) }
            } else { try mergeSessionSnapshot(intent.snapshot) }
            _ = try updateMeasurement(id: id) { next in
                next.selection = .accepted; next.resultRecordId = intent.expected.id; next.resultStore = intent.expected.store
                next.events.append(.init(action: "result_save_recovered",detail: "acceptance_time_unrecorded"))
            }
            try reconcileInventoryReplacement(of: intent)
            try completeResultSave(id: id,resultRecordId: intent.expected.id,resultStore: intent.expected.store)
        }
    }
    /// A process restart cannot silently turn an unfinished attempt into a success.
    @discardableResult public func recoverInterruptedAttempts() throws -> Int {
        try synchronized {
            var count = 0
            let prepared = Set(scanResultSaves().values.filter { !$0.completed }.map { $0.id.lowercased() })
            for record in scanMeasurements().values where record.outcome == .started && !prepared.contains(record.id.lowercased()) {
                try updateMeasurement(id: record.id) { next in
                    next.outcome = .interrupted; next.failureReason = "process_interrupted"
                    next.events.append(.init(action: "interrupted", detail: "process_restart"))
                }; count += 1
            }
            return count
        }
    }
}
