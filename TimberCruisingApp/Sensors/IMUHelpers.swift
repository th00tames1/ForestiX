// Spec §7.2 + REQ-HGT-004. IMU utilities used by the height measurement
// flow to timestamp pitch samples and compute the ±200 ms median pitch
// around a tap event.
//
// Three pieces:
//   1. `IMUHelpers.pitchFromGravity` — pure math, derives portrait pitch
//      from a CMDeviceMotion gravity vector. Cross-platform so tests run.
//   2. `IMUPitchBuffer`              — bounded ring buffer of timestamped
//      pitch samples plus a windowed median. Cross-platform.
//   3. `IMUMotionService`            — CMMotionManager wrapper that drives
//      the buffer at 100 Hz. iOS-only; macOS compiles a no-op stub.
//
// The split keeps the §7.2 tap-median logic testable on developer macs
// without CoreMotion while the real CMMotionManager wiring is used on
// device.

import Foundation
import simd

#if canImport(CoreMotion) && os(iOS)
import CoreMotion
#endif

// MARK: - Pitch sample

public struct IMUPitchSample: Equatable, Sendable {
    public let timestamp: TimeInterval
    public let pitchRad: Double

    public init(timestamp: TimeInterval, pitchRad: Double) {
        self.timestamp = timestamp
        self.pitchRad = pitchRad
    }
}

// MARK: - Pure math

public enum IMUHelpers {

    /// Portrait-orientation pitch derived from a gravity vector in the
    /// device's own frame (Apple convention: +X right, +Y up, +Z out of
    /// the screen toward the user).
    ///
    /// Reference postures (portrait-locked phone, back camera):
    /// - Upright portrait, camera at horizon: g ≈ (0, -1,  0) → pitch =   0
    /// - Flat face-up, camera at zenith:     g ≈ (0,  0, -1) → pitch = +π/2
    /// - Flat face-down, camera at ground:   g ≈ (0,  0, +1) → pitch = -π/2
    @inlinable
    public static func pitchFromGravity(_ g: SIMD3<Double>) -> Double {
        // atan2(y, x): use (-g.z, -g.y) so tilting the top of the phone
        // backwards (sky) gives a positive angle and forwards (ground) a
        // negative one.
        atan2(-g.z, -g.y)
    }

    @inlinable
    public static func pitchFromGravity(_ g: SIMD3<Float>) -> Double {
        pitchFromGravity(SIMD3<Double>(Double(g.x), Double(g.y), Double(g.z)))
    }
}

// MARK: - Pitch buffer

/// Bounded ring buffer of `IMUPitchSample`s with constant-time append and
/// O(k log k) median over a time window (k = samples in window, ≤ ~40 for
/// a 400 ms window at 100 Hz).
///
/// The buffer is thread-confined by convention; call sites are expected
/// to drive it from a single actor (the HeightScanViewModel's
/// `@MainActor` context). Keeping it plain (not an `actor`) lets the
/// view model compute medians synchronously inside a tap handler.
public final class IMUPitchBuffer {

    /// Retention window. Any sample older than `retention` seconds behind
    /// the newest timestamp is evicted on append. 5 s is comfortably
    /// longer than the ±200 ms median window plus any tap latency.
    public let retention: TimeInterval

    private var samples: [IMUPitchSample] = []

    public init(retention: TimeInterval = 5.0) {
        self.retention = retention
    }

    public var count: Int { samples.count }

    public func append(_ sample: IMUPitchSample) {
        samples.append(sample)
        evictOlderThan(sample.timestamp - retention)
    }

    public func append(timestamp: TimeInterval, pitchRad: Double) {
        append(IMUPitchSample(timestamp: timestamp, pitchRad: pitchRad))
    }

    /// Median pitch over `[center − windowMs/2, center + windowMs/2]`.
    /// Returns nil if the window contains no samples.
    public func medianPitch(centeredOn center: TimeInterval,
                            windowMs: Double = 400) -> Double? {
        let half = (windowMs / 1000.0) / 2.0
        let lo = center - half
        let hi = center + half
        let window = samples
            .filter { $0.timestamp >= lo && $0.timestamp <= hi }
            .map { $0.pitchRad }
            .sorted()
        guard !window.isEmpty else { return nil }
        let mid = window.count / 2
        if window.count % 2 == 0 {
            return (window[mid - 1] + window[mid]) / 2.0
        } else {
            return window[mid]
        }
    }

    /// Count of samples in the given window — surfaced by the view model
    /// for REQ-HGT-004 "sample count logged".
    public func sampleCount(centeredOn center: TimeInterval,
                            windowMs: Double = 400) -> Int {
        let half = (windowMs / 1000.0) / 2.0
        let lo = center - half
        let hi = center + half
        return samples.reduce(0) { $0 + (($1.timestamp >= lo && $1.timestamp <= hi) ? 1 : 0) }
    }

    public func removeAll() {
        samples.removeAll()
    }

    private func evictOlderThan(_ cutoff: TimeInterval) {
        if let firstKeep = samples.firstIndex(where: { $0.timestamp >= cutoff }) {
            if firstKeep > 0 { samples.removeFirst(firstKeep) }
        } else {
            samples.removeAll()
        }
    }
}

// MARK: - Motion service

#if canImport(CoreMotion) && os(iOS)

/// CMMotionManager wrapper running at 100 Hz. Streams device-motion
/// samples into the supplied `IMUPitchBuffer`. Call `start()` on scan
/// enter and `stop()` on exit.
@MainActor
public final class IMUMotionService {

    public let buffer: IMUPitchBuffer
    private let motion: CMMotionManager
    private let queue: OperationQueue
    private let sampleRateHz: Double

    public init(buffer: IMUPitchBuffer, sampleRateHz: Double = 100) {
        self.buffer = buffer
        self.sampleRateHz = sampleRateHz
        self.motion = CMMotionManager()
        self.queue = OperationQueue()
        self.queue.name = "com.forestix.imu.motion"
        self.queue.qualityOfService = .userInitiated
    }

    public func start() {
        guard motion.isDeviceMotionAvailable else { return }
        motion.deviceMotionUpdateInterval = 1.0 / sampleRateHz
        motion.startDeviceMotionUpdates(
            using: .xArbitraryZVertical,
            to: queue
        ) { [weak self] data, _ in
            guard let self, let d = data else { return }
            let g = SIMD3<Double>(d.gravity.x, d.gravity.y, d.gravity.z)
            let pitch = IMUHelpers.pitchFromGravity(g)
            let sample = IMUPitchSample(timestamp: d.timestamp, pitchRad: pitch)
            Task { @MainActor [weak self] in
                self?.buffer.append(sample)
            }
        }
    }

    public func stop() {
        motion.stopDeviceMotionUpdates()
    }
}

#else

/// macOS stand-in for tests and previews. `start`/`stop` are no-ops and
/// the buffer stays empty unless callers inject samples directly.
@MainActor
public final class IMUMotionService {

    public let buffer: IMUPitchBuffer

    public init(buffer: IMUPitchBuffer, sampleRateHz: Double = 100) {
        self.buffer = buffer
        _ = sampleRateHz
    }

    public func start() {}
    public func stop() {}
}

#endif
