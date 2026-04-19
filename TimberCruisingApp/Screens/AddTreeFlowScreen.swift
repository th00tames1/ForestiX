// Phase 5 §5.2 AddTreeFlowScreen. REQ-TAL-001..004, REQ-HGT-007.
//
// 5-step stepper UI driven by AddTreeFlowViewModel:
//   1. Species quick-tap (recent 5 species, 3-col grid, 56pt buttons) +
//      alphabetical search list fallback.
//   2. DBH number entry + method picker + irregular toggle.
//   3. Height (only shown when subsample rule demands it; skippable).
//   4. Extras (status, crown class, damage, notes, bearing/distance).
//   5. Review (confidence tiers, red-tier warning, Save + Save & add stem).

import SwiftUI
import Models
import Common
import InventoryEngine

public struct AddTreeFlowScreen: View {

    @StateObject private var viewModel: AddTreeFlowViewModel
    @Environment(\.dismiss) private var dismiss

    public var onSaved: (Tree) -> Void = { _ in }

    public init(viewModel: @autoclosure @escaping () -> AddTreeFlowViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        VStack(spacing: 0) {
            progressStrip
            Divider()
            stepContent
                .frame(maxHeight: .infinity)
            Divider()
            actionBar
        }
        .navigationTitle("Tree #\(viewModel.nextTreeNumber)")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onChange(of: viewModel.savedTree?.id) { _, _ in
            if let t = viewModel.savedTree { onSaved(t) }
        }
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

    // MARK: - Progress strip

    private var progressStrip: some View {
        HStack(spacing: 6) {
            ForEach(AddTreeFlowViewModel.Step.allCases, id: \.rawValue) { step in
                Capsule()
                    .fill(color(for: step))
                    .frame(height: 6)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private func color(for step: AddTreeFlowViewModel.Step) -> Color {
        if step.rawValue < viewModel.currentStep.rawValue { return .accentColor }
        if step == viewModel.currentStep { return .accentColor.opacity(0.7) }
        return Color(white: 0.85)
    }

    // MARK: - Step content

    @ViewBuilder
    private var stepContent: some View {
        switch viewModel.currentStep {
        case .species: speciesStep
        case .dbh:     dbhStep
        case .height:  heightStep
        case .extras:  extrasStep
        case .review:  reviewStep
        }
    }

    // MARK: Species

    private var speciesStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VoiceSpeciesPicker(
                    candidates: allSpeciesSorted().map { sp in
                        (code: sp.code,
                         commonName: sp.commonName,
                         scientificName: sp.scientificName)
                    },
                    onMatch: { viewModel.speciesCode = $0 }
                )
                if !viewModel.recentSpeciesCodes.isEmpty {
                    Text("Recent")
                        .font(.headline)
                    LazyVGrid(columns: Array(repeating:
                        GridItem(.flexible(), spacing: 12), count: 3),
                              spacing: 12) {
                        ForEach(viewModel.recentSpeciesCodes, id: \.self) { code in
                            speciesButton(code: code)
                        }
                    }
                }
                Text("All species")
                    .font(.headline)
                    .padding(.top, 4)
                ForEach(allSpeciesSorted(), id: \.code) { sp in
                    Button {
                        viewModel.speciesCode = sp.code
                    } label: {
                        HStack {
                            Text(sp.code)
                                .font(.body.monospaced().bold())
                                .frame(width: 44, alignment: .leading)
                            Text(sp.commonName)
                                .font(.body)
                            Spacer()
                            if viewModel.speciesCode == sp.code {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(.tint)
                            }
                        }
                        .padding(.vertical, 8)
                    }
                    .buttonStyle(.plain)
                    Divider()
                }
            }
            .padding(16)
        }
    }

    private func speciesButton(code: String) -> some View {
        Button {
            viewModel.speciesCode = code
        } label: {
            VStack(spacing: 2) {
                Text(code)
                    .font(.title3.bold().monospaced())
                if let sp = viewModel.speciesByCode[code] {
                    Text(sp.commonName)
                        .font(.caption2)
                        .lineLimit(1)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 56)
        }
        .buttonStyle(.borderedProminent)
        .tint(viewModel.speciesCode == code ? .accentColor : Color(white: 0.85))
        .foregroundStyle(viewModel.speciesCode == code ? .white : .primary)
    }

    private func allSpeciesSorted() -> [SpeciesConfig] {
        viewModel.speciesByCode.values.sorted { $0.code < $1.code }
    }

    // MARK: DBH

    private var dbhStep: some View {
        Form {
            Section("DBH (cm)") {
                HStack {
                    TextField("0.0", value: $viewModel.dbhCm, format: .number)
                        .font(.title2.monospacedDigit())
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                    Text("cm")
                        .foregroundStyle(.secondary)
                }
            }
            Section("Method") {
                Picker("Method", selection: $viewModel.dbhMethod) {
                    Text("Caliper").tag(DBHMethod.manualCaliper)
                    Text("Visual").tag(DBHMethod.manualVisual)
                    Text("LiDAR — single").tag(DBHMethod.lidarPartialArcSingleView)
                    Text("LiDAR — dual").tag(DBHMethod.lidarPartialArcDualView)
                    Text("LiDAR — irregular").tag(DBHMethod.lidarIrregular)
                }
                .pickerStyle(.menu)
                Toggle("Irregular cross-section", isOn: $viewModel.dbhIsIrregular)
            }
            if let sp = viewModel.speciesByCode[viewModel.speciesCode] {
                Section("Species range") {
                    Text(String(format: "%.1f–%.1f cm",
                                sp.expectedDbhMinCm, sp.expectedDbhMaxCm))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: Height

    private var heightStep: some View {
        Form {
            Section {
                Text("Subsample rule requires a measured height for this tree.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            Section("Height (m)") {
                HStack {
                    TextField(
                        "0.0",
                        value: Binding(
                            get: { viewModel.heightM ?? 0 },
                            set: { viewModel.heightM = $0 > 0 ? $0 : nil }),
                        format: .number)
                        .font(.title2.monospacedDigit())
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                    Text("m")
                        .foregroundStyle(.secondary)
                }
            }
            Section("Method") {
                Picker("Method", selection: Binding(
                    get: { viewModel.heightMethod ?? .manualEntry },
                    set: { viewModel.heightMethod = $0 })) {
                    Text("Manual entry").tag(HeightMethod.manualEntry)
                    Text("Tape + tangent").tag(HeightMethod.tapeTangent)
                    Text("VIO walk-off").tag(HeightMethod.vioWalkoffTangent)
                }
                .pickerStyle(.menu)
            }
            if let sp = viewModel.speciesByCode[viewModel.speciesCode] {
                Section("Species range") {
                    Text(String(format: "%.1f–%.1f m",
                                sp.expectedHeightMinM, sp.expectedHeightMaxM))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    // MARK: Extras

    private var extrasStep: some View {
        Form {
            Section("Status") {
                Picker("Status", selection: $viewModel.status) {
                    Text("Live").tag(TreeStatus.live)
                    Text("Dead standing").tag(TreeStatus.deadStanding)
                    Text("Dead down").tag(TreeStatus.deadDown)
                    Text("Cull").tag(TreeStatus.cull)
                }
                .pickerStyle(.segmented)
            }
            Section("Crown class") {
                TextField("e.g. dominant",
                          text: Binding(
                            get: { viewModel.crownClass ?? "" },
                            set: { viewModel.crownClass = $0.isEmpty ? nil : $0 }))
            }
            Section("Placement") {
                HStack {
                    TextField("Bearing °",
                              value: Binding(
                                get: { viewModel.bearingFromCenterDeg ?? 0 },
                                set: { viewModel.bearingFromCenterDeg = $0 }),
                              format: .number)
                        .font(.body.monospacedDigit())
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                    Text("°").foregroundStyle(.secondary)
                }
                HStack {
                    TextField("Distance m",
                              value: Binding(
                                get: { viewModel.distanceFromCenterM ?? 0 },
                                set: { viewModel.distanceFromCenterM = $0 }),
                              format: .number)
                        .font(.body.monospacedDigit())
                        #if os(iOS)
                        .keyboardType(.decimalPad)
                        #endif
                    Text("m").foregroundStyle(.secondary)
                }
            }
            Section("Notes") {
                TextField("Optional notes", text: $viewModel.notes, axis: .vertical)
                    .lineLimit(2...4)
            }
        }
    }

    // MARK: Review

    private var reviewStep: some View {
        Form {
            Section("Species") {
                LabeledContent("Code", value: viewModel.speciesCode)
                if let sp = viewModel.speciesByCode[viewModel.speciesCode] {
                    LabeledContent("Common", value: sp.commonName)
                }
            }
            Section("DBH") {
                LabeledContent("Value",
                               value: String(format: "%.1f cm", viewModel.dbhCm))
                HStack {
                    Text("Confidence")
                    Spacer()
                    tierBadge(viewModel.dbhConfidence)
                }
            }
            if let h = viewModel.heightM {
                Section("Height") {
                    LabeledContent("Value", value: String(format: "%.1f m", h))
                    if let tier = viewModel.heightConfidence {
                        HStack {
                            Text("Confidence")
                            Spacer()
                            tierBadge(tier)
                        }
                    }
                }
            } else {
                Section("Height") {
                    Text("Not measured — will be imputed from H–D model.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            if let warn = viewModel.redTierWarning {
                Section {
                    Label(warn, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                        .font(.callout)
                } header: {
                    Text("Red-tier warning")
                }
            }
            if viewModel.isMultistem {
                Section {
                    Label("Multistem child stem",
                          systemImage: "arrow.branch")
                        .font(.callout)
                }
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
        switch tier {
        case .green: return .green
        case .yellow: return .yellow
        case .red: return .red
        }
    }

    // MARK: - Action bar

    private var actionBar: some View {
        HStack(spacing: 12) {
            if !viewModel.history.isEmpty {
                Button("Back") { viewModel.back() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
            }
            Spacer()
            if viewModel.currentStep == .height {
                Button("Skip") { viewModel.skipHeight() }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
            }
            if viewModel.currentStep == .review {
                Button("Save") {
                    viewModel.save()
                    if viewModel.errorMessage == nil {
                        dismiss()
                    }
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .disabled(viewModel.isSaving)
            } else {
                Button("Next") { viewModel.advance() }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .disabled(!viewModel.canAdvance())
            }
        }
        .padding(16)
    }
}
