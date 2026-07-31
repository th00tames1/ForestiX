// The field log's summary card, AS A FUNCTION OF THE FILTER.
// Kotlin sibling of iOS Screens/FieldLogSummary.swift: same branches, same
// strings, same reuse.
//
// It used to be a card about one special case: whichever quick-measure plot
// happened to be active, and nothing else. Pick a cruise plot in the filter
// and the card either vanished or — worse — went on describing a different
// plot than every row beneath it. The filter is the screen's one statement of
// what the cruiser is looking at, so the card now follows it:
//
//   • one QUICK plot     → that plot's readings
//   • one CRUISE plot    → that plot's PlotStats
//   • one CRUISE project → that project's stand statistics
//   • Everything         → no card. "Everything" names no single plot, and a
//                          summary of two stores whose per-area bases are not
//                          the same quantity would be a number about nothing.
//
// NOTHING HERE COMPUTES A CRUISE NUMBER. The cruise branches build the same
// view models the cruise screens build — PlotSummaryViewModel for a plot,
// StandSummaryViewModel for a project — call refresh() and read the result.
// That is deliberate: this app has already been bitten by a figure computed
// in two places, and a stand table in the field log that disagreed with the
// stand table on the stand-summary screen would be exactly that bug with a
// friendlier face. The population is the same too — a project card is the
// project's CLOSED plots, because that is what the stand summary aggregates,
// and matching screens matter more here than a fuller-looking card mid-cruise.
//
// The QUICK branch is the math that used to live inside PlotSummaryCard,
// moved here unchanged so the card became a renderer and all three branches
// arrive at it in one shape.

package com.hcjeong.forestix.ui.screens

import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.common.AreaUnit
import com.hcjeong.forestix.common.MeasurementFormatter
import com.hcjeong.forestix.common.areaUnit
import com.hcjeong.forestix.data.MeasureKind
import com.hcjeong.forestix.data.QuickMeasureEntry
import com.hcjeong.forestix.data.QuickMeasurePlot
import com.hcjeong.forestix.data.SettingsSnapshot
import com.hcjeong.forestix.data.cruise.CruiseDesign
import com.hcjeong.forestix.data.cruise.Plot
import com.hcjeong.forestix.data.cruise.PlotType
import com.hcjeong.forestix.data.cruise.Project
import com.hcjeong.forestix.data.cruise.SamplingScheme
import com.hcjeong.forestix.data.cruise.SpeciesConfig
import com.hcjeong.forestix.sensors.LogRule
import com.hcjeong.forestix.sensors.VolumeConversion
import com.hcjeong.forestix.ui.screens.plot.PlotSummaryViewModel
import com.hcjeong.forestix.ui.screens.stand.StandSummaryViewModel
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sqrt

/// What the field log's summary card and its detail view render. Every string
/// is finished here so the card and the detail cannot format the same number
/// two ways.
data class FieldLogSummary(
    /// The all-caps heading on the card, and the thing the cruiser taps to
    /// open the detail view.
    val heading: String,
    val title: String,
    val subtitle: String?,
    /// Four cells, always — the card's grid is fixed on both platforms.
    val cells: List<Cell>,
    val speciesMix: List<Species>,
    /// The full set of computed values, and the settings that produced them.
    val groups: List<Group>,
    /// Why there is nothing to compute, when there is nothing to compute. The
    /// cells then read "—" rather than 0, which is a measurement.
    val note: String? = null,
) {
    data class Cell(val label: String, val value: String)
    data class Species(val code: String, val share: Double)
    data class Row(val label: String, val value: String)
    data class Group(val title: String, val rows: List<Row>)

    companion object {
        const val PLOT_HEADING = "PLOT SUMMARY"
        /// A project is a STAND — the same word the screen that computes these
        /// numbers already uses, so a cruiser reading the two knows they are
        /// the same figures.
        const val STAND_HEADING = "STAND SUMMARY"
        /// Shown on a project card whose project has closed no plots. The
        /// stand summary's own sentence, verbatim.
        const val NO_CLOSED_PLOTS = "No closed plots yet."
        /// A plot with readings but nothing to compute from them.
        const val NOTHING_MEASURED = "Nothing measured in this plot yet."
        /// The detail view's title, and the label on the card's heading.
        const val DETAIL_TITLE = "How this was computed"
    }
}

