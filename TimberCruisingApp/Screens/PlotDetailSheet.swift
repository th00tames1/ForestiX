// The plot's own record: what the cruiser can say about the GROUND, next to
// what the app already knows about the plot.
//
// One sheet, reached from more than one place (the field log and the cruise
// map both open it), so it takes a plot IDENTIFIER and loads its own data
// rather than being handed a Plot the host has already read. Two hosts each
// holding their own copy of the same plot is how one of them saves over the
// other's edit.
//
// The four site fields are slope, aspect, ground elevation and canopy cover.
// Slope and aspect are DEGREES, the units the rest of the app already prints
// them in (see the plot block in the PDF report). Elevation is stored in
// metres like every other length here and is typed in the cruiser's own unit —
// feet on a US cruise — through `MeasurementFormatter.elevationDisplay`.
// Canopy cover is a percentage and is the same number in both systems.
//
// Nothing here validates on its own authority: the ranges live on
// `Plot.siteDescriptionRejection`, which is also what the repository refuses
// with. This screen asks the model the same question the store will ask, so
// Save is never offered for something the write would throw on.

import SwiftUI
import Common
import Models
import Persistence

/// Plot detail, presented as a sheet.
///
/// ```
/// .sheet(item: $detailPlotId) { id in
///     PlotDetailSheet(plotId: id.value) { updated in ... }
/// }
/// ```
///
/// `onSaved` fires once, with the stored plot, after a successful write; the
/// sheet dismisses itself. The host needs `AppEnvironment` and `AppSettings`
/// in the environment, which every screen in this app already has.
public struct PlotDetailSheet: View {

