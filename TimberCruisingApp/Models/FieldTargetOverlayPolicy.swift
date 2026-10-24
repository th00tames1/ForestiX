import Foundation

/// Progress is scoped to a study and planned round, never to proximity or time.
public enum FieldTargetProgress {
    public static func completed(studyId: String,plannedRepeat: Int,records: [FieldMeasurementRecord]) -> Set<String> {
        var kinds: [String:Set<FieldStudyKind>] = [:]
        for r in records where r.studyId == studyId && r.plannedRepeat == plannedRepeat && r.selection == .accepted &&
            r.outcome == .result && r.liveValue.map({ $0.isFinite && $0 > 0 }) == true && r.resultRecordId != nil {
            if let id = r.targetId { kinds[id,default: []].insert(r.kind) }
        }
        return Set(kinds.filter { $0.value.contains(.dbh) && $0.value.contains(.height) }.keys)
    }
    public static func visible(configuration c: FieldStudyConfiguration,targets: [FieldStudyTarget],records: [FieldMeasurementRecord]) -> [FieldStudyTarget] {
        let done = c.hideCompletedTargets ? completed(studyId: c.studyId,plannedRepeat: c.plannedRepeat,records: records) : []
        return targets.filter { $0.studyId == c.studyId && !done.contains($0.id) }
    }
}

/// The on-screen identity is pinned to an actual attempt, not a later selection.
public struct FieldScanIdentity: Equatable, Sendable {
    public let studyId: String
    public let treeId: String?
    public let plannedRepeat: Int
    public let kind: FieldStudyKind
    public let measurementId: String?
    public let recordingEnabled: Bool
    public static func snapshot(configuration c: FieldStudyConfiguration,selected: FieldStudyTarget?,
                                record: FieldMeasurementRecord?,kind: FieldStudyKind) -> FieldScanIdentity? {
        if let r = record,r.selection != .discarded,r.selection != .superseded {
            return .init(studyId: r.studyId,treeId: r.targetId == nil ? nil : r.treeId,plannedRepeat: r.plannedRepeat,
                kind: r.kind,measurementId: r.id,recordingEnabled: true)
        }
        guard c.enabled || selected != nil else { return nil }
        return .init(studyId: c.studyId,treeId: selected?.studyId == c.studyId ? selected?.treeId : nil,plannedRepeat: c.plannedRepeat,kind: kind,measurementId: nil,recordingEnabled: c.enabled)
    }
}

public struct FieldOverlayLocation: Sendable {
    public var latitude: Double
    public var longitude: Double
    public var horizontalAccuracyM: Double
    public var fixAgeSeconds: Double
    public var headingTrueDegrees: Double?
    public var headingAccuracyDegrees: Double?
    public var headingAgeSeconds: Double?
    public var arTrackingNormal: Bool
    public init(latitude: Double, longitude: Double, horizontalAccuracyM: Double, fixAgeSeconds: Double,
                headingTrueDegrees: Double?, headingAccuracyDegrees: Double?, headingAgeSeconds: Double?, arTrackingNormal: Bool) {
        self.latitude = latitude; self.longitude = longitude; self.horizontalAccuracyM = horizontalAccuracyM
        self.fixAgeSeconds = fixAgeSeconds; self.headingTrueDegrees = headingTrueDegrees
        self.headingAccuracyDegrees = headingAccuracyDegrees; self.headingAgeSeconds = headingAgeSeconds
        self.arTrackingNormal = arTrackingNormal
    }
}
public enum FieldOverlayStatus: String, Codable, Sendable {
    case ready, disabled
    case positionUnavailable = "position_unavailable", positionStale = "position_stale", positionUncertain = "position_uncertain"
    case headingUnavailable = "heading_unavailable", headingStale = "heading_stale", headingUncertain = "heading_uncertain"
    case trackingUnavailable = "tracking_unavailable", viewportUnavailable = "viewport_unavailable"
}
public struct FieldOverlayCandidate: Equatable, Sendable {
    public let targetId: String
    public let label: String
    public let distanceM: Double
    public let bearingDegrees: Double
    public let relativeBearingDegrees: Double
    public let uncertaintyDegrees: Double
    public let targetAccuracyKnown: Bool
}
public struct FieldOverlayDecision: Sendable {
    public let status: FieldOverlayStatus
    public let candidates: [FieldOverlayCandidate]
}