// MARK: - Builder

object FieldLogSummaryBuilder {

    /// The summary for the scope the log is showing, or null when the scope
    /// names no single plot or project.
    suspend fun make(
        scope: FieldLogScope,
        quickPlots: List<QuickMeasurePlot>,
        quickEntries: List<QuickMeasureEntry>,
        settings: SettingsSnapshot,
        env: AppEnvironment,
        cruiseData: FieldLogCruiseData,
    ): FieldLogSummary? = when (scope) {
        is FieldLogScope.Everything -> null
        is FieldLogScope.QuickPlot -> {
            val plot = quickPlots.firstOrNull { it.id == scope.id }
            if (plot == null) {
                null
            } else {
                // A reading with no plot on it belongs to the default plot —
                // the same fallback the log's own sections apply, so the card
                // counts exactly the rows drawn beneath it.
                val defaultPlotID = quickPlots.firstOrNull { it.isDefault }?.id
                quick(
                    plot,
                    quickEntries.filter { (it.plotID ?: defaultPlotID) == scope.id },
                    settings)
            }
        }
        is FieldLogScope.CruisePlot -> {
            val plot = cruiseData.plot(scope.id)
            val project = cruiseData.project(scope.id)
            if (plot == null || project == null) null
            else cruisePlot(plot, project, settings, env)
        }
        is FieldLogScope.CruiseProject -> {
            val project = cruiseData.projects.firstOrNull { it.id == scope.id }
            if (project == null) null else cruiseProject(project, settings, env)
        }
    }

    // MARK: Quick

    /// The quick-measure branch — the math PlotSummaryCard used to hold.
    ///
    /// It divides by max(acres ?: 0.1, 0.05), which means a plot with no
    /// acreage entered is being divided by an INVENTED tenth of an acre. That
    /// was true before and is unchanged; what is new is that the detail view
    /// says so on the "Plot area" line, so a density read off this card can be
    /// recognised as relative rather than absolute.
    private fun quick(
        plot: QuickMeasurePlot,
        entries: List<QuickMeasureEntry>,
        settings: SettingsSnapshot,
    ): FieldLogSummary {
        val areaUnit = settings.unitSystem.areaUnit
        val factor = areaUnit.perAcreDensityFactor
        val stats = QuickPlotStats.compute(plot, entries, areaUnit, settings.logRule)

        val subtitleParts = mutableListOf<String>()
        if (plot.unitName.isNotEmpty()) subtitleParts.add(plot.unitName)
        plot.acres?.let {
            subtitleParts.add(
                String.format(
                    Locale.US, "%.2f %s", areaUnit.fromAcres(it), areaUnit.abbreviation))
        }

        // The basal-area numerator follows the areal basis: ft² per acre for a
        // US cruise, m² per hectare for a metric one. It is IN THE VALUE now
        // rather than left to the label, because a cruise plot's basal area on
        // the same card is m² whatever the basis, and two numbers an order of
        // magnitude apart under one bare "BASAL/AC" heading is how a cruiser
        // reads a stand as ten times denser than it is.
        val baUnit = if (areaUnit == AreaUnit.HECTARE) "m²" else "ft²"
        val cells = listOf(
            FieldLogSummary.Cell("TREES", stats?.treeCount?.toString() ?: "—"),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("BASAL").uppercase(Locale.US),
                stats?.let {
                    String.format(Locale.US, "%.0f %s", it.baPerAcre * factor, baUnit)
                } ?: "—"),
            FieldLogSummary.Cell(
                treesPerAreaLabel(areaUnit),
                stats?.let { String.format(Locale.US, "%.0f", it.tpa * factor) } ?: "—"),
            FieldLogSummary.Cell(
                "MEAN DBH",
                stats?.let {
                    MeasurementFormatter.diameter(it.qmdCm, settings.unitSystem)
                } ?: "—"))

