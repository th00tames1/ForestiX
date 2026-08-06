// RAW-CAPTURE REPLAY — the re-run engine + recorder. Companion to
// RawCaptureStore.swift (models + disk).
//
// RawCaptureReplay reconstructs estimator inputs from a stored bundle and
// calls the SAME production estimator entry points (DBHEstimator /
// HeightEstimator) — no forked math, so re-running current OR updated code
// on field data is a pure function of the bytes on disk.
//
// RawCaptureRecorder serializes a bundle at capture time and immediately
// runs the reproducibility self-check: reload the bundle from disk, re-run
// the current estimator, and compare to the value computed live over the
// same canonical frames. Deterministic RANSAC + pure chord math ⇒ the
// round-trip is exact (delta ≈ 0). A structural failure (a frame that
// won't reconstruct, an estimator that returns nil) still SAVES the
// bundle, flagged `fail`.

import Foundation
import simd
import Common
import Models

// MARK: - Replay (re-run current estimators from stored bytes)

public enum RawCaptureReplay {

    /// Relative tolerance for the self-check pass/fail decision.
    public static let tolerance: Double = 1e-3

    // MARK: DBH

    /// The estimator inputs reconstructed once from a DBH bundle's stored
    /// bytes — depth frames + tap + axis + calibration + the manual bracket.
    /// Every candidate geometry in the sweep runs from this SAME object, so
    /// the algorithms are compared on identical inputs.
    public struct DBHReplayInputs: Sendable {
        public let frames: [ARDepthFrame]
        public let tap: SIMD2<Double>
        public let axis: GuideAxis
        public let cal: ProjectCalibration
        public let bracket: RawCaptureManifest.DBHBundle.Bracket
    }

    /// Rebuild the canonical estimator inputs from a DBH bundle's stored
    /// depth+intrinsics+pose bytes. Returns nil only when the bundle can't
    /// be reconstructed (missing depth files, empty frame list).
    public static func reconstructDBHInputs(
        manifest m: RawCaptureManifest, id: String) -> DBHReplayInputs? {
        guard let dbh = m.dbh, !dbh.frames.isEmpty else { return nil }
        let dir = RawCaptureStore.bundleDirectory(id: id)

        var frames: [ARDepthFrame] = []
        frames.reserveCapacity(dbh.frames.count)
        for meta in dbh.frames {
            let count = meta.width * meta.height
            guard let depth = RawCaptureStore.readDepth(
                url: dir.appendingPathComponent(meta.depthFile), count: count)
            else { return nil }
            frames.append(RawCaptureFrame.reconstruct(from: meta, depth: depth))
        }

        // Tap + axis reconstruct exactly from the stored per-frame metadata
        // (pickGuideAxis derives the fixed row/col from the tap, so
        // tap + "row"/"col" round-trips to the same GuideAxis).
        let tapArr = dbh.frames[0].tapPx
        let tap = SIMD2<Double>(tapArr.first ?? 0, tapArr.count > 1 ? tapArr[1] : 0)
        let axis = guideAxis(from: dbh.frames[0].axis, tap: tap)
        let cal = calibration(from: m.settings.calibration)
        return DBHReplayInputs(
            frames: frames, tap: tap, axis: axis, cal: cal, bracket: dbh.bracket)
    }

    /// Reconstruct the DBH estimator input from the bundle and run the SAME
    /// production entry point the live capture used (chord / partial-arc /
    /// manual bracket). This is the reproducibility-self-check path — it
    /// re-runs ONLY the algorithm recorded in the manifest, unchanged.
    /// Returns nil only when the bundle can't be reconstructed.
    public static func rerunDBH(manifest m: RawCaptureManifest, id: String) -> DBHResult? {
        guard let inp = reconstructDBHInputs(manifest: m, id: id) else { return nil }

        if inp.bracket.enabled {
            return DBHEstimator.bracketChordEstimate(
                frames: inp.frames,
                guideAxis: inp.axis,
                leftFraction: inp.bracket.left,
                rightFraction: inp.bracket.right,
                calibration: inp.cal)
        }
        let input = DBHScanInput(
            frames: inp.frames, tapPixel: inp.tap, guideAxis: inp.axis,
            projectCalibration: inp.cal)
        switch m.settings.algorithm {
        case "arc": return DBHEstimator.estimate(input: input)
        default:    return DBHEstimator.chordEstimate(input: input)   // "silhouette"
        }
    }

    /// The same bundle read against the OTHER guide axis.
    ///
    /// Not an algorithm variant — a diagnostic. The live estimator and the
    /// recorder used to choose the walk axis separately, and when they chose
    /// differently the bracket fractions were measured against the other
    /// extent (256 px against 192), scaling that reading by about 4:3 with
    /// nothing on screen to show for it.
    ///
    /// Given a stored reading that disagrees with its bundle, comparing it
    /// with BOTH readings of that bundle says which of the two disagreements
    /// it is: a reading that lands on this value and not on `rerunDBH`'s was
    /// computed on the wrong axis, and a reading that matches neither differs
    /// for some ordinary reason — a different set of frames, a re-measure —
    /// and must be left alone. Only the first is repairable, and only that
    /// identification makes an automatic rewrite of field data defensible.
    ///
    /// Nil when the bundle has no bracket: the auto path walks out from the
    /// tap rather than reading fractions off an extent, so it has no 4:3 to
    /// be wrong by.
    public static func rerunDBHOppositeAxis(manifest m: RawCaptureManifest,
                                            id: String) -> Double? {
        guard let inp = reconstructDBHInputs(manifest: m, id: id),
              inp.bracket.enabled,
              let first = inp.frames.first
        else { return nil }
        // Flip row<->col about the same tap. `guideAxis(from:tap:)` builds one
        // from the stored string, so the flip is that string's counterpart.
        let flipped: GuideAxis
        switch inp.axis {
        case .row: flipped = .col(x: Int(inp.tap.x.rounded()))
        case .col: flipped = .row(y: Int(inp.tap.y.rounded()))
        }
        // A tap outside the flipped extent has no line to walk.
        switch flipped {
        case .row(let y): guard y >= 0, y < first.height else { return nil }
        case .col(let x): guard x >= 0, x < first.width else { return nil }
        }
        return DBHEstimator.bracketChordEstimate(
            frames: inp.frames,
            guideAxis: flipped,
            leftFraction: inp.bracket.left,
            rightFraction: inp.bracket.right,
            calibration: inp.cal).map { Double($0.diameterCm) }
    }

