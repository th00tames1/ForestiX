package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.data.fieldstudy.FieldAIProvenance
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Entirely synthetic camera/depth/mask fixtures; no field datasets. */
class RawAIInferenceTest {
    private class FailingSource:RawAIRecordSource {
        override val inferenceId="synthetic-inference"
        override fun write(directory:File):JsonObject=error("synthetic write failure")
    }
    private fun ai(id:String="synthetic-inference")=FieldAIProvenance(inferenceRan=true,inferenceId=id)
    private fun frame()=ArDepthFrame(4,3,FloatArray(12){1.25f},ByteArray(12){2},1.0,1.0,0.0,0.0,
        FloatArray(16){if(it%5==0)1f else 0f},depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),frameTimestampNanos=123250000000)
    private fun source(chw:FloatArray,turns:Int=0,workspace:YoloInferenceWorkspace=YoloInferenceWorkspace(),mask:YoloAlignmentMask.Mask?=null):YoloOwnedInference {
        workspace.prepareInput(chw,turns)
        val viewMask=LiveStemMask.sample(200.0,300.0){x,_->x>.4&&x<.6}
        val guide=LiveStemMask.guideExtent(.9f){x,_->x>.4&&x<.6}
        return YoloOwnedInference("synthetic-inference",chw,Letterbox(20,12,640),turns,workspace.inputSha256(),frame(),null,mask,
            LiveStemObservation(null,viewMask,guide),200f,300f,123.25,123.3,"synthetic-model-sha","test",2)
    }
    private fun input(seed:Int=19)=FloatArray(3*640*640){i->((i*73+seed)%256)/255f}
    private fun mask():YoloAlignmentMask.Mask {
        val head=FloatArray(37*8400);head[0]=320f;head[8400]=320f;head[2*8400]=160f;head[3*8400]=640f
        head[4*8400]=.9f;head[5*8400]=1f
        val proto=FloatArray(32*25600);for(i in 0 until 25600)proto[i]=if(i%160<80)-1f else 1f
        return requireNotNull(YoloAlignmentMask.select(head,proto,20,12))
    }
    @Test fun disabledMissingAndMismatchedRequestsNeverWrite(){
        val dir=Files.createTempDirectory("forestix-ai-proof-").toFile()
        try{
            val s=FailingSource()
            assertEquals("not_requested",RawAIWriteRequest.make(false,ai(),s).write(dir).status)
            assertEquals("no_inference",RawAIWriteRequest.make(true,FieldAIProvenance(),s).write(dir).status)
            assertEquals("input_not_retained_at_inference",RawAIWriteRequest.make(true,ai(),null).write(dir).reason)
            assertEquals("inference_identity_mismatch",RawAIWriteRequest.make(true,ai("other"),s).write(dir).reason)
            assertTrue(dir.listFiles()!!.isEmpty())
        }finally{dir.deleteRecursively()}
    }
    @Test fun twoWriterLimitCancelAndFailureReleaseBudget(){
        val dir=Files.createTempDirectory("forestix-ai-proof-").toFile();val s=FailingSource()
        val first=RawAIWriteRequest.make(true,ai(),s);val second=RawAIWriteRequest.make(true,ai(),s)
        try{
            assertEquals("pending",first.status);assertEquals("pending",second.status)
            assertEquals("busy",RawAIWriteRequest.make(true,ai(),s).status)
            first.cancel();first.cancel();assertEquals("cancelled",first.write(dir).status)
            val replacement=RawAIWriteRequest.make(true,ai(),s)
            try{assertEquals("pending",replacement.status)}finally{replacement.cancel()}
            assertEquals("failed",second.write(dir).status)
            assertEquals("AI_request_already_consumed",second.write(dir).reason)
            val after=RawAIWriteRequest.make(true,ai(),s)
            try{assertEquals("pending",after.status)}finally{after.cancel()}
        }finally{first.cancel();second.cancel();dir.deleteRecursively()}
    }
    @Test fun boundaryProvenanceKeepsOlderAppliedInputAndAdjustOrigin(){
        val auto=AIBoundaryProvenance.snapshot(true,true,"new-miss",false,"visible-old-mask",false)
        assertTrue(auto.boundaryApplied);assertTrue(auto.maskDetected);assertEquals("visible-old-mask",auto.inferenceId)
        val adjust=AIBoundaryProvenance.snapshot(false,true,"new-miss",false,"visible-old-mask",false)
        assertFalse(adjust.enabled);assertTrue(adjust.boundaryApplied);assertEquals("visible-old-mask",adjust.inferenceId)
        val edited=AIBoundaryProvenance.snapshot(false,true,"new-miss",false,"visible-old-mask",true)
        assertFalse(edited.boundaryApplied);assertTrue(edited.userAdjustedBoundary);assertEquals("new-miss",edited.inferenceId)
        assertFalse(AIBoundaryProvenance.snapshot(false,true,null,false,null,false).inferenceRan)
    }
    @Test fun cancelDoesNotReleaseRunningWriterPermitPrematurely(){
        val dir=Files.createTempDirectory("forestix-ai-proof-").toFile()
        val entered=CountDownLatch(1);val finish=CountDownLatch(1)
        val s=object:RawAIRecordSource {
            override val inferenceId="synthetic-inference"
            override fun write(directory:File):JsonObject{entered.countDown();finish.await(5,TimeUnit.SECONDS);error("synthetic write failure")}
        }
        val running=RawAIWriteRequest.make(true,ai(),s);val other=RawAIWriteRequest.make(true,ai(),FailingSource())
        val worker=Thread{running.write(dir)}
        try{
            worker.start();assertTrue(entered.await(3,TimeUnit.SECONDS));running.cancel()
            assertEquals("busy",RawAIWriteRequest.make(true,ai(),FailingSource()).status)
            finish.countDown();worker.join(5000);assertFalse(worker.isAlive)
            val after=RawAIWriteRequest.make(true,ai(),FailingSource())
            try{assertEquals("pending",after.status)}finally{after.cancel()}
        }finally{finish.countDown();worker.join(5000);running.cancel();other.cancel();dir.deleteRecursively()}
    }
    @Test fun ownedInputSurvivesWorkspaceReuseAndRestoresOriginalMaskEveryPixel(){
        val dir=Files.createTempDirectory("forestix-ai-proof-").toFile()
        try{
            val workspace=YoloInferenceWorkspace();val original=mask()
            val owned=source(input(),1,workspace,original);val expected=workspace.inputSha256()
            source(input(99),3,workspace);assertNotEquals(expected,workspace.inputSha256())
            val result=RawAIWriteRequest.make(true,ai(),owned).write(dir)
            assertEquals(result.reason,"saved",result.status);val json=requireNotNull(result.inference)
            assertEquals(expected,RawCaptureEvidenceIO.hash(RawAIInferenceIO.verifiedTensor(json,dir)))
            val restored=requireNotNull(RawAIInferenceIO.restoredMask(json,dir))
            for(y in 0 until 12)for(x in 0 until 20)assertEquals(original.contains(x,y),restored.contains(x,y))
            assertNotNull(json["view_mask_runs"]);assertNotNull(json["guide_fractions"])
            val e=RawCaptureEvidence("synthetic-measurement",resultSeconds=124.0,
                displayedResult=RawCapturedResult(25.5,null,"yellow","synthetic",true),aiInference=json,aiEvidenceStatus=result.status)
            val roundtrip=RawCaptureEvidence.parse(e.json().toString())
            assertEquals(e,roundtrip);assertEquals(25.5,roundtrip.displayedResult.value!!,0.0)
        }finally{dir.deleteRecursively()}
    }
    @Test fun tensorMaskCorruptionPairingAndInputMutationCannotPass(){
        val dir=Files.createTempDirectory("forestix-ai-proof-").toFile()
        try{
            val chw=input();val owned=source(chw,mask=mask());val json=owned.write(dir)
            val tensor=RawCaptureEvidence.proof(json.getValue("tensor").jsonObject)
            val file=File(dir,tensor.file);val bytes=file.readBytes();bytes[0]=(bytes[0].toInt() xor 1).toByte();file.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java){RawAIInferenceIO.verifiedTensor(json,dir)}
            val badPair=JsonObject(json-"mask_geometry")
            assertThrows(IllegalArgumentException::class.java){RawAIInferenceIO.restoredMask(badPair,dir)}
            val maskFile=File(dir,RawCaptureEvidence.proof(json.getValue("mask_logits").jsonObject).file)
            maskFile.writeBytes(byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java){RawAIInferenceIO.restoredMask(json,dir)}
            chw[0]=.987f
            assertEquals("failed",RawAIWriteRequest.make(true,ai(),owned).write(dir).status)
        }finally{dir.deleteRecursively()}
    }
    @Test fun recordedTensorUsesActualAllFourRotationsAndDoesNotChangeWorkspacePosition(){
        val dir=Files.createTempDirectory("forestix-ai-proof-").toFile()
        try{
            val chw=input();val workspace=YoloInferenceWorkspace()
            for(turns in 0..3){
                val owned=source(chw,turns,workspace)
                val buffer=workspace.prepareInput(chw,turns);buffer.position(23)
                val hash=workspace.inputSha256();assertEquals(23,buffer.position())
                val json=owned.write(dir)
                assertEquals(hash,RawCaptureEvidenceIO.hash(RawAIInferenceIO.verifiedTensor(json,dir)))
                assertNull(RawAIInferenceIO.restoredMask(json,dir))
            }
        }finally{dir.deleteRecursively()}
    }
}
