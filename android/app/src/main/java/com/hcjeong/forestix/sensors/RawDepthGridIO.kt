package com.hcjeong.forestix.sensors

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Legacy manifest grids. New capture_evidence remains the checksummed,
 * bit-exact authority; this additive fallback never quantizes float inputs. */
internal object RawDepthGridIO {
    data class Stored(val format:String,val confidenceFile:String)
    data class Grid(val depth:FloatArray,val confidence:ByteArray)

    private fun count(width:Int,height:Int):Int {
        require(width in 1..4096&&height in 1..4096&&width.toLong()*height<=4*1024*1024){"Depth grid dimensions"}
        return width*height
    }
    private fun file(directory:File,name:String):File {
        require(name.isNotEmpty()&&name==File(name).name&&!name.contains("..")&&!name.contains('\\')){"Depth grid filename"}
        return File(directory,name).also{
            require(it.canonicalFile.parentFile==directory.canonicalFile&&!Files.isSymbolicLink(it.toPath())){"Depth grid escaped bundle"}
        }
    }
    private fun write(directory:File,name:String,bytes:ByteArray) {
        FileOutputStream(file(directory,name)).use{it.write(bytes);it.fd.sync()}
    }
    private fun read(directory:File,name:String,bytes:Int):ByteArray {
        val f=file(directory,name)
        require(f.isFile&&f.length()==bytes.toLong()){"Depth grid byte count"}
        return f.readBytes().also{require(it.size==bytes){"Depth grid byte count changed"}}
    }

    fun write(frame:ArDepthFrame,directory:File,depthName:String):Stored {
        val n=count(frame.width,frame.height)
        require(frame.depth.size==n&&frame.confidence.size==n&&frame.confidence.all{it.toInt() in 0..2}){"Depth grid input shape"}
        val native=frame.rawDepthMm
        require(native==null||native.size==n){"Native depth grid input shape"}
        val bytes=ByteBuffer.allocate(n*if(native==null)4 else 2).order(ByteOrder.LITTLE_ENDIAN)
        if(native!=null)native.forEach{bytes.putShort(it)}else frame.depth.forEach{bytes.putInt(it.toRawBits())}
        val confidenceName=depthName.removeSuffix(".bin")+".conf"
        require(confidenceName!=depthName){"Conflicting depth/confidence filenames"}
        write(directory,depthName,bytes.array())
        write(directory,confidenceName,frame.confidence)
        return Stored(if(native==null)"f32m"else"u16mm",confidenceName)
    }

    fun read(directory:File,width:Int,height:Int,format:String,depthName:String,confidenceName:String?):Grid {
        val n=count(width,height)
        require(format=="u16mm"||format=="f32m"){"Unknown depth grid format"}
        val bytes=ByteBuffer.wrap(read(directory,depthName,n*if(format=="u16mm")2 else 4)).order(ByteOrder.LITTLE_ENDIAN)
        val depth=FloatArray(n);var confidence=ByteArray(n)
        for(i in 0 until n) {
            if(format=="u16mm")ArDepthFrame.ingestMmInto(bytes.short.toInt() and 0xffff,i,depth,confidence)
            else {depth[i]=Float.fromBits(bytes.int);if(depth[i]>=.001f&&depth[i]<=8f)confidence[i]=2}
        }
        // Absence means legacy derivation, not permission to replace a corrupt
        // explicitly recorded confidence file with made-up high confidence.
        if(confidenceName!=null)confidence=read(directory,confidenceName,n).also{
            require(it.all{v->v.toInt() in 0..2}){"Depth grid confidence range"}
        }
        return Grid(depth,confidence)
    }
}
