// The field log's summary card, AS A FUNCTION OF THE FILTER.
//
// It used to be a card about one special case: whichever quick-measure plot
// happened to be active, and nothing else. Pick a cruise plot in the filter
// and the card either vanished or — worse — went on describing a different
// plot than every row beneath it. The filter is the screen's one statement
// of what the cruiser is looking at, so the card now follows it:
//
//   • one QUICK plot    → that plot's readings
//   • one CRUISE plot   → that plot's PlotStats
//   • one CRUISE project→ that project's stand statistics
//   • Everything        → whatever "everything" unambiguously is: the one
//                         stand, when there is exactly one and no quick
//                         readings sit outside it, and otherwise a COUNT of
//                         what the log holds.
//
// THE EVERYTHING BRANCH USED TO RETURN NOTHING, and that reproduced the
// complaint this file was written to answer: the log opens on Everything, so
// the cruiser opened the field log and saw no summary at all until they
// worked out that the funnel was where summaries came from. "No card" also
// says the same thing as "nothing measured", which is a different fact.
//
// What it must NOT do is add the two stores' densities together. A quick
// plot's per-acre figure is divided by an assumed tenth of an acre and a
// cruise plot's is expanded by the design's own factor; those are not the
// same quantity and their sum is a number about nothing. So the Everything
// card carries COUNTS, which are basis-free and additive, and says in one
// line where the computed values are. When the log holds exactly one stand
// and nothing outside it, "everything" IS that stand, and the cruiser gets
// the real figures without touching the filter.
//
// NOTHING HERE COMPUTES A CRUISE NUMBER. The cruise branches build the same
// view models the cruise screens build — `PlotSummaryViewModel` for a plot,
// `StandSummaryViewModel` for a project — call `refresh()` and read the
// result. That is deliberate: this app has already been bitten by a figure
// computed in two places, and a stand table in the field log that disagreed
// with the stand table on the stand-summary screen would be exactly that bug
// with a friendlier face. The population is the same too — a project card is
// the project's CLOSED plots, because that is what the stand summary
// aggregates, and matching screens matter more here than a fuller-looking
// card mid-cruise.
//
// The QUICK branch is the math that used to live inside `PlotSummaryCard`,
// moved here unchanged so the card became a renderer and all three branches
// arrive at it in one shape.
//
// The Android sibling is ui/screens/FieldLogSummary.kt — same branches, same
// strings, same reuse.

import Foundation
import Models
import Common
import Sensors
import Persistence
import InventoryEngine

/// One summary, ready to draw. Every string is finished here so the card and
/// the detail view cannot format the same number two ways.
public struct FieldLogSummary: Equatable {

    /// One cell of the card's stat row.
    public struct Cell: Equatable {
        public let label: String
        public let value: String
    }

    /// One species and its share of the trees behind this summary.
    public struct Species: Equatable {
        public let code: String
        public let share: Double
    }

    /// One line of the detail view.
    public struct Row: Equatable {
        public let label: String
        public let value: String
    }

    /// A titled block of the detail view.
    public struct Group: Equatable {
        public let title: String
        public let rows: [Row]
    }

    /// The all-caps heading on the card, and the thing the cruiser taps to
    /// open the detail view.
    public let heading: String
    public let title: String
    public let subtitle: String?
    /// Four cells, always — the card's row is a fixed grid on both platforms.
    public let cells: [Cell]
    public let speciesMix: [Species]
    /// The full set of computed values, and the settings that produced them.
    public let groups: [Group]
    /// Why there is nothing to compute, when there is nothing to compute.
    /// The cells then read "—" rather than 0, which is a measurement.
    public let note: String?

    /// Headings, spelled where both platforms can read them off one line.
    public static let plotHeading = "PLOT SUMMARY"
    /// A project is a STAND — the same word the screen that computes these
    /// numbers already uses, so a cruiser reading the two knows they are the
    /// same figures.
    public static let standHeading = "STAND SUMMARY"
    /// Shown on a project card whose project has closed no plots. The stand
    /// summary's own sentence, verbatim.
    public static let noClosedPlots = "No closed plots yet."
    /// A plot with readings but nothing to compute from them.
    public static let nothingMeasured = "Nothing measured in this plot yet."
    /// The unfiltered scope's heading and title.
    public static let logHeading = "LOG SUMMARY"
    public static let everythingTitle = "Everything measured"
    /// Said on the unfiltered card, because counts are all it can honestly
    /// carry and the computed values are one tap away.
    public static let pickAScope =
        "Pick a plot or a stand in the filter for computed values."
    /// The detail view's title, and the hint on the card's heading.
    public static let detailTitle = "How this was computed"
}

