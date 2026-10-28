package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.FieldStudyTarget
import com.hcjeong.forestix.ui.PendingTreeNumber
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PendingFieldTargetTest {
    @Test fun scopedTargetAndPlotTravelTogetherOnceWithoutChangingLegacyLocks(){
        PendingTreeNumber.consume() // This test process has no application session.
        try{
            val target=FieldStudyTarget("SYNTHETIC","0003","003");val plot=UUID.randomUUID()
            PendingTreeNumber.set(7,name=target.treeId,plotID=plot,fieldTarget=target)
            PendingTreeNumber.clearFieldTarget("different-target")
            val lock=requireNotNull(PendingTreeNumber.consume())
            assertEquals(7,lock.number);assertEquals("0003",lock.name);assertEquals(plot,lock.plotID);assertEquals(target,lock.fieldTarget)
            assertNull(PendingTreeNumber.consume())
            PendingTreeNumber.set(7,fieldTarget=target);PendingTreeNumber.clearFieldTarget(target.id);assertNull(PendingTreeNumber.consume())
            PendingTreeNumber.set(9);val legacy=requireNotNull(PendingTreeNumber.consume())
            assertEquals(9,legacy.number);assertNull(legacy.fieldTarget);assertNull(legacy.plotID)
            assertFalse(legacy.fieldTargetLocked)
            PendingTreeNumber.set(7,plotID=plot,fieldTargetLocked=true)
            val unassigned=requireNotNull(PendingTreeNumber.consume())
            assertTrue(unassigned.fieldTargetLocked);assertNull(unassigned.fieldTarget);assertEquals(plot,unassigned.plotID)
        }finally{PendingTreeNumber.consume()}
    }
}
