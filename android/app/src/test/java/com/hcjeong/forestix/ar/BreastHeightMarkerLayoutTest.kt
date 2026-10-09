package com.hcjeong.forestix.ar

import com.hcjeong.forestix.common.BreastHeightMarkerLayout
import org.junit.Assert.*
import org.junit.Test

class BreastHeightMarkerLayoutTest {
    @Test fun ticksAndLabelLeaveBracketAndCenterClear() {
        val layout = BreastHeightMarkerLayout.make(200f, 400f, 400f, 800f, 140f, 260f)!!
        assertEquals(400f, layout.y, 0f)
        assertTrue(layout.leftTick!!.endInclusive < 140f)
        assertTrue(layout.rightTick!!.start > 260f)
        assertTrue(layout.labelCenterX!! - 32f > 260f)
    }

    @Test fun labelMovesLeftAtRightEdgeWithoutMovingHeight() {
        val layout = BreastHeightMarkerLayout.make(360f, 350f, 400f, 800f)!!
        assertNull(layout.rightTick)
        assertTrue(layout.labelCenterX!! + 32f < 360f)
        assertEquals(350f, layout.y, 0f)
    }

    @Test fun offScreenAndInvalidPointsAreHidden() {
        for ((x, y) in listOf(-1f to 300f, 401f to 300f, 200f to -1f,
            200f to 801f, Float.NaN to 300f, 200f to Float.POSITIVE_INFINITY)) {
            assertNull(BreastHeightMarkerLayout.make(x, y, 400f, 800f))
        }
    }

    @Test fun noSpaceHidesLabelInsteadOfCoveringStem() {
        val layout = BreastHeightMarkerLayout.make(200f, 400f, 400f, 800f, 60f, 340f)!!
        assertNotNull(layout.leftTick)
        assertNotNull(layout.rightTick)
        assertNull(layout.labelCenterX)
        assertNull(BreastHeightMarkerLayout.make(200f, 400f, 400f, 800f, 10f, 390f))
    }
}
