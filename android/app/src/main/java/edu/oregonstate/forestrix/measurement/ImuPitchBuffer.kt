package edu.oregonstate.forestrix.measurement

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class ImuPitchBuffer(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val samples = ArrayDeque<PitchSample>()

    fun start() {
        if (rotationVector != null) {
            sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        samples.clear()
    }

    fun medianPitch(centerNanos: Long = System.nanoTime(), windowNanos: Long = 400_000_000L): Float? {
        val half = windowNanos / 2
        val lo = centerNanos - half
        val hi = centerNanos + half
        val values = samples.filter { it.timestampNanos in lo..hi }.map { it.pitchRad }.sorted()
        if (values.isEmpty()) return mostRecentPitch()
        return values[values.size / 2]
    }

    fun mostRecentPitch(): Float? = samples.lastOrNull()?.pitchRad

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rotation = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        SensorManager.getOrientation(rotation, orientation)
        val pitch = orientation[1]
        samples.addLast(PitchSample(event.timestamp, pitch))
        val cutoff = event.timestamp - 3_000_000_000L
        while (samples.firstOrNull()?.timestampNanos ?: Long.MAX_VALUE < cutoff) {
            samples.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private data class PitchSample(val timestampNanos: Long, val pitchRad: Float)
}
