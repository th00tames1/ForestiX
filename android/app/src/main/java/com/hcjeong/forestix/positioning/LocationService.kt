// Location service — port of iOS Positioning/LocationService.swift
// (spec §7.3 + §4.5, REQ-CTR-001 / REQ-NAV-003), on Android backed by
// android.location.LocationManager (no play-services fused provider).
//
// Responsibilities (mirroring the CoreLocation wrapper):
//  * Surface the permission/authorization status so the UI can show a
//    graceful "location denied" banner. Android has no NOT_DETERMINED
//    vs DENIED distinction without an Activity, so `onPermissionResult`
//    lets the composable that owns the request launcher report back.
//  * Maintain a rolling buffer of up to 120 CLLocationSnapshots (2 min
//    at 1 Hz, enough for the 60 s averaging window + headroom).
//  * Expose StateFlows `latestSnapshot` / `headingTrueDeg` for live
//    tier badges and the compass arrow on NavigationScreen. Heading
//    comes from the rotation-vector sensor with a geomagnetic
//    declination correction (prefer true, fall back to magnetic —
//    same rule as the CLHeading handler on iOS).
//  * A synchronous `tier(forHorizontalAccuracyM:)` helper that
//    classifies the most recent fix for the header badge without
//    running the 60 s averager (REQ-NAV-003).

package com.hcjeong.forestix.positioning

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.hcjeong.forestix.data.cruise.PositionTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Map a framework Location to the pure snapshot the averager consumes.
/// `hasAccuracy() == false` maps to -1 so "no accuracy" flows through
/// the same negative-value guard CoreLocation uses.
fun Location.toCLLocationSnapshot(): CLLocationSnapshot = CLLocationSnapshot(
    latitude = latitude,
    longitude = longitude,
    horizontalAccuracyM = if (hasAccuracy()) accuracy.toDouble() else -1.0,
    timestamp = time,
)

