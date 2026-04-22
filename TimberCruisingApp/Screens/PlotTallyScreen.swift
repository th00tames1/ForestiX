// Phase 5 §5.1 PlotTallyScreen. REQ-TAL-005/006.
//
// Live tree-tally UI for an open plot:
//   • Header stats strip: #live, TPA, BA/ac, QMD, V/ac.
//   • List of tallied trees (swipe-to-soft-delete; tap → TreeDetail).
//   • "Add Tree" primary button, "Close Plot" secondary button.
//
// Live stats update within the REQ-TAL-005 300 ms budget because
// PlotTallyViewModel.recomputeStats() is pure and O(n_live).

import SwiftUI
import Models
import Common
import InventoryEngine

public struct PlotTallyScreen: View {

    @StateObject private var viewModel: PlotTallyViewModel
    @State private var showingAddTree = false
    @State private var closingPlot = false

    public var onAddTree: () -> Void = {}
    public var onOpenTree: (Tree) -> Void = { _ in }
    public var onClosePlot: () -> Void = {}

    public init(viewModel: @autoclosure @escaping () -> PlotTallyViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        VStack(spacing: 0) {
            statsStrip
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(ForestixPalette.surfaceRaised)
            treeList
            Divider()
            actionRow
        }
        .navigationTitle("Plot \(viewModel.plot.plotNumber)")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                // Close Plot is consequential (stamps closedAt, runs
                // HD rollup, ends the plot) — moved up to the nav
                // bar so a glove brush on a big bottom button can't
                // terminate the plot by accident.
                Button(role: .destructive) {
                    closingPlot = true
                } label: {
                    Label("Close Plot", systemImage: "lock.fill")
                }
                .tint(ForestixPalette.confidenceBad)
                .accessibilityIdentifier("plotTally.closePlotNav")
            }
        }
        .onAppear { viewModel.refresh() }
        .alert("Error", isPresented: errorBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .confirmationDialog(
            "Close this plot?",
            isPresented: $closingPlot,
            titleVisibility: .visible
        ) {
            Button("Review + close", role: .destructive) {
                closingPlot = false
                onClosePlot()
            }
            Button("Keep tallying", role: .cancel) {
                closingPlot = false
            }
        } message: {
            let n = viewModel.liveTrees.count
            Text("\(n) live tree\(n == 1 ? "" : "s") tallied. Closing pushes to Summary where you can confirm or re-open.")
        }
    }

    // MARK: - Error alert plumbing

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.clearError() } })
    }

    // MARK: - Stats strip

    private var statsStrip: some View {
        HStack(spacing: 16) {
            statCell(label: "Live",
                     value: "\(viewModel.stats.liveTreeCount)")
            statCell(label: "TPA",
                     value: String(format: "%.1f", viewModel.stats.tpa))
            statCell(label: "BA/ac m²",
                     value: String(format: "%.2f", viewModel.stats.baPerAcreM2))
            statCell(label: "QMD cm",
                     value: String(format: "%.1f", viewModel.stats.qmdCm))
            statCell(label: "V/ac m³",
                     value: String(format: "%.1f", viewModel.stats.grossVolumePerAcreM3))
        }
    }

    private func statCell(label: String, value: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title3.bold().monospacedDigit())
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - List

    private var treeList: some View {
        List {
            Section {
                ForEach(viewModel.liveTrees, id: \.id) { tree in
                    Button {
                        onOpenTree(tree)
                    } label: {
                        treeRow(tree)
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            viewModel.softDelete(treeId: tree.id)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            } header: {
                Text("Trees (\(viewModel.liveTrees.count))")
            }

            if !viewModel.softDeletedTrees.isEmpty {
                Section {
                    ForEach(viewModel.softDeletedTrees, id: \.id) { tree in
                        HStack {
                            treeRow(tree)
                                .opacity(0.5)
                            Spacer()
                            Button("Undo") {
                                viewModel.undelete(treeId: tree.id)
                            }
                            .font(.caption.bold())
                        }
                    }
                } header: {
                    Text("Deleted (\(viewModel.softDeletedTrees.count))")
                }
            }
        }
        .listStyle(.plain)
    }

    private func treeRow(_ tree: Tree) -> some View {
        HStack(spacing: 12) {
            Text("#\(tree.treeNumber)")
                .font(.body.monospacedDigit().bold())
                .frame(width: 44, alignment: .leading)
            VStack(alignment: .leading, spacing: 2) {
                Text(tree.speciesCode)
                    .font(.body)
                Text(tree.status.rawValue)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(String(format: "%.1f cm", tree.dbhCm))
                    .font(.body.monospacedDigit())
                if let h = tree.heightM {
                    Text(String(format: "%.1f m", h))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
            confidenceDot(tree.dbhConfidence)
            if tree.isMultistem {
                Image(systemName: "arrow.branch")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func confidenceDot(_ tier: ConfidenceTier) -> some View {
        Circle()
            .fill(color(for: tier))
            .frame(width: 10, height: 10)
    }

    private func color(for tier: ConfidenceTier) -> Color {
        // Route through ConfidenceStyle so every surface shows the
        // same muted instrument-grade tier hue. Raw system .green /
        // .yellow / .red read like traffic lights — not the tone
        // DesignSystem.swift is trying to establish.
        ConfidenceStyle.descriptor(for: tier.rawValue).color
    }

    // MARK: - Action row
    //
    // Only "Add Tree" lives here — Close Plot moved to the toolbar as
    // a destructive primary action. The action you press 30+ times
    // per plot deserves the big bottom button; the terminal action
    // shouldn't share the same tap zone.
    private var actionRow: some View {
        HStack(spacing: 12) {
            Button {
                onAddTree()
            } label: {
                Label("Add Tree", systemImage: "plus.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("plotTally.addTree")
        }
        .padding(16)
    }
}
