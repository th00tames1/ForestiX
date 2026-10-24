package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.export.CSVExporter
import com.hcjeong.forestix.sensors.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.io.File
import java.util.UUID

class FieldTreeMeasurementIdentityTest {
    @Test fun registeredSpeciesNamesAreExportedAlongsideStableCode() {
        val s = com.hcjeong.forestix.common.SpeciesRegistration.make("Synthetic \"oak\", A", "Testus alpha", emptyList())
        val csv = CSVExporter.treesCSV(listOf(tree().copy(speciesCode = s.code)), listOf(s.unconfigured()))
        assertTrue(csv.contains("species_common_name,species_scientific_name"))
        assertTrue(csv.contains("\"Synthetic \"\"oak\"\", A\",Testus alpha")); assertTrue(csv.contains(s.code))
    }
    private fun tree()=Tree(id=UUID.randomUUID(),plotId=UUID.randomUUID(),treeNumber=7,treeName="0007",speciesCode="DF",status=TreeStatus.LIVE,
        dbhCm=25f,dbhMethod=DBHMethod.MANUAL_CALIPER,dbhSigmaMm=1f,dbhRmseMm=2f,dbhCoverageDeg=90f,dbhNInliers=60,dbhConfidence=ConfidenceTier.GREEN,dbhIsIrregular=false,
        heightM=20f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",heightSigmaM=.2f,heightDHM=13.625f,heightAlphaTopDeg=50f,heightAlphaBaseDeg=-5f,heightConfidence=ConfidenceTier.YELLOW,
        bearingFromCenterDeg=25f,distanceFromCenterM=4f,boundaryCall="in",crownClass="dominant",damageCodes=listOf("x"),isMultistem=false,parentTreeId=null,notes="original",photoPath=null,rawScanPath=null,
        createdAt=1700000000123,updatedAt=1700000000456,deletedAt=null,dbhMeasurementId=newFieldId().lowercase(),heightMeasurementId=newFieldId().lowercase())
    @Test fun metadataEditsKeepUUIDsAndLegacyNativeCodecsStayCompatible(){
        val original=tree();val edited=original.copy(notes="new metadata",treeName="new label",latitude=45.0,speciesCode="OTHER")
        assertEquals(edited,FieldTreeMeasurementIdentity.prepare(edited,original))
        val json=FieldInventoryTreeJSON.encode(edited);assertEquals(edited,FieldInventoryTreeJSON.decode(json))
        val legacy=FieldInventoryTreeJSON.decode(JsonObject(json.filterKeys{it !in listOf("dbhMeasurementId","heightMeasurementId")}))
        assertNull(legacy.dbhMeasurementId);assertNull(legacy.heightMeasurementId);assertEquals(edited.dbhCm,legacy.dbhCm)
        val intent=FieldInventoryUndoIntent(newFieldId(),edited,original,listOf(original.dbhMeasurementId!!,original.heightMeasurementId!!))
        assertEquals(intent,FieldInventoryUndoIntent.decode(intent.encode()))
        // The native mapper uses Android's org.json. Its actual Room roundtrip
        // is checked by LegacyCruiseIdentityPersistenceChecks, not android.jar stubs.
    }
    @Test fun unattributedChangesClearOnlyTheirKindAndCaseCannotBypassIt(){
        val original=tree();val changed=original.copy(dbhCm=26f,dbhMeasurementId=original.dbhMeasurementId!!.uppercase())
        val dbh=FieldTreeMeasurementIdentity.prepare(changed,original)
        assertNull(dbh.dbhMeasurementId);assertEquals(original.heightMeasurementId,dbh.heightMeasurementId);assertEquals(changed.dbhCm,dbh.dbhCm)
        val height=FieldTreeMeasurementIdentity.prepare(original.copy(heightSigmaM=1.2f),original)
        assertNull(height.heightMeasurementId);assertEquals(original.dbhMeasurementId,height.dbhMeasurementId)
    }
    @Test fun explicitCaptureAndUndoKeepUUIDsAndMalformedNewStampIsRefused(){
        val original=tree();val next=original.copy(dbhCm=27f,dbhMeasurementId=newFieldId())
        assertEquals(next,FieldTreeMeasurementIdentity.prepare(next,original));assertEquals(original,FieldTreeMeasurementIdentity.prepare(original,next))
        assertThrows(FieldStudyException::class.java){FieldTreeMeasurementIdentity.prepare(next.copy(dbhMeasurementId="invalid"),original)}
        assertEquals(20f,original.heightM)
    }
    @Test fun dbhRemeasurementPreservesHeightUUIDAndTreeCSVExportsBoth(){
        val original=tree();val measurement=original.copy(dbhCm=27f,dbhMeasurementId=newFieldId(),heightMeasurementId=newFieldId())
        val merged=FieldCruiseTreeUpdate.dbh(original,measurement,null)
        assertEquals(measurement.dbhMeasurementId,merged.dbhMeasurementId);assertEquals(original.heightMeasurementId,merged.heightMeasurementId)
        val csv=CSVExporter.treesCSV(listOf(merged));assertTrue(csv.contains("dbh_measurement_id,height_measurement_id,species_common_name,species_scientific_name\r\n"))
        assertTrue(csv.contains(",${merged.dbhMeasurementId},${merged.heightMeasurementId},,\r\n"))
    }
    @Test fun cruiseExportRequiresActualPerKindUUIDEvenWhenValuesMatch(){
        val root=Files.createTempDirectory("forestix-tree-id-export-").toFile()
        try{
            val a=FieldStudyArchive(File(root,"field"));val c=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="OP-1");a.saveConfiguration(c,true)
            val row=newFieldId();val r=FieldMeasurementRecord(sessionId=newFieldId(),treeId="unassigned",kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=emptyMap(),
                outcome=FieldAttemptOutcome.RESULT,selection=FieldSelectionStatus.ACCEPTED,liveValue=25.0,resultRecordId=row,resultStore="cruise");a.createMeasurement(r)
            fun build(id:String?,kind:FieldStudyKind=FieldStudyKind.DBH)=FieldStudyExport.build(a,File(root,"raw"),listOf(FieldExportApplicationResult(row,"cruise",kind,25.0,id)),outputDirectory=File(root,"exports"))
            assertTrue(build(r.id).report.integrityPassed)
            assertTrue(build(null).report.issues.any{it.code=="application_measurement_identity_unrecorded"})
            assertTrue(build(newFieldId()).report.issues.any{it.code=="application_measurement_identity_mismatch"})
            assertTrue(build(r.id,FieldStudyKind.HEIGHT).report.issues.any{it.code=="application_without_measurement"})
            assertEquals(r,a.measurement(r.id))
        }finally{root.deleteRecursively()}
    }
    @Test fun identifiedRowsAreExcludedFromLegacyEpochPlanning()=runBlocking{
        val tree=tree().copy(dbhEstimatorEpoch=0,dbhCaptureMode="manual",dbhMethod=DBHMethod.LIDAR_CHORD_SILHOUETTE)
        val project=DBHEpochRecompute.ProjectTrees(UUID.randomUUID(),"SYNTHETIC",listOf(tree))
        val plan=DBHEpochRecompute.plan(DBHEpochRecompute.BundleIndex(emptyMap()),project)
        assertTrue(plan.changes.isEmpty());assertEquals(DBHEpochRecompute.SkipReason.IdentifiedMeasurement,plan.skips.single().reason)
    }
    @Test fun legacyEpochCASCannotOverwriteAConcurrentSameValueNewCapture()=runBlocking{
        val legacy=tree().copy(dbhMeasurementId=null,dbhEstimatorEpoch=0,dbhCaptureMode="manual",dbhMethod=DBHMethod.LIDAR_CHORD_SILHOUETTE)
        var row=legacy;val latest=legacy.copy(dbhMeasurementId=newFieldId());var writes=0;var changeAfterRead=true
        val repo=object:TreeRepository{
            override suspend fun create(t:Tree)=t
            override suspend fun read(id:UUID,includeDeleted:Boolean):Tree{val before=FieldCruiseTreeUpdate.snapshot(row);if(changeAfterRead)row=latest;return before}
            override suspend fun update(t:Tree):Tree{writes++;row=t;return t}
            override suspend fun replaceIfUnchanged(next:Tree,expected:Tree):Tree{
                if(row!=expected)throw FieldStudyException("conflict","Changed result")
                writes++;row=FieldTreeMeasurementIdentity.prepare(next,expected);return row
            }
            override suspend fun delete(id:UUID,at:Long)=Unit
            override suspend fun hardDelete(id:UUID)=Unit
            override suspend fun listByPlot(plotId:UUID,includeDeleted:Boolean)=listOf(row)
            override suspend fun bySpeciesInProject(projectId:UUID,speciesCode:String,includeDeleted:Boolean)=listOf(row)
            override suspend fun recentSpeciesCodes(projectId:UUID,limit:Int)=emptyList<String>()
            override fun observeByPlot(plotId:UUID)=flowOf(listOf(row))
        }
        val change=DBHEpochRecompute.Change(legacy.id,"SYNTHETIC",legacy.treeNumber,newFieldId(),legacy.dbhCm.toDouble(),45.0)
        val refused=DBHEpochRecompute.apply(listOf(change),repo)
        assertEquals(0,refused.written);assertEquals(0,writes);assertEquals(latest,row)
        row=legacy;changeAfterRead=false
        val applied=DBHEpochRecompute.apply(listOf(change),repo)
        assertEquals(1,applied.written);assertEquals(45f,row.dbhCm);assertNull(row.dbhMeasurementId);assertEquals(legacy.heightMeasurementId,row.heightMeasurementId)
    }
}
