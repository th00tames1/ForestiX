import Foundation

/// Immutable metadata shared by one tree's capture chain. Actual runtime
/// parameters are still recorded separately on every attempt, never invented
/// from this snapshot and never used as a camera/GPS measurement gate.
public struct FieldMeasurementScope: Codable,Equatable,Sendable {
    public let id: String
    public let configuration: FieldStudyConfiguration
    public let target: FieldStudyTarget?
    public let references: [FieldReferenceObservation]
    public let createdAt: String
    public init(id: String = UUID().uuidString,configuration: FieldStudyConfiguration,target: FieldStudyTarget?,
                references: [FieldReferenceObservation],createdAt: String = FieldStudyJSON.timestamp()) throws {
        self.id = id; self.configuration = configuration; self.target = target
        self.references = references.sorted { $0.id < $1.id }; self.createdAt = createdAt
        try validate()
    }
    public func references(for kind: FieldStudyKind) -> [FieldReferenceObservation] {
        references.filter { $0.method.kind == kind }
    }
    public func validate() throws {
        try configuration.validate(); try target?.validate()
        let enabled = Set(configuration.referenceMethods.filter(\.enabled).map(\.id))
        guard UUID(uuidString: id) != nil,configuration.enabled,!createdAt.isEmpty,
              target == nil || target?.studyId == configuration.studyId,
              Set(references.map(\.id)).count == references.count else { throw FieldStudyError.invalid("measurement scope") }
        for r in references {
            try r.validate(developerMode: true)
            guard r.targetId == target?.id,enabled.contains(r.method.id),
                  r.plannedRepeat == nil || r.plannedRepeat == configuration.plannedRepeat else {
                throw FieldStudyError.invalid("scope reference binding")
            }
        }
    }
}

/// Read-only reference visibility; a display setting never grants GT editing.
public enum FieldMeasurementReferences {
    public static func visible(scope: FieldMeasurementScope?,kind: FieldStudyKind,developerMode: Bool) -> [FieldReferenceObservation] {
        guard developerMode,let scope,scope.configuration.showReferencesDuringMeasurement else { return [] }
        return scope.references(for: kind)
    }
    public static func preview(configuration: FieldStudyConfiguration,targetId: String?,observations: [FieldReferenceObservation],
                               kind: FieldStudyKind,developerMode: Bool) -> [FieldReferenceObservation] {
        guard developerMode,configuration.enabled,configuration.showReferencesDuringMeasurement,let targetId else { return [] }
        let enabled = Set(configuration.referenceMethods.filter(\.enabled).map(\.id))
        let superseded = Set(observations.compactMap(\.supersedesId))
        return observations.filter { $0.targetId == targetId && $0.method.kind == kind && enabled.contains($0.method.id) &&
            !superseded.contains($0.id) && ($0.plannedRepeat == nil || $0.plannedRepeat == configuration.plannedRepeat)
        }.sorted { $0.id < $1.id }
    }
}
