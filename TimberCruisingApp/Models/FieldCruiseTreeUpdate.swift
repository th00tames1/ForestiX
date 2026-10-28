import Foundation

public struct FieldCruiseUndoState: Sendable {
    public let operationId = UUID().uuidString
    public let original: Tree?
    public var saved: Tree
    public var measurementIds: [String]
    public init(original: Tree?,saved: Tree,measurementIds: [String]) {
        self.original = original; self.saved = saved; self.measurementIds = measurementIds
    }
}

/// Remeasure DBH only; do not reset the tree's height, status, name, surveyed
/// position, crown/multistem data or earlier notes/damage. No estimator math.
public enum FieldCruiseTreeUpdate {
    public static func dbh(previous: Tree,measurement: Tree,speciesOverride: String?) throws -> Tree {
        guard previous.plotId == measurement.plotId,previous.deletedAt == nil else { throw FieldStudyError.conflict("Inventory scope changed") }
        var next = previous
        next.dbhCm = measurement.dbhCm; next.dbhMethod = measurement.dbhMethod
        next.dbhSigmaMm = measurement.dbhSigmaMm; next.dbhRmseMm = measurement.dbhRmseMm
        next.dbhCoverageDeg = measurement.dbhCoverageDeg; next.dbhNInliers = measurement.dbhNInliers
        next.dbhConfidence = measurement.dbhConfidence; next.dbhIsIrregular = measurement.dbhIsIrregular
        next.dbhCaptureMode = measurement.dbhCaptureMode; next.dbhEstimatorEpoch = measurement.dbhEstimatorEpoch
        next.dbhMeasurementId = measurement.dbhMeasurementId
        if let speciesOverride { next.speciesCode = speciesOverride }
        for code in measurement.damageCodes where !next.damageCodes.contains(code) { next.damageCodes.append(code) }
        if !measurement.notes.isEmpty,measurement.notes != next.notes {
            next.notes = next.notes.isEmpty ? measurement.notes : next.notes+"\n"+measurement.notes
        }
        if next.latitude == nil,next.longitude == nil { next.latitude = measurement.latitude; next.longitude = measurement.longitude }
        if next.bearingFromCenterDeg == nil,next.distanceFromCenterM == nil {
            next.bearingFromCenterDeg = measurement.bearingFromCenterDeg; next.distanceFromCenterM = measurement.distanceFromCenterM
        }
        next.photoPath = measurement.photoPath ?? next.photoPath
        next.rawScanPath = measurement.rawScanPath; next.updatedAt = measurement.updatedAt
        return next
    }
}
