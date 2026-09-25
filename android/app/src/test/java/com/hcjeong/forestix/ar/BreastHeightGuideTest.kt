package com.hcjeong.forestix.ar

import com.hcjeong.forestix.common.BreastHeightGuideHeight
import com.hcjeong.forestix.common.UnitSystem
import org.junit.Assert.*
import org.junit.Test

class BreastHeightGuideTest {
    @Test fun placementIsOptInAndHeightChangesNeverArmIt() {
        val guide = BreastHeightGuide(ArController())
        guide.height = BreastHeightGuideHeight.METERS_137
        guide.updateGhost(Vec3(1f, 2f, 3f))
        assertEquals(BreastHeightGuide.Stage.OFF, guide.stage)
        assertNull(guide.ghostPoint)
        assertFalse(guide.place(Vec3(1f, 2f, 3f)))
        assertTrue(guide.markers().isEmpty())
        guide.arm()
        assertEquals(BreastHeightGuide.Stage.AIMING, guide.stage)
        guide.disable() // Cancel, clear, or advance to the next tree.
        guide.height = BreastHeightGuideHeight.METERS_130
        assertEquals(BreastHeightGuide.Stage.OFF, guide.stage)
        assertTrue(guide.markers().isEmpty())
    }

    @Test fun defaultsAndSavedChoice() {
        assertEquals(BreastHeightGuideHeight.METERS_130, BreastHeightGuideHeight.fromRaw(null))
        assertEquals(BreastHeightGuideHeight.METERS_130, BreastHeightGuideHeight.fromRaw("invalid"))
        for (height in BreastHeightGuideHeight.entries) {
            assertEquals(height, BreastHeightGuideHeight.fromRaw(height.raw))
        }
    }

    @Test fun aimingUsesOnlyASmallBaseDotAndResetClearsIt() {
        val guide = BreastHeightGuide(ArController())
        assertTrue(guide.markers().isEmpty())
        guide.arm()
        guide.updateGhost(Vec3(2f, -4f, 6f))
        assertEquals(1, guide.markers().size)
        assertEquals(MarkerShape.Sphere(0.015f), guide.markers().single().shape)
        assertNull(guide.heightWorldPoint())
        guide.clearBase()
        assertTrue(guide.markers().isEmpty())
        assertNull(guide.heightWorldPoint())
    }

    @Test fun disablingGuideClearsItsStateUntilRearmed() {
        val guide = BreastHeightGuide(ArController())
        guide.arm()
        guide.updateGhost(Vec3(2f, -4f, 6f))
        assertFalse(guide.markers().isEmpty())

        guide.disable()
        assertEquals(BreastHeightGuide.Stage.OFF, guide.stage)
        assertNull(guide.basePoint)
        assertNull(guide.ghostPoint)
        assertNull(guide.heightWorldPoint())
        assertTrue(guide.markers().isEmpty())

        guide.clearBase()
        assertEquals(BreastHeightGuide.Stage.OFF, guide.stage)
        guide.arm()
        assertEquals(BreastHeightGuide.Stage.AIMING, guide.stage)
        assertTrue(guide.markers().isEmpty())
    }

    @Test fun placedGuideHasFullHeightRiserAndThinRing() {
        for (ground in listOf(Vec3(2f, -4f, 6f), Vec3(-3f, 8f, -2f))) {
            for (height in BreastHeightGuideHeight.entries) {
                val markers = BreastHeightGuide.placementMarkers(ground, height)
                assertEquals(3, markers.size)
                assertEquals(ground, markers[0].worldPosition)
                val riser = markers[1]
                val h = height.meters.toFloat()
                assertEquals(ground.x, riser.worldPosition.x, 0f)
                assertEquals(ground.z, riser.worldPosition.z, 0f)
                assertEquals(h / 2f, riser.worldPosition.y - ground.y, 0.000001f)
                val cylinder = riser.shape as MarkerShape.Cylinder
                assertEquals(h, cylinder.heightM, 0f)
                assertEquals(0.004f, cylinder.radiusM, 0f)
                assertFalse(riser.scalesWithDistance)
                val ring = markers[2]
                assertEquals(ground.y + h, ring.worldPosition.y, 0.000001f)
                assertEquals(MarkerShape.Torus(0.35f, 0.01f), ring.shape)
                assertFalse(ring.scalesWithDistance)
            }
        }
    }

    @Test fun displayUnitsChangeOnlyTheLabel() {
        val guide = BreastHeightGuide(ArController())
        guide.arm()
        guide.updateGhost(Vec3(1f, -2f, 3f))
        val metricGeometry = guide.markers()
        assertEquals("1.30 m", guide.label(UnitSystem.METRIC))
        assertEquals("4.27 ft", guide.label(UnitSystem.IMPERIAL))
        assertEquals(metricGeometry, guide.markers())
        guide.height = BreastHeightGuideHeight.METERS_137
        assertEquals("1.37 m", guide.label(UnitSystem.METRIC))
        assertEquals("4.5 ft", guide.label(UnitSystem.IMPERIAL))
    }
}
