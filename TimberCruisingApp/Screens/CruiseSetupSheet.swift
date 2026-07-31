// SIMPLIFIED CRUISE SETUP (mock ⑥) — Phase B replacement for the old
// six-field CruiseDesignScreen. A bottom sheet with every field
// pre-defaulted: plot type (fixed radius / BAF), one plot-size field,
// one layout field (plot count OR grid spacing), and an optional
// stratum polygon drawn with the kept StratumDrawScreen. "Generate
// plots" runs the SAME generation engine the old screen used
// (Geo.SamplingGenerator + the planned-plot / cruise-design repos):
//
//   • polygon drawn + Grid spacing → systematic grid inside the strata;
//   • polygon drawn + Count        → stratified random, `count` per
//                                    stratum (the engine's count-based
//                                    scheme);
//   • NO polygon                   → a centred square-ish grid anchored
//     on the current map centre: `count` plots (count mode) at the
//     default 150 m pitch, or 12 plots (spacing mode) at the chosen
//     spacing — ceil(√n) columns, row-major, exactly n plots. A small
//     woodlot rarely needs a boundary before pins are useful.
//
// Planned-plot numbering continues after the project's existing real
// plots (max plotNumber + 1) so informal plots started before setup
// never collide with the plan. Generating replaces the project's
// previous planned plots and upserts the single CruiseDesign record —
// identical persistence semantics to the retired screen. Generation
// never gates measuring: planned pins are suggestions.

import SwiftUI
import Common
import Models
import Persistence
import Geo

public struct CruiseSetupSheet: View {

    @EnvironmentObject private var environment: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    private enum LayoutMode: String, CaseIterable, Identifiable {
        case count, spacing
        var id: String { rawValue }
    }

    public let project: Project
    /// Anchor for the no-polygon fallback grid — the map centre the
    /// cruiser has framed (they are looking at their woodlot).
    public let mapCentre: CoordinateConversions.LatLon
    /// The AREA this setup is for, when the sheet was opened from an
    /// outline the cruiser selected on the map ("Cruise this area").
    ///
    /// It changes two things, and nothing else. The stratum row stops
    /// offering to draw a boundary — one is already chosen, and its name
    /// and size are shown instead — and generation lays plots into THIS
    /// stratum only, replacing this area's previous plan and leaving every
    /// other area's alone. Opened from the project strip it is nil and the
    /// sheet behaves exactly as it always has: every stratum, or the
    /// centred fallback grid when there are none.
    public let area: Stratum?
    public var onGenerated: () -> Void

    public init(project: Project,
                mapCentre: CoordinateConversions.LatLon,
                area: Stratum? = nil,
                onGenerated: @escaping () -> Void = {}) {
        self.project = project
        self.mapCentre = mapCentre
        self.area = area
        self.onGenerated = onGenerated
    }

    // Defaults from the existing design model (CruiseDesign record when
    // present; otherwise the long-standing 0.1 ac / BAF 20 / 150 m
    // grid / 10-per-stratum defaults the old screen shipped with).
    @State private var plotType: PlotType = .fixedArea
    @State private var radiusText = CruiseSetupSheet.defaultRadiusText
    @State private var bafText = "20"
    @State private var layoutMode: LayoutMode = .spacing
    @State private var countText = "10"
    @State private var spacingText = "150"
    @State private var strataCount = 0
    @State private var pushingStratumDraw = false
    @State private var errorMessage: String?
    @State private var loadedExisting = false

    /// Radius of the default 0.1 ac fixed plot — the existing design
    /// model's plot-size default expressed in metres.
    private static var defaultRadiusText: String {
        String(format: "%.1f", radiusM(fromAcres: 0.1))
    }

    private static func radiusM(fromAcres acres: Double) -> Double {
        sqrt(acres * Units.squareMetersPerAcre / .pi)
    }

    /// Radius of a fixed-area plot given its area in square metres — used for
    /// the round metric default (0.05 ha) offered to metric-country cruisers.
    private static func radiusM(fromSquareMeters area: Double) -> Double {
        sqrt(area / .pi)
    }

