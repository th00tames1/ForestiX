package com.hcjeong.forestix.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/** Created only in developer raw mode. Sensor callbacks never touch UI/disk. */
class RawIMUCapture(context:Context):SensorEventListener {
    private val manager=context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val buffer=RawIMUBuffer()
    private var thread:HandlerThread?=null
    private var running=false
    private val sensors=listOf(
        "accelerometer" to manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
        "gyroscope" to (manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)?:manager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)),
        "attitude" to manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR))
    private fun specs()=sensors.map{(name,s)->
        val columns=when(name){"attitude"->listOf("qw","qx","qy","qz");"gyroscope"->if(s?.type==Sensor.TYPE_GYROSCOPE_UNCALIBRATED)listOf("x","y","z","bias_x","bias_y","bias_z")else listOf("x","y","z");else->listOf("x","y","z")}
        RawIMUStream(name,s?.let{"${it.stringType}; ${it.vendor}; ${it.name}; version=${it.version}"}?:"unavailable",columns,
            List(columns.size){when(name){"attitude"->"1";"accelerometer"->"m/s^2";else->"rad/s"}},s!=null,
            if(name=="attitude")0.02 else 0.01,if(name=="attitude")"TYPE_GAME_ROTATION_VECTOR; SensorManager quaternion as returned wxyz; no geomagnetic heading" else null)
    }
    @Synchronized fun resume() {
        if(running)return
        val fresh=buffer.needsFreshArm()
        if(fresh)buffer.arm(specs())
        buffer.resume(SystemClock.elapsedRealtimeNanos().toString())
        val t=thread?:HandlerThread("Forestix.rawIMU").also{it.start();thread=it}
        val failed=mutableSetOf<String>()
        sensors.forEach{(name,s)->if(s!=null&&manager?.registerListener(this,s,if(name=="attitude")20_000 else 10_000,Handler(t.looper))!=true)failed.add(name)}
        if(fresh&&failed.isNotEmpty())buffer.arm(specs().map{if(it.name in failed)it.copy(available=false)else it})
        else failed.forEach{mark("sensor_error",note="registration failed: $it")}
        running=true
    }
    @Synchronized private fun stop(){manager?.unregisterListener(this);running=false}
    fun pause(){buffer.pause(SystemClock.elapsedRealtimeNanos().toString(),"screen inactive/background");stop()}
    fun begin(id:String,arTimestamp:Long?,cameraPose:FloatArray?) {
        val now=SystemClock.elapsedRealtimeNanos()
        buffer.begin(id,now/1e9,RawIMUEvent("anchor",now.toString(),arTimestamp?.toString(),cameraPose?.map{it.toDouble()},
            "monotonicHostTimestamp=${System.nanoTime()}"))
    }
    fun mark(event:String,arTimestamp:Long?=null,cameraPose:FloatArray?=null,note:String?=null){
        buffer.mark(RawIMUEvent(event,SystemClock.elapsedRealtimeNanos().toString(),arTimestamp?.toString(),cameraPose?.map{it.toDouble()},note))
    }
    fun observe(observation:RawHeightObservation){buffer.observe(observation)}
    fun finish():RawInertialRecording?{stop();thread?.quitSafely();thread=null;return buffer.finish()}
    fun close(){pause();thread?.quitSafely();thread=null}
    override fun onSensorChanged(event:SensorEvent){
        val stream=sensors.firstOrNull{it.second?.type==event.sensor.type}?.first?:return
        val count=if(event.sensor.type==Sensor.TYPE_GYROSCOPE_UNCALIBRATED)6 else 3
        if(event.values.size<count){mark("sensor_error",note="short native sample: $stream");return}
        val values=if(stream=="attitude")FloatArray(4).also{SensorManager.getQuaternionFromVector(it,event.values)}
            else event.values.copyOf(count)
        val now=SystemClock.elapsedRealtimeNanos()
        if(!buffer.append(RawIMUSample(stream,event.timestamp.toString(),now.toString(),values.map{it.toDouble()}),now/1e9))stop()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){mark("sensor_accuracy",note="${sensor?.stringType}: $accuracy")}
}
