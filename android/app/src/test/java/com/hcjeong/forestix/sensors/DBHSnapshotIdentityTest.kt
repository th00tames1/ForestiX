package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class DBHSnapshotIdentityTest {
    private fun frame(age:Long=0L)=ArDepthFrame(4,4,FloatArray(16){1f},ByteArray(16){2},1.0,1.0,0.0,0.0,
        FloatArray(16){if(it%5==0)1f else 0f},depthFromViewAffine=floatArrayOf(1f,0f,0f,0f,1f,0f),
        frameTimestampNanos=12_000_000_000L,depthAgeNanos=age)
    private fun image()=RawCapturedImage("inference_source",null,4,4,12.0,11.99,"arcore_camera_image_timestamp",
        listOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0),List(16){if(it%5==0)1.0 else 0.0},
        listOf(1.0,0.0,0.0,0.0,1.0,0.0),sourceFrameId="arcore_frame_ns:12000000000")
    @Test fun exactARFrameIdentityAndMappingAreRequired() {
        assertTrue(DBHSnapshotIdentity.matches(frame(),image()))
        assertFalse(DBHSnapshotIdentity.matches(frame(),image().copy(cameraFrameSeconds=12.001)))
        assertFalse(DBHSnapshotIdentity.matches(frame(),image().copy(sourceFrameId="different")))
        assertFalse(DBHSnapshotIdentity.matches(frame(),image().copy(viewToImage=null)))
        assertFalse(DBHSnapshotIdentity.matches(frame(),null))
    }
    @Test fun sensorExposureTimestampIsPreservedNotMisrepresentedAsFrameTime() {
        assertTrue(DBHSnapshotIdentity.matches(frame(),image()))
        assertEquals(11.99,image().imageSensorSeconds!!,0.0)
        assertNotEquals(image().imageSensorSeconds,image().cameraFrameSeconds)
    }
    @Test fun matchingARFrameIdCannotAuthorizeStaleDepth() {
        assertTrue(DBHSnapshotIdentity.matches(frame(500_000_000L),image()))
        assertFalse(DBHSnapshotIdentity.matches(frame(500_000_001L),image()))
        assertFalse(DBHSnapshotIdentity.matches(frame(13_318_058_552L),image()))
    }
    @Test fun frozenInputEvidenceRetainsBothOriginalFrameIdsAndBytes() {
        val directory=java.nio.file.Files.createTempDirectory("forestix-shutter-synthetic-").toFile()
        try {
            val f=frame();val rgb=byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte(),0xd9.toByte())
            val depth=RawCaptureEvidenceIO.writeFrame(f,"shutter",directory)
            val image=RawCaptureEvidenceIO.attachImage(image(),rgb,null,"shutter",directory)!!
            assertEquals(depth.sourceFrameId,image.sourceFrameId)
            assertArrayEquals(rgb,image.file!!.read(directory))
            assertTrue(DBHSnapshotIdentity.matches(RawCaptureEvidenceIO.readFrame(depth,directory),image))
        } finally {directory.deleteRecursively()}
    }
}
