package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class FieldMeasurementScopeTest {
    private fun config()=FieldStudyConfiguration(enabled=true,studyId="SYNTHETIC",operatorId="OP-1",showReferencesDuringMeasurement=true)
    private fun target(c:FieldStudyConfiguration)=FieldStudyTarget(c.studyId,"0007","007")
    private fun refs(c:FieldStudyConfiguration,t:FieldStudyTarget)=c.referenceMethods.map{FieldReferenceObservation(targetId=t.id,method=it,inputValue=25.0,operatorId="OP-1")}
    @Test fun bothKindsKeepOriginalGTUnitsOperatorAndRoundAcrossCodec(){
        val c=config();val t=target(c);val scope=FieldMeasurementScope(configuration=c,target=t,references=refs(c,t))
        assertEquals(scope,FieldStudyJson.scope(FieldStudyJson.scopeJson(scope).toString()))
        for(kind in FieldStudyKind.entries){
            val s=FieldMeasurementSession(0.0);s.begin(newFieldId(),c,t,kind,mapOf("platform" to if(kind==FieldStudyKind.DBH)"ios"else"android"),
                scope.references(kind).map{it.id},true,false,false,1.0,scope)
            val r=s.current!!;r.validate();val restored=FieldStudyJson.measurement(FieldStudyJson.measurementJson(r).toString())
            assertEquals(scope,restored.measurementScope);assertEquals("OP-1",restored.operatorId);assertEquals(1,restored.plannedRepeat)
        }
    }
    @Test fun wrongTargetRoundOrChangedReferenceSnapshotIsRefused(){
        val c=config();val t=target(c);val rs=refs(c,t)
        assertThrows(FieldStudyException::class.java){FieldMeasurementScope(configuration=c,target=t,references=listOf(rs[0].copy(targetId="other")))}
        assertThrows(FieldStudyException::class.java){FieldMeasurementScope(configuration=c,target=t,references=listOf(rs[0].copy(plannedRepeat=2)))}
        val scope=FieldMeasurementScope(configuration=c,target=t,references=rs)
        assertThrows(FieldStudyException::class.java){FieldMeasurementSession(0.0).begin(newFieldId(),c,t,FieldStudyKind.DBH,emptyMap(),emptyList(),false,false,true,1.0,scope)}
    }
    @Test fun visibilityRequiresDeveloperAndSettingAndFiltersRevisionsKindsRounds(){
        val c=config();val t=target(c);val rs=refs(c,t);val old=rs[0]
        val revision=old.copy(id=newFieldId(),inputValue=30.0,baseValue=30.0,supersedesId=old.id)
        val observations=rs+revision+old.copy(id=newFieldId(),plannedRepeat=2)
        assertEquals(listOf(revision.id),FieldMeasurementReferences.preview(c,t.id,observations,old.method.kind,true).map{it.id})
        assertTrue(FieldMeasurementReferences.preview(c,t.id,observations,FieldStudyKind.DBH,false).isEmpty())
        assertTrue(FieldMeasurementReferences.preview(c.copy(showReferencesDuringMeasurement=false),t.id,observations,FieldStudyKind.DBH,true).isEmpty())
        assertTrue(FieldMeasurementReferences.visible(FieldMeasurementScope(configuration=c.copy(showReferencesDuringMeasurement=false),target=t,references=rs),FieldStudyKind.DBH,true).isEmpty())
    }
    @Test fun nestedCallerCollectionsAndCurrentSnapshotsCannotMutateFrozenScope(){
        val methods=config().referenceMethods.toMutableList();val pinned=mutableMapOf("procedure" to "one");val attributes=mutableMapOf("source" to "original")
        val c=config().copy(referenceMethods=methods,pinnedRuntimeSettings=pinned);val t=target(c).copy(attributes=attributes);val rs=refs(c,t).toMutableList()
        val scope=FieldMeasurementScope(configuration=c,target=t,references=rs)
        val runtime=mutableMapOf("platform" to "android");val ids=scope.references(FieldStudyKind.DBH).map{it.id}.toMutableList()
        val session=FieldMeasurementSession(0.0);session.begin(newFieldId(),c,t,FieldStudyKind.DBH,runtime,ids,true,false,false,1.0,scope)
        methods.clear();pinned["procedure"]="changed";attributes["source"]="changed";rs.clear();runtime.clear();ids.clear()
        assertEquals(2,scope.configuration.referenceMethods.size);assertEquals("one",scope.configuration.pinnedRuntimeSettings["procedure"])
        assertEquals("original",scope.target!!.attributes["source"]);assertEquals(2,scope.references.size)
        assertEquals("android",session.current!!.runtimeSettings["platform"]);session.current!!.validate()
        assertThrows(UnsupportedOperationException::class.java){(scope.configuration.pinnedRuntimeSettings as MutableMap)["procedure"]="tamper"}
    }
    @Test fun archiveRefusesScopeSwapBeforeResultAndKeepsOriginalRecord(){
        val root=Files.createTempDirectory("forestix-scope-archive-").toFile()
        try{
            val c=config();val t=target(c);val rs=refs(c,t);val scope=FieldMeasurementScope(configuration=c,target=t,references=rs)
            val s=FieldMeasurementSession(0.0);s.begin(newFieldId(),c,t,FieldStudyKind.DBH,emptyMap(),scope.references(FieldStudyKind.DBH).map{it.id},false,false,true,1.0,scope)
            val original=s.current!!;val a=FieldStudyArchive(root);a.createMeasurement(original)
            assertThrows(FieldStudyException::class.java){a.updateMeasurement(original.id){it.copy(measurementScope=null)}}
            assertThrows(FieldStudyException::class.java){a.mergeSessionSnapshot(original.copy(measurementScope=FieldMeasurementScope(configuration=c,target=t,references=rs)))}
            assertEquals(original,a.measurement(original.id))
        }finally{root.deleteRecursively()}
    }
    @Test fun actualRetryAndHeightHandoffKeepOriginalOperatorRoundAndReferenceRevision()=runBlocking<Unit>{
        val root=Files.createTempDirectory("forestix-scope-capture-").toFile()
        try{
            val store=FieldStudyStore(root);withTimeout(5000){while(!store.state.value.loaded)delay(2)}
            val c=config();val t=target(c);val rs=refs(c,t);store.saveConfiguration(c,true);store.saveTargets(listOf(t));store.selectTarget(t.id)
            rs.forEach{store.appendReference(it,true)}
            var now=0.0;val registry=FieldUIFlowRegistry();val dbh=FieldCaptureCoordinator(store,FieldStudyKind.DBH,registry){now}
            dbh.configure(emptyMap(),"quick:7");dbh.appeared();assertEquals(1,dbh.previewReferences(FieldStudyKind.DBH,true).size)
            now=1.0;val first=dbh.begin(false,false,true);now=2.0;dbh.finish(first,25.0,null,true,null,FieldAIProvenance())
            val scope=dbh.measurementScope!!;val next=c.copy(operatorId="OP-2",plannedRepeat=2,protocolVersion="2");store.saveConfiguration(next,true)
            val old=rs.first{it.method.kind==FieldStudyKind.HEIGHT};store.appendReference(old.copy(id=newFieldId(),inputValue=30.0,baseValue=30.0,supersedesId=old.id),true)
            now=3.0;val retry=dbh.begin(false,false,true);assertEquals(scope,dbh.displayRecord.value!!.measurementScope);assertEquals("OP-1",dbh.displayRecord.value!!.operatorId)
            now=4.0;dbh.finish(retry,26.0,null,true,null,FieldAIProvenance());now=5.0;dbh.resultSaved(retry,retry,"quick_history")
            val height=FieldCaptureCoordinator(store,FieldStudyKind.HEIGHT,registry){now};height.configure(emptyMap(),"quick:7",t,false,scopeOverride=scope);height.appeared()
            now=6.0;val h=height.begin(false,false,true);assertEquals("OP-1",height.displayRecord.value!!.operatorId);assertEquals(1,height.displayRecord.value!!.plannedRepeat)
            assertEquals(listOf(old.id),height.displayRecord.value!!.referenceIds);assertEquals(25.0,height.previewReferences(FieldStudyKind.HEIGHT,true).single().inputValue,0.0)
            assertTrue(height.previewReferences(FieldStudyKind.HEIGHT,false).isEmpty())
            store.saveConfiguration(next.copy(showReferencesDuringMeasurement=false),true)
            assertTrue(height.previewReferences(FieldStudyKind.HEIGHT,true).isEmpty());assertEquals(scope,height.measurementScope)
            store.saveConfiguration(next,true)
            now=7.0;height.finish(h,20.0,null,true,null,FieldAIProvenance());now=8.0;height.resultSaved(h,h,"quick_history");store.awaitPendingWrites()
            assertNull(dbh.error.value);assertNull(height.error.value);assertEquals(scope,store.archive.measurement(h).measurementScope)
            dbh.configure(emptyMap(),"quick:8");now=9.0;dbh.begin(false,false,true)
            assertEquals("OP-2",dbh.displayRecord.value!!.operatorId);assertEquals(2,dbh.displayRecord.value!!.plannedRepeat);assertNotEquals(scope.id,dbh.measurementScope!!.id)
            dbh.close();store.awaitPendingWrites()
        }finally{root.deleteRecursively()}
    }
}
