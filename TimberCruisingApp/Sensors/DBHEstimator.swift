// Spec §7.1 — DBH: Guide-Align Partial-Arc Circle Fit. Full 11-step
// pipeline.
//
// Orchestrates PointCloud + CircleFit + Common (confidence tier) to turn
// a burst of ARDepthFrames and a single tap into a DBHResult. Pure
// function: no ARKit, no UI, no IO (raw-PLY dump is the caller's job via
// an optional sidecar writer).
//
// The ARDepthFrame comes from Sensors/ARKitSessionManager; depth arrays
// are row-major landscape-native (sensor coords don't rotate with the
// device). To produce a horizontal-on-screen slice across the trunk the
// caller picks a `GuideAxis` matching the current UI orientation:
//
//   • Landscape on screen — depth-width axis is horizontal-on-screen, so
//     walk columns at a fixed row → `.row(y: depth.height / 2)`.
//   • Portrait on screen — depth-width axis is VERTICAL-on-screen, so
//     walking columns yields a vertical strip ALONG the trunk (all
//     points cluster at one world XZ). Walk rows at a fixed column
//     instead → `.col(x: depth.width / 2)`.
//
// Phase 14 added the `.col` path. Before it, the algorithm assumed
// `.row` semantics implicitly and produced degenerate fits on portrait
// iPhones (the only orientation iPhone now supports).
//
// `tapPixel` stays in depth-map pixel coordinates regardless of axis;
// only the strip-walk direction depends on orientation.

import Foundation
import simd
import Common
import Models

// MARK: - Inputs

public struct ProjectCalibration: Sendable, Equatable {
    /// σ of LiDAR depth noise, millimetres. Controls RANSAC tolerance +
    /// sigma_r metric.
    public var depthNoiseMm: Float
    /// Cylinder calibration: `DBH_true = alpha + beta · DBH_raw_cm`.
    public var dbhCorrectionAlpha: Float
    public var dbhCorrectionBeta: Float
    /// The estimator epoch these coefficients were FITTED against.
    ///
    /// A cylinder calibration is a straight line through (raw, true) pairs, so
    /// it absorbs whatever systematic error the estimator had on the day it
    /// was fitted. Change the estimator and beta is answering a question that
    /// no longer exists: the geometry's own correction and the fitted one both
    /// pull the diameter down, they stack, and the project starts under-
    /// reading silently and without limit. Nothing warns anyone, because a
    /// calibrated project looks more trustworthy than an uncalibrated one.
    ///
    /// So the coefficients carry the epoch they belong to, and
    /// `appliedToRawCm` refuses to use them under any other. 0 means "never
    /// calibrated", which is safe at any epoch because the identity is.
    public var dbhCalibrationEpoch: Int

    /// Is this project's calibration still answering the current estimator?
    public var calibrationIsStale: Bool {
        (dbhCorrectionAlpha != 0 || dbhCorrectionBeta != 1)
            && dbhCalibrationEpoch != DBHEstimator.estimatorEpoch
    }

    /// The published diameter for a raw one — the ONLY place the cylinder
    /// coefficients may be applied, so the staleness check cannot be
    /// forgotten at one of the call sites.
    public func appliedToRawCm(_ rawCm: Double) -> Double {
        guard !calibrationIsStale else { return rawCm }
        return Double(dbhCorrectionAlpha) + Double(dbhCorrectionBeta) * rawCm
    }
    /// §7.2 VIO drift fraction — σ_d = vioDriftFraction · d_h. Default 0.02.
    public var vioDriftFraction: Float
    /// Maximum allowed depth jump between adjacent guide-row pixels
    /// during stem-strip extraction. Catches the multi-tree case where
    /// two trunks at slightly different depths visually touch — the
    /// connectivity walk previously absorbed both into one inflated
    /// fit. A 4 cm default sits comfortably above any legitimate
    /// intra-trunk gradient (typically ≤ 25 mm per pixel even at the
    /// trunk's tangent edge for trees up to 80 cm DBH at 1 m) and
    /// below the typical inter-trunk gap (≥ 5 cm).
    /// Tunable per-region — densely-buttressed species or rougher bark
    /// may warrant a larger threshold to avoid false splits.
    public var depthDiscontinuityM: Float

    public init(
        depthNoiseMm: Float,
        dbhCorrectionAlpha: Float,
        dbhCorrectionBeta: Float,
        dbhCalibrationEpoch: Int = 0,
        vioDriftFraction: Float = 0.02,
        depthDiscontinuityM: Float = 0.04
    ) {
        self.depthNoiseMm = depthNoiseMm
        self.dbhCorrectionAlpha = dbhCorrectionAlpha
        self.dbhCorrectionBeta = dbhCorrectionBeta
        self.vioDriftFraction = vioDriftFraction
        self.depthDiscontinuityM = depthDiscontinuityM
        self.dbhCalibrationEpoch = dbhCalibrationEpoch
    }

    /// Neutral calibration — pre-calibration projects start here.
    public static let identity = ProjectCalibration(
        depthNoiseMm: 5.0,
        dbhCorrectionAlpha: 0,
        dbhCorrectionBeta: 1,
        dbhCalibrationEpoch: 0,
        vioDriftFraction: 0.02,
        depthDiscontinuityM: 0.04)
}

/// Axis the strip walks along + the fixed coordinate on the orthogonal
/// axis. See file header for the orientation mapping.
public enum GuideAxis: Sendable, Equatable {
    /// Walk columns (x) at fixed row y. Produces a horizontal slice when
    /// the device's long edge is horizontal on screen (landscape iPad).
    case row(y: Int)
    /// Walk rows (y) at fixed column x. Produces a horizontal-on-screen
    /// slice when the device's long edge is vertical on screen (portrait
    /// iPhone, portrait iPad).
    case col(x: Int)
}

public struct DBHScanInput: Sendable {
    public let frames: [ARDepthFrame]
    /// Image-space (x, y) in the depth map's own coordinate system.
    public let tapPixel: SIMD2<Double>
    /// Strip-walk axis chosen by the caller per current UI orientation.
    public let guideAxis: GuideAxis
    public let projectCalibration: ProjectCalibration
    /// Optional sidecar that persists the cleaned point set to PLY when
    /// the caller opts in (REQ-DBH-007). Returns the file path or nil.
    public let rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)?
    /// The unit the RECOVERY INSTRUCTIONS are worded in. A failed scan's
    /// reason is the only thing telling the cruiser what to do differently,
    /// and "stand 0.5–3 m from the trunk" is not an instruction a cruiser who
    /// paces in feet can act on. Carried here rather than fixed up in the
    /// screen because the reason is built where the check fails. Same field,
    /// same purpose, as `HeightScanInput.unitSystem`.
    public let unitSystem: UnitSystem

    public init(
        frames: [ARDepthFrame],
        tapPixel: SIMD2<Double>,
        guideAxis: GuideAxis,
        projectCalibration: ProjectCalibration,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil,
        unitSystem: UnitSystem = .metric
    ) {
        self.frames = frames
        self.tapPixel = tapPixel
        self.guideAxis = guideAxis
        self.projectCalibration = projectCalibration
        self.rawPointsWriter = rawPointsWriter
        self.unitSystem = unitSystem
    }
}

// MARK: - Estimator

public enum DBHEstimator {

    /// Plausible-diameter window for any single-frame fit, in centimetres.
    ///
    /// THE CEILING USED TO BE 100 cm, WHICH IS 39.4 INCHES. The stand at McDunn
    /// has Douglas-fir over 40 in, so the gate refused them outright: the bracket
    /// would be placed correctly on a 42 in stem, the arithmetic would return
    /// ~107 cm, and the fit would come back nil with nothing on screen. It also
    /// explains why a deliberately wide bracket "stopped working" past about half
    /// the screen, and why the automatic path could not hold a big tree from a
    /// distance — all three were the same number.
    ///
    /// 300 cm is chosen to clear any stem this app will meet (the largest known
    /// Douglas-fir is ~440 cm, and a hand-held phone is not measuring that) while
    /// still refusing the degenerate cases the gate exists for: a bracket dragged
    /// across the whole depth axis at arm's length computes tens of metres and is
    /// still rejected. The floor stays at 2.5 cm.
    public static let plausibleDiameterCm: ClosedRange<Double> = 2.5...300.0

    /// How far the phone may be from the trunk for the tap's depth sample to
    /// be usable, in METRES. A property of the depth camera, not a preference:
    /// closer than half a metre the sensor has nothing to triangulate, further
    /// than three the per-pixel depth noise swamps a stem's curvature. Named
    /// so the guard and the recovery sentence read it from one place.
    /// `Float` because the depth map is — `medianDepth` returns one, and a
    /// Double range here would only add a conversion at the guard.
    public static let usableTapDepthM: ClosedRange<Float> = 0.5...3.0

    /// §7.9 tier thresholds, named once so the cruiser-facing confidence
    /// explainer can QUOTE the numbers the checks apply instead of carrying a
    /// prose copy of them that drifts the first time one of them moves. Every
    /// value is the shipped one; this is a naming change, not a tuning one,
    /// and no stored measurement, sigma, method or capture_mode moves with it.
    ///
    /// Mirrored byte-for-byte by Android `DBHEstimator.TierThresholds`.
    public enum TierThresholds {
        // The §7.1 partial-arc circle fit (`estimate`). NOT the default
        // capture — see `frameSpreadGreen` for the one that is.
        public static let minInliersReject = 10
        public static let minInliersWarn = 20
        public static let minArcDegReject: Double = 30
        public static let minArcDegWarn: Double = 45
        public static let rmseOverRadiusReject: Double = 0.07
        public static let rmseOverRadiusWarn: Double = 0.05
        public static let sigmaOverRadiusReject: Double = 0.05
        public static let sigmaOverRadiusWarn: Double = 0.02
        public static let radiusCoVReject: Double = 0.10
        public static let radiusCoVWarn: Double = 0.05

        /// THE DEFAULT CAPTURE'S ONLY TIER RULE. The edge-bracket (Adjust)
        /// and chord/silhouette paths grade a burst on frame-to-frame
        /// agreement alone — (max − min) / mean of the per-frame diameters.
        /// At or below this it is green, above it yellow. None of the
        /// circle-fit criteria above run on that path at all.
        public static let frameSpreadGreen: Double = 0.15

        /// A burst needs this many usable frames before it is graded; fewer
        /// is the one way the default path produces a red.
        public static let minUsableFrames = 3
    }