/// Advisory target selection ONLY. This API deliberately exposes no measurement/capture gate.
/// A hidden overlay never changes estimator input, the selected tree, or the ability to measure.
public enum FieldTargetOverlayPolicy {
    public static func quality(configuration c: FieldARConfiguration, location f: FieldOverlayLocation?) -> FieldOverlayStatus {
        guard c.enabled else { return .disabled }
        guard (try? c.validate()) != nil, let f, f.latitude.isFinite, f.longitude.isFinite,
              (-90...90).contains(f.latitude), (-180...180).contains(f.longitude)
        else { return .positionUnavailable }
        guard f.fixAgeSeconds.isFinite, f.fixAgeSeconds >= 0, f.fixAgeSeconds <= c.maximumFixAgeSeconds else { return .positionStale }
        guard f.horizontalAccuracyM.isFinite, f.horizontalAccuracyM >= 0,
              f.horizontalAccuracyM <= c.maximumHorizontalAccuracyM else { return .positionUncertain }
        guard let heading = f.headingTrueDegrees, heading.isFinite, (0..<360).contains(heading) else { return .headingUnavailable }
        guard let age = f.headingAgeSeconds, age.isFinite, age >= 0, age <= c.maximumHeadingAgeSeconds else { return .headingStale }
        guard let error = f.headingAccuracyDegrees, error.isFinite, error >= 0, error <= c.maximumHeadingErrorDegrees else { return .headingUncertain }
        guard f.arTrackingNormal else { return .trackingUnavailable }
        return .ready
    }
    public static func evaluate(configuration: FieldARConfiguration, studyId: String, targets: [FieldStudyTarget],
                                location: FieldOverlayLocation?, horizontalFieldOfViewDegrees: Double,
                                previouslyVisible: Set<String> = [], selectedTargetId: String? = nil) -> FieldOverlayDecision {
        let status = quality(configuration: configuration, location: location)
        guard status == .ready, let f = location else { return .init(status: status, candidates: []) }
        guard horizontalFieldOfViewDegrees.isFinite, horizontalFieldOfViewDegrees > 0, horizontalFieldOfViewDegrees < 180
        else { return .init(status: .viewportUnavailable, candidates: []) }
        var candidates: [FieldOverlayCandidate] = []
        for t in targets where t.studyId == studyId {
            guard let lat = t.latitude, let lon = t.longitude, (try? t.validate()) != nil else { continue }
            let g = geometry(latitude: f.latitude, longitude: f.longitude, targetLatitude: lat, targetLongitude: lon)
            let limit = configuration.maximumDistanceM + (previouslyVisible.contains(t.id) ? configuration.distanceHysteresisM : 0)
            guard g.distance > 0.01, g.distance <= limit else { continue }
            let relative = wrappedDegrees(g.bearing-f.headingTrueDegrees!)
            guard abs(relative) <= horizontalFieldOfViewDegrees/2 else { continue }
            let uncertainty = atan2(f.horizontalAccuracyM+(t.horizontalAccuracyM ?? 0), g.distance)*180/Double.pi + f.headingAccuracyDegrees!
            candidates.append(.init(targetId: t.id, label: t.label, distanceM: g.distance, bearingDegrees: g.bearing,
                                    relativeBearingDegrees: relative, uncertaintyDegrees: min(180,uncertainty),
                                    targetAccuracyKnown: t.horizontalAccuracyM != nil))
        }
        candidates.sort {
            if ($0.targetId == selectedTargetId) != ($1.targetId == selectedTargetId) { return $0.targetId == selectedTargetId }
            return $0.distanceM == $1.distanceM ? $0.targetId < $1.targetId : $0.distanceM < $1.distanceM
        }
        return .init(status: .ready, candidates: Array(candidates.prefix(configuration.maximumPoints)))
    }
    public static func wrappedDegrees(_ value: Double) -> Double {
        let v = (value+180).truncatingRemainder(dividingBy: 360)
        return (v < 0 ? v+360 : v)-180
    }
    public static func geometry(latitude: Double, longitude: Double, targetLatitude: Double, targetLongitude: Double)
        -> (distance: Double, bearing: Double) {
        let p1 = latitude*Double.pi/180, p2 = targetLatitude*Double.pi/180
        let dp = p2-p1, dl = wrappedDegrees(targetLongitude-longitude)*Double.pi/180
        let a = pow(sin(dp/2),2) + cos(p1)*cos(p2)*pow(sin(dl/2),2)
        let distance = 6371008.8 * 2 * atan2(sqrt(max(0,min(1,a))), sqrt(max(0,1-a)))
        let bearing = atan2(sin(dl)*cos(p2), cos(p1)*sin(p2)-sin(p1)*cos(p2)*cos(dl))*180/Double.pi
        return (distance, (bearing+360).truncatingRemainder(dividingBy: 360))
    }
}