// MARK: - Builder

@MainActor
public enum FieldLogSummaryBuilder {

    /// The summary for the scope the log is showing, or nil when the scope
    /// names no single plot or project.
    public static func make(scope: FieldLogScope,
                            history: QuickMeasureHistory,
                            settings: AppSettings,
                            environment: AppEnvironment,
                            cruiseFeed: FieldLogCruiseFeed) -> FieldLogSummary? {
        switch scope {
        case .everything:
            return everything(history: history, settings: settings,
                              environment: environment, cruiseFeed: cruiseFeed)
        case .quickPlot(let id):
            guard let plot = history.plot(id: id) else { return nil }
            return quick(plot: plot,
                         entries: history.entries(forPlot: id),
                         settings: settings)
        case .cruisePlot(let id):
            guard let plot = cruiseFeed.plot(id: id),
                  let project = cruiseFeed.project(ofPlot: id) else { return nil }
            return cruisePlot(plot: plot, project: project,
                              settings: settings, environment: environment)
        case .cruiseProject(let id):
            guard let project = cruiseFeed.projects.first(where: { $0.id == id })
            else { return nil }
            return cruiseProject(project: project,
                                 settings: settings, environment: environment)
        }
    }

    // MARK: Everything

    /// The unfiltered scope: the one stand if that is unambiguous, else counts.
    ///
    /// The delegation matters more than the counts do. A cruiser running one
    /// project and nothing else — the ordinary case — opens the field log and
    /// gets that stand's real figures without learning that the funnel is
    /// where summaries live. The counts are the fallback for a log that holds
    /// more than one thing, and they are counts precisely because the two
    /// stores' per-area figures cannot be added: see the note at the top.
    private static func everything(history: QuickMeasureHistory,
                                   settings: AppSettings,
                                   environment: AppEnvironment,
                                   cruiseFeed: FieldLogCruiseFeed) -> FieldLogSummary? {
        let quickEntries = history.entries.count
        let cruiseTrees = cruiseFeed.rowsByPlot.values.reduce(0) { $0 + $1.count }
        let cruisePlots = cruiseFeed.plotsByProject.values.reduce(0) { $0 + $1.count }

        // Exactly one stand, and nothing measured outside it: "everything" and
        // "that stand" name the same set, so show the stand.
        if cruiseFeed.projects.count == 1, quickEntries == 0,
           let only = cruiseFeed.projects.first {
            return cruiseProject(project: only, settings: settings,
                                 environment: environment)
        }

        // An empty log gets no card. Here — and only here — "nothing at all"
        // is genuinely nothing to summarise, and a row of zeroes would read as
        // a measurement.
        if quickEntries == 0 && cruiseTrees == 0 { return nil }

        let cells = [
            FieldLogSummary.Cell(label: "TREES",
                                 value: "\(quickEntries + cruiseTrees)"),
            FieldLogSummary.Cell(label: "STANDS",
                                 value: "\(cruiseFeed.projects.count)"),
            FieldLogSummary.Cell(label: "CRUISE PLOTS", value: "\(cruisePlots)"),
            FieldLogSummary.Cell(label: "QUICK PLOTS",
                                 value: "\(history.plots.count)")]

        return FieldLogSummary(
            heading: FieldLogSummary.logHeading,
            title: FieldLogSummary.everythingTitle,
            subtitle: nil,
            cells: cells,
            speciesMix: [],
            groups: [
                .init(title: "What the log holds", rows: [
                    .init(label: "Cruise trees", value: "\(cruiseTrees)"),
                    .init(label: "Cruise plots", value: "\(cruisePlots)"),
                    .init(label: "Stands", value: "\(cruiseFeed.projects.count)"),
                    .init(label: "Quick readings", value: "\(quickEntries)"),
                    .init(label: "Quick plots", value: "\(history.plots.count)")]),
                .init(title: "Behind these numbers", rows: [
                    .init(label: "Why counts",
                          value: "A quick plot's per-area figure is divided by an assumed area and a cruise plot's is expanded by the design's factor. They are not the same quantity, so they are not added."),
                    .init(label: "Board-foot log rule", value: settings.logRule.displayName),
                    .init(label: "Density basis",
                          value: settings.unitSystem.areaUnit.basisPhrase)])],
            note: FieldLogSummary.pickAScope)
    }

