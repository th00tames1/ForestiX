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
//   • Everything         → whatever "everything" unambiguously is: the one
//                          stand, when there is exactly one and no quick
//                          readings sit outside it, and otherwise a COUNT of
//                          what the log holds. Counts and not a density,
//                          because a quick plot's per-area figure is divided
//                          by an assumed area and a cruise plot's is expanded
//                          by the design's factor; those are not the same
//                          quantity and their sum is a number about nothing.
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
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.common.Units
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
    /// The quick-measure plot behind this summary, when there is one.
    ///
    /// The plot's area is the divisor under every per-area figure on the card,
    /// and it is the one thing here the cruiser can still correct — a detail
    /// view writes a typed area back through this id. Null on the cruise
    /// branches, whose area comes from the design.
    ///
    /// iOS's detail view takes that entry today; the Android sheet cannot yet,
    /// because QuickMeasureHistory has no plot-area write to call.
    val quickPlotID: UUID? = null,
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
        /// The unfiltered scope's heading and title.
        const val LOG_HEADING = "LOG SUMMARY"
        const val EVERYTHING_TITLE = "Everything measured"
        /// Said on the unfiltered card, because counts are all it can honestly
        /// carry and the computed values are one tap away.
        const val PICK_A_SCOPE =
            "Pick a plot or a stand in the filter for computed values."
        /// The detail view's title, and the label on the card's heading.
        const val DETAIL_TITLE = "How this was computed"
        /// Carried by any per-area figure that rests on an ASSUMED plot area.
        /// One character, on the value itself: the card has no room for a
        /// second line, and a density divided by an area nobody measured must
        /// not look like one divided by an area somebody did.
        const val ASSUMED_MARK = "~"
        /// The label the plot's area carries in the settings block, and the
        /// heading over the entry that takes it.
        const val PLOT_AREA_TITLE = "Plot area"
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
        is FieldLogScope.Everything ->
            everything(quickPlots, quickEntries, settings, env, cruiseData)
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

    // MARK: Everything

    /// The unfiltered scope: the one stand if that is unambiguous, else counts.
    ///
    /// Port of iOS `FieldLogSummaryBuilder.everything`. The delegation matters
    /// more than the counts do — a cruiser running one project and nothing
    /// else opens the field log and gets that stand's real figures without
    /// learning that the funnel is where summaries live. The fallback is
    /// COUNTS and not a density, because a quick plot's per-area figure is
    /// divided by an assumed area while a cruise plot's is expanded by the
    /// design's factor; those are not the same quantity and adding them makes
    /// a number about nothing.
    private suspend fun everything(
        quickPlots: List<QuickMeasurePlot>,
        quickEntries: List<QuickMeasureEntry>,
        settings: SettingsSnapshot,
        env: AppEnvironment,
        cruiseData: FieldLogCruiseData,
    ): FieldLogSummary? {
        val quickCount = quickEntries.size
        val cruiseTrees = cruiseData.rowsByPlot.values.sumOf { it.size }
        val cruisePlots = cruiseData.plotsByProject.values.sumOf { it.size }

        // Exactly one stand, and nothing measured outside it: "everything" and
        // "that stand" name the same set, so show the stand.
        val only = cruiseData.projects.singleOrNull()
        if (only != null && quickCount == 0) {
            return cruiseProject(only, settings, env)
        }

        // An empty log gets no card. Here — and only here — "nothing at all"
        // is genuinely nothing to summarise, and a row of zeroes would read as
        // a measurement.
        if (quickCount == 0 && cruiseTrees == 0) return null

        val areaUnit = settings.unitSystem.areaUnit
        return FieldLogSummary(
            heading = FieldLogSummary.LOG_HEADING,
            title = FieldLogSummary.EVERYTHING_TITLE,
            subtitle = null,
            cells = listOf(
                FieldLogSummary.Cell("TREES", "${quickCount + cruiseTrees}"),
                FieldLogSummary.Cell("STANDS", "${cruiseData.projects.size}"),
                FieldLogSummary.Cell("CRUISE PLOTS", "$cruisePlots"),
                FieldLogSummary.Cell("QUICK PLOTS", "${quickPlots.size}")),
            speciesMix = emptyList(),
            groups = listOf(
                FieldLogSummary.Group(
                    "What the log holds",
                    listOf(
                        FieldLogSummary.Row("Cruise trees", "$cruiseTrees"),
                        FieldLogSummary.Row("Cruise plots", "$cruisePlots"),
                        FieldLogSummary.Row("Stands", "${cruiseData.projects.size}"),
                        FieldLogSummary.Row("Quick readings", "$quickCount"),
                        FieldLogSummary.Row("Quick plots", "${quickPlots.size}"))),
                FieldLogSummary.Group(
                    "Behind these numbers",
                    listOf(
                        FieldLogSummary.Row(
                            "Board-foot log rule", settings.logRule.displayName),
                        FieldLogSummary.Row("Density basis", areaUnit.basisPhrase)))),
            note = FieldLogSummary.PICK_A_SCOPE,
            quickPlotID = null)
    }

    // MARK: Quick

    /// The quick-measure branch — the math PlotSummaryCard used to hold.
    ///
    /// It divides by QuickPlotStats.divisorAcres, which fills in a tenth of an
    /// acre for a plot with no acreage on it and floors a tiny one. Either way
    /// the denominator is then the APP's and not the cruiser's, and every
    /// figure standing on it carries ASSUMED_MARK — on the card, in the
    /// computed rows and in the note — until a real area is entered.
    ///
    /// Callable on its own, unlike the cruise branches: the plot's area is a
    /// thing a view can change, and a view that changed it has to rebuild
    /// these figures from the store — going on showing the densities from
    /// before the area just typed is the stale-card bug moved one screen
    /// along. iOS's FieldLogSummaryDetail calls it for exactly that.
    fun quick(
        plot: QuickMeasurePlot,
        entries: List<QuickMeasureEntry>,
        settings: SettingsSnapshot,
    ): FieldLogSummary {
        val areaUnit = settings.unitSystem.areaUnit
        val factor = areaUnit.perAcreDensityFactor
        val stats = QuickPlotStats.compute(plot, entries, areaUnit, settings.logRule)

        // What the per-area figures were actually divided by, and whether the
        // app chose it rather than the cruiser or the ring.
        //
        // The flag comes from the resolver rather than being re-derived by
        // comparing the divisor with `plot.acres`: that comparison also read
        // "assumed" for a plot floored from 0.01 ac, and on Kotlin it read
        // the OPPOSITE of Swift for a NaN acreage, because boxed
        // Double.equals calls NaN equal to itself where Swift's != does not.
        val (divisor, assumed) = QuickPlotStats.resolvedArea(plot, entries)
        val mark = if (assumed) FieldLogSummary.ASSUMED_MARK else ""
        val divisorText = String.format(
            Locale.US, "%.2f %s", areaUnit.fromAcres(divisor), areaUnit.abbreviation)

        val subtitleParts = mutableListOf<String>()
        if (plot.unitName.isNotEmpty()) subtitleParts.add(plot.unitName)
        // THE SUBTITLE SHOWS THE DIVISOR, not the raw stored acreage. Built
        // from `plot.acres` it contradicted the area row on the same card: a
        // plot stored as 0.01 ac read "0.01 ac" up here and "0.05 ac
        // (assumed)" below, and a plot whose area came from the ring showed
        // nothing up here at all. Mirrors iOS.
        if (!assumed) {
            subtitleParts.add(
                String.format(
                    Locale.US, "%.2f %s",
                    areaUnit.fromAcres(divisor), areaUnit.abbreviation))
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
                    String.format(
                        Locale.US, "%s%.0f %s", mark, it.baPerAcre * factor, baUnit)
                } ?: "—"),
            FieldLogSummary.Cell(
                treesPerAreaLabel(areaUnit),
                stats?.let {
                    String.format(Locale.US, "%s%.0f", mark, it.tpa * factor)
                } ?: "—"),
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
                    Locale.US, "%s%.1f %s%s", mark, stats.baPerAcre * factor, baUnit,
                    areaUnit.densitySuffix)),
            FieldLogSummary.Row(
                "Trees",
                String.format(
                    Locale.US, "%s%.0f %s", mark, stats.tpa * factor,
                    areaUnit.densitySuffix)),
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
                        Locale.US, "%s%.0f bf%s", mark, it * factor,
                        areaUnit.densitySuffix)
                } ?: "—"))

        // The area reads as a figure either way; "(assumed)" is what tells the
        // cruiser it is the app's figure and not theirs.
        val areaRow = if (assumed) "$divisorText (assumed)" else divisorText

        // With nothing measured there is no per-area figure to qualify, and
        // "nothing measured" is the more useful sentence.
        val note = when {
            stats == null -> FieldLogSummary.NOTHING_MEASURED
            assumed ->
                "Plot area assumed, not measured: every " +
                    "${FieldLogSummary.ASSUMED_MARK} figure is divided by $divisorText."
            else -> null
        }

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
                        FieldLogSummary.Row(FieldLogSummary.PLOT_AREA_TITLE, areaRow),
                        FieldLogSummary.Row(
                            "Board-foot log rule", settings.logRule.displayName),
                        FieldLogSummary.Row("Density basis", areaUnit.basisPhrase)))),
            note = note,
            quickPlotID = plot.id)
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
            // Same rule the quick-measure card above already follows: the
            // numerator switches with the basis. The engine hands over m² per
            // ACRE, so scaling only the denominator printed "m²/ac" for an
            // imperial cruise — the one card in this file that disagreed with
            // the one beside it.
            FieldLogSummary.Cell(
                areaUnit.densityLabel("BASAL").uppercase(Locale.US),
                if (empty) "—" else String.format(
                    Locale.US, "%.1f %s",
                    MeasurementFormatter.basalAreaDensity(
                        stats.baPerAcreM2.toDouble(), areaUnit),
                    MeasurementFormatter.basalAreaNumeratorUnit(areaUnit))),
            FieldLogSummary.Cell(
                treesPerAreaLabel(areaUnit),
                if (empty) "—" else String.format(
                    Locale.US, "%.0f", stats.tpa.toDouble() * factor)),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("VOLUME").uppercase(Locale.US),
                if (empty || pending) "—" else String.format(
                    Locale.US, "%.1f %s",
                    MeasurementFormatter.volumeDensity(
                        stats.grossVolumePerAcreM3.toDouble(), areaUnit),
                    MeasurementFormatter.volumeNumeratorUnit(areaUnit))))

        val computed = if (empty) emptyList() else listOf(
            FieldLogSummary.Row("Live trees", stats.liveTreeCount.toString()),
            FieldLogSummary.Row(
                "Basal area",
                String.format(
                    Locale.US, "%.2f %s",
                    MeasurementFormatter.basalAreaDensity(
                        stats.baPerAcreM2.toDouble(), areaUnit),
                    MeasurementFormatter.basalAreaDensityUnit(areaUnit))),
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
                    Locale.US, "%.1f %s",
                    MeasurementFormatter.volumeDensity(
                        stats.grossVolumePerAcreM3.toDouble(), areaUnit),
                    MeasurementFormatter.volumeDensityUnit(areaUnit))),
            FieldLogSummary.Row(
                "Merchantable volume",
                if (pending) VOLUME_PENDING else String.format(
                    Locale.US, "%.1f %s",
                    MeasurementFormatter.volumeDensity(
                        stats.merchVolumePerAcreM3.toDouble(), areaUnit),
                    MeasurementFormatter.volumeDensityUnit(areaUnit))))

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
            note = if (empty) FieldLogSummary.NOTHING_MEASURED else null,
            quickPlotID = null)
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
        // Basal area alone needs a factor of its own: the engine's figure is
        // m² per ACRE, so an imperial cruise has to convert the numerator too
        // (m² -> ft²), where a tree count only has a denominator to convert.
        // Mean and half-width are scaled by the SAME number, or the band stops
        // bracketing the value it belongs to.
        val ba = viewModel.baStat.value.scaledPerArea(
            MeasurementFormatter.basalAreaDensityFactor(areaUnit))
        // Volume is the same shape of quantity — m³ per ACRE — so it takes a
        // factor of its own too, and the same one scales its band.
        val volume = viewModel.volStat.value.scaledPerArea(
            MeasurementFormatter.volumeDensityFactor(areaUnit))

        val cells = listOf(
            FieldLogSummary.Cell("TREES", if (empty) "—" else liveTrees.toString()),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("BASAL").uppercase(Locale.US),
                if (empty) "—" else String.format(
                    Locale.US, "%.1f %s", ba.mean,
                    MeasurementFormatter.basalAreaNumeratorUnit(areaUnit))),
            FieldLogSummary.Cell(
                treesPerAreaLabel(areaUnit),
                if (empty) "—" else String.format(Locale.US, "%.0f", tpa.mean)),
            FieldLogSummary.Cell(
                areaUnit.densityLabel("VOLUME").uppercase(Locale.US),
                if (empty || pending) "—"
                else String.format(
                    Locale.US, "%.1f %s", volume.mean,
                    MeasurementFormatter.volumeNumeratorUnit(areaUnit))))

        val computed = if (empty) emptyList() else listOf(
            FieldLogSummary.Row("Closed plots", plots.size.toString()),
            FieldLogSummary.Row("Live trees", liveTrees.toString()),
            FieldLogSummary.Row(
                "Trees",
                confidenceText(tpa.mean, tpa.ci95HalfWidth, 1, areaUnit.densitySuffix)),
            FieldLogSummary.Row(
                "Basal area",
                confidenceText(
                    ba.mean, ba.ci95HalfWidth, 2,
                    " " + MeasurementFormatter.basalAreaDensityUnit(areaUnit))),
            FieldLogSummary.Row(
                "Gross volume",
                if (pending) VOLUME_PENDING else confidenceText(
                    volume.mean, volume.ci95HalfWidth, 1,
                    " " + MeasurementFormatter.volumeDensityUnit(areaUnit))))

        // Species counts summed over the plots that were averaged, so the mix
        // describes the same trees the figures above it do.
        val counts = mutableMapOf<String, Int>()
        for (row in viewModel.perPlotStats.value) {
            for ((code, stat) in row.stats.bySpecies) {
                counts[code] = (counts[code] ?: 0) + stat.count
            }
        }

        // A project average is a plot mean, not a tree mean, and the plots it
        // was taken over are already counted in "Closed plots" above and in the
        // subtitle. This block carries the SETTINGS, not the method.
        val settingsRows = cruiseSettingsRows(
            design = design, speciesCodes = counts.keys, speciesByCode = emptyMap(),
            areaUnit = areaUnit, settings = settings, env = env)

        return FieldLogSummary(
            heading = FieldLogSummary.STAND_HEADING,
            title = project.name,
            subtitle = "${plots.size} closed plot(s) · $liveTrees live trees",
            cells = cells,
            speciesMix = mix(counts),
            groups = listOf(
                FieldLogSummary.Group("Computed across closed plots", computed),
                FieldLogSummary.Group("Behind these numbers", settingsRows)),
            note = if (empty) FieldLogSummary.NO_CLOSED_PLOTS else null,
            quickPlotID = null)
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
                // The stored BAF is ft²/ac (see `CruiseDesign.baf`); a metric
                // cruiser reads the same prism as m²/ha. `areaUnit` is already
                // the cruiser's own basis, so it is what decides which. Bare,
                // the number could be either and the two are 4.36x apart.
                val system =
                    if (areaUnit == AreaUnit.HECTARE) UnitSystem.METRIC
                    else UnitSystem.IMPERIAL
                String.format(
                    Locale.US, "Variable-radius · BAF %.0f %s",
                    MeasurementFormatter.bafDisplay(it.toDouble(), system),
                    MeasurementFormatter.bafUnit(system))
            } ?: "Variable-radius"
        }

    /// The settings a cruise figure rests on. Named rather than assumed: a
    /// volume with no equation behind it is 0, and a 0 that looks like a
    /// measurement is the failure this block exists to prevent.
    ///
    /// Every row is the SETTING and nothing else — a value a cruiser can read
    /// at arm's length and compare with what they set. The log rule is here
    /// even though no cruise figure is board feet, because a cruiser who
    /// changed it in Settings and saw no volume move looks for it here.
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
        FieldLogSummary.Row("Board-foot log rule", settings.logRule.displayName),
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

    /// What the volume-equation row says when the species carry none. The
    /// consequence — a volume of 0 — is on the volume row itself, which reads
    /// 0; this row names the setting.
    private const val NO_VOLUME_EQUATION = "None configured"
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
        const val MINIMUM_ACRES = 0.05

        /// The acreage the per-area figures are divided by: the cruiser's, or
        /// the assumed tenth when they entered none, never below the floor.
        ///
        /// One place, because the card has to be able to ASK whether the
        /// denominator is theirs — `divisorAcres(plot.acres) != plot.acres` is
        /// exactly the question, and it stops being answerable the moment two
        /// copies of this expression exist.
        fun divisorAcres(acres: Double?): Double = max(acres ?: ASSUMED_ACRES, MINIMUM_ACRES)

        const val SQUARE_METRES_PER_ACRE = 4046.8564224

        /// Acres per acre-basis figure, taking the plot's area from the best
        /// source the app actually has.
        ///
        /// IT USUALLY HAS ONE. Placing the sampling ring writes a
        /// SAMPLING_PLOT entry whose secondaryValue is that ring's area in
        /// square metres (SamplingPlotScreen), so a cruiser who dropped an
        /// 8 m ring has measured 201 m² — 0.0497 ac — and the app recorded
        /// it. Reading only `plot.acres` ignored that, divided by an assumed
        /// tenth of an acre, and then LABELLED the result as assumed: twice
        /// the true density, stated with confidence. Being confidently wrong
        /// is worse than the quiet guess it replaced.
        ///
        /// Order: what the cruiser typed, then what the ring measured, then
        /// the assumption. The newest ring wins — resizing it is the cruiser
        /// saying the earlier one was wrong.
        ///
        /// Mirrors iOS `QuickPlotStats.resolvedArea`.
        fun resolvedArea(
            plot: QuickMeasurePlot,
            entries: List<QuickMeasureEntry>,
        ): Pair<Double, Boolean> {
            val typed = plot.acres
            if (typed != null && typed > 0.0) {
                return Pair(max(typed, MINIMUM_ACRES), false)
            }
            val m2 = entries
                .filter { it.kind == MeasureKind.SAMPLING_PLOT && (it.secondaryValue ?: 0.0) > 0.0 }
                .maxByOrNull { it.createdAt }
                ?.secondaryValue
            if (m2 != null && m2 > 0.0) {
                return Pair(max(m2 / SQUARE_METRES_PER_ACRE, MINIMUM_ACRES), false)
            }
            return Pair(divisorAcres(null), true)
        }

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
            val acres = resolvedArea(plot, entries).first
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

/// The inverse of [AreaUnit.fromAcres] — an area TYPED in this unit, in the
/// acres the model stores. A metric cruise enters hectares and a US one acres,
/// and only this line knows which. iOS keeps the twin on its own `AreaUnit`.
fun AreaUnit.toAcres(value: Double): Double =
    if (this == AreaUnit.HECTARE) value * Units.ACRES_PER_HECTARE else value