    // MARK: DBH multi-algorithm sweep (accuracy validation)

    /// One candidate DBH geometry in the accuracy-validation sweep. `run`
    /// returns the estimated diameter (cm) from the shared inputs, or nil
    /// when the algorithm cannot be applied to a given capture (e.g. the
    /// manual bracket-chord on an auto capture that has no bracket) — that
    /// capture is then N/A for this algorithm rather than a sweep failure.
    public struct DBHCandidate: Sendable {
        public let id: String            // stable tag, pooled with Android
        public let name: String          // display label
        public let run: @Sendable (DBHReplayInputs) -> Double?
    }

    /// THE REGISTRY. Adding a new candidate algorithm to the sweep is one
    /// entry here — nothing else changes. Seeded with the four existing
    /// geometries. A `.red` (rejected) fit is treated as N/A, not a 0 cm
    /// estimate, so a failed fit never pollutes the error metrics.
    public static let dbhCandidates: [DBHCandidate] = [
        DBHCandidate(id: "silhouette", name: "Chord (silhouette)") { inp in
            let input = DBHScanInput(
                frames: inp.frames, tapPixel: inp.tap, guideAxis: inp.axis,
                projectCalibration: inp.cal)
            return acceptedDiameterCm(DBHEstimator.chordEstimate(input: input))
        },
        DBHCandidate(id: "arc", name: "Partial-arc circle fit") { inp in
            let input = DBHScanInput(
                frames: inp.frames, tapPixel: inp.tap, guideAxis: inp.axis,
                projectCalibration: inp.cal)
            return acceptedDiameterCm(DBHEstimator.estimate(input: input))
        },
        DBHCandidate(id: "depthBand", name: "Depth-band chord") { inp in
            acceptedDiameterCm(DBHEstimator.depthBandChordEstimate(
                frames: inp.frames, tapPixel: inp.tap, guideAxis: inp.axis,
                calibration: inp.cal))
        },
        DBHCandidate(id: "bracket", name: "Manual bracket-chord") { inp in
            guard inp.bracket.enabled else { return nil }   // N/A: no bracket
            return acceptedDiameterCm(DBHEstimator.bracketChordEstimate(
                frames: inp.frames, guideAxis: inp.axis,
                leftFraction: inp.bracket.left, rightFraction: inp.bracket.right,
                calibration: inp.cal))
        },
    ]

    /// A fit is usable only if it exists and wasn't rejected (`.red` → 0 cm).
    static func acceptedDiameterCm(_ r: DBHResult?) -> Double? {
        guard let r, r.confidence != .red else { return nil }
        return Double(r.diameterCm)
    }

    /// One capture's value under every registered algorithm.
    public struct DBHSweepEntry: Sendable {
        public let algorithmId: String
        public let name: String
        public let value: Double?        // nil = N/A for this capture
    }

    /// Run a DBH capture through ALL candidate geometries from the SAME
    /// stored bytes. Returns one entry per registered algorithm (in registry
    /// order), or nil when the bundle can't be reconstructed at all.
    public static func sweepDBH(manifest m: RawCaptureManifest, id: String) -> [DBHSweepEntry]? {
        guard let inp = reconstructDBHInputs(manifest: m, id: id) else { return nil }
        return dbhCandidates.map {
            DBHSweepEntry(algorithmId: $0.id, name: $0.name, value: $0.run(inp))
        }
    }

    // MARK: Corpus aggregation + ranking

    /// Per-algorithm accuracy over the captures that have a truth value.
    /// Carries BOTH the raw error stats and an in-sample linear-correction
    /// fit `truth ≈ a + b·estimate` (OLS). `rmseCorrected` is the RMSE after
    /// applying that fit to the same captures — a CALIBRATION-fit number, not
    /// held-out, so it tells you the bias/slope to bake in, not out-of-sample
    /// accuracy. When the fit is degenerate (`fitted == false`) a/b are the
    /// identity (0, 1) and `rmseCorrected == rmse`.
    public struct AlgorithmRanking: Sendable {
        public let algorithmId: String
        public let name: String
        public let n: Int              // captures scored (had truth + a value)
        public let bias: Double        // mean(estimate − truth), signed
        public let rmse: Double        // raw root-mean-square error
        public let mae: Double         // mean absolute error
        public let fitted: Bool        // OLS correction fit succeeded
        public let a: Double           // fitted intercept  (truth ≈ a + b·est)
        public let b: Double           // fitted slope
        public let rmseCorrected: Double  // in-sample RMSE after the fit
    }

    /// In-sample ordinary-least-squares fit of `truth ≈ a + b·estimate` over
    /// one algorithm's (estimate, truth) pairs, plus the resulting corrected
    /// RMSE. Degenerate cases — fewer than two pairs, or zero variance in the
    /// estimates — yield NO fit: identity (a=0, b=1) and corrected = raw, with
    /// `fitted == false` so the UI can mark it. Guards against NaN/inf slopes.
    public struct CorrectionFit: Sendable {
        public let fitted: Bool
        public let a: Double
        public let b: Double
        public let rmseCorrected: Double
    }

