import Foundation
import Combine
import SwiftUI
import Models
import Sensors
import Persistence

/// App-level state; the archive owns durability and authorization, not SwiftUI bindings.
@MainActor
public final class FieldStudyStore: ObservableObject {
    #if canImport(UIKit)
    private let uiFlowLifecycle = FieldUIFlowApplicationLifecycle()
    #endif
    public let archive: FieldStudyArchive
    @Published public private(set) var configuration = FieldStudyConfiguration() {
        didSet { configurationRevision &+= 1; progressRevision &+= 1 }
    }
    @Published public private(set) var targets: [FieldStudyTarget] = [] {
        didSet { targetsRevision &+= 1; progressRevision &+= 1 }
    }
    @Published public private(set) var referenceObservations: [FieldReferenceObservation] = [] {
        didSet { referencesRevision &+= 1 }
    }
    @Published public private(set) var issues: [FieldArchiveIssue] = []
    @Published public private(set) var loaded = false
    @Published public private(set) var error: String?
    @Published public private(set) var selectedTargetId: String?
    @Published public private(set) var measurements: [FieldMeasurementRecord] = [] {
        didSet { progressRevision &+= 1 }
    }
    private var configurationRevision: UInt64 = 0
    private var targetsRevision: UInt64 = 0
    private var referencesRevision: UInt64 = 0
    private var progressRevision: UInt64 = 0
    private var cachedProgressRevision: UInt64?
    private var cachedOverlayTargets: [FieldStudyTarget] = []
    @Published public private(set) var modelFingerprint: String?
    private var journalTail: Task<Void,Never>?
    private var inventoryUndoRepository: (any TreeRepository)?
    private var resultSaveReader: (@Sendable (FieldResultSaveIntent) throws -> [FieldExportApplicationResult])?
    func attachResultSaveReader(_ reader: @escaping @Sendable (FieldResultSaveIntent) throws -> [FieldExportApplicationResult]) {
        resultSaveReader = reader
        if loaded { Task { await self.reload(recoverInterrupted: true) } }
    }
    private var reloadGeneration = 0
    @Published public private(set) var unresolvedUndoMeasurementIds: Set<String> = [] {
        didSet { progressRevision &+= 1 }
    }
    @Published public private(set) var inventoryUndoInventoryVerified = true {
        didSet { progressRevision &+= 1 }
    }
    func attachInventoryUndoRepository(_ repository: any TreeRepository) {
        inventoryUndoRepository = repository
        if loaded { Task { await self.reload() } }
    }
    func performInventoryUndo(_ undo: FieldCruiseUndoState) async throws {
        await awaitPendingWrites()
        guard let repository = inventoryUndoRepository else { throw FieldStudyError.missing("Inventory Undo repository") }
        let archive = self.archive
        do {
            try await Task.detached(priority: .utility) {
                try FieldInventoryUndoCoordinator(repository: repository,archive: archive).execute(id: undo.operationId,
                    saved: undo.saved,original: undo.original,measurementIds: undo.measurementIds)
            }.value
        } catch { await reload(); throw error }
        await reload()
    }
    public func awaitPendingWrites() async { await journalTail?.value }
    public init(root: URL? = nil) {
        let directory = root ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("field-study", isDirectory: true)
        archive = FieldStudyArchive(root: directory)
        Task { [weak self] in await self?.reload(recoverInterrupted: true) }
    }
    public static func ephemeral() -> FieldStudyStore {
        FieldStudyStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("forestix-study-preview-"+UUID().uuidString))
    }
    public var activeTargets: [FieldStudyTarget] { targets.filter { $0.studyId == configuration.studyId } }
    public var selectedTarget: FieldStudyTarget? { activeTargets.first { $0.id == selectedTargetId } }
    public var overlayTargets: [FieldStudyTarget] {
        if cachedProgressRevision != progressRevision {
            cachedOverlayTargets = FieldTargetProgress.visible(configuration: configuration,targets: targets,records: completionRecords)
            cachedProgressRevision = progressRevision
        }
        return cachedOverlayTargets
    }
    private var completionRecords: [FieldMeasurementRecord] {
        inventoryUndoInventoryVerified ? measurements.filter { !unresolvedUndoMeasurementIds.contains($0.id.lowercased()) } : []
    }
    public func selectTarget(_ id: String?) throws {
        guard id == nil || activeTargets.contains(where: { $0.id == id }) else { throw FieldStudyError.missing("selected target") }
        selectedTargetId = id
    }
    public func references(targetId: String, kind: FieldStudyKind, plannedRepeat: Int? = nil) -> [FieldReferenceObservation] {
        let superseded = Set(referenceObservations.compactMap(\.supersedesId))
        return referenceObservations.filter {
            $0.targetId == targetId && $0.method.kind == kind && !superseded.contains($0.id) &&
            ($0.plannedRepeat == nil || $0.plannedRepeat == (plannedRepeat ?? configuration.plannedRepeat))
        }.sorted { $0.method.name == $1.method.name ? $0.referenceRepeat < $1.referenceRepeat : $0.method.name < $1.method.name }
    }
    public func reload(recoverInterrupted: Bool = false) async {
        reloadGeneration += 1; let generation = reloadGeneration
        let versions = (configurationRevision,targetsRevision,referencesRevision)
        let baselineIds = Set(measurements.map(\.id))
        let archive = self.archive,repository = inventoryUndoRepository,saveReader = resultSaveReader
        do {
            let result = try await Task.detached(priority: .utility) {
                var undoIssues = repository.map { FieldInventoryUndoCoordinator(repository: $0,archive: archive).recover() } ?? []
                let intents = archive.scanInventoryUndoIntents()
                if repository == nil {
                    undoIssues += intents.values.filter { $0.phase != .complete }.map { FieldArchiveIssue(file: "inventory-undos/"+$0.id,reason: "inventory_repository_unavailable") }
                }
                let saves = archive.scanResultSaves()
                var saveIssues = saves.issues
                if recoverInterrupted {
                    var remaining = saves.values.filter { !$0.completed },failures: [String:String] = [:]
                    while !remaining.isEmpty {
                        var next: [FieldResultSaveIntent] = []
                        for intent in remaining {
                            do {
                                guard let saveReader else { throw FieldStudyError.missing("application_result_reader") }
                                let native = try saveReader(intent)
                                let proofs = FieldResultSaveIntent.usesNativeProof(expected: intent.expected,native: native) ? native :
                                    FieldResultSaveIntent.proofCandidates(expected: intent.expected,native: native,archived: try archive.completedReplacementProofs(for: intent))
                                try archive.recoverResultSave(id: intent.id,results: proofs); failures.removeValue(forKey: intent.id)
                            } catch { next.append(intent); failures[intent.id] = error.localizedDescription }
                        }
                        if next.count == remaining.count { break }; remaining = next
                    }
                    saveIssues += failures.map { .init(file: "result-saves/"+$0.key,reason: $0.value) }
                    do { try archive.reconcileCompletedReplacements() }
                    catch { saveIssues.append(.init(file: "result-saves",reason: error.localizedDescription)) }
                } else {
                    saveIssues += saves.values.filter { !$0.completed }.map { .init(file: "result-saves/"+$0.id,reason: "application_save_pending") }
                }
                if recoverInterrupted { _ = try archive.recoverInterruptedAttempts() }
                let c = try archive.loadConfiguration(); let targets = try archive.loadTargets()
                let refs = archive.scanReferences(); let records = archive.scanMeasurements()
                let model = try? YoloModelIdentity.sha256()
                let allIssues = (refs.issues+records.issues+undoIssues+intents.issues+saveIssues).reduce(into: [FieldArchiveIssue]()) { if !$0.contains($1) { $0.append($1) } }
                return (c,targets,refs.values,allIssues,records.values,model,Set(intents.values.flatMap { $0.unresolvedMeasurementIds }),intents.issues.isEmpty)
            }.value
            guard reloadGeneration == generation else { return }
            // A disk scan must not overwrite a mutation published while it
            // was awaiting I/O. Disk files remain the authoritative archive.
            if configurationRevision == versions.0 { configuration = result.0 }
            if targetsRevision == versions.1 { targets = result.1 }
            if referencesRevision == versions.2 { referenceObservations = result.2 }
            issues = result.3+issues.filter { $0.file.hasPrefix("raw-captures") && !result.3.contains($0) }
            measurements = FieldMeasurementSnapshot.merge(scanned: result.4,current: measurements,baselineIds: baselineIds)
            modelFingerprint = result.5; unresolvedUndoMeasurementIds = result.6
            inventoryUndoInventoryVerified = result.7
            if !activeTargets.contains(where: { $0.id == selectedTargetId }) { selectedTargetId = nil }
            error = nil; loaded = true
        } catch { if reloadGeneration == generation { self.error = error.localizedDescription; loaded = true } }
    }
    public func saveConfiguration(_ next: FieldStudyConfiguration, developerMode: Bool) async throws {
        let archive = self.archive
        try await Task.detached(priority: .utility) { try archive.saveConfiguration(next, developerMode: developerMode) }.value
        configuration = next
        if !activeTargets.contains(where: { $0.id == selectedTargetId }) { selectedTargetId = nil }
        error = nil
    }
    public func saveTargets(_ next: [FieldStudyTarget]) async throws {
        let archive = self.archive
        try await Task.detached(priority: .utility) { try archive.saveTargets(next) }.value
        targets = next
        if !activeTargets.contains(where: { $0.id == selectedTargetId }) { selectedTargetId = nil }
    }
    public func appendReference(_ reference: FieldReferenceObservation, developerMode: Bool) async throws {
        let archive = self.archive
        try await Task.detached(priority: .utility) { try archive.appendReference(reference, developerMode: developerMode) }.value
        referenceObservations.append(reference)
    }
    public func commitImport(_ batch: FieldTargetImportBatch, developerMode: Bool) async throws {
        let archive = self.archive
        let catalog = try await Task.detached(priority: .utility) {
            try archive.commitImport(batch, developerMode: developerMode)
            return (try archive.loadTargets(),archive.scanReferences())
        }.value
        targets = catalog.0; referenceObservations = catalog.1.values; issues = catalog.1.issues
    }
    func reflectMeasurement(_ record: FieldMeasurementRecord) {
        if let index = measurements.firstIndex(where: { $0.id == record.id }) { measurements[index] = record }
        else { measurements.append(record) }
        issues.removeAll { $0.file == "measurements/"+record.id }
    }
    /// One application-wide ordered writer, shared by chained/overlapping scan screens.
    func enqueueMeasurementWrite(id: String, allowMissing: Bool = false,
                                 operation: @escaping @Sendable () throws -> Void,
                                 completion: @escaping (Result<FieldMeasurementRecord?,Error>) -> Void) {
        let previous = journalTail; let archive = self.archive
        journalTail = Task {
            await previous?.value
            do {
                let record = try await Task.detached(priority: .utility) {
                    try operation()
                    do { return try archive.measurement(id: id) as FieldMeasurementRecord? }
                    catch FieldStudyError.missing where allowMissing { return nil }
                }.value
                if let record { reflectMeasurement(record) }
                completion(.success(record))
            } catch { completion(.failure(error)) }
        }
    }
    func reportMeasurementWriteFailure(id: String, reason: String) {
        let issue = FieldArchiveIssue(file: "measurements/"+id,reason: reason)
        if !issues.contains(issue) { issues.append(issue) }
    }
    func reportInfrastructureIssues(_ next: [FieldArchiveIssue]) {
        for issue in next where !issues.contains(issue) { issues.append(issue) }
    }
    public var completedTargetIds: Set<String> {
        FieldTargetProgress.completed(studyId: configuration.studyId,plannedRepeat: configuration.plannedRepeat,records: completionRecords)
    }
}

/// Observes the app-owned store without requiring another EnvironmentObject
/// in existing previews or measurement-host tests.
struct FieldStudyObservedContent<Content: View>: View {
    @ObservedObject var study: FieldStudyStore
    @ViewBuilder var content: (FieldStudyStore) -> Content
    var body: some View { content(study) }
}
private struct FieldStudyEnvironmentKey: EnvironmentKey { static let defaultValue: FieldStudyStore? = nil }
extension EnvironmentValues {
    var fieldStudyStore: FieldStudyStore? {
        get { self[FieldStudyEnvironmentKey.self] }
        set { self[FieldStudyEnvironmentKey.self] = newValue }
    }
}
