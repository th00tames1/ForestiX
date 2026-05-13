// Phase 5 §5.3 TreeDetailScreen. REQ-TAL-006.
//
// Single-tree inspector: editable primary fields, swipe-to-undelete via button,
// and a read-only "Raw metadata" section auditors can inspect.

import SwiftUI
import Models
import Common

public struct TreeDetailScreen: View {

    @StateObject private var viewModel: TreeDetailViewModel
    @Environment(\.dismiss) private var dismiss

    public init(viewModel: @autoclosure @escaping () -> TreeDetailViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        Form {
            if viewModel.isDeleted {
                Section {
                    Label("This tree is soft-deleted — it is excluded from all statistics.",
                          systemImage: "trash.circle")
                        .foregroundStyle(.secondary)
                        .font(.callout)
                }
            }
            identitySection
            dbhSection
            heightSection
            placementSection
            notesSection
            rawMetaSection
            actionSection
        }
        .navigationTitle("Tree #\(viewModel.tree.treeNumber)")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .alert("Error", isPresented: errorBinding) {
            Button("OK", role: .cancel) { }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.clearError() } })
    }

    private var identitySection: some View {
        Section("Identity") {
            TextField("Species code", text: $viewModel.speciesCode)
                .onChange(of: viewModel.speciesCode) { _, _ in viewModel.markDirty() }
            Picker("Status", selection: $viewModel.status) {
                Text("Live").tag(TreeStatus.live)
                Text("Dead standing").tag(TreeStatus.deadStanding)
                Text("Dead down").tag(TreeStatus.deadDown)
                Text("Cull").tag(TreeStatus.cull)
            }
            .onChange(of: viewModel.status) { _, _ in viewModel.markDirty() }
            if viewModel.tree.isMultistem {
                Label("Multistem child", systemImage: "arrow.branch")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var dbhSection: some View {
        Section("DBH") {
            HStack {
                TextField("0.0", value: $viewModel.dbhCm, format: .number)
                    .font(.body.monospacedDigit())
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .onChange(of: viewModel.dbhCm) { _, _ in viewModel.markDirty() }
                Text("cm").foregroundStyle(.secondary)
            }
            Toggle("Irregular", isOn: $viewModel.dbhIsIrregular)
                .onChange(of: viewModel.dbhIsIrregular) { _, _ in viewModel.markDirty() }
            HStack {
                Text("Method")
                Spacer()
                Text(viewModel.tree.dbhMethod.rawValue)
                    .foregroundStyle(.secondary)
                    .font(.caption)
            }
            HStack {
                Text("Confidence")
                Spacer()
                tierBadge(viewModel.tree.dbhConfidence)
            }
        }
    }

    private var heightSection: some View {
        Section("Height") {
            HStack {
                TextField(
                    "-",
                    value: Binding(
                        get: { viewModel.heightM ?? 0 },
                        set: {
                            viewModel.heightM = $0 > 0 ? $0 : nil
                            viewModel.markDirty()
                        }),
                    format: .number)
                    .font(.body.monospacedDigit())
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                Text("m").foregroundStyle(.secondary)
            }
            if let src = viewModel.tree.heightSource {
                HStack {
                    Text("Source"); Spacer()
                    Text(src).foregroundStyle(.secondary).font(.caption)
                }
            }
            if let tier = viewModel.tree.heightConfidence {
                HStack {
                    Text("Confidence"); Spacer()
                    tierBadge(tier)
                }
            }
        }
    }

    private var placementSection: some View {
        Section("Placement") {
            HStack {
                TextField("Bearing",
                          value: Binding(
                            get: { viewModel.bearingFromCenterDeg ?? 0 },
                            set: {
                                viewModel.bearingFromCenterDeg = $0
                                viewModel.markDirty()
                            }),
                          format: .number)
                    .font(.body.monospacedDigit())
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                Text("°").foregroundStyle(.secondary)
            }
            HStack {
                TextField("Distance",
                          value: Binding(
                            get: { viewModel.distanceFromCenterM ?? 0 },
                            set: {
                                viewModel.distanceFromCenterM = $0
                                viewModel.markDirty()
                            }),
                          format: .number)
                    .font(.body.monospacedDigit())
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                Text("m").foregroundStyle(.secondary)
            }
        }
    }

    private var notesSection: some View {
        Section("Notes") {
            TextField("Notes", text: $viewModel.notes, axis: .vertical)
                .lineLimit(2...5)
                .onChange(of: viewModel.notes) { _, _ in viewModel.markDirty() }
        }
    }

    private var rawMetaSection: some View {
        Section("Raw metadata (read-only)") {
            metaRow("dbhSigmaMm", viewModel.tree.dbhSigmaMm.map { String(format: "%.2f", $0) })
            metaRow("dbhRmseMm", viewModel.tree.dbhRmseMm.map { String(format: "%.2f", $0) })
            metaRow("dbhCoverageDeg", viewModel.tree.dbhCoverageDeg.map { String(format: "%.1f", $0) })
            metaRow("dbhNInliers", viewModel.tree.dbhNInliers.map { "\($0)" })
            metaRow("heightSigmaM", viewModel.tree.heightSigmaM.map { String(format: "%.2f", $0) })
            metaRow("heightDHM", viewModel.tree.heightDHM.map { String(format: "%.2f", $0) })
            metaRow("heightAlphaTopDeg", viewModel.tree.heightAlphaTopDeg.map { String(format: "%.2f", $0) })
            metaRow("heightAlphaBaseDeg", viewModel.tree.heightAlphaBaseDeg.map { String(format: "%.2f", $0) })
            metaRow("createdAt", viewModel.tree.createdAt.ISO8601Format())
            metaRow("updatedAt", viewModel.tree.updatedAt.ISO8601Format())
        }
    }

    private func metaRow(_ label: String, _ value: String?) -> some View {
        HStack {
            Text(label).font(.caption.monospaced())
            Spacer()
            Text(value ?? "—")
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
        }
    }

    private var actionSection: some View {
        Section {
            Button {
                viewModel.save()
                if viewModel.errorMessage == nil, !viewModel.dirty {
                    dismiss()
                }
            } label: {
                Text("Save changes").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(!viewModel.dirty || viewModel.isSaving)

            if viewModel.isDeleted {
                Button {
                    viewModel.undelete()
                } label: {
                    Label("Undelete", systemImage: "arrow.uturn.backward")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            } else {
                Button(role: .destructive) {
                    viewModel.softDelete()
                } label: {
                    Label("Soft delete", systemImage: "trash")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.large)
            }
        }
    }

    private func tierBadge(_ tier: ConfidenceTier) -> some View {
        HStack(spacing: 6) {
            Circle()
                .fill(tierColor(tier))
                .frame(width: 10, height: 10)
            Text(tier.rawValue.capitalized)
                .font(.caption.bold())
                .foregroundStyle(tierColor(tier))
        }
    }

    private func tierColor(_ tier: ConfidenceTier) -> Color {
        ConfidenceStyle.descriptor(for: tier.rawValue).color
    }
}
