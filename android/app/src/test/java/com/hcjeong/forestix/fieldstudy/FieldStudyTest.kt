package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlinx.serialization.json.*

class FieldStudyTest {
    @get:Rule val temp=TemporaryFolder()
    private fun config()=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC-STUDY",operatorId="OP-1")
    private fun archive()=FieldStudyArchive(temp.newFolder())
    private fun record(c:FieldStudyConfiguration=config())=FieldMeasurementRecord(sessionId=newFieldId(),treeId="TREE-034",
        kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=mapOf("estimator_epoch" to "9","sampling_fraction" to "0.8"))

    @Test fun configurationRoundTripsSnakeCaseWithoutAcronymLoss(){
        val c=config();val text=FieldStudyJson.configurationJson(c).toString()
        assertTrue(text.contains("\"preserve_ai_inputs\":true"));assertEquals(c,FieldStudyJson.configuration(text))
    }
    @Test fun protocolLockRequiresVersionChange(){
        val c=config().copy(protocolLocked=true)
        assertThrows(FieldStudyException::class.java){c.copy(ar=c.ar.copy(maximumDistanceM=30.0)).validateTransition(c,true)}
        c.copy(protocolVersion="2",ar=c.ar.copy(maximumDistanceM=30.0)).validateTransition(c,true)
        assertThrows(FieldStudyException::class.java){c.copy(protocolLocked=false).validateTransition(c,true)}
        c.copy(operatorId="OP-2",plannedRepeat=2).validateTransition(c,false)
    }
    @Test fun referenceDefinitionEditsRequireDeveloperMode(){
        val c=config();val next=c.copy(referenceMethods=c.referenceMethods+
            FieldReferenceMethod(name="Caliper",kind=FieldStudyKind.DBH,unit=FieldReferenceUnit.CM))
        assertThrows(FieldStudyException::class.java){next.validateTransition(c,false)}
        next.validateTransition(c,true)
    }
    @Test fun invalidUnitsCoordinatesAndRepetitionsAreRefused(){
        assertThrows(FieldStudyException::class.java){FieldReferenceMethod(name="Bad",kind=FieldStudyKind.DBH,unit=FieldReferenceUnit.M).validate()}
        assertThrows(FieldStudyException::class.java){config().copy(plannedRepeat=0).validate()}
        assertThrows(FieldStudyException::class.java){config().copy(ar=FieldARConfiguration(maximumDistanceM=Double.NaN)).validate()}
        assertThrows(FieldStudyException::class.java){FieldStudyTarget("S","T","1",999.0,0.0).validate()}
        assertThrows(FieldStudyException::class.java){FieldStudyTarget("S","T","1",45.0).validate()}
        FieldStudyTarget("S","T","1").validate()
    }
    @Test fun everyReferenceRepeatAndOriginalUnitSurvivesCorrection(){
        val a=archive();val target=FieldStudyTarget("S","T","1");a.saveTargets(listOf(target))
        val m=FieldReferenceMethod(name="Tape",kind=FieldStudyKind.DBH,unit=FieldReferenceUnit.INCH,role=FieldReferenceRole.GROUND_TRUTH)
        val r=FieldReferenceObservation(targetId=target.id,method=m,inputValue=10.0,operatorId="OP")
        assertThrows(FieldStudyException::class.java){a.appendReference(r,false)};a.appendReference(r,true)
        a.appendReference(r.copy(id=newFieldId(),inputValue=11.0,baseValue=m.unit.baseValue(11.0),supersedesId=r.id),true)
        a.appendReference(r.copy(id=newFieldId(),inputValue=12.0,baseValue=m.unit.baseValue(12.0),plannedRepeat=2,referenceRepeat=2),true)
        val rows=a.scanReferences();assertEquals(3,rows.values.size);assertEquals(0,rows.issues.size)
        assertEquals(25.4,rows.values.first{it.id==r.id}.baseValue,1e-12)
        assertThrows(FieldStudyException::class.java){a.appendReference(r,true)}
        assertThrows(FieldStudyException::class.java){a.appendReference(r.copy(id=newFieldId(),supersedesId=r.id),true)}
    }
    @Test fun measurementAndRawIdentityCannotDiverge(){
        val a=archive();val original=record();val r=original.copy(rawStatus=FieldRawStatus.PENDING,rawBundleId=original.id)
        a.createMeasurement(r)
        assertThrows(FieldStudyException::class.java){a.updateMeasurement(r.id){it.copy(treeId="OTHER")}}
        assertThrows(FieldStudyException::class.java){a.updateMeasurement(r.id){it.copy(rawBundleId=newFieldId())}}
        assertThrows(FieldStudyException::class.java){a.createMeasurement(r)}
        val done=a.updateMeasurement(r.id,0){it.copy(outcome=FieldAttemptOutcome.RESULT,liveValue=25.0,
            selection=FieldSelectionStatus.ACCEPTED,rawStatus=FieldRawStatus.SAVED,events=it.events+FieldMeasurementEvent("accepted"))}
        assertEquals(1,done.revision)
        assertThrows(FieldStudyException::class.java){a.updateMeasurement(r.id,0){it.copy(rawStatus=FieldRawStatus.MISSING)}}
        assertThrows(FieldStudyException::class.java){a.updateMeasurement(r.id){it.copy(liveValue=30.0)}}
        assertThrows(FieldStudyException::class.java){a.updateMeasurement(r.id){it.copy(outcome=FieldAttemptOutcome.FAILED,failureReason="late")}}
        a.updateMeasurement(r.id){it.copy(selection=FieldSelectionStatus.SUPERSEDED)}
    }
    @Test fun noDepthFailuresAndInterruptedAttemptsRemainPresent(){
        val a=archive();val failed=record();a.createMeasurement(failed)
        a.updateMeasurement(failed.id){it.copy(outcome=FieldAttemptOutcome.FAILED,failureReason="no_depth")}
        val pending=record();a.createMeasurement(pending)
        assertEquals(1,a.recoverInterruptedAttempts());assertEquals(0,a.recoverInterruptedAttempts())
        assertEquals(2,a.scanMeasurements().values.size);assertEquals("no_depth",a.measurement(failed.id).failureReason)
        assertEquals(FieldAttemptOutcome.INTERRUPTED,a.measurement(pending.id).outcome)
    }
    @Test fun corruptFilesAreCountedAndNotOverwritten(){
        val a=archive();a.createMeasurement(record())
        File(File(a.root,"measurements"),newFieldId()+".json").writeText("{truncated")
        assertEquals(2,a.scanMeasurements().total);assertEquals(1,a.scanMeasurements().issues.size)
        File(a.root,"configuration.json").writeText("bad")
        assertThrows(Exception::class.java){a.saveConfiguration(config(),true)}
        assertEquals("bad",File(a.root,"configuration.json").readText())
    }
    @Test fun targetSnapshotCannotFollowLaterCoordinateEdits(){
        val a=archive();val c=config();val t=FieldStudyTarget(c.studyId,"T","1",45.0,-123.0)
        val r=FieldMeasurementRecord(sessionId=newFieldId(),treeId="T",targetId=t.id,targetSnapshot=t,
            kind=FieldStudyKind.DBH,attemptNumber=1,configuration=c,runtimeSettings=emptyMap())
        a.createMeasurement(r);val moved=t.copy(latitude=46.0);a.saveTargets(listOf(moved))
        assertEquals(45.0,a.measurement(r.id).targetSnapshot!!.latitude!!,0.0)
        assertThrows(FieldStudyException::class.java){a.updateMeasurement(r.id){it.copy(targetSnapshot=moved)}}
    }
    @Test fun retriesAndBackgroundTimeDoNotResetTheScreenClock(){
        val c=FieldMeasurementClock(100.0);c.beginAttempt(102.0);c.noteResult(false,104.0)
        c.setForeground(false,105.0);c.setForeground(true,110.0);c.noteResult(true,112.0)
        c.beginAttempt(113.0);c.noteResult(true,115.0);c.noteAccepted(118.0)
        val t=c.snapshot(118.0)
        assertEquals(18.0,t.screenElapsedSeconds,0.0);assertEquals(5.0,t.backgroundSeconds,0.0)
        assertEquals(13.0,t.activeElapsedSeconds,0.0);assertEquals(12.0,t.firstValidResultElapsedSeconds!!,0.0)
        assertEquals(2.0,t.attemptFirstResultSeconds!!,0.0);assertEquals(5.0,t.attemptElapsedSeconds!!,0.0)
        assertEquals(18.0,t.acceptedElapsedSeconds!!,0.0);assertTrue(t.clockValid)
    }
    @Test fun badClocksNeverCreateNegativeOrFabricatedDurations(){
        val c=FieldMeasurementClock(10.0);c.beginAttempt(11.0)
        assertFalse(c.snapshot(Double.NaN).clockValid);assertEquals(1.0,c.snapshot(9.0).screenElapsedSeconds,0.0)
        assertEquals(0.0,FieldMeasurementClock(1000.0).snapshot(1000.0).screenElapsedSeconds,0.0)
    }
    @Test fun aiCannotClaimAppliedWithoutActualInferenceAndMask(){
        assertThrows(FieldStudyException::class.java){FieldAIProvenance(boundaryApplied=true).validate()}
        FieldAIProvenance(inferenceRan=true,maskDetected=true,boundaryApplied=true,inferenceId=newFieldId()).validate()
    }
    @Test fun runtimeMismatchIsNotSilentlyDeclaredCompatible(){
        val c=config().copy(protocolLocked=true,pinnedRuntimeSettings=mapOf("epoch" to "9"))
        assertFalse(record(c).copy(runtimeSettings=mapOf("epoch" to "10")).protocolConforms)
    }
    @Test fun measurementJsonRoundTripsAllEvidence(){
        val original=record();val r=original.copy(outcome=FieldAttemptOutcome.RESULT,liveValue=25.4,liveSigma=0.5,
            rawStatus=FieldRawStatus.PENDING,rawBundleId=original.id,selection=FieldSelectionStatus.ACCEPTED,
            ai=FieldAIProvenance(true,true,true,true,false,newFieldId()),events=listOf(FieldMeasurementEvent("accepted")))
        assertEquals(r,FieldStudyJson.measurement(FieldStudyJson.measurementJson(r).toString()))
    }
    @Test fun independentArchiveInstancesSerializeConcurrentUpdates(){
        val a=archive();val b=FieldStudyArchive(a.root);val r=record();a.createMeasurement(r)
        val pool=Executors.newFixedThreadPool(4)
        try{val tasks=(0 until 20).map{i->pool.submit{(if(i%2==0)a else b).updateMeasurement(r.id){it.copy(events=it.events+FieldMeasurementEvent("event-$i"))}}}
            tasks.forEach{it.get()}}finally{pool.shutdown()}
        assertEquals(20,a.measurement(r.id).revision);assertEquals(20,a.measurement(r.id).events.size)
    }
    @Test fun swiftEncodedSyntheticFixtureDecodesWithTheSameFingerprint(){
        val text=javaClass.classLoader!!.getResourceAsStream("field-study-v1.json")!!.bufferedReader().use{it.readText()}
        val root=Json.parseToJsonElement(text).jsonObject
        val c=FieldStudyJson.configuration(root.getValue("configuration").toString())
        assertEquals(root.getValue("protocol_fingerprint").jsonPrimitive.content,c.protocolFingerprint)
        val ts=FieldStudyJson.targets(buildJsonObject{put("schema",1);put("targets",root.getValue("targets"))}.toString())
        val refs=root.getValue("references").jsonArray.map{FieldStudyJson.reference(it.toString())}
        val measurements=root.getValue("measurements").jsonArray.map{FieldStudyJson.measurement(it.toString())}
        assertEquals("테이프 reference",refs.first().method.name)
        assertEquals(ts.first().id,refs.first().targetId)
        assertEquals(measurements.first().id,measurements.first().rawBundleId)
        assertEquals(listOf(refs.first().id),measurements.first().referenceIds)
        for(m in measurements)assertEquals(m,FieldStudyJson.measurement(FieldStudyJson.measurementJson(m).toString()))
    }
}