    /// Full §7.1 pipeline. Returns nil only if the input cannot be
    /// attempted at all (e.g. burst too small). Quality failures return
    /// a `.red` `DBHResult` carrying `rejectionReason`.
    public static func estimate(input: DBHScanInput) -> DBHResult? {
        guard input.frames.count >= 5 else { return nil }

        // Step 2: depth + confidence at tap (last frame, 5×5 median).
        guard let lastFrame = input.frames.last else { return nil }
        guard let dTap = medianDepth(
            around: input.tapPixel, frame: lastFrame, radius: 2)
        else {
            return redResult(
                reason: "The crosshair isn't on anything the depth camera can see — aim at the trunk and capture again.",
                method: .lidarPartialArcSingleView)
        }
        guard usableTapDepthM.contains(dTap) else {
            return redResult(
                // The first clause was already the whole instruction; the
                // "tap depth … out of range" half echoed the internal
                // depth-map sample at the crosshair pixel and told the
                // cruiser nothing they could act on.
                //
                // THE GATE IS UNCHANGED and stays metric — it is the depth
                // camera's usable range, not a preference. Only the wording
                // follows the cruiser's units, and it is built from the same
                // constant the guard tests, so the sentence cannot come to
                // quote a range the check no longer applies.
                reason: "Stand "
                        + GuidanceDistance.range(fromMetres: Double(usableTapDepthM.lowerBound),
                                                 toMetres: Double(usableTapDepthM.upperBound),
                                                 in: input.unitSystem)
                        + " from the trunk and capture again.",
                method: .lidarPartialArcSingleView)
        }
        guard confidenceAt(pixel: input.tapPixel, frame: lastFrame) >= 1 else {
            return redResult(
                reason: "Trunk surface not reliably seen; try a cleaner stem area",
                method: .lidarPartialArcSingleView)
        }

        // Steps 3 + 4: extract stem strip per frame, back-project to world XZ.
        let tapAlongAxis = tapAlongAxis(input.tapPixel, axis: input.guideAxis)
        var combinedXZ: [SIMD2<Double>] = []
        combinedXZ.reserveCapacity(input.frames.count * 64)
        for frame in input.frames {
            let strip = extractGuideStemStrip(
                frame: frame,
                axis: input.guideAxis,
                tapAlongAxis: tapAlongAxis,
                dTap: dTap,
                deltaDepth: guideStripDepthBudgetM,
                discontinuityThresholdM: input.projectCalibration.depthDiscontinuityM)
            for idx in strip {
                let (px, py) = pixelCoords(axis: input.guideAxis, idx: idx)
                let xz = BackProjection.worldXZ(
                    x: Double(px), y: Double(py),
                    depth: Double(frame.depth(atX: px, y: py)),
                    intrinsics: frame.intrinsics,
                    cameraPoseWorld: frame.cameraPoseWorld)
                combinedXZ.append(xz)
            }
        }

        // Step 5: point count check.
        guard combinedXZ.count >= 30 else {
            return redResult(
                reason: "Not enough surface points; hold steadier or move closer",
                method: .lidarPartialArcSingleView,
                nInliers: combinedXZ.count)
        }

        // Step 6: statistical outlier removal (k = 8, σ_mult = 2.0).
        let cleaned = OutlierRemoval.statistical(
            points: combinedXZ, k: 8, sigmaMult: 2.0)
        guard cleaned.count >= 20 else {
            // "Too few points after outlier removal" named a pipeline step
            // and asked for nothing. The COUNT gate (>= 20 after the k = 8
            // statistical trim) is unchanged; only the sentence is.
            return redResult(
                reason: "Too much of that scan was noise to measure from — move closer, fill the crosshair with bark, and capture again.",
                method: .lidarPartialArcSingleView,
                nInliers: cleaned.count)
        }

        // Step 7: RANSAC + Taubin refit.
        let cal = input.projectCalibration
        let noiseM = Double(cal.depthNoiseMm) / 1000.0
        let inlierTol = max(0.003, 2.0 * noiseM)
        guard let fit = RANSACCircle.fit(
            points: cleaned, inlierTol: inlierTol,
            iterations: 500, minInliers: 20)
        else {
            // Was "Could not fit a circle" — the name of the algorithm,
            // with no instruction. RANSAC's tolerance, iteration budget
            // and 20-inlier floor above are all unchanged.
            return redResult(
                reason: "Couldn't find a round trunk in that scan — aim at the stem, hold steadier, and capture again.",
                method: .lidarPartialArcSingleView,
                nInliers: 0)
        }

        // Step 8: metrics.
        let rmse   = rootMeanSquaredResidual(
            inliers: fit.inliers, circle: fit.circle)
        let arcDeg = arcCoverageDeg(
            inliers: fit.inliers, center: (fit.circle.cx, fit.circle.cy))
        let sigmaR = sigmaR(
            noiseMeters: noiseM, nInliers: fit.inliers.count,
            arcCoverageDeg: arcDeg)
        let radiusCoV = perFrameRadiusCoV(
            frames: input.frames,
            axis: input.guideAxis,
            tapAlongAxis: tapAlongAxis,
            dTap: dTap,
            discontinuityThresholdM: input.projectCalibration.depthDiscontinuityM)

        // Optional raw-PLY sidecar (REQ-DBH-007).
        let rawPath = input.rawPointsWriter?(cleaned)

        // Step 8.5: chord-based silhouette sanity (catches pathological
        // RANSAC inflation only).
        //
        // On small arcs, three nearly-collinear points let RANSAC fit
        // any huge circle and still claim all points as inliers. On
        // device a cruiser would see "40 cm" in the live preview and
        // "120 cm" as the final measurement — RANSAC passed every § 7.9
        // sanity check on the pathological fit.
        //
        // The observed silhouette chord — the XZ bounding-box diagonal
        // of the cleaned point cloud — is a lower bound on the true
        // diameter that never diverges. A RANSAC fit whose diameter is
        // more than 3× the chord is definitely wrong, and we fall back
        // to the chord (which for realistic forest-cruise observations
        // sits within 70–100 % of the true diameter).
        //
        // The 3× threshold is deliberately loose: on clean 45–60° arcs
        // the chord is half the diameter (ratio ≈ 2.0–2.6), and we
        // don't want to override those correct fits. Only egregious
        // inflations trigger.
        //
        // Deflation lower bound: the bbox diagonal across multi-frame
        // bursts can legitimately exceed the diameter (D · √(1.25) at
        // 180° arc, D · √2 at full 360°), so the smallest legitimate
        // ratio is ≈ 0.707. A ratio below 0.65 means RANSAC chose a
        // circle smaller than the silhouette can possibly contain —
        // that's the Phase 14.1 deflation case (cruiser saw a 30 cm
        // chord on screen but the burst returned 12 cm DBH).
        let chordDiameterM = chordDiameterFromCloud(cleaned)
        var r = fit.circle.radius
        var chordOverride = false
        if chordDiameterM > 0.025 {
            let fittedDiameterM = 2.0 * r
            let ratio = fittedDiameterM / chordDiameterM
            if ratio > 3.0 || ratio < 0.65 {
                r = chordDiameterM / 2.0
                chordOverride = true
            }
        }

        // Step 9: sanity tree.
        // Phase 18.2 — gate relaxation. The earlier thresholds were
        // borrowed from §7.1 verbatim and rejected most field captures
        // taken handheld at 1.5–3 m. Single-Shot SAM
        // papers report usable fits at 30°+ arc / 10+ inliers / 7%
        // RMSE — we now match that and demote borderline fits to warn
        // (yellow) instead of red.
        let checks: [Check] = [
            // This one reaches the scan banner (it is a .reject, and the
            // banner shows `firstFailingRejectReason`), so it says what went
            // wrong and what to change. "Fewer than 10 trunk surface points"
            // was the count the code tests, in the code's words. The COUNT
            // is unchanged.
            check(fit.inliers.count >= TierThresholds.minInliersReject, sev: .reject,
                  reason: "Too little of the trunk was picked up — move closer, fill the crosshair with bark, and capture again."),
            check(fit.inliers.count >= TierThresholds.minInliersWarn, sev: .warn,
                  reason: "Only 10–20 trunk surface points"),
            // Reject reasons are the ones a cruiser reads (the banner shows
            // `firstFailingRejectReason`), so they are written as
            // instructions. Warn reasons stay in the estimator's own
            // vocabulary — they never leave the struct. Every THRESHOLD
            // below is unchanged.
            check(arcDeg >= TierThresholds.minArcDegReject, sev: .reject,
                  reason: "Not enough of the trunk in view — step back or centre the guide line."),
            check(arcDeg >= TierThresholds.minArcDegWarn, sev: .warn,
                  reason: "Trunk arc coverage 30°–45°"),
            check(r >= 0.025 && r <= 1.0, sev: .reject,
                  reason: "That doesn't measure like a trunk — aim at the stem and capture again."),
            check(rmse / r <= TierThresholds.rmseOverRadiusReject, sev: .reject,
                  reason: "The shape didn't match a trunk — hold steadier and capture again."),
            check(rmse / r <= TierThresholds.rmseOverRadiusWarn, sev: .warn,
                  reason: "Fit error 5–7% of radius"),
            check(sigmaR / r <= TierThresholds.sigmaOverRadiusReject, sev: .reject,
                  reason: "This diameter isn't settling — hold steadier and capture again."),
            check(sigmaR / r <= TierThresholds.sigmaOverRadiusWarn, sev: .warn,
                  reason: "Radius precision ±2–5%"),
            check(radiusCoV <= TierThresholds.radiusCoVReject, sev: .reject,
                  reason: "The trunk width kept changing between shots — hold the phone steadier and capture again."),
            check(radiusCoV <= TierThresholds.radiusCoVWarn, sev: .warn,
                  reason: "Per-frame radius spread 5–10%"),
            // Extra warn when we had to override with the chord fallback
            // so the cruiser knows the fit didn't fully converge.
            check(!chordOverride, sev: .warn,
                  reason: "Fit disagreed with silhouette; using chord")
        ]
        let tier = combineChecks(checks)
        let rejectionReason: String?
        if tier == .red {
            rejectionReason = firstFailingRejectReason(checks)
                ?? "This reading didn't pass the accuracy checks — retake it, or accept it and flag the tree."
        } else {
            rejectionReason = nil
        }

        // Step 10: apply cylinder calibration.
        let dbhRawCm = 2 * r * 100
        let dbhCm = cal.appliedToRawCm(dbhRawCm)

        // Step 11: build the DBHResult.
        return DBHResult(
            diameterCm: Float(dbhCm),
            centerXZ: SIMD2(Float(fit.circle.cx), Float(fit.circle.cy)),
            arcCoverageDeg: Float(arcDeg),
            rmseMm: Float(rmse * 1000),
            sigmaRmm: Float(sigmaR * 1000),
            nInliers: fit.inliers.count,
            confidence: tier,
            method: .lidarPartialArcSingleView,
            rawPointsPath: rawPath,
            rejectionReason: rejectionReason)
    }

    /// The depth map arrives in the camera's sensor orientation, which
    /// varies by device/rotation — so a fixed guide axis can walk the strip
    /// ALONG the trunk (its length) instead of ACROSS it (its width). That
    /// collapses the back-projected points to one world-XZ spot and the
    /// diameter reads a few centimetres. We try both axes at the tap and pick
    /// whichever yields the wider XZ chord — the across-the-trunk direction.
    public static func pickGuideAxis(
        frame: ARDepthFrame,
        tapPixel: SIMD2<Double>,
        calibration cal: ProjectCalibration
    ) -> GuideAxis {
        guard let dTap = medianDepth(around: tapPixel, frame: frame, radius: 2)
        else { return .col(x: Int(tapPixel.x.rounded())) }

        func chordFor(_ axis: GuideAxis) -> Double {
            let along = tapAlongAxis(tapPixel, axis: axis)
            let strip = extractGuideStemStrip(
                frame: frame, axis: axis, tapAlongAxis: along, dTap: dTap,
                deltaDepth: guideStripDepthBudgetM,
                discontinuityThresholdM: cal.depthDiscontinuityM)
            if strip.count < 4 { return 0 }
            let pts = strip.map { idx -> SIMD2<Double> in
                let (px, py) = pixelCoords(axis: axis, idx: idx)
                return BackProjection.worldXZ(
                    x: Double(px), y: Double(py),
                    depth: Double(frame.depth(atX: px, y: py)),
                    intrinsics: frame.intrinsics,
                    cameraPoseWorld: frame.cameraPoseWorld)
            }
            return chordDiameterFromCloud(pts)
        }

        let row = GuideAxis.row(y: Int(tapPixel.y.rounded()))
        let col = GuideAxis.col(x: Int(tapPixel.x.rounded()))
        return chordFor(row) >= chordFor(col) ? row : col
    }

    // MARK: - Step 2: depth + confidence at tap

    /// 5×5 median depth (radius = 2) around the tap pixel. Returns nil
    /// if the tap falls outside the depth map or every sample is 0.
    static func medianDepth(
        around pixel: SIMD2<Double>,
        frame: ARDepthFrame,
        radius: Int
    ) -> Float? {
        let cx = Int(pixel.x.rounded())
        let cy = Int(pixel.y.rounded())
        guard cx >= 0, cx < frame.width, cy >= 0, cy < frame.height
        else { return nil }
        var samples: [Float] = []
        samples.reserveCapacity((2 * radius + 1) * (2 * radius + 1))
        for dy in -radius...radius {
            let y = cy + dy
            guard y >= 0, y < frame.height else { continue }
            for dx in -radius...radius {
                let x = cx + dx
                guard x >= 0, x < frame.width else { continue }
                let d = frame.depth(atX: x, y: y)
                if d > 0 { samples.append(d) }
            }
        }
        guard !samples.isEmpty else { return nil }
        samples.sort()
        return samples[samples.count / 2]
    }

    /// Confidence (0/1/2) at the tap pixel. Returns 0 for out-of-bounds
    /// so guard checks trivially reject.
    static func confidenceAt(pixel: SIMD2<Double>, frame: ARDepthFrame) -> UInt8 {
        let cx = Int(pixel.x.rounded())
        let cy = Int(pixel.y.rounded())
        guard cx >= 0, cx < frame.width, cy >= 0, cy < frame.height
        else { return 0 }
        return frame.confidence(atX: cx, y: cy)
    }

    // MARK: - Step 3: stem-strip extraction along the guide axis