        val computed = if (stats == null) emptyList() else listOf(
            FieldLogSummary.Row("Trees with a diameter", stats.treeCount.toString()),
            FieldLogSummary.Row(
                "Basal area",
                String.format(
                    Locale.US, "%.1f %s%s", stats.baPerAcre * factor, baUnit,
                    areaUnit.densitySuffix)),
            FieldLogSummary.Row(
                "Trees",
                String.format(
                    Locale.US, "%.0f %s", stats.tpa * factor, areaUnit.densitySuffix)),
            FieldLogSummary.Row(
                "Quadratic mean diameter",
                MeasurementFormatter.diameter(stats.qmdCm, settings.unitSystem)),
            FieldLogSummary.Row(
                "Mean height",
                stats.meanHeightM?.let {
                    MeasurementFormatter.height(it, settings.unitSystem)
                } ?: "—"),
            // Board feet is the one volume the quick world can produce — it
            // needs a diameter, a height and a log rule, so a plot with no
            // heights on it reads "—" rather than 0.
            FieldLogSummary.Row(
                "Board feet",
                stats.boardFeetPerAcre?.let {
                    String.format(
                        Locale.US, "%.0f bf%s", it * factor, areaUnit.densitySuffix)
                } ?: "—"))

        val areaRow = plot.acres?.let {
            String.format(Locale.US, "%.2f %s", areaUnit.fromAcres(it), areaUnit.abbreviation)
        } ?: String.format(
            Locale.US, "Not entered — %.2f %s assumed",
            areaUnit.fromAcres(QuickPlotStats.ASSUMED_ACRES), areaUnit.abbreviation)

