// App-level user preferences — port of iOS App/AppSettings.swift, backed
// by Jetpack DataStore instead of UserDefaults. Exposes a StateFlow of an
// immutable snapshot the Compose UI collects, plus setters that persist.
//
// Defaults match iOS exactly: unitSystem = imperial, logRule = scribner,
// dbhMeasurementMethod = chord (Phase 19).

package com.hcjeong.forestix.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hcjeong.forestix.common.UnitSystem
import com.hcjeong.forestix.sensors.LogRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/// Phase 19 DBH algorithm selector. chord = silhouette/pixel-width
/// (default, matches peer LiDAR apps); arc = older partial-arc circle fit.
enum class DBHMeasurementMethod(val raw: String) {
    CHORD("chord"), ARC("arc");
    companion object { fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s } ?: CHORD }
}

/// Which world-sensing path the AR measurement screens raycast against —
/// mirror of the iOS MeasurementSource. LIDAR uses ARCore depth-point
/// hits (the Depth API, the LiDAR-equivalent); AR filters to plane hits so
/// it works on every device. Clamped to AR at runtime on devices whose
/// Depth API isn't supported.
enum class MeasurementSource(val raw: String, val displayName: String) {
    LIDAR("lidar", "LiDAR"), AR("ar", "AR");
    companion object { fun fromRaw(s: String?) = entries.firstOrNull { it.raw == s } ?: LIDAR }
}

data class SettingsSnapshot(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val logRule: LogRule = LogRule.SCRIBNER,
    val dbhMeasurementMethod: DBHMeasurementMethod = DBHMeasurementMethod.CHORD,
    val measurementSource: MeasurementSource = MeasurementSource.LIDAR,
    val tileURLTemplate: String? = null,
    val tileProviderLabel: String? = null,
    val providerUsageAcknowledged: Boolean = false,
    val advancedMode: Boolean = false,
    val region: String? = null,
    val regionPickerSeen: Boolean = false,
)

private val Context.settingsStore by preferencesDataStore(name = "forestix_settings")

class AppSettings(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val unitSystem = stringPreferencesKey("tc.unitSystem")
        val tileURLTemplate = stringPreferencesKey("tc.tileURLTemplate")
        val tileProviderLabel = stringPreferencesKey("tc.tileProviderLabel")
        val providerUsageAck = booleanPreferencesKey("tc.providerUsageAcknowledged")
        val advancedMode = booleanPreferencesKey("tc.advancedMode")
        val region = stringPreferencesKey("tc.region")
        val regionPickerSeen = booleanPreferencesKey("tc.regionPickerSeen")
        val logRule = stringPreferencesKey("tc.logRule")
        val dbhMethod = stringPreferencesKey("tc.dbhMeasurementMethod")
        val measurementSource = stringPreferencesKey("tc.measurementSource")
    }

    private val _state = MutableStateFlow(loadSnapshot())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    private fun loadSnapshot(): SettingsSnapshot = runBlocking {
        // First emission is the persisted prefs.
        val p = context.settingsStore.data.first()
        SettingsSnapshot(
            unitSystem = when (p[Keys.unitSystem]) {
                "metric" -> UnitSystem.METRIC
                else -> UnitSystem.IMPERIAL
            },
            logRule = LogRule.fromRaw(p[Keys.logRule] ?: "scribner"),
            dbhMeasurementMethod = DBHMeasurementMethod.fromRaw(p[Keys.dbhMethod]),
            measurementSource = MeasurementSource.fromRaw(p[Keys.measurementSource]),
            tileURLTemplate = p[Keys.tileURLTemplate]?.takeIf { it.isNotBlank() },
            tileProviderLabel = p[Keys.tileProviderLabel],
            providerUsageAcknowledged = p[Keys.providerUsageAck] ?: false,
            advancedMode = p[Keys.advancedMode] ?: false,
            region = p[Keys.region],
            regionPickerSeen = p[Keys.regionPickerSeen] ?: false,
        )
    }

    fun setUnitSystem(value: UnitSystem) = update {
        _state.value = _state.value.copy(unitSystem = value)
        it[Keys.unitSystem] = if (value == UnitSystem.METRIC) "metric" else "imperial"
    }

    fun setLogRule(value: LogRule) = update {
        _state.value = _state.value.copy(logRule = value)
        it[Keys.logRule] = value.name.lowercase()
    }

    fun setDbhMethod(value: DBHMeasurementMethod) = update {
        _state.value = _state.value.copy(dbhMeasurementMethod = value)
        it[Keys.dbhMethod] = value.raw
    }

    fun setMeasurementSource(value: MeasurementSource) = update {
        _state.value = _state.value.copy(measurementSource = value)
        it[Keys.measurementSource] = value.raw
    }

    fun setTileURLTemplate(value: String?) = update {
        _state.value = _state.value.copy(tileURLTemplate = value?.takeIf { v -> v.isNotBlank() })
        if (value == null) it.remove(Keys.tileURLTemplate) else it[Keys.tileURLTemplate] = value
    }

    fun setProviderUsageAcknowledged(value: Boolean) = update {
        _state.value = _state.value.copy(providerUsageAcknowledged = value)
        it[Keys.providerUsageAck] = value
    }

    fun setAdvancedMode(value: Boolean) = update {
        _state.value = _state.value.copy(advancedMode = value)
        it[Keys.advancedMode] = value
    }

    fun setRegion(value: String?) = update {
        _state.value = _state.value.copy(region = value)
        if (value == null) it.remove(Keys.region) else it[Keys.region] = value
    }

    fun setRegionPickerSeen(value: Boolean) = update {
        _state.value = _state.value.copy(regionPickerSeen = value)
        it[Keys.regionPickerSeen] = value
    }

    private fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { context.settingsStore.edit(block) }
    }
}
