// Robust moving average for AR-estimated distances (Hampel-style).
//
// ARKit's estimated-plane raycast distance flickers frame-to-frame —
// single-frame spikes plus low-frequency wander — where the LiDAR path
// is stable. This smoother keeps a short sliding window of recent
// samples and publishes the mean of the samples that agree with the
// window median (outliers beyond max(floor, k·MAD) are excluded from
// the average). Spikes DO stay in the buffer: if a "spike" persists
// (the cruiser actually walked closer), the median follows it within a
// few frames, so genuine movement tracks while flicker never shows.
//
// Used by the AR (non-LiDAR) distance paths only: the caliper distance
// and the live distance readout in AR mode. Mirror of the Android
// sensors/DistanceSmoother.kt — keep the maths identical.

import Foundation

public final class DistanceSmoother {

    private struct Sample {
        let value: Double
        let time: TimeInterval
    }

    private var buffer: [Sample] = []
    private let capacity: Int
    private let maxAgeSec: TimeInterval
    private let madMultiplier: Double
    /// MAD of a genuinely stable signal can collapse to ~0, which would
    /// reject everything — this floor keeps a few cm of slack.
    private let toleranceFloorM: Double

    public init(capacity: Int = 12,
                maxAgeSec: TimeInterval = 1.2,
                madMultiplier: Double = 3.0,
                toleranceFloorM: Double = 0.03) {
        self.capacity = capacity
        self.maxAgeSec = maxAgeSec
        self.madMultiplier = madMultiplier
        self.toleranceFloorM = toleranceFloorM
    }

    public func add(_ value: Double,
                    at time: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        guard value.isFinite, value > 0 else { return }
        buffer.append(Sample(value: value, time: time))
        prune(now: time)
    }

    public func reset() {
        buffer.removeAll(keepingCapacity: true)
    }

    /// Robust mean of the recent window; nil when no recent samples.
    public func value(now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> Double? {
        prune(now: now)
        guard let last = buffer.last else { return nil }
        let values = buffer.map(\.value)
        guard values.count >= 3 else { return last.value }

        let sorted = values.sorted()
        let median = sorted[sorted.count / 2]
        let deviations = values.map { abs($0 - median) }.sorted()
        let mad = deviations[deviations.count / 2]
        let tolerance = max(toleranceFloorM, madMultiplier * mad)
        let kept = values.filter { abs($0 - median) <= tolerance }
        guard !kept.isEmpty else { return median }
        return kept.reduce(0, +) / Double(kept.count)
    }

    private func prune(now: TimeInterval) {
        buffer.removeAll { now - $0.time > maxAgeSec }
        if buffer.count > capacity {
            buffer.removeFirst(buffer.count - capacity)
        }
    }
}
