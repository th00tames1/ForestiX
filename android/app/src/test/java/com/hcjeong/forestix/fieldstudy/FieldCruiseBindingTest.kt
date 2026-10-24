package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.data.cruise.Tree
import com.hcjeong.forestix.data.cruise.TreeStatus
import com.hcjeong.forestix.sensors.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class FieldCruiseBindingTest {
    private val plot=newFieldId()
    private val target=FieldStudyTarget("SYNTHETIC","0003","003")
    private val other=FieldStudyTarget("SYNTHETIC","0004","004")
    private fun row(number:Int=7,plot:String=this.plot,deleted:Boolean=false,id:String=newFieldId())=FieldInventoryRow(id,plot,number,deleted)
    private fun record(row:FieldInventoryRow,kind:FieldStudyKind=FieldStudyKind.DBH,target:FieldStudyTarget=this.target):FieldMeasurementRecord{
        val c=FieldStudyConfiguration(enabled=true,studyId=target.studyId,operatorId="TEST");val s=FieldMeasurementSession(0.0);val id=newFieldId()
        s.begin(id,c,target,kind,emptyMap(),emptyList(),false,false,false,1.0)
        s.finish(id,25.0,1.0,true,null,FieldAIProvenance(),2.0);s.accept(id,row.id,"cruise",3.0)
        return requireNotNull(s.current)
    }
    private fun resolve(rows:List<FieldInventoryRow>,records:List<FieldMeasurementRecord>)=FieldCruiseTargetBinding.resolve(target,plot,rows,records)
    @Test fun distinctDbhAndHeightAttemptIDsShareOneInventoryRow(){
        val r=row();val d=record(r);val h=record(r,FieldStudyKind.HEIGHT)
        val result=resolve(listOf(r.copy(id=r.id.uppercase())),listOf(d,h))
        assertNotEquals(d.id,h.id);assertNotEquals(d.id,r.id);assertEquals(r.id.lowercase(),result.existingRowId);assertEquals(7,result.treeNumber)
        assertEquals(setOf(d.id.lowercase(),h.id.lowercase()),result.sourceMeasurementIds.toSet())
        assertEquals(result,FieldCruiseTargetBinding.resolve(target,plot.uppercase(),listOf(r),listOf(d,h)))
    }
    @Test fun externalIDNeverBecomesLocalNumberAndDeletedNumbersStayReserved(){
        val result=resolve(listOf(row(12),row(15,deleted=true),row(800,plot=newFieldId())),emptyList())
        assertEquals(16,result.treeNumber);assertNull(result.existingRowId);assertTrue(result.sourceMeasurementIds.isEmpty());assertEquals("0003",target.treeId)
    }
    @Test fun missingDeletedOrWrongPlotRowsDoNotBind(){
        val r=row();val d=record(r)
        assertNull(resolve(emptyList(),listOf(d)).existingRowId)
        assertNull(resolve(listOf(r.copy(deleted=true)),listOf(d)).existingRowId)
        assertNull(resolve(listOf(r.copy(plotId=newFieldId())),listOf(d)).existingRowId)
    }
    @Test fun pendingFailedDiscardedAndQuickRecordsAreIgnored(){
        val r=row();val d=record(r)
        val variants=listOf(d.copy(selection=FieldSelectionStatus.PENDING),d.copy(outcome=FieldAttemptOutcome.FAILED),
            d.copy(selection=FieldSelectionStatus.DISCARDED),d.copy(resultStore="quick_history"))
        variants.forEach{assertNull(resolve(listOf(r),listOf(it)).existingRowId)}
        assertEquals(r.id.lowercase(),resolve(listOf(r),listOf(d.copy(selection=FieldSelectionStatus.SUPERSEDED))).existingRowId)
    }
    @Test fun conflictingRowsOwnersAndLocalNumbersAreRefused(){
        val a=row(2);val b=row(8);val ar=record(a);val br=record(b)
        assertThrows(FieldStudyException::class.java){resolve(listOf(a,b),listOf(ar,br))}
        assertThrows(FieldStudyException::class.java){resolve(listOf(a),listOf(ar,record(a,target=other)))}
        assertThrows(FieldStudyException::class.java){resolve(listOf(a,row(2)),listOf(ar))}
        assertEquals(a.id.lowercase(),resolve(listOf(a,row(2,deleted=true)),listOf(ar)).existingRowId)
    }
    @Test fun duplicateMalformedIDsAndNumberCapacityAreRefused(){
        val a=row();val ar=record(a)
        assertThrows(FieldStudyException::class.java){resolve(listOf(a,a),listOf(ar))}
        assertThrows(FieldStudyException::class.java){resolve(listOf(row(id="not-a-uuid")),emptyList())}
        assertThrows(FieldStudyException::class.java){resolve(listOf(row(plot="not-a-uuid")),emptyList())}
        assertThrows(FieldStudyException::class.java){resolve(listOf(row(Int.MAX_VALUE)),emptyList())}
        val zero=row(0);assertThrows(FieldStudyException::class.java){resolve(listOf(zero),listOf(record(zero)))}
        assertThrows(FieldStudyException::class.java){resolve(listOf(a),listOf(ar.copy(id="not-a-uuid")))}
    }
    private fun tree()=Tree(id=UUID.randomUUID(),plotId=UUID.randomUUID(),treeNumber=7,treeName="P1-T07",speciesCode="DF",status=TreeStatus.DEAD_STANDING,
        dbhCm=30f,dbhMethod=DBHMethod.MANUAL_CALIPER,dbhSigmaMm=1f,dbhRmseMm=2f,dbhCoverageDeg=90f,dbhNInliers=60,
        dbhConfidence=ConfidenceTier.GREEN,dbhIsIrregular=true,dbhCaptureMode="typed",dbhEstimatorEpoch=1,
        heightM=21f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",heightSigmaM=0.2f,heightDHM=10f,
        heightAlphaTopDeg=50f,heightAlphaBaseDeg=-5f,heightConfidence=ConfidenceTier.YELLOW,bearingFromCenterDeg=25f,distanceFromCenterM=4f,
        boundaryCall="in",crownClass="dominant",damageCodes=listOf("OLD"),isMultistem=true,parentTreeId=UUID.randomUUID(),
        notes="prior note",photoPath="old.jpg",rawScanPath="old.raw",createdAt=100,updatedAt=101,deletedAt=null,latitude=45.0,longitude=-123.0)
    @Test fun dbhReplacementPreservesUnrelatedMetadataAndDoesNotAliasLists(){
        val codes=mutableListOf("OLD");val old=tree().copy(damageCodes=codes);val frozen=FieldCruiseTreeUpdate.snapshot(old)
        val measurement=tree().copy(plotId=old.plotId,dbhCm=42f,dbhMethod=DBHMethod.LIDAR_CHORD_SILHOUETTE,dbhSigmaMm=3f,
            dbhRmseMm=null,dbhCoverageDeg=100f,dbhNInliers=80,dbhConfidence=ConfidenceTier.YELLOW,dbhIsIrregular=false,dbhCaptureMode="auto",dbhEstimatorEpoch=7,
            heightM=null,notes="new note",damageCodes=listOf("OLD","NEW","NEW"),photoPath="new.jpg",rawScanPath=null,latitude=0.0,longitude=0.0,updatedAt=200)
        val next=FieldCruiseTreeUpdate.dbh(old,measurement,null)
        val expected=old.copy(dbhCm=42f,dbhMethod=DBHMethod.LIDAR_CHORD_SILHOUETTE,dbhSigmaMm=3f,dbhRmseMm=null,dbhCoverageDeg=100f,dbhNInliers=80,
            dbhConfidence=ConfidenceTier.YELLOW,dbhIsIrregular=false,dbhCaptureMode="auto",dbhEstimatorEpoch=7,
            notes="prior note\nnew note",damageCodes=listOf("OLD","NEW"),photoPath="new.jpg",rawScanPath=null,updatedAt=200)
        assertEquals(expected,next);codes.add("MUTATED");assertEquals(listOf("OLD"),frozen.damageCodes);assertEquals(listOf("OLD","NEW"),next.damageCodes)
    }
    @Test fun emptyMetadataNeverErasesAndOnlyMissingPositionIsFilled(){
        val old=tree();val measurement=tree().copy(plotId=old.plotId,notes="",damageCodes=emptyList(),photoPath=null)
        assertEquals("old.jpg",FieldCruiseTreeUpdate.dbh(old,measurement,null).photoPath)
        assertEquals("WH",FieldCruiseTreeUpdate.dbh(old,measurement,"WH").speciesCode)
        assertEquals(old.notes,FieldCruiseTreeUpdate.dbh(old,measurement.copy(notes=old.notes),null).notes)
        val partial=FieldCruiseTreeUpdate.dbh(old.copy(longitude=null,distanceFromCenterM=null),measurement.copy(latitude=2.0,longitude=3.0),null)
        assertEquals(45.0,partial.latitude);assertNull(partial.longitude);assertNull(partial.distanceFromCenterM)
        val missing=FieldCruiseTreeUpdate.dbh(old.copy(latitude=null,longitude=null,bearingFromCenterDeg=null,distanceFromCenterM=null),measurement.copy(latitude=2.0,longitude=3.0),null)
        assertEquals(2.0,missing.latitude);assertEquals(3.0,missing.longitude);assertEquals(4f,missing.distanceFromCenterM)
    }
    @Test fun movedAndDeletedTreesCannotBeRemeasured(){
        val old=tree();val measurement=tree()
        assertThrows(FieldStudyException::class.java){FieldCruiseTreeUpdate.dbh(old,measurement,null)}
        assertThrows(FieldStudyException::class.java){FieldCruiseTreeUpdate.dbh(old.copy(plotId=measurement.plotId,deletedAt=200),measurement,null)}
    }
}
