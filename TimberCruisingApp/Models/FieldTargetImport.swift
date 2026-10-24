import Foundation

public struct FieldTargetSourceFeature: Equatable, Sendable {
    public var sourceFeatureId: String
    public var latitude: Double
    public var longitude: Double
    public var attributes: [String: String]
    public init(sourceFeatureId: String, latitude: Double, longitude: Double, attributes: [String: String]) {
        self.sourceFeatureId = sourceFeatureId; self.latitude = latitude; self.longitude = longitude; self.attributes = attributes
    }
    public func value(_ field: String) -> String? { field == "$feature_id" ? sourceFeatureId : attributes[field] }
}
public struct FieldTargetSource: Sendable {
    public var layerId: String
    public var name: String
    public var features: [FieldTargetSourceFeature]
    public var skippedRecords: Int
    public var fields: [String] { ["$feature_id"] + Set(features.flatMap { $0.attributes.keys }).sorted() }
    public init(layerId: String, name: String, features: [FieldTargetSourceFeature], skippedRecords: Int = 0) {
        self.layerId = layerId; self.name = name; self.features = features; self.skippedRecords = skippedRecords
    }
}
public struct FieldTargetMapping: Codable, Equatable, Sendable {
    public var treeIdField: String
    public var labelField: String?
    public var altitudeField: String?
    public var altitudeUnit: FieldReferenceUnit = .m
    public var accuracyField: String?
    public var referenceFields: [String: String] = [:]
    public var referencePlannedRepeat: Int?
    public init(treeIdField: String) { self.treeIdField = treeIdField }
}
/// One atomic import contains both the catalog and its reference observations.
/// A crash cannot expose new targets with only part of their GT imported.
public struct FieldTargetImportBatch: Codable, Equatable, Sendable, Identifiable {
    public var schema = 1
    public var id: String = UUID().uuidString
    public var importedAt = FieldStudyJSON.timestamp()
    public var sourceName: String
    public var sourceLayerId: String
    public var studyId: String
    public var protocolFingerprint: String
    public var mapping: FieldTargetMapping
    public var targets: [FieldStudyTarget]
    public var references: [FieldReferenceObservation]
    public var skippedRecords: Int
    public func validate(developerMode: Bool) throws {
        let targetIds = Set(targets.map(\.id))
        guard schema == 1 else { throw FieldStudyError.unsupportedSchema(schema) }
        guard UUID(uuidString: id) != nil, !sourceName.isEmpty, !studyId.isEmpty,
              !targets.isEmpty, targets.count <= 10000, references.count <= 100000,
              Set(targets.map(\.id)).count == targets.count,
              Set(references.map(\.id)).count == references.count, skippedRecords >= 0,
              targets.allSatisfy({ $0.studyId == studyId && $0.sourceLayerId == sourceLayerId }),
              references.allSatisfy({ targetIds.contains($0.targetId) })
        else { throw FieldStudyError.invalid("target import batch") }
        if !references.isEmpty && !developerMode { throw FieldStudyError.developerRequired }
        try targets.forEach { try $0.validate() }; try references.forEach { try $0.validate(developerMode: developerMode) }
        let slots = references.map { FieldFingerprint.digest([$0.targetId,$0.method.id,String($0.plannedRepeat ?? 0),String($0.referenceRepeat)]) }
        guard Set(slots).count == slots.count, references.allSatisfy({ $0.supersedesId == nil })
        else { throw FieldStudyError.invalid("duplicate imported reference slot") }
    }
}
public enum FieldTargetMaterializer {
    public static func make(source: FieldTargetSource, mapping: FieldTargetMapping,
                            configuration c: FieldStudyConfiguration, developerMode: Bool) throws -> FieldTargetImportBatch {
        try c.validate()
        let fields = Set(source.fields)
        let mapped = [mapping.treeIdField] + [mapping.labelField,mapping.altitudeField,mapping.accuracyField].compactMap { $0 } + Array(mapping.referenceFields.values)
        guard !c.studyId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              source.features.count <= 10000, source.features.count * mapping.referenceFields.count <= 100000, !mapping.treeIdField.isEmpty,
              mapped.allSatisfy({ fields.contains($0) }),
              mapping.altitudeUnit.supports(.height),
              mapping.referencePlannedRepeat.map({ (1...c.plannedRepetitions).contains($0) }) ?? true
        else { throw FieldStudyError.invalid("target field mapping or study ID") }
        if !mapping.referenceFields.isEmpty && !developerMode { throw FieldStudyError.developerRequired }
        var targets: [FieldStudyTarget] = []; var references: [FieldReferenceObservation] = []
        for feature in source.features {
            func text(_ field: String) throws -> String {
                guard let value = feature.value(field)?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty
                else { throw FieldStudyError.invalid("missing \(field) in feature \(feature.sourceFeatureId)") }
                return value
            }
            func optionalNumber(_ field: String?) throws -> Double? {
                guard let field, let value = feature.value(field)?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else { return nil }
                guard let number = Double(value), number.isFinite else { throw FieldStudyError.invalid("numeric field \(field) in feature \(feature.sourceFeatureId)") }
                return number
            }
            let treeId = try text(mapping.treeIdField)
            let label = try mapping.labelField.map { try text($0) } ?? treeId
            let altitude = try optionalNumber(mapping.altitudeField).map { mapping.altitudeUnit.baseValue($0) }
            let target = FieldStudyTarget(studyId: c.studyId, treeId: treeId, label: label,
                latitude: feature.latitude, longitude: feature.longitude, altitudeM: altitude,
                horizontalAccuracyM: try optionalNumber(mapping.accuracyField), sourceLayerId: source.layerId,
                sourceFeatureId: feature.sourceFeatureId, attributes: feature.attributes)
            try target.validate(); targets.append(target)
            for methodId in mapping.referenceFields.keys.sorted() {
                guard let method = c.referenceMethods.first(where: { $0.id == methodId && $0.enabled })
                else { throw FieldStudyError.invalid("unknown reference method \(methodId)") }
                let field = mapping.referenceFields[methodId]!
                if let value = try optionalNumber(field) {
                    references.append(.init(targetId: target.id, method: method, inputValue: value,
                        plannedRepeat: mapping.referencePlannedRepeat, operatorId: c.operatorId,
                        source: source.layerId+":"+feature.sourceFeatureId+":"+field))
                }
            }
        }
        let batch = FieldTargetImportBatch(sourceName: source.name, sourceLayerId: source.layerId,
            studyId: c.studyId, protocolFingerprint: c.protocolFingerprint, mapping: mapping,
            targets: targets, references: references, skippedRecords: source.skippedRecords)
        try batch.validate(developerMode: developerMode); return batch
    }
}