    static func fitCorrection(
        pairs: [(est: Double, truth: Double)], rawRmse: Double) -> CorrectionFit {
        let n = pairs.count
        guard n >= 2 else {
            return CorrectionFit(fitted: false, a: 0, b: 1, rmseCorrected: rawRmse)
        }
        let meanX = pairs.reduce(0) { $0 + $1.est } / Double(n)
        let meanY = pairs.reduce(0) { $0 + $1.truth } / Double(n)
        var sxx = 0.0, sxy = 0.0
        for p in pairs {
            let dx = p.est - meanX
            sxx += dx * dx
            sxy += dx * (p.truth - meanY)
        }
        guard sxx > 0, sxy.isFinite else {
            return CorrectionFit(fitted: false, a: 0, b: 1, rmseCorrected: rawRmse)
        }
        let b = sxy / sxx
        let a = meanY - b * meanX
        guard a.isFinite, b.isFinite else {
            return CorrectionFit(fitted: false, a: 0, b: 1, rmseCorrected: rawRmse)
        }
        let sse = pairs.reduce(0.0) { acc, p in
            let r = p.truth - (a + b * p.est)
            return acc + r * r
        }
        return CorrectionFit(fitted: true, a: a, b: b,
                             rmseCorrected: (sse / Double(n)).squareRoot())
    }

    /// Minimum scored captures before the "best" row may be highlighted as a
    /// winner. Under this, an RMSE ordering is noise — the table still shows
    /// every number, it just doesn't crown anything. Shared with Android.
    public static let minRankingN: Int = 5

    /// Build the ranking rows shared by the DBH and height sweeps: raw
    /// {n, bias, RMSE, MAE} plus the OLS correction fit, sorted best-first by
    /// RAW RMSE (algorithms with no scored captures sink last). `order` is the
    /// registry order (id, name); `pairsById` the (estimate, truth) pairs
    /// collected per algorithm id.
    static func buildRankings(
        order: [(id: String, name: String)],
        pairsById: [String: [(est: Double, truth: Double)]]
    ) -> [AlgorithmRanking] {
        var rankings: [AlgorithmRanking] = order.map { c in
            let ps = pairsById[c.id] ?? []
            let n = ps.count
            let errs = ps.map { $0.est - $0.truth }
            let bias = n > 0 ? errs.reduce(0, +) / Double(n) : 0
            let mae = n > 0 ? errs.map { abs($0) }.reduce(0, +) / Double(n) : 0
            let rmse = n > 0 ? (errs.map { $0 * $0 }.reduce(0, +) / Double(n)).squareRoot() : 0
            let fit = fitCorrection(pairs: ps, rawRmse: rmse)
            return AlgorithmRanking(
                algorithmId: c.id, name: c.name, n: n, bias: bias, rmse: rmse, mae: mae,
                fitted: fit.fitted, a: fit.a, b: fit.b, rmseCorrected: fit.rmseCorrected)
        }
        // Best-first by RAW RMSE; algorithms with no scored captures sink last.
        // (Corrected RMSE is surfaced in the table but does NOT reorder the
        // ranking — it's an in-sample calibration number, not held-out.)
        rankings.sort { a, b in
            if (a.n == 0) != (b.n == 0) { return b.n == 0 }
            return a.rmse < b.rmse
        }
        return rankings
    }

    /// One capture's sweep alongside its truth, with the closest algorithm.
    public struct DBHSweepCapture: Sendable {
        public let id: String
        public let treeNumber: Int?
        public let truth: Double
        public let entries: [DBHSweepEntry]
        public let winnerId: String?   // algorithm closest to truth
    }

    /// The full ranked report the "Compare algorithms" view renders.
    ///
    /// HONEST SKIP ACCOUNTING (S6): the two skip reasons are counted
    /// SEPARATELY, so `scored + skippedNoTruth + skippedUnreadable == total`
    /// always holds. Previously an unreadable bundle (missing depth file,
    /// truncated write) was tallied under "no truth" and vanished — exactly
    /// the class of data loss this corpus can't afford.
    public struct DBHSweepReport: Sendable {
        public let rankings: [AlgorithmRanking]   // best-first (lowest RMSE)
        public let perCapture: [DBHSweepCapture]  // only truthed captures
        public let totalDBH: Int
        public let scored: Int                    // truthed & reconstructable
        public let skippedNoTruth: Int            // truth never entered
        public let skippedUnreadable: Int         // truthed but won't reconstruct
    }

    /// Sweep every DBH capture and rank the algorithms best-first by RMSE
    /// against the hand-measured truth. Captures with no truth, and captures
    /// whose bytes won't reconstruct, are counted separately.
    /// Pure + Sendable — the caller runs it off the main actor.
    public static func rankDBH(_ summaries: [RawCaptureSummary]) -> DBHSweepReport {
        var totalDBH = 0
        var skipped = 0
        var unreadable = 0
        var scored = 0
        // Per-algorithm (estimate, truth) pairs, keyed by registry id — feeds
        // both the raw error stats and the OLS correction fit.
        var pairs: [String: [(est: Double, truth: Double)]] = [:]
        var perCapture: [DBHSweepCapture] = []

        for sum in summaries where sum.manifest.kind == "dbh" {
            totalDBH += 1
            guard let truth = sum.manifest.truth.value else { skipped += 1; continue }
            guard let entries = sweepDBH(manifest: sum.manifest, id: sum.id) else {
                unreadable += 1; continue
            }
            scored += 1
            var winnerId: String?
            var winnerErr = Double.infinity
            for e in entries {
                guard let v = e.value else { continue }
                pairs[e.algorithmId, default: []].append((est: v, truth: truth))
                let err = abs(v - truth)
                if err < winnerErr { winnerErr = err; winnerId = e.algorithmId }
            }
            perCapture.append(DBHSweepCapture(
                id: sum.id, treeNumber: sum.manifest.context.treeNumber,
                truth: truth, entries: entries, winnerId: winnerId))
        }

        let rankings = buildRankings(
            order: dbhCandidates.map { ($0.id, $0.name) }, pairsById: pairs)

        return DBHSweepReport(
            rankings: rankings, perCapture: perCapture,
            totalDBH: totalDBH, scored: scored,
            skippedNoTruth: skipped, skippedUnreadable: unreadable)
    }

    // MARK: Height

    public struct HeightRerun: Sendable {
        public let result: HeightResult          // from stored d_h + angles
        public let reposed: HeightResult?        // d_h re-derived from stored poses
    }

