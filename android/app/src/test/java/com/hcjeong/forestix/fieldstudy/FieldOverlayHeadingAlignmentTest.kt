package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.FieldOverlayHeadingAlignment
import org.junit.Assert.*
import org.junit.Test

class FieldOverlayHeadingAlignmentTest{
    @Test fun pairedHeadingThenARRotationAvoidsCompassDisplayLag(){
        val a=FieldOverlayHeadingAlignment()
        assertEquals(10.0,a.update(30.0,10.0,10.0,10.0,10.01,2.0,true)!!,1e-9)
        assertEquals(40.0,a.update(60.0,10.2,10.0,10.0,10.21,2.0,true)!!,1e-9)
        assertEquals(20.0,a.worldNorthOffsetDegrees!!,1e-9)
        assertEquals(45.0,a.update(65.0,10.3,45.0,10.3,10.31,2.0,true)!!,1e-9)
    }
    @Test fun unpairedOrExpiredEvidenceCannotInventNorth(){
        val a=FieldOverlayHeadingAlignment()
        assertNull(a.update(30.0,10.2,10.0,10.0,10.21,2.0,true))
        assertNull(a.worldNorthOffsetDegrees)
        assertNotNull(a.update(30.0,11.0,10.0,11.0,11.01,2.0,true))
        assertNull(a.update(30.0,13.1,10.0,11.0,13.11,2.0,true))
        assertNull(a.worldNorthOffsetDegrees)
    }
    @Test fun wrapTrackingLossAndInvalidClocksFailClosed(){
        val a=FieldOverlayHeadingAlignment()
        assertEquals(350.0,a.update(5.0,10.0,350.0,10.0,10.01,2.0,true)!!,1e-9)
        assertEquals(10.0,a.update(25.0,10.2,350.0,10.0,10.21,2.0,true)!!,1e-9)
        assertNull(a.update(25.0,10.3,10.0,10.3,10.31,2.0,false));assertNull(a.worldNorthOffsetDegrees)
        assertNull(a.update(25.0,10.5,10.0,10.5,10.4,2.0,true))
        assertNull(a.update(Double.NaN,10.5,10.0,10.5,10.6,2.0,true))
        assertNull(a.update(25.0,10.5,10.0,10.5,10.6,-1.0,true))
    }
}
