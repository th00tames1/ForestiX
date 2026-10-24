import Foundation
import Common
import Combine
import SwiftUI
import Models
import Sensors

struct FieldQuickTargetRoute {
    let target: FieldStudyTarget
    let plotID: UUID
    let treeNumber: Int
    let treeName: String
    let speciesCode: String?

    /// Preparation is read-only. Apply a complete route only after the caller's
    /// cancellation/origin check; never change only the common target ID.
    @MainActor static func prepare(targetId: String,plotID: UUID?,study: FieldStudyStore,
                                   history: QuickMeasureHistory) async throws -> Self {
        await study.awaitPendingWrites(); try Task.checkCancellation()
        guard study.loaded,study.error == nil,study.configuration.enabled,
              let target = study.activeTargets.first(where: { $0.id == targetId }),
              let plot = plotID ?? history.activePlotID ?? history.defaultPlotID(),
              history.plots.contains(where: { $0.id == plot }) else {
            throw FieldStudyError.missing("Target, study recording or Measure log plot")
        }
        let fallback = history.defaultPlotID()
        let rows = history.entries.map { e in FieldApplicationReading(id: e.id.uuidString,
            kind: e.kind == .dbh ? .dbh : e.kind == .height ? .height : nil,
            plotId: (e.plotID ?? fallback)?.uuidString,treeNumber: e.treeNumber) }
        let binding = try FieldTargetApplicationBinding.resolve(target: target,plotId: plot.uuidString,
            readings: rows,records: study.measurements)
        return .init(target: target,plotID: plot,treeNumber: binding.treeNumber,
            treeName: binding.existing ? history.treeName(forTreeNumber: binding.treeNumber,plotID: plot) ?? target.treeId : target.treeId,
            speciesCode: binding.existing ? history.speciesCode(forTreeNumber: binding.treeNumber,plotID: plot) : nil)
    }
}

/// A cancelled/obsolete preparation cannot overwrite the UI state of a later
/// switch. The route action itself checks cancellation immediately before apply.
@MainActor final class FieldTargetSwitchCoordinator: ObservableObject {
    struct Choice { let targetId: String; let label: String }
    @Published private(set) var busy = false
    @Published private(set) var pending: Choice?
    @Published var error: String?
    private var pendingAction: (@MainActor () async throws -> Void)?
    private var task: Task<Void,Never>?
    private var generation = 0
    var presentationGeneration: Int { generation }
    func request(targetId: String,label: String,requiresConfirmation: Bool,
                 action: @escaping @MainActor () async throws -> Void) {
        guard !busy,pending == nil else { return }
        generation += 1
        error = nil
        if requiresConfirmation { pending = .init(targetId: targetId,label: label); pendingAction = action }
        else { begin(action) }
    }
    func confirm() {
        guard let action = pendingAction else { return }
        pending = nil; pendingAction = nil; begin(action)
    }
    func dismissConfirmation() { pending = nil; pendingAction = nil }
    func dismissPresentation(generation expected: Int) {
        guard generation == expected else { return }
        dismissConfirmation(); error = nil
    }
    func cancel() {
        generation += 1; task?.cancel(); task = nil
        pending = nil; pendingAction = nil; busy = false
    }
    func waitForCurrentSwitch() async { await task?.value }
    private func begin(_ action: @escaping @MainActor () async throws -> Void) {
        generation += 1; let token = generation; busy = true
        task = Task { @MainActor [weak self] in
            do { try Task.checkCancellation(); try await action() }
            catch is CancellationError { }
            catch { if self?.generation == token { self?.error = error.localizedDescription } }
            if self?.generation == token { self?.busy = false; self?.task = nil }
        }
    }
}

public struct FieldResultReceipt: Sendable {
    public var measurementId: String? = nil
    let prepareHandler: @MainActor @Sendable (UUID,String,Double,[FieldQuickReplacementSnapshot],FieldInventoryReplacementSnapshot?) throws -> Void
    let handler: @MainActor @Sendable (UUID,String,[UUID]) -> Void
    /// Must finish before the application result transaction starts.
    @MainActor public func prepare(recordId: UUID,store: String,value: Double,replacements: [FieldQuickReplacementSnapshot] = [],inventoryReplacement: FieldInventoryReplacementSnapshot? = nil) throws {
        try prepareHandler(recordId,store,value,replacements,inventoryReplacement)
    }
    /// Call only after the application result row has reached durable storage.
    @MainActor public func confirm(recordId: UUID, store: String, superseded: [UUID] = []) { handler(recordId,store,superseded) }
}