    /// Re-run the height estimator two ways: from the stored standing pose
    /// (base aim), and with d_h re-derived from the final stored 5 Hz camera
    /// pose. Both call the production `HeightEstimator.estimate`.
    public static func rerunHeight(manifest m: RawCaptureManifest) -> HeightRerun? {
        guard let h = m.height, h.anchor.world.count == 3 else { return nil }
        let anchor = SIMD3<Float>(Float(h.anchor.world[0]),
                                  Float(h.anchor.world[1]),
                                  Float(h.anchor.world[2]))
        let standing = RawCaptureMatrix.translation(h.base.cameraPose)
        let alphaTop = Float(h.top.pitchDeg * .pi / 180)
        let alphaBase = Float(h.base.pitchDeg * .pi / 180)
        let cal = calibration(from: m.settings.calibration)

        let input = HeightMeasureInput(
            anchorPointWorld: anchor,
            standingPointWorld: standing,
            alphaTopRad: alphaTop,
            alphaBaseRad: alphaBase,
            trackingStateWasNormalThroughout: true,
            projectCalibration: cal)
        let primary = HeightEstimator.estimate(input: input)

        var reposed: HeightResult?
        if let last = h.poseSamples.last {
            let standingReposed = RawCaptureMatrix.translation(last.pose)
            let input2 = HeightMeasureInput(
                anchorPointWorld: anchor,
                standingPointWorld: standingReposed,
                alphaTopRad: alphaTop,
                alphaBaseRad: alphaBase,
                trackingStateWasNormalThroughout: true,
                projectCalibration: cal)
            reposed = HeightEstimator.estimate(input: input2)
        }
        return HeightRerun(result: primary, reposed: reposed)
    }

    // MARK: Height multi-algorithm sweep (accuracy validation)

    /// The height estimator inputs reconstructed once from a height bundle's
    /// STORED tangent geometry — anchor, standing point (base-aim camera pose
    /// translation), the two aim pitches, d_h, and calibration. Every height
    /// candidate runs from this SAME object, so the algorithms are compared on
    /// identical stored inputs (no re-collection, no new geometry).
    public struct HeightReplayInputs: Sendable {
        public let anchor: SIMD3<Float>
        public let standing: SIMD3<Float>
        public let alphaTopRad: Float
        public let alphaBaseRad: Float
        public let dHM: Double
        public let cal: ProjectCalibration
    }

    /// Rebuild the canonical height estimator inputs from the stored bundle
    /// (mirrors `rerunHeight`'s stored-geometry path). Returns nil only when
    /// the bundle has no reconstructable height geometry.
    public static func reconstructHeightInputs(
        manifest m: RawCaptureManifest) -> HeightReplayInputs? {
        guard let h = m.height, h.anchor.world.count == 3 else { return nil }
        let anchor = SIMD3<Float>(Float(h.anchor.world[0]),
                                  Float(h.anchor.world[1]),
                                  Float(h.anchor.world[2]))
        let standing = RawCaptureMatrix.translation(h.base.cameraPose)
        let alphaTop = Float(h.top.pitchDeg * .pi / 180)
        let alphaBase = Float(h.base.pitchDeg * .pi / 180)
        let cal = calibration(from: m.settings.calibration)
        return HeightReplayInputs(
            anchor: anchor, standing: standing,
            alphaTopRad: alphaTop, alphaBaseRad: alphaBase,
            dHM: h.dHM, cal: cal)
    }

    /// One candidate height algorithm in the sweep. Same shape as
    /// `DBHCandidate`: `run` returns the estimated height (m) from the shared
    /// stored inputs, or nil when the algorithm is N/A for that capture.
    public struct HeightCandidate: Sendable {
        public let id: String            // stable tag, pooled with Android
        public let name: String          // display label
        public let run: @Sendable (HeightReplayInputs) -> Double?
    }

    /// Illustrative fixed-bias correction (metres) baked onto the tangent
    /// height by the example corrected candidate below. NOT a tuned value — it
    /// exists only to demonstrate the preprocess → estimator → CORRECTION
    /// registry shape (a candidate may post-process the estimate). The OLS
    /// fit in the ranking finds the real bias/slope; delete or replace this
    /// once a genuine correction / alternate method lands.
    public static let exampleHeightBiasM: Double = 0.5

    /// THE HEIGHT REGISTRY — mirror of `dbhCandidates`. Seeded with the
    /// production walk-off tangent method as the base candidate; adding a
    /// slope/terrain-corrected tangent or a future depth-based method is ONE
    /// entry here. The second entry demonstrates the correction step.
    public static let heightCandidates: [HeightCandidate] = [
        HeightCandidate(id: "tangent", name: "Two-tangent") { inp in
            acceptedHeightM(HeightEstimator.estimate(input: heightInput(inp)))
        },
        // EXAMPLE corrected candidate: base tangent estimator, then a plain
        // post-correction (here a fixed bias). Shows the registry supports
        // preprocess → estimator → correction with one line each.
        HeightCandidate(id: "tangentBias", name: "Two-tangent + fixed bias") { inp in
            guard let h = acceptedHeightM(HeightEstimator.estimate(input: heightInput(inp)))
            else { return nil }   // N/A: base estimator rejected this capture
            return h + exampleHeightBiasM
        },
    ]

    /// Build the shared height estimator input from the reconstructed geometry.
    static func heightInput(_ inp: HeightReplayInputs) -> HeightMeasureInput {
        HeightMeasureInput(
            anchorPointWorld: inp.anchor,
            standingPointWorld: inp.standing,
            alphaTopRad: inp.alphaTopRad,
            alphaBaseRad: inp.alphaBaseRad,
            trackingStateWasNormalThroughout: true,
            projectCalibration: inp.cal)
    }

    /// A height result is usable only if it wasn't rejected (red tier).
    static func acceptedHeightM(_ r: HeightResult) -> Double? {
        guard r.confidence != .red else { return nil }
        return Double(r.heightM)
    }

    /// One height capture's value under every registered algorithm.
    public struct HeightSweepEntry: Sendable {
        public let algorithmId: String
        public let name: String
        public let value: Double?        // nil = N/A for this capture
    }

