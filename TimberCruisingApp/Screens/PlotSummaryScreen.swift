// Phase 5 §5.4 PlotSummaryScreen. REQ-AGG-001/002, §7.4.
//
// Pre-close summary: validation warnings/errors, final plot stats,
// and Close button that triggers the H–D rolling update.

import SwiftUI
import Models
import Common
import InventoryEngine

public struct PlotSummaryScreen: View {

    @StateObject private var viewModel: PlotSummaryViewModel
    @Environment(\.dismiss) private var dismiss

    public var onClosed: () -> Void = {}

    public init(viewModel: @autoclosure @escaping () -> PlotSummaryViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        Form {
            if viewModel.closedAt != nil {
                closedBanner
            }
            validationSection
            statsSection
            speciesBreakdownSection
            if !viewModel.hdFitsByProject.isEmpty {
                hdFitSection
            }
            actionSection
        }
        .navigationTitle("Plot \(viewModel.plot.plotNumber) summary")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.refresh() }
        .alert("Error", isPresented: errorBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { _ in })
    }

    private var closedBanner: some View {
        Section {
            Label("Plot closed \(viewModel.closedAt?.formatted(date: .abbreviated, time: .shortened) ?? "")",
                  systemImage: "lock.circle.fill")
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Validation

    private var validationSection: some View {
        Section("Validation") {
            if viewModel.validation.errors.isEmpty
                && viewModel.validation.warnings.isEmpty {
                Label("All checks passed.", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(.green)
            } else {
                ForEach(viewModel.validation.errors, id: \.code) { issue in
                    Label(issue.message, systemImage: "xmark.octagon.fill")
                        .foregroundStyle(.red)
                        .font(.callout)
                }
                ForEach(viewModel.validation.warnings, id: \.code) { issue in
                    Label(issue.message, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .font(.callout)
                }
            }
        }
    }

    // MARK: - Stats

    private var statsSection: some View {
        Section("Plot stats") {
            statRow("Live trees", "\(viewModel.stats.liveTreeCount)")
            statRow("Trees / ac", String(format: "%.1f", viewModel.stats.tpa))
            statRow("Basal area / ac",
                    String(format: "%.2f m²/ac", viewModel.stats.baPerAcreM2))
            statRow("QMD",
                    String(format: "%.1f cm", viewModel.stats.qmdCm))
            statRow("Gross V / ac",
                    String(format: "%.1f m³/ac", viewModel.stats.grossVolumePerAcreM3))
            statRow("Merch V / ac",
                    String(format: "%.1f m³/ac", viewModel.stats.merchVolumePerAcreM3))
        }
    }

    private func statRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .font(.body.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - Species breakdown

    private var speciesBreakdownSection: some View {
        Section("By species") {
            if viewModel.stats.bySpecies.isEmpty {
                Text("No live trees.").foregroundStyle(.secondary)
            } else {
                ForEach(sortedSpecies(), id: \.0) { code, stat in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(code).font(.body.monospaced().bold())
                            Spacer()
                            Text("\(stat.count) trees")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        HStack {
                            Text(String(format: "%.1f /ac", stat.tpa))
                            Spacer()
                            Text(String(format: "%.2f m²/ac", stat.baPerAcreM2))
                            Spacer()
                            Text(String(format: "%.1f m³/ac", stat.grossVolumePerAcreM3))
                        }
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func sortedSpecies() -> [(String, PlotStats.SpeciesStat)] {
        viewModel.stats.bySpecies.sorted { $0.key < $1.key }
    }

    // MARK: - H-D fits

    private var hdFitSection: some View {
        Section("H–D fits (project)") {
            ForEach(viewModel.hdFitsByProject.keys.sorted(), id: \.self) { code in
                if let fit = viewModel.hdFitsByProject[code] {
                    HStack {
                        Text(code).font(.body.monospaced().bold())
                        Spacer()
                        Text(String(format: "a=%.3f b=%.3f n=%d RMSE=%.2fm",
                                    fit.a, fit.b, fit.nObs, fit.rmse))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
            if viewModel.hdFitDurationMs > 0 {
                Text(String(format: "Rolling update: %.0f ms",
                            viewModel.hdFitDurationMs))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - Actions

    private var actionSection: some View {
        Section {
            if viewModel.closedAt == nil {
                Button {
                    viewModel.close()
                    if viewModel.errorMessage == nil
                        && viewModel.closedAt != nil {
                        onClosed()
                        dismiss()
                    }
                } label: {
                    Label("Close plot", systemImage: "lock.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(!viewModel.validation.canClose || viewModel.isClosing)
            } else {
                Button {
                    dismiss()
                } label: {
                    Text("Done").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
            }
        }
    }
}
