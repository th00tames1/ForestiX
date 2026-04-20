// Spec §4.3 DBH state machine + §5.2 screen contract. Observes
// ARKitSessionManager for depth frames, buffers a burst on user tap,
// runs DBHEstimator, and surfaces the state transitions for the view.
//
// The view model is cross-platform. On iOS it starts a real ARKit
// session via ARKitSessionManager; on macOS the same session type
// compiles to a no-op stub so previews/snapshots compile unchanged.
// Tests drive the state machine directly through the `preview` factory.

import Foundation
import Combine
import Models
import Common
import Sensors

@MainActor
public final class DBHScanViewModel: ObservableObject {

    // MARK: - §4.3 DBHScanState

    public enum State: Equatable, Sendable {
        case idle
        case aligning
        case armed
        case capturing
        case fitted
        case accepted
        case rejected
        case manualEntry
    }

    // MARK: - Published surface

    @Published public private(set) var state: State = .idle
    @Published public private(set) var result: DBHResult?
    @Published public private(set) var crosshairIsStable: Bool = false
    @Published public var manualDbhCm: String = ""
    @Published public private(set) var unsupportedBanner: String?

    // MARK: - Dependencies

    public let session: ARKitSessionManager
    public let calibration: ProjectCalibration
    public let isLiDARSupported: Bool
    public let rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)?

    // MARK: - Burst state

    private let burstSize: Int = 12
    private var burstBuffer: [ARDepthFrame] = []
    private var burstTap: SIMD2<Double> = .zero
    private var depthCancellable: AnyCancellable?

    // MARK: - Construction

    public init(
        calibration: ProjectCalibration,
        session: ARKitSessionManager? = nil,
        rawPointsWriter: (@Sendable ([SIMD2<Double>]) -> String?)? = nil
    ) {
        self.session = session ?? ARKitSessionManager()
        self.calibration = calibration
        self.rawPointsWriter = rawPointsWriter
        self.isLiDARSupported = ARKitSessionManager.supportsLiDAR
        if !isLiDARSupported {
            unsupportedBanner = "LiDAR not supported on this device. " +
                                "Use Manual Entry to record DBH."
        }
    }

    // MARK: - Lifecycle

    public func onAppear() {
        // Always start the AR session — even on non-LiDAR devices we want
        // the camera feed to render so the cruiser can see what they're
        // pointing at while entering DBH manually. `session.run()` is
        // internally guarded against unsupported configurations, so it's
        // safe to call on any device.
        session.run()
        subscribeToDepth()

        if isLiDARSupported {
            if state == .idle || state == .accepted || state == .rejected {
                state = .aligning
            }
        } else {
            // No LiDAR → caliper / tape workflow. Camera still visible
            // behind the manual-entry panel so the cruiser can frame the
            // measurement.
            state = .manualEntry
        }
    }

    public func onDisappear() {
        depthCancellable?.cancel()
        depthCancellable = nil
        session.pause()
    }

    private func subscribeToDepth() {
        depthCancellable = session.$latestDepthFrame
            .compactMap { $0 }
            .sink { [weak self] frame in
                self?.handleDepthFrame(frame)
            }
    }

    private func handleDepthFrame(_ frame: ARDepthFrame) {
        // REQ-DBH-003 crosshair transitions green when center depth
        // is stable and < 3 m.
        let cx = frame.width / 2
        let cy = frame.height / 2
        let d = frame.depth(atX: cx, y: cy)
        let c = frame.confidence(atX: cx, y: cy)
        let stable = d > 0.5 && d < 3.0 && c >= 1
        crosshairIsStable = stable
        if state == .aligning, stable { state = .armed }
        if state == .armed, !stable    { state = .aligning }
        if state == .capturing {
            burstBuffer.append(frame)
            if burstBuffer.count >= burstSize { finishCapture() }
        }
    }

    // MARK: - User actions

    /// Called on trunk-center tap. `tapPixel` is in the depth map's
    /// coordinate space (caller converts from view coords to depth
    /// coords via the ARKit displayTransform).
    public func tap(at tapPixel: SIMD2<Double>) {
        guard state == .armed else { return }
        burstBuffer.removeAll(keepingCapacity: true)
        burstTap = tapPixel
        state = .capturing
    }

    public func retake() {
        burstBuffer.removeAll()
        result = nil
        state = isLiDARSupported ? .aligning : .manualEntry
    }

    public func accept() {
        guard let r = result, r.confidence != .red else { return }
        state = .accepted
    }

    public func enterManualEntry() {
        state = .manualEntry
    }

    public func submitManualEntry() {
        guard let cm = Double(manualDbhCm), cm > 0 else { return }
        result = DBHResult(
            diameterCm: Float(cm),
            centerXZ: SIMD2(0, 0),
            arcCoverageDeg: 0,
            rmseMm: 0,
            sigmaRmm: 0,
            nInliers: 0,
            confidence: .yellow,
            method: .manualVisual,
            rawPointsPath: nil,
            rejectionReason: nil)
        state = .accepted
    }

    private func finishCapture() {
        let frames = burstBuffer
        burstBuffer.removeAll()
        guard let firstFrame = frames.first else {
            state = .rejected
            return
        }
        let guideRow = firstFrame.height / 2
        let input = DBHScanInput(
            frames: frames,
            tapPixel: burstTap,
            guideRowY: guideRow,
            projectCalibration: calibration,
            rawPointsWriter: rawPointsWriter)
        let outcome = DBHEstimator.estimate(input: input)
        result = outcome
        if let r = outcome {
            state = r.confidence == .red ? .rejected : .fitted
        } else {
            state = .rejected
        }
    }
}

// MARK: - Preview / snapshot factories

public extension DBHScanViewModel {

    /// Builds a view model in the requested state with a canned result.
    /// Used by snapshot tests and SwiftUI previews so each §4.3 state is
    /// reachable without a live ARKit session.
    static func preview(
        state: State,
        result: DBHResult? = nil,
        unsupported: Bool = false
    ) -> DBHScanViewModel {
        let vm = DBHScanViewModel(
            calibration: ProjectCalibration.identity,
            session: nil,
            rawPointsWriter: nil)
        vm.applyPreview(state: state, result: result, unsupported: unsupported)
        return vm
    }

    /// Internal test hook that forces the state and cached result. Kept
    /// separate from production transitions so nothing outside tests or
    /// previews can mutate `state` arbitrarily.
    func applyPreview(
        state: State,
        result: DBHResult?,
        unsupported: Bool
    ) {
        self.state = state
        self.result = result
        if unsupported {
            self.unsupportedBanner =
                "LiDAR not supported on this device. " +
                "Use Manual Entry to record DBH."
        }
    }
}
