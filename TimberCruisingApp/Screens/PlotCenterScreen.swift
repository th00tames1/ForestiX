// Spec §4.5 + §5.1 PlotCenterScreen. REQ-CTR-001/002/005.
//
// Progress bar + countdown + live sample count + live median h-acc
// while the 60 s window runs; on completion, show the computed
// center, tier, and either "Save" (A/B) or "Try Offset / Save
// anyway" (C/D per §4.5 fallback).

import SwiftUI
import Models
import Positioning

public struct PlotCenterScreen: View {

    @StateObject private var viewModel: PlotCenterViewModel
    public var onAccept: (PlotCenterResult) -> Void = { _ in }
    public var onTryOffset: (PlotCenterResult) -> Void = { _ in }

    public init(
        viewModel: @autoclosure @escaping () -> PlotCenterViewModel,
        onAccept: @escaping (PlotCenterResult) -> Void = { _ in },
        onTryOffset: @escaping (PlotCenterResult) -> Void = { _ in }
    ) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onAccept = onAccept
        self.onTryOffset = onTryOffset
    }

    public var body: some View {
        VStack(spacing: 24) {
            header
            phaseBody
            Spacer()
            actions
        }
        .padding(24)
        .navigationTitle("Plot Center")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.cancel() }
    }

    // MARK: - Header

    private var header: some View {
        VStack(spacing: 4) {
            Text("GPS Averaging")
                .font(.headline)
            Text("Stand still at the plot center for \(viewModel.averagingDurationS) s.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
    }

    // MARK: - Phase body

    @ViewBuilder
    private var phaseBody: some View {
        switch viewModel.phase {
        case .idle:
            ProgressView("Waiting for GPS…")

        case .averaging(let secs, let count):
            averagingView(secs: secs, count: count)

        case .good(let result):
            resultView(result, banner: nil, bannerColor: .green)

        case .poor(let result):
            resultView(
                result,
                banner: "Accuracy weak (tier \(result.tier.rawValue)). "
                      + "Offset-from-Opening usually helps under canopy.",
                bannerColor: .orange)

        case .failed(let reason):
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 48))
                    .foregroundStyle(.red)
                Text(reason)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private func averagingView(secs: Int, count: Int) -> some View {
        let progress = Double(secs) / Double(viewModel.averagingDurationS)
        return VStack(spacing: 16) {
            ProgressView(value: min(progress, 1))
                .progressViewStyle(.linear)
            HStack {
                Text("\(secs) / \(viewModel.averagingDurationS) s")
                Spacer()
                Text("\(count) samples")
            }
            .font(.caption.monospacedDigit())
            .foregroundStyle(.secondary)
            if let s = viewModel.latestSample {
                HStack {
                    Text("Live accuracy")
                    Spacer()
                    Text(String(format: "±%.1f m", s.horizontalAccuracyM))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }
        }
    }

    private func resultView(
        _ r: PlotCenterResult,
        banner: String?,
        bannerColor: Color
    ) -> some View {
        VStack(spacing: 12) {
            Text("Tier \(r.tier.rawValue)")
                .font(.largeTitle.bold())
                .foregroundStyle(bannerColor)
            Text(String(format: "%.6f, %.6f", r.lat, r.lon))
                .font(.callout.monospacedDigit())
            HStack(spacing: 16) {
                Label("\(r.nSamples)", systemImage: "dot.radiowaves.left.and.right")
                Label(String(format: "±%.1f m", r.medianHAccuracyM), systemImage: "scope")
                Label(String(format: "σxy %.1f m", r.sampleStdXyM), systemImage: "circle.dashed")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            if let banner {
                Text(banner)
                    .font(.caption)
                    .foregroundStyle(bannerColor)
                    .multilineTextAlignment(.center)
                    .padding(8)
                    .background(bannerColor.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
    }

    // MARK: - Actions

    @ViewBuilder
    private var actions: some View {
        switch viewModel.phase {
        case .good(let r):
            Button("Save plot center") { onAccept(r) }
                .buttonStyle(.borderedProminent)

        case .poor(let r):
            HStack(spacing: 12) {
                Button("Try Offset") { onTryOffset(r) }
                    .buttonStyle(.borderedProminent)
                Button("Save anyway") {
                    viewModel.acceptAnyway()
                    onAccept(r)
                }
                .buttonStyle(.bordered)
            }

        case .failed:
            Button("Retry") {
                viewModel.cancel()
                viewModel.start()
            }
            .buttonStyle(.borderedProminent)

        case .idle, .averaging:
            Button("Cancel", role: .cancel) { viewModel.cancel() }
        }
    }
}
