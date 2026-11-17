package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import kotlinx.serialization.json.*

class RawInertialRecordingTest {
    private fun buffer(max:Int=40000,duration:Double=120.0,available:Boolean=true)=RawIMUBuffer(max,duration).also{b->
        b.arm(listOf("accelerometer","gyroscope").map{RawIMUStream(it,"synthetic",listOf("x","y","z"),listOf("1","1","1"),available,.01)})}
    private fun append(b:RawIMUBuffer,t:Long,stream:String="accelerometer") {
        b.append(RawIMUSample(stream,t.toString(),t.toString(),listOf(1.0,2.0,3.0)),t/1e9)
    }
    private fun begin(b:RawIMUBuffer){b.begin("synthetic-tree",10.0,RawIMUEvent("anchor","10000000000","10000000000"))}
    private fun complete(b:RawIMUBuffer){append(b,10_010_000_000);append(b,10_020_000_000,"gyroscope")
        b.mark(RawIMUEvent("base","11000000000"));b.mark(RawIMUEvent("top","12000000000"))}
    @Test fun preRollAndIdentityStayCorrect(){val b=buffer();append(b,1_000_000_000);append(b,8_000_000_000);append(b,9_000_000_000);begin(b);complete(b)
        val r=b.finish()!!;assertEquals(4,r.samples.size);assertEquals("8000000000",r.samples.first().timestamp)
        assertEquals("synthetic-tree",r.measurementId);assertEquals("captured",r.status)}
    @Test fun nativeNanosecondTimestampIsNotRoundedThroughDouble(){val b=buffer();begin(b)
        val stamp=10_000_000_001L;append(b,stamp);val j=b.finish()!!.json()
        assertEquals(stamp.toString(),j["samples"]!!.jsonArray.first().jsonObject["timestamp"]!!.jsonPrimitive.content)}
    @Test fun inFlightPreRollCallbackDoesNotCapMeasurement(){val b=buffer();begin(b);append(b,9_999_000_000);complete(b)
        val r=b.finish()!!;assertFalse(r.capped);assertEquals(3,r.samples.size);assertEquals("captured",r.status)}
    @Test fun pauseAndResumeArePartialAndDoNotAddBackgroundSamples(){val b=buffer();begin(b);complete(b)
        b.pause("12000000000","background");append(b,13_000_000_000);b.resume("14000000000");append(b,14_000_000_000)
        val r=b.finish()!!;assertEquals(3,r.samples.size);assertEquals(1,r.interruptions);assertEquals("partial",r.status)
        assertEquals(listOf("anchor","base","top","pause","resume"),r.events.map{it.event})}
    @Test fun timeAndCountCapsAreExplicit(){for(b in listOf(buffer(max=2),buffer(duration=.015))){begin(b);append(b,10_010_000_000);append(b,10_020_000_000);append(b,10_030_000_000)
        val r=b.finish()!!;assertTrue(r.capped);assertTrue(r.samples.size<=2);assertEquals("partial",r.status)}}
    @Test fun unavailableSensorsNeverBecomeZeros(){val b=buffer(available=false);begin(b);append(b,10_010_000_000)
        val r=b.finish()!!;assertEquals("unavailable",r.status);assertTrue(r.samples.isEmpty())}
    @Test fun invalidSamplesAreDroppedExplicitly(){val b=buffer();begin(b)
        b.append(RawIMUSample("accelerometer","10000000000","10000000000",listOf(Double.NaN,0.0,0.0)),10.0)
        b.append(RawIMUSample("accelerometer","10000000000","10000000000",listOf(0.0)),10.0)
        val r=b.finish()!!;assertEquals(2,r.droppedSamples);assertTrue(r.samples.isEmpty());r.json().toString()}
    @Test fun finishIsOneShotAndSnapshotIsImmutable(){val b=buffer();begin(b);complete(b);val r=b.finish()!!
        append(b,13_000_000_000);b.resume("13000000000");assertNull(b.finish());assertTrue(b.needsFreshArm());assertEquals(2,r.samples.size)}
    @Test fun cadenceAndDiscontinuitiesAreMeasured(){val b=buffer();begin(b)
        listOf(10_010_000_000,10_020_000_000,10_020_000_000,10_520_000_000).forEach{append(b,it)}
        val s=b.finish()!!.json()["streamStatistics"]!!.jsonObject["accelerometer"]!!.jsonObject
        assertEquals(4,s["count"]!!.jsonPrimitive.int);assertEquals(1,s["nonIncreasingTimestamps"]!!.jsonPrimitive.int)
        assertEquals(.5,s["maximumGapSeconds"]!!.jsonPrimitive.double,1e-9)}
    @Test fun fileChecksumAndIdentityAreVerified(){val dir=Files.createTempDirectory("forestix-imu-synthetic-").toFile()
        try{val b=buffer();begin(b);complete(b);val r=b.finish()!!
            assertThrows(IllegalArgumentException::class.java){r.write("other-tree",dir)}
            val a=r.write("synthetic-tree",dir);val f=RawCaptureEvidence.proof(a["file"]!!.jsonObject)
            val stored=Json.parseToJsonElement(f.read(dir).toString(Charsets.UTF_8)).jsonObject
            assertEquals("synthetic-tree",stored["measurementId"]!!.jsonPrimitive.content)
            java.io.File(dir,"imu.json").writeBytes(byteArrayOf(0))
            assertThrows(IllegalArgumentException::class.java){f.read(dir)}
        }finally{dir.deleteRecursively()}}
    @Test fun concurrentSensorsCannotOverflowBuffer(){val b=buffer(max=500);begin(b)
        val workers=(0..3).map{Thread{repeat(1000){append(b,10_010_000_000)}}.also{it.start()}}
        workers.forEach{it.join()};val r=b.finish()!!;assertEquals(500,r.samples.size);assertTrue(r.capped)}
    @Test fun observationKeepsBothTransformsAndTrackingThermalClocks(){val b=buffer();begin(b);complete(b)
        val camera=List(16){1.25};val anchor=List(16){2.5}
        b.observe(RawHeightObservation("10000000001","10010000000",camera,anchor,"PAUSED","EXCESSIVE_MOTION","PowerManager:3",
            "10000000001","10005000000",1.0/60,"10009999999","PAUSED"))
        val r=b.finish()!!;assertEquals(camera,r.observations.single().cameraPose);assertEquals(anchor,r.observations.single().anchorPose)
        val j=r.json()["observations"]!!.jsonArray.single().jsonObject
        assertEquals("10000000001",j["arTimestamp"]!!.jsonPrimitive.content)
        assertEquals("EXCESSIVE_MOTION",j["trackingReason"]!!.jsonPrimitive.content);assertEquals("PowerManager:3",j["thermalState"]!!.jsonPrimitive.content)}
    @Test fun observationLimitIsBoundedAndExplicit(){val b=buffer();begin(b)
        val missing=RawHeightObservation(null,"11000000000",null,null,"frame_delivery_stale",null,"unknown",null,null,null)
        repeat(800){b.observe(missing)};val r=b.finish()!!;assertEquals(700,r.observations.size);assertTrue(r.capped)
        assertEquals("partial",r.status);assertNull(r.observations.first().cameraPose)}
    @Test fun eventOverflowIsNotReportedComplete(){val b=buffer();begin(b);complete(b)
        repeat(200){b.mark(RawIMUEvent("sensor_accuracy","11000000000"))}
        val r=b.finish()!!;assertEquals(128,r.events.size);assertTrue(r.capped);assertEquals("partial",r.status)}
}
