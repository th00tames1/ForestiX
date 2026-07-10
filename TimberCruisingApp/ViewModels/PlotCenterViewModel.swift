// Spec §4.5 + §7.3.1 PlotCenterScreen view model. REQ-CTR-001/005.
//
// Runs the 60 s GPS averaging window, surfaces live counters (sample
// count, current median h-accuracy), and on completion hands the
// buffer to `GPSAveraging.compute`. Tier A or B → done, auto-accept;
// tier C/D → show the Offset-from-Opening fallback banner per §4.5
// and let the user decide between "Accept anyway" (tier C/D plot,
// recommend revisit) and "Try Offset".

import Foundation
import Combine
import Models
import Positioning

@MainActor
public final class PlotCenterViewModel: ObservableObject {

    public enum Phase: Sendable {
        case idle
        case averaging(secondsElapsed: Int, sampleCount: Int)
        case good(PlotCenterResult)
        case poor(PlotCenterResult)    // tier C/D — offer Offset
        case failed(reason: String)
    }

    public let location: LocationService
    public let averagingDurationS: Int
    public private(set) var startedAt: Date?

    @Published public private(set) var phase: Phase = .idle
    @Published public private(set) var latestSample: CLLocationSnapshot?

    private var tickTimer: Timer?
    private var cancellables: Set<AnyCancellable> = []

    public init(
        location: LocationService,
        averagingDurationS: Int = 60
    ) {
        self.location = location
        self.averagingDurationS = averagingDurationS
        location.$latestSnapshot
            .assign(to: &$latestSample)
    }

    // MARK: - Lifecycle

    public func start() {
        guard case .idle = phase else { return }
        location.clearBuffer()
        location.requestAuthorization()
        location.start()
        startedAt = Date()
        phase = .averaging(secondsElapsed: 0, sampleCount: 0)
        tickTimer = Timer.scheduledTimer(
            withTimeInterval: 1.0, repeats: true
        ) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    public func cancel() {
        tickTimer?.invalidate()
        tickTimer = nil
        location.stop()
        phase = .idle
    }

    private func tick() {
        guard case .averaging = phase, let startedAt else { return }
        let elapsed = Int(Date().timeIntervalSince(startedAt))
        let samples = location.buffer.count
        if elapsed >= averagingDurationS {
            finish()
        } else {
            phase = .averaging(secondsElapsed: elapsed, sampleCount: samples)
        }
    }

    private func finish() {
        tickTimer?.invalidate()
        tickTimer = nil
        let samples = location.buffer
        guard let result = GPSAveraging.compute(
            input: .init(samples: samples))
        else {
            phase = .failed(reason:
                "Not enough samples (need 30 with accuracy ≤ 20 m)")
            return
        }
        switch result.tier {
        case .A, .B:
            phase = .good(result)
        case .C, .D:
            phase = .poor(result)
        }
    }

    /// Force-accept a tier-C/D result the user chose over falling
    /// back to Offset. Returned as `.good` so downstream accept flow
    /// can treat both paths uniformly.
    public func acceptAnyway() {
        if case .poor(let r) = phase { phase = .good(r) }
    }

    // MARK: - Preview helpers

    public static func preview(phase: Phase) -> PlotCenterViewModel {
        let vm = PlotCenterViewModel(location: LocationService())
        vm.phase = phase
        return vm
    }
}
