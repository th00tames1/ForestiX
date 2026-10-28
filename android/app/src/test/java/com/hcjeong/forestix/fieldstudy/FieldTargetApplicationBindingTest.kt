package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FieldTargetApplicationBindingTest {
    private val plot=newFieldId()
    private val target=FieldStudyTarget("SYNTHETIC","0003","003")
    private val other=FieldStudyTarget("SYNTHETIC","0004","004")
    private fun pair(number:Int?,plot:String?,kind:FieldStudyKind=FieldStudyKind.DBH,target:FieldStudyTarget=this.target,id:String=newFieldId()):Pair<FieldApplicationReading,FieldMeasurementRecord>{
        val c=FieldStudyConfiguration(enabled=true,studyId=target.studyId,operatorId="TEST");val s=FieldMeasurementSession(0.0)
        s.begin(id,c,target,kind,emptyMap(),emptyList(),false,false,false,1.0)
        s.finish(id,25.0,1.0,true,null,FieldAIProvenance(),2.0);s.accept(id,id,"quick_history",3.0)
        return FieldApplicationReading(id,kind,plot,number) to requireNotNull(s.current)
    }
    @Test fun externalNumericStringNeverBecomesLocalNumberWithoutExactResultLink(){
        val row=FieldApplicationReading(newFieldId(),null,plot,12)
        val s=FieldTargetApplicationBinding.resolve(target,plot,listOf(row),emptyList())
        assertEquals(13,s.treeNumber);assertFalse(s.existing);assertEquals("0003",target.treeId);assertTrue(s.sourceResultIds.isEmpty())
    }
    @Test fun dbhAndHeightReuseOneExactApplicationTreeWithCaseInsensitiveUuids(){
        val(a,ar)=pair(7,plot.uppercase());val(b,br)=pair(7,plot,FieldStudyKind.HEIGHT)
        val s=FieldTargetApplicationBinding.resolve(target,plot,listOf(a.copy(id=a.id.uppercase()),b),listOf(ar,br))
        assertEquals(7,s.treeNumber);assertTrue(s.existing);assertEquals(setOf(a.id.lowercase(),b.id.lowercase()),s.sourceResultIds.toSet())
    }
    @Test fun deletedPendingFailedAndCruiseRowsCannotProvideQuickBinding(){
        val(row,r)=pair(5,plot)
        val variants=listOf(emptyList(),listOf(r.copy(selection=FieldSelectionStatus.PENDING)),listOf(r.copy(outcome=FieldAttemptOutcome.FAILED)),listOf(r.copy(resultStore="cruise")))
        for(records in variants){val s=FieldTargetApplicationBinding.resolve(target,plot,listOf(row),records)
            assertEquals(6,s.treeNumber);assertFalse(s.existing)}
        assertFalse(FieldTargetApplicationBinding.resolve(target,plot,emptyList(),listOf(r)).existing)
    }
    @Test fun bindingIsPlotScopedAndCounterIncludesOtherPlots(){
        val p=newFieldId();val(a,ar)=pair(2,plot);val(b,br)=pair(8,p)
        assertEquals(2,FieldTargetApplicationBinding.resolve(target,plot,listOf(a,b),listOf(ar,br)).treeNumber)
        assertEquals(8,FieldTargetApplicationBinding.resolve(target,p,listOf(a,b),listOf(ar,br)).treeNumber)
        assertEquals(9,FieldTargetApplicationBinding.resolve(other,plot,listOf(a,b),listOf(ar,br)).treeNumber)
    }
    @Test fun conflictingNumbersOrTargetsAreNotGuessedThrough(){
        val(a,ar)=pair(2,plot);val(b,br)=pair(8,plot);val(f,fr)=pair(2,plot,target=other)
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(a,b),listOf(ar,br))}
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(a,f),listOf(ar,fr))}
    }
    @Test fun duplicateMisboundOrWrongKindUuidsAreRejected(){
        val(row,r)=pair(2,plot);val(_,foreign)=pair(2,plot,target=other,id=row.id)
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(row,row),listOf(r))}
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(row),listOf(r,foreign))}
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(row.copy(kind=FieldStudyKind.HEIGHT)),listOf(r))}
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(row),listOf(r.copy(id=newFieldId())))}
    }
    @Test fun invalidNumbersAndUuidsCannotOverflowOrInventBindings(){
        val full=FieldApplicationReading(newFieldId(),FieldStudyKind.DBH,plot,Int.MAX_VALUE)
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(full),emptyList())}
        val(row,r)=pair(null,plot)
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(row),listOf(r))}
        assertThrows(FieldStudyException::class.java){FieldTargetApplicationBinding.resolve(target,plot,listOf(row.copy(id="not-a-uuid")),emptyList())}
    }
    @Test fun nilPlotAndSupersededStillPresentLinksRemainExact(){
        val(row,r)=pair(6,null)
        assertTrue(FieldTargetApplicationBinding.resolve(target,null,listOf(row),listOf(r.copy(selection=FieldSelectionStatus.SUPERSEDED))).existing)
        assertFalse(FieldTargetApplicationBinding.resolve(target,plot,listOf(row),listOf(r)).existing)
    }
}
