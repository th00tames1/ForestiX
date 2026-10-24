import Foundation

/// Result identities follow measurement content, never the mutable tree label.
/// Unattributed edits invalidate only that kind's identity; explicit new capture
/// and exact Undo identities remain part of the same atomic tree write.
public enum FieldTreeMeasurementIdentity {
    public static func prepare(_ proposed: Tree,previous: Tree? = nil) throws -> Tree {
        var next = proposed
        for kind in [FieldStudyKind.dbh,.height] {
            let id = kind == .dbh ? next.dbhMeasurementId : next.heightMeasurementId
            let old = kind == .dbh ? previous?.dbhMeasurementId : previous?.heightMeasurementId
            if let id,!sameID(id,old),UUID(uuidString: id) == nil { throw FieldStudyError.invalid("inventory measurement UUID") }
        }
        if let previous {
            if !sameDBH(next,previous),sameID(next.dbhMeasurementId,previous.dbhMeasurementId) { next.dbhMeasurementId = nil }
            if !sameHeight(next,previous),sameID(next.heightMeasurementId,previous.heightMeasurementId) { next.heightMeasurementId = nil }
        }
        return next
    }
    private static func sameID(_ a: String?,_ b: String?) -> Bool {
        if let a,let b,let first = UUID(uuidString: a),let second = UUID(uuidString: b) { return first == second }
        return a == b
    }
    private static func sameDBH(_ a: Tree,_ b: Tree) -> Bool {
        a.dbhCm == b.dbhCm && a.dbhMethod == b.dbhMethod && a.dbhSigmaMm == b.dbhSigmaMm && a.dbhRmseMm == b.dbhRmseMm &&
        a.dbhCoverageDeg == b.dbhCoverageDeg && a.dbhNInliers == b.dbhNInliers && a.dbhConfidence == b.dbhConfidence &&
        a.dbhIsIrregular == b.dbhIsIrregular && a.dbhCaptureMode == b.dbhCaptureMode && a.dbhEstimatorEpoch == b.dbhEstimatorEpoch
    }
    private static func sameHeight(_ a: Tree,_ b: Tree) -> Bool {
        a.heightM == b.heightM && a.heightMethod == b.heightMethod && a.heightSource == b.heightSource &&
        a.heightSigmaM == b.heightSigmaM && a.heightDHM == b.heightDHM && a.heightAlphaTopDeg == b.heightAlphaTopDeg &&
        a.heightAlphaBaseDeg == b.heightAlphaBaseDeg && a.heightConfidence == b.heightConfidence
    }
}
