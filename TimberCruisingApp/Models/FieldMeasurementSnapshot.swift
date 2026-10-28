import Foundation

public enum FieldMeasurementSnapshot {
    /// Linear-time merge. Preserve newer revisions and IDs first published
    /// while a disk scan was in flight, not stale entries deleted beforehand.
    public static func merge(scanned: [FieldMeasurementRecord],current: [FieldMeasurementRecord],
                             baselineIds: Set<String>) -> [FieldMeasurementRecord] {
        let live = Dictionary(current.map { ($0.id,$0) },uniquingKeysWith: { a,b in a.revision > b.revision ? a : b })
        let scannedIds = Set(scanned.map(\.id))
        return scanned.map { disk in
            guard let current = live[disk.id],current.revision > disk.revision else { return disk }
            return current
        }+current.filter { !baselineIds.contains($0.id) && !scannedIds.contains($0.id) }
    }
}