class LocationService(
    private val context: Context,
    val bufferCapacity: Int = 120,
) {

    enum class AuthStatus {
        NOT_DETERMINED, DENIED, RESTRICTED, AUTHORIZED_WHEN_IN_USE, AUTHORIZED,

        /// Platform has no location manager at all.
        UNSUPPORTED,
    }

    /// Up to `bufferCapacity` recent samples in insertion order. The
    /// PlotCenter averager pulls the last 60 when the user accepts.
    private val _buffer = MutableStateFlow<List<CLLocationSnapshot>>(emptyList())
    val buffer: StateFlow<List<CLLocationSnapshot>> = _buffer.asStateFlow()

    private val _latestSnapshot = MutableStateFlow<CLLocationSnapshot?>(null)
    val latestSnapshot: StateFlow<CLLocationSnapshot?> = _latestSnapshot.asStateFlow()

    private val _authStatus = MutableStateFlow(AuthStatus.NOT_DETERMINED)
    val authStatus: StateFlow<AuthStatus> = _authStatus.asStateFlow()

    /// Compass heading in degrees true north (0…360). null until the
    /// first heading update arrives. Used by NavigationScreen for the
    /// arrow rotation.
    private val _headingTrueDeg = MutableStateFlow<Double?>(null)
    val headingTrueDeg: StateFlow<Double?> = _headingTrueDeg.asStateFlow()

    private val locationManager: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val sensorManager: SensorManager? =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private var running = false

    init {
        if (locationManager == null) {
            _authStatus.value = AuthStatus.UNSUPPORTED
        } else {
            refreshAuthStatus()
        }
    }

    // MARK: - Lifecycle

    /// On iOS this pops the system dialog. Android permission requests
    /// need an Activity result launcher, which lives in the composable
    /// layer (see GpsAccuracyBadge) — here we just re-read the current
    /// grant state so callers observing `authStatus` stay fresh.
    fun requestAuthorization() {
        refreshAuthStatus()
    }

    /// Report the outcome of a runtime permission request made by the
    /// UI layer, resolving the NOT_DETERMINED / DENIED ambiguity.
    fun onPermissionResult(granted: Boolean) {
        _authStatus.value =
            if (granted) AuthStatus.AUTHORIZED_WHEN_IN_USE else AuthStatus.DENIED
    }

    /// Begin 1 Hz location updates (GPS + network providers) and
    /// rotation-vector heading updates. No-op without permission or on
    /// unsupported hosts — mirroring the iOS stub path that "refuses
    /// to start".
    fun start() {
        val lm = locationManager ?: return
        refreshAuthStatus()
        if (!hasLocationPermission(context)) return
        if (running) return
        try {
            // 1 Hz matches the CoreLocation cadence the 120-sample
            // buffer was sized for.
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(
                        provider,
                        1000L,
                        0f,
                        locationListener,
                        Looper.getMainLooper(),
                    )
                }
            }
            running = true
        } catch (_: SecurityException) {
            _authStatus.value = AuthStatus.DENIED
            return
        }
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(headingListener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        locationManager?.removeUpdates(locationListener)
        sensorManager?.unregisterListener(headingListener)
        running = false
    }

    /// Grab the most recent `n` samples — what PlotCenterViewModel
    /// feeds into `GPSAveraging.compute` once the averaging window
    /// elapses.
    fun recentSamples(n: Int): List<CLLocationSnapshot> =
        _buffer.value.takeLast(n)

    fun clearBuffer() {
        _buffer.value = emptyList()
    }

    // MARK: - Internal

    private fun ingest(s: CLLocationSnapshot) {
        val next = _buffer.value + s
        _buffer.value =
            if (next.size > bufferCapacity) next.takeLast(bufferCapacity) else next
        _latestSnapshot.value = s
    }

    private fun refreshAuthStatus() {
        if (locationManager == null) {
            _authStatus.value = AuthStatus.UNSUPPORTED
            return
        }
        if (hasLocationPermission(context)) {
            // Android runtime grants are foreground-only by default,
            // the analogue of CoreLocation's whenInUse.
            _authStatus.value = AuthStatus.AUTHORIZED_WHEN_IN_USE
        } else if (_authStatus.value != AuthStatus.DENIED) {
            _authStatus.value = AuthStatus.NOT_DETERMINED
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            ingest(location.toCLLocationSnapshot())
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in API 29; required override on older devices")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }

    private val headingListener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            var deg = Math.toDegrees(orientation[0].toDouble())
            // Prefer true heading: add magnetic declination when we
            // have a fix to compute it at; fall back to magnetic
            // otherwise (mirror of the CLHeading trueHeading >= 0 rule).
            val snap = _latestSnapshot.value
            if (snap != null) {
                val field = GeomagneticField(
                    snap.latitude.toFloat(),
                    snap.longitude.toFloat(),
                    0f,
                    System.currentTimeMillis(),
                )
                deg += field.declination
            }
            _headingTrueDeg.value = (deg + 360.0) % 360.0
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    companion object {

        /// The single runtime permission the positioning stack needs.
        const val PERMISSION: String = Manifest.permission.ACCESS_FINE_LOCATION

        fun hasLocationPermission(context: Context): Boolean =
            context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

        // MARK: - Live tier classification (REQ-NAV-003)

        /// Quick "what tier would one sample be?" for the nav header
        /// badge. Uses a conservative single-sample rule: horizontal
        /// accuracy alone drives the tier (we don't have scatter for a
        /// 1-sample window). Matches the UX: "GPS-C" while walking,
        /// "GPS-A" when it tightens up.
        fun tier(forHorizontalAccuracyM: Double): PositionTier {
            if (forHorizontalAccuracyM <= 0) return PositionTier.D
            if (forHorizontalAccuracyM < 5) return PositionTier.A
            if (forHorizontalAccuracyM < 10) return PositionTier.B
            if (forHorizontalAccuracyM < 20) return PositionTier.C
            return PositionTier.D
        }
    }
}