/// Screen-owned coordinator; identity is synchronous, journal IO is ordered on
/// a utility worker and remains owned until completion even if the view exits.
@MainActor
final class FieldCaptureCoordinator: ObservableObject {
    private let clock: () -> Double
    private let applicationVersion: () -> (String,String)
    private let uiFlowRegistry: FieldUIFlowRegistry
    private(set) var uiFlow: FieldUIFlowClock?
    init(clock: @escaping () -> Double = { FieldContinuousTime.seconds() },uiFlowRegistry: FieldUIFlowRegistry = .shared,
         applicationVersion: @escaping () -> (String,String) = {
             (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown",
              Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "unknown") }) {
        self.clock = clock; self.uiFlowRegistry = uiFlowRegistry; self.applicationVersion = applicationVersion
    }
    @Published private(set) var error: String?
    @Published private(set) var currentId: String?
    @Published private(set) var displayRecord: FieldMeasurementRecord?
    @Published private(set) var scopedTarget: FieldStudyTarget?
    @Published private(set) var usesGlobalTargetSelection = true
    private var session: FieldMeasurementSession?
    private var store: FieldStudyStore?
    private var kind: FieldStudyKind = .dbh
    private var settings: AppSettings?
    private var calibration = ProjectCalibration.identity
    private var context: [String: String] = [:]
    private var writeFailures: [String:String] = [:]
    private var recordedIds: Set<String> = []
    private var applicationContextKey: String?
    private var targetSessionKey: String?
    private var selectionObserver: AnyCancellable?
    private var wasHidden = false
    private var hasAppeared = false
    private var inheritedScope: FieldMeasurementScope?
    private(set) var measurementScope: FieldMeasurementScope?
    func previewReferences(kind: FieldStudyKind,developerMode: Bool) -> [FieldReferenceObservation] {
        guard store?.configuration.enabled == true,store?.configuration.showReferencesDuringMeasurement == true else { return [] }
        if let scope = measurementScope ?? inheritedScope {
            guard scope.configuration.studyId == store?.configuration.studyId else { return [] }
            return FieldMeasurementReferences.visible(scope: scope,kind: kind,developerMode: developerMode)
        }
        guard let store else { return [] }
        return FieldMeasurementReferences.preview(configuration: store.configuration,
            targetId: scopedTarget?.id ?? (usesGlobalTargetSelection ? store.selectedTargetId : nil),
            observations: store.referenceObservations,kind: kind,developerMode: developerMode)
    }