    /// Maps a strip index back to the (x, y) pixel coordinate using the
    /// fixed coord on the orthogonal axis.
    @inlinable
    static func pixelCoords(axis: GuideAxis, idx: Int) -> (x: Int, y: Int) {
        switch axis {
        case .row(let y): return (idx, y)
        case .col(let x): return (x, idx)
        }
    }

    /// Tap coordinate along the walked axis (column for `.row`, row for
    /// `.col`). The other coordinate's tap value is unused by the strip
    /// walk — only the orthogonal-axis fixed coord and the along-axis
    /// seed matter.
    @inlinable
    static func tapAlongAxis(_ tapPixel: SIMD2<Double>, axis: GuideAxis) -> Int {
        switch axis {
        case .row: return Int(tapPixel.x.rounded())
        case .col: return Int(tapPixel.y.rounded())
        }
    }

    /// Extracts the contiguous run of pixels along the walked axis that
    /// (a) have confidence ≥ 1, (b) depth within ±deltaDepth of dTap,
    /// (c) are connected to the tap seed, and
    /// (d) have an adjacent-pixel depth jump no greater than
    ///     `discontinuityThresholdM`. Returns indices on the walked axis
    /// (column indices for `.row`, row indices for `.col`).
    /// Short-circuits if the seed pixel itself fails.
    ///
    /// Adjacent-jump check rationale (Phase 9): when two trunks at
    /// slightly different depths (e.g., 1.50 m vs 1.55 m) are visually
    /// adjacent, both fall inside the ±deltaDepth absolute window, so
    /// the older walk absorbed the second trunk and inflated the fit.
    /// Comparing each step's depth to the LAST accepted neighbour's
    /// depth instead detects the inter-trunk boundary as a sudden jump
    /// (typically ≥ 5 cm) without splitting clean trunks (where the
    /// per-pixel gradient stays under ~25 mm at the steepest edge for
    /// trunks up to 80 cm DBH at 1 m). Pass `Float.infinity` to disable.
    static func extractGuideStemStrip(
        frame: ARDepthFrame,
        axis: GuideAxis,
        tapAlongAxis: Int,
        dTap: Float,
        deltaDepth: Float,
        discontinuityThresholdM: Float = .infinity
    ) -> [Int] {
        let walkLength: Int
        switch axis {
        case .row(let y):
            guard y >= 0, y < frame.height else { return [] }
            walkLength = frame.width
        case .col(let x):
            guard x >= 0, x < frame.width else { return [] }
            walkLength = frame.height
        }
        let clampedTap = max(0, min(walkLength - 1, tapAlongAxis))

        func depthAt(_ idx: Int) -> Float {
            let (x, y) = pixelCoords(axis: axis, idx: idx)
            return frame.depth(atX: x, y: y)
        }
        func confAt(_ idx: Int) -> UInt8 {
            let (x, y) = pixelCoords(axis: axis, idx: idx)
            return frame.confidence(atX: x, y: y)
        }
        func pixelValid(at idx: Int) -> Bool {
            if confAt(idx) < 1 { return false }
            let d = depthAt(idx)
            if d <= 0 { return false }
            return abs(d - dTap) < deltaDepth
        }

        // Walk from the tap seed outward. If the seed itself is invalid,
        // search the closest valid replacement within a small window.
        var seed = clampedTap
        if !pixelValid(at: seed) {
            var found = -1
            for off in 1...10 {
                let l = clampedTap - off
                if l >= 0, pixelValid(at: l) { found = l; break }
                let r = clampedTap + off
                if r < walkLength, pixelValid(at: r) { found = r; break }
            }
            if found < 0 { return [] }
            seed = found
        }

        let seedDepth = depthAt(seed)
        var indices: [Int] = [seed]

        // Walk one direction, comparing each new pixel's depth to the
        // previously accepted neighbour's depth (NOT to dTap). A sudden
        // jump means we've hit the boundary between two trunks (or a
        // step feature) and should stop, leaving the strip on the
        // seed's trunk only.
        var i = seed - 1
        var lastDepth = seedDepth
        while i >= 0, pixelValid(at: i) {
            let d = depthAt(i)
            if abs(d - lastDepth) > discontinuityThresholdM { break }
            indices.append(i)
            lastDepth = d
            i -= 1
        }

        // Walk the other direction with a fresh `lastDepth` anchored at the seed.
        i = seed + 1
        lastDepth = seedDepth
        while i < walkLength, pixelValid(at: i) {
            let d = depthAt(i)
            if abs(d - lastDepth) > discontinuityThresholdM { break }
            indices.append(i)
            lastDepth = d
            i += 1
        }

        indices.sort()
        return indices
    }

    // MARK: - Step 8: metrics

    static func rootMeanSquaredResidual(
        inliers: [SIMD2<Double>], circle: Circle2D
    ) -> Double {
        guard !inliers.isEmpty else { return 0 }
        var sumSq = 0.0
        for p in inliers {
            let dx = p.x - circle.cx
            let dy = p.y - circle.cy
            let r  = (dx * dx + dy * dy).squareRoot()
            let e  = r - circle.radius
            sumSq += e * e
        }
        return (sumSq / Double(inliers.count)).squareRoot()
    }

    /// Observed silhouette chord in metres — the XZ bounding-box
    /// diagonal of a point cloud drawn from the trunk's front arc.
    /// For arcs smaller than a hemisphere (the common case) the
    /// bounding-box diagonal is within a couple of percent of the
    /// true chord between the leftmost and rightmost stem pixels,
    /// and therefore within a few percent of the trunk diameter.
    /// Used as an independent sanity check against the RANSAC radius.
    static func chordDiameterFromCloud(_ points: [SIMD2<Double>]) -> Double {
        guard !points.isEmpty else { return 0 }
        var minX =  Double.infinity, maxX = -Double.infinity
        var minZ =  Double.infinity, maxZ = -Double.infinity
        for p in points {
            if p.x < minX { minX = p.x }
            if p.x > maxX { maxX = p.x }
            if p.y < minZ { minZ = p.y }
            if p.y > maxZ { maxZ = p.y }
        }
        let dx = maxX - minX
        let dz = maxZ - minZ
        return (dx * dx + dz * dz).squareRoot()
    }

    /// Unwrapped angular span of a set of points around `center`.
    /// Computed as 2π minus the largest gap between adjacent angles.
    static func arcCoverageDeg(
        inliers: [SIMD2<Double>],
        center: (Double, Double)
    ) -> Double {
        guard inliers.count >= 2 else { return 0 }
        var angles = inliers.map { atan2($0.y - center.1, $0.x - center.0) }
        angles.sort()
        var maxGap = 0.0
        for i in 1..<angles.count {
            maxGap = max(maxGap, angles[i] - angles[i - 1])
        }
        // wrap-around gap
        let wrap = 2 * .pi - (angles.last! - angles.first!)
        maxGap = max(maxGap, wrap)
        let span = 2 * .pi - maxGap
        return span * 180 / .pi
    }

    static func sigmaR(
        noiseMeters: Double, nInliers: Int, arcCoverageDeg arcDeg: Double
    ) -> Double {
        guard nInliers > 0, arcDeg > 0 else { return .infinity }
        let halfArcRad = arcDeg * .pi / 360
        let sinHalf = max(sin(halfArcRad), 1e-3)
        return noiseMeters / (Double(nInliers).squareRoot() * sinHalf)
    }

    /// CoV of per-frame Taubin radii. Frames that fail to back-project or
    /// fit are skipped. Returns 0 when fewer than two frames contribute.
    static func perFrameRadiusCoV(
        frames: [ARDepthFrame],
        axis: GuideAxis,
        tapAlongAxis: Int,
        dTap: Float,
        discontinuityThresholdM: Float = .infinity
    ) -> Double {
        var radii: [Double] = []
        for frame in frames {
            let strip = extractGuideStemStrip(
                frame: frame,
                axis: axis,
                tapAlongAxis: tapAlongAxis,
                dTap: dTap,
                deltaDepth: guideStripDepthBudgetM,
                discontinuityThresholdM: discontinuityThresholdM)
            if strip.count < 5 { continue }
            let pts = strip.map { idx -> SIMD2<Double> in
                let (px, py) = pixelCoords(axis: axis, idx: idx)
                return BackProjection.worldXZ(
                    x: Double(px), y: Double(py),
                    depth: Double(frame.depth(atX: px, y: py)),
                    intrinsics: frame.intrinsics,
                    cameraPoseWorld: frame.cameraPoseWorld)
            }
            if let c = TaubinFit.fit(points: pts) { radii.append(c.radius) }
        }
        guard radii.count >= 2 else { return 0 }
        let mean = radii.reduce(0, +) / Double(radii.count)
        guard mean > 0 else { return 0 }
        var varSum = 0.0
        for r in radii { varSum += (r - mean) * (r - mean) }
        let sd = (varSum / Double(radii.count)).squareRoot()
        return sd / mean
    }

    // MARK: - Live preview (single-frame, no RANSAC)

    /// Result of the cheap single-frame preview fit. Used by the scan
    /// HUD to render a live DBH estimate, a 3D cylinder marker placed
    /// in the AR scene, and a distance-to-center readout.
    ///
    /// Phase 14.4 added the §7.1-style quality fields (`tier`,
    /// `inlierCount`, `arcDeg`, `rmseMm`, `rejectionReason`) so the HUD
    /// can refuse to publish a value the cruiser shouldn't trust. The
    /// view model treats `.red` as "do not display" and surfaces the
    /// reason in the status banner instead of a numeric estimate.
    public struct PreviewFit: Equatable, Sendable {
        /// Estimated trunk diameter in centimetres.
        public let diameterCm: Double
        /// Trunk centre in world XZ metres (height is set by the caller —
        /// usually the camera Y for the DBH row).
        public let centerWorldXZ: SIMD2<Double>
        /// Trunk radius in metres.
        public let radiusM: Double
        /// Leftmost stem strip pixel, normalised to 0...1 of frame width.
        /// Used by the HUD to draw a 2D fit-chord overlay across the trunk.
        public let stripLeftFraction: Double
        /// Rightmost stem strip pixel, same normalisation.
        public let stripRightFraction: Double
        /// Confidence tier from the §7.1 sanity tree applied to this fit.
        /// `.red` means the HUD should hide the value; `.yellow` means
        /// show it with a caution badge; `.green` is fully trustworthy.
        public let tier: ConfidenceTier
        /// Number of points inside the inlier-tolerance band of the
        /// chosen circle. Drives the inlier-count check.
        public let inlierCount: Int
        /// Angular span (degrees) the inliers cover around the fitted
        /// centre. Drives the arc-coverage check.
        public let arcDeg: Double
        /// RMS radial residual of the inliers (millimetres). Drives the
        /// rmse / r quality check.
        public let rmseMm: Double
        /// Human-readable rejection reason when `tier == .red`, nil
        /// otherwise.
        public let rejectionReason: String?
        /// Effective tap depth (metres) actually used to anchor the
        /// stem-strip's ±deltaDepth window for *this* frame. The caller
        /// is expected to feed this back as the next frame's
        /// `tapDepthHint` — that's what stops the depth window from
        /// sliding under hand tremor and prevents a fresh inlier set
        /// being chosen every preview tick.
        public let effectiveTapDepth: Double

        public init(diameterCm: Double,
                    centerWorldXZ: SIMD2<Double>,
                    radiusM: Double,
                    stripLeftFraction: Double,
                    stripRightFraction: Double,
                    tier: ConfidenceTier,
                    inlierCount: Int,
                    arcDeg: Double,
                    rmseMm: Double,
                    rejectionReason: String?,
                    effectiveTapDepth: Double) {
            self.diameterCm = diameterCm
            self.centerWorldXZ = centerWorldXZ
            self.radiusM = radiusM
            self.stripLeftFraction = stripLeftFraction
            self.stripRightFraction = stripRightFraction
            self.tier = tier
            self.inlierCount = inlierCount
            self.arcDeg = arcDeg
            self.rmseMm = rmseMm
            self.rejectionReason = rejectionReason
            self.effectiveTapDepth = effectiveTapDepth
        }
    }

