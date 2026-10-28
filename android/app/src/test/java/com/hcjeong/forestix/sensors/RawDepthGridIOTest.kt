package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Synthetic grids only; no field samples or fitted coefficients. */
class RawDepthGridIOTest {
    private fun frame(native:ShortArray?=null)=ArDepthFrame(4,2,
        floatArrayOf(-0.0f,Float.fromBits(0x7fc01234),Float.POSITIVE_INFINITY,1.2345678f,.001f,8f,9f,-1f),
        byteArrayOf(0,0,0,1,2,2,0,0),123.0,124.0,1.5,.5,FloatArray(16){if(it%5==0)1f else 0f},rawDepthMm=native)
    private fun bits(v:FloatArray)=v.map{it.toRawBits()}.toIntArray()
    private fun fixture(block:(File)->Unit){val dir=Files.createTempDirectory("forestix-grid-").toFile();try{block(dir)}finally{dir.deleteRecursively()}}
    @Test fun f32FallbackPreservesEveryBitAndOriginalConfidence()=fixture{dir->
        val f=frame();val d=f.depth.copyOf();val c=f.confidence.copyOf()
        val m=RawDepthGridIO.write(f,dir,"depth_0.bin")
        assertEquals("f32m",m.format)
        val r=RawDepthGridIO.read(dir,4,2,m.format,"depth_0.bin",m.confidenceFile)
        assertArrayEquals(bits(f.depth),bits(r.depth));assertArrayEquals(c,r.confidence)
        assertArrayEquals(bits(d),bits(f.depth));assertArrayEquals(c,f.confidence)
        val bytes=File(dir,"depth_0.bin").readBytes()
        assertArrayEquals(byteArrayOf(0,0,0,0x80.toByte()),bytes.take(4).toByteArray())
    }
    @Test fun nativeU16BytesRemainVerbatimAndLegacyIngestRemainsIdentical()=fixture{dir->
        val native=shortArrayOf(0,1,999,1000,1250,8000,8001,(-1).toShort())
        val f=frame(native);val m=RawDepthGridIO.write(f,dir,"depth_0.bin")
        assertEquals("u16mm",m.format)
        val expected=ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);native.forEach{expected.putShort(it)}
        assertArrayEquals(expected.array(),File(dir,"depth_0.bin").readBytes())
        val r=RawDepthGridIO.read(dir,4,2,"u16mm","depth_0.bin",null)
        val d=FloatArray(8);val c=ByteArray(8)
        native.forEachIndexed{i,v->ArDepthFrame.ingestMmInto(v.toInt() and 0xffff,i,d,c)}
        assertArrayEquals(bits(d),bits(r.depth));assertArrayEquals(c,r.confidence)
    }
    @Test fun legacyF32WithoutConfidenceKeepsHistoricalDerivation()=fixture{dir->
        RawDepthGridIO.write(frame(),dir,"depth_0.bin")
        val r=RawDepthGridIO.read(dir,4,2,"f32m","depth_0.bin",null)
        assertArrayEquals(bits(frame().depth),bits(r.depth))
        assertArrayEquals(byteArrayOf(0,0,0,2,2,2,0,0),r.confidence)
    }
    @Test fun explicitlyRecordedConfidenceCannotSilentlyFallBack()=fixture{dir->
        val m=RawDepthGridIO.write(frame(),dir,"depth_0.bin")
        val conf=File(dir,m.confidenceFile)
        for(bytes in listOf(byteArrayOf(2),ByteArray(8){3},ByteArray(9){2})){
            conf.writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,4,2,"f32m","depth_0.bin",m.confidenceFile)}
        }
        conf.delete()
        assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,4,2,"f32m","depth_0.bin",m.confidenceFile)}
    }
    @Test fun boundsTruncationAndUnknownFormatsAreRejectedBeforeReplay()=fixture{dir->
        RawDepthGridIO.write(frame(),dir,"depth_0.bin")
        for(size in listOf(31,33)){
            File(dir,"bad.bin").writeBytes(ByteArray(size))
            assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,4,2,"f32m","bad.bin",null)}
        }
        for(size in listOf(0 to 2,Int.MAX_VALUE to 2,4096 to 4096))
            assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,size.first,size.second,"f32m","depth_0.bin",null)}
        assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,4,2,"unknown","depth_0.bin",null)}
    }
    @Test fun escapedAndSymlinkedPayloadsCannotBeReadOrOverwritten()=fixture{dir->
        RawDepthGridIO.write(frame(),dir,"depth_0.bin")
        for(name in listOf("../depth_0.bin",File(dir,"depth_0.bin").absolutePath,"sub/depth_0.bin","sub\\depth_0.bin","")){
            assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,4,2,"f32m",name,null)}
        }
        Files.createSymbolicLink(File(dir,"link.bin").toPath(),File(dir,"depth_0.bin").toPath())
        assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.read(dir,4,2,"f32m","link.bin",null)}
        assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.write(frame(),dir,"link.bin")}
    }
    @Test fun malformedNativeOrConfidenceShapeIsNeverSilentlyDropped()=fixture{dir->
        assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.write(frame(ShortArray(7)),dir,"depth_0.bin")}
        val f=frame();f.confidence[3]=(-1).toByte()
        assertThrows(IllegalArgumentException::class.java){RawDepthGridIO.write(f,dir,"depth_0.bin")}
        assertTrue(dir.listFiles()!!.isEmpty())
    }
}
