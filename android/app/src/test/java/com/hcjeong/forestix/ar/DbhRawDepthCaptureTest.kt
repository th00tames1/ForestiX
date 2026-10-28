package com.hcjeong.forestix.ar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

/** Synthetic padded native planes, not captured measurements. */
class DbhRawDepthCaptureTest {
    @Test fun paddedRowsAndPixelStridePreserveAllUnsignedBits() {
        val b=ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        val expected=shortArrayOf(0,500,32767,(-1).toShort())
        for(i in expected.indices)b.putShort(3+(i/2)*16+(i%2)*4,expected[i])
        b.position(3)
        assertArrayEquals(expected,DbhRawDepthCapture.copyMillimeters(b,2,2,16,4))
        assertEquals(3,b.position())
    }
    @Test fun actualConfidenceIsUnsignedByteDataNotAValidityMask() {
        val b=ByteBuffer.allocate(24)
        val values=byteArrayOf(0,127,128.toByte(),255.toByte())
        for(i in values.indices)b.put(2+(i/2)*8+(i%2)*3,values[i])
        b.position(2)
        assertArrayEquals(values,DbhRawDepthCapture.copyConfidence(b,2,2,8,3))
    }
    @Test fun shortOverlappingAndExcessivePlanesAreRefused() {
        assertThrows(IllegalArgumentException::class.java){DbhRawDepthCapture.copyMillimeters(ByteBuffer.allocate(3),2,1,4,2)}
        assertThrows(IllegalArgumentException::class.java){DbhRawDepthCapture.copyMillimeters(ByteBuffer.allocate(8),2,2,2,2)}
        assertThrows(IllegalArgumentException::class.java){DbhRawDepthCapture.copyConfidence(ByteBuffer.allocate(1),4096,4096,4096,1)}
    }
}