    // MARK: Quick

    /// The quick-measure branch — the math `PlotSummaryCard` used to hold.
    ///
    /// It divides by `max(acres ?? 0.1, 0.05)`, which means a plot with no
    /// acreage entered is being divided by an INVENTED tenth of an acre. That
    /// was true before and is unchanged; what is new is that the detail view
    /// says so on the "Plot area" line, so a density read off this card can be
    /// recognised as relative rather than absolute.
    private static func quick(plot: QuickMeasurePlot,
                              entries: [QuickMeasureEntry],
                              settings: AppSettings) -> FieldLogSummary {
        let areaUnit = settings.unitSystem.areaUnit
        let factor = areaUnit.perAcreDensityFactor
        let stats = QuickPlotStats.compute(plot: plot, entries: entries,
                                           areaUnit: areaUnit,
                                           logRule: settings.logRule)

        var subtitleParts: [String] = []
        if !plot.unitName.isEmpty { subtitleParts.append(plot.unitName) }
        if let ac = plot.acres {
            subtitleParts.append(String(format: "%.2f %@",
                                        areaUnit.fromAcres(ac), areaUnit.abbreviation))
        }

        // The basal-area numerator follows the areal basis: ft² per acre for a
        // US cruise, m² per hectare for a metric one. It is IN THE VALUE now
        // rather than left to the label, because a cruise plot's basal area on
        // the same card is m² whatever the basis, and two numbers an order of
        // magnitude apart under one bare "BASAL/AC" heading is how a cruiser
        // reads a stand as ten times denser than it is.
        let baUnit = areaUnit == .hectare ? "m²" : "ft²"
        let cells = [
            FieldLogSummary.Cell(label: "TREES",
                                 value: stats.map { "\($0.treeCount)" } ?? "—"),
            FieldLogSummary.Cell(label: areaUnit.densityLabel("BASAL").uppercased(),
                                 value: stats.map {
                                     String(format: "%.0f %@", $0.baPerAcre * factor, baUnit)
                                 } ?? "—"),
            FieldLogSummary.Cell(label: treesPerAreaLabel(areaUnit),
                                 value: stats.map {
                                     String(format: "%.0f", $0.tpa * factor)
                                 } ?? "—"),
            FieldLogSummary.Cell(label: "MEAN DBH",
                                 value: stats.map {
                                     MeasurementFormatter.diameter(cm: $0.qmdCm,
                                                                   in: settings.unitSystem)
                                 } ?? "—")]

        var computed: [FieldLogSummary.Row] = []
        if let stats {
            computed = [
                .init(label: "Trees with a diameter", value: "\(stats.treeCount)"),
                .init(label: "Basal area",
                      value: String(format: "%.1f %@%@", stats.baPerAcre * factor,
                                    baUnit, areaUnit.densitySuffix)),
                .init(label: "Trees",
                      value: String(format: "%.0f %@", stats.tpa * factor,
                                    areaUnit.densitySuffix)),
                .init(label: "Quadratic mean diameter",
                      value: MeasurementFormatter.diameter(cm: stats.qmdCm,
                                                           in: settings.unitSystem)),
                .init(label: "Mean height",
                      value: stats.meanHeightM.map {
                          MeasurementFormatter.height(m: $0, in: settings.unitSystem)
                      } ?? "—"),
                // Board feet is the one volume the quick world can produce —
                // it needs a diameter, a height and a log rule, so a plot with
                // no heights on it reads "—" rather than 0.
                .init(label: "Board feet",
                      value: stats.boardFeetPerAcre.map {
                          String(format: "%.0f bf%@", $0 * factor, areaUnit.densitySuffix)
                      } ?? "—")]
        }

        let areaRow = plot.acres.map {
            String(format: "%.2f %@", areaUnit.fromAcres($0), areaUnit.abbreviation)
        } ?? String(format: "Not entered — %.2f %@ assumed",
                    areaUnit.fromAcres(QuickPlotStats.assumedAcres),
                    areaUnit.abbreviation)

        return FieldLogSummary(
            heading: FieldLogSummary.plotHeading,
            title: plot.name,
            subtitle: subtitleParts.isEmpty ? nil
                : subtitleParts.joined(separator: FieldLogWords.headingSeparator),
            cells: cells,
            speciesMix: stats?.speciesMix ?? [],
            groups: [
                .init(title: "Computed for this plot", rows: computed),
                .init(title: "Behind these numbers", rows: [
                    .init(label: "Plot area", value: areaRow),
                    .init(label: "Board-foot log rule", value: settings.logRule.displayName),
                    .init(label: "Density basis", value: areaUnit.basisPhrase),
                    .init(label: "Trees", value: "One per tree number, taking its newest diameter and height.")])],
            note: stats == nil ? FieldLogSummary.nothingMeasured : nil)
    }

