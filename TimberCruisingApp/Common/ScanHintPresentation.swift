/// A short introduction once per scan-screen visit, not once per depth lock.
/// Errors and tracking warnings must never pass through this presentation gate.
public struct ScanHintPresentation {
    public static let durationSeconds = 4.0
    public private(set) var visibleKey: String?
    private var seen = Set<String>()
    public init() {}
    @discardableResult public mutating func begin(_ key: String?) -> Bool {
        visibleKey = nil
        guard let key, seen.insert(key).inserted else { return false }
        visibleKey = key
        return true
    }
    public mutating func dismiss(_ key: String?) {
        if visibleKey == key { visibleKey = nil }
    }
    public func isVisible(_ key: String?) -> Bool {
        key != nil && visibleKey == key
    }
}