    /// Single-frame preview that runs a direct Taubin circle fit on the
    /// back-projected stem strip — the same geometric model the full
    /// pipeline uses, just single-frame and without RANSAC / outlier
    /// removal. The previous chord-based preview always under-read the
    /// diameter on large trees (the chord of a tangent-limited strip
    /// is shorter than the true diameter), so the cruiser saw the live
    /// number disagree with the final burst measurement by 20–30 %.
    /// A direct circle fit removes that systematic bias.
    ///
    /// Returns nil when:
    ///   • tap depth is outside the 0.5–3.0 m scan band
    ///   • the strip is too short to fit a circle
    ///   • the fitted radius falls outside a sanity range
    ///   • the chord-based estimate (still computed as a fallback)
    ///     wildly disagrees with the fit (another inflated-fit guard)
    ///
    /// The chord is also kept for the HUD fit-line overlay, which
    /// spans the actual strip endpoints.
    public static func previewFit(
        frame: ARDepthFrame,
        tapPixel: SIMD2<Double>,
        guideAxis: GuideAxis,
        deltaDepth: Float = 0.15,
        discontinuityThresholdM: Float = 0.04,
        tapDepthHint: Double? = nil
    ) -> PreviewFit? {
        guard let rawTapDepth = medianDepth(around: tapPixel, frame: frame, radius: 2)
        else { return nil }
        guard (0.5...3.0).contains(rawTapDepth) else { return nil }

        // Phase 18.1 — depth-window anchoring. The 5×5 median is stable
        // *within* a frame but fluctuates 5–10 cm frame-to-frame because
        // hand tremor moves the screen-centre pixel across different
        // parts of the trunk surface (and bark roughness alone is a few
        // cm). Without anchoring, the ±deltaDepth window slides every
        // tick, swaps in a different set of edge pixels, and forces
        // RANSAC to vote on a brand-new point set — which is the root
        // cause behind the "DBH and distance jitter even when standing
        // still" complaint. Blend the raw depth with the previous tick's
        // effective depth so the window only drifts slowly. A 25 cm gate
        // keeps the anchor honest: if the cruiser actually retargets a
        // different tree (or steps closer/farther by more than half a
        // depth window), drop the hint and let the raw depth take over.
        let dTap: Float
        if let hintDouble = tapDepthHint {
            let hint = Float(hintDouble)
            if abs(rawTapDepth - hint) <= 0.25 {
                dTap = 0.35 * rawTapDepth + 0.65 * hint
            } else {
                dTap = rawTapDepth
            }
        } else {
            dTap = rawTapDepth
        }

        let strip = extractGuideStemStrip(
            frame: frame,
            axis: guideAxis,
            tapAlongAxis: tapAlongAxis(tapPixel, axis: guideAxis),
            dTap: dTap,
            deltaDepth: deltaDepth,
            discontinuityThresholdM: discontinuityThresholdM)
        guard let leftIdx = strip.first, let rightIdx = strip.last,
              rightIdx > leftIdx,
              strip.count >= 6
        else { return nil }

        // Back-project every strip pixel, not just the endpoints — this
        // is what the single-frame Taubin fit wants.
        var stripPoints: [SIMD2<Double>] = []
        stripPoints.reserveCapacity(strip.count)
        for idx in strip {
            let (px, py) = pixelCoords(axis: guideAxis, idx: idx)
            let depth = frame.depth(atX: px, y: py)
            guard depth > 0 else { continue }
            let p = BackProjection.worldXZ(
                x: Double(px), y: Double(py),
                depth: Double(depth),
                intrinsics: frame.intrinsics,
                cameraPoseWorld: frame.cameraPoseWorld)
            stripPoints.append(p)
        }
        guard stripPoints.count >= 6 else { return nil }

        // Endpoints power the fit-line overlay + chord sanity guard.
        let leftWorld  = stripPoints.first!
        let rightWorld = stripPoints.last!
        let dx = rightWorld.x - leftWorld.x
        let dz = rightWorld.y - leftWorld.y
        let chordM = (dx * dx + dz * dz).squareRoot()

        // Phase 14.3: outlier-aware fit. The earlier circumradius preview
        // (Phase 14.2) used the strip's two endpoints plus an apex from
        // the 5×5-median tap depth — only three points. If any of those
        // landed on a bark crack or a noise spike, the radius diverged
        // anyway. Solve the right problem instead: run the same RANSAC
        // we already trust in the burst pipeline, just with a smaller
        // iteration budget so the cost stays well inside the 10 Hz
        // preview tick. Stratified 3-point sampling votes by inlier
        // count over ALL ~67 strip points, then Taubin refits only the
        // surviving inliers — outliers can never enter the final fit.
        //
        // Tolerance mirrors burst: max(3 mm, 2·sensor σ). Default σ is
        // 5 mm → 10 mm tolerance — tight enough to reject thin branch
        // pixels but loose enough to absorb bark roughness.
        // Phase 17.1: thresholds calibrated for thin-trunk reach. A
        // 10 cm DBH stem at 1 m only spans ≈ 22 sensor pixels, leaves
        // ≈ 15 inliers after RANSAC, and after the 16.1 trim ≈ 12. The
        // earlier 15-floor on minInliers (and 20-floor on the §7.1
        // tier check below) rejected those thin-stem fits outright,
        // even though forestry cruise routinely measures saplings down
        // to 4–10 cm. Lower the floors to admit those legitimate
        // small-tree fits while the arc, rmse, and radius checks still
        // catch a non-trunk lock.
        let depthNoiseM: Double = 0.005   // matches ProjectCalibration default
        let inlierTol = max(0.003, 2.0 * depthNoiseM)
        let minInliers = max(10, stripPoints.count / 4)
        let chordTooShort = chordM < 0.03

        var radiusM: Double
        var diameterCm: Double
        var fittedCenter: SIMD2<Double>?
        var inlierCount: Int = 0
        var arcDeg: Double = 0
        var rmseMm: Double = 0
        var ransacFailed = false

        if let robust = RANSACCircle.fit(
            points: stripPoints,
            inlierTol: inlierTol,
            iterations: 80,
            minInliers: minInliers
        ) {
            // Phase 16.1: trimmed least-squares refinement. RANSAC's
            // tolerance band is wide enough to absorb LiDAR noise, but
            // the worst residuals inside that band (bark cracks,
            // tangent-edge points) still skew Taubin's algebraic refit
            // and inflate the rmse. Drop the top quintile by residual
            // and refit once more — the cleanest 80 % of points pull
            // the radius onto a tighter, more honest fit. Answers the
            // cruiser's ask to "exclude the outliers and keep
            // computing" rather than rejecting whole fits on a
            // too-tight rmse gate.
            var refinedInliers = robust.inliers
            var refinedCircle = robust.circle
            if refinedInliers.count >= 18 {
                let pairs = refinedInliers.map { p -> (Double, SIMD2<Double>) in
                    let dx = p.x - refinedCircle.cx
                    let dy = p.y - refinedCircle.cy
                    let r = (dx * dx + dy * dy).squareRoot()
                    return (abs(r - refinedCircle.radius), p)
                }
                let sorted = pairs.sorted { $0.0 < $1.0 }
                let keep = max(12, Int(Double(sorted.count) * 0.80))
                refinedInliers = sorted.prefix(keep).map { $0.1 }
                if let refit = TaubinFit.fit(points: refinedInliers) {
                    refinedCircle = refit
                }
            }
            // Phase 17.2: split where each metric reads from.
            //   • radius / rmse — post-trim (refined fit quality)
            //   • inlier count / arc — pre-trim (what the camera saw)
            // Trimming the top quintile by residual systematically drops
            // tangent-edge points (their grazing-angle LiDAR noise is
            // 1/cos(angle) larger), and those edge points are precisely
            // the ones that anchor the widest arc. Computing arc on the
            // trimmed set was making the §7.1 ≥ 45° gate fire on real
            // trees that the camera plainly observed past 90°.
            radiusM = refinedCircle.radius
            diameterCm = 2.0 * radiusM * 100.0
            fittedCenter = SIMD2(refinedCircle.cx, refinedCircle.cy)
            let rmse = rootMeanSquaredResidual(
                inliers: refinedInliers, circle: refinedCircle)
            rmseMm = rmse * 1000
            inlierCount = robust.inliers.count
            arcDeg = arcCoverageDeg(
                inliers: robust.inliers,
                center: (robust.circle.cx, robust.circle.cy))
        } else {
            // Not enough trunk-like points for a robust fit. Fall back
            // to the silhouette chord — at least the cruiser sees a
            // value tied to what's on screen rather than a stale or
            // missing readout, but flag it red so the HUD doesn't
            // present it as authoritative.
            guard !chordTooShort else { return nil }
            radiusM = chordM / 2.0
            diameterCm = chordM * 100.0
            ransacFailed = true
        }

        // Sanity range still applies — and so does the chord override
        // for inflated / deflated fits, just in case RANSAC's inlier
        // set was thin enough that Taubin's refit drifted off.
        let diameterOutOfRange = !(5.0...200.0).contains(diameterCm)
        let inflatedVsChord = chordM > 0.025 && (diameterCm / 100.0) / chordM > 3.0
        let deflatedVsChord = chordM > 0.025 && (diameterCm / 100.0) / chordM < 0.85
        var chordOverride = false
        if diameterOutOfRange || inflatedVsChord || deflatedVsChord {
            guard !chordTooShort else { return nil }
            radiusM = chordM / 2.0
            diameterCm = chordM * 100.0
            fittedCenter = nil
            chordOverride = true
        }
        guard (5.0...200.0).contains(diameterCm) else { return nil }

        // Centre: prefer the fitted centre; otherwise project the chord
        // midpoint one radius further from the camera (the "behind the
        // chord" centre of a circle whose front arc is what we just
        // measured).
        let center: SIMD2<Double>
        if let c = fittedCenter {
            center = c
        } else {
            let nearMid = SIMD2<Double>((leftWorld.x + rightWorld.x) / 2.0,
                                         (leftWorld.y + rightWorld.y) / 2.0)
            let cam = frame.cameraPoseWorld.columns.3
            let cameraXZ = SIMD2<Double>(Double(cam.x), Double(cam.z))
            let toSurface = nearMid - cameraXZ
            let dist = (toSurface.x * toSurface.x + toSurface.y * toSurface.y).squareRoot()
            let unit: SIMD2<Double> = dist > 1e-6
                ? SIMD2(toSurface.x / dist, toSurface.y / dist)
                : SIMD2(0, 1)
            center = nearMid + SIMD2(unit.x * radiusM, unit.y * radiusM)
        }

        // Strip endpoints normalised against the walked axis's extent —
        // width for `.row`, height for `.col`. The HUD overlay maps these
        // to its on-screen along-axis pixel range so the chord lines up
        // with the trunk in either orientation.
        let extent: Double
        switch guideAxis {
        case .row: extent = Double(frame.width)
        case .col: extent = Double(frame.height)
        }
        let leftFrac = extent > 0 ? Double(leftIdx) / extent : 0
        let rightFrac = extent > 0 ? Double(rightIdx) / extent : 1

        // §7.1-style sanity tree applied to the single-frame preview.
        // sigmaR / radiusCoV are skipped — the former needs the burst's
        // multi-frame noise model and the latter is multi-frame by
        // definition. RANSAC failure or chord override forces .red so
        // the HUD knows not to publish the value as authoritative.
        let radiusM_ = radiusM     // keep a copy for capture-by-Bool checks
        let rmseRatio = radiusM_ > 0 ? rmseMm / 1000.0 / radiusM_ : Double.infinity
        let checks: [Check] = [
            // The live HUD banner publishes these (via
            // `DBHScanViewModel.previewStatusText`), so a cruiser reads them
            // continuously while aiming. The first two are the SAME two
            // failures the burst path reports above — a circle that wouldn't
            // fit, and too few points on the trunk — so they are worded
            // identically here. Two sentences for one failure taught the
            // cruiser that the live view and the capture disagreed. Every
            // PREDICATE is unchanged.
            check(!ransacFailed, sev: .reject,
                  reason: "Couldn't find a round trunk in that scan — aim at the stem, hold steadier, and capture again."),
            check(inlierCount >= 10, sev: .reject,
                  reason: "Too little of the trunk was picked up — move closer, fill the crosshair with bark, and capture again."),
            check(inlierCount >= 18, sev: .warn,
                  reason: "Only 10–18 trunk surface points"),
            check(arcDeg >= TierThresholds.minArcDegReject, sev: .reject,
                  reason: "Not enough of the trunk in view — step back or centre the guide line."),
            check(arcDeg >= TierThresholds.minArcDegWarn, sev: .warn,
                  reason: "Trunk arc coverage 30°–45°"),
            check(radiusM_ >= 0.025 && radiusM_ <= 1.0, sev: .reject,
                  reason: "That doesn't measure like a trunk — aim at the stem and capture again."),
            check(rmseRatio <= 0.07, sev: .reject,
                  reason: "The shape didn't match a trunk — hold steadier and capture again."),
            check(rmseRatio <= 0.05, sev: .warn,
                  reason: "Fit error 5–7% of radius"),
            check(!chordOverride, sev: .warn,
                  reason: "Fit disagreed with silhouette; using chord")
        ]
        // Phase 18.2 — preview-specific tier rule. The §7.9 default
        // promotes "≥2 warns" to red, which made the live HUD flap
        // between "Stabilizing…" (yellow) and the red "didn't pass the
        // accuracy checks" line whenever two warns happened to fire on the
        // same frame (e.g. narrow arc + chord override). Yellow is
        // already publishable and visually silent for the cruiser, so
        // we suppress that promotion here — only an actual reject
        // failure paints the preview red.
        let hasReject = checks.contains { !$0.passed && $0.severity == .reject }
        let hasWarn = checks.contains { !$0.passed && $0.severity == .warn }
        let tier: ConfidenceTier = hasReject
            ? .red : (hasWarn ? .yellow : .green)
        let rejectionReason: String? = hasReject
            ? (firstFailingRejectReason(checks)
               ?? "This reading didn't pass the accuracy checks — retake it, or accept it and flag the tree.")
            : nil

        return PreviewFit(
            diameterCm: diameterCm,
            centerWorldXZ: center,
            radiusM: radiusM,
            stripLeftFraction: leftFrac,
            stripRightFraction: rightFrac,
            tier: tier,
            inlierCount: inlierCount,
            arcDeg: arcDeg,
            rmseMm: rmseMm,
            rejectionReason: rejectionReason,
            effectiveTapDepth: Double(dTap))
    }