    private static func acres(fromRadiusM radius: Double) -> Double {
        .pi * radius * radius / Units.squareMetersPerAcre
    }

    public var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("CRUISE SETUP")
                        .font(.system(size: 13, weight: .heavy))
                        .tracking(1.0)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .padding(.top, ForestixSpace.md)
                        .padding(.bottom, ForestixSpace.sm)

                    fieldHeader("Plot type")
                    Picker("Plot type", selection: $plotType) {
                        Text("Fixed radius").tag(PlotType.fixedArea)
                        // Named for the cruising method, not the factor: the
                        // segment is shown BEFORE the "Prism basal-area
                        // factor" caption exists (that caption only renders
                        // once this mode is picked), so "BAF" here asked the
                        // cruiser to choose blind. The unit on the value
                        // below stays "BAF", where a cruise sheet expects it.
                        Text("Variable radius (prism)").tag(PlotType.variableRadius)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("cruiseSetup.plotType")
                    .padding(.bottom, ForestixSpace.sm)

                    fieldHeader("Plot size")
                    numericRow(
                        value: plotType == .fixedArea ? $radiusText : $bafText,
                        unit: plotType == .fixedArea ? "m radius" : "BAF",
                        caption: plotType == .fixedArea
                            ? sizeCaptionFixed
                            : "Prism basal-area factor",
                        accessibilityID: "cruiseSetup.plotSize")
                    .padding(.bottom, ForestixSpace.sm)

                    Picker("Layout", selection: $layoutMode) {
                        Text("Count").tag(LayoutMode.count)
                        Text("Grid spacing").tag(LayoutMode.spacing)
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("cruiseSetup.layoutMode")
                    .padding(.bottom, ForestixSpace.xs)

                    fieldHeader(layoutMode == .count ? "Plots" : "Spacing")
                    numericRow(
                        value: layoutMode == .count ? $countText : $spacingText,
                        unit: layoutMode == .count ? "plots" : "m",
                        caption: layoutMode == .count
                            ? "How many plot centres to lay out"
                            : "Distance between plot centres",
                        accessibilityID: "cruiseSetup.layoutValue")
                    .padding(.bottom, ForestixSpace.sm)

                    stratumRow

                    Text("Defaults work for most woodlots.")
                        .font(ForestixType.caption)
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, ForestixSpace.sm)

                    Button {
                        generate()
                    } label: {
                        Text("Generate plots")
                            .font(.system(size: 15, weight: .bold))
                            .foregroundStyle(ForestixPalette.primaryInk)
                            .frame(maxWidth: .infinity, minHeight: 54)
                            .background(
                                RoundedRectangle(cornerRadius: ForestixRadius.card,
                                                 style: .continuous)
                                    .fill(ForestixPalette.primary))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("cruiseSetup.generate")
                    .padding(.top, ForestixSpace.xs)

                    Text("Shown as hollow pins. You can measure them any time.")
                        .font(.system(size: 11.5))
                        .foregroundStyle(ForestixPalette.textTertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, ForestixSpace.xs)

                    Spacer(minLength: ForestixSpace.lg)
                }
                .padding(.horizontal, ForestixSpace.md)
            }
            .background(ForestixPalette.surface.ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: $pushingStratumDraw) {
                StratumDrawScreen(project: project)
            }
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(ForestixPalette.surface)
        .onAppear {
            refreshStrata()
            applyInitialDefaults()
        }
        .onChange(of: pushingStratumDraw) { _, pushing in
            if !pushing { refreshStrata() }
        }
        .alert("Couldn't generate plots",
               isPresented: Binding(
                   get: { errorMessage != nil },
                   set: { if !$0 { errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
    }

    // MARK: Rows

    private func fieldHeader(_ title: String) -> some View {
        Text(title)
            .font(.system(size: 14.5, weight: .bold))
            .foregroundStyle(ForestixPalette.textPrimary)
            .padding(.bottom, 6)
    }

    private var sizeCaptionFixed: String {
        if let r = Double(radiusText), r > 0 {
            return String(format: "≈ %.2f ha · %.2f ac",
                          Self.acres(fromRadiusM: r) * 0.404685642,
                          Self.acres(fromRadiusM: r))
        }
        return "Circular fixed-area plot"
    }

    private func numericRow(value: Binding<String>,
                            unit: String,
                            caption: String,
                            accessibilityID: String) -> some View {
        HStack(spacing: ForestixSpace.sm) {
            Text(caption)
                .font(.system(size: 11.5))
                .foregroundStyle(ForestixPalette.textTertiary)
                .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 6) {
                TextField("0", text: value)
                    #if os(iOS)
                    .keyboardType(.decimalPad)
                    #endif
                    .multilineTextAlignment(.trailing)
                    .font(.system(size: 14, weight: .bold, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textPrimary)
                    .frame(width: 76)
                    .accessibilityIdentifier(accessibilityID)
                Text(unit)
                    .font(.system(size: 11, weight: .semibold, design: .monospaced))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(ForestixPalette.surfaceRaised))
            .overlay(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .stroke(ForestixPalette.divider, lineWidth: 1))
        }
        .padding(.vertical, 4)
    }

    /// Optional stratum polygon — "Draw boundary" pushes the kept
    /// StratumDrawScreen; with none drawn, generation falls back to the
    /// centred default pattern around the map centre.
    ///
    /// Opened from an area on the map the row has nothing to offer: the
    /// outline is already chosen, so it states which one and how big it is
    /// and gets out of the way. A "Draw boundary" button there would offer
    /// to change the very thing the cruiser opened this sheet to cruise.
    @ViewBuilder
    private var stratumRow: some View {
        HStack(spacing: ForestixSpace.sm) {
            VStack(alignment: .leading, spacing: 2) {
                // "polygon" is the GIS word for the shape; the button
                // beside this row already says "Draw boundary" and the
                // state line below says "None drawn", so the label was
                // the only place the GIS term appeared.
                Text(area == nil ? "Stratum boundary" : "Area")
                    .font(.system(size: 14.5, weight: .bold))
                    .foregroundStyle(ForestixPalette.textPrimary)
                // FIELD REPORT F7 — the field is named, not qualified. The
                // word "Optional" told the cruiser nothing they could act on;
                // "None drawn" says what the state actually is.
                Text(areaRowDetail)
                    .font(.system(size: 11.5))
                    .foregroundStyle(ForestixPalette.textTertiary)
            }
            Spacer(minLength: 4)
            if area == nil {
                Button {
                    pushingStratumDraw = true
                } label: {
                    Text("Draw boundary")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(ForestixPalette.textPrimary)
                        .padding(.horizontal, 12)
                        .frame(minHeight: 38)
                        .background(
                            RoundedRectangle(cornerRadius: ForestixRadius.control,
                                             style: .continuous)
                                .stroke(ForestixPalette.divider, lineWidth: 1))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("cruiseSetup.drawBoundary")
            }
        }
        .padding(.vertical, ForestixSpace.xs)
        .overlay(alignment: .top) {
            Rectangle().fill(ForestixPalette.divider).frame(height: 0.5)
        }
    }

    private var areaRowDetail: String {
        if let area {
            let acres = Double(area.areaAcres)
            return String(format: "%@ · %.2f ha · %.2f ac",
                          area.name, acres * 0.404685642, acres)
        }
        return strataCount == 0
            ? "None drawn — whole area"
            : "\(strataCount) boundar\(strataCount == 1 ? "y" : "ies") drawn"
    }

    // MARK: Data

    private func refreshStrata() {
        strataCount = (try? environment.stratumRepository
            .listByProject(project.id).count) ?? 0
    }

    /// One-time initial defaults: prefill from the last saved design if one
    /// exists, otherwise fall back to a round default plot size — the imperial
    /// 0.1-acre default (already baked into `radiusText`) for the US, or a
    /// round metric 0.05 ha for metric-country projects.
    private func applyInitialDefaults() {
        guard !loadedExisting else { return }
        let appliedStoredRadius = prefillFromExistingDesign()
        if !appliedStoredRadius && project.units == .metric {
            radiusText = String(format: "%.1f",
                                Self.radiusM(fromSquareMeters: 500))  // 0.05 ha
        }
    }

    /// Re-opening setup shows what was generated last time — same
    /// prefill behaviour the old screen's refresh() had. Returns true when a
    /// saved plot size was applied to `radiusText`.
    @discardableResult
    private func prefillFromExistingDesign() -> Bool {
        guard !loadedExisting else { return false }
        loadedExisting = true
        guard let design = (try? environment.cruiseDesignRepository
            .forProject(project.id))?.first else { return false }
        plotType = design.plotType
        var appliedStoredRadius = false
        if let a = design.plotAreaAcres {
            radiusText = String(format: "%.1f", Self.radiusM(fromAcres: Double(a)))
            appliedStoredRadius = true
        }
        if let b = design.baf {
            bafText = String(format: "%.0f", b)
        }
        switch design.samplingScheme {
        case .systematicGrid:
            layoutMode = .spacing
            if let g = design.gridSpacingMeters {
                spacingText = String(format: "%.0f", g)
            }
        case .stratifiedRandom:
            layoutMode = .count
        case .manual:
            break
        }
        return appliedStoredRadius
    }

    // MARK: Generation (the existing engine)

    private func generate() {
        // Validate the three visible fields.
        let radiusM = Double(radiusText) ?? 0
        let baf = Double(bafText) ?? 0
        let count = Int(countText) ?? 0
        let spacing = Double(spacingText) ?? 0
        if plotType == .fixedArea && radiusM <= 0 {
            errorMessage = "Plot radius must be a positive number of metres."
            return
        }
        if plotType == .variableRadius && baf <= 0 {
            errorMessage = "Basal area factor must be a positive number."
            return
        }
        if layoutMode == .count && count <= 0 {
            errorMessage = "Plot count must be a positive whole number."
            return
        }
        if layoutMode == .spacing && spacing <= 0 {
            errorMessage = "Grid spacing must be a positive number of metres."
            return
        }

        do {
            // Opened from an area, that area is the only stratum in play.
            let strata = try area.map { [$0] }
                ?? environment.stratumRepository.listByProject(project.id)
            // Which plans this run REPLACES. From an area it is that
            // area's own plans and nobody else's: generating into one
            // outline must not wipe the plan laid inside another, which is
            // the whole-project rule and would be a silent loss of work
            // the cruiser never asked to touch.
            let allPlanned = try environment.plannedPlotRepository
                .listByProject(project.id)
            let replacing = area.map { seed in
                allPlanned.filter { $0.stratumId == seed.id }
            } ?? allPlanned
            let surviving = allPlanned.filter { plan in
                !replacing.contains { $0.id == plan.id }
            }
            // Continue numbering after every number already spoken for —
            // the project's REAL plots, so informal plots and the plan
            // never share a number, and the plans that are surviving this
            // run, so two areas never both hold a "Plot 4".
            let startNumber = max(
                (try? environment.plotRepository
                    .listByProject(project.id))?.map(\.plotNumber).max() ?? 0,
                surviving.map(\.plotNumber).max() ?? 0) + 1

            let planned: [PlannedPlot]
            if strata.isEmpty {
                planned = Self.centredGrid(
                    projectId: project.id,
                    anchor: mapCentre,
                    count: layoutMode == .count ? count : 12,
                    spacingM: layoutMode == .spacing ? spacing : 150,
                    startingPlotNumber: startNumber)
            } else {
                let inputs: [SamplingGenerator.StratumInput] = try strata.map {
                    .init(stratumId: $0.id,
                          rings: try Self.parseRings(from: $0.polygonGeoJSON))
                }
                let options = SamplingGenerator.GenerationOptions(
                    projectId: project.id,
                    scheme: layoutMode == .spacing ? .systematicGrid
                                                   : .stratifiedRandom,
                    gridSpacingMeters: layoutMode == .spacing ? spacing : nil,
                    nPerStratum: layoutMode == .count ? count : nil,
                    seed: 1)
                planned = try SamplingGenerator.generate(
                    strata: inputs, options: options,
                    startingPlotNumber: startNumber)
            }

            guard !planned.isEmpty else {
                // The sheet calls this thing a "boundary" everywhere else
                // ("Stratum boundary", "Draw boundary", "None drawn — whole
                // area"), so the fix names the same two controls the
                // cruiser would go back to. Nothing about the generator
                // changed — only the sentence when it returns nothing.
                errorMessage = area == nil
                    ? "No plot centres fell inside the boundary — try a tighter spacing or a bigger area."
                    : "No plot centres fell inside this area — try a tighter spacing, or make the area bigger."
                return
            }

            // Replace the plans this run is for (whole project, or just
            // this area's — see `replacing`).
            for p in replacing {
                try environment.plannedPlotRepository.delete(id: p.id)
            }
            for p in planned {
                _ = try environment.plannedPlotRepository.create(p)
            }

            // Upsert the single CruiseDesign record for the project.
            let designs = try environment.cruiseDesignRepository
                .forProject(project.id)
            let design = CruiseDesign(
                id: designs.first?.id ?? UUID(),
                projectId: project.id,
                plotType: plotType,
                plotAreaAcres: plotType == .fixedArea
                    ? Float(Self.acres(fromRadiusM: radiusM)) : nil,
                baf: plotType == .variableRadius ? Float(baf) : nil,
                samplingScheme: layoutMode == .spacing ? .systematicGrid
                                                       : .stratifiedRandom,
                gridSpacingMeters: layoutMode == .spacing
                    ? Float(spacing) : nil)
            if designs.first != nil {
                _ = try environment.cruiseDesignRepository.update(design)
            } else {
                _ = try environment.cruiseDesignRepository.create(design)
            }

            HapticFeedback.play(.success)
            onGenerated()
            dismiss()
        } catch {
            errorMessage = "Plot generation failed: \(error)"
        }
    }

    /// No-polygon default pattern: a centred, walkable square-ish grid
    /// around the anchor — ceil(√n) columns, rows top-to-bottom, exactly
    /// n plots at `spacingM` pitch.
    static func centredGrid(projectId: UUID,
                            anchor: CoordinateConversions.LatLon,
                            count: Int,
                            spacingM: Double,
                            startingPlotNumber: Int) -> [PlannedPlot] {
        guard count > 0, spacingM > 0 else { return [] }
        let cols = Int(ceil(sqrt(Double(count))))
        let rows = Int(ceil(Double(count) / Double(cols)))
        var out: [PlannedPlot] = []
        out.reserveCapacity(count)
        for i in 0..<count {
            let row = i / cols
            let col = i % cols
            let east = (Double(col) - Double(cols - 1) / 2) * spacingM
            let north = (Double(rows - 1) / 2 - Double(row)) * spacingM
            let ll = CoordinateConversions.toLatLon(
                enu: .init(east: east, north: north), origin: anchor)
            out.append(PlannedPlot(
                id: UUID(),
                projectId: projectId,
                stratumId: nil,
                plotNumber: startingPlotNumber + i,
                plannedLat: ll.latitude,
                plannedLon: ll.longitude,
                visited: false))
        }
        return out
    }

    /// Stored stratum GeoJSON → rings (extracted from the retired
    /// CruiseDesignViewModel — the generator's polygon input format).
    static func parseRings(
        from geojson: String
    ) throws -> [[CoordinateConversions.LatLon]] {
        guard let data = geojson.data(using: .utf8),
              let obj = try JSONSerialization.jsonObject(with: data)
                  as? [String: Any]
        else {
            throw NSError(domain: "CruiseSetup", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Invalid stored polygon JSON"])
        }
        guard obj["type"] as? String == "Polygon",
              let coords = obj["coordinates"] as? [[[Double]]]
        else {
            throw NSError(domain: "CruiseSetup", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "Stored geometry is not a Polygon"])
        }
        return coords.map { ring in
            ring.compactMap { pair in
                guard pair.count >= 2 else { return nil }
                return CoordinateConversions.LatLon(latitude: pair[1],
                                                    longitude: pair[0])
            }
        }
    }
}
