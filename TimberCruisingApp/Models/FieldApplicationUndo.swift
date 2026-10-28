import Foundation

/// One durable journal batch after a successful conditional inventory Undo.
/// Before/after snapshots make interrupted multi-record writes recoverable.
public struct FieldApplicationUndoBatch: Codable, Equatable, Sendable {
    public struct Change: Codable, Equatable, Sendable {
        public let before: FieldMeasurementRecord
        public let after: FieldMeasurementRecord
    }
    public let schema: Int
    public let id: String
    public let resultRecordId: String
    public let measurementIds: [String]
    public let changes: [Change]
    public var completed: Bool

    public static func prepare(id: String,resultRecordId: String,measurementIds: [String],records: [FieldMeasurementRecord]) throws -> Self {
        guard UUID(uuidString: id) != nil,UUID(uuidString: resultRecordId) != nil,
              !measurementIds.isEmpty,Set(measurementIds.map { $0.lowercased() }).count == measurementIds.count else {
            throw FieldStudyError.invalid("application Undo identity")
        }
        var changes: [Change] = []
        for measurementId in measurementIds {
            let matches = records.filter { $0.id.caseInsensitiveCompare(measurementId) == .orderedSame }
            guard matches.count == 1,let current = matches.first,current.selection == .accepted,current.outcome == .result,
                  current.resultStore == "cruise",current.resultRecordId?.caseInsensitiveCompare(resultRecordId) == .orderedSame else {
                throw FieldStudyError.conflict("Undo journal no longer matches the inventory result")
            }
            var discarded = current; discarded.selection = .discarded; discarded.revision += 1
            discarded.events.append(.init(action: "application_undo",detail: id))
            changes.append(.init(before: current,after: discarded))
            for old in records where old.selection == .superseded && old.resultStore == "cruise" &&
                old.resultRecordId?.caseInsensitiveCompare(resultRecordId) == .orderedSame && FieldMeasurementReplacementScope.matches(old,current) {
                let last = old.events.last { ["superseded","application_undo_restore"].contains($0.action) }
                guard last?.action == "superseded",last?.detail?.caseInsensitiveCompare(current.id) == .orderedSame else { continue }
                var restored = old; restored.selection = .accepted; restored.revision += 1
                restored.events.append(.init(action: "application_undo_restore",detail: id+":"+current.id))
                changes.append(.init(before: old,after: restored))
            }
        }
        let batch = Self(schema: 1,id: id,resultRecordId: resultRecordId,measurementIds: measurementIds,changes: changes,completed: false)
        try batch.validate(); return batch
    }
    public func validate() throws {
        guard schema == 1,UUID(uuidString: id) != nil,UUID(uuidString: resultRecordId) != nil,!measurementIds.isEmpty,
              measurementIds.allSatisfy({ UUID(uuidString: $0) != nil }),Set(measurementIds.map { $0.lowercased() }).count == measurementIds.count,
              Set(changes.map { $0.before.id.lowercased() }).count == changes.count,
              Set(changes.filter { $0.after.selection == .discarded }.map { $0.before.id.lowercased() }) == Set(measurementIds.map { $0.lowercased() }) else {
            throw FieldStudyError.invalid("application Undo batch")
        }
        for change in changes {
            let old = change.before,next = change.after
            try old.validate(); try next.validate()
            guard old.resultStore == "cruise",old.resultRecordId?.caseInsensitiveCompare(resultRecordId) == .orderedSame,
                  let event = next.events.last else { throw FieldStudyError.invalid("application Undo result") }
            var expected = old; expected.revision += 1; expected.events.append(event)
            if measurementIds.contains(where: { $0.caseInsensitiveCompare(old.id) == .orderedSame }) {
                guard old.selection == .accepted,old.outcome == .result,event.action == "application_undo",event.detail == id else {
                    throw FieldStudyError.invalid("application Undo discard")
                }
                expected.selection = .discarded
            } else {
                guard old.selection == .superseded,event.action == "application_undo_restore",
                      let replacement = changes.first(where: { event.detail == id+":"+$0.before.id && $0.after.selection == .discarded }),
                      old.events.last(where: { ["superseded","application_undo_restore"].contains($0.action) })?.detail?.caseInsensitiveCompare(replacement.before.id) == .orderedSame,
                      FieldMeasurementReplacementScope.matches(old,replacement.before) else { throw FieldStudyError.invalid("application Undo restoration") }
                expected.selection = .accepted
            }
            guard next == expected else { throw FieldStudyError.conflict("Undo cannot rewrite capture evidence") }
        }
    }
}