    /// Run a height capture through ALL candidate algorithms from the SAME
    /// stored tangent geometry. Returns one entry per registered algorithm,
    /// or nil when the bundle can't be reconstructed.
    public static func sweepHeight(manifest m: RawCaptureManifest) -> [HeightSweepEntry]? {
        guard let inp = reconstructHeightInputs(manifest: m) else { return nil }
        return heightCandidates.map {
            HeightSweepEntry(algorithmId: $0.id, name: $0.name, value: $0.run(inp))
        }
    }

    /// One height capture's sweep alongside its truth, with the closest algo.
    public struct HeightSweepCapture: Sendable {
        public let id: String
        public let treeNumber: Int?
        public let truth: Double
        public let entries: [HeightSweepEntry]
        public let winnerId: String?
    }

    /// The full ranked height report the "Compare algorithms (height)" view
    /// renders. Same shape as `DBHSweepReport`.
    public struct HeightSweepReport: Sendable {
        public let rankings: [AlgorithmRanking]      // best-first (lowest raw RMSE)
        public let perCapture: [HeightSweepCapture]  // only truthed captures
        public let totalHeight: Int
        public let scored: Int
        public let skippedNoTruth: Int               // truth never entered
        public let skippedUnreadable: Int            // truthed but won't reconstruct
    }

    /// Sweep every height capture and rank the algorithms best-first by raw
    /// RMSE against the hand-measured truth (m), with the same OLS correction
    /// fit as DBH. Pure + Sendable — the caller runs it off the main actor.
    public static func rankHeight(_ summaries: [RawCaptureSummary]) -> HeightSweepReport {
        var totalHeight = 0
        var skipped = 0
        var unreadable = 0
        var scored = 0
        var pairs: [String: [(est: Double, truth: Double)]] = [:]
        var perCapture: [HeightSweepCapture] = []

        for sum in summaries where sum.manifest.kind == "height" {
            totalHeight += 1
            guard let truth = sum.manifest.truth.value else { skipped += 1; continue }
            guard let entries = sweepHeight(manifest: sum.manifest) else {
                unreadable += 1; continue
            }
            scored += 1
            var winnerId: String?
            var winnerErr = Double.infinity
            for e in entries {
                guard let v = e.value else { continue }
                pairs[e.algorithmId, default: []].append((est: v, truth: truth))
                let err = abs(v - truth)
                if err < winnerErr { winnerErr = err; winnerId = e.algorithmId }
            }
            perCapture.append(HeightSweepCapture(
                id: sum.id, treeNumber: sum.manifest.context.treeNumber,
                truth: truth, entries: entries, winnerId: winnerId))
        }

        let rankings = buildRankings(
            order: heightCandidates.map { ($0.id, $0.name) }, pairsById: pairs)

        return HeightSweepReport(
            rankings: rankings, perCapture: perCapture,
            totalHeight: totalHeight, scored: scored,
            skippedNoTruth: skipped, skippedUnreadable: unreadable)
    }

    // MARK: Shared reconstruction helpers

    static func guideAxis(from s: String, tap: SIMD2<Double>) -> GuideAxis {
        switch s {
        case "col": return .col(x: Int(tap.x.rounded()))
        default:    return .row(y: Int(tap.y.rounded()))
        }
    }

    static func calibration(from c: RawCaptureManifest.Calibration) -> ProjectCalibration {
        ProjectCalibration(
            depthNoiseMm: Float(c.depthNoiseMm),
            dbhCorrectionAlpha: Float(c.alpha),
            dbhCorrectionBeta: Float(c.beta),
            // 0 for a bundle written before the key existed, which leaves its
            // coefficients refused — the same answer that build gave. Omitting
            // this argument is what made EVERY replay epoch 0, so a calibrated
            // project's replay silently dropped its correction.
            dbhCalibrationEpoch: c.dbhCalibrationEpoch ?? 0,
            vioDriftFraction: Float(c.vioDriftFraction))
        // depthDiscontinuityM is not a persisted/project-tunable field
        // (always the 0.04 m default on both platforms), so the default
        // here reproduces the live pipeline exactly.
    }

    /// Relative-error pass/fail against the live value.
    static func evaluate(rerun: Double?, live: Double) -> RawCaptureManifest.ReplaySelfcheck {
        guard let r = rerun else {
            return .init(status: "fail", rerunValue: nil, delta: nil)
        }
        let delta = r - live
        let denom = max(abs(live), 1e-6)
        let pass = abs(delta) <= tolerance * denom
        return .init(status: pass ? "pass" : "fail", rerunValue: r, delta: delta)
    }
}

// MARK: - Recorder (serialize a bundle + self-check)

public enum RawCaptureRecorder {

    // MARK: DBH

