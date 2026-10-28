package com.hcjeong.forestix.sensors

/** Bounded metadata only. No images, IMU integration, GPS, persistent loop or
 * files. Serialized only with developer raw capture, including refused attempts. */
class DbhDepthTrace(private val limit: Int = 128) {
    data class Sample(val receivedNanos: Long,val frameNanos: Long,val cameraNanos: Long,
        val fullDepthNanos: Long?,val status: String,val trackingFailure: String,
        val acquisitionNanos: Long,val rawDepthNanos: Long?=null,
        val rawStatus: String?=null,val pendingCameraInputs: Int=0,val sampledNanos:Long=receivedNanos)
    private val samples=ArrayDeque<Sample>()
    init {require(limit in 1..512)}
    @Synchronized fun add(sample: Sample) {
        if(samples.lastOrNull()?.let{sample.frameNanos<it.frameNanos}==true)samples.clear()
        samples.addLast(sample);while(samples.size>limit)samples.removeFirst()
    }
    @Synchronized fun snapshot():List<Sample> = samples.toList()
}