        return FieldLogSummary(
            heading = FieldLogSummary.PLOT_HEADING,
            title = plot.name,
            subtitle = subtitleParts.takeIf { it.isNotEmpty() }
                ?.joinToString(FieldLogWords.HEADING_SEPARATOR),
            cells = cells,
            speciesMix = stats?.speciesMix.orEmpty(),
            groups = listOf(
                FieldLogSummary.Group("Computed for this plot", computed),
                FieldLogSummary.Group(
                    "Behind these numbers",
                    listOf(
                        FieldLogSummary.Row("Plot area", areaRow),
                        FieldLogSummary.Row(
                            "Board-foot log rule", settings.logRule.displayName),
                        FieldLogSummary.Row("Density basis", areaUnit.basisPhrase),
                        FieldLogSummary.Row(
                            "Trees",
                            "One per tree number, taking its newest diameter and height.")))),
            note = if (stats == null) FieldLogSummary.NOTHING_MEASURED else null)
    }

    // MARK: Cruise plot

    private suspend fun cruisePlot(
        plot: Plot,
        project: Project,
        settings: SettingsSnapshot,
        env: AppEnvironment,
    ): FieldLogSummary {
        val areaUnit = settings.unitSystem.areaUnit
        val factor = areaUnit.perAcreDensityFactor
        val design = effectiveDesign(project.id, env)
        // The plot-details screen's own view model, refreshed and read. No
        // second computation of TPA, basal area, QMD or volume exists.
        val viewModel = PlotSummaryViewModel(
            project = project, design = design, plot = plot,
            plotRepo = env.plotRepository,
            treeRepo = env.treeRepository,
            speciesRepo = env.speciesConfigRepository,
            volRepo = env.volumeEquationRepository,
            hdFitRepo = env.heightDiameterFitRepository)
        viewModel.refresh()
        val stats = viewModel.stats.value
        val empty = stats.liveTreeCount == 0
        val pending = settings.country.volumeStandardPending

        val cells = listOf(
            FieldLogSummary.Cell(
                "TREES", if (empty) "—" else stats.liveTreeCount.toString()),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("BASAL").uppercase(Locale.US),
                if (empty) "—" else String.format(
                    Locale.US, "%.1f m²", stats.baPerAcreM2.toDouble() * factor)),
            FieldLogSummary.Cell(
                treesPerAreaLabel(areaUnit),
                if (empty) "—" else String.format(
                    Locale.US, "%.0f", stats.tpa.toDouble() * factor)),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("VOLUME").uppercase(Locale.US),
                if (empty || pending) "—" else String.format(
                    Locale.US, "%.1f m³",
                    stats.grossVolumePerAcreM3.toDouble() * factor)))

        val computed = if (empty) emptyList() else listOf(
            FieldLogSummary.Row("Live trees", stats.liveTreeCount.toString()),
            FieldLogSummary.Row(
                "Basal area",
                String.format(
                    Locale.US, "%.2f %s", stats.baPerAcreM2.toDouble() * factor,
                    areaUnit.densityLabel("m²"))),
            FieldLogSummary.Row(
                "Trees",
                String.format(
                    Locale.US, "%.1f %s", stats.tpa.toDouble() * factor,
                    areaUnit.densitySuffix)),
            FieldLogSummary.Row(
                "Quadratic mean diameter",
                MeasurementFormatter.diameter(stats.qmdCm.toDouble(), settings.unitSystem)),
            FieldLogSummary.Row(
                "Gross volume",
                if (pending) VOLUME_PENDING else String.format(
                    Locale.US, "%.1f %s", stats.grossVolumePerAcreM3.toDouble() * factor,
                    areaUnit.densityLabel("m³"))),
            FieldLogSummary.Row(
                "Merchantable volume",
                if (pending) VOLUME_PENDING else String.format(
                    Locale.US, "%.1f %s", stats.merchVolumePerAcreM3.toDouble() * factor,
                    areaUnit.densityLabel("m³"))))

        return FieldLogSummary(
            heading = FieldLogSummary.PLOT_HEADING,
            title = FieldLogWords.plotName(plot.plotNumber),
            subtitle = project.name + FieldLogWords.HEADING_SEPARATOR +
                designPhrase(design, areaUnit),
            cells = cells,
            speciesMix = mix(stats.bySpecies.mapValues { it.value.count }),
            groups = listOf(
                FieldLogSummary.Group("Computed for this plot", computed),
                FieldLogSummary.Group(
                    "Behind these numbers",
                    cruiseSettingsRows(
                        design = design,
                        speciesCodes = stats.bySpecies.keys,
                        speciesByCode = viewModel.speciesByCode.value,
                        areaUnit = areaUnit, settings = settings, env = env))),
            note = if (empty) FieldLogSummary.NOTHING_MEASURED else null)
    }

    // MARK: Cruise project

    private suspend fun cruiseProject(
        project: Project,
        settings: SettingsSnapshot,
        env: AppEnvironment,
    ): FieldLogSummary {
        val areaUnit = settings.unitSystem.areaUnit
        val factor = areaUnit.perAcreDensityFactor
        val design = effectiveDesign(project.id, env)
        // The stand-summary screen's own view model, refreshed and read —
        // same closed plots, same stratification, same means.
        val viewModel = StandSummaryViewModel(
            project = project, design = design,
            plotRepo = env.plotRepository,
            treeRepo = env.treeRepository,
            speciesRepo = env.speciesConfigRepository,
            volRepo = env.volumeEquationRepository,
            hdFitRepo = env.heightDiameterFitRepository,
            stratumRepo = env.stratumRepository,
            plannedRepo = env.plannedPlotRepository)
        viewModel.refresh()
        val plots = viewModel.closedPlots.value
        val empty = plots.isEmpty()
        val pending = settings.country.volumeStandardPending
        val liveTrees = viewModel.totalLiveTreeCount.value
        val tpa = viewModel.tpaStat.value.scaledPerArea(factor)
        val ba = viewModel.baStat.value.scaledPerArea(factor)
        val volume = viewModel.volStat.value.scaledPerArea(factor)

        val cells = listOf(
            FieldLogSummary.Cell("TREES", if (empty) "—" else liveTrees.toString()),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("BASAL").uppercase(Locale.US),
                if (empty) "—" else String.format(Locale.US, "%.1f m²", ba.mean)),
            FieldLogSummary.Cell(
                treesPerAreaLabel(areaUnit),
                if (empty) "—" else String.format(Locale.US, "%.0f", tpa.mean)),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("VOLUME").uppercase(Locale.US),
                if (empty || pending) "—"
                else String.format(Locale.US, "%.1f m³", volume.mean)))

        val computed = if (empty) emptyList() else listOf(
            FieldLogSummary.Row("Closed plots", plots.size.toString()),
            FieldLogSummary.Row("Live trees", liveTrees.toString()),
            FieldLogSummary.Row(
                "Trees",
                confidenceText(tpa.mean, tpa.ci95HalfWidth, 1, areaUnit.densitySuffix)),
            FieldLogSummary.Row(
                "Basal area",
                confidenceText(
                    ba.mean, ba.ci95HalfWidth, 2, " " + areaUnit.densityLabel("m²"))),
            FieldLogSummary.Row(
                "Gross volume",
                if (pending) VOLUME_PENDING else confidenceText(
                    volume.mean, volume.ci95HalfWidth, 1,
                    " " + areaUnit.densityLabel("m³"))))

        // Species counts summed over the plots that were averaged, so the mix
        // describes the same trees the figures above it do.
        val counts = mutableMapOf<String, Int>()
        for (row in viewModel.perPlotStats.value) {
            for ((code, stat) in row.stats.bySpecies) {
                counts[code] = (counts[code] ?: 0) + stat.count
            }
        }

        val settingsRows = cruiseSettingsRows(
            design = design, speciesCodes = counts.keys, speciesByCode = emptyMap(),
            areaUnit = areaUnit, settings = settings, env = env).toMutableList()
        // A project average is a plot mean, not a tree mean. Which plots went
        // into it is the first thing a cruiser questioning the number asks.
        settingsRows.add(
            1,
            FieldLogSummary.Row(
                "Averaged over",
                if (empty) FieldLogSummary.NO_CLOSED_PLOTS
                else "The project's ${plots.size} closed plot" +
                    (if (plots.size == 1) "" else "s") +
                    ", each weighted by its stratum's area."))

        return FieldLogSummary(
            heading = FieldLogSummary.STAND_HEADING,
            title = project.name,
            subtitle = "${plots.size} closed plot(s) · $liveTrees live trees",
            cells = cells,
            speciesMix = mix(counts),
            groups = listOf(
                FieldLogSummary.Group("Computed across closed plots", computed),
                FieldLogSummary.Group("Behind these numbers", settingsRows)),
            note = if (empty) FieldLogSummary.NO_CLOSED_PLOTS else null)
    }

    // MARK: Shared pieces

    /// Korea ships as a scaffold: the NIFoS coefficients are not in the app
    /// yet, so a volume there would be a fabricated 0.
    private const val VOLUME_PENDING = "Not available for this region yet."

    private fun treesPerAreaLabel(areaUnit: AreaUnit): String =
        if (areaUnit == AreaUnit.HECTARE) "TREES/HA" else "TREES/AC"

    private fun confidenceText(
        mean: Double,
        halfWidth: Double,
        decimals: Int,
        unit: String,
    ): String = String.format(
        Locale.US, "%.${decimals}f%s ± %.${decimals}f (95%% confidence)",
        mean, unit, halfWidth)

    /// Shares by tree count, biggest first — the same rule the quick branch
    /// uses, so one card cannot be read two ways.
    private fun mix(counts: Map<String, Int>): List<FieldLogSummary.Species> {
        val total = counts.values.sum()
        if (total <= 0) return emptyList()
        return counts
            .map { FieldLogSummary.Species(it.key, it.value.toDouble() / total) }
            .sortedWith(compareByDescending<FieldLogSummary.Species> { it.share }
                .thenBy { it.code })
    }

    /// The CruiseDesign a project's roll-ups are computed against.
    ///
    /// Cruise setup is optional, so a project can be tallied to completion
    /// with no CruiseDesign row at all; the informal path must still
    /// summarise, which is why the miss synthesises a fixed-area / manual
    /// design rather than refusing. PlotStatsCalculator consults only
    /// `plotType` and `baf` — the plot's own `plotAreaAcres` carries the area
    /// — so the synthesised row changes no number a real design would produce.
    /// iOS keeps the same rule in `CruiseDesignFallback`.
    private suspend fun effectiveDesign(projectID: UUID, env: AppEnvironment): CruiseDesign =
        env.cruiseDesignRepository.forProject(projectID).firstOrNull()
            ?: CruiseDesign(
                id = UUID.randomUUID(),
                projectId = projectID,
                plotType = PlotType.FIXED_AREA,
                plotAreaAcres = null,
                baf = null,
                samplingScheme = SamplingScheme.MANUAL,
                gridSpacingMeters = null)

    private fun designPhrase(design: CruiseDesign, areaUnit: AreaUnit): String =
        when (design.plotType) {
            PlotType.FIXED_AREA -> design.plotAreaAcres?.let {
                String.format(
                    Locale.US, "Fixed-area · %.2f %s",
                    areaUnit.fromAcres(it.toDouble()), areaUnit.abbreviation)
            } ?: "Fixed-area"
            PlotType.VARIABLE_RADIUS -> design.baf?.let {
                String.format(Locale.US, "Variable-radius · BAF %.0f", it.toDouble())
            } ?: "Variable-radius"
        }

    /// The settings a cruise figure rests on. Named rather than assumed: a
    /// volume with no equation behind it is 0, and a 0 that looks like a
    /// measurement is the failure this block exists to prevent.
    private suspend fun cruiseSettingsRows(
        design: CruiseDesign,
        speciesCodes: Set<String>,
        speciesByCode: Map<String, SpeciesConfig>,
        areaUnit: AreaUnit,
        settings: SettingsSnapshot,
        env: AppEnvironment,
    ): List<FieldLogSummary.Row> = listOf(
        FieldLogSummary.Row("Plot design", designPhrase(design, areaUnit)),
        FieldLogSummary.Row(
            "Volume equation", volumeEquationText(speciesCodes, speciesByCode, env)),
        FieldLogSummary.Row(
            "Heights",
            "Measured where one was taken; the rest read off this project's height curve."),
        // The log rule drives BOARD FEET, and no cruise number here is board
        // feet. Saying so is the point: a cruiser who changed the rule in
        // Settings and saw no volume move should know why.
        FieldLogSummary.Row(
            "Board-foot log rule",
            settings.logRule.displayName +
                " — not used here; this volume is m³ from the equation above."),
        FieldLogSummary.Row("Density basis", areaUnit.basisPhrase))

    private suspend fun volumeEquationText(
        speciesCodes: Set<String>,
        speciesByCode: Map<String, SpeciesConfig>,
        env: AppEnvironment,
    ): String = try {
        val configs = speciesByCode.ifEmpty {
            env.speciesConfigRepository.list().associateBy { it.code }
        }
        val wanted = speciesCodes.ifEmpty { configs.keys }
        val equationIDs = wanted.mapNotNull { configs[it]?.volumeEquationId }.toSet()
        val names = env.volumeEquationRepository.list()
            .filter { it.id in equationIDs }
            .map { it.sourceCitation.ifEmpty { it.form } }
            .sorted()
        if (names.isEmpty()) NO_VOLUME_EQUATION
        else names.joinToString(FieldLogWords.HEADING_SEPARATOR)
    } catch (e: Exception) {
        NO_VOLUME_EQUATION
    }

    private const val NO_VOLUME_EQUATION = "None configured — volume reads 0."
}

