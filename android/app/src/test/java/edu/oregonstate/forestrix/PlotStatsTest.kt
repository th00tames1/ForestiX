package edu.oregonstate.forestrix

import edu.oregonstate.forestrix.inventory.PlotStatsCalculator
import edu.oregonstate.forestrix.models.CruiseDesign
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.Plot
import edu.oregonstate.forestrix.models.Project
import edu.oregonstate.forestrix.models.Tree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlotStatsTest {
    @Test
    fun fixedAreaPlotStatsMatchCruiseMath() {
        val project = Project(name = "test")
        val plot = Plot(
            projectId = project.id,
            plotNumber = 1,
            centerLat = 44.0,
            centerLon = -123.0,
            plotAreaAcres = 0.1f
        )
        val design = CruiseDesign(projectId = project.id, plotAreaAcres = 0.1f)
        val trees = listOf(
            Tree(plotId = plot.id, treeNumber = 1, speciesCode = "DF", dbhCm = 40f, heightM = 30f, dbhMethod = DbhMethod.MANUAL_CALIPER),
            Tree(plotId = plot.id, treeNumber = 2, speciesCode = "WH", dbhCm = 20f, heightM = 22f, dbhMethod = DbhMethod.MANUAL_CALIPER)
        )

        val stats = PlotStatsCalculator.compute(plot, design, trees)

        assertEquals(2, stats.liveTreeCount)
        assertEquals(20f, stats.tpa, 0.001f)
        assertEquals(1.57f, stats.baPerAcreM2, 0.03f)
        assertEquals(31.62f, stats.qmdCm, 0.05f)
        assertTrue(stats.grossVolumePerAcreM3 > 0f)
        assertEquals(stats.grossVolumePerAcreM3 * 0.85f, stats.merchVolumePerAcreM3, 0.001f)
    }
}
