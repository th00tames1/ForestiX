// Spec §4.5 Offset-from-Opening flow. REQ-CTR-002.
// Minimal stepper UI — snapshot-friendly, delegates all math to
// OffsetFlowViewModel / Positioning.

import SwiftUI
import Models
import Positioning

public struct OffsetFlowScreen: View {

    @StateObject private var viewModel: OffsetFlowViewModel
    public var onDone: (PlotCenterResult) -> Void = { _ in }

    public init(
        viewModel: @autoclosure @escaping () -> OffsetFlowViewModel,
        onDone: @escaping (PlotCenterResult) -> Void = { _ in }
    ) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onDone = onDone
    }

    public var body: some View {
        VStack(spacing: 20) {
            title
            stepBody
            Spacer()
            actions
        }
        .padding(24)
        .navigationTitle("Offset from Opening")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }

    private var title: some View {
        VStack(spacing: 2) {
            Text(stepName).font(.headline)
            Text(stepHint).font(.caption).foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    private var stepName: String {
        switch viewModel.step {
        case .anchorPlot:          return "A · Anchor plot"
        case .walkToOpening:       return "B · Walk to opening"
        case .averagingAtOpening:  return "C · Averaging at opening"
        case .walkBack:            return "D · Walk back"
        case .computed:            return "E · Confirmed"
        case .failed:              return "Failed"
        }
    }

    private var stepHint: String {
        switch viewModel.step {
        case .anchorPlot:
            return "Stand at the plot center. Tap Anchor when steady."
        case .walkToOpening:
            return "Walk to an opening with clear sky. Keep phone upright."
        case .averagingAtOpening:
            return "Hold still for \(viewModel.openingAveragingDurationS) s."
        case .walkBack:
            return "Walk back to the plot center under continuous tracking."
        case .computed:
            return "Plot center recovered."
        case .failed(let r):
            return r
        }
    }

    @ViewBuilder
    private var stepBody: some View {
        switch viewModel.step {
        case .averagingAtOpening(let secs, let count):
            VStack(spacing: 10) {
                ProgressView(
                    value: Double(secs),
                    total: Double(viewModel.openingAveragingDurationS))
                HStack {
                    Text("\(secs) / \(viewModel.openingAveragingDurationS) s")
                    Spacer()
                    Text("\(count) samples")
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        case .walkBack(let d):
            VStack {
                Text(d.map { String(format: "%.1f m from plot", $0) }
                     ?? "Waiting for ARKit pose…")
                    .font(.title3.monospacedDigit())
            }
        case .computed(let r):
            VStack(spacing: 6) {
                Text("Tier \(r.tier.rawValue)")
                    .font(.largeTitle.bold())
                    .foregroundStyle(.green)
                Text(String(format: "%.6f, %.6f", r.lat, r.lon))
                    .font(.callout.monospacedDigit())
                if let w = r.offsetWalkM {
                    Text(String(format: "Walk %.1f m", w))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        case .failed:
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 40))
                .foregroundStyle(.red)
        case .anchorPlot, .walkToOpening:
            EmptyView()
        }
    }

    @ViewBuilder
    private var actions: some View {
        switch viewModel.step {
        case .anchorPlot:
            Button("Anchor here") { viewModel.anchorPlotCenter() }
                .buttonStyle(.borderedProminent)
        case .walkToOpening:
            Button("Capture fix here") { viewModel.beginOpeningAveraging() }
                .buttonStyle(.borderedProminent)
        case .averagingAtOpening:
            Button("Cancel", role: .cancel) { viewModel.cancel() }
        case .walkBack:
            Button("Confirm plot center") { viewModel.confirmPlotCenter() }
                .buttonStyle(.borderedProminent)
        case .computed(let r):
            Button("Save") { onDone(r) }
                .buttonStyle(.borderedProminent)
        case .failed:
            Button("Restart") { viewModel.cancel() }
        }
    }
}