    // MARK: Cruise plot

    private static func cruisePlot(plot: Plot,
                                   project: Project,
                                   settings: AppSettings,
                                   environment: AppEnvironment) -> FieldLogSummary {
        let areaUnit = settings.unitSystem.areaUnit
        let factor = areaUnit.perAcreDensityFactor
        let design = CruiseDesignFallback.effective(
            forProjectID: project.id, repository: environment.cruiseDesignRepository)
        // The plot-details screen's own view model, refreshed and read. No
        // second computation of TPA, basal area, QMD or volume exists.
        let viewModel = PlotSummaryViewModel(
            project: project, design: design, plot: plot,
            plotRepo: environment.plotRepository,
            treeRepo: environment.treeRepository,
            speciesRepo: environment.speciesRepository,
            volRepo: environment.volumeEquationRepository,
            hdFitRepo: environment.hdFitRepository)
        viewModel.refresh()
        let stats = viewModel.stats
        let empty = stats.liveTreeCount == 0
        let pending = settings.country.volumeStandard.isPending

        let cells = [
            FieldLogSummary.Cell(label: "TREES",
                                 value: empty ? "—" : "\(stats.liveTreeCount)"),
            FieldLogSummary.Cell(label: areaUnit.densityLabel("BASAL").uppercased(),
                                 value: empty ? "—"
                                     : String(format: "%.1f m²",
                                              Double(stats.baPerAcreM2) * factor)),
            FieldLogSummary.Cell(label: treesPerAreaLabel(areaUnit),
                                 value: empty ? "—"
                                     : String(format: "%.0f", Double(stats.tpa) * factor)),
            FieldLogSummary.Cell(label: areaUnit.densityLabel("VOLUME").uppercased(),
                                 value: empty || pending ? "—"
                                     : String(format: "%.1f m³",
                                              Double(stats.grossVolumePerAcreM3) * factor))]

        var computed: [FieldLogSummary.Row] = []
        if !empty {
            computed = [
                .init(label: "Live trees", value: "\(stats.liveTreeCount)"),
                .init(label: "Basal area",
                      value: String(format: "%.2f %@", Double(stats.baPerAcreM2) * factor,
                                    areaUnit.densityLabel("m²"))),
                .init(label: "Trees",
                      value: String(format: "%.1f %@", Double(stats.tpa) * factor,
                                    areaUnit.densitySuffix)),
                .init(label: "Quadratic mean diameter",
                      value: MeasurementFormatter.diameter(cm: Double(stats.qmdCm),
                                                           in: settings.unitSystem)),
                .init(label: "Gross volume",
                      value: pending ? volumePendingText
                          : String(format: "%.1f %@",
                                   Double(stats.grossVolumePerAcreM3) * factor,
                                   areaUnit.densityLabel("m³"))),
                .init(label: "Merchantable volume",
                      value: pending ? volumePendingText
                          : String(format: "%.1f %@",
                                   Double(stats.merchVolumePerAcreM3) * factor,
                                   areaUnit.densityLabel("m³")))]
        }

        return FieldLogSummary(
            heading: FieldLogSummary.plotHeading,
            title: FieldLogWords.plotName(number: plot.plotNumber),
            subtitle: project.name + FieldLogWords.headingSeparator
                + designPhrase(design, areaUnit: areaUnit),
            cells: cells,
            speciesMix: mix(stats.bySpecies.mapValues(\.count)),
            groups: [
                .init(title: "Computed for this plot", rows: computed),
                .init(title: "Behind these numbers",
                      rows: cruiseSettingsRows(design: design,
                                               speciesCodes: Set(stats.bySpecies.keys),
                                               speciesByCode: viewModel.speciesByCode,
                                               areaUnit: areaUnit,
                                               settings: settings,
                                               environment: environment))],
            note: empty ? FieldLogSummary.nothingMeasured : nil)
    }

