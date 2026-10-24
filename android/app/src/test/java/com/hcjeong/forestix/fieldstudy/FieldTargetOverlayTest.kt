package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test

class FieldTargetOverlayTest {
    private fun configuration()=FieldARConfiguration(enabled=true)
    private fun fix()=FieldOverlayLocation(0.0,0.0,1.0,0.2,0.0,2.0,0.1,true)
    private fun target(n:Int,northM:Double,eastM:Double=0.0)=FieldStudyTarget("S","T$n","$n",northM/111195.0802335,eastM/111195.0802335)
    @Test fun positionAndHeadingFailuresHideOnlyTheOverlay(){
        val c=configuration()
        assertEquals(FieldOverlayStatus.POSITION_UNAVAILABLE,FieldTargetOverlayPolicy.quality(c,null))
        assertEquals(FieldOverlayStatus.POSITION_STALE,FieldTargetOverlayPolicy.quality(c,fix().copy(fixAgeSeconds=3.0)))
        assertEquals(FieldOverlayStatus.POSITION_UNCERTAIN,FieldTargetOverlayPolicy.quality(c,fix().copy(horizontalAccuracyM=4.0)))
        assertEquals(FieldOverlayStatus.HEADING_UNAVAILABLE,FieldTargetOverlayPolicy.quality(c,fix().copy(headingTrueDegrees=null)))
        assertEquals(FieldOverlayStatus.HEADING_STALE,FieldTargetOverlayPolicy.quality(c,fix().copy(headingAgeSeconds=3.0)))
        assertEquals(FieldOverlayStatus.HEADING_UNCERTAIN,FieldTargetOverlayPolicy.quality(c,fix().copy(headingAccuracyDegrees=11.0)))
        assertEquals(FieldOverlayStatus.TRACKING_UNAVAILABLE,FieldTargetOverlayPolicy.quality(c,fix().copy(arTrackingNormal=false)))
    }
    @Test fun rangeFieldOfViewCountAndStudyAreFiltered(){
        val ts=listOf(target(1,5.0),target(2,10.0),target(3,15.0),target(4,30.0),target(5,-5.0),target(6,5.0,10.0),
            FieldStudyTarget("OTHER","1","1",0.00001,0.0))
        val d=FieldTargetOverlayPolicy.evaluate(configuration().copy(maximumPoints=2),"S",ts,fix(),60.0)
        assertEquals(FieldOverlayStatus.READY,d.status);assertEquals(listOf("1","2"),d.candidates.map{it.label})
    }
    @Test fun hysteresisRetainsButDoesNotAdmitANewDistantTarget(){
        val t=target(1,21.0)
        assertTrue(FieldTargetOverlayPolicy.evaluate(configuration(),"S",listOf(t),fix(),60.0).candidates.isEmpty())
        assertEquals(1,FieldTargetOverlayPolicy.evaluate(configuration(),"S",listOf(t),fix(),60.0,setOf(t.id)).candidates.size)
    }
    @Test fun selectionSurvivesCountButNeverOverridesPositionQuality(){
        val c=configuration().copy(maximumPoints=1);val t=target(2,10.0)
        assertEquals(t.id,FieldTargetOverlayPolicy.evaluate(c,"S",listOf(target(1,5.0),t),fix(),60.0,selectedTargetId=t.id).candidates.first().targetId)
        assertTrue(FieldTargetOverlayPolicy.evaluate(c,"S",listOf(t),fix().copy(horizontalAccuracyM=99.0),60.0,selectedTargetId=t.id).candidates.isEmpty())
    }
    @Test fun wraparoundAndUnlocatedTreesAreHandled(){
        assertEquals(-1.0,FieldTargetOverlayPolicy.wrappedDegrees(359.0),0.0)
        assertEquals(1.0,FieldTargetOverlayPolicy.wrappedDegrees(-359.0),0.0)
        assertTrue(FieldTargetOverlayPolicy.evaluate(configuration(),"S",listOf(FieldStudyTarget("S","T","1")),fix(),60.0).candidates.isEmpty())
    }
    @Test fun badFixAndViewportDoNotReuseOldPoints(){
        assertEquals(FieldOverlayStatus.POSITION_UNAVAILABLE,FieldTargetOverlayPolicy.quality(configuration(),fix().copy(latitude=Double.NaN)))
        assertEquals(FieldOverlayStatus.HEADING_UNCERTAIN,FieldTargetOverlayPolicy.quality(configuration(),fix().copy(headingAccuracyDegrees=-1.0)))
        assertEquals(FieldOverlayStatus.VIEWPORT_UNAVAILABLE,FieldTargetOverlayPolicy.evaluate(configuration(),"S",emptyList(),fix(),Double.NaN).status)
    }
}
