package com.hcjeong.forestix.sensors

import kotlin.math.*

/** ARCore Raw Depth + its REAL unsigned 0..255 confidence, never substituted
 * for the full-depth map's synthetic validity mask. Native depth-grid axes. */
data class DbhRawDepthEvidence(
    val width: Int, val height: Int,
    val millimeters: ShortArray, val confidence: ByteArray,
    val timestampNanos: Long, val confidenceTimestampNanos: Long,
    val cameraTimestampNanos: Long,
) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 4*1024*1024)
        require(millimeters.size == width*height && confidence.size == millimeters.size)
    }
}

/** Diagnostic comparison, not an independent sensor calibration. Sparse or
 * absent Raw Depth is UNKNOWN. Old information reprojected into a current
 * view is not the same as a stale projection, and is evaluated as such.
 * This does not change the displayed or saved measurement. */
object DbhRawDepthSupport {
    const val MIN_CONFIDENCE = 128
    data class Assessment(val status: String, val supportedPixels: Int = 0,
        val sampledPixels: Int = 0, val pairedDifferenceM: Double? = null,
        val rawMedianM: Double? = null, val fullPairedMedianM: Double? = null)
    private fun median(values: List<Double>): Double {
        val s=values.sorted();return (s[(s.size-1)/2]+s[s.size/2])/2
    }
    fun assess(frame: ArDepthFrame, axis: GuideAxis, leftFraction: Double, rightFraction: Double): Assessment {
        val raw=frame.rawDepthEvidence ?: return Assessment("raw_not_available")
        if(raw.width!=frame.width || raw.height!=frame.height ||
            !DbhMeasurementDepth.currentProjection(raw))
            return Assessment("raw_not_current_or_aligned")
        val col=axis is GuideAxis.Col
        val width=if(col)frame.height else frame.width
        val fixed=when(axis){is GuideAxis.Col->axis.x;is GuideAxis.Row->axis.y}
        val lo=min(leftFraction,rightFraction)*width
        val hi=max(leftFraction,rightFraction)*width
        if(!lo.isFinite()||!hi.isFinite()||lo<0||hi>=width||hi<=lo) return Assessment("invalid_bracket")
        val a=ceil(lo+(hi-lo)*.1).toInt();val b=floor(hi-(hi-lo)*.1).toInt()
        val depths=ArrayList<Double>();val full=ArrayList<Double>();val differences=ArrayList<Double>()
        val bins=BooleanArray(3)
        var count=0
        for(i in a..b)for(offset in -2..2){
            val x=if(col)fixed+offset else i;val y=if(col)i else fixed+offset
            if(x !in 0 until frame.width||y !in 0 until frame.height)continue
            count++
            val p=y*frame.width+x;val mm=raw.millimeters[p].toInt() and 0xffff
            val score=raw.confidence[p].toInt() and 0xff
            val fullDepth=frame.depth[p].toDouble()
            if(score<MIN_CONFIDENCE||mm !in 1..8000||!fullDepth.isFinite()||fullDepth !in .001..8.0)continue
            val z=mm/1000.0
            depths.add(z);full.add(fullDepth);differences.add(abs(z-fullDepth))
            bins[min(2,((i-a)*3/max(1,b-a+1)))]=true
        }
        if(depths.size<6||bins.any{!it})return Assessment("raw_support_insufficient",depths.size,count)
        val gap=median(differences);val rawZ=median(depths);val fullZ=median(full)
        // An application contradiction tolerance, NOT Google's confidence
        // guarantee or a fitted multiplier. Must be checked in lab/field data.
        val threshold=max(.03,.05*rawZ)
        return Assessment(if(gap>threshold)"raw_full_disagreement"else"raw_full_agree",
            depths.size,count,gap,rawZ,fullZ)
    }
}
