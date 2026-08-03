// Spec §7.10 calibration procedures — view model for the Wall and
// Cylinder procedures. The view model holds user-facing state only; the
// actual math lives in Sensors/LiDARCalibration. On macOS the ARKit
// session is a no-op stub; tests drive state via the `preview` factory.

import Foundation
import Combine
import Common
import Models
import Sensors

@MainActor
public final class CalibrationViewModel: ObservableObject {

    public enum Procedure: Equatable, Sendable {
        case wall
        case cylinder
    }

    public enum WallState: Equatable, Sendable {
        case idle
        case scanning(progress: Double)     // 0…1 (frames captured / 30)
        case computed(WallCalibrationResult)
        case failed(String)
    }

    public enum CylinderState: Equatable, Sendable {
        case idle
        case collecting(samples: [CylinderCalibration.Sample])
        case computed(CylinderCalibrationResult, samples: [CylinderCalibration.Sample])
        case failed(String)
    }

    @Published public private(set) var wall: WallState = .idle
    @Published public private(set) var cylinder: CylinderState = .idle
    /// The two round-post widths as TYPED — in whatever unit the screen's
    /// boxes are labelled with, which is the cruiser's, not necessarily
    /// centimetres. `addCylinderSample(unit:)` is the only reader and it
    /// converts. The stored `Sample` stays cm, because the fitted α is added
    /// in centimetres to every diameter this phone measures
    /// (`DBHEstimator`), so an inch-scale α would bias every tree.
    @Published public var newMeasuredText: String = ""
    @Published public var newTrueText: String = ""

    public let session: ARKitSessionManager
    private var depthSubscription: AnyCancellable?
    private var collectedPoints: [SIMD3<Double>] = []
    private let targetWallFrames = 30

    public init(session: ARKitSessionManager? = nil) {
        self.session = session ?? ARKitSessionManager()
    }

    // MARK: - Wall procedure

    /// Apply a final point set. Real iOS wiring collects points over 30
    /// frames via ARKit; this entry point lets tests inject the result
    /// directly and lets the live UI forward a prebuilt buffer.
    public func finishWallScan(points: [SIMD3<Double>]) {
        switch WallCalibration.fit(points: points) {
        case .success(let r):
            wall = .computed(r)
        case .failure(let err):
            wall = .failed(describe(err))
        }
    }

    /// Begin live wall-scan collection. Subscribes to the ARKit depth
    /// frame stream, back-projects each frame's centre 21×21 patch into
    /// world space, accumulates 30 frames, then runs `WallCalibration.fit`.
    /// On macOS the session is a no-op stub and this is a no-op.
    public func startWallScan() {
        guard case .idle = wall else { return }
        wall = .scanning(progress: 0)
        collectedPoints = []
        session.run()
        depthSubscription = session.$latestDepthFrame
            .compactMap { $0 }
            .sink { [weak self] frame in
                guard let self else { return }
                self.appendPatch(from: frame)
            }
    }

    public func cancelWallScan() {
        depthSubscription?.cancel()
        depthSubscription = nil
        collectedPoints = []
        wall = .idle
        session.pause()
    }

    private func appendPatch(from frame: ARDepthFrame) {
        // Back-project a 21x21 patch from the depth-map center into
        // world space. Filter NaN / zero.
        let cx = frame.width / 2
        let cy = frame.height / 2
        let half = 10
        let cameraToWorld = frame.cameraPoseWorld
        for dy in -half...half {
            for dx in -half...half {
                let x = cx + dx, y = cy + dy
                guard x >= 0, x < frame.width,
                      y >= 0, y < frame.height else { continue }
                let d = frame.depth(atX: x, y: y)
                guard d.isFinite, d > 0.1, d < 5.0 else { continue }
                // Pinhole back-projection.
                let fx = frame.intrinsics.columns.0.x
                let fy = frame.intrinsics.columns.1.y
                let px = frame.intrinsics.columns.2.x
                let py = frame.intrinsics.columns.2.y
                let xCam = (Float(x) - px) * d / fx
                let yCam = (Float(y) - py) * d / fy
                let pCam = SIMD4<Float>(xCam, yCam, -d, 1)
                // simd_float4x4 column-major × column-vector: hand-roll
                // because the * operator's overload set differs across
                // SDK versions and isn't available as `simd_mul` either.
                let c0 = cameraToWorld.columns.0 * pCam.x
                let c1 = cameraToWorld.columns.1 * pCam.y
                let c2 = cameraToWorld.columns.2 * pCam.z
                let c3 = cameraToWorld.columns.3 * pCam.w
                let pWorld = c0 + c1 + c2 + c3
                collectedPoints.append(SIMD3<Double>(
                    Double(pWorld.x), Double(pWorld.y), Double(pWorld.z)))
            }
        }

        let frames = collectedPoints.count / 441   // 21*21
        let progress = min(1.0, Double(frames) / Double(targetWallFrames))
        wall = .scanning(progress: progress)

        if frames >= targetWallFrames {
            depthSubscription?.cancel()
            depthSubscription = nil
            session.pause()
            finishWallScan(points: collectedPoints)
        }
    }

