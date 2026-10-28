package com.hcjeong.forestix.sensors

import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DbhDepthComparisonIOTest {
    private fun frame():ArDepthFrame {
        val time=50_000_000_000L;val n=64*48
        return ArDepthFrame(64,48,FloatArray(n){1.5f},ByteArray(n){2},100.0,100.0,32.0,24.0,
            FloatArray(16){if(it%5==0)1f else 0f},frameTimestampNanos=time,depthInputPolicy=DbhMeasurementDepth.QUALIFIED_RAW,
            rawDepthEvidence=DbhRawDepthEvidence(64,48,ShortArray(n){1000},ByteArray(n){255.toByte()},time-4_000_000_000,time,time))
    }
    @Test fun sameOriginalInputProducesAllDeterministicShadowValuesAndLinkedCsv(){
        val dir=Files.createTempDirectory("depth-comparison-").toFile()
        try{
            val f=frame();val source=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir)
            val id=UUID.randomUUID().toString()
            val inputs=listOf(DbhDepthComparisonIO.Input(f,source,DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.25,.75)))
            val descriptor=DbhDepthComparisonIO.write(dir,id,inputs,ProjectCalibration.identity)
            val saved=DbhDepthComparisonIO.replay(dir,id,descriptor)
            assertEquals(DbhDepthComparisonIO.evaluate(id,inputs,ProjectCalibration.identity),saved)
            assertEquals("shadow_only",saved.getValue("mode").jsonPrimitive.content)
            assertFalse(saved.getValue("automatic_selection").jsonPrimitive.boolean)
            val row=saved.getValue("frames").jsonArray.single().jsonObject
            val estimates=row.getValue("estimates").jsonArray.map{it.jsonObject}
            assertEquals(DbhDepthCandidates.IDS,estimates.map{it.getValue("candidate_id").jsonPrimitive.content})
            assertTrue(estimates.all{!it.getValue("applied_to_result").jsonPrimitive.boolean})
            val csv=DbhDepthComparisonIO.csv(saved);assertEquals(DbhDepthCandidates.IDS.size+1,csv.trimEnd().lines().size)
            assertEquals(DbhDepthCandidates.IDS.size,Regex(source.depth.sha256).findAll(csv).count())
            assertTrue(csv.contains(id));assertTrue(csv.contains(source.auxiliaryRawDepth!!.confidence.sha256))
            assertTrue(dir.listFiles()!!.sumOf{it.length()}<100_000)
        }finally{dir.deleteRecursively()}
    }
    @Test fun corruptOriginalOrSidecarsAndWrongIdentityCannotReplayAsValidComparison(){
        val dir=Files.createTempDirectory("depth-comparison-corrupt-").toFile()
        try{
            val f=frame();val source=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir);val id=UUID.randomUUID().toString()
            val descriptor=DbhDepthComparisonIO.write(dir,id,listOf(DbhDepthComparisonIO.Input(f,source,
                DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.25,.75))),ProjectCalibration.identity)
            assertThrows(IllegalArgumentException::class.java){DbhDepthComparisonIO.replay(dir,"wrong",descriptor)}
            val proof=source.auxiliaryRawDepth!!.confidence;val file=java.io.File(dir,proof.file);val bytes=file.readBytes()
            file.writeBytes(ByteArray(bytes.size))
            assertThrows(IllegalArgumentException::class.java){DbhDepthComparisonIO.replay(dir,id,descriptor)}
            file.writeBytes(bytes)
            java.io.File(dir,"dbh_depth_candidates.csv").appendText("corrupt")
            assertThrows(IllegalArgumentException::class.java){DbhDepthComparisonIO.replay(dir,id,descriptor)}
        }finally{dir.deleteRecursively()}
    }
    @Test fun refusedInputWithoutKnownBoundaryRetainsAllCandidateReasonsWithoutInventingValue(){
        val dir=Files.createTempDirectory("depth-comparison-refused-").toFile()
        try{
            val f=frame();val source=RawCaptureEvidenceIO.writeFrame(f,"shutter_refused",dir);val id=UUID.randomUUID().toString()
            val d=DbhDepthComparisonIO.write(dir,id,listOf(DbhDepthComparisonIO.Input(f,source,null)),ProjectCalibration.identity)
            val r=DbhDepthComparisonIO.replay(dir,id,d).getValue("frames").jsonArray.single().jsonObject
            assertEquals(JsonNull,r["geometry"])
            assertTrue(r.getValue("estimates").jsonArray.all{!it.jsonObject.containsKey("diameter_cm")})
        }finally{dir.deleteRecursively()}
    }
    @Test fun reportMustMatchCanonicalOriginalAndItsBoundaryNotJustAMatchingUuid(){
        val dir=Files.createTempDirectory("depth-comparison-association-").toFile()
        try{
            val f=frame();val g=DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.25,.75)
            val source=DbhDepthComparisonIO.annotate(RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir),g)
            val id=UUID.randomUUID().toString()
            val report=DbhDepthComparisonIO.evaluate(id,listOf(DbhDepthComparisonIO.Input(f,source,g)),ProjectCalibration.identity)
            val e=RawCaptureEvidence(id,resultSeconds=1.0,displayedResult=RawCapturedResult(null,null,null,null,false),frames=listOf(source),version=2)
            DbhDepthComparisonIO.requireOriginalAssociation(report,e)
            assertThrows(IllegalArgumentException::class.java){DbhDepthComparisonIO.requireOriginalAssociation(report,
                e.copy(frames=listOf(source.copy(sourceFrameId="arcore_frame_ns:999"))))}
            val changed=DbhDepthComparisonIO.evaluate(id,listOf(DbhDepthComparisonIO.Input(f,source,
                DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.2,.8))),ProjectCalibration.identity)
            assertThrows(IllegalArgumentException::class.java){DbhDepthComparisonIO.requireOriginalAssociation(changed,e)}
        }finally{dir.deleteRecursively()}
    }
    @Test fun versionOneFourCandidateReportStillReplaysExactlyAndFutureVersionDoesNot(){
        val dir=Files.createTempDirectory("depth-comparison-v1-").toFile()
        try{
            val f=frame();val source=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir);val id=UUID.randomUUID().toString()
            val input=listOf(DbhDepthComparisonIO.Input(f,source,DbhDepthComparisonIO.Geometry(GuideAxis.Row(24),.25,.75)))
            val descriptor=DbhDepthComparisonIO.write(dir,id,input,ProjectCalibration.identity,version=1)
            val old=DbhDepthComparisonIO.replay(dir,id,descriptor)
            val ids=old.getValue("frames").jsonArray.single().jsonObject.getValue("estimates").jsonArray.map{it.jsonObject.getValue("candidate_id").jsonPrimitive.content}
            assertEquals(DbhDepthCandidates.IDS_V1,ids)
            assertFalse(old.getValue("parameters").jsonObject.containsKey("dense_ray_quantile"))
            val invalid=JsonObject(descriptor+mapOf("version" to JsonPrimitive(999)))
            assertThrows(IllegalStateException::class.java){DbhDepthComparisonIO.replay(dir,id,invalid)}
        }finally{dir.deleteRecursively()}
    }
}
