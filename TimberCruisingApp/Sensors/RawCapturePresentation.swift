import Foundation

/// Recovery export must remain available even if no manifest can be decoded.
public enum RawCaptureExportAvailability {
    public static func canExportRaw(_ inventory: RawCaptureStore.Inventory,isExporting: Bool) -> Bool {
        !isExporting && inventory.directories > 0
    }
}

/// Display-only interpretation. Never substitutes the legacy canonical fit
/// or a zero placeholder for the measurement the user actually saw.
public struct RawCapturePresentation: Sendable,Equatable {
    public let displayedValue: Double?
    public let canonicalValue: Double?
    public let usesExactEvidence: Bool
    public let allowsTruth: Bool
    public let sigma: Double?
    public let tier: String?
    public init(_ m: RawCaptureManifest) {
        func positive(_ v: Double?) -> Double? { v.flatMap { $0.isFinite && $0 > 0 ? $0 : nil } }
        canonicalValue = positive(m.resultLive.value)
        usesExactEvidence = m.measurementId != nil || m.captureEvidence != nil
        if let e = m.captureEvidence {
            let bound = UUID(uuidString: e.measurementId) != nil &&
                e.measurementId.caseInsensitiveCompare(m.measurementId ?? "") == .orderedSame
            displayedValue = e.displayedResult.valid && bound ? positive(e.displayedResult.value) : nil
            sigma = displayedValue == nil ? nil : e.displayedResult.sigma.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil }
            tier = displayedValue == nil ? nil : e.displayedResult.tier
        } else {
            displayedValue = usesExactEvidence ? nil : canonicalValue
            sigma = displayedValue == nil ? nil : m.resultLive.sigma
            tier = displayedValue == nil ? nil : m.resultLive.tier
        }
        allowsTruth = displayedValue != nil && ["dbh","height"].contains(m.kind)
    }
}