    /// Serialize one DBH burst bundle. `frames` are the representative
    /// per-sub-sample depth frames (≤5 → depth_0..4.bin). The canonical
    /// `result_live` is the production estimator run over exactly these
    /// (confidence-derived) frames, so replay reproduces it bit-for-bit.
    ///
    /// `id` is minted by the CALLER, synchronously, before this work is
    /// dispatched — so a ground truth typed and Accepted while the bundle is
    /// still being written already knows which bundle it belongs to.
    /// Returns a `.saved` / `.failed` outcome; every failure carries a reason
    /// the scan screen shows in a warning colour. Nothing is swallowed.
    @discardableResult
    public static func recordDBH(
        id: String,
        frames: [ARDepthFrame],
        tapPixel: SIMD2<Double>,
        calibration: ProjectCalibration,
        algorithm: DBHMeasurementMethod,
        bracket: RawCaptureManifest.DBHBundle.Bracket,
        /// The axis the LIVE estimate was computed on. Passed in rather than
        /// re-voted, because a recorder that votes for itself can disagree
        /// with the reading it is supposed to be the raw record of — and it
        /// did, on 60 of 107 validation captures, by a factor of 4:3. Nil
        /// only for callers with no axis of their own; then it votes.
        guideAxis: GuideAxis?,
        captureManual: Bool,
        context: RawCaptureContext,
        referenceJPEG: Data?,
        gps: RawCaptureGPS?
    ) -> RawCaptureOutcome {
        guard let first = frames.first else {
            return .failed(reason: "no depth frames in the burst")
        }
        let dir = RawCaptureStore.bundleDirectory(id: id)
        do {
            try RawCaptureStore.assertStorageAvailable()
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            return .failed(reason: message(error))
        }

        // Canonicalize: derive confidence from depth (shared cross-platform
        // rule) so the stored bytes fully determine the estimator input.
        let canon = frames.map { RawCaptureFrame.canonicalized($0) }
        let canonFirst = RawCaptureFrame.canonicalized(first)
        let axis = guideAxis ?? DBHEstimator.pickGuideAxis(
            frame: canonFirst, tapPixel: tapPixel, calibration: calibration)

        // Canonical live result over the stored frames.
        let liveResult = computeDBH(
            frames: canon, tapPixel: tapPixel, axis: axis,
            calibration: calibration, algorithm: algorithm, bracket: bracket)

        // Per-frame single-frame diameters (diagnostic).
        let perFrame: [Double] = canon.compactMap {
            singleFrameDiameterCm(frame: $0, tapPixel: tapPixel, axis: axis,
                                  algorithm: algorithm, bracket: bracket)
        }

        // Write depth bins + per-frame metadata.
        var frameMetas: [RawCaptureManifest.DBHBundle.Frame] = []
        for (i, f) in canon.enumerated() {
            let name = RawCaptureStore.depthFileName(i)
            do {
                try RawCaptureStore.writeDepth(f.depth, to: dir.appendingPathComponent(name))
            } catch {
                // Tearing the bundle down also destroys a truth/Accept the
                // operator has already been told is queued — say so.
                return .failed(reason: message(error)
                               + RawCaptureStore.discardFailedBundle(id: id))
            }
            frameMetas.append(.init(
                depthFile: name,
                width: f.width, height: f.height, format: "f32m",
                fx: Double(f.intrinsics[0, 0]), fy: Double(f.intrinsics[1, 1]),
                cx: Double(f.intrinsics[2, 0]), cy: Double(f.intrinsics[2, 1]),
                tapPx: [tapPixel.x, tapPixel.y],
                axis: axisString(axis),
                cameraPose: RawCaptureMatrix.flat(f.cameraPoseWorld),
                // The REAL view→depth affine this frame was measured
                // through, in the same six-coefficient order Android
                // writes, so a pooled corpus reads one way.
                //
                // This used to be hard-coded to the identity with a comment
                // asserting that iOS "captures the crosshair already in
                // depth-pixel space". That was true of the crosshair — the
                // auto path only ever reads the centre pixel, which a
                // centred crop leaves alone — and false of the ADJUST
                // bracket, which is a screen-space SPAN. Writing the
                // identity there recorded the assumption instead of the
                // measurement, so a bundle could not be used to detect the
                // error either. Bundles recorded before this carry
                // `[1,0,0,0,1,0]` and cannot be trusted for bracket replay.
                viewToDepth: f.viewMapping?.flattened ?? [1, 0, 0, 0, 1, 0]))
        }

        // Reference RGB (first burst frame's camera image, ~80% quality).
        var rgbFile: String?
        if let jpeg = referenceJPEG {
            let name = "rgb_0.jpg"
            if (try? jpeg.write(to: dir.appendingPathComponent(name))) != nil { rgbFile = name }
        }

        let liveValue = Double(liveResult?.diameterCm ?? 0)
        // TWO explicit booleans (cross-platform, S8): `tier_ok` is the
        // RECORD-TIME quality gate (fit not red); `operator_accepted` starts
        // false and is set by `markAccepted` when the cruiser taps Accept.
        // The legacy `accepted` key now mirrors operator_accepted on BOTH
        // platforms, so the pooled corpus reads it one way.
        let tierOK = (liveResult?.confidence ?? .red) != .red
        let resultLive = RawCaptureManifest.ResultLive(
            value: liveValue,
            sigma: Double(liveResult?.sigmaRmm ?? 0),
            tier: liveResult?.confidence.rawValue ?? "red",
            accepted: false,                    // legacy mirror of operator_accepted
            tierOk: tierOK,
            operatorAccepted: false,
            frameCount: frameMetas.count,
            perFrame: perFrame)

        var manifest = baseManifest(
            kind: "dbh",
            context: context,
            algorithm: algorithmTag(algorithm),
            captureMode: captureManual ? "manual" : "auto",
            calibration: calibration,
            resultLive: resultLive,
            gps: gps)
        manifest.dbh = .init(frames: frameMetas, bracket: bracket, rgbFile: rgbFile)
        manifest.replaySelfcheck = .init(status: "fail", rerunValue: nil, delta: nil)
        do {
            try RawCaptureStore.writeManifest(manifest, id: id)
        } catch {
            return .failed(reason: message(error)
                           + RawCaptureStore.discardFailedBundle(id: id))
        }

        // Reproducibility self-check: reload from disk + re-run.
        let reran = RawCaptureStore.loadManifest(id: id)
            .flatMap { RawCaptureReplay.rerunDBH(manifest: $0, id: id) }
        manifest.replaySelfcheck = RawCaptureReplay.evaluate(
            rerun: reran.map { Double($0.diameterCm) }, live: liveValue)
        do {
            try RawCaptureStore.commitManifestFoldingTruth(manifest, id: id)
        } catch {
            // The bundle IS on disk (first write succeeded) but the self-check
            // stamp / folded truth didn't land — say so rather than claim a
            // clean save.
            return .failed(reason: message(error))
        }
        return .saved(id: id, frames: frameMetas.count)
    }

