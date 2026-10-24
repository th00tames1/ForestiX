import Foundation
import Common

public struct RawOperatorRecoveryIssue: Sendable,Equatable {
    public let file: String
    public let reason: String
}

/// Write-ahead operator intent, independent of camera/estimator state. A
/// receipt in the manifest prevents an unlink failure from replaying an old
/// GT over a later explicit edit. Missing/corrupt sources are never guessed.
enum RawOperatorEditJournal {
    struct Edit: Codable {
        var schema: Int? = 1
        var measurementId: String?
        var editId: String?
        var truth: RawCaptureManifest.Truth?
        var operatorAccepted: Bool
        enum CodingKeys: String,CodingKey {
            case schema,truth
            case measurementId = "measurement_id",editId = "edit_id",operatorAccepted = "operator_accepted"
        }
        init(id: String) { measurementId = id; editId = UUID().uuidString; operatorAccepted = false }
        func validate(id: String) throws {
            guard schema == nil || schema == 1,
                  measurementId == nil || measurementId?.caseInsensitiveCompare(id) == .orderedSame,
                  editId == nil || UUID(uuidString: editId!) != nil,
                  truth?.value.map({ $0.isFinite && $0 > 0 }) ?? true,
                  truth?.truthUnit.map({ ["cm","in","m","ft"].contains($0) }) ?? true else {
                throw RawCaptureError.writeFailed("pending operator edit is corrupt or misbound")
            }
        }
    }
    static func validate(directory: URL,id: String) throws {
        guard UUID(uuidString: id) != nil,directory.lastPathComponent.caseInsensitiveCompare(id) == .orderedSame,
              (try? directory.resourceValues(forKeys: [.isSymbolicLinkKey]).isSymbolicLink) != true else {
            throw RawCaptureError.writeFailed("raw capture identity/path mismatch")
        }
    }
    static func atomic(_ data: Data,to url: URL) throws {
        try data.write(to: url,options: .atomic)
        let h = try FileHandle(forWritingTo: url); defer { try? h.close() }; try h.synchronize()
    }
    static func readManifest(_ directory: URL,id: String) throws -> RawCaptureManifest? {
        let url = directory.appendingPathComponent("manifest.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let m = try RawCaptureStore.decoder().decode(RawCaptureManifest.self,from: Data(contentsOf: url))
        guard m.measurementId == nil || m.measurementId?.caseInsensitiveCompare(id) == .orderedSame else {
            throw RawCaptureError.writeFailed("manifest measurement ID mismatch")
        }
        return m
    }
    private static func readEdit(_ directory: URL,id: String) throws -> Edit? {
        let url = directory.appendingPathComponent("pending_patch.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let e = try RawCaptureStore.decoder().decode(Edit.self,from: Data(contentsOf: url)); try e.validate(id: id); return e
    }
    private static func save(_ edit: Edit,directory: URL) throws {
        try atomic(RawCaptureStore.encoder().encode(edit),to: directory.appendingPathComponent("pending_patch.json"))
    }
    private static func measurable(_ m: RawCaptureManifest) -> Bool {
        if let actual = m.captureEvidence?.displayedResult { return actual.valid && (actual.value.map { $0.isFinite && $0 > 0 } ?? false) }
        return m.resultLive.value.isFinite && m.resultLive.value > 0
    }
    private static func apply(_ edit: Edit,to m: inout RawCaptureManifest) throws {
        if let id = edit.editId,id == m.operatorEditId { return }
        guard measurable(m) else { throw RawCaptureError.writeFailed("operator edit requires an actual measurement") }
        if let truth = edit.truth { m.truth = truth } // Includes an explicit clear.
        if edit.operatorAccepted { m.resultLive.operatorAccepted = true; m.resultLive.accepted = true }
        m.operatorEditId = edit.editId
    }
    /// Caller holds the shared recursive patch lock.
    private static func commitPending(_ base: RawCaptureManifest,directory: URL,id: String) throws {
        var m = base
        if var edit = try readEdit(directory,id: id) {
            // Upgrade old sidecars BEFORE applying, giving them an idempotent receipt.
            if edit.editId == nil { edit.editId = UUID().uuidString; edit.measurementId = id; try save(edit,directory: directory) }
            try apply(edit,to: &m)
        }
        try atomic(RawCaptureStore.encoder().encode(m),to: directory.appendingPathComponent("manifest.json"))
        // A failed unlink is safe: the just-written receipt makes replay a no-op.
        try? FileManager.default.removeItem(at: directory.appendingPathComponent("pending_patch.json"))
    }
    static func update(directory: URL,id: String,truth: RawCaptureManifest.Truth? = nil,accepted: Bool = false) throws -> Bool {
        RawCaptureStore.patchLock.lock(); defer { RawCaptureStore.patchLock.unlock() }
        try validate(directory: directory,id: id)
        let manifest = try readManifest(directory,id: id)
        if let manifest,!measurable(manifest) { throw RawCaptureError.writeFailed("operator edit requires an actual measurement") }
        var edit = try readEdit(directory,id: id) ?? Edit(id: id)
        if let receipt = manifest?.operatorEditId,receipt == edit.editId { edit = Edit(id: id) }
        edit.editId = UUID().uuidString; edit.measurementId = id
        if let truth { edit.truth = truth }; if accepted { edit.operatorAccepted = true }
        try edit.validate(id: id)
        try FileManager.default.createDirectory(at: directory,withIntermediateDirectories: true)
        try save(edit,directory: directory) // Must precede any manifest mutation.
        guard let manifest else { return false }
        try commitPending(manifest,directory: directory,id: id); return true
    }
    static func commitRecorder(_ base: RawCaptureManifest,directory: URL,id: String) throws {
        RawCaptureStore.patchLock.lock(); defer { RawCaptureStore.patchLock.unlock() }
        try validate(directory: directory,id: id); var m = base
        if let disk = try readManifest(directory,id: id) {
            m.truth = disk.truth; m.operatorEditId = disk.operatorEditId
            if disk.resultLive.operatorAccepted { m.resultLive.operatorAccepted = true; m.resultLive.accepted = true }
        }
        try commitPending(m,directory: directory,id: id)
    }
    static func recover(root: URL) -> [RawOperatorRecoveryIssue] {
        RawCaptureStore.patchLock.lock(); defer { RawCaptureStore.patchLock.unlock() }
        let fm = FileManager.default
        guard fm.fileExists(atPath: root.path) else { return [] }
        guard let dirs = try? fm.contentsOfDirectory(at: root,includingPropertiesForKeys: nil) else {
            return [.init(file: "raw-captures",reason: "pending_operator_inventory_unreadable")]
        }
        var issues: [RawOperatorRecoveryIssue] = []
        for dir in dirs where fm.fileExists(atPath: dir.appendingPathComponent("pending_patch.json").path) {
            do {
                try validate(directory: dir,id: dir.lastPathComponent)
                guard let m = try readManifest(dir,id: dir.lastPathComponent) else { throw RawCaptureError.bundleMissing("pending operator input retained without manifest") }
                try commitPending(m,directory: dir,id: dir.lastPathComponent)
            } catch { issues.append(.init(file: "raw-captures/"+dir.lastPathComponent+"/pending_patch.json",reason: String(describing: error))) }
        }
        return issues
    }
}
