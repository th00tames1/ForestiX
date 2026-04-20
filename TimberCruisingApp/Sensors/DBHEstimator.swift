// Spec §7.1 — DBH: Guide-Align Partial-Arc Circle Fit. Full 11-step
// pipeline.
//
// Orchestrates PointCloud + CircleFit + Common (confidence tier) to turn
// a burst of ARDepthFrames and a single tap into a DBHResult. Pure
// function: no ARKit, no UI, no IO (raw-PLY dump is the caller's job via
// an optional sidecar writer).
//
// The ARDepthFrame comes from Sensors/ARKitSessionManager; depth arrays
// are row-major landscape-native per §7.1 invariant "guideRowY equals
// depth.height / 2 for the capture device". The caller is responsible
// for passing tapPixel in the same coordinate system as depth pixels.

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

    public init(
        depthNoiseMm: Float,
        dbhCorrectionAlpha: Float,
        dbhCorrectionBeta: Float,
        vioDriftFraction: Float = 0.02
    ) {
        self.depthNoiseMm = depthNoiseMm
        self.dbhCorrectionAlpha = dbhCorrectionAlpha
        self.dbhCorrectionBeta = dbhCorrectionBeta
        self.vioDriftFraction = vioDriftFraction
    }

    /// Neutral calibration — pre-calibration projects start here.
    public static let identity = ProjectCalibration(
        depthNoiseMm: 5.0,
        dbhCorrectionAlpha: 0,
        dbhCorrectionBeta: 1,
        vioDriftFraction: 0.02)
}