    func configure(store: FieldStudyStore?, kind: FieldStudyKind, settings: AppSettings,
                   calibration: ProjectCalibration, context: [String: String],targetOverride: FieldStudyTarget? = nil,useGlobalTarget: Bool = true,
                   uiFlow: FieldUIFlowClock? = nil,scopeOverride: FieldMeasurementScope? = nil) {
        let nextFlow = uiFlow?.kind == kind ? uiFlow! : (self.uiFlow?.kind == kind ? self.uiFlow! : FieldUIFlowClock(kind: kind))
        let flowChanged = self.uiFlow != nil && self.uiFlow !== nextFlow
        let targetScopeChanged = scopedTarget?.id != targetOverride?.id || usesGlobalTargetSelection != (useGlobalTarget && targetOverride == nil) ||
            (scopeOverride != nil && scopeOverride?.id != measurementScope?.id && scopeOverride?.id != inheritedScope?.id)
        if self.store !== store {
            selectionObserver = store?.$selectedTargetId.removeDuplicates().dropFirst().sink { [weak self] id in
                self?.selectedTargetChanged(id)
            }
        }
        self.store = store; self.kind = kind; self.settings = settings; self.calibration = calibration; self.context = context
        inheritedScope = scopeOverride
        if scopedTarget != targetOverride { scopedTarget = targetOverride }
        let usesGlobal = useGlobalTarget && targetOverride == nil
        if usesGlobalTargetSelection != usesGlobal { usesGlobalTargetSelection = usesGlobal }
        if session == nil { session = FieldMeasurementSession(now: clock()) }
        let key = context["application.plot_id",default: ""]+":"+context["application.tree_number",default: ""]
        if let previous = applicationContextKey, previous != key || (flowChanged && currentId != nil) || targetScopeChanged {
            let inheritedTarget = session?.current?.targetId
            close(reason: flowChanged ? "ui_flow_changed" : "next_application_tree")
            session = FieldMeasurementSession(now: clock()); currentId = nil
            displayRecord = nil
            targetSessionKey = nil; measurementScope = nil
            if let inheritedTarget,store?.selectedTargetId == inheritedTarget { try? store?.selectTarget(nil) }
        }
        applicationContextKey = key
        self.uiFlow = nextFlow
        if hasAppeared,flowChanged { uiFlowRegistry.track(nextFlow,now: clock()) }
    }
    private func targetKey(_ configuration: FieldStudyConfiguration, _ id: String?) -> String {
        configuration.studyId+":"+String(configuration.plannedRepeat)+":"+(id ?? "")
    }
    private func selectedTargetChanged(_ id: String?) {
        guard usesGlobalTargetSelection else { return }
        guard let store, let previous = targetSessionKey else { return }
        let next = targetKey(store.configuration,id)
        guard previous != next, session?.current?.outcome != .started else { return }
        close(reason: "target_changed"); session = FieldMeasurementSession(now: clock())
        currentId = nil; targetSessionKey = next
        measurementScope = nil
        displayRecord = nil
    }
    private var preparedProtocol: (runtime: [String: String],binding: FieldProtocolBinding?)?
    private func runtime(typed: Bool = false) -> [String: String] {
        var values = context
        values["field_session_version"] = "1"
        values["platform"] = "ios"
        let version = applicationVersion();values["app_version"] = version.0; values["app_build"] = version.1
        values["dbh_estimator_epoch"] = String(DBHCalibration.currentEpoch)
        values["dbh_method"] = settings?.dbhMeasurementMethod.rawValue ?? "unknown"
        values["capture_mode"] = settings?.dbhCaptureMode.rawValue ?? "single"
        values["capture_frame_count"] = String(settings?.dbhCaptureMode.frameCount(developerMode: settings?.developerMode ?? false) ?? 1)
        values["dbh_chord_algorithm"] = "silhouette"
        values["input_mode"] = typed ? "typed" : "sensor"
        values["depth_sampling_fraction"] = FieldFingerprint.number(BoundaryAlignment.samplingFraction)
        values["depth_sampling_statistic"] = "median"
        values["depth_surface_correction"] = "cylinder_median_v1"
        values["breast_height_guide_height_m"] = FieldFingerprint.number(settings?.breastHeightGuideHeight.meters ?? BreastHeightGuideHeight.meters137.meters)
        values["height_method"] = "vio_walkoff_tangent"
        values["height_procedure_version"] = "1"
        values["measurement_source"] = kind == .dbh ? "arkit_scene_depth" : (settings?.measurementSource.rawValue ?? "unknown")
        values["model_sha256"] = store?.modelFingerprint ?? "unavailable"
        values["calibration.depth_noise_mm"] = FieldFingerprint.number(Double(calibration.depthNoiseMm))
        values["calibration.alpha"] = FieldFingerprint.number(Double(calibration.dbhCorrectionAlpha))
        values["calibration.beta"] = FieldFingerprint.number(Double(calibration.dbhCorrectionBeta))
        values["calibration.epoch"] = String(calibration.dbhCalibrationEpoch)
        values["calibration.vio_drift_fraction"] = FieldFingerprint.number(Double(calibration.vioDriftFraction))
        values["calibration.depth_discontinuity_m"] = FieldFingerprint.number(Double(calibration.depthDiscontinuityM))
        return values
    }
    /// Separate from lifecycle events: a denied start must not replace an
    /// existing result or start the camera/estimator worker.
    func capturePermission(typed: Bool,continuing: Bool = false,calibration actual: ProjectCalibration? = nil,
                           dbhMethod: String? = nil,frameCount: Int? = nil) -> String? {
        var values = runtime(typed: typed)
        if let actual {
            values["calibration.depth_noise_mm"] = FieldFingerprint.number(Double(actual.depthNoiseMm))
            values["calibration.alpha"] = FieldFingerprint.number(Double(actual.dbhCorrectionAlpha))
            values["calibration.beta"] = FieldFingerprint.number(Double(actual.dbhCorrectionBeta))
            values["calibration.epoch"] = String(actual.dbhCalibrationEpoch)
            values["calibration.vio_drift_fraction"] = FieldFingerprint.number(Double(actual.vioDriftFraction))
            values["calibration.depth_discontinuity_m"] = FieldFingerprint.number(Double(actual.depthDiscontinuityM))
        }
        if let dbhMethod { values["dbh_method"] = dbhMethod }
        if let frameCount { values["capture_frame_count"] = String(frameCount) }
        if !continuing { preparedProtocol = nil }
        writeFailures.removeValue(forKey: "protocol"); error = writeFailures.values.sorted().first
        do {
            let config = !continuing && store?.configuration.enabled == false ? store?.configuration :
                ((continuing ? session?.current?.configuration : nil) ?? measurementScope?.configuration ?? inheritedScope?.configuration ?? store?.configuration)
            guard let config,config.enabled,config.protocolLocked else {
                if !continuing { preparedProtocol = (values,nil) }; return nil
            }
            guard config.pinnedRuntimeSettings == FieldProtocolBinding.policy else {
                throw FieldStudyError.conflict("Legacy protocol lock has no verified runtime baseline. Use a new protocol version.")
            }
            guard let store,store.loaded,store.error == nil else { throw FieldStudyError.missing("field-study configuration") }
            if continuing {
                guard let binding = session?.current?.protocolBinding,
                      binding.matches(configuration: config,kind: kind,runtime: values) else {
                    throw FieldStudyError.conflict("Protocol settings changed during capture. Restore the locked settings or retake with a new protocol version.")
                }
            } else {
                let candidate = try FieldProtocolBinding(configuration: config,kind: kind,runtime: values)
                preparedProtocol = (values,try store.archive.bindProtocol(candidate))
            }
            writeFailures.removeValue(forKey: "protocol"); error = writeFailures.values.sorted().first
            return nil
        } catch {
            let message = "Capture not started: "+error.localizedDescription
            writeFailures["protocol"] = message; self.error = message; return message
        }
    }
    func handle(_ event: FieldCaptureEvent) {
        let now = clock()
        do {
            switch event {
            case .started(let id,let raw,let ai,let typed):
                if preparedProtocol == nil,let reason = capturePermission(typed: typed) { throw FieldStudyError.conflict(reason) }
                let prepared = preparedProtocol; preparedProtocol = nil
                if let previous = currentId { try session?.abandon(id: previous,reason: "superseded_by_attempt",now: now); persist() }
                currentId = id
                displayRecord = nil
                guard let store else { return }
                guard store.loaded, store.error == nil else { throw FieldStudyError.missing("field-study configuration") }
                if store.configuration.enabled,let frozen = measurementScope ?? inheritedScope,
                   frozen.configuration.studyId != store.configuration.studyId { throw FieldStudyError.conflict("study changed during capture scope") }
                if let inheritedScope,inheritedScope.target?.id != scopedTarget?.id { throw FieldStudyError.conflict("inherited scope target mismatch") }
                let target = scopedTarget ?? (usesGlobalTargetSelection ? store.selectedTarget : nil)
                if !store.configuration.enabled { measurementScope = nil }
                else if inheritedScope == nil,usesGlobalTargetSelection,measurementScope?.target?.id != target?.id { measurementScope = nil }
                if store.configuration.enabled,measurementScope == nil {
                    if let inheritedScope { try inheritedScope.validate(); measurementScope = inheritedScope }
                    else if store.configuration.enabled {
                        let config = store.configuration
                        let methods = Set(config.referenceMethods.filter(\.enabled).map(\.id))
                        let refs = target.map { t in [FieldStudyKind.dbh,.height].flatMap {
                            store.references(targetId: t.id,kind: $0,plannedRepeat: config.plannedRepeat)
                        }.filter { methods.contains($0.method.id) } } ?? []
                        measurementScope = try .init(configuration: config,target: target,references: refs)
                    }
                }
                let config = measurementScope?.configuration ?? store.configuration
                let frozenTarget: FieldStudyTarget?
                if let scope = measurementScope { frozenTarget = scope.target } else { frozenTarget = target }
                let next = targetKey(config,frozenTarget?.id)
                if let previous = targetSessionKey, previous != next { session = FieldMeasurementSession(now: now) }
                targetSessionKey = next
                let refs = measurementScope?.references(for: kind).map(\.id) ?? []
                try session?.begin(id: id,configuration: config,target: frozenTarget,kind: kind,runtime: prepared?.runtime ?? runtime(typed: typed),references: refs,
                                   rawRequested: raw,aiEnabled: ai,typed: typed,now: now,scope: measurementScope,protocolBinding: prepared?.binding)
            case .result(let id,let value,let sigma,let valid,let reason,let ai):
                try session?.finish(id: id,value: value,sigma: sigma,valid: valid,reason: reason,ai: ai,now: now)
            case .refused(let id,let reason,let ai): try session?.refuse(id: id,reason: reason,ai: ai,now: now)
            case .abandoned(let id,let reason): try session?.abandon(id: id,reason: reason,now: now)
            case .stageRefused(let id,let stage,let reason): session?.noteStageRefusal(id: id,stage: stage,reason: reason,now: now)
            case .raw(let id,let status,let reason,let replay):
                guard recordedIds.contains(id), let archive = store?.archive else { return }
                enqueue(id: id) { try archive.updateRawEvidence(id: id,status: status,reason: reason,replayValue: replay) }
                return
            }
            persist()
        } catch { report(id: currentId ?? "unknown",error: error) }
    }
    func foreground(_ active: Bool) {
        session?.setForeground(active,now: clock()); persist()
    }
    func close(reason: String = "screen_closed") {
        if reason == "screen_closed" { wasHidden = true }
        guard let currentId else { return }
        do { try session?.abandon(id: currentId,reason: reason,now: clock()); persist() }
        catch { report(id: currentId,error: error) }
    }
    func appeared() {
        hasAppeared = true
        if let uiFlow { uiFlowRegistry.track(uiFlow,now: clock()) }
        if wasHidden, session?.attemptNumber == 0 { session = FieldMeasurementSession(now: clock()) }
        wasHidden = false; foreground(true)
    }
    func resultSaved(id: String, resultRecordId: String, resultStore: String, superseded: [UUID] = []) {
        do {
            try session?.accept(id: id,resultRecordId: resultRecordId,resultStore: resultStore,now: clock())
            writeFailures.removeValue(forKey: "application:"+id); error = writeFailures.values.sorted().first
            persist()
            if recordedIds.contains(id),let archive = store?.archive {
                for old in superseded { enqueue(id: old.uuidString,allowMissing: true) { _ = try archive.supersedeAcceptedMeasurement(id: old.uuidString,by: id) } }
                enqueue(id: id) { try archive.completeResultSave(id: id,resultRecordId: resultRecordId,resultStore: resultStore) }
            }
        }
        catch { report(id: id,error: error) }
    }
    func resultSaveFailed(id: String, reason: String) {
        session?.saveFailed(id: id,reason: reason,now: clock()); persist()
        let message = "Result NOT saved: "+reason
        writeFailures["application:"+id] = message; error = message
    }
    func receipt(id: String) -> FieldResultReceipt {
        FieldResultReceipt(measurementId: recordedIds.contains(id) ? id : nil,prepareHandler: { [self] row,storeName,value,replacements,inventoryReplacement in
            guard recordedIds.contains(id) else { return }
            guard let snapshot = session?.current,snapshot.id == id,let archive = store?.archive else { throw FieldStudyError.missing("prepared attempt") }
            let proof = FieldExportApplicationResult(id: row.uuidString,store: storeName,kind: snapshot.kind,value: value,measurementId: id)
            try archive.prepareResultSave(.init(snapshot: snapshot,expected: proof,replacements: replacements,inventoryReplacement: inventoryReplacement))
        }) { [self] recordId, resultStore, superseded in
            resultSaved(id: id,resultRecordId: recordId.uuidString,resultStore: resultStore,superseded: superseded)
        }
    }
    private func persist() {
        if let current = session?.current,current.id == currentId,current.configuration.recordTiming,let uiFlow {
            do {
                if let evidence = try uiFlow.evidence(now: clock(),previous: current.uiFlowTiming,
                    result: current.outcome == .result,accepted: current.selection == .accepted || current.selection == .superseded) {
                    try session?.updateUIFlow(evidence)
                }
            } catch { report(id: current.id,error: error) }
        }
        displayRecord = session?.current.flatMap { $0.id == currentId ? $0 : nil }
        guard let session, session.shouldPersistCurrent, let record = session.current,record.id == currentId, let archive = store?.archive else { return }
        recordedIds.insert(record.id)
        enqueue(id: record.id) { try archive.mergeSessionSnapshot(record) }
    }
    private func enqueue(id: String, allowMissing: Bool = false, operation: @escaping @Sendable () throws -> Void) {
        store?.enqueueMeasurementWrite(id: id,allowMissing: allowMissing,operation: operation) { [self] result in
            switch result {
            case .success(let record):
                if record != nil { writeFailures.removeValue(forKey: id); error = writeFailures.values.sorted().first }
            case .failure(let failure): report(id: id,error: failure)
            }
        }
    }
    private func report(id: String,error: Error) {
        self.error = "Field record NOT saved: "+error.localizedDescription
        writeFailures[id] = self.error
        store?.reportMeasurementWriteFailure(id: id,reason: error.localizedDescription)
    }
}