    /// Back-compat helper — returns just the diameter when only the
    /// scalar is wanted.
    public static func previewDiameterCm(
        frame: ARDepthFrame,
        tapPixel: SIMD2<Double>,
        guideAxis: GuideAxis,
        deltaDepth: Float = 0.15
    ) -> Double? {
        previewFit(frame: frame, tapPixel: tapPixel,
                   guideAxis: guideAxis,
                   deltaDepth: deltaDepth)?.diameterCm
    }

    // MARK: - Chord / silhouette method (Phase 19 default)

    /// Chord-specific strip walk. Unlike `extractGuideStemStrip`, this
    /// one is meant to find the *silhouette edges* of the trunk — the
    /// last LiDAR-valid pixel before the depth either drops to 0
    /// (no return) or jumps to a far background. The legacy extractor's
    /// ±deltaDepth window was tuned for circle-fit point selection
    /// (it deliberately discards the tangent-edge pixels because their
    /// grazing-angle noise corrupts the Taubin fit). For chord we want
    /// the opposite: we *need* the tangent edges because that's the
    /// silhouette width the formula reads.
    ///
    /// Walks outward from the seed, stops when the next pixel:
    ///   • has confidence 0 or depth ≤ 0 (no return / sky / occluder)
    ///   • is more than `silhouetteJumpM` further/closer than the last
    ///     accepted neighbour (catches trunk-to-background transitions
    ///     and split-stem situations).
    /// The default jump (0.30 m) is large enough to absorb the
    /// geometric gradient near the tangent of trunks up to 80 cm DBH at
    /// 1 m, and small enough to catch the typical 0.5 m+ trunk-to-tree
    /// or trunk-to-foliage step.
    static func extractChordSilhouetteStrip(
        frame: ARDepthFrame,
        axis: GuideAxis,
        tapAlongAxis: Int,
        silhouetteJumpM: Float = 0.30
    ) -> [Int] {
        let walkLength: Int
        switch axis {
        case .row(let y):
            guard y >= 0, y < frame.height else { return [] }
            walkLength = frame.width
        case .col(let x):
            guard x >= 0, x < frame.width else { return [] }
            walkLength = frame.height
        }
        let clampedTap = max(0, min(walkLength - 1, tapAlongAxis))

        func depthAt(_ idx: Int) -> Float {
            let (x, y) = pixelCoords(axis: axis, idx: idx)
            return frame.depth(atX: x, y: y)
        }
        func confAt(_ idx: Int) -> UInt8 {
            let (x, y) = pixelCoords(axis: axis, idx: idx)
            return frame.confidence(atX: x, y: y)
        }
        func valid(_ idx: Int) -> Bool {
            confAt(idx) >= 1 && depthAt(idx) > 0
        }

        // Find the seed — closest valid pixel to the tap along the axis.
        var seed = clampedTap
        if !valid(seed) {
            var found = -1
            for off in 1...10 {
                let l = clampedTap - off
                if l >= 0, valid(l) { found = l; break }
                let r = clampedTap + off
                if r < walkLength, valid(r) { found = r; break }
            }
            if found < 0 { return [] }
            seed = found
        }

        var indices: [Int] = [seed]
        var lastDepth = depthAt(seed)

        // Walk left.
        var i = seed - 1
        while i >= 0, valid(i) {
            let d = depthAt(i)
            if abs(d - lastDepth) > silhouetteJumpM { break }
            indices.append(i)
            lastDepth = d
            i -= 1
        }

        // Walk right (reset the running anchor at the seed).
        lastDepth = depthAt(seed)
        i = seed + 1
        while i < walkLength, valid(i) {
            let d = depthAt(i)
            if abs(d - lastDepth) > silhouetteJumpM { break }
            indices.append(i)
            lastDepth = d
            i += 1
        }

        indices.sort()
        return indices
    }

    /// Single-frame DBH preview using the chord / silhouette identity:
    ///
    ///     diameter_m ≈ pixel_width × tap_depth_m / fx
    ///
    /// where `pixel_width` is the count of contiguous trunk pixels along
    /// the guide axis at the cruiser's chosen row. We extract that strip
    /// at the guide row plus a stack of neighbouring rows (± 10 by
    /// default) and take the **median** width across rows — branches /
    /// leaves / bark cracks contaminate at most one or two rows, never
    /// the median of 21. The result is a single scalar that no circle
    /// fit can amplify; the only failure modes are "no trunk pixels at
    /// all" and "out-of-range diameter", both of which we detect with a
    /// straightforward sanity gate.
    ///
    /// Why this replaces the partial-arc Taubin fit as the default:
    /// every peer LiDAR forestry app (with segmentation models
    /// like YOLACT++ or Single-Shot SAM) uses essentially this method.
    /// Geometrically the chord is what LiDAR can actually see — the
    /// Taubin radius depends on observing curvature across an arc, but
    /// a hand-held phone at 1.5 m only ever sees ≈ 60–90° of a small
    /// trunk, which is geometrically too narrow to constrain the radius
    /// (the centre is well-fit, the radius isn't). That's why the
    /// previous DBH digit jittered ± 5 cm even when the cruiser stood
    /// still, while the distance-to-trunk number stayed stable.
    public static func chordPreviewFit(
        frame: ARDepthFrame,
        tapPixel: SIMD2<Double>,
        guideAxis: GuideAxis,
        rowSpan: Int = 10,
        silhouetteJumpM: Float = 0.30,
        discontinuityThresholdM: Float = 0.30
    ) -> PreviewFit? {
        _ = discontinuityThresholdM  // chord uses silhouetteJumpM, kept for sig parity
        // Guard 1 — tap depth must be in the LiDAR-usable range. Phase
        // 19 widened this from 0.5–3.0 m to 0.3–5.0 m to match the
        // sensor's actual envelope; chord widths stay reliable across
        // that whole band because a wider field-of-view gives more
        // pixels to median over.
        guard let dTap = medianDepth(around: tapPixel, frame: frame, radius: 2)
        else { return nil }
        guard (0.3...5.0).contains(dTap) else { return nil }

        let fx = Double(frame.intrinsics[0, 0])
        guard fx > 0 else { return nil }

        let centerAlong = tapAlongAxis(tapPixel, axis: guideAxis)

        // Walk the guide row plus a stack of neighbour rows. Keep the
        // ones that produced a usable strip (≥ 5 pixels wide).
        var widths: [Int] = []
        var firstUsableExtent: (left: Int, right: Int)?
        for offset in -rowSpan...rowSpan {
            let neighbourAxis: GuideAxis
            switch guideAxis {
            case .row(let y): neighbourAxis = .row(y: y + offset)
            case .col(let x): neighbourAxis = .col(x: x + offset)
            }
            // Silhouette walk — captures tangent-edge pixels the legacy
            // extractor would discard, because the chord identity reads
            // off the *full projected width* not the inner stem-strip.
            let strip = extractChordSilhouetteStrip(
                frame: frame,
                axis: neighbourAxis,
                tapAlongAxis: centerAlong,
                silhouetteJumpM: silhouetteJumpM)
            guard let l = strip.first, let r = strip.last, r > l else { continue }
            let w = r - l + 1
            if w < 5 { continue }
            widths.append(w)
            if firstUsableExtent == nil, offset == 0 {
                firstUsableExtent = (l, r)
            } else if firstUsableExtent == nil {
                firstUsableExtent = (l, r)
            }
        }

        // Need at least a handful of rows agreeing on the width — one
        // row alone could be a branch crossing the guide line.
        guard widths.count >= 5 else { return nil }
        guard let extent = firstUsableExtent else { return nil }

        // Sort + median. Take the middle row's width.
        let sortedWidths = widths.sorted()
        let medianWidth = sortedWidths[sortedWidths.count / 2]

        // Diameter from the silhouette, by the tangent form.
        //
        //   k  = w / 2fx                    half-angle, from the pinhole
        //   K  = k(k + sqrt(k² + 1))        = R / z_near, the tangent solution
        //   d  = 2 · dTap · K
        //
        // WHAT THIS REPLACED, and why. The old line inverted
        // d = w·dTap/(fx − w/2), which comes from putting the edge at the
        // widest point of the circle — half a diameter behind the near face
        // — and calling the depth to THAT the axis distance. IT IS WRONG
        // ABOUT WHERE THE EDGE IS. It puts the edge at
        // the widest point of the circle, half a diameter behind the near
        // face; the edge a camera actually sees is the TANGENT point, which
        // is nearer and narrower. Every other diameter in this app inverts
        // the tangent form (`silhouetteDiameterCm`) and this path is the
        // last one that did not, which is why Auto and ADJUST stopped
        // agreeing the day the bracket moved over.
        //
        // The offset term is ZERO here, and that is the difference between
        // the two paths rather than an omission. `silhouetteDiameterCm`
        // carries `medianDepthOffsetFactor` because the bracket medians the
        // depth across the middle half of a curved face, which sits
        // 0.031754 R behind the near face. This path has no such spread: the
        // depth it uses is `dTap`, one reading at the tap, on the near face
        // the tangent form already wants. Substituting the bracket's offset
        // here would correct for a spread that is not in the number.
        let halfWidth = Double(medianWidth) / 2.0
        guard fx - halfWidth > 1.0 else { return nil }
        let k = Double(medianWidth) / (2.0 * fx)
        let kk = k * (k + (k * k + 1).squareRoot())
        let diameterM = 2.0 * Double(dTap) * kk
        let diameterCm = diameterM * 100.0
        guard plausibleDiameterCm.contains(diameterCm) else { return nil }

        // Confidence: width consistency. Tight CoV ⇒ green; otherwise
        // yellow (renders as a silent / "gray" chip in the HUD per
        // Phase 19's "green or quiet" rule).
        let mean = Double(widths.reduce(0, +)) / Double(widths.count)
        var sumSq = 0.0
        for w in widths {
            let d = Double(w) - mean
            sumSq += d * d
        }
        let std = (sumSq / Double(widths.count)).squareRoot()
        let cov = mean > 0 ? std / mean : 1.0
        let tier: ConfidenceTier = cov <= 0.10 ? .green : .yellow

        // Centre for the cylinder overlay + distance HUD: back-project
        // the guide-row strip's midpoint to world XZ, then push one
        // radius further along the surface ray (the cylinder's axis
        // sits one radius behind the visible front arc).
        let midIdx = (extent.left + extent.right) / 2
        let (mpx, mpy) = pixelCoords(axis: guideAxis, idx: midIdx)
        let pixDepth = Double(frame.depth(atX: mpx, y: mpy))
        let depthForBackProject = pixDepth > 0 ? pixDepth : Double(dTap)
        let surfaceXZ = BackProjection.worldXZ(
            x: Double(mpx), y: Double(mpy),
            depth: depthForBackProject,
            intrinsics: frame.intrinsics,
            cameraPoseWorld: frame.cameraPoseWorld)
        let cam = frame.cameraPoseWorld.columns.3
        let camXZ = SIMD2<Double>(Double(cam.x), Double(cam.z))
        let toSurface = surfaceXZ - camXZ
        let dist = (toSurface.x * toSurface.x + toSurface.y * toSurface.y).squareRoot()
        let unit: SIMD2<Double> = dist > 1e-6
            ? SIMD2(toSurface.x / dist, toSurface.y / dist)
            : SIMD2(0, 1)
        let radiusM = diameterM / 2.0
        let center = surfaceXZ + SIMD2(unit.x * radiusM, unit.y * radiusM)

        // Strip-edge fractions for the on-screen chord overlay.
        let frameExtent: Double
        switch guideAxis {
        case .row: frameExtent = Double(frame.width)
        case .col: frameExtent = Double(frame.height)
        }
        let leftFrac = frameExtent > 0 ? Double(extent.left) / frameExtent : 0
        let rightFrac = frameExtent > 0 ? Double(extent.right) / frameExtent : 1

        return PreviewFit(
            diameterCm: diameterCm,
            centerWorldXZ: center,
            radiusM: radiusM,
            stripLeftFraction: leftFrac,
            stripRightFraction: rightFrac,
            tier: tier,
            inlierCount: medianWidth,
            arcDeg: 0,
            rmseMm: 0,
            rejectionReason: nil,
            effectiveTapDepth: Double(dTap))
    }

