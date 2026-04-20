// Spec §3.1 REQ-PRJ-003 + REQ-PRJ-004. Configure cruise design and generate
// planned plots.

import SwiftUI
import Models

public struct CruiseDesignScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: CruiseDesignViewModel

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue: CruiseDesignViewModel(project: project))
    }

    public var body: some View {
        Form {
            Section("Plot Type") {
                Picker("Plot type", selection: $viewModel.plotType) {
                    Text("Fixed-area").tag(PlotType.fixedArea)
                    Text("Variable-radius (BAF)").tag(PlotType.variableRadius)
                }
                .pickerStyle(.segmented)

                if viewModel.plotType == .fixedArea {
                    LabeledField(title: "Plot area (acres)",
                                 text: $viewModel.plotAreaAcresString,
                                 id: "design.plotArea")
                } else {
                    LabeledField(title: "BAF (ft²/ac)",
                                 text: $viewModel.bafString,
                                 id: "design.baf")
                }
            }

            Section("Sampling") {
                Picker("Scheme", selection: $viewModel.samplingScheme) {
                    Text("Systematic grid").tag(SamplingScheme.systematicGrid)
                    Text("Stratified random").tag(SamplingScheme.stratifiedRandom)
                    Text("Manual").tag(SamplingScheme.manual)
                }

                switch viewModel.samplingScheme {
                case .systematicGrid:
                    LabeledField(title: "Grid spacing (m)",
                                 text: $viewModel.gridSpacingMetersString,
                                 id: "design.gridSpacing")
                case .stratifiedRandom:
                    LabeledField(title: "Plots per stratum",
                                 text: $viewModel.nPerStratumString,
                                 id: "design.nPerStratum")
                case .manual:
                    Text("Manual mode lets you tap the map to place plots. Generation is a no-op.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                LabeledField(title: "Random seed",
                             text: $viewModel.seedString,
                             id: "design.seed")
            }

            Section("Plan") {
                LabeledContent("Planned plots", value: "\(viewModel.plannedCount)")
                Button {
                    viewModel.generatePlannedPlots()
                } label: {
                    Text("Generate planned plots")
                        .bold()
                        .frame(minHeight: 44)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(!viewModel.isValid)
                .accessibilityIdentifier("design.generate")
            }

            speciesSection

            if let message = viewModel.validationMessage {
                Section {
                    Label(message, systemImage: "exclamationmark.triangle")
                        .foregroundStyle(.orange)
                }
            }
        }
        .navigationTitle("Cruise Design")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task {
            viewModel.configure(with: environment)
            viewModel.refresh()
        }
        .alert("Something went wrong",
               isPresented: Binding(
                    get: { viewModel.errorMessage != nil },
                    set: { if !$0 { viewModel.errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
    }

    // MARK: - Species & equations section
    //
    // Phase 7.3 — added after the abstract audit found that the design
    // screen had no surface for the species + volume-equation catalogue,
    // leaving the cruiser unable to confirm which species were
    // available for this project. We render a read-only list here;
    // add / edit of species is a Phase 8 CRUD task.

    @ViewBuilder
    private var speciesSection: some View {
        Section(
            header: Text("Species & volume equations"),
            footer: Text(viewModel.availableSpecies.isEmpty
                ? "No species loaded. Re-install the app so the Pacific Northwest seed (DF, WH, RC, RA) is re-imported."
                : "These species + volume equations are available for this project. Coefficients marked PLACEHOLDER in the source citation should be replaced with locally-calibrated values before production cruising — see docs/VOLUME_EQUATIONS.md.")
        ) {
            if viewModel.availableSpecies.isEmpty {
                Text("No species configured.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(viewModel.availableSpecies, id: \.code) { sp in
                    speciesRow(sp)
                }
            }
        }
    }

    @ViewBuilder
    private func speciesRow(_ sp: Models.SpeciesConfig) -> some View {
        let eq = viewModel.volumeEquationsById[sp.volumeEquationId]
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(sp.code)
                    .font(.body.monospaced().bold())
                    .frame(width: 44, alignment: .leading)
                VStack(alignment: .leading, spacing: 2) {
                    Text(sp.commonName)
                    Text(sp.scientificName)
                        .font(.caption.italic())
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            if let eq = eq {
                HStack(spacing: 6) {
                    Text(eq.form)
                        .font(.caption.monospaced())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.accentColor.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 4))
                    if eq.sourceCitation
                        .uppercased().contains("PLACEHOLDER") {
                        Label("placeholder",
                              systemImage: "exclamationmark.triangle.fill")
                            .labelStyle(.titleAndIcon)
                            .font(.caption)
                            .foregroundStyle(.orange)
                    } else {
                        Label("verified",
                              systemImage: "checkmark.seal.fill")
                            .font(.caption)
                            .foregroundStyle(.green)
                    }
                }
            } else {
                Label("Missing equation \(sp.volumeEquationId)",
                      systemImage: "exclamationmark.octagon.fill")
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding(.vertical, 2)
    }
}

private struct LabeledField: View {
    let title: String
    @Binding var text: String
    let id: String
    var body: some View {
        HStack {
            Text(title)
            Spacer()
            TextField(title, text: $text)
                .multilineTextAlignment(.trailing)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier(id)
        }
    }
}