    // MARK: Cruise project

    private static func cruiseProject(project: Project,
                                      settings: AppSettings,
                                      environment: AppEnvironment) -> FieldLogSummary {
        let areaUnit = settings.unitSystem.areaUnit
        let factor = areaUnit.perAcreDensityFactor
        let design = CruiseDesignFallback.effective(
            forProjectID: project.id, repository: environment.cruiseDesignRepository)
        // The stand-summary screen's own view model, refreshed and read —
        // same closed plots, same stratification, same means.
        let viewModel = StandSummaryViewModel(
            project: project, design: design,
            plotRepo: environment.plotRepository,
            treeRepo: environment.treeRepository,
            speciesRepo: environment.speciesRepository,
            volRepo: environment.volumeEquationRepository,
            hdFitRepo: environment.hdFitRepository,
            stratumRepo: environment.stratumRepository,
            plannedRepo: environment.plannedPlotRepository)
        viewModel.refresh()
        let plots = viewModel.closedPlots
        let empty = plots.isEmpty
        let pending = settings.country.volumeStandard.isPending
        let tpa = viewModel.tpaStat.scaledPerArea(by: factor)
        let ba = viewModel.baStat.scaledPerArea(by: factor)
        let volume = viewModel.volStat.scaledPerArea(by: factor)

        let cells = [
            FieldLogSummary.Cell(label: "TREES",
                                 value: empty ? "—" : "\(viewModel.totalLiveTreeCount)"),
            FieldLogSummary.Cell(label: areaUnit.densityLabel("BASAL").uppercased(),
                                 value: empty ? "—" : String(format: "%.1f m²", ba.mean)),
            FieldLogSummary.Cell(label: treesPerAreaLabel(areaUnit),
                                 value: empty ? "—" : String(format: "%.0f", tpa.mean)),
            FieldLogSummary.Cell(label: areaUnit.densityLabel("VOLUME").uppercased(),
                                 value: empty || pending ? "—"
                                     : String(format: "%.1f m³", volume.mean))]

        var computed: [FieldLogSummary.Row] = []
        if !empty {
            computed = [
                .init(label: "Closed plots", value: "\(plots.count)"),
                .init(label: "Live trees", value: "\(viewModel.totalLiveTreeCount)"),
                .init(label: "Trees",
                      value: confidenceText(tpa.mean, tpa.ci95HalfWidth,
                                            decimals: 1, unit: areaUnit.densitySuffix)),
                .init(label: "Basal area",
                      value: confidenceText(ba.mean, ba.ci95HalfWidth,
                                            decimals: 2, unit: " " + areaUnit.densityLabel("m²"))),
                .init(label: "Gross volume",
                      value: pending ? volumePendingText
                          : confidenceText(volume.mean, volume.ci95HalfWidth,
                                           decimals: 1,
                                           unit: " " + areaUnit.densityLabel("m³")))]
        }

        // Species counts summed over the plots that were averaged, so the mix
        // describes the same trees the figures above it do.
        var counts: [String: Int] = [:]
        for row in viewModel.perPlotStats {
            for (code, stat) in row.stats.bySpecies {
                counts[code, default: 0] += stat.count
            }
        }

        var settingsRows = cruiseSettingsRows(
            design: design, speciesCodes: Set(counts.keys),
            speciesByCode: [:], areaUnit: areaUnit,
            settings: settings, environment: environment)
        // A project average is a plot mean, not a tree mean. Which plots went
        // into it is the first thing a cruiser questioning the number asks.
        settingsRows.insert(
            .init(label: "Averaged over",
                  value: empty ? FieldLogSummary.noClosedPlots
                      : "The project's \(plots.count) closed plot\(plots.count == 1 ? "" : "s"), each weighted by its stratum's area."),
            at: 1)

        return FieldLogSummary(
            heading: FieldLogSummary.standHeading,
            title: project.name,
            subtitle: "\(plots.count) closed plot(s) · \(viewModel.totalLiveTreeCount) live trees",
            cells: cells,
            speciesMix: mix(counts),
            groups: [
                .init(title: "Computed across closed plots", rows: computed),
                .init(title: "Behind these numbers", rows: settingsRows)],
            note: empty ? FieldLogSummary.noClosedPlots : nil)
    }

