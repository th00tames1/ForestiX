import Foundation

/// Height uses horizontal range to the same trunk, not absolute 3-D camera speed.
/// Predictions verify *measured* ranges; they never become a measurement.
public struct CameraPoseSpeedGuard {
    public enum Verdict { case usable, ignored, checking, retake }
    public static let maximumSpeedMetersPerSecond: Float = 4
    private struct Sample {
        let p: SIMD3<Float>, offset: SIMD2<Float>, r: Float, t: Double
    }
    private var previous: Sample?, last: Sample?, latestTime: Double?
    private var quick = false
    private var suspectRange: Float?, suspectError: Float = 0
    private var witness: Sample?, station: Sample?, recent: Sample?
    private var stationCount = 0
    public private(set) var state = Verdict.ignored
    public init() {}
    public mutating func reset() { self = Self() }

    /// No observation is not evidence of a different distance. Keep the last
    /// trusted frame but do not count repeated UI polls or time spent blind.
    @discardableResult public mutating func interrupt() -> Verdict {
        if state == .retake { return state }
        if state != .checking { quick = previous != nil; suspectRange = nil }
        else if witness != nil || recent != nil { quick = false }
        witness = nil; station = nil; recent = nil; stationCount = 0
        state = last == nil ? .ignored : .checking
        return state
    }
    private mutating func admit(_ s: Sample, after preceding: Sample?) -> Verdict {
        previous = preceding; last = s; quick = false; witness = nil; suspectRange = nil
        station = nil; recent = nil; stationCount = 0; state = .usable
        return state
    }
    private func length(_ v: SIMD2<Float>) -> Float { (v.x*v.x + v.y*v.y).squareRoot() }
    public mutating func observe(position p: SIMD3<Float>?, timestamp t: Double?,
                                 trackedAnchor a: SIMD3<Float>?) -> Verdict {
        guard state != .retake else { return state }
        guard let t, t.isFinite else { return interrupt() }
        if let newest = latestTime, t - newest < 0.01 {
            if state == .checking { return state }
            if t == newest, let last, last.t == t, let p, last.p == p, let a,
               last.offset == SIMD2(p.x-a.x, p.z-a.z) { return .usable }
            return .ignored
        }
        latestTime = t
        guard let p, let a, p.x.isFinite, p.y.isFinite, p.z.isFinite,
              a.x.isFinite, a.y.isFinite, a.z.isFinite else { return interrupt() }
        let offset = SIMD2(p.x-a.x,p.z-a.z), r = length(offset)
        guard r.isFinite else { return interrupt() }
        let s = Sample(p:p, offset:offset, r:r, t:t)
        guard let last else { return admit(s, after:nil) }
        let elapsed = t-last.t
        let radialDelta = abs(r-last.r)
        let tol = min(0.10, max(0.025, 0.008*last.r))
        if state != .checking {
            // Test motion in trunk-relative horizontal coordinates. Identical
            // translations of both endpoints cancel even while the operator walks.
            let limit = Self.maximumSpeedMetersPerSecond * Float(elapsed)
            // A camera-only scale jump can hide below 4 m/s at low frame rates.
            // Allow walking acceleration (4 m/s²) and range noise before challenging it.
            var radialImpulse = false
            if let pre = previous, last.t-pre.t <= 0.30, elapsed <= 0.30 {
                let dt = Float(elapsed), dt0 = Float(last.t-pre.t)
                let expectedStep = (last.r-pre.r)*dt/dt0
                let step = r-last.r, surprise = step-expectedStep
                let floor = max(0.50,0.025*last.r)
                radialImpulse = abs(step) > floor && step*surprise > 0 && abs(surprise) > floor + 2*dt*(dt+dt0)
            }
            if elapsed <= 0.5 && length(offset-last.offset) <= limit && radialDelta <= limit && !radialImpulse {
                return admit(s, after:last)
            }
            quick = false
            if let previous, elapsed <= 0.30, last.t-previous.t <= 0.30 {
                quick = true; suspectRange = r
                let rate = (last.r-previous.r)/Float(last.t-previous.t)
                suspectError = r - (last.r+rate*Float(elapsed))
            }
            state = .checking
            return state  // never use the suspicious frame as its own confirmation
        }

        // A briefly disturbed trajectory must fit its previous radial velocity
        // in two distinct subsequent frames. One mismatch closes the fast path.
        if quick, let pre = previous, elapsed <= 0.70 {
            let rate = (last.r-pre.r)/Float(last.t-pre.t)
            let from = witness ?? last
            let reversed = suspectRange.map { (r-$0)*(suspectError >= 0 ? 1 : -1) <= -0.35*abs(suspectError) } ?? true
            if (witness != nil || suspectRange == nil || abs(suspectError) <= tol || reversed) &&
                abs(rate) <= Self.maximumSpeedMetersPerSecond &&
                abs(r-(last.r+rate*Float(elapsed))) <= tol &&
                abs(r-from.r) <= Self.maximumSpeedMetersPerSecond*Float(t-from.t) &&
                (witness == nil || t-from.t <= 0.30) {
                if let witness { return admit(s, after:witness) }
                witness = s
            } else { quick = false; witness = nil }
        } else { quick = false; witness = nil }

        // In-place recheck: no clock-only timeout and no extrapolation across
        // a pause. An unchanged *wrong* plateau is not proof of recovery.
        let near = radialDelta <= tol
        if let first = station, let recent, t-recent.t <= 0.30,
           length(offset-first.offset) <= 0.08,
           near == (abs(first.r-last.r) <= tol) {
            stationCount += 1
            if near && stationCount >= 3 && t-first.t >= 0.35 { return admit(s, after:recent) }
            if !near && stationCount >= 6 && t-first.t >= 2.0 { state = .retake; return state }
        } else { station = s; stationCount = 1 }
        recent = s
        return state
    }
}
