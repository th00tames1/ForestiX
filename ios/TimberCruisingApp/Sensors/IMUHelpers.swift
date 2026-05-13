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

    /// Back-camera elevation angle from horizontal, derived from a
    /// gravity vector in the device's own frame (Apple convention:
    /// +X right, +Y top of device, +Z out of the screen toward the user).
    ///
    /// Sign convention — POSITIVE when the back camera is aimed ABOVE
    /// horizontal (toward sky), NEGATIVE when aimed BELOW (toward
    /// ground). This matches the §7.2 height formula
    /// `H = d_h · (tan α_top − tan α_base)` which assumes α_top > α_base
    /// for trees taller than eye level.
    ///
    /// Reference postures, regardless of how the phone is rolled around
    /// the screen-out (Z) axis:
    /// - Back camera at horizon:                       0
    /// - Back camera at zenith (looking straight up):  +π/2
    /// - Back camera at nadir  (looking straight down): −π/2
    ///
    /// Why `atan2(g.z, sqrt(g.x²+g.y²))` rather than `atan2(g.z, -g.y)`:
    /// the back-camera direction in the device frame is body −Z. Its
    /// elevation from the horizontal plane is the angle between body −Z
    /// and the gravity-orthogonal plane, which depends ONLY on g.z
    /// (= sin(elevation)). Using `-g.y` as the adjacent component made
    /// the formula collapse to ±π/2 in landscape orientations — gravity
    /// moves out of the Y axis into X, so `−g.y → 0` and atan2 returns
    /// ±π/2 independent of the actual aim. Real-device test on
    /// 2026-04-28 captured α_top = +80°, α_base = −88° on a desk
    /// (correct values were ≈ −25° and −44°) producing H = 51 m on a
    /// 0.75 m desk. The roll-invariant form fixes that.
    @inlinable
    public static func pitchFromGravity(_ g: SIMD3<Double>) -> Double {
        let horiz = (g.x * g.x + g.y * g.y).squareRoot()
        return atan2(g.z, horiz)
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

    /// Most recent buffered sample's pitch, if any. Used by the scan
    /// view model as a last-resort fallback when the preferred ±200 ms
    /// and ±600 ms windows are both empty (e.g. the IMU stream paused
    /// for longer than expected) — always beats silently dropping the
    /// user's tap.
    public func mostRecentPitch() -> Double? {
        samples.last?.pitchRad
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