    /// Burst-mode chord measurement. Runs `chordPreviewFit` against
    /// every frame in the burst, takes the median diameter (so a single
    /// foreshortened or motion-blurred frame can't drag the result),
    /// and applies the project's cylinder calibration on the way out.
    /// Confidence is `.green` when the per-frame chord widths are tight,
    /// `.yellow` when they spread, `.red` only when too few frames
    /// produced a chord at all.
    public static func chordEstimate(input: DBHScanInput) -> DBHResult? {
        guard input.frames.count >= 5 else { return nil }

        var diameters: [Double] = []
        var centersX: [Double] = []
        var centersZ: [Double] = []
        var widths: [Int] = []
        for frame in input.frames {
            guard let fit = chordPreviewFit(
                frame: frame,
                tapPixel: input.tapPixel,
                guideAxis: input.guideAxis,
                discontinuityThresholdM: input.projectCalibration.depthDiscontinuityM)
            else { continue }
            diameters.append(fit.diameterCm)
            centersX.append(fit.centerWorldXZ.x)
            centersZ.append(fit.centerWorldXZ.y)
            widths.append(fit.inlierCount)
        }

        guard diameters.count >= TierThresholds.minUsableFrames else {
            return redResult(
                reason: "Not enough usable frames; hold steadier or move closer",
                method: .lidarChordSilhouette,
                nInliers: diameters.count)
        }

        // Median diameter — the chord version of the burst's "consensus"
        // value. CoV across frames maps to the confidence tier.
        let sortedDia = diameters.sorted()
        let medianRawCm = sortedDia[sortedDia.count / 2]
        let mean = sortedDia.reduce(0, +) / Double(sortedDia.count)
        let lo = sortedDia.first ?? mean
        let hi = sortedDia.last ?? mean
        let cov = mean > 0 ? (hi - lo) / mean : 1
        let tier: ConfidenceTier = cov <= TierThresholds.frameSpreadGreen ? .green : .yellow

        // Apply cylinder calibration last so the published cm value
        // ends up identical to what a trained Cylinder calibration on
        // a chord-method burst would expect.
        let cal = input.projectCalibration
        let dbhCm = cal.appliedToRawCm(medianRawCm)

        // Median centre across frames for the persisted XZ — robust to
        // a frame or two with bad depth.
        let sortedCx = centersX.sorted()
        let sortedCz = centersZ.sorted()
        let medCenter = SIMD2<Float>(
            Float(sortedCx[sortedCx.count / 2]),
            Float(sortedCz[sortedCz.count / 2]))

        return DBHResult(
            diameterCm: Float(dbhCm),
            centerXZ: medCenter,
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: 0,
            nInliers: widths.reduce(0, +),
            confidence: tier,
            method: .lidarChordSilhouette,
            rawPointsPath: nil,
            rejectionReason: nil)
    }

    /// Burst-mode DEPTH-BAND chord measurement — the fourth candidate
    /// geometry in the accuracy-validation sweep, and the Android sibling's
    /// `ChordAlgorithm.DEPTH_BAND` path ported to iOS so the two corpora can
    /// be scored with the same set of algorithms. Instead of the silhouette
    /// pixel-width, each frame extracts the depth-banded stem strip (±0.15 m
    /// around the tap depth), back-projects it to world XZ, and reads the
    /// diameter as the XZ bounding-box diagonal of that point cloud. Median
    /// across frames, then the project's cylinder calibration — identical
    /// tail to `chordEstimate`.
    public static func depthBandChordEstimate(
        frames: [ARDepthFrame],
        tapPixel: SIMD2<Double>,
        guideAxis: GuideAxis,
        calibration cal: ProjectCalibration
    ) -> DBHResult? {
        guard frames.count >= 5 else { return nil }
        let tapAlong = tapAlongAxis(tapPixel, axis: guideAxis)

        var diameters: [Double] = []
        var centersX: [Double] = []
        var centersZ: [Double] = []
        for frame in frames {
            guard let dTap = medianDepth(around: tapPixel, frame: frame, radius: 2),
                  (0.3...5.0).contains(dTap) else { continue }
            let strip = extractGuideStemStrip(
                frame: frame, axis: guideAxis, tapAlongAxis: tapAlong,
                dTap: dTap, deltaDepth: guideStripDepthBudgetM,
                discontinuityThresholdM: cal.depthDiscontinuityM)
            guard strip.count >= 6 else { continue }
            var pts: [SIMD2<Double>] = []
            pts.reserveCapacity(strip.count)
            for idx in strip {
                let (px, py) = pixelCoords(axis: guideAxis, idx: idx)
                pts.append(BackProjection.worldXZ(
                    x: Double(px), y: Double(py),
                    depth: Double(frame.depth(atX: px, y: py)),
                    intrinsics: frame.intrinsics,
                    cameraPoseWorld: frame.cameraPoseWorld))
            }
            let chordM = chordDiameterFromCloud(pts)
            guard chordM > 0 else { continue }
            let diameterCm = chordM * 100.0
            guard plausibleDiameterCm.contains(diameterCm) else { continue }
            diameters.append(diameterCm)
            var minX = Double.infinity, maxX = -Double.infinity
            var minZ = Double.infinity, maxZ = -Double.infinity
            for p in pts {
                minX = Swift.min(minX, p.x); maxX = Swift.max(maxX, p.x)
                minZ = Swift.min(minZ, p.y); maxZ = Swift.max(maxZ, p.y)
            }
            centersX.append((minX + maxX) / 2)
            centersZ.append((minZ + maxZ) / 2)
        }

        guard diameters.count >= TierThresholds.minUsableFrames else {
            return redResult(
                reason: "Not enough usable frames; hold steadier or move closer",
                method: .lidarChordSilhouette,
                nInliers: diameters.count)
        }

        let sortedDia = diameters.sorted()
        let medianRawCm = sortedDia[sortedDia.count / 2]
        let mean = sortedDia.reduce(0, +) / Double(sortedDia.count)
        let lo = sortedDia.first ?? mean
        let hi = sortedDia.last ?? mean
        let cov = mean > 0 ? (hi - lo) / mean : 1
        let tier: ConfidenceTier = cov <= TierThresholds.frameSpreadGreen ? .green : .yellow

        let dbhCm = cal.appliedToRawCm(medianRawCm)

        let sortedCx = centersX.sorted()
        let sortedCz = centersZ.sorted()
        let medCenter = SIMD2<Float>(
            Float(sortedCx[sortedCx.count / 2]),
            Float(sortedCz[sortedCz.count / 2]))

        return DBHResult(
            diameterCm: Float(dbhCm),
            centerXZ: medCenter,
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: 0,
            nInliers: diameters.count,
            confidence: tier,
            method: .lidarChordSilhouette,
            rawPointsPath: nil,
            rejectionReason: nil)
    }

    // MARK: - Manual edge-bracket (ADJUST) fits
    //
    // TWO ENTRY POINTS, and the split is the whole point of this section.
    //
    //   `bracketDepthGeometry` is the BOUNDARY. It takes the handles as the
    //   cruiser placed them — fractions of the VIEW — and converts them ONCE
    //   into depth-map fractions plus the walk axis, using the frame's
    //   view→depth affine.
    //
    //   `bracketChordFit` / `bracketChordEstimate` work purely in DEPTH
    //   space below that boundary, which is also the space the raw-capture
    //   manifest stores, so a recorded bracket replays through exactly the
    //   code that produced it.
    //
    // WHY THE BOUNDARY EXISTS (field report, follow-up to a3a2a91). The view
    // fractions used to be handed straight to the depth grid as if the two
    // shared a scale. They do not: a 4:3 sensor aspect-FILLED into a
    // ~19.5:9 portrait view shows only about 60 % of the image's short axis.
    // The crop is CENTRED, so the middle pixel is unaffected — which is why
    // the auto path, whose only view-space input is the centre, has always
    // been right — but a SPAN off the screen was inflated by the crop
    // factor. Every bracketed diameter read high by roughly a third to a
    // half, and it never looked wrong, because a fixed bracket agrees
    // frame-to-frame and so earns a small σ and a GREEN tier.
    //
    // Android's `DbhEstimator.constrainedEstimate` has always had this
    // boundary; this is the same rule, so a bracket now measures the same on
    // both platforms.

    /// The bracket as the DEPTH MAP sees it: which axis it walks, and where
    /// its two ends fall along that axis as fractions.
    ///
    /// Returns nil when the frame carries no view mapping. FAIL CLOSED is
    /// deliberate — the fallback that used to be here (assume 1:1) is the
    /// defect.
    public static func bracketDepthGeometry(
        frame: ARDepthFrame,
        leftFraction: Double,
        rightFraction: Double,
        guideFractionY: Double = 0.5,
        viewSize: CGSize
    ) -> (axis: GuideAxis, left: Double, right: Double)? {
        guard let mapping = frame.viewMapping,
              viewSize.width > 1, viewSize.height > 1,
              frame.width > 0, frame.height > 0 else { return nil }
        let lo = min(leftFraction, rightFraction)
        let hi = max(leftFraction, rightFraction)
        let gy = guideFractionY * Double(viewSize.height)
        let pL = mapping.viewToDepth(x: lo * Double(viewSize.width), y: gy)
        let pR = mapping.viewToDepth(x: hi * Double(viewSize.width), y: gy)
        let dx = abs(pR.x - pL.x)
        let dy = abs(pR.y - pL.y)
        // The walk axis is whichever depth axis the screen-horizontal
        // bracket covers more of — they sit 90° apart in portrait. Derived
        // per frame from the display transform, which is stable, rather than
        // voted on the scene's depth content, which is not: the old
        // `pickGuideAxis` latch could fix the wrong axis for a whole plot
        // and rescale it by 4:3.
        let rowWalk = dx >= dy
        let midX = (pL.x + pR.x) / 2
        let midY = (pL.y + pR.y) / 2
        let axis: GuideAxis = rowWalk
            ? .row(y: min(max(Int(midY.rounded()), 0), frame.height - 1))
            : .col(x: min(max(Int(midX.rounded()), 0), frame.width - 1))
        let extent = Double(rowWalk ? frame.width : frame.height)
        guard extent > 1 else { return nil }
        let a = (rowWalk ? pL.x : pL.y) / extent
        let b = (rowWalk ? pR.x : pR.y) / extent
        let left = min(a, b)
        let right = max(a, b)
        guard left.isFinite, right.isFinite, right > left else { return nil }
        return (axis, left, right)
    }