/// A noninteractive identity label remains visible when GPS/heading hides AR points.
struct FieldScanIdentityBadge: View {
    @Environment(\.fieldStudyStore) private var store
    @ObservedObject var coordinator: FieldCaptureCoordinator
    let kind: FieldStudyKind
    var body: some View {
        if let store {
            FieldStudyObservedContent(study: store) { study in
                if let identity = FieldScanIdentity.snapshot(configuration: study.configuration,
                    selected: coordinator.scopedTarget ?? (coordinator.usesGlobalTargetSelection ? study.selectedTarget : nil),
                    record: coordinator.displayRecord,kind: kind) {
                    VStack(spacing: 2) {
                        Text("Tree ID · "+(identity.treeId ?? "Unassigned")).font(.system(.caption,design: .monospaced).weight(.semibold))
                        Text("\(identity.studyId) · Round \(identity.plannedRepeat)"+(identity.recordingEnabled ? "" : " · Recording off"))
                            .font(.caption2)
                    }.lineLimit(1).foregroundStyle(.white).padding(.horizontal,10).padding(.vertical,6)
                        .background(.black.opacity(0.65),in: RoundedRectangle(cornerRadius: 8))
                        .accessibilityIdentifier("fieldCapture.identity")
                }
            }
        }
    }
}

