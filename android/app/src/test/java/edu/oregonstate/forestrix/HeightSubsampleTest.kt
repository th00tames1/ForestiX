package edu.oregonstate.forestrix

import edu.oregonstate.forestrix.inventory.HeightSubsample
import edu.oregonstate.forestrix.models.DbhMethod
import edu.oregonstate.forestrix.models.HeightSubsampleRule
import edu.oregonstate.forestrix.models.Tree
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class HeightSubsampleTest {
    @Test
    fun everyKthMatchesIosFirstTreeThenEveryK() {
        val rule = HeightSubsampleRule.EveryKth(5)

        assertTrue(HeightSubsample.shouldMeasureHeight(rule, 1, "DF", emptyList()))
        assertFalse(HeightSubsample.shouldMeasureHeight(rule, 2, "DF", emptyList()))
        assertTrue(HeightSubsample.shouldMeasureHeight(rule, 6, "DF", emptyList()))
    }

    @Test
    fun perSpeciesCountsOnlyMeasuredLiveTrees() {
        val plotId = UUID.randomUUID()
        val measured = Tree(
            plotId = plotId,
            treeNumber = 1,
            speciesCode = "DF",
            dbhCm = 40f,
            dbhMethod = DbhMethod.MANUAL_CALIPER,
            heightM = 30f,
            heightSource = "measured"
        )
        val rule = HeightSubsampleRule.PerSpeciesCount(1)

        assertFalse(HeightSubsample.shouldMeasureHeight(rule, 2, "DF", listOf(measured)))
        assertTrue(HeightSubsample.shouldMeasureHeight(rule, 2, "WH", listOf(measured)))
    }
}
