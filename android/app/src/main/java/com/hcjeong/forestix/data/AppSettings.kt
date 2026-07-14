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


data class SettingsSnapshot(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val logRule: LogRule = LogRule.SCRIBNER,
    val dbhMeasurementMethod: DBHMeasurementMethod = DBHMeasurementMethod.CHORD,
    /// DBH scan capture flow — "depth" (depth-API chord fit) or "caliper"
    /// (two-tap trunk edges). Stored raw to keep the data layer free of a
    /// UI-package enum dependency; the scan screen maps it to DbhCaptureMethod.
    val dbhCaptureMethod: String = "depth",
    /// Per-frame chord algorithm for the depth method — "silhouette"
    /// (iOS-identical pixel-width) or "band" (Android point-cloud diagonal).
    /// Maps to sensors.ChordAlgorithm. Default silhouette so Android matches
    /// iOS out of the box.
    val dbhChordAlgorithm: String = "silhouette",
    val tileURLTemplate: String? = null,
    val tileProviderLabel: String? = null,
    /// Draw the user overlay template on top of the built-in satellite
    /// base layer (mirror of iOS tc.overlayEnabled). The template itself
    /// stays in tileURLTemplate; this only toggles its visibility.
    val overlayEnabled: Boolean = true,
    val providerUsageAcknowledged: Boolean = false,
    val advancedMode: Boolean = false,
    val region: String? = null,
    val regionPickerSeen: Boolean = false,
    /// Developer / research mode — surfaces the live measurement internals
    /// (depth source, intrinsics, point counts, raw chord, pitch, σ) on the
    /// AR screens and unlocks the validation-experiment tooling.
    val developerMode: Boolean = false,
    /// Operator-set target/tree id written into every research-log row while
    /// developer mode is on — groups repeats + joins to ground truth. Shared
    /// across the scan screens and persisted so a field session survives
    /// app restarts (mirror of iOS tc.researchTreeId).
    val researchTreeId: String = "",
    /// App appearance — "light" (default) or "dark". Same Field
    /// High-Contrast identity in both; ForestixTheme maps this to the
    /// light or dark token set (mirror of iOS tc.appearance).
    val appearance: String = "light",
    /// Cruise mode (v3 redesign): the CURRENT project shown in the cruise
    /// map's project chip. Null = no project yet ("New project" chip).
    val cruiseProjectId: String? = null,
    /// Cruise mode: the ACTIVE plot the (+) button scopes "Add tree" to.
    /// Null = no active plot ("Start plot" state). Cleared on close/switch.
    val cruisePlotId: String? = null,
)

private val Context.settingsStore by preferencesDataStore(name = "forestix_settings")

class AppSettings(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val unitSystem = stringPreferencesKey("tc.unitSystem")
        val tileURLTemplate = stringPreferencesKey("tc.tileURLTemplate")
        val tileProviderLabel = stringPreferencesKey("tc.tileProviderLabel")
        val overlayEnabled = booleanPreferencesKey("tc.overlayEnabled")
        val providerUsageAck = booleanPreferencesKey("tc.providerUsageAcknowledged")
        val advancedMode = booleanPreferencesKey("tc.advancedMode")
        val region = stringPreferencesKey("tc.region")
        val regionPickerSeen = booleanPreferencesKey("tc.regionPickerSeen")
        val logRule = stringPreferencesKey("tc.logRule")
        val dbhMethod = stringPreferencesKey("tc.dbhMeasurementMethod")
        val dbhCaptureMethod = stringPreferencesKey("tc.dbhCaptureMethod")
        val dbhChordAlgorithm = stringPreferencesKey("tc.dbhChordAlgorithm")
        val developerMode = booleanPreferencesKey("tc.developerMode")
        val researchTreeId = stringPreferencesKey("tc.researchTreeId")
        val appearance = stringPreferencesKey("tc.appearance")
        val cruiseProjectId = stringPreferencesKey("tc.cruiseProjectId")
        val cruisePlotId = stringPreferencesKey("tc.cruisePlotId")
    }

    private val _state = MutableStateFlow(loadSnapshot())
    val state: StateFlow<SettingsSnapshot> = _state.asStateFlow()

    companion object {
        /// Synchronous read of the persisted appearance for the launch
        /// splash — it paints BEFORE AppEnvironment (and this class) exist,
        /// yet must follow the SAVED appearance setting (default light),
        /// not a hardcoded light theme or the system trait.
        fun peekAppearanceIsDark(context: Context): Boolean = runBlocking {
            context.settingsStore.data.first()[Keys.appearance] == "dark"
        }
    }

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
            dbhCaptureMethod = p[Keys.dbhCaptureMethod] ?: "depth",
            dbhChordAlgorithm = p[Keys.dbhChordAlgorithm] ?: "silhouette",
            tileURLTemplate = p[Keys.tileURLTemplate]?.takeIf { it.isNotBlank() },
            tileProviderLabel = p[Keys.tileProviderLabel],
            overlayEnabled = p[Keys.overlayEnabled] ?: true,
            providerUsageAcknowledged = p[Keys.providerUsageAck] ?: false,
            advancedMode = p[Keys.advancedMode] ?: false,
            region = p[Keys.region],
            regionPickerSeen = p[Keys.regionPickerSeen] ?: false,
            developerMode = p[Keys.developerMode] ?: false,
            researchTreeId = p[Keys.researchTreeId] ?: "",
            appearance = p[Keys.appearance] ?: "light",
            cruiseProjectId = p[Keys.cruiseProjectId],
            cruisePlotId = p[Keys.cruisePlotId],
        )
    }

    fun setCruiseProjectId(value: String?) = update {
        _state.value = _state.value.copy(cruiseProjectId = value)
        if (value == null) it.remove(Keys.cruiseProjectId) else it[Keys.cruiseProjectId] = value
    }

    fun setCruisePlotId(value: String?) = update {
        _state.value = _state.value.copy(cruisePlotId = value)
        if (value == null) it.remove(Keys.cruisePlotId) else it[Keys.cruisePlotId] = value
    }

    fun setAppearance(value: String) = update {
        _state.value = _state.value.copy(appearance = value)
        it[Keys.appearance] = value
    }

    fun setResearchTreeId(value: String) = update {
        _state.value = _state.value.copy(researchTreeId = value)
        it[Keys.researchTreeId] = value
    }

    fun setDeveloperMode(value: Boolean) = update {
        _state.value = _state.value.copy(developerMode = value)
        it[Keys.developerMode] = value
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

    fun setDbhCaptureMethod(raw: String) = update {
        _state.value = _state.value.copy(dbhCaptureMethod = raw)
        it[Keys.dbhCaptureMethod] = raw
    }

    fun setDbhChordAlgorithm(raw: String) = update {
        _state.value = _state.value.copy(dbhChordAlgorithm = raw)
        it[Keys.dbhChordAlgorithm] = raw
    }

    fun setTileURLTemplate(value: String?) = update {
        _state.value = _state.value.copy(tileURLTemplate = value?.takeIf { v -> v.isNotBlank() })
        if (value == null) it.remove(Keys.tileURLTemplate) else it[Keys.tileURLTemplate] = value
    }

    fun setTileProviderLabel(value: String?) = update {
        _state.value = _state.value.copy(tileProviderLabel = value?.takeIf { v -> v.isNotBlank() })
        if (value == null) it.remove(Keys.tileProviderLabel) else it[Keys.tileProviderLabel] = value
    }

    fun setOverlayEnabled(value: Boolean) = update {
        _state.value = _state.value.copy(overlayEnabled = value)
        it[Keys.overlayEnabled] = value
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