struct FieldCaptureWarning: View {
    @ObservedObject var coordinator: FieldCaptureCoordinator
    var body: some View {
        if let error = coordinator.error {
            Text(error).font(.caption).foregroundStyle(.white).padding(8)
                .background(.red.opacity(0.85),in: RoundedRectangle(cornerRadius: 8)).padding(.horizontal,20)
                .accessibilityIdentifier("fieldCapture.writeFailure")
        }
    }
}

/// Opt-in, developer-only read-only preview; values do not cover the camera
/// until explicitly opened and cannot be edited from a capture screen.
struct FieldReferencePeek: View {
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.fieldStudyStore) private var store
    @ObservedObject var capture: FieldCaptureCoordinator
    let kind: FieldStudyKind
    @State private var showing = false
    private var references: [FieldReferenceObservation] { capture.previewReferences(kind: kind,developerMode: settings.developerMode) }
    var body: some View {
        if let store {
            FieldStudyObservedContent(study: store) { _ in
                Group {
                if !references.isEmpty {
                    Button { showing = true } label: {
                        Text("Reference preview · \(references.count)").font(.caption)
                            .foregroundStyle(.white).padding(.horizontal,10).padding(.vertical,7)
                            .background(.black.opacity(0.65),in: Capsule())
                    }.buttonStyle(.plain).accessibilityIdentifier("fieldCapture.references")
                    .sheet(isPresented: $showing) {
                        NavigationStack {
                            List(references) { r in
                                VStack(alignment: .leading,spacing: 3) {
                                    LabeledContent(r.method.name,value: String(format: "%.3f %@",r.inputValue,r.method.unit.rawValue))
                                    Text((r.method.role == .groundTruth ? "GT" : "Comparison")+" · Reference repeat \(r.referenceRepeat)")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                            }.navigationTitle("Reference preview").toolbar { Button("Done") { showing = false } }
                        }
                    }
                }
                }
                .onChange(of: references.isEmpty) { empty in if empty { showing = false } }
            }
        }
    }
}
