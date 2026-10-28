package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FieldResultBindingTest {
    private val a=FieldStudyTarget("SYNTHETIC","0003","003");private val b=FieldStudyTarget("SYNTHETIC","0004","004")
    private fun record(target:FieldStudyTarget=a,unassigned:Boolean=false,round:Int=1,operator:String="TEST",version:String="1",
        kind:FieldStudyKind=FieldStudyKind.DBH,store:String="quick_history",row:String?=null,accept:Boolean=true,runtime:Map<String,String> = emptyMap(),id:String=newFieldId()):FieldMeasurementRecord {
        val c=FieldStudyConfiguration(enabled=true,studyId=target.studyId,operatorId=operator,plannedRepeat=round,protocolVersion=version)
        val s=FieldMeasurementSession(0.0)
        s.begin(id,c,if(unassigned)null else target,kind,runtime,emptyList(),false,false,false,1.0)
        s.finish(id,25.0,1.0,true,null,FieldAIProvenance(),2.0)
        if(accept)s.accept(id,row?:id,store,3.0)
        return requireNotNull(s.current)
    }
    private fun resolve(ids:List<String>,records:List<FieldMeasurementRecord>,store:String="quick_history",targets:List<FieldStudyTarget> = listOf(a,b))=
        FieldResultTargetBinding.resolve(ids,store,a.studyId,records,targets)
    @Test fun reverseQuickLookupUsesExactUuidNotSelectionOrTreeNumber(){
        val r=record();assertEquals(a,resolve(listOf(r.id.lowercase()),listOf(r)))
        assertNull(resolve(listOf(newFieldId()),listOf(r)));assertNull(resolve(emptyList(),listOf(r)))
        assertNull(resolve(listOf(r.id),listOf(r.copy(selection=FieldSelectionStatus.PENDING))))
    }
    @Test fun cruiseSharedRowResolvesUniqueOwnerAcrossKindsAndRejectsConflict(){
        val row=newFieldId();val d=record(store="cruise",row=row);val h=record(kind=FieldStudyKind.HEIGHT,store="cruise",row=row)
        assertEquals(a,resolve(listOf(row),listOf(d,h),"cruise"))
        val wrong=record(target=b,kind=FieldStudyKind.HEIGHT,store="cruise",row=row)
        assertThrows(FieldStudyException::class.java){resolve(listOf(row),listOf(d,wrong),"cruise")}
    }
    @Test fun foreignStudyMissingCatalogOrMisboundQuickCannotFallThrough(){
        val foreign=record(target=FieldStudyTarget("OTHER","0003","003"))
        assertThrows(FieldStudyException::class.java){resolve(listOf(foreign.id),listOf(foreign))}
        val r=record();assertThrows(FieldStudyException::class.java){resolve(listOf(r.id),listOf(r),targets=listOf(b))}
        assertThrows(FieldStudyException::class.java){resolve(listOf(r.id,foreign.id),listOf(r,foreign))}
        val row=newFieldId();val wrong=record(row=row)
        assertThrows(FieldStudyException::class.java){resolve(listOf(row),listOf(wrong))}
    }
    @Test fun currentCatalogRevisionIsSelectedAfterExactIdentityResolution(){
        val r=record();val updated=a.copy(label="Updated catalog label")
        assertEquals(updated,resolve(listOf(r.id),listOf(r),targets=listOf(updated)))
    }
    @Test fun onlyAcceptedSameScopeReplacementRetiresOldRecord(){
        val root=Files.createTempDirectory("forestix-replacement-").toFile()
        try{
            val archive=FieldStudyArchive(root);val old=record();val newId=newFieldId();archive.mergeSessionSnapshot(old)
            assertThrows(FieldStudyException::class.java){archive.supersedeAcceptedMeasurement(old.id,newId)}
            val session=FieldMeasurementSession(0.0)
            session.begin(newId,old.configuration,a,FieldStudyKind.DBH,emptyMap(),emptyList(),false,false,false,1.0)
            session.finish(newId,25.0,1.0,true,null,FieldAIProvenance(),2.0)
            archive.mergeSessionSnapshot(requireNotNull(session.current))
            assertThrows(FieldStudyException::class.java){archive.supersedeAcceptedMeasurement(old.id,newId)}
            session.accept(newId,newId,"quick_history",3.0);val newer=requireNotNull(session.current)
            archive.mergeSessionSnapshot(newer)
            assertFalse(archive.supersedeAcceptedMeasurement(old.id,old.id.lowercase()))
            assertTrue(archive.supersedeAcceptedMeasurement(old.id,newer.id))
            assertEquals(FieldSelectionStatus.SUPERSEDED,archive.measurement(old.id).selection);assertEquals(25.0,archive.measurement(old.id).liveValue!!,0.0)
        }finally{root.deleteRecursively()}
    }
    @Test fun otherRoundsOperatorsProtocolsTargetsKindsStudiesAndStoresStayAccepted(){
        val variants=listOf(record(round=2),record(operator="ANOTHER"),record(version="2"),record(target=b),record(kind=FieldStudyKind.HEIGHT),
            record(store="cruise"),record(target=FieldStudyTarget("OTHER","0003","003")))
        for(newer in variants){
            val root=Files.createTempDirectory("forestix-retain-repeat-").toFile()
            try{
                val archive=FieldStudyArchive(root);val old=record();archive.mergeSessionSnapshot(old);archive.mergeSessionSnapshot(newer)
                assertFalse(archive.supersedeAcceptedMeasurement(old.id,newer.id))
                val retained=archive.measurement(old.id);assertEquals(FieldSelectionStatus.ACCEPTED,retained.selection)
                assertEquals("application_row_replaced_retained",retained.events.last().action);assertEquals(newer.id,retained.events.last().detail)
            }finally{root.deleteRecursively()}
        }
    }
    @Test fun unassignedRetirementNeedsExplicitMatchingApplicationScope(){
        assertFalse(FieldMeasurementReplacementScope.matches(record(unassigned=true),record(unassigned=true)))
        val ctx=mapOf("application.mode" to "quick","application.plot_id" to newFieldId(),"application.tree_number" to "7")
        val bound=record(unassigned=true,runtime=ctx)
        assertTrue(FieldMeasurementReplacementScope.matches(bound,record(unassigned=true,runtime=ctx)))
        assertFalse(FieldMeasurementReplacementScope.matches(bound,record(unassigned=true,runtime=ctx+("application.tree_number" to "8"))))
    }
}
