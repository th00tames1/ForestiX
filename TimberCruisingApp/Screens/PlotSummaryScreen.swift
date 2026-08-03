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

    /// Areal basis for the density rows (TPA, BA/area, volume/area). The engine
    /// computes per acre; metric countries re-express per hectare. The caller
    /// passes `settings.unitSystem.areaUnit`. Defaults to `.acre` so US callers,
    /// previews and tests are unchanged.
    private let areaUnit: AreaUnit

    /// The LINEAR half of the same setting — what the diameter and the height
    /// band on this screen are read in. `areaUnit` alone was not enough: it
    /// governs the denominator of the density rows and nothing else, so the
    /// quadratic mean diameter sat in the middle of a unit-aware block still
    /// printing centimetres, and the height curve's ± stayed in metres on a
    /// screen whose heights are feet. Defaults alongside `areaUnit` so
    /// previews and tests are unchanged.
    private let unitSystem: UnitSystem

    @State private var confirmingDelete = false

    public init(viewModel: @autoclosure @escaping () -> PlotSummaryViewModel,
                areaUnit: AreaUnit = .acre,
                unitSystem: UnitSystem = .imperial) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.areaUnit = areaUnit
        self.unitSystem = unitSystem
    }

    /// Per-acre → display-basis multiplier (1.0 US acres, 2.47105 hectares).
    private var densityFactor: Double { areaUnit.perAcreDensityFactor }

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
        .alert("Delete plot?", isPresented: $confirmingDelete) {
            Button("Delete plot", role: .destructive) {
                viewModel.delete()
                if viewModel.isDeleted { dismiss() }
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Delete Plot \(viewModel.plot.plotNumber) and its \(viewModel.trees.count) tree\(viewModel.trees.count == 1 ? "" : "s")? This can't be undone.")
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { viewModel.errorMessage != nil },
            set: { if !$0 { viewModel.clearError() } })
    }

    private var closedBanner: some View {
        Section {
            Label("Plot closed \(viewModel.closedAt?.formatted(date: .abbreviated, time: .shortened) ?? "")",
                  systemImage: "lock.circle.fill")
                .foregroundStyle(.secondary)
            Button {
                viewModel.reopen()
            } label: {
                Text("Reopen plot")
            }
            .accessibilityIdentifier("plotSummary.reopen")
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
            statRow("Trees / \(areaUnit.abbreviation)",
                    String(format: "%.1f", Double(viewModel.stats.tpa) * densityFactor))
            // Basal area converts its NUMERATOR with the basis, not only its
            // suffix — the engine reports m² per ACRE, so scaling just the
            // denominator left an imperial cruise reading "11.49 m²/ac", a
            // unit no cruise sheet uses and 10.76× away from the ft²/ac the
            // quick-measure card shows for the same stand.
            statRow("Basal area / \(areaUnit.abbreviation)",
                    String(format: "%.2f %@",
                           MeasurementFormatter.basalAreaDensity(
                               m2PerAcre: Double(viewModel.stats.baPerAcreM2),
                               in: areaUnit),
                           MeasurementFormatter.basalAreaDensityUnit(areaUnit)))
            // The one row in this block that used to stay metric — and the one
            // a cruiser compares against the inch diameters on the trees above.
            statRow("Quadratic mean diameter",
                    MeasurementFormatter.diameter(cm: Double(viewModel.stats.qmdCm),
                                                  in: unitSystem))
            statRow("Gross volume / \(areaUnit.abbreviation)",
                    String(format: "%.1f %@",
                           Double(viewModel.stats.grossVolumePerAcreM3) * densityFactor,
                           areaUnit.densityLabel("m³")))
            statRow("Merchantable volume / \(areaUnit.abbreviation)",
                    String(format: "%.1f %@",
                           Double(viewModel.stats.merchVolumePerAcreM3) * densityFactor,
                           areaUnit.densityLabel("m³")))
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
                            Text(RegionalSpecies.name(forCode: code))
                                .font(.body.bold())
                            Spacer()
                            Text("\(stat.count) trees")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                        HStack {
                            Text(String(format: "%.1f %@",
                                        Double(stat.tpa) * densityFactor,
                                        areaUnit.densitySuffix))
                            Spacer()
                            Text(String(format: "%.2f %@",
                                        MeasurementFormatter.basalAreaDensity(
                                            m2PerAcre: Double(stat.baPerAcreM2),
                                            in: areaUnit),
                                        MeasurementFormatter.basalAreaDensityUnit(areaUnit)))
                            Spacer()
                            Text(String(format: "%.1f %@",
                                        Double(stat.grossVolumePerAcreM3) * densityFactor,
                                        areaUnit.densityLabel("m³")))
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
        // "H–D fits" was height–diameter compressed to two letters plus the
        // modelling verb, and each row printed the regression coefficients
        // raw (a, b, n, RMSE). A cruiser cannot do anything differently
        // because b came out 0.567 — what they can use is how many trees
        // the curve rests on and how close it usually lands. The fit itself
        // is unchanged; only the readout is. The millisecond "Rolling
        // update" timing was developer telemetry and is gone.
        Section("Height curves for this project") {
            ForEach(viewModel.hdFitsByProject.keys.sorted(), id: \.self) { code in
                if let fit = viewModel.hdFitsByProject[code] {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(RegionalSpecies.name(forCode: code))
                            .font(.body.bold())
                        // The ± is the ONLY thing this screen says about how
                        // far an imputed height can be out. Read as feet on an
                        // imperial cruise it understated the curve's error by
                        // 3.28×, so it goes through the same band the per-tree
                        // report uses.
                        Text("Height curve from \(fit.nObs) trees, typically within "
                             + MeasurementFormatter.heightSigma(m: Double(fit.rmse),
                                                                in: unitSystem))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
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
                .buttonStyle(.forestixProminent)
                .controlSize(.large)
                .disabled(!viewModel.validation.canClose || viewModel.isClosing)
            } else {
                Button {
                    dismiss()
                } label: {
                    Text("Done").frame(maxWidth: .infinity)
                }
                .buttonStyle(.forestixProminent)
                .controlSize(.large)
            }

            // Hard removal from the map — cascades the plot's trees +
            // photos, then the plot. Same affordance as the map peek.
            Button(role: .destructive) {
                confirmingDelete = true
            } label: {
                Label("Delete plot", systemImage: "trash")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(viewModel.isDeleting)
        }
    }
}