    /// The middle half of a bracket, as an index range on the walk axis.
    ///
    /// FIELD REPORT 13 — with the bracket held perfectly still on a trunk,
    /// the diameter jumped by several inches at random. The bracket's z is a
    /// median, the chord identity d = w·z/(f − w/2) is LINEAR in z, and the
    /// median was taken over the WHOLE span. The cruiser puts the handles ON
    /// the silhouette edges, so the span's end pixels sit on the boundary and
    /// routinely return the background instead of the stem — several metres
    /// further away in a stand. Whenever the valid samples split near evenly
    /// between stem and background, one pixel dropping in or out of validity
    /// moves the median from one cluster to the other, and the diameter moves
    /// with it in exact proportion: a 2 m stem behind a 6 m gap triples.
    ///
    /// The middle half cannot be background if the bracket is on a trunk at
    /// all — that is what placing the handles on the edges MEANS — so the
    /// median is taken from stem pixels only and the bimodal hop is gone.
    ///
    /// A short temporal median over consecutive frames was the alternative
    /// and is the wrong tool twice over: it slows a hop it can't remove (the
    /// distribution is bimodal in SPACE, and the wrong mode persists for as
    /// long as the cruiser holds still), and ADJUST deliberately publishes
    /// the raw per-frame fit so the number tracks a handle drag immediately.
    ///
    /// Nothing about the geometry changes — same identity, same span, same
    /// focal. Only which pixels the depth is read from.
    ///
    /// The measured VALUE does move, though, and whoever pools this study's
    /// corpora needs to know it: a stem's centre is up to one radius nearer
    /// than its edges, so a median over the middle half reads a smaller z
    /// and every bracketed diameter comes out slightly lower than before.
    /// That is the geometrically right input for the identity — but it is
    /// not backwards-compatible. A project whose `dbhCorrectionAlpha` /
    /// `dbhCorrectionBeta` were fitted on ADJUST captures from before this
    /// change now carries that bias into the correction applied on top, and
    /// a raw-capture bundle recorded before it will not replay to the live
    /// value in its manifest. The manifest's `app_commit` is what separates
    /// the two corpora.
    static func bracketCoreRange(iLo: Int, iHi: Int) -> (Int, Int) {
        let span = iHi - iLo
        // Too few pixels to trim and still make a median of: a bracket this
        // narrow is a handful of samples either way, and dropping to one or
        // two would fail the ≥ 3 gate on a fit that is otherwise fine.
        guard span >= 8 else { return (iLo, iHi) }
        let quarter = span / 4
        return (iLo + quarter, iHi - quarter)
    }

    /// The bracket's index arithmetic on the walk axis, lifted verbatim out of
    /// `bracketChordFit` so the read-only probe below can ask about EXACTLY
    /// the pixels the fit medians — not about pixels derived by a second copy
    /// of the same arithmetic that is free to drift away from it.
    ///
    /// Pure index math: no depth is read here, and all three refusals are the
    /// fit's own (axis in bounds, span at least 2 px, a non-empty index range).
    static func bracketGeometry(
        frame: ARDepthFrame,
        guideAxis: GuideAxis,
        leftFraction: Double,
        rightFraction: Double
    ) -> (lo: Double, hi: Double, widthPx: Double, iLo: Int, iHi: Int)? {
        let extent: Int
        switch guideAxis {
        case .row(let y):
            guard y >= 0, y < frame.height else { return nil }
            extent = frame.width
        case .col(let x):
            guard x >= 0, x < frame.width else { return nil }
            extent = frame.height
        }
        let lo = min(leftFraction, rightFraction)
        let hi = max(leftFraction, rightFraction)
        let leftPx = lo * Double(extent)
        let rightPx = hi * Double(extent)
        let widthPx = rightPx - leftPx
        guard widthPx >= 2 else { return nil }
        let iLo = max(0, Int(leftPx.rounded(.up)))
        let iHi = min(extent - 1, Int(rightPx.rounded(.down)))
        guard iHi >= iLo else { return nil }
        return (lo, hi, widthPx, iLo, iHi)
    }

    /// The valid depths over the bracket's middle half, sorted ascending —
    /// the exact sample `bracketChordFit` takes its median from. Also lifted
    /// verbatim, for the same reason as `bracketGeometry`.
    static func bracketCoreDepthsSorted(
        frame: ARDepthFrame,
        guideAxis: GuideAxis,
        iLo: Int,
        iHi: Int
    ) -> [Float] {
        var depths: [Float] = []
        depths.reserveCapacity(iHi - iLo + 1)
        let (cLo, cHi) = bracketCoreRange(iLo: iLo, iHi: iHi)
        for idx in cLo...cHi {
            let (px, py) = pixelCoords(axis: guideAxis, idx: idx)
            guard frame.confidence(atX: px, y: py) >= 1 else { continue }
            let d = frame.depth(atX: px, y: py)
            if d > 0 { depths.append(d) }
        }
        depths.sort()
        return depths
    }

    /// What the estimators compute, as a number that changes when they do.
    ///
    /// Stamped into every raw-capture bundle by `RawCaptureStore.appCommit`
    /// and into every project's calibration by `CylinderCalibration`. Bump it
    /// for ANY change to a published diameter — a new identity, a different
    /// depth statistic, a different sample. Two corpora that disagree on this
    /// number must not be pooled, and a calibration fitted under one epoch
    /// must not be applied under another.
    ///
    ///   1  chord identity, full-span depth median
    ///   2  chord identity, middle-half depth median (`bracketCoreRange`)
    ///   3  cylinder-tangent inversion, middle-half median corrected to the
    ///      near face — `silhouetteDiameterCm`
    /// 4 — the auto path joined the bracket on the tangent form, and the
    /// guide strip stopped truncating at 0.15 m.
    /// 5 — Android's ADJUST bracket stopped keeping two arithmetics behind one
    ///     pair of handles. iOS reads the same as it did at 4; the number is
    ///     shared because the epoch describes a GENERATION of the estimators
    ///     and two corpora that disagree on it must not be pooled — and this
    ///     study pools iOS and Android. Epoch 5 is the first generation in
    ///     which both handsets read a bracket the same way.
    ///
    /// THE NUMBER IS THE CONTRACT, and it was nearly broken. Epoch 3 shipped
    /// on 7/30 with the BRACKET on the tangent inversion and the auto path
    /// still on the chord-at-axis form. Changing the auto path and the walk's
    /// depth budget alters an auto diameter by up to a fifth on a large stem
    /// (traced: -4.4 % at 40 cm, -21.4 % at 90 cm against a cylinder), and
    /// leaving the number at 3 would have meant two geometries under one
    /// label — with `DBHEpochRecompute` then refusing, as "already at epoch
    /// 3", precisely the rows that needed re-deriving.
    ///
    /// Bump this whenever a change moves a stored diameter. It is what the
    /// recompute decides on and what an analysis splits the corpus by.
    public static let estimatorEpoch = 5

    /// How far the guide-strip walk lets the surface recede before it calls
    /// the stem finished, in metres.
    ///
    /// THIS IS A RADIUS BUDGET, not a noise tolerance, and it was set as
    /// though it were one. On a round stem the surface at the silhouette sits
    /// exactly R behind the near face, so a walk that stops at 0.15 m stops
    /// short of the tangent points on anything over 30 cm — and stops further
    /// short the bigger the tree. Traced against a perfect cylinder, the auto
    /// path read -0.9 % at 20 cm, -4.4 % at 40, -11.0 % at 60 and -21.4 % at
    /// 90: not a bias, a taper.
    ///
    /// 0.80 m covers a 160 cm stem, which is past anything this app will meet
    /// in a coastal-PNW cruise. What stops the walk running onto the next
    /// trunk is NOT this number — it is `depthDiscontinuityM`, a 4 cm jump
    /// between ADJACENT pixels, which is untouched and does that job on its
    /// own. The remaining ~1 % under-read is the last pixel or two before the
    /// tangent, where the surface turns faster than the grid can follow, and
    /// it is the same at every size.
    static let guideStripDepthBudgetM: Float = 0.80

    /// A stem's diameter from the width of its silhouette.
    ///
    /// THE EDGES ARE TANGENT POINTS, NOT THE ENDS OF A DIAMETER. Sight lines
    /// to the left and right of a round stem graze the bark; they touch it
    /// nearer the camera than the axis and closer together than the full
    /// width. Writing that out, with `theta` the half-angle the stem
    /// subtends,
    ///
    ///     w/2 = f·tan(theta)                    the edges, in pixels
    ///     R   = (z + R)·sin(theta)              tangency, z to the near face
    ///
    /// and eliminating theta with `k = w/2f = tan(theta)` gives
    ///
    ///     d = 2·z·k·(k + sqrt(k² + 1))
    ///
    /// The identity this replaces, `d = w·z/(f − w/2)`, is the same expression
    /// with `sin` swapped for `tan`. Rearranged it reads `d = w·(z + d/2)/f`
    /// — a segment of length d at the depth of the stem's AXIS — so it was
    /// answering a different question from the one the bracket asks. It
    /// agrees to second order and over-reads for every real k, by about
    /// k²/2: a tenth of a percent on a distant sapling, 2.5 % on a stem
    /// filling a fifth of the frame.
    ///
    /// z ARRIVES A LITTLE TOO FAR AWAY. The caller's depth is a median over
    /// the bracket's middle half, and those pixels lie on a curved face, so
    /// the median sits behind the near point the tangent form wants — by
    /// `(1 − sqrt(15)/4)·R = 0.0318·R` for a uniform sample across the middle
    /// half. (Over the FULL span it would be `1 − sqrt(3)/2 = 0.134·R`; the
    /// trim already removed most of it, which is why the correction here is
    /// small.) R is what we are solving for, so substitute and solve rather
    /// than iterate:
    ///
    ///     R = z_median·K / (1 + 0.0318·K),   K = k·(k + sqrt(k² + 1))
    ///
    /// WHAT THIS DOES NOT FIX. Measured against tape on 100 stems it removes
    /// about a third of the over-read — iOS +4.8 % to +2.1 %, Android +9.1 %
    /// to +5.6 %, RMSE down 14 % and 12 %, and every diameter class improves
    /// on both platforms. The rest of the bias is unexplained. Both this form
    /// and the old one also assume the stem sits on the optical axis; off to
    /// one side by psi the bracket spans about `sec²(psi)` too much, which at
    /// 20° off-centre is larger than everything above.
    ///
    /// Returns nil when the geometry cannot produce a diameter at all.
    public static func silhouetteDiameterCm(
        spanPx: Double,
        depthM: Double,
        focalPx: Double
    ) -> Double? {
        guard spanPx > 0, depthM > 0, focalPx > 1 else { return nil }
        let k = spanPx / (2.0 * focalPx)
        let kk = k * (k + (k * k + 1).squareRoot())
        let radiusM = depthM * kk / (1.0 + medianDepthOffsetFactor * kk)
        guard radiusM.isFinite, radiusM > 0 else { return nil }
        return 2.0 * radiusM * 100.0
    }

    /// How far behind the near face the middle-half depth median sits, as a
    /// fraction of the radius: `1 − sqrt(15)/4`. Derivation in
    /// `silhouetteDiameterCm`. Exact in the orthographic limit; the
    /// perspective correction is first order in k and worth ~0.003 R here.
    static let medianDepthOffsetFactor = 1.0 - (15.0.squareRoot() / 4.0)

    /// Widest depth separation, in metres, that a bracket sitting entirely on
    /// bark can produce across its middle half.
    ///
    /// GEOMETRY, not a tuned number. Over the middle half of a chord the stem
    /// surface recedes from its nearest point by r·(1 − cos30°) = 0.134·r, so
    /// even a 1 m stem contributes under 7 cm, and LiDAR noise at bracket range
    /// adds a centimetre or two. 0.20 m is therefore several times anything
    /// bark alone can produce, while the excursions this exists to name are
    /// 30–60 cm of depth — the gap between a stem and whatever stands behind
    /// it. Same value on Android.
    public static let bracketCoreDepthSpreadLimitM: Double = 0.20

