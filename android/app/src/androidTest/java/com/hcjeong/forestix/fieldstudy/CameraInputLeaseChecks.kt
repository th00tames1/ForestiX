package com.hcjeong.forestix.fieldstudy

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import com.hcjeong.forestix.ar.ArController
import java.util.concurrent.atomic.AtomicInteger

/** Real offscreen YUV images, not a camera/AR session or user photograph.
 * Run only after the operator is finished; instrumentation restarts the app. */
internal object CameraInputLeaseChecks {
    private fun <T> withImage(operation:(Image)->T):T {
        ImageReader.newInstance(4,4,ImageFormat.YUV_420_888,2).use { reader ->
            ImageWriter.newInstance(reader.surface,2).use { writer ->
                writer.dequeueInputImage().use { image ->
                    image.planes.forEachIndexed { index,plane ->
                        val w=if(index==0)4 else 2;val h=w
                        for(y in 0 until h)for(x in 0 until w)
                            plane.buffer.put(y*plane.rowStride+x*plane.pixelStride,(80+index*30+y*4+x).toByte())
                    }
                    return operation(image)
                }
            }
        }
    }
    fun run() {
        val mapping=floatArrayOf(1f,0f,0f,0f,1f,0f)
        // Compare exact prepared input against the previous conversion path.
        val expected=withImage { image ->
            ArController.CameraLetterboxInput(image,640,mapping,100).use { requireNotNull(it.convert()) }
        }
        withImage { image ->
            val closed=AtomicInteger()
            val input=ArController.CameraLetterboxInput(image,640,mapping,100,onClosed={closed.incrementAndGet()})
            val actual=requireNotNull(input.prepareAndClose())
            check(expected.second==actual.second)
            check(expected.first.contentEquals(actual.first)){"RGB tensor changed"}
            check(closed.get()==1&&input.convert()==null){"Camera still owned by inference"}
            check(runCatching{image.planes}.isFailure){"Native image was not closed before inference"}
            // ONNX uses the copied array after close, even if inference stalls.
            check(actual.first.all{it.isFinite()}&&actual.first.size==3*640*640)
            check(input.imageFromViewAffine.contentEquals(mapping)&&input.frameTimestampNanos==100L)
            input.close();check(closed.get()==1){"Double close callback"}
        }
        withImage { image ->
            val closed=AtomicInteger()
            val input=ArController.CameraLetterboxInput(image,0,mapping,100,onClosed={closed.incrementAndGet()})
            check(input.prepareAndClose()==null&&closed.get()==1){"Failed preparation retained image"}
            input.close();check(closed.get()==1)
        }
    }
}
