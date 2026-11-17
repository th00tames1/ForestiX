package com.hcjeong.forestix.sensors

import java.io.File
import kotlinx.serialization.json.*

data class RawIMUStream(val name:String,val source:String,val columns:List<String>,val units:List<String>,
    val available:Boolean,val requestedIntervalSeconds:Double,val referenceFrame:String?=null) {
    fun json()=buildJsonObject {
        put("name",name);put("source",source);put("columns",JsonArray(columns.map(::JsonPrimitive)))
        put("units",JsonArray(units.map(::JsonPrimitive)));put("available",available)
        put("requestedIntervalSeconds",requestedIntervalSeconds);referenceFrame?.let{put("referenceFrame",it)}
    }
}
data class RawIMUSample(val stream:String,val timestamp:String,val received:String,val values:List<Double>) {
    fun json()=buildJsonObject { put("stream",stream);put("timestamp",timestamp);put("received",received)
        put("values",JsonArray(values.map(::JsonPrimitive))) }
}
data class RawIMUEvent(val event:String,val hostTimestamp:String,val arTimestamp:String?=null,
    val cameraPose:List<Double>?=null,val note:String?=null) {
    fun json()=buildJsonObject { put("event",event);put("hostTimestamp",hostTimestamp)
        arTimestamp?.let{put("arTimestamp",it)};cameraPose?.let{put("cameraPose",JsonArray(it.map(::JsonPrimitive)))}
        note?.let{put("note",it)} }
}
data class RawHeightObservation(val arTimestamp:String?,val observedHostTimestamp:String,
    val cameraPose:List<Double>?,val anchorPose:List<Double>?,val trackingState:String,val trackingReason:String?,
    val thermalState:String,val deliveredArTimestamp:String?,val deliveryHostTimestamp:String?,
    val nativeFrameIntervalSeconds:Double?,val monotonicHostTimestamp:String?=null,val anchorTrackingState:String?=null) {
    fun json()=buildJsonObject {
        arTimestamp?.let{put("arTimestamp",it)};put("observedHostTimestamp",observedHostTimestamp)
        put("poseConvention","column-major 4x4 camera/anchor to AR world, metres; y up; camera looks along -z")
        cameraPose?.let{put("cameraPose",JsonArray(it.map(::JsonPrimitive)))}
        anchorPose?.let{put("anchorPose",JsonArray(it.map(::JsonPrimitive)))}
        put("trackingState",trackingState);trackingReason?.let{put("trackingReason",it)};put("thermalState",thermalState)
        deliveredArTimestamp?.let{put("deliveredArTimestamp",it)};deliveryHostTimestamp?.let{put("deliveryHostTimestamp",it)}
        nativeFrameIntervalSeconds?.let{put("nativeFrameIntervalSeconds",it)}
        monotonicHostTimestamp?.let{put("monotonicHostTimestamp",it)};anchorTrackingState?.let{put("anchorTrackingState",it)}
    }
}
data class RawInertialRecording(val measurementId:String,val streams:List<RawIMUStream>,val samples:List<RawIMUSample>,
    val events:List<RawIMUEvent>,val observations:List<RawHeightObservation>,val droppedSamples:Int,val capped:Boolean,
    val interruptions:Int,val maximumDurationSeconds:Double) {
    val status:String get() {
        val required=listOf("accelerometer","gyroscope")
        if(!required.all{n->streams.any{it.name==n&&it.available}})return "unavailable"
        return if(capped||droppedSamples>0||interruptions>0||!listOf("anchor","base","top").all{n->events.any{it.event==n}}||
            !required.all{n->samples.any{it.stream==n}}||events.any{it.event=="sensor_error"}) "partial" else "captured"
    }
    fun json()=buildJsonObject {
        put("schema",1);put("measurementId",measurementId);put("platform","android");put("status",status)
        put("sensorTimeUnit","nanoseconds");put("hostTimeUnit","nanoseconds")
        put("sensorClock","SensorEvent.timestamp: BOOTTIME, same clock as elapsedRealtimeNanos")
        put("hostClock","SystemClock.elapsedRealtimeNanos: BOOTTIME")
        put("arTimeUnit","nanoseconds (ARCore Frame.timestamp); preserve native clock, do not assume same epoch as BOOTTIME")
        put("axes","device-fixed: x right, y top, z out of screen; independent of display rotation")
        put("includesCameraInInertialSamples",false);put("maximumDurationSeconds",maximumDurationSeconds)
        put("streams",JsonArray(streams.map{it.json()}));put("samples",JsonArray(samples.map{it.json()}))
        put("events",JsonArray(events.map{it.json()}));put("observations",JsonArray(observations.map{it.json()}))
        put("droppedSamples",droppedSamples);put("capped",capped);put("interruptions",interruptions)
        put("streamStatistics",buildJsonObject { streams.forEach { spec ->
            val rows=samples.filter{it.stream==spec.name}
            val delta=rows.zipWithNext{a,b->(b.timestamp.toLong()-a.timestamp.toLong())/1e9}
            val positive=delta.filter{it>0}
            put(spec.name,buildJsonObject { put("count",rows.size)
                rows.firstOrNull()?.let{put("firstTimestamp",it.timestamp)};rows.lastOrNull()?.let{put("lastTimestamp",it.timestamp)}
                if(positive.isNotEmpty()){put("meanIntervalSeconds",positive.average());put("maximumGapSeconds",positive.max())}
                put("nonIncreasingTimestamps",delta.count{it<=0}) })
        } })
    }
    fun write(id:String,directory:File):JsonObject {
        require(id==measurementId){"IMU measurement identity mismatch"}
        val temporary=File.createTempFile("imu_",".pending",directory)
        val written=RawCaptureEvidenceIO.write(json().toString().toByteArray(Charsets.UTF_8),temporary.name,directory)
        val destination=File(directory,"imu.json")
        require(!destination.exists()){"IMU sidecar already exists"}
        try { java.nio.file.Files.move(temporary.toPath(),destination.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        catch (_:java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(temporary.toPath(),destination.toPath()) }
        val f=written.copy(file="imu.json")
        f.read(directory)
        return buildJsonObject { put("file",buildJsonObject { put("file",f.file);put("bytes",f.bytes);put("sha256",f.sha256) })
            put("status",status);put("sampleCount",samples.size);put("observationCount",observations.size) }
    }
}
/** Thread-safe bounded evidence; never enters the measurement algorithm. */
class RawIMUBuffer(private val maximumSamples:Int=40_000,private val maximumDuration:Double=120.0) {
    private var streams=emptyList<RawIMUStream>()
    private var samples=ArrayList<RawIMUSample>();private var hostTimes=ArrayList<Double>()
    private var events=ArrayList<RawIMUEvent>();private var observations=ArrayList<RawHeightObservation>()
    private var id:String?=null;private var start:Double?=null;private var active=false;private var finished=false
    private var capped=false;private var dropped=0;private var interruptions=0
    @Synchronized fun needsFreshArm()=streams.isEmpty()||finished
    @Synchronized fun arm(specs:List<RawIMUStream>) {
        streams=specs.toList();samples.clear();hostTimes.clear();events.clear();observations.clear()
        id=null;start=null;active=true;finished=false;capped=false;dropped=0;interruptions=0
    }
    @Synchronized fun resume(host:String){if(!finished&&!capped){if(!active&&id!=null)mark(RawIMUEvent("resume",host));active=true}}
    @Synchronized fun pause(host:String,reason:String){if(active&&!finished&&id!=null){interruptions++;mark(RawIMUEvent("pause",host,note=reason))};active=false}
    @Synchronized fun begin(id:String,hostSeconds:Double,event:RawIMUEvent) {
        val kept=samples.indices.filter{hostTimes[it]>=hostSeconds-2}
        samples=ArrayList(kept.map{samples[it]});hostTimes=ArrayList(kept.map{hostTimes[it]})
        this.id=id;start=hostSeconds;events.clear();events.add(event);observations.clear();finished=false;capped=false;dropped=0;interruptions=0
    }
    @Synchronized fun mark(event:RawIMUEvent){
        if(id==null||finished)return
        if(events.size<128)events.add(event)else capped=true
    }
    @Synchronized fun observe(observation:RawHeightObservation){
        if(id==null||!active||finished||capped)return
        if(observations.size>=700){capped=true;dropped++;return};observations.add(observation)
    }
    @Synchronized fun append(sample:RawIMUSample,hostSeconds:Double):Boolean {
        if(!active||finished||capped)return false
        val spec=streams.firstOrNull{it.name==sample.stream}
        if(spec==null||!spec.available||spec.columns.size!=sample.values.size||!sample.values.all{it.isFinite()}||
            sample.timestamp.toLongOrNull()==null||sample.received.toLongOrNull()==null||!hostSeconds.isFinite()){dropped++;return true}
        val begin=start
        if(begin!=null){
            if(hostSeconds<begin-2){dropped++;return true}
            if(samples.size>=maximumSamples||hostSeconds-begin>maximumDuration){capped=true;dropped++;active=false;return false}}
        else if(samples.size>=2000){samples.subList(0,1000).clear();hostTimes.subList(0,1000).clear()}
        samples.add(sample);hostTimes.add(hostSeconds);return true
    }
    @Synchronized fun finish():RawInertialRecording? {
        val id=id?:return null;if(finished)return null;finished=true;active=false
        val recording=RawInertialRecording(id,streams.toList(),samples.toList(),events.toList(),observations.toList(),dropped,capped,interruptions,maximumDuration)
        // The writer owns immutable copies now. Do not retain every sensor
        // object while the operator leaves the result screen open.
        samples.clear();hostTimes.clear();events.clear();observations.clear()
        return recording
    }
}