// MARK: - Quick-measure plot statistics

/// The quick world's plot math, lifted out of PlotSummaryCard when that card
/// became a renderer. Unchanged: same grouping by tree number, same basal-area
/// numerator rule, same invented tenth of an acre when no acreage was entered.
data class QuickPlotStats(
    val treeCount: Int,
    val tpa: Double,
    val baPerAcre: Double,
    val qmdCm: Double,
    val meanHeightM: Double?,
    val boardFeetPerAcre: Double?,
    val speciesMix: List<FieldLogSummary.Species>,
) {
    companion object {
        /// The acreage a plot with none entered is divided by. Not a
        /// measurement: see the note on FieldLogSummaryBuilder.quick.
        const val ASSUMED_ACRES = 0.1
        /// Floor under a typed acreage, so a plot entered as 0 cannot divide
        /// by nothing.
        private const val MINIMUM_ACRES = 0.05

        fun compute(
            plot: QuickMeasurePlot,
            entries: List<QuickMeasureEntry>,
            areaUnit: AreaUnit,
            logRule: LogRule,
        ): QuickPlotStats? {
            if (entries.isEmpty()) return null

            // Group by tree number; each tree contributes the first DBH it has
            // and the first height it has.
            data class TreeAgg(val dbhCm: Double?, val hM: Double?, val species: String)
            val trees = entries.groupBy { it.treeNumber ?: -1 }.map { (_, group) ->
                TreeAgg(
                    dbhCm = group.firstOrNull { it.kind == MeasureKind.DBH }?.value,
                    hM = group.firstOrNull { it.kind == MeasureKind.HEIGHT }?.value,
                    species = group.firstOrNull { !it.speciesCode.isNullOrEmpty() }
                        ?.speciesCode ?: "")
            }

            val dbhTrees = trees.mapNotNull { it.dbhCm }
            if (dbhTrees.isEmpty()) return null

            // Basal area per tree in the unit that matches the displayed
            // basis: ft² for the per-acre card, m² for the per-hectare one.
            // Computing ft² and labelling it "/ha" over-reads basal area by
            // ~10.76×, so the numerator has to switch with the label.
            val baPerTree = dbhTrees.map { cm ->
                if (areaUnit == AreaUnit.HECTARE) {
                    val m = cm / 100.0
                    (PI / 4.0) * m * m
                } else {
                    val inches = cm / 2.54
                    0.005454 * inches * inches
                }
            }
            val acres = max(plot.acres ?: ASSUMED_ACRES, MINIMUM_ACRES)
            val qmdSqCm = dbhTrees.sumOf { it * it } / dbhTrees.size.toDouble()

            val heights = trees.mapNotNull { it.hM }
            var totalBF = 0.0
            var bfCount = 0
            for (t in trees) {
                val dbh = t.dbhCm ?: continue
                val h = t.hM ?: continue
                val bf = VolumeConversion.boardFeet(
                    dbhCm = dbh, totalHeightM = h, rule = logRule) ?: continue
                totalBF += bf
                bfCount += 1
            }

            val counts = mutableMapOf<String, Int>()
            for (t in trees) counts[t.species] = (counts[t.species] ?: 0) + 1

            return QuickPlotStats(
                treeCount = dbhTrees.size,
                tpa = dbhTrees.size.toDouble() / acres,
                baPerAcre = baPerTree.sum() / acres,
                qmdCm = sqrt(qmdSqCm),
                meanHeightM = if (heights.isEmpty()) null
                else heights.sum() / heights.size.toDouble(),
                boardFeetPerAcre = if (bfCount > 0) totalBF / acres else null,
                speciesMix = counts
                    .map {
                        FieldLogSummary.Species(it.key, it.value.toDouble() / trees.size)
                    }
                    .sortedWith(compareByDescending<FieldLogSummary.Species> { it.share }
                        .thenBy { it.code }))
        }
    }
}

// MARK: - Area-unit wording

/// How the detail view names the basis every density on the card rests on.
/// iOS's `AreaUnit.basisPhrase` returns the identical strings.
val AreaUnit.basisPhrase: String
    get() = if (this == AreaUnit.HECTARE) "Per hectare" else "Per acre"
