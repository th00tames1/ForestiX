package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.common.finiteNumberFormat
import com.hcjeong.forestix.common.Country
import com.hcjeong.forestix.export.CSVExporter
import com.hcjeong.forestix.inventory.VolumeEquationFactory
import com.hcjeong.forestix.inventory.StandStatsCalculator
import com.hcjeong.forestix.data.cruise.VolumeEquation
import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.inventory.PlotStatsCalculator
import com.hcjeong.forestix.inventory.SchumacherHall
import java.util.UUID
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class VolumeAvailabilityTest {
    @Test fun persistedPlaceholderEquationIsRejected() {
        val record = VolumeEquation("bruce-df-pnw", "bruce",
            mapOf("b0" to -2.6f, "b1" to 1.8f, "b2" to 1.1f), "cm,m", "m3",
            "PLACEHOLDER pending primary-source verification")
        assertNull(VolumeEquationFactory.make(record))
        assertNotNull(VolumeEquationFactory.make(record.copy(sourceCitation = "user-provided coefficients")))
        assertTrue(Country.UNITED_STATES.volumeStandardPending)
    }

    @Test fun unavailableVolumeDoesNotBecomeZeroInExports() {
        assertEquals("", CSVExporter.format(Double.NaN, 4))
        assertEquals("0.0000", CSVExporter.format(0.0, 4))
        assertEquals("Unavailable", finiteNumberFormat(Locale.US, "%.3f m3", Double.NaN))
        assertEquals("1.000 m3", finiteNumberFormat(Locale.US, "%.3f m3", 1.0))
    }

    @Test fun incompletePlotVolumeDoesNotBecomeCompleteStandVolume() {
        val r = StandStatsCalculator.compute(listOf("stand" to 2.0, "stand" to Double.NaN), mapOf("stand" to 1.0))
        assertTrue(r.mean.isNaN())
        assertTrue(r.ci95HalfWidth.isNaN())
        assertEquals(2, r.nPlots)
    }

    @Test fun missingSpeciesEquationInvalidatesPlotVolumeButPreservesTally() {
        val id = UUID.randomUUID()
        val plot = Plot(id, id, null, 1, 44.0, -123.0, PositionSource.MANUAL,
            PositionTier.A, 1, 0f, 0f, null, 0f, 0f,
            plotAreaAcres = .1f, startedAt = 0L, closedAt = null, closedBy = null,
            notes = "", coverPhotoPath = null, panoramaPath = null)
        val design = CruiseDesign(id, id, PlotType.FIXED_AREA, .1f, null,
            SamplingScheme.MANUAL, null)
        fun tree(code: String) = Tree(id = UUID.randomUUID(), plotId = id,
            treeNumber = 1, speciesCode = code, status = TreeStatus.LIVE,
            dbhCm = 30f, dbhMethod = DBHMethod.MANUAL_CALIPER, dbhSigmaMm = null,
            dbhRmseMm = null, dbhCoverageDeg = null, dbhNInliers = null,
            dbhConfidence = ConfidenceTier.GREEN, dbhIsIrregular = false,
            heightM = 25f, heightMethod = HeightMethod.MANUAL_ENTRY, heightSource = "measured",
            heightSigmaM = null, heightDHM = null, heightAlphaTopDeg = null,
            heightAlphaBaseDeg = null, heightConfidence = ConfidenceTier.GREEN,
            bearingFromCenterDeg = null, distanceFromCenterM = null, boundaryCall = null,
            crownClass = null, damageCodes = emptyList(), isMultistem = false,
            parentTreeId = null, notes = "", photoPath = null, rawScanPath = null,
            createdAt = 0L, updatedAt = 0L, deletedAt = null)
        val r = PlotStatsCalculator.compute(plot, design, listOf(tree("DF"), tree("WH")),
            emptyMap(), mapOf("DF" to SchumacherHall(mapOf("a" to .0001f, "b" to 2f, "c" to 1f))))
        assertEquals(2, r.liveTreeCount)
        assertEquals(20f, r.tpa, .001f)
        assertTrue(r.grossVolumePerAcreM3.isNaN())
        assertTrue(r.merchVolumePerAcreM3.isNaN())
        assertTrue(r.bySpecies.getValue("WH").grossVolumePerAcreM3.isNaN())
        assertTrue(r.bySpecies.getValue("DF").grossVolumePerAcreM3 > 0f)
    }
}
