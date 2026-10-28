package com.hcjeong.forestix.sensors

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** Scratch only: the aligner's synchronized inference owns it through decoding.
 * No camera/depth frame is cached, and no result retains these buffers. */
internal class YoloInferenceWorkspace {
    companion object {
        const val SIDE = 640
        const val INPUT_COUNT = 3 * SIDE * SIDE
        private const val HEAD_COUNT = 37 * 8400
        private const val PROTOTYPE_COUNT = 32 * 25600
    }

    // A direct native-order input avoids ORT allocating/copying a heap-backed
    // FloatBuffer on every run. Only the synchronous inference may read it.
    private val inputBytes = ByteBuffer.allocateDirect(INPUT_COUNT * 4).order(ByteOrder.nativeOrder())
    private val input = inputBytes.asFloatBuffer()
    private val rotated = FloatArray(INPUT_COUNT)
    private val head = FloatArray(HEAD_COUNT)
    private val prototypes = FloatArray(PROTOTYPE_COUNT)

    fun prepareInput(chw: FloatArray, quarterTurns: Int): FloatBuffer {
        require(chw.size == INPUT_COUNT && quarterTurns in 0..3)
        input.clear()
        if (quarterTurns == 0) {
            // The original rotation is identity here; a second heap copy is
            // unnecessary. Still copy into PRIVATE storage, never alias RGB.
            input.put(chw)
        } else {
            // Fixed inverse transforms, planar/block traversal: no per-pixel
            // rotation loop or channel switching. Copies remain bit exact.
            YoloTensorRotation.rotate(chw,quarterTurns,rotated)
            input.put(rotated)
        }
        input.flip()
        return input
    }
    fun inputSha256():String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        digest.update(inputBytes.asReadOnlyBuffer().apply{clear()})
        return digest.digest().joinToString(""){"%02x".format(it.toInt()and 255)}
    }

    fun decodeMask(headData: FloatBuffer, prototypeData: FloatBuffer,
                   width: Int, height: Int): YoloAlignmentMask.Mask? {
        if (headData.remaining() != HEAD_COUNT || prototypeData.remaining() != PROTOTYPE_COUNT) return null
        headData.get(head)
        prototypeData.get(prototypes)
        // The decoder creates independent logits for the returned mask.
        return YoloAlignmentMask.select(head, prototypes, width, height)
    }
}

internal object YoloTensorRotation {
    fun rotate(chw:FloatArray,quarterTurns:Int,target:FloatArray){
        val side=640;val area=side*side
        require(chw.size==3*area&&target.size==chw.size&&quarterTurns in 1..3)
        for(c in 0..2){val base=c*area
            if(quarterTurns==2){for(i in 0 until area)target[base+i]=chw[base+area-1-i]}
            else{for(by in 0 until side step 16)for(bx in 0 until side step 16)
                for(y in by until by+16)for(x in bx until bx+16){
                    val source=if(quarterTurns==1)(side-1-x)*side+y else x*side+side-1-y
                    target[base+y*side+x]=chw[base+source]
                }}
        }
    }
    fun bytes(chw:FloatArray,quarterTurns:Int):ByteArray {
        require(chw.size==3*640*640&&quarterTurns in 0..3)
        val values=if(quarterTurns==0)chw else FloatArray(chw.size).also{rotate(chw,quarterTurns,it)}
        val bytes=ByteBuffer.allocate(values.size*4).order(ByteOrder.LITTLE_ENDIAN)
        for(v in values)bytes.putInt(v.toRawBits())
        return bytes.array()
    }
}