    // MARK: Shared pieces

    /// Korea ships as a scaffold: the NIFoS coefficients are not in the app
    /// yet, so a volume there would be a fabricated 0. Same sentence the
    /// stand-summary screen prints.
    private static let volumePendingText =
        "Not available for this region yet."

    private static func treesPerAreaLabel(_ areaUnit: AreaUnit) -> String {
        areaUnit == .hectare ? "TREES/HA" : "TREES/AC"
    }

    private static func confidenceText(_ mean: Double, _ halfWidth: Double,
                                       decimals: Int, unit: String) -> String {
        String(format: "%.\(decimals)f%@ ± %.\(decimals)f (95%% confidence)",
               mean, unit, halfWidth)
    }

    /// Shares by tree count, biggest first — the same rule the quick branch
    /// uses, so one card cannot be read two ways.
    private static func mix(_ counts: [String: Int]) -> [FieldLogSummary.Species] {
        let total = counts.values.reduce(0, +)
        guard total > 0 else { return [] }
        return counts
            .map { FieldLogSummary.Species(code: $0.key,
                                           share: Double($0.value) / Double(total)) }
            .sorted { $0.share == $1.share ? $0.code < $1.code : $0.share > $1.share }
    }

    private static func designPhrase(_ design: CruiseDesign,
                                     areaUnit: AreaUnit) -> String {
        switch design.plotType {
        case .fixedArea:
            guard let acres = design.plotAreaAcres else { return "Fixed-area" }
            return String(format: "Fixed-area · %.2f %@",
                          areaUnit.fromAcres(Double(acres)), areaUnit.abbreviation)
        case .variableRadius:
            guard let baf = design.baf else { return "Variable-radius" }
            return String(format: "Variable-radius · BAF %.0f", Double(baf))
        }
    }

    /// The settings a cruise figure rests on. Named rather than assumed: a
    /// volume with no equation behind it is 0, and a 0 that looks like a
    /// measurement is the failure this block exists to prevent.
    private static func cruiseSettingsRows(design: CruiseDesign,
                                           speciesCodes: Set<String>,
                                           speciesByCode: [String: SpeciesConfig],
                                           areaUnit: AreaUnit,
                                           settings: AppSettings,
                                           environment: AppEnvironment)
        -> [FieldLogSummary.Row] {
        var rows: [FieldLogSummary.Row] = [
            .init(label: "Plot design", value: designPhrase(design, areaUnit: areaUnit))]
        rows.append(.init(label: "Volume equation",
                          value: volumeEquationText(speciesCodes: speciesCodes,
                                                    speciesByCode: speciesByCode,
                                                    environment: environment)))
        rows.append(.init(label: "Heights",
                          value: "Measured where one was taken; the rest read off this project's height curve."))
        // The log rule drives BOARD FEET, and no cruise number here is board
        // feet. Saying so is the point: a cruiser who changed the rule in
        // Settings and saw no volume move should know why.
        rows.append(.init(label: "Board-foot log rule",
                          value: "\(settings.logRule.displayName) — not used here; this volume is m³ from the equation above."))
        rows.append(.init(label: "Density basis", value: areaUnit.basisPhrase))
        return rows
    }

    private static func volumeEquationText(speciesCodes: Set<String>,
                                           speciesByCode: [String: SpeciesConfig],
                                           environment: AppEnvironment) -> String {
        let configs: [String: SpeciesConfig]
        if speciesByCode.isEmpty {
            let listed = (try? environment.speciesRepository.list()) ?? []
            configs = Dictionary(uniqueKeysWithValues: listed.map { ($0.code, $0) })
        } else {
            configs = speciesByCode
        }
        let wanted = speciesCodes.isEmpty ? Set(configs.keys) : speciesCodes
        let equationIDs = Set(wanted.compactMap { configs[$0]?.volumeEquationId })
        guard !equationIDs.isEmpty,
              let equations = try? environment.volumeEquationRepository.list()
        else { return "None configured — volume reads 0." }
        let names = equations
            .filter { equationIDs.contains($0.id) }
            .map { $0.sourceCitation.isEmpty ? $0.form : $0.sourceCitation }
            .sorted()
        return names.isEmpty ? "None configured — volume reads 0."
            : names.joined(separator: FieldLogWords.headingSeparator)
    }
}

