// PAIRING STORED READINGS WITH THEIR OWN RAW CAPTURES.
//
// A reading in the field log and the raw-capture bundle it came from are two
// records of one measurement, and nothing on disk links them: the bundle keeps
// the mode/plot/tree it was taken under, the reading keeps its own, and neither
// carries the other's id. When the two disagree about the measurement — which
// they did, for the whole of one validation plot — repairing the reading means
// first deciding, defensibly, which bundle produced it.
//
// The key that works is (kind, tree, plot) plus TIME. A bundle is written
// within seconds of the reading it belongs to, and the next capture of the same
// tree is minutes away, so the true pair is far closer in time than any rival.
// Matching is greedy from the smallest gap and strictly one-to-one, which is
// the part that matters: a tree measured twice has two bundles and one
// surviving reading, and letting both bundles claim it would let a discarded
// attempt overwrite a good number.

import Foundation

public enum CaptureReadingMatch {

    /// One raw-capture bundle, reduced to what pairing and repair need.
    public struct Capture: Sendable, Equatable {
        public let bundleID: String
        public let kind: QuickMeasureEntry.Kind
        public let treeNumber: Int?
        public let plotID: UUID?
        public let createdAt: Date
        /// What the CURRENT estimator makes of this bundle's stored frames.
        public let value: Double
        /// The same frames read against the OTHER guide axis. This is what
        /// makes the repair identifiable rather than merely different: a
        /// reading that sits on this number instead of `value` was measured
        /// on the wrong axis. Nil when the capture has no bracket and so has
        /// no axis to be wrong about.
        public let oppositeAxisValue: Double?
        public let sigma: Double?

        public init(bundleID: String, kind: QuickMeasureEntry.Kind, treeNumber: Int?,
                    plotID: UUID?, createdAt: Date, value: Double,
                    oppositeAxisValue: Double?, sigma: Double?) {
            self.bundleID = bundleID
            self.kind = kind
            self.treeNumber = treeNumber
            self.plotID = plotID
            self.createdAt = createdAt
            self.value = value
            self.oppositeAxisValue = oppositeAxisValue
            self.sigma = sigma
        }
    }

    /// One reading that should be re-stated, and the value it must still hold
    /// for the repair to be allowed to land.
    public struct Repair: Sendable, Equatable {
        public let entryID: UUID
        public let bundleID: String
        public let expected: Double
        public let corrected: Double
        public let sigma: Double?
        public let gap: TimeInterval
    }

    /// A bundle is written seconds after its reading. Ninety is generous
    /// enough to survive a slow write and a hand-set minute, and far short of
    /// the gap to the next tree.
    public static let defaultWindow: TimeInterval = 90

    /// A reading is only claimed as axis-broken when it sits this close to
    /// the other axis's answer. The two axes differ by roughly 4:3, so 3 %
    /// separates "this reading IS the other axis" from "this reading is
    /// something else" by a wide margin.
    public static let axisMatchTolerance = 0.03

    /// The two axes must disagree by at least this much before either could
    /// be identified from the other. Below it there is nothing to diagnose.
    public static let axisSeparation = 0.10

    /// Every reading that was measured on the WRONG GUIDE AXIS.
    ///
    /// NOT every reading that differs from its bundle. Most differences are
    /// ordinary: the live estimate is a trimmed mean over five sub-samples of
    /// a full burst window, the bundle is a median over the five frames kept
    /// on disk, and those two honest computations of one capture land a
    /// percent or so apart. Rewriting field data over that would be churn,
    /// and it would hide the defect inside the noise — on the validation
    /// corpus "any difference" flags 179 readings where the actual bug
    /// touched 52.
    ///
    /// So a repair requires a POSITIVE IDENTIFICATION, not a disagreement:
    /// the stored reading must sit on the other axis's answer and not on this
    /// one. That is the signature the field data showed — 60 iOS readings
    /// disagreed with their bundle, and recomputing them on the opposite axis
    /// reproduced the stored number to a median ratio of 1.0000. Anything
    /// that fails the test is left exactly as the cruiser recorded it.
    ///
    /// A reading the cruiser typed is never proposed for repair either. They
    /// looked at the capture, disagreed with it, and typed over it; replaying
    /// the capture cannot overturn that.
    public static func repairs(
        captures: [Capture],
        entries: [QuickMeasureEntry],
        window: TimeInterval = defaultWindow,
        tolerance: Double = 1e-4
    ) -> [Repair] {
        // Candidate pairs, closest in time first.
        var candidates: [(gap: TimeInterval, c: Int, e: Int)] = []
        for (ci, c) in captures.enumerated() {
            guard c.value.isFinite, c.value > 0 else { continue }
            for (ei, e) in entries.enumerated() {
                guard e.kind == c.kind,
                      e.treeNumber == c.treeNumber,
                      samePlot(e.plotID, c.plotID),
                      e.captureMode != "typed",
                      e.method != QuickMeasureEntry.typedMethodRaw(for: e.kind)
                else { continue }
                let gap = abs(e.createdAt.timeIntervalSince(c.createdAt))
                if gap <= window { candidates.append((gap, ci, ei)) }
            }
        }
        candidates.sort { a, b in
            // Deterministic: ties break on the bundle id, never on array order.
            a.gap == b.gap ? captures[a.c].bundleID < captures[b.c].bundleID : a.gap < b.gap
        }

        var usedCapture = Set<Int>(), usedEntry = Set<Int>()
        var out: [Repair] = []
        for (gap, ci, ei) in candidates {
            guard !usedCapture.contains(ci), !usedEntry.contains(ei) else { continue }
            usedCapture.insert(ci); usedEntry.insert(ei)
            let c = captures[ci], e = entries[ei]
            guard abs(e.value - c.value) > tolerance,
                  isWrongAxisReading(reading: e.value, capture: c)
            else { continue }
            out.append(Repair(entryID: e.id, bundleID: c.bundleID,
                              expected: e.value, corrected: c.value,
                              sigma: c.sigma, gap: gap))
        }
        return out
    }

    /// Does this reading look like the wrong axis, rather than merely unlike
    /// the bundle?
    ///
    /// Three things must hold. The capture must HAVE another axis to have
    /// been read on. The two axes must be far enough apart to tell apart.
    /// And the reading must land on the other one — closer to it, and within
    /// a few per cent of it — which is the part that distinguishes a
    /// rescaled reading from a re-measure or a different frame set.
    public static func isWrongAxisReading(reading: Double, capture c: Capture) -> Bool {
        guard let other = c.oppositeAxisValue, other > 0, c.value > 0 else { return false }
        let separation = abs(other - c.value) / max(other, c.value)
        guard separation >= axisSeparation else { return false }
        let toOther = abs(reading - other) / other
        let toStored = abs(reading - c.value) / c.value
        return toOther <= axisMatchTolerance && toOther < toStored
    }

    /// Two plots match when they agree, or when either side never recorded
    /// one. A bundle taken before plots existed has no plot to compare, and
    /// refusing it would strand exactly the oldest captures — the ones most
    /// likely to need repairing.
    private static func samePlot(_ a: UUID?, _ b: UUID?) -> Bool {
        guard let a, let b else { return true }
        return a == b
    }

    /// The repair map `QuickMeasureHistory.repairValuesFromCaptures` takes.
    public static func map(_ repairs: [Repair])
        -> [UUID: (expected: Double, corrected: Double, sigma: Double?)] {
        var out: [UUID: (expected: Double, corrected: Double, sigma: Double?)] = [:]
        for r in repairs {
            out[r.entryID] = (expected: r.expected, corrected: r.corrected, sigma: r.sigma)
        }
        return out
    }
}