    @EnvironmentObject private var environment: AppEnvironment
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.dismiss) private var dismiss

    public let plotId: UUID
    public var onSaved: (Plot) -> Void

    public init(plotId: UUID, onSaved: @escaping (Plot) -> Void = { _ in }) {
        self.plotId = plotId
        self.onSaved = onSaved
    }

    /// The plot as STORED. Every prefill is derived from this, and a save
    /// replaces it, so the fields re-seed from what actually landed rather
    /// than from what was typed.
    @State private var plot: Plot?
    /// Live trees on the plot — the count a cruiser checks the sheet for.
    /// Soft-deleted trees are excluded because they are excluded from every
    /// tally; a sheet that counted them would disagree with the field log.
    @State private var treeCount = 0
    @State private var loadError: String?

    @State private var slopeText = ""
    @State private var aspectText = ""
    @State private var elevationText = ""
    @State private var canopyText = ""

    @State private var saving = false
    @State private var saveError: String?

    public var body: some View {
        NavigationStack {
            Group {
                if let plot {
                    form(plot)
                } else {
                    placeholder
                }
            }
            .navigationTitle(plot.map { "Plot \($0.plotNumber)" } ?? "Plot")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .accessibilityIdentifier("plotDetail.cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(!canSave)
                        .accessibilityIdentifier("plotDetail.save")
                }
            }
        }
        .onAppear(perform: load)
        .alert("Couldn't save the plot",
               isPresented: Binding(get: { saveError != nil },
                                    set: { if !$0 { saveError = nil } })) {
            Button("OK", role: .cancel) { saveError = nil }
        } message: {
            Text(saveError ?? "")
        }
        .accessibilityIdentifier("plotDetail.sheet")
    }

    // MARK: - Load

    private func load() {
        // Re-seeding on every appearance would throw away an edit in progress
        // if the sheet redraws; the sheet loads once and `save()` refreshes it.
        guard plot == nil else { return }
        do {
            guard let stored = try environment.plotRepository.read(id: plotId) else {
                loadError = "This plot is no longer in the project."
                return
            }
            treeCount = (try? environment.treeRepository
                .listByPlot(stored.id, includeDeleted: false))?.count ?? 0
            seed(from: stored)
        } catch {
            loadError = "Couldn't open the plot: \(error.localizedDescription)"
        }
    }

    private func seed(from stored: Plot) {
        plot = stored
        slopeText = slopePrefill(stored)
        aspectText = aspectPrefill(stored)
        elevationText = elevationPrefill(stored)
        canopyText = canopyPrefill(stored)
    }

    private var placeholder: some View {
        VStack(spacing: ForestixSpace.sm) {
            if let loadError {
                Text(loadError)
                    .font(ForestixType.body)
                    .foregroundStyle(ForestixPalette.confidenceBad)
                    .multilineTextAlignment(.center)
            } else {
                ProgressView()
            }
        }
        .padding(ForestixSpace.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: - Form

    private func form(_ stored: Plot) -> some View {
        Form {
            positionSection(stored)
            siteSection(stored)
            plotFactsSection(stored)
        }
    }

    private func positionSection(_ stored: Plot) -> some View {
        Section("Position") {
            // `hasCentre` rather than a lat/lon test of our own — (0, 0) is
            // the app's "no centre" sentinel and printing it here would put
            // the plot in the Gulf of Guinea.
            labelRow("Centre",
                     stored.hasCentre
                     ? String(format: "%.5f, %.5f", stored.centerLat, stored.centerLon)
                     : "No centre recorded")
        }
    }

    private func siteSection(_ stored: Plot) -> some View {
        Section {
            valueRow(label: "Slope", unit: "°",
                     identifier: "plotDetail.slope",
                     text: $slopeText)
            if !slopeEntryValid {
                entryWarning("Slope is degrees from 0 (flat) to 90 (vertical).")
            }

            valueRow(label: "Aspect", unit: aspectUnitLabel,
                     identifier: "plotDetail.aspect",
                     text: $aspectText,
                     decimalKeyboard: false)
            if !aspectEntryValid {
                entryWarning("Aspect is a bearing from 0 to 360, or a compass point like NE.")
            }

            valueRow(label: "Elevation",
                     unit: MeasurementFormatter.heightUnit(settings.unitSystem),
                     identifier: "plotDetail.elevation",
                     text: $elevationText)
            if !elevationEntryValid {
                entryWarning("Elevation is a number, or blank if you didn't record one.")
            }

            valueRow(label: "Canopy cover", unit: "%",
                     identifier: "plotDetail.canopy",
                     text: $canopyText)
            if !canopyEntryValid {
                entryWarning("Canopy cover is 0 to 100 %, or blank if you didn't record one.")
            }

            // The MODEL's refusal, in the model's words — the same sentence
            // the repository would throw. Anything the sheet allowed but
            // storage will not surfaces here rather than under a Save button
            // that fails silently.
            if let rejection = edited(from: stored)?.siteDescriptionRejection {
                entryWarning(rejection)
            }
        } header: {
            Text("Site")
        } footer: {
            Text("Aspect takes a bearing or a compass point — 45 and NE are the same entry. Leave elevation or canopy cover blank if you didn't record one.")
        }
    }

    private func plotFactsSection(_ stored: Plot) -> some View {
        Section("This plot") {
            labelRow("Trees", "\(treeCount)")
            labelRow("Area", areaText(stored))
            labelRow("Opened",
                     stored.startedAt.formatted(date: .abbreviated, time: .shortened))
            labelRow("Closed",
                     stored.closedAt.map {
                         $0.formatted(date: .abbreviated, time: .shortened)
                     } ?? "Still open")
        }
    }

    private func areaText(_ stored: Plot) -> String {
        let unit = settings.unitSystem.areaUnit
        return String(format: "%.2f %@",
                      unit.fromAcres(Double(stored.plotAreaAcres)),
                      unit.abbreviation)
    }

    // MARK: - Rows

    /// One editable site field: label left, value right, unit trailing. Same
    /// geometry as the tree inspector's measurement rows, so the two detail
    /// forms read as one app.
    private func valueRow(label: String,
                          unit: String,
                          identifier: String,
                          text: Binding<String>,
                          decimalKeyboard: Bool = true) -> some View {
        HStack(spacing: 8) {
            Text(label)
            Spacer(minLength: 8)
            TextField("—", text: text)
                .font(.body.monospacedDigit())
                .multilineTextAlignment(.trailing)
                #if os(iOS)
                // The aspect field takes letters ("NE"), so it cannot have the
                // decimal pad — there would be no way to type the point.
                .keyboardType(decimalKeyboard ? .decimalPad : .default)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
                #endif
                .frame(maxWidth: 110)
                .accessibilityIdentifier(identifier)
            Text(unit)
                .foregroundStyle(.secondary)
                .frame(minWidth: 28, alignment: .leading)
        }
    }

    private func labelRow(_ label: String, _ value: String) -> some View {
        HStack(spacing: 8) {
            Text(label)
            Spacer(minLength: 8)
            Text(value)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    private func entryWarning(_ text: String) -> some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(ForestixPalette.confidenceBad)
            .fixedSize(horizontal: false, vertical: true)
    }

    /// The degree sign plus the compass point the current entry stands for —
    /// what tells the cruiser that a typed "NE" landed on 45, and that a typed
    /// 45 is the north-east they meant.
    private var aspectUnitLabel: String {
        guard let deg = PlotAspect.parse(aspectText) else { return "°" }
        return "° \(PlotAspect.pointName(deg))"
    }

    // MARK: - Prefills
    //
    // Every prefill is the stored value ROUNDED for display, which is only
    // safe because `edited(from:)` restores the stored value verbatim whenever
    // the text still equals its prefill. Without that, opening the sheet and
    // pressing Save would quietly re-round every field — and on an imperial
    // cruise the elevation would also make a metres→feet→metres round trip
    // every time it was saved, walking a few centimetres per visit.

    private func slopePrefill(_ p: Plot) -> String {
        MeasurementFormatter.entryText(Double(p.slopeDeg), fractionDigits: 1)
    }

    private func aspectPrefill(_ p: Plot) -> String {
        MeasurementFormatter.entryText(Double(p.aspectDeg), fractionDigits: 0)
    }

    private func elevationPrefill(_ p: Plot) -> String {
        guard let m = p.groundElevationM else { return "" }
        let shown = MeasurementFormatter.elevationDisplay(m: Double(m),
                                                          in: settings.unitSystem)
        return MeasurementFormatter.entryText(shown, fractionDigits: 0)
    }

    private func canopyPrefill(_ p: Plot) -> String {
        guard let pct = p.canopyCoverPct else { return "" }
        return MeasurementFormatter.entryText(Double(pct), fractionDigits: 0)
    }

    // MARK: - What Save would write

    /// The plot the Save button would store, or nil while a field holds
    /// something that is not a site reading at all.
    ///
    /// Range checking is NOT done here — that is the model's job, and asking
    /// it once (in `siteDescriptionRejection`) is what keeps the sheet and the
    /// store from disagreeing. This only answers "did the cruiser type a
    /// number, a compass point, or nothing".
    private func edited(from stored: Plot) -> Plot? {
        var p = stored

        if slopeText == slopePrefill(stored) {
            p.slopeDeg = stored.slopeDeg
        } else if let v = TruthInput.parse(slopeText), v.isFinite {
            p.slopeDeg = Float(v)
        } else {
            return nil
        }

        if aspectText == aspectPrefill(stored) {
            p.aspectDeg = stored.aspectDeg
        } else if let deg = PlotAspect.parse(aspectText) {
            p.aspectDeg = deg
        } else {
            return nil
        }

        // Blank clears an elevation: "I don't have one for this plot" is a
        // real answer, and it is not the same as sea level.
        if elevationText == elevationPrefill(stored) {
            p.groundElevationM = stored.groundElevationM
        } else if TruthInput.normalized(elevationText).isEmpty {
            p.groundElevationM = nil
        } else if let v = TruthInput.parse(elevationText), v.isFinite {
            p.groundElevationM = Float(
                MeasurementFormatter.elevationMetres(display: v,
                                                     in: settings.unitSystem))
        } else {
            return nil
        }

        if canopyText == canopyPrefill(stored) {
            p.canopyCoverPct = stored.canopyCoverPct
        } else if TruthInput.normalized(canopyText).isEmpty {
            p.canopyCoverPct = nil
        } else if let v = TruthInput.parse(canopyText), v.isFinite {
            p.canopyCoverPct = Float(v)
        } else {
            return nil
        }

        return p
    }

    // Per-row validity, so the warning sits under the field that is wrong
    // instead of one blanket message at the foot of the section.
    private var slopeEntryValid: Bool {
        guard let stored = plot else { return true }
        return slopeText == slopePrefill(stored)
            || (TruthInput.parse(slopeText)?.isFinite ?? false)
    }

    private var aspectEntryValid: Bool {
        guard let stored = plot else { return true }
        return aspectText == aspectPrefill(stored) || PlotAspect.parse(aspectText) != nil
    }

    private var elevationEntryValid: Bool {
        guard let stored = plot else { return true }
        return elevationText == elevationPrefill(stored)
            || TruthInput.normalized(elevationText).isEmpty
            || (TruthInput.parse(elevationText)?.isFinite ?? false)
    }

    private var canopyEntryValid: Bool {
        guard let stored = plot else { return true }
        return canopyText == canopyPrefill(stored)
            || TruthInput.normalized(canopyText).isEmpty
            || (TruthInput.parse(canopyText)?.isFinite ?? false)
    }

    private var canSave: Bool {
        guard !saving, let stored = plot, let next = edited(from: stored) else {
            return false
        }
        return next.siteDescriptionRejection == nil
    }

    // MARK: - Save

    private func save() {
        guard !saving, let stored = plot, let next = edited(from: stored) else { return }
        saving = true
        do {
            let written = try environment.plotRepository.update(next)
            // Re-seed from what was STORED, not from what was typed, so a
            // sheet left open after a save shows the record and not a draft.
            seed(from: written)
            saving = false
            onSaved(written)
            dismiss()
        } catch {
            saving = false
            saveError = "\(error.localizedDescription) Nothing was changed — try again."
        }
    }
}

// MARK: - Aspect: a bearing or a compass point

/// Aspect is a compass bearing, and a cruiser says "NE" faster than they type
/// "045" with cold hands. Both spellings land on the same stored degrees, and
/// the row echoes the point back so a typed number is confirmed by name.
///
/// Sixteen points, because that is the resolution a hand compass and a
/// forester's vocabulary share. The Android sibling carries the identical
/// table and the identical rounding.
enum PlotAspect {

    /// Point name → bearing. Ordered north-clockwise so the index doubles as
    /// the 22.5° step used by `pointName`.
    static let points: [String] = [
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    ]

    /// The bearing this entry stands for, or nil when it is neither a compass
    /// point nor a bearing on the compass.
    ///
    /// 360 is accepted and stored as 0 — a cruiser writing "360" means north,
    /// and storage keeps one spelling of north. 400, though, is REFUSED
    /// rather than wrapped to 40: wrapping would turn a typo into a
    /// north-east-facing plot that nobody ever stood on.
    static func parse(_ raw: String) -> Float? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let upper = trimmed.uppercased()
        if let index = points.firstIndex(of: upper) {
            return Float(index) * 22.5
        }
        guard let v = TruthInput.parse(trimmed), v.isFinite,
              v >= 0, v <= 360 else { return nil }
        return v == 360 ? 0 : Float(v)
    }

    /// The nearest of the sixteen points to a stored bearing.
    static func pointName(_ deg: Float) -> String {
        let wrapped = deg.truncatingRemainder(dividingBy: 360)
        let positive = wrapped < 0 ? wrapped + 360 : wrapped
        let index = Int((positive / 22.5).rounded()) % points.count
        return points[index]
    }
}
