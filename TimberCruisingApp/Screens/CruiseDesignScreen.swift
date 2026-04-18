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