// MARK: - Quick-measure plot statistics

/// The quick world's plot math, lifted out of `PlotSummaryCard` when that card
/// became a renderer. Unchanged: same grouping by tree number, same basal-area
/// numerator rule, same invented tenth of an acre when no acreage was entered.
///
/// It stays a plain value type with a static entry point because it is pure —
/// a list of readings in, a set of numbers out — and because the field log now
/// calls it from a builder rather than from a view's body.
public struct QuickPlotStats: Equatable {

    /// The acreage a plot with none entered is divided by. Not a measurement:
    /// see the note on `FieldLogSummaryBuilder.quick`.
    public static let assumedAcres: Double = 0.1
    /// Floor under a typed acreage, so a plot entered as 0 cannot divide by
    /// nothing.
    static let minimumAcres: Double = 0.05

    public let treeCount: Int
    public let tpa: Double
    public let baPerAcre: Double
    public let qmdCm: Double
    public let meanHeightM: Double?
    public let boardFeetPerAcre: Double?
    public let speciesMix: [FieldLogSummary.Species]

    public static func compute(plot: QuickMeasurePlot,
                               entries: [QuickMeasureEntry],
                               areaUnit: AreaUnit,
                               logRule: LogRule) -> QuickPlotStats? {
        guard !entries.isEmpty else { return nil }

        // Group by tree number; each tree contributes the first DBH it has
        // and the first height it has.
        let byTree = Dictionary(grouping: entries) { $0.treeNumber ?? -1 }
        let trees = byTree.map { (_, group) -> (dbhCm: Double?, hM: Double?, species: String) in
            let dbh = group.first(where: { $0.kind == .dbh })?.value
            let h = group.first(where: { $0.kind == .height })?.value
            let sp = group.first(where: { ($0.speciesCode ?? "").isEmpty == false })?.speciesCode ?? ""
            return (dbh, h, sp)
        }

        let dbhTrees = trees.compactMap { $0.dbhCm }
        guard !dbhTrees.isEmpty else { return nil }

        // Basal area per tree in the unit that matches the displayed basis:
        // ft² for the per-acre card, m² for the per-hectare one. Computing ft²
        // and labelling it "/ha" over-reads basal area by ~10.76×, so the
        // numerator has to switch with the label.
        let baPerTree: [Double] = dbhTrees.map { cm in
            if areaUnit == .hectare {
                let m = cm / 100.0
                return Double.pi / 4.0 * m * m
            } else {
                let inches = cm / 2.54
                return 0.005454 * inches * inches
            }
        }
        let acres = max(plot.acres ?? assumedAcres, minimumAcres)
        let qmdSqCm = dbhTrees.map { $0 * $0 }.reduce(0, +) / Double(dbhTrees.count)

        let heights = trees.compactMap { $0.hM }
        var totalBF: Double = 0
        var bfCount = 0
        for t in trees {
            guard let dbh = t.dbhCm, let h = t.hM,
                  let bf = VolumeConversion.boardFeet(dbhCm: dbh, totalHeightM: h,
                                                      rule: logRule) else { continue }
            totalBF += bf
            bfCount += 1
        }

        var counts: [String: Int] = [:]
        for t in trees { counts[t.species, default: 0] += 1 }

        return QuickPlotStats(
            treeCount: dbhTrees.count,
            tpa: Double(dbhTrees.count) / acres,
            baPerAcre: baPerTree.reduce(0, +) / acres,
            qmdCm: qmdSqCm.squareRoot(),
            meanHeightM: heights.isEmpty ? nil
                : heights.reduce(0, +) / Double(heights.count),
            boardFeetPerAcre: bfCount > 0 ? totalBF / acres : nil,
            speciesMix: counts
                .map { FieldLogSummary.Species(code: $0.key,
                                               share: Double($0.value) / Double(trees.count)) }
                .sorted { $0.share == $1.share ? $0.code < $1.code : $0.share > $1.share })
    }
}

// MARK: - Area-unit wording

extension AreaUnit {
    /// How the detail view names the basis every density on the card rests on.
    /// Android's `AreaUnit.basisPhrase` returns the identical strings.
    var basisPhrase: String {
        self == .hectare ? "Per hectare" : "Per acre"
    }
}