    public func resetWall() {
        depthSubscription?.cancel()
        depthSubscription = nil
        collectedPoints = []
        wall = .idle
    }

    // MARK: - Apply to project

    /// Write the (wall, cylinder) results back into a Project struct,
    /// returning a fresh Project. The caller is responsible for
    /// persisting via the ProjectRepository.
    public func applyTo(project: Project) -> Project {
        var updated = project
        if case .computed(let w) = wall {
            updated.depthNoiseMm = Float(w.depthNoiseMm)
            updated.lidarBiasMm = Float(w.depthBiasMm)
        }
        if case .computed(let c, _) = cylinder {
            updated.dbhCorrectionAlpha = Float(c.alpha)
            updated.dbhCorrectionBeta = Float(c.beta)
            // Stamp WHAT was calibrated, not just the result. Without this the
            // coefficients outlive the estimator they correct and stack with
            // its successor's own correction.
            updated.dbhCalibrationEpoch = DBHEstimator.estimatorEpoch
        }
        updated.updatedAt = Date()
        return updated
    }

    /// Apply spec §7.10 identity / sensible defaults without scanning.
    /// Lets a cruiser get into the field on a freshly installed phone
    /// without standing in front of a wall first; the values match the
    /// nominal iPhone LiDAR datasheet noise (5 mm) and an identity DBH
    /// correction (α = 0, β = 1).
    public static func sensibleDefaultsApplied(to project: Project) -> Project {
        var updated = project
        updated.depthNoiseMm = 5
        updated.lidarBiasMm = 0
        updated.dbhCorrectionAlpha = 0
        updated.dbhCorrectionBeta = 1
        updated.dbhCalibrationEpoch = 0
        updated.updatedAt = Date()
        return updated
    }

    // MARK: - Cylinder procedure

    /// `unit` is the unit the two boxes are LABELLED in — cm or inches. Both
    /// widths are converted to the stored centimetre base here, in one place,
    /// because the fit's offset α is applied in centimetres to every diameter
    /// the phone measures for the project (`Sensors/DBHEstimator`): a fit
    /// taken on inch-scale numbers lands as a ~2.5× understated cm offset on
    /// every tree, and nothing downstream would say so. β is a ratio and
    /// survives an inch/inch fit either way.
    public func addCylinderSample(unit: TruthInput.Unit = .cm) {
        guard let measured = TruthInput.parsePositiveBase(newMeasuredText, unit: unit),
              let trueV    = TruthInput.parsePositiveBase(newTrueText, unit: unit)
        else { return }
        var samples = currentCylinderSamples
        samples.append(.init(dbhMeasuredCm: measured, dbhTrueCm: trueV))
        cylinder = .collecting(samples: samples)
        newMeasuredText = ""
        newTrueText = ""
    }

    public func computeCylinderCalibration() {
        let samples = currentCylinderSamples
        switch CylinderCalibration.fit(samples: samples) {
        case .success(let r):
            cylinder = .computed(r, samples: samples)
        case .failure(let err):
            cylinder = .failed(describe(err))
        }
    }

    public func resetCylinder() {
        cylinder = .idle
        newMeasuredText = ""
        newTrueText = ""
    }

    private var currentCylinderSamples: [CylinderCalibration.Sample] {
        switch cylinder {
        case .collecting(let s): return s
        case .computed(_, let s): return s
        default: return []
        }
    }

    private func describe(_ err: Error) -> String {
        switch err {
        // All three were "Need at least N …" with the shortfall in
        // brackets and no instruction. They now say what happened and what
        // to do, and the cruiser enters POSTS on this screen, not samples.
        // Every MINIMUM is the fit's own and unchanged.
        case WallCalibration.Failure.tooFewPoints(let c, let m):
            return "The scan only picked up \(c) points on the wall and it needs "
                 + "\(m). Stand closer so the wall fills the screen, then scan again."
        case CylinderCalibration.Failure.tooFewSamples(let c, let m):
            return "You have entered \(c) posts and it needs \(m). "
                 + "Measure another post and add it."
        case CylinderCalibration.Failure.degenerateX:
            return "Every post you entered is the same width. Add posts of different widths "
                 + "so the app can tell how the error changes with size."
        default:
            // A raw Swift error interpolated into the red line on the
            // Calibration screen put a type name and its associated values in
            // front of a cruiser — unreadable, and it named internals on a
            // screen that sits in the ordinary Settings group. Anything that
            // isn't one of the three cases above gets a sentence they can act
            // on instead. Nothing about the failure itself changes.
            return "The app couldn't work out a correction from that. "
                 + "Check what you entered and run the scan again."
        }
    }
}

// MARK: - Preview factories

public extension CalibrationViewModel {

    static func preview(wall: WallState, cylinder: CylinderState)
        -> CalibrationViewModel
    {
        let vm = CalibrationViewModel()
        vm.applyPreview(wall: wall, cylinder: cylinder)
        return vm
    }

    func applyPreview(wall: WallState, cylinder: CylinderState) {
        self.wall = wall
        self.cylinder = cylinder
    }
}