    /// Interquartile depth spread over the bracket's middle half, in metres —
    /// READ-ONLY, and never consulted by any estimator.
    ///
    /// FIELD ROUND 10 — THE DIAMETER THAT JUMPS BY INCHES. Quantified over 140
    /// bursts: the median within-burst spread is 0.49 cm on iOS and 0.41 cm on
    /// Android, the same, but iOS carries a tail Android does not — 7 of 68
    /// ADJUST bursts spanning two inches or more, worst case 26 cm. d = w·z/(f
    /// − w/2) is LINEAR in z, so 26 cm of diameter on a 30 cm stem is z moving
    /// by most of a metre. Nothing in a LiDAR return moves that far; a metre is
    /// the distance from the bark to what is behind it. Some of the sample is
    /// not bark.
    ///
    /// `bracketCoreRange` already removed the worst of it — the handles sit ON
    /// the silhouette, so the span's END pixels read the background — but the
    /// middle half is not immune: a gap between stems, a limb, or foliage seen
    /// through a lean puts a far cluster under the middle of the bracket too.
    /// The median only MOVES when that cluster reaches half the sample, which
    /// is why the failure is intermittent (about one capture in ten) rather
    /// than a constant bias.
    ///
    /// THE INTERQUARTILE RANGE IS THE RIGHT STATISTIC, precisely because the
    /// median is what has to be defended. A handful of stray far pixels cannot
    /// move a median of a dozen and does not widen the IQR either. A sample
    /// split near evenly between two surfaces moves the median on the next
    /// pixel that flips validity — and puts the two clusters on opposite sides
    /// of the quartiles, which is exactly what a wide IQR reports. min–max
    /// would fire on the single stray and blank a readout that was fine.
    ///
    /// WHAT THIS DELIBERATELY DOES NOT DO is change which pixels the estimator
    /// admits. That was the better physical fix and it is not available: the
    /// stored diameter is the median of five frames, `bracketChordFit` is the
    /// per-frame fit BOTH the live readout and the burst call, and the
    /// estimator is frozen. It is also not needed — the excursions do not
    /// reach the stored value (rho = −0.11 between burst spread and error
    /// against tape), so the corpus is intact and the defect is entirely in
    /// what the cruiser sees. This reports; the screen decides.
    public static func bracketCoreDepthSpreadM(
        frame: ARDepthFrame,
        guideAxis: GuideAxis,
        leftFraction: Double,
        rightFraction: Double
    ) -> Double? {
        guard let g = bracketGeometry(frame: frame,
                                      guideAxis: guideAxis,
                                      leftFraction: leftFraction,
                                      rightFraction: rightFraction)
        else { return nil }
        let depths = bracketCoreDepthsSorted(frame: frame,
                                             guideAxis: guideAxis,
                                             iLo: g.iLo, iHi: g.iHi)
        // Same floor the fit uses: below it there is no median worth
        // describing, and the fit has already refused.
        guard depths.count >= 3 else { return nil }
        let q1 = depths[depths.count / 4]
        let q3 = depths[(depths.count * 3) / 4]
        return Double(q3 - q1)
    }

    /// Single-frame DBH estimate constrained by two user-placed edge
    /// handles instead of the automatic silhouette walk — the DBH
    /// ADJUST mode's estimator. `leftFraction` / `rightFraction` are
    /// handle positions normalised 0…1 along the walked axis (the same
    /// normalisation `PreviewFit.stripLeftFraction` uses, so on-screen
    /// handles and the published chord agree by construction: the
    /// screen's view-x fraction maps 1:1 onto the depth map's walk-axis
    /// extent, exactly like the fit-chord overlay in reverse).
    ///
    ///     w = handle span in walk-axis pixels
    ///     z = median valid depth over the bracket's MIDDLE HALF at the
    ///         guide row (see `bracketCoreRange` — the ends sit on the
    ///         silhouette edge and read the background)
    ///     d = w·z / (f_axis − w/2)
    ///
    /// — the same axis-matched pinhole identity the auto chord path
    /// uses, with the user's bracket supplying the width instead of the
    /// silhouette edge-finder.
    public static func bracketChordFit(
        frame: ARDepthFrame,
        guideAxis: GuideAxis,
        leftFraction: Double,
        rightFraction: Double
    ) -> PreviewFit? {
        guard let g = bracketGeometry(frame: frame,
                                      guideAxis: guideAxis,
                                      leftFraction: leftFraction,
                                      rightFraction: rightFraction)
        else { return nil }
        let lo = g.lo, hi = g.hi
        let widthPx = g.widthPx
        let iLo = g.iLo, iHi = g.iHi

        // Median depth inside the bracket's CENTRAL PORTION at the guide row.
        // Zero-depth / low-confidence pixels are skipped; a handful of valid
        // returns is required before the median is trusted.
        let depths = bracketCoreDepthsSorted(frame: frame,
                                             guideAxis: guideAxis,
                                             iLo: iLo, iHi: iHi)
        guard depths.count >= 3 else { return nil }
        let z = Double(depths[depths.count / 2])
        guard (0.3...5.0).contains(z) else { return nil }

        // fx on BOTH axes, which is what the version the field verified
        // used. Switching a column walk to fy is defensible on paper and is
        // NOT being done here: this path is being restored to the code that
        // measured correctly in the stand, and every change to it made from
        // reasoning alone has been wrong. Revisit with a device and a tape,
        // not from a reading of the geometry.
        //
        // KNOWN CROSS-PLATFORM DIVERGENCE, recorded so the accuracy study
        // does not discover it in the pooled data: Android's bracket path
        // (DbhEstimator.bracketChordFit / constrainedEstimate) divides by
        // the axis-matched focal — fy for a column walk — and its live
        // ADJUST readout is calibrated, where this one publishes the raw
        // bracket diameter. Two phones on the same tree therefore show
        // different live numbers. Settling which is right is a device-and-
        // tape job on both platforms at once, not a keyboard edit to one.
        let fx = Double(frame.intrinsics[0, 0])
        guard fx - widthPx / 2.0 > 1.0 else { return nil }
        guard let diameterCm = silhouetteDiameterCm(
            spanPx: widthPx, depthM: z, focalPx: fx) else { return nil }
        let diameterM = diameterCm / 100.0
        guard plausibleDiameterCm.contains(diameterCm) else { return nil }

        // Centre for the cylinder overlay + distance HUD — bracket
        // midpoint back-projected at its depth, pushed one radius behind
        // the front surface (same construction as the auto chord path).
        let midIdx = (iLo + iHi) / 2
        let (mpx, mpy) = pixelCoords(axis: guideAxis, idx: midIdx)
        let pixDepth = Double(frame.depth(atX: mpx, y: mpy))
        let depthForBackProject = pixDepth > 0 ? pixDepth : z
        let surfaceXZ = BackProjection.worldXZ(
            x: Double(mpx), y: Double(mpy),
            depth: depthForBackProject,
            intrinsics: frame.intrinsics,
            cameraPoseWorld: frame.cameraPoseWorld)
        let cam = frame.cameraPoseWorld.columns.3
        let camXZ = SIMD2<Double>(Double(cam.x), Double(cam.z))
        let toSurface = surfaceXZ - camXZ
        let dist = (toSurface.x * toSurface.x + toSurface.y * toSurface.y).squareRoot()
        let unit: SIMD2<Double> = dist > 1e-6
            ? SIMD2(toSurface.x / dist, toSurface.y / dist)
            : SIMD2(0, 1)
        let radiusM = diameterM / 2.0
        let center = surfaceXZ + SIMD2(unit.x * radiusM, unit.y * radiusM)

        // Tier: yellow for a single manual frame — the user vouches for
        // the edges, so it's capturable, but only the burst's frame-to-
        // frame agreement can promote a manual reading to green.
        return PreviewFit(
            diameterCm: diameterCm,
            centerWorldXZ: center,
            radiusM: radiusM,
            stripLeftFraction: lo,
            stripRightFraction: hi,
            tier: .yellow,
            inlierCount: Int(widthPx.rounded()),
            arcDeg: 0,
            rmseMm: 0,
            rejectionReason: nil,
            effectiveTapDepth: z)
    }


    public static func bracketChordEstimate(
        frames: [ARDepthFrame],
        guideAxis: GuideAxis,
        leftFraction: Double,
        rightFraction: Double,
        calibration cal: ProjectCalibration
    ) -> DBHResult? {
        guard frames.count >= 5 else { return nil }

        var diameters: [Double] = []
        var centersX: [Double] = []
        var centersZ: [Double] = []
        var widths: [Int] = []
        for frame in frames {
            guard let fit = bracketChordFit(
                frame: frame,
                guideAxis: guideAxis,
                leftFraction: leftFraction,
                rightFraction: rightFraction)
            else { continue }
            diameters.append(fit.diameterCm)
            centersX.append(fit.centerWorldXZ.x)
            centersZ.append(fit.centerWorldXZ.y)
            widths.append(fit.inlierCount)
        }

        guard diameters.count >= TierThresholds.minUsableFrames else {
            return redResult(
                reason: "Not enough usable frames; hold steadier or move closer",
                method: .lidarChordSilhouette,
                nInliers: diameters.count)
        }

        let sortedDia = diameters.sorted()
        let medianRawCm = sortedDia[sortedDia.count / 2]
        let mean = sortedDia.reduce(0, +) / Double(sortedDia.count)
        let lo = sortedDia.first ?? mean
        let hi = sortedDia.last ?? mean
        let cov = mean > 0 ? (hi - lo) / mean : 1
        let tier: ConfidenceTier = cov <= TierThresholds.frameSpreadGreen ? .green : .yellow

        let dbhCm = cal.appliedToRawCm(medianRawCm)

        let sortedCx = centersX.sorted()
        let sortedCz = centersZ.sorted()
        let medCenter = SIMD2<Float>(
            Float(sortedCx[sortedCx.count / 2]),
            Float(sortedCz[sortedCz.count / 2]))

        return DBHResult(
            diameterCm: Float(dbhCm),
            centerXZ: medCenter,
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: 0,
            nInliers: widths.reduce(0, +),
            confidence: tier,
            method: .lidarChordSilhouette,
            rawPointsPath: nil,
            rejectionReason: nil)
    }

    // MARK: - Multi-sample aggregation (trimmed mean)

    /// Combine the hold-steady capture's repeated sub-measurements into one
    /// result: keep the 3 samples closest to the median diameter (with 5
    /// samples that trims the 2 largest deviations) and average them. Red
    /// sub-samples are excluded up front — they represent "couldn't
    /// measure", not a value. Mirrors Android DbhEstimator.aggregateSamples
    /// so the two platforms record identical statistics.
    public static func aggregateSamples(_ samples: [DBHResult]) -> DBHResult? {
        let valid = samples.filter { $0.confidence != .red }
        guard valid.count >= 3 else { return nil }

        let sorted = valid.map { Double($0.diameterCm) }.sorted()
        let median = sorted[sorted.count / 2]
        let kept = Array(valid
            .sorted { abs(Double($0.diameterCm) - median) < abs(Double($1.diameterCm) - median) }
            .prefix(3))

        let dias = kept.map { Double($0.diameterCm) }
        let meanDia = dias.reduce(0, +) / Double(dias.count)
        // Scatter of the kept samples → standard error of the mean, folded
        // into σ_R (radius, mm) on top of the per-sample σ so repeat-to-
        // repeat disagreement is visible in the published ±.
        let variance = dias.reduce(0.0) { $0 + ($1 - meanDia) * ($1 - meanDia) } / Double(dias.count)
        let seDiaCm = variance.squareRoot() / Double(dias.count).squareRoot()
        let seRadiusMm = seDiaCm * 10.0 / 2.0
        let meanSigma = kept.reduce(0.0) { $0 + Double($1.sigmaRmm) } / Double(kept.count)
        let sigmaRmm = (meanSigma * meanSigma + seRadiusMm * seRadiusMm).squareRoot()

        // Majority (median) tier across the kept samples — one noisy yellow
        // among greens doesn't demote the capture, two do.
        let rank: (ConfidenceTier) -> Int = { $0 == .green ? 0 : $0 == .yellow ? 1 : 2 }
        let tiers = kept.map(\.confidence).sorted { rank($0) < rank($1) }
        let tier = tiers[tiers.count / 2]

        func medianF(_ xs: [Float]) -> Float {
            let s = xs.sorted(); return s[s.count / 2]
        }
        return DBHResult(
            diameterCm: Float(meanDia),
            centerXZ: SIMD2<Float>(medianF(kept.map(\.centerXZ.x)),
                                   medianF(kept.map(\.centerXZ.y))),
            arcCoverageDeg: medianF(kept.map(\.arcCoverageDeg)),
            rmseMm: medianF(kept.map(\.rmseMm)),
            sigmaRmm: Float(sigmaRmm),
            nInliers: kept.reduce(0) { $0 + $1.nInliers },
            confidence: tier,
            method: kept[0].method,
            rawPointsPath: nil,
            rejectionReason: nil)
    }

    // MARK: - Rejection formatting

    private static func firstFailingRejectReason(_ checks: [Check]) -> String? {
        for c in checks where !c.passed && c.severity == .reject {
            return c.reason
        }
        return nil
    }

    private static func redResult(
        reason: String,
        method: DBHMethod,
        nInliers: Int = 0
    ) -> DBHResult {
        DBHResult(
            diameterCm: 0,
            centerXZ: SIMD2(0, 0),
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: 0,
            nInliers: nInliers,
            confidence: .red,
            method: method,
            rawPointsPath: nil,
            rejectionReason: reason)
    }
}