    /// Human-readable one-liner for the scan screen's failure pill.
    static func message(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    /// Production estimator over the stored frames — the SAME entry points
    /// the live burst used, so `result_live` is exactly reproducible.
    static func computeDBH(
        frames: [ARDepthFrame], tapPixel: SIMD2<Double>, axis: GuideAxis,
        calibration: ProjectCalibration, algorithm: DBHMeasurementMethod,
        bracket: RawCaptureManifest.DBHBundle.Bracket
    ) -> DBHResult? {
        if bracket.enabled {
            return DBHEstimator.bracketChordEstimate(
                frames: frames, guideAxis: axis,
                leftFraction: bracket.left, rightFraction: bracket.right,
                calibration: calibration)
        }
        let input = DBHScanInput(
            frames: frames, tapPixel: tapPixel, guideAxis: axis,
            projectCalibration: calibration)
        switch algorithm {
        case .chord:               return DBHEstimator.chordEstimate(input: input)
        case .partialArcCircleFit: return DBHEstimator.estimate(input: input)
        }
    }

    static func singleFrameDiameterCm(
        frame: ARDepthFrame, tapPixel: SIMD2<Double>, axis: GuideAxis,
        algorithm: DBHMeasurementMethod, bracket: RawCaptureManifest.DBHBundle.Bracket
    ) -> Double? {
        if bracket.enabled {
            return DBHEstimator.bracketChordFit(
                frame: frame, guideAxis: axis,
                leftFraction: bracket.left, rightFraction: bracket.right)?.diameterCm
        }
        switch algorithm {
        case .chord:
            return DBHEstimator.chordPreviewFit(
                frame: frame, tapPixel: tapPixel, guideAxis: axis)?.diameterCm
        case .partialArcCircleFit:
            return DBHEstimator.previewFit(
                frame: frame, tapPixel: tapPixel, guideAxis: axis)?.diameterCm
        }
    }

    // MARK: Height

    /// Serialize one Height compute bundle + self-check. `id` is minted by the
    /// caller synchronously (see `recordDBH`), and the outcome is explicit.
    @discardableResult
    public static func recordHeight(
        id: String,
        anchorWorld: SIMD3<Float>,
        anchorHitType: String,
        anchorDistanceM: Float,
        anchorPose: simd_float4x4,
        basePitchRad: Float,
        baseStanding: SIMD3<Float>,
        baseRotationPose: simd_float4x4,
        topPitchRad: Float,
        topPose: simd_float4x4,
        dHM: Float,
        poseSamples: [(tMs: Int, pose: simd_float4x4)],
        // Whether VIO tracking dropped between the anchor and the aims — the
        // on-screen warning, recorded so the bundle can be filtered on it.
        trackingDropped: Bool,
        calibration: ProjectCalibration,
        context: RawCaptureContext,
        gps: RawCaptureGPS?,
        // Schema 2: the depth frame + reference RGB grabbed at the base-aim
        // and top-aim taps. Optional — when nil (non-LiDAR device, or the
        // frame wasn't available at tap time) the bundle stays a schema-1-
        // shaped tangent-only height capture and still self-checks.
        baseFrame: ARDepthFrame? = nil,
        baseJPEG: Data? = nil,
        topFrame: ARDepthFrame? = nil,
        topJPEG: Data? = nil
    ) -> RawCaptureOutcome {
        let dir = RawCaptureStore.bundleDirectory(id: id)
        do {
            try RawCaptureStore.assertStorageAvailable()
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        } catch {
            return .failed(reason: message(error))
        }

        // Base camera_pose: real camera rotation with its translation column
        // pinned to the exact standing point the live estimator used, so the
        // replay's standing pose == live and the self-check is exact.
        var basePose = baseRotationPose
        basePose.columns.3 = SIMD4<Float>(baseStanding.x, baseStanding.y, baseStanding.z, 1)

        // Aim frames (schema 2). Same canonicalization + f32m depth format +
        // ~80% reference JPEG the DBH path uses, so a pooled depth-height
        // algorithm sees byte-consistent inputs across both capture kinds.
        let baseAimExtra: AimFrameExtra?
        let topAimExtra: AimFrameExtra?
        do {
            baseAimExtra = try writeAimFrame(prefix: "base", frame: baseFrame,
                                             jpeg: baseJPEG, dir: dir)
            topAimExtra = try writeAimFrame(prefix: "top", frame: topFrame,
                                            jpeg: topJPEG, dir: dir)
        } catch {
            // Tearing the bundle down also destroys a truth/Accept the
            // operator has already been told is queued — say so.
            return .failed(reason: message(error)
                           + RawCaptureStore.discardFailedBundle(id: id))
        }

        let liveResult = HeightEstimator.estimate(input: HeightMeasureInput(
            anchorPointWorld: anchorWorld,
            standingPointWorld: baseStanding,
            alphaTopRad: topPitchRad,
            alphaBaseRad: basePitchRad,
            trackingStateWasNormalThroughout: true,
            projectCalibration: calibration))

        // tier_ok = record-time quality gate; operator_accepted is set later
        // by markAccepted when the cruiser taps Accept (S8, both platforms).
        let tierOK = liveResult.confidence != .red
        let aimFrames = [baseAimExtra, topAimExtra].compactMap { $0 }.count
        let resultLive = RawCaptureManifest.ResultLive(
            value: Double(liveResult.heightM),
            // The bundle now carries the REAL σ for red fits too — a red
            // tangent reading is still a measurement and its σ is what the
            // algorithm comparison propagates. σ is unset only when the
            // capture is not a measurement (degenerate d_h, inverted aims,
            // non-finite pose); `sigma` is a non-optional key in the
            // schema shared with the Android sibling, so those write 0.
            // Such a bundle is always tier "red" with tier_ok false, and
            // `HeightEstimator.canAccept` refuses it, so operator_accepted
            // can never become true for it — filter on those, not on σ.
            sigma: liveResult.sigmaHm.map(Double.init) ?? 0,
            tier: liveResult.confidence.rawValue,
            accepted: false,                    // legacy mirror of operator_accepted
            tierOk: tierOK,
            operatorAccepted: false,
            frameCount: aimFrames,
            perFrame: [])

        var manifest = baseManifest(
            kind: "height",
            context: context,
            algorithm: "tangent",
            captureMode: "auto",
            calibration: calibration,
            resultLive: resultLive,
            gps: gps)
        manifest.height = .init(
            anchor: .init(
                world: [Double(anchorWorld.x), Double(anchorWorld.y), Double(anchorWorld.z)],
                hitType: anchorHitType,
                distanceM: Double(anchorDistanceM),
                cameraPose: RawCaptureMatrix.flat(anchorPose)),
            base: aim(pitchDeg: Double(basePitchRad * 180 / .pi),
                      cameraPose: RawCaptureMatrix.flat(basePose),
                      extra: baseAimExtra),
            top: aim(pitchDeg: Double(topPitchRad * 180 / .pi),
                     cameraPose: RawCaptureMatrix.flat(topPose),
                     extra: topAimExtra),
            dHM: Double(dHM),
            poseSamples: poseSamples.map {
                .init(tMs: $0.tMs, pose: RawCaptureMatrix.flat($0.pose))
            },
            trackingDropped: trackingDropped)
        manifest.replaySelfcheck = .init(status: "fail", rerunValue: nil, delta: nil)
        do {
            try RawCaptureStore.writeManifest(manifest, id: id)
        } catch {
            return .failed(reason: message(error)
                           + RawCaptureStore.discardFailedBundle(id: id))
        }

        // Self-check against the stored-d_h re-run. The pose-reposed
        // derivation is recomputed on demand in the replay UI (Android
        // parity: replay_selfcheck stays {status, rerun_value, delta}).
        let liveValue = Double(liveResult.heightM)
        if let reloaded = RawCaptureStore.loadManifest(id: id),
           let rerun = RawCaptureReplay.rerunHeight(manifest: reloaded) {
            manifest.replaySelfcheck = RawCaptureReplay.evaluate(
                rerun: Double(rerun.result.heightM), live: liveValue)
        }
        do {
            try RawCaptureStore.commitManifestFoldingTruth(manifest, id: id)
        } catch {
            return .failed(reason: message(error))
        }
        return .saved(id: id, frames: aimFrames)
    }

    // MARK: Shared

    static func baseManifest(
        kind: String,
        context: RawCaptureContext,
        algorithm: String,
        captureMode: String,
        calibration: ProjectCalibration,
        resultLive: RawCaptureManifest.ResultLive,
        gps: RawCaptureGPS?
    ) -> RawCaptureManifest {
        RawCaptureManifest(
            // Schema 2 (bumped from 1 on BOTH platforms together): height
            // bundles may now carry base/top depth+rgb aim frames. DBH bundles
            // are unchanged; old captures of either kind still load & replay.
            schema: 2,
            platform: "ios",
            device: RawCaptureStore.deviceModel(),
            appCommit: RawCaptureStore.appCommit(),
            createdAt: RawCaptureStore.isoNow(),
            kind: kind,
            context: .init(mode: context.mode, projectId: context.projectID,
                           plotId: context.plotID, treeNumber: context.treeNumber),
            settings: .init(
                algorithm: algorithm,
                units: context.units,
                captureMode: captureMode,
                calibration: .init(
                    alpha: Double(calibration.dbhCorrectionAlpha),
                    beta: Double(calibration.dbhCorrectionBeta),
                    depthNoiseMm: Double(calibration.depthNoiseMm),
                    vioDriftFraction: Double(calibration.vioDriftFraction),
                    dbhCalibrationEpoch: calibration.dbhCalibrationEpoch)),
            resultLive: resultLive,
            truth: .init(value: nil, enteredAt: nil),
            gps: gps.map { .init(lat: $0.lat, lon: $0.lon, accM: $0.accM) },
            replaySelfcheck: .init(status: "fail", rerunValue: nil, delta: nil),
            dbh: nil,
            height: nil)
    }

    static func axisString(_ axis: GuideAxis) -> String {
        switch axis {
        case .row: return "row"
        case .col: return "col"
        }
    }

    static func algorithmTag(_ m: DBHMeasurementMethod) -> String {
        switch m {
        case .chord:               return "silhouette"
        case .partialArcCircleFit: return "arc"
        }
    }

    // MARK: Height aim-frame serialization (schema 2)

    /// Metadata for one written aim frame — mirrors the DBH frame keys.
    struct AimFrameExtra {
        let depthFile: String
        let rgbFile: String?
        let width: Int
        let height: Int
        let fx: Double
        let fy: Double
        let cx: Double
        let cy: Double
    }

    /// Write `depth_<prefix>.bin` (row-major f32 metres, same format the DBH
    /// path uses) and, when present, `rgb_<prefix>.jpg`. Returns nil when no
    /// frame was captured, which keeps the aim schema-1-shaped (tangent only).
    /// Throws on a real IO failure — a half-written aim frame must not pass
    /// for a complete bundle.
    static func writeAimFrame(prefix: String, frame: ARDepthFrame?,
                              jpeg: Data?, dir: URL) throws -> AimFrameExtra? {
        guard let f = frame else { return nil }
        let depthName = "depth_\(prefix).bin"
        try RawCaptureStore.writeDepth(f.depth, to: dir.appendingPathComponent(depthName))
        var rgbName: String?
        if let jpeg {
            let name = "rgb_\(prefix).jpg"
            if (try? jpeg.write(to: dir.appendingPathComponent(name))) != nil { rgbName = name }
        }
        return AimFrameExtra(
            depthFile: depthName, rgbFile: rgbName,
            width: f.width, height: f.height,
            fx: Double(f.intrinsics[0, 0]), fy: Double(f.intrinsics[1, 1]),
            cx: Double(f.intrinsics[2, 0]), cy: Double(f.intrinsics[2, 1]))
    }

    /// Build a height `Aim` with the schema-2 aim-frame keys populated from
    /// `extra` (all nil ⇒ a tangent-only aim identical to schema 1).
    static func aim(pitchDeg: Double, cameraPose: [Double],
                    extra: AimFrameExtra?) -> RawCaptureManifest.HeightBundle.Aim {
        RawCaptureManifest.HeightBundle.Aim(
            pitchDeg: pitchDeg, cameraPose: cameraPose,
            depthFile: extra?.depthFile, rgbFile: extra?.rgbFile,
            width: extra?.width, height: extra?.height,
            format: extra == nil ? nil : "f32m",
            fx: extra?.fx, fy: extra?.fy, cx: extra?.cx, cy: extra?.cy)
    }
}
