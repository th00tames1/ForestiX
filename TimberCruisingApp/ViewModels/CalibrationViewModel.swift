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

    public func resetWall() { wall = .idle }

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
