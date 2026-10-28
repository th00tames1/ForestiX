package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*

class RawCaptureEvidenceTest {
    private fun frame():ArDepthFrame {
        val p=FloatArray(16);for(i in 0..3)p[i*5]=1f;p[12]=1.25f;p[13]=-2.5f;p[14]=3.75f
        return ArDepthFrame(4,3,floatArrayOf(0f,-0.0f,Float.fromBits(0x7fc01234),Float.POSITIVE_INFINITY,1f,1.25f,2.5f,8f,9f,-1f,.001f,3f),
            byteArrayOf(0,0,0,0,1,2,0,2,1,0,2,2),123.25,124.5,1.5,2.5,p,
            depthFromViewAffine=floatArrayOf(.25f,0f,-1f,0f,.5f,-2f),frameTimestampNanos=123250000000,depthAgeNanos=250000000)
    }
    @Test fun originalFrameAndMetadataRoundTripPreservesEveryBit(){
        val dir=Files.createTempDirectory("forestix-evidence-").toFile()
        try{
            val f=frame()
            RawFrameClockRegistry.register(f,f.frameTimestampNanos+1000000000,f.frameTimestampNanos+750000000)
            val m=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir)
            val e=RawCaptureEvidence(UUID.randomUUID().toString(),resultSeconds=120.0,
                displayedResult=RawCapturedResult(25.0,null,"yellow","synthetic",true),frames=listOf(m))
            val d=RawCaptureEvidence.parse(e.json().toString());assertEquals(e,d)
            val r=RawCaptureEvidenceIO.readFrame(d.frames.single(),dir)
            assertArrayEquals(f.depth.map{it.toRawBits()}.toIntArray(),r.depth.map{it.toRawBits()}.toIntArray())
            assertArrayEquals(f.confidence,r.confidence);assertArrayEquals(f.pose,r.pose,0f)
            assertArrayEquals(f.depthFromViewAffine,r.depthFromViewAffine,0f)
            assertEquals(f.fx,r.fx,0.0);assertEquals(f.cy,r.cy,0.0)
            assertEquals(123.25,m.cameraFrameSeconds!!,0.0);assertEquals(124.0,m.depthSensorSeconds!!,0.0)
            assertEquals("arcore_frame_ns:${f.frameTimestampNanos}",m.sourceFrameId)
            assertEquals(f.frameTimestampNanos,r.frameTimestampNanos);assertEquals(f.depthAgeNanos,r.depthAgeNanos)
        }finally{dir.deleteRecursively()}
    }
    @Test fun corruptMissingAndEscapedFilesNeverPassAsOriginalInput(){
        val dir=Files.createTempDirectory("forestix-evidence-").toFile()
        try{
            val m=RawCaptureEvidenceIO.writeFrame(frame(),"dbh_0",dir)
            val f=java.io.File(dir,m.confidence.file);val data=f.readBytes();data[4]=2;f.writeBytes(data)
            assertThrows(IllegalArgumentException::class.java){RawCaptureEvidenceIO.readFrame(m,dir)}
            assertThrows(IllegalArgumentException::class.java){RawCaptureEvidenceIO.readFrame(m.copy(depth=m.depth.copy(file="../outside.f32")),dir)}
            assertThrows(IllegalArgumentException::class.java){RawCaptureEvidenceIO.readFrame(m.copy(depth=m.depth.copy(file="absent.f32")),dir)}
        }finally{dir.deleteRecursively()}
    }
    @Test fun unknownDepthSensorClockIsNotInventedFromArcoreFrameTime(){
        val dir=Files.createTempDirectory("forestix-unknown-clock-").toFile()
        try{
            val f=frame();val m=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir)
            assertNull(m.depthSensorSeconds);assertNull(m.depthSensorId);assertNull(m.cameraSensorId)
            assertEquals("camera_relative_depth_age_only",m.depthTimeSource)
            val restored=RawCaptureEvidenceIO.readFrame(m,dir)
            assertEquals(f.depthAgeNanos,restored.depthAgeNanos);assertEquals(f.frameTimestampNanos,restored.frameTimestampNanos)
        }finally{dir.deleteRecursively()}
    }
    @Test fun actualDisplayedValueNeverChangesToAReplayedNumber(){
        val e=RawCaptureEvidence(UUID.randomUUID().toString(),resultSeconds=120.0,
            displayedResult=RawCapturedResult(25.123456,null,"yellow","manualVisual",true)).noteReplay(24.75)
        val d=RawCaptureEvidence.parse(e.json().toString())
        assertEquals(25.123456,d.displayedResult.value!!,0.0);assertNull(d.displayedResult.sigma)
        assertEquals(24.75,d.replayValue!!,0.0);assertEquals("mismatch",d.replayStatus)
    }
    @Test fun invalidReplayIsMissingNotAZeroMeasurement(){
        for(value in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)){
            val evidence=RawCaptureEvidence(UUID.randomUUID().toString(),resultSeconds=2.0,
                displayedResult=RawCapturedResult(25.0,null,"green","synthetic",true)).noteReplay(value)
            assertNull(evidence.replayValue);assertNull(evidence.replayDelta);assertEquals("no_result",evidence.replayStatus)
            assertEquals(25.0,evidence.displayedResult.value!!,0.0)
        }
    }
    @Test fun imageSourceMetadataAndBytesRemainLinkedWithoutAnAimDepthFrame(){
        val dir=Files.createTempDirectory("forestix-image-proof-").toFile()
        try{
            val image=RawCapturedImage("reference",null,4,3,123.25,123.0,"arcore_camera_image_timestamp",
                listOf(1.0,0.0,0.0,0.0,1.0,0.0,2.0,1.0,1.0),frame().pose.map{it.toDouble()},null)
            val bytes=byteArrayOf(-1,-40,-1,-39) // Synthetic bytes; no field photograph.
            val m=RawCaptureEvidenceIO.attachImage(image,bytes,null,"base",dir)!!
            assertArrayEquals(bytes,m.file!!.read(dir));assertEquals(123.25,m.cameraFrameSeconds!!,0.0)
            val e=RawCaptureEvidence(UUID.randomUUID().toString(),resultSeconds=124.0,
                displayedResult=RawCapturedResult(20.0,null,"yellow","synthetic",true),images=listOf(m))
            assertEquals(e,RawCaptureEvidence.parse(e.json().toString()))
        }finally{dir.deleteRecursively()}
    }
    @Test fun auxiliaryRawDepthAndRealConfidenceRoundTripWithoutChangingFullInput(){
        val dir=Files.createTempDirectory("forestix-aux-raw-").toFile()
        try{
            val raw=DbhRawDepthEvidence(4,3,ShortArray(12){(it*6000).toShort()},
                byteArrayOf(0,1,2,127,128.toByte(),160.toByte(),200.toByte(),255.toByte(),0,0,1,2),
                1000000000,1000000000,1100000000)
            val original=frame()
            val input=ArDepthFrame(4,3,original.depth,original.confidence,original.fx,original.fy,original.cx,original.cy,
                original.pose,rawDepthEvidence=raw,depthDiagnostics=mapOf("raw_acquisition_status" to "available"))
            val descriptor=RawCaptureEvidenceIO.writeFrame(input,"dbh_0",dir)
            val evidence=RawCaptureEvidence(UUID.randomUUID().toString(),resultSeconds=2.0,
                displayedResult=RawCapturedResult(null,null,null,null,false),frames=listOf(descriptor),
                depthDiagnostics=buildJsonObject{put("status","synthetic_trace")})
            val parsed=RawCaptureEvidence.parse(evidence.json().toString());assertEquals(evidence,parsed)
            val restored=RawCaptureEvidenceIO.readFrame(parsed.frames.single(),dir)
            assertArrayEquals(raw.millimeters,restored.rawDepthEvidence!!.millimeters)
            assertArrayEquals(raw.confidence,restored.rawDepthEvidence.confidence)
            assertArrayEquals(input.confidence,restored.confidence)
            assertArrayEquals(input.depth.map{it.toRawBits()}.toIntArray(),restored.depth.map{it.toRawBits()}.toIntArray())
            assertEquals(input.depthDiagnostics,restored.depthDiagnostics)
            val proof=descriptor.auxiliaryRawDepth!!.confidence
            java.io.File(dir,proof.file).writeBytes(ByteArray(12))
            assertThrows(IllegalArgumentException::class.java){RawCaptureEvidenceIO.readFrame(descriptor,dir)}
        }finally{dir.deleteRecursively()}
    }
    @Test fun originalReplayKeepsKnownStaleAgeAndRejectsWrongIdentity(){
        val dir=Files.createTempDirectory("forestix-stale-replay-").toFile()
        try{
            val id=UUID.randomUUID().toString();val m=RawCaptureEvidenceIO.writeFrame(frame(),"dbh_0",dir)
                .copy(depthAgeNanoseconds="13318058552")
            val e=RawCaptureEvidence(id,resultSeconds=2.0,displayedResult=RawCapturedResult(null,null,null,null,false),frames=listOf(m))
            val restored=RawCaptureReplay.restoreOriginalDbhFrames(dir,id,e,listOf(4 to 3)).single()
            assertEquals(13_318_058_552L,restored.depthAgeNanos);assertFalse(DbhDepthInputPolicy.acceptsFrame(restored))
            assertThrows(IllegalArgumentException::class.java){RawCaptureReplay.restoreOriginalDbhFrames(dir,"wrong-id",e,listOf(4 to 3))}
            assertThrows(IllegalArgumentException::class.java){RawCaptureReplay.restoreOriginalDbhFrames(dir,id,e,listOf(3 to 4))}
        }finally{dir.deleteRecursively()}
    }
    @Test fun recordedClockViolationCannotBeReplayedAsFreshOrMalformedAgeSilentlyDefaulted(){
        val dir=Files.createTempDirectory("forestix-clock-replay-").toFile()
        try{
            val m=RawCaptureEvidenceIO.writeFrame(frame(),"dbh_0",dir).copy(depthAgeNanoseconds="0",
                cameraSensorId="android_camera_ns:20000000000",depthSensorId="depth_image_ns:6666556484")
            assertFalse(DbhDepthInputPolicy.acceptsFrame(RawCaptureEvidenceIO.readFrame(m,dir)))
            assertThrows(NumberFormatException::class.java){RawCaptureEvidenceIO.readFrame(m.copy(depthAgeNanoseconds="broken"),dir)}
        }finally{dir.deleteRecursively()}
    }
    @Test fun explicitQualifiedPolicyRoundTripsAndCannotBeReadAsVersionOneOrUnknownPolicy(){
        val dir=Files.createTempDirectory("forestix-qualified-source-").toFile()
        try{
            val original=frame();val raw=DbhRawDepthEvidence(4,3,ShortArray(12){1000},ByteArray(12){255.toByte()},1_000_000_000,2_000_000_000,2_000_000_000)
            val f=ArDepthFrame(4,3,original.depth,original.confidence,original.fx,original.fy,original.cx,original.cy,original.pose,
                rawDepthEvidence=raw,depthInputPolicy=DbhMeasurementDepth.QUALIFIED_RAW)
            val descriptor=RawCaptureEvidenceIO.writeFrame(f,"dbh_0",dir)
            val e=RawCaptureEvidence(UUID.randomUUID().toString(),resultSeconds=2.0,
                displayedResult=RawCapturedResult(null,null,null,null,false),frames=listOf(descriptor),version=2)
            val restored=RawCaptureEvidenceIO.readFrame(RawCaptureEvidence.parse(e.json().toString()).frames.single(),dir)
            assertEquals(DbhMeasurementDepth.QUALIFIED_RAW,restored.depthInputPolicy)
            assertArrayEquals(raw.confidence,restored.rawDepthEvidence!!.confidence)
            assertThrows(IllegalArgumentException::class.java){RawCaptureEvidence.parse(e.copy(version=1).json().toString())}
            assertThrows(IllegalArgumentException::class.java){RawCaptureEvidenceIO.readFrame(descriptor.copy(depthInputPolicy="unknown"),dir)}
        }finally{dir.deleteRecursively()}
    }
}
