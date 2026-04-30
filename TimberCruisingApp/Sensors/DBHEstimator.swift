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
        vioDriftFraction: Float = 0.02,
        depthDiscontinuityM: Float = 0.04
    ) {
        self.depthNoiseMm = depthNoiseMm
        self.dbhCorrectionAlpha = dbhCorrectionAlpha
        self.dbhCorrectionBeta = dbhCorrectionBeta
        self.vioDriftFraction = vioDriftFraction
        self.depthDiscontinuityM = depthDiscontinuityM
    }

    /// Neutral calibration — pre-calibration projects start here.
    public static let identity = ProjectCalibration(
        depthNoiseMm: 5.0,
        dbhCorrectionAlpha: 0,
        dbhCorrectionBeta: 1,
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

    public init(
        frames: [ARDepthFrame],
        tapPixel: SIMD2<Double>,
        guideAxis: GuideAxis,
        projectCalibration: ProjectCalibration,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil
    ) {
        self.frames = frames
        self.tapPixel = tapPixel
        self.guideAxis = guideAxis
        self.projectCalibration = projectCalibration
        self.rawPointsWriter = rawPointsWriter
    }
}

// MARK: - Estimator

public enum DBHEstimator {

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
                reason: "Tap pixel outside depth map",
                method: .lidarPartialArcSingleView)
        }
        guard (0.5...3.0).contains(dTap) else {
            return redResult(
                reason: "Move closer or step back; tap depth " +
                        "\(String(format: "%.2f", dTap)) m out of range",
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
                deltaDepth: 0.15,
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
            return redResult(
                reason: "Too few points after outlier removal",
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
            return redResult(
                reason: "Could not fit a circle",
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
        let checks: [Check] = [
            check(fit.inliers.count >= 20, sev: .reject,
                  reason: "Fewer than 20 trunk surface points"),
            check(fit.inliers.count >= 30, sev: .warn,
                  reason: "Only 20–30 trunk surface points"),
            check(arcDeg >= 45, sev: .reject,
                  reason: "Trunk arc coverage below 45°"),
            check(arcDeg >= 60, sev: .warn,
                  reason: "Trunk arc coverage 45°–60°"),
            check(r >= 0.025 && r <= 1.0, sev: .reject,
                  reason: "Fitted radius outside 2.5–100 cm"),
            check(rmse / r <= 0.05, sev: .reject,
                  reason: "Fit error worse than 5% of radius"),
            check(rmse / r <= 0.03, sev: .warn,
                  reason: "Fit error 3–5% of radius"),
            check(sigmaR / r <= 0.05, sev: .reject,
                  reason: "Radius precision worse than ±5%"),
            check(sigmaR / r <= 0.02, sev: .warn,
                  reason: "Radius precision ±2–5%"),
            check(radiusCoV <= 0.10, sev: .reject,
                  reason: "Per-frame radius spread above 10%"),
            check(radiusCoV <= 0.05, sev: .warn,
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
                ?? "Quality below threshold"
        } else {
            rejectionReason = nil
        }

        // Step 10: apply cylinder calibration.
        let dbhRawCm = 2 * r * 100
        let dbhCm = Double(cal.dbhCorrectionAlpha)
            + Double(cal.dbhCorrectionBeta) * dbhRawCm

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
                deltaDepth: 0.15,
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

        public init(diameterCm: Double,
                    centerWorldXZ: SIMD2<Double>,
                    radiusM: Double,
                    stripLeftFraction: Double,
                    stripRightFraction: Double,
                    tier: ConfidenceTier,
                    inlierCount: Int,
                    arcDeg: Double,
                    rmseMm: Double,
                    rejectionReason: String?) {
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
        discontinuityThresholdM: Float = 0.04
    ) -> PreviewFit? {
        guard let dTap = medianDepth(around: tapPixel, frame: frame, radius: 2)
        else { return nil }
        guard (0.5...3.0).contains(dTap) else { return nil }

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
            check(!ransacFailed, sev: .reject,
                  reason: "Couldn't fit a trunk circle — move closer or steadier"),
            check(inlierCount >= 12, sev: .reject,
                  reason: "Fewer than 12 trunk surface points"),
            check(inlierCount >= 20, sev: .warn,
                  reason: "Only 12–20 trunk surface points"),
            check(arcDeg >= 45, sev: .reject,
                  reason: "Trunk arc coverage below 45°"),
            check(arcDeg >= 60, sev: .warn,
                  reason: "Trunk arc coverage 45°–60°"),
            check(radiusM_ >= 0.025 && radiusM_ <= 1.0, sev: .reject,
                  reason: "Fitted radius outside 2.5–100 cm"),
            check(rmseRatio <= 0.05, sev: .reject,
                  reason: "Fit error worse than 5% of radius"),
            check(rmseRatio <= 0.03, sev: .warn,
                  reason: "Fit error 3–5% of radius"),
            check(!chordOverride, sev: .warn,
                  reason: "Fit disagreed with silhouette; using chord")
        ]
        let tier = combineChecks(checks)
        let rejectionReason: String? = (tier == .red)
            ? (firstFailingRejectReason(checks) ?? "Quality below threshold")
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
            rejectionReason: rejectionReason)
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
