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
    public struct AlgorithmRanking: Sendable {
        public let algorithmId: String
        public let name: String
        public let n: Int              // captures scored (had truth + a value)
        public let bias: Double        // mean(estimate − truth), signed
        public let rmse: Double        // root-mean-square error
        public let mae: Double         // mean absolute error
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
    public struct DBHSweepReport: Sendable {
        public let rankings: [AlgorithmRanking]   // best-first (lowest RMSE)
        public let perCapture: [DBHSweepCapture]  // only truthed captures
        public let totalDBH: Int
        public let scored: Int                    // truthed & reconstructable
        public let skippedNoTruth: Int
    }

    /// Sweep every DBH capture and rank the algorithms best-first by RMSE
    /// against the hand-measured truth. Captures with no truth are skipped
    /// (counted). Pure + Sendable — the caller runs it off the main actor.
    public static func rankDBH(_ summaries: [RawCaptureSummary]) -> DBHSweepReport {
        var totalDBH = 0
        var skipped = 0
        var scored = 0
        // Per-algorithm error accumulators, keyed by registry id.
        var errors: [String: [Double]] = [:]
        var perCapture: [DBHSweepCapture] = []

        for sum in summaries where sum.manifest.kind == "dbh" {
            totalDBH += 1
            guard let truth = sum.manifest.truth.value else { skipped += 1; continue }
            guard let entries = sweepDBH(manifest: sum.manifest, id: sum.id) else {
                skipped += 1; continue
            }
            scored += 1
            var winnerId: String?
            var winnerErr = Double.infinity
            for e in entries {
                guard let v = e.value else { continue }
                let err = v - truth
                errors[e.algorithmId, default: []].append(err)
                if abs(err) < winnerErr { winnerErr = abs(err); winnerId = e.algorithmId }
            }
            perCapture.append(DBHSweepCapture(
                id: sum.id, treeNumber: sum.manifest.context.treeNumber,
                truth: truth, entries: entries, winnerId: winnerId))
        }

        var rankings: [AlgorithmRanking] = dbhCandidates.map { c in
            let errs = errors[c.id] ?? []
            let n = errs.count
            let bias = n > 0 ? errs.reduce(0, +) / Double(n) : 0
            let mae = n > 0 ? errs.map { abs($0) }.reduce(0, +) / Double(n) : 0
            let rmse = n > 0 ? (errs.map { $0 * $0 }.reduce(0, +) / Double(n)).squareRoot() : 0
            return AlgorithmRanking(
                algorithmId: c.id, name: c.name, n: n, bias: bias, rmse: rmse, mae: mae)
        }
        // Best-first by RMSE; algorithms with no scored captures sink last.
        rankings.sort { a, b in
            if (a.n == 0) != (b.n == 0) { return b.n == 0 }
            return a.rmse < b.rmse
        }

        return DBHSweepReport(
            rankings: rankings, perCapture: perCapture,
            totalDBH: totalDBH, scored: scored, skippedNoTruth: skipped)
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
    /// Returns the bundle id (nil only on a hard IO/empty-input failure).
    @discardableResult
    public static func recordDBH(
        frames: [ARDepthFrame],
        tapPixel: SIMD2<Double>,
        calibration: ProjectCalibration,
        algorithm: DBHMeasurementMethod,
        bracket: RawCaptureManifest.DBHBundle.Bracket,
        captureManual: Bool,
        context: RawCaptureContext,
        referenceJPEG: Data?,
        gps: RawCaptureGPS?
    ) -> String? {
        guard let first = frames.first else { return nil }
        let id = UUID().uuidString
        let dir = RawCaptureStore.bundleDirectory(id: id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // Canonicalize: derive confidence from depth (shared cross-platform
        // rule) so the stored bytes fully determine the estimator input.
        let canon = frames.map { RawCaptureFrame.canonicalized($0) }
        let canonFirst = RawCaptureFrame.canonicalized(first)
        let axis = DBHEstimator.pickGuideAxis(
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
            RawCaptureStore.writeDepth(f.depth, to: dir.appendingPathComponent(name))
            frameMetas.append(.init(
                depthFile: name,
                width: f.width, height: f.height, format: "f32m",
                fx: Double(f.intrinsics[0, 0]), fy: Double(f.intrinsics[1, 1]),
                cx: Double(f.intrinsics[2, 0]), cy: Double(f.intrinsics[2, 1]),
                tapPx: [tapPixel.x, tapPixel.y],
                axis: axisString(axis),
                cameraPose: RawCaptureMatrix.flat(f.cameraPoseWorld),
                // iOS captures the crosshair already in depth-pixel space
                // (no view→depth affine is applied), so the mapping is
                // identity. Android carries a real display-rotation affine.
                viewToDepth: [1, 0, 0, 0, 1, 0]))
        }

        // Reference RGB (first burst frame's camera image, ~80% quality).
        var rgbFile: String?
        if let jpeg = referenceJPEG {
            let name = "rgb_0.jpg"
            if (try? jpeg.write(to: dir.appendingPathComponent(name))) != nil { rgbFile = name }
        }

        let liveValue = Double(liveResult?.diameterCm ?? 0)
        // Android parity: `accepted` = the fit was acceptable (tier != red),
        // decided at record time (not the cruiser's later Accept tap).
        let resultLive = RawCaptureManifest.ResultLive(
            value: liveValue,
            sigma: Double(liveResult?.sigmaRmm ?? 0),
            tier: liveResult?.confidence.rawValue ?? "red",
            accepted: (liveResult?.confidence ?? .red) != .red,
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
        RawCaptureStore.writeManifest(manifest, id: id)

        // Reproducibility self-check: reload from disk + re-run.
        let reran = RawCaptureStore.loadManifest(id: id)
            .flatMap { RawCaptureReplay.rerunDBH(manifest: $0, id: id) }
        manifest.replaySelfcheck = RawCaptureReplay.evaluate(
            rerun: reran.map { Double($0.diameterCm) }, live: liveValue)
        RawCaptureStore.writeManifest(manifest, id: id)
        return id
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

    /// Serialize one Height compute bundle + self-check.
    @discardableResult
    public static func recordHeight(
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
    ) -> String? {
        let id = UUID().uuidString
        let dir = RawCaptureStore.bundleDirectory(id: id)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)

        // Base camera_pose: real camera rotation with its translation column
        // pinned to the exact standing point the live estimator used, so the
        // replay's standing pose == live and the self-check is exact.
        var basePose = baseRotationPose
        basePose.columns.3 = SIMD4<Float>(baseStanding.x, baseStanding.y, baseStanding.z, 1)

        // Aim frames (schema 2). Same canonicalization + f32m depth format +
        // ~80% reference JPEG the DBH path uses, so a pooled depth-height
        // algorithm sees byte-consistent inputs across both capture kinds.
        let baseAimExtra = writeAimFrame(prefix: "base", frame: baseFrame,
                                         jpeg: baseJPEG, dir: dir)
        let topAimExtra = writeAimFrame(prefix: "top", frame: topFrame,
                                        jpeg: topJPEG, dir: dir)

        let liveResult = HeightEstimator.estimate(input: HeightMeasureInput(
            anchorPointWorld: anchorWorld,
            standingPointWorld: baseStanding,
            alphaTopRad: topPitchRad,
            alphaBaseRad: basePitchRad,
            trackingStateWasNormalThroughout: true,
            projectCalibration: calibration))

        let resultLive = RawCaptureManifest.ResultLive(
            value: Double(liveResult.heightM),
            sigma: Double(liveResult.sigmaHm),
            tier: liveResult.confidence.rawValue,
            accepted: liveResult.confidence != .red,   // Android parity
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
            })
        manifest.replaySelfcheck = .init(status: "fail", rerunValue: nil, delta: nil)
        RawCaptureStore.writeManifest(manifest, id: id)

        // Self-check against the stored-d_h re-run. The pose-reposed
        // derivation is recomputed on demand in the replay UI (Android
        // parity: replay_selfcheck stays {status, rerun_value, delta}).
        let liveValue = Double(liveResult.heightM)
        if let reloaded = RawCaptureStore.loadManifest(id: id),
           let rerun = RawCaptureReplay.rerunHeight(manifest: reloaded) {
            manifest.replaySelfcheck = RawCaptureReplay.evaluate(
                rerun: Double(rerun.result.heightM), live: liveValue)
        }
        RawCaptureStore.writeManifest(manifest, id: id)
        return id
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
                    vioDriftFraction: Double(calibration.vioDriftFraction))),
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
    static func writeAimFrame(prefix: String, frame: ARDepthFrame?,
                              jpeg: Data?, dir: URL) -> AimFrameExtra? {
        guard let f = frame else { return nil }
        let depthName = "depth_\(prefix).bin"
        RawCaptureStore.writeDepth(f.depth, to: dir.appendingPathComponent(depthName))
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
