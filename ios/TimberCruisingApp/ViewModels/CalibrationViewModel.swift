// Spec §7.10 calibration procedures — view model for the Wall and
// Cylinder procedures. The view model holds user-facing state only; the
// actual math lives in Sensors/LiDARCalibration. On macOS the ARKit
// session is a no-op stub; tests drive state via the `preview` factory.

import Foundation
import Combine
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
    @Published public var newMeasuredCm: String = ""
    @Published public var newTrueCm: String = ""

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
        updated.updatedAt = Date()
        return updated
    }

    // MARK: - Cylinder procedure

    public func addCylinderSample() {
        guard let measured = Double(newMeasuredCm),
              let trueV    = Double(newTrueCm),
              measured > 0, trueV > 0
        else { return }
        var samples = currentCylinderSamples
        samples.append(.init(dbhMeasuredCm: measured, dbhTrueCm: trueV))
        cylinder = .collecting(samples: samples)
        newMeasuredCm = ""
        newTrueCm = ""
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
        newMeasuredCm = ""
        newTrueCm = ""
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
        case WallCalibration.Failure.tooFewPoints(let c, let m):
            return "Need at least \(m) points (captured \(c))."
        case CylinderCalibration.Failure.tooFewSamples(let c, let m):
            return "Need at least \(m) samples (collected \(c))."
        case CylinderCalibration.Failure.degenerateX:
            return "All diameters were identical — vary the target sizes."
        default:
            return "\(err)"
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
