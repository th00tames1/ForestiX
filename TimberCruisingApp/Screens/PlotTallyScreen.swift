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
                .background(Color(white: 0.95))
            treeList
            Divider()
            actionRow
        }
        .navigationTitle("Plot \(viewModel.plot.plotNumber)")
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

    // MARK: - Error alert plumbing

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { _ in })
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
        switch tier {
        case .green: return .green
        case .yellow: return .yellow
        case .red: return .red
        }
    }

    // MARK: - Action row

    private var actionRow: some View {
        HStack(spacing: 12) {
            Button {
                onClosePlot()
            } label: {
                Text("Close Plot")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)

            Button {
                onAddTree()
            } label: {
                Label("Add Tree", systemImage: "plus.circle.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
        }
        .padding(16)
    }
}
