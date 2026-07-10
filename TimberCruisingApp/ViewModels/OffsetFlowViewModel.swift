// Spec §4.5 + §7.3.2 Offset-from-Opening flow. REQ-CTR-002.
//
// Walks the user through the five-step recovery when GPS at the plot
// center is too weak to accept:
//   A. Stand at plot center — snapshot ARKit camera pose as `plotPose`.
//   B. Walk to an opening where sky is visible.
//   C. Run 30 s GPS averaging there → `openingFix`; snapshot
//      `openingPose`.
//   D. Walk back to plot center (ARKit must stay .normal throughout).
//   E. Confirm → OffsetFromOpening.compute to back-solve plot lat/lon.

import Foundation
import simd
import Combine
import Models
import Sensors
import Positioning

@MainActor
public final class OffsetFlowViewModel: ObservableObject {

    public enum Step: Sendable {
        case anchorPlot          // A
        case walkToOpening       // B
        case averagingAtOpening(secondsElapsed: Int, sampleCount: Int)  // C
        case walkBack(distanceFromPlotM: Float?)   // D
        case computed(PlotCenterResult)            // E
        case failed(reason: String)
    }

    public let location: LocationService
    public let session: ARKitSessionManager
    public let openingAveragingDurationS: Int

    @Published public private(set) var step: Step = .anchorPlot
    public private(set) var plotPoseWorld: SIMD3<Float>?
    public private(set) var openingPoseWorld: SIMD3<Float>?
    public private(set) var openingFix: PlotCenterResult?

    private var tickTimer: Timer?
    private var averagingStartedAt: Date?
    private var cancellables: Set<AnyCancellable> = []

    public init(
        location: LocationService,
        session: ARKitSessionManager,
        openingAveragingDurationS: Int = 30
    ) {
        self.location = location
        self.session = session
        self.openingAveragingDurationS = openingAveragingDurationS
        session.$currentCameraWorldPosition
            .sink { [weak self] pos in self?.updateWalkBackDistance(camera: pos) }
            .store(in: &cancellables)
    }

    // MARK: - Step transitions

    public func anchorPlotCenter() {
        guard let p = session.currentCameraWorldPosition else {
            step = .failed(reason: "ARKit pose unavailable — tracking not started")
            return
        }
        plotPoseWorld = p
        step = .walkToOpening
    }

    public func beginOpeningAveraging() {
        guard let p = session.currentCameraWorldPosition else {
            step = .failed(reason: "ARKit pose unavailable at opening")
            return
        }
        openingPoseWorld = p
        location.clearBuffer()
        location.requestAuthorization()
        location.start()
        averagingStartedAt = Date()
        step = .averagingAtOpening(secondsElapsed: 0, sampleCount: 0)
        tickTimer = Timer.scheduledTimer(
            withTimeInterval: 1.0, repeats: true
        ) { [weak self] _ in
            Task { @MainActor in self?.tickAveraging() }
        }
    }

    private func tickAveraging() {
        guard case .averagingAtOpening = step,
              let startedAt = averagingStartedAt else { return }
        let elapsed = Int(Date().timeIntervalSince(startedAt))
        let samples = location.buffer.count
        if elapsed >= openingAveragingDurationS {
            finishOpeningAveraging()
        } else {
            step = .averagingAtOpening(
                secondsElapsed: elapsed, sampleCount: samples)
        }
    }

    private func finishOpeningAveraging() {
        tickTimer?.invalidate()
        tickTimer = nil
        guard let result = GPSAveraging.compute(
            input: .init(samples: location.buffer))
        else {
            step = .failed(reason:
                "Not enough clean samples at opening (need 30 ≤ 20 m).")
            return
        }
        openingFix = result
        step = .walkBack(distanceFromPlotM: nil)
    }

    private func updateWalkBackDistance(camera: SIMD3<Float>?) {
        guard case .walkBack = step,
              let camera, let plot = plotPoseWorld else { return }
        let d = simd_distance(camera, plot)
        step = .walkBack(distanceFromPlotM: d)
    }

    public func confirmPlotCenter() {
        guard let plotPose = plotPoseWorld,
              let openingPose = openingPoseWorld,
              let fix = openingFix else {
            step = .failed(reason: "Missing opening fix or pose snapshots.")
            return
        }
        let input = OffsetFromOpening.Input(
            openingFix: fix,
            openingPointWorld: openingPose,
            plotPointWorld: plotPose,
            trackingStateWasNormalThroughout: session.trackingStayedNormal)
        guard let result = OffsetFromOpening.compute(input: input) else {
            step = .failed(reason:
                "ARKit tracking was interrupted — offset invalid.")
            return
        }
        step = .computed(result)
    }

    public func cancel() {
        tickTimer?.invalidate()
        tickTimer = nil
        location.stop()
        step = .anchorPlot
        plotPoseWorld = nil
        openingPoseWorld = nil
        openingFix = nil
    }

    // MARK: - Preview helpers

    public static func preview(step: Step) -> OffsetFlowViewModel {
        let vm = OffsetFlowViewModel(
            location: LocationService(),
            session: ARKitSessionManager())
        vm.step = step
        return vm
    }
}