public struct DBHScanInput: Sendable {
    public let frames: [ARDepthFrame]
    /// Image-space (x, y) in the depth map's own coordinate system.
    public let tapPixel: SIMD2<Double>
    /// Fixed horizontal guide row (depth_height / 2 per §7.1 invariant).
    public let guideRowY: Int
    public let projectCalibration: ProjectCalibration
    /// Optional sidecar that persists the cleaned point set to PLY when
    /// the caller opts in (REQ-DBH-007). Returns the file path or nil.
    public let rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)?

    public init(
        frames: [ARDepthFrame],
        tapPixel: SIMD2<Double>,
        guideRowY: Int,
        projectCalibration: ProjectCalibration,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil
    ) {
        self.frames = frames
        self.tapPixel = tapPixel
        self.guideRowY = guideRowY
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
        var combinedXZ: [SIMD2<Double>] = []
        combinedXZ.reserveCapacity(input.frames.count * 64)
        for frame in input.frames {
            let strip = extractGuideRowStemStrip(
                frame: frame,
                guideRowY: input.guideRowY,
                tapColumn: Int(input.tapPixel.x.rounded()),
                dTap: dTap,
                deltaDepth: 0.15)
            for x in strip {
                let xz = BackProjection.worldXZ(
                    x: Double(x), y: Double(input.guideRowY),
                    depth: Double(frame.depth(atX: x, y: input.guideRowY)),
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
            guideRowY: input.guideRowY,
            tapColumn: Int(input.tapPixel.x.rounded()),
            dTap: dTap)

        // Optional raw-PLY sidecar (REQ-DBH-007).
        let rawPath = input.rawPointsWriter?(cleaned)

        // Step 9: sanity tree.
        let r = fit.circle.radius
        let checks: [Check] = [
            check(fit.inliers.count >= 20, sev: .reject,
                  reason: "n_inliers < 20"),
            check(fit.inliers.count >= 30, sev: .warn,
                  reason: "n_inliers 20–30"),
            check(arcDeg >= 45, sev: .reject,
                  reason: "arc_coverage_deg < 45°"),
            check(arcDeg >= 60, sev: .warn,
                  reason: "arc_coverage_deg 45°–60°"),
            check(r >= 0.025 && r <= 1.0, sev: .reject,
                  reason: "r_fit outside [2.5 cm, 100 cm]"),
            check(rmse / r <= 0.05, sev: .reject,
                  reason: "rmse / r_fit > 5%"),
            check(rmse / r <= 0.03, sev: .warn,
                  reason: "rmse / r_fit 3–5%"),
            check(sigmaR / r <= 0.05, sev: .reject,
                  reason: "sigma_r / r_fit > 5%"),
            check(sigmaR / r <= 0.02, sev: .warn,
                  reason: "sigma_r / r_fit 2–5%"),
            check(radiusCoV <= 0.10, sev: .reject,
                  reason: "frame burst radius CoV > 10%"),
            check(radiusCoV <= 0.05, sev: .warn,
                  reason: "frame burst radius CoV 5–10%")
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

    // MARK: - Step 3: stem-strip extraction along the guide row

    /// Extracts the contiguous run of columns along `guideRowY` that
    /// (a) have confidence ≥ 1, (b) depth within ±deltaDepth of dTap, and
    /// (c) are connected to the tap column. Returns the list of column
    /// indices. Short-circuits if the tap column itself fails.
    static func extractGuideRowStemStrip(
        frame: ARDepthFrame,
        guideRowY: Int,
        tapColumn: Int,
        dTap: Float,
        deltaDepth: Float
    ) -> [Int] {
        guard guideRowY >= 0, guideRowY < frame.height else { return [] }
        let width = frame.width
        let clampedTap = max(0, min(width - 1, tapColumn))

        func pixelValid(at x: Int) -> Bool {
            let c = frame.confidence(atX: x, y: guideRowY)
            if c < 1 { return false }
            let d = frame.depth(atX: x, y: guideRowY)
            if d <= 0 { return false }
            return abs(d - dTap) < deltaDepth
        }

        // Walk from the tap column outward. If the tap column itself is
        // invalid, search the closest valid seed within a small window.
        var seed = clampedTap
        if !pixelValid(at: seed) {
            var found = -1
            for off in 1...10 {
                let l = clampedTap - off
                if l >= 0, pixelValid(at: l) { found = l; break }
                let r = clampedTap + off
                if r < width, pixelValid(at: r) { found = r; break }
            }
            if found < 0 { return [] }
            seed = found
        }

        var cols: [Int] = [seed]
        var x = seed - 1
        while x >= 0, pixelValid(at: x) { cols.append(x); x -= 1 }
        x = seed + 1
        while x < width, pixelValid(at: x) { cols.append(x); x += 1 }
        cols.sort()
        return cols
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
        guideRowY: Int,
        tapColumn: Int,
        dTap: Float
    ) -> Double {
        var radii: [Double] = []
        for frame in frames {
            let cols = extractGuideRowStemStrip(
                frame: frame,
                guideRowY: guideRowY,
                tapColumn: tapColumn,
                dTap: dTap,
                deltaDepth: 0.15)
            if cols.count < 5 { continue }
            let pts = cols.map { x -> SIMD2<Double> in
                BackProjection.worldXZ(
                    x: Double(x), y: Double(guideRowY),
                    depth: Double(frame.depth(atX: x, y: guideRowY)),
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
    public struct PreviewFit: Equatable, Sendable {
        /// Estimated trunk diameter in centimetres.
        public let diameterCm: Double
        /// Trunk centre in world XZ metres (height is set by the caller —
        /// usually the camera Y for the DBH row).
        public let centerWorldXZ: SIMD2<Double>
        /// Trunk radius in metres.
        public let radiusM: Double

        public init(diameterCm: Double,
                    centerWorldXZ: SIMD2<Double>,
                    radiusM: Double) {
            self.diameterCm = diameterCm
            self.centerWorldXZ = centerWorldXZ
            self.radiusM = radiusM
        }
    }

    /// Single-frame approximation used by the scan HUD to show a live
    /// DBH estimate near the crosshair while the cruiser is aiming.
    ///
    /// This is intentionally much cheaper than the full `estimate(input:)`
    /// pipeline:
    ///
    ///   1. Take a 5×5 median depth at `tapPixel`. If it's outside the
    ///      0.5–3.0 m scan band, no preview.
    ///   2. Walk the guide row outwards from the tap column to gather
    ///      stem pixels within ±15 cm of that depth (same strip the
    ///      burst uses).
    ///   3. Back-project the leftmost and rightmost strip pixels into
    ///      world XZ. The chord between them is the trunk diameter;
    ///      their midpoint is the trunk surface centre on the near
    ///      side. Shifting inward by one radius along the camera view
    ///      direction gives the cylinder centre.
    ///
    /// Looking roughly head-on at the trunk, the strip's left and right
    /// silhouette columns are tangent to the cylinder surface — so the
    /// chord between their back-projections is the trunk diameter as
    /// seen from the camera. Accuracy is good enough (≈ ±2 cm when
    /// aimed head-on, worse with oblique angles) to guide the cruiser;
    /// the real measurement still runs the full §7.1 pipeline on
    /// capture.
    ///
    /// Returns nil when the preview can't be trusted at all
    /// (out-of-range depth, strip too short, etc.).
    public static func previewFit(
        frame: ARDepthFrame,
        tapPixel: SIMD2<Double>,
        guideRowY: Int,
        deltaDepth: Float = 0.15
    ) -> PreviewFit? {
        guard let dTap = medianDepth(around: tapPixel, frame: frame, radius: 2)
        else { return nil }
        guard (0.5...3.0).contains(dTap) else { return nil }

        let strip = extractGuideRowStemStrip(
            frame: frame,
            guideRowY: guideRowY,
            tapColumn: Int(tapPixel.x.rounded()),
            dTap: dTap,
            deltaDepth: deltaDepth)
        guard let leftCol = strip.first, let rightCol = strip.last,
              rightCol > leftCol
        else { return nil }

        let left = BackProjection.worldXZ(
            x: Double(leftCol), y: Double(guideRowY),
            depth: Double(frame.depth(atX: leftCol, y: guideRowY)),
            intrinsics: frame.intrinsics,
            cameraPoseWorld: frame.cameraPoseWorld)
        let right = BackProjection.worldXZ(
            x: Double(rightCol), y: Double(guideRowY),
            depth: Double(frame.depth(atX: rightCol, y: guideRowY)),
            intrinsics: frame.intrinsics,
            cameraPoseWorld: frame.cameraPoseWorld)

        let dx = right.x - left.x
        let dz = right.y - left.y    // SIMD2 y-slot holds world-Z here
        let chordM = (dx * dx + dz * dz).squareRoot()
        let diameterCm = chordM * 100
        let radiusM = chordM / 2.0

        // Reject wild values so we don't flash obviously-wrong numbers
        // mid-aim. The §7.1 sanity tree does the same on the final fit.
        guard (5.0...200.0).contains(diameterCm) else { return nil }

        // Near-side midpoint between the two silhouette hits — i.e. the
        // trunk surface facing the camera.
        let nearMid = SIMD2<Double>((left.x + right.x) / 2.0,
                                     (left.y + right.y) / 2.0)
        // Shift inward (away from camera) by one radius along the
        // camera-to-surface direction to land at the cylinder axis.
        let cam = frame.cameraPoseWorld.columns.3
        let cameraXZ = SIMD2<Double>(Double(cam.x), Double(cam.z))
        let toSurface = nearMid - cameraXZ
        let dist = (toSurface.x * toSurface.x + toSurface.y * toSurface.y).squareRoot()
        let unit: SIMD2<Double> = dist > 1e-6
            ? SIMD2(toSurface.x / dist, toSurface.y / dist)
            : SIMD2(0, 1)
        let center = nearMid + SIMD2(unit.x * radiusM, unit.y * radiusM)

        return PreviewFit(
            diameterCm: diameterCm,
            centerWorldXZ: center,
            radiusM: radiusM)
    }

    /// Back-compat helper — returns just the diameter when only the
    /// scalar is wanted.
    public static func previewDiameterCm(
        frame: ARDepthFrame,
        tapPixel: SIMD2<Double>,
        guideRowY: Int,
        deltaDepth: Float = 0.15
    ) -> Double? {
        previewFit(frame: frame, tapPixel: tapPixel,
                   guideRowY: guideRowY,
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
