// App-level user preferences — port of iOS App/AppSettings.swift, backed
// by Jetpack DataStore instead of UserDefaults. Exposes a StateFlow of an
// immutable snapshot the Compose UI collects, plus setters that persist.
//
// Defaults match iOS exactly: unitSystem = imperial, logRule = scribner,
// dbhMeasurementMethod = chord (Phase 19).

package com.hcjeong.forestix.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hcjeong.forestix.common.Country
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

/// The DBH bracket's legal half-width range. 0.02 is half the fit's 0.04
/// minimum handle gap; 0.48 puts the handles 2 % in from each screen edge,
/// where they are still grabbable. Mirror of iOS
/// `AppSettings.clampBracketHalfWidth`.
fun clampBracketHalfWidth(value: Float): Float =
    if (!value.isFinite()) 0.25f else value.coerceIn(0.02f, 0.48f)


data class SettingsSnapshot(
    val unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    val logRule: LogRule = LogRule.SCRIBNER,
    val dbhMeasurementMethod: DBHMeasurementMethod = DBHMeasurementMethod.CHORD,
    /// Per-frame chord algorithm for the depth method — "silhouette"
    /// (iOS-identical pixel-width) or "band" (Android point-cloud diagonal).
    /// Maps to sensors.ChordAlgorithm. Default silhouette so Android matches
    /// iOS out of the box.
    val dbhChordAlgorithm: String = "silhouette",
    /// Whether the diameter scan opens with the edge bracket (ADJUST) up.
    ///
    /// DEFAULTS TO TRUE. Automatic edge-finding jitters
    /// left and right on a real stem: bark texture, a stick against the
    /// trunk, a second stem behind it, and the found edges move between
    /// frames. The cruiser's verdict on it was that the automatic path was
    /// not usable in the stand. Placing the bracket by hand takes one drag
    /// and is stable, so that is what the screen opens on.
    ///
    /// IT BRIEFLY WENT BACK TO AUTO, for one commit, after the field
    /// reported the bracket reading 1.5x high here and showing nothing at
    /// all on iOS. Both were faults in this round's own changes — a
    /// symmetric-about-the-crosshair drag that over-reads whenever the aim
    /// is not exactly on the stem centre, and (iOS) a view-to-depth mapping
    /// that was never built. With those fixed the bracket is back to what
    /// the field had already confirmed works, so it is the default again.
    ///
    /// It is the LAST-USED mode, not a hard default: the Auto pill still
    /// exists and a cruiser who prefers automatic edges gets it back on the
    /// next scan without hunting for a Settings row. Mirror of iOS
    /// tc.dbhEdgeAdjustDefault.
    val dbhEdgeAdjustDefault: Boolean = true,
    /// HALF the bracket width, as a fraction of the view's walk axis, so a
    /// bracket set on one tree opens at the same width on the next.
    ///
    /// FIELD REPORT 4 — a cruise walks a plot at roughly one standing
    /// distance from stem to stem, so consecutive trees subtend nearly the
    /// same on-screen width. Re-dragging from ±25 % every time was work the
    /// previous tree had already done.
    ///
    /// HALF-width, not two edges, because the bracket is symmetric about
    /// the crosshair by construction — one number is the whole state.
    /// Mirror of iOS tc.dbhBracketHalfWidth.
    val dbhBracketHalfWidth: Float = 0.25f,
    /// Which BUILT-IN base layer the map draws — "satellite" (Esri World
    /// Imagery) or "normal" (OpenStreetMap standard). Default satellite so
    /// nothing changes for existing installs (mirror of iOS tc.mapType).
    /// The user's own XYZ template is a separate OVERLAY, not a base.
    val mapType: String = "satellite",
    val tileURLTemplate: String? = null,
    val tileProviderLabel: String? = null,
    /// Draw the user overlay template on top of the built-in satellite
    /// base layer (mirror of iOS tc.overlayEnabled). The template itself
    /// stays in tileURLTemplate; this only toggles its visibility.
    val overlayEnabled: Boolean = true,
    val providerUsageAcknowledged: Boolean = false,
    /// Internationalization framework — Country sits above Region and derives
    /// the volume standard, unit (board-foot vs m³) and whether the Log rule
    /// applies. Defaults to UNITED_STATES so existing US installs are
    /// unchanged (mirror of iOS tc.country).
    val country: Country = Country.UNITED_STATES,
    val region: String? = null,
    /// Gates the first-run Country → Region cascade (formerly the US-only
    /// region picker). Stamped once the cascade is picked, skipped or
    /// dismissed so it never auto-presents again.
    val regionPickerSeen: Boolean = false,
    /// CRUISE — after a diameter is accepted in the cruise tally, go
    /// straight into Height for the SAME tree. Field report F10: the tally
    /// used to loop diameter-only, so heights were never asked for and the
    /// cruiser had to go back through the tree peek for every one.
    ///
    /// Defaults to TRUE. Cruisers running a diameter-only tally turn it off
    /// in Settings › Measuring. Key + wording identical on iOS.
    val measureHeightAfterDiameter: Boolean = true,
    /// Developer / research mode — surfaces the live measurement internals
    /// (depth source, intrinsics, point counts, raw chord, pitch, σ) on the
    /// AR screens and unlocks the validation-experiment tooling.
    val developerMode: Boolean = false,
    /// Operator-set target/tree id written into every research-log row while
    /// developer mode is on — groups repeats + joins to ground truth. Shared
    /// across the scan screens and persisted so a field session survives
    /// app restarts (mirror of iOS tc.researchTreeId).
    val researchTreeId: String = "",
    /// Raw-capture recording (developer mode only) — when ON, every DBH
    /// capture burst and Height compute serializes a replay bundle (raw
    /// depth u16 + intrinsics + poses + calibration) under
    /// filesDir/raw-captures/ so the estimator can be re-run offline against
    /// field ground truth. Default OFF; zero cost while off (mirror of iOS
    /// tc.rawCaptureEnabled). Cross-platform schema is identical.
    val rawCaptureEnabled: Boolean = false,
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
    /// Map-home mode (v3.1): the single map screen renders "measure"
    /// (quick-measure home, default) or "cruise" (the absorbed cruise map).
    /// Persisted so the app reopens in the mode the cruiser left it in
    /// (mirror of iOS tc.mapMode).
    val mapMode: String = "measure",
    /// Cached ARCore Depth-API capability verdict (Android-only; iOS
    /// checks LiDAR statically). ONLY a definitive session report writes
    /// it (ArSessionHub.applySessionConfig → the AppEnvironment-wired
    /// recorder): true after `isDepthModeSupported` returned false, and
    /// cleared back to false the moment any later session reports support
    /// (e.g. an ARCore update adding the device) — so a cached negative
    /// can never block a supported device. While true, DBHScanScreen
    /// shows its unsupported blocker immediately WITHOUT spinning up a
    /// probe AR session. Developer mode ignores it (the dev capture arms
    /// are depth-free, and a dev-mode session re-probes the verdict).
    val depthUnsupported: Boolean = false,
)

private val Context.settingsStore by preferencesDataStore(name = "forestix_settings")

class AppSettings(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val unitSystem = stringPreferencesKey("tc.unitSystem")
        val mapType = stringPreferencesKey("tc.mapType")
        val tileURLTemplate = stringPreferencesKey("tc.tileURLTemplate")
        val tileProviderLabel = stringPreferencesKey("tc.tileProviderLabel")
        val overlayEnabled = booleanPreferencesKey("tc.overlayEnabled")
        val providerUsageAck = booleanPreferencesKey("tc.providerUsageAcknowledged")
        val country = stringPreferencesKey("tc.country")
        val region = stringPreferencesKey("tc.region")
        val regionPickerSeen = booleanPreferencesKey("tc.regionPickerSeen")
        val logRule = stringPreferencesKey("tc.logRule")
        val dbhMethod = stringPreferencesKey("tc.dbhMeasurementMethod")
        // RETIRED: "tc.dbhCaptureMethod" (depth / AR-motion / AR-caliper
        // picker). The AR arms are gone — they recorded no raw captures —
        // so the DBH scan is depth-only. The key is no longer read, which
        // migrates any stored "motion"/"caliper" value to the depth method.
        val dbhChordAlgorithm = stringPreferencesKey("tc.dbhChordAlgorithm")
        val dbhEdgeAdjustDefault = booleanPreferencesKey("tc.dbhEdgeAdjustDefault")
        val dbhBracketHalfWidth = floatPreferencesKey("tc.dbhBracketHalfWidth")
        val measureHeightAfterDBH = booleanPreferencesKey("tc.measureHeightAfterDiameter")
        val developerMode = booleanPreferencesKey("tc.developerMode")
        val researchTreeId = stringPreferencesKey("tc.researchTreeId")
        val rawCaptureEnabled = booleanPreferencesKey("tc.rawCaptureEnabled")
        val appearance = stringPreferencesKey("tc.appearance")
        // Unified with the iOS sibling's key (was "tc.cruiseProjectId"); the
        // one-time reset of this transient current-project pointer is
        // acceptable. iOS: AppSettings.Keys.currentCruiseProjectID.
        val cruiseProjectId = stringPreferencesKey("tc.currentCruiseProjectID")
        val cruisePlotId = stringPreferencesKey("tc.cruisePlotId")
        val mapMode = stringPreferencesKey("tc.mapMode")
        val depthUnsupported = booleanPreferencesKey("tc.depthUnsupported")
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
            dbhChordAlgorithm = p[Keys.dbhChordAlgorithm] ?: "silhouette",
            dbhEdgeAdjustDefault = p[Keys.dbhEdgeAdjustDefault] ?: true,
            dbhBracketHalfWidth = clampBracketHalfWidth(
                p[Keys.dbhBracketHalfWidth] ?: 0.25f),
            // Anything unrecognised falls back to the satellite default.
            mapType = if (p[Keys.mapType] == "normal") "normal" else "satellite",
            tileURLTemplate = p[Keys.tileURLTemplate]?.takeIf { it.isNotBlank() },
            tileProviderLabel = p[Keys.tileProviderLabel],
            overlayEnabled = p[Keys.overlayEnabled] ?: true,
            providerUsageAcknowledged = p[Keys.providerUsageAck] ?: false,
            country = Country.fromRaw(p[Keys.country]) ?: Country.default,
            region = p[Keys.region],
            regionPickerSeen = p[Keys.regionPickerSeen] ?: false,
            // Defaults ON (F10) — `?: true`, so an install that has never
            // touched the toggle chains diameter → height.
            measureHeightAfterDiameter = p[Keys.measureHeightAfterDBH] ?: true,
            developerMode = p[Keys.developerMode] ?: false,
            researchTreeId = p[Keys.researchTreeId] ?: "",
            rawCaptureEnabled = p[Keys.rawCaptureEnabled] ?: false,
            appearance = p[Keys.appearance] ?: "light",
            cruiseProjectId = p[Keys.cruiseProjectId],
            cruisePlotId = p[Keys.cruisePlotId],
            mapMode = if (p[Keys.mapMode] == "cruise") "cruise" else "measure",
            depthUnsupported = p[Keys.depthUnsupported] ?: false,
        )
    }

    fun setDepthUnsupported(value: Boolean) = update {
        _state.value = _state.value.copy(depthUnsupported = value)
        it[Keys.depthUnsupported] = value
    }

    fun setMapMode(value: String) = update {
        _state.value = _state.value.copy(mapMode = value)
        it[Keys.mapMode] = value
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

    fun setRawCaptureEnabled(value: Boolean) = update {
        _state.value = _state.value.copy(rawCaptureEnabled = value)
        it[Keys.rawCaptureEnabled] = value
    }

    fun setMeasureHeightAfterDiameter(value: Boolean) = update {
        _state.value = _state.value.copy(measureHeightAfterDiameter = value)
        it[Keys.measureHeightAfterDBH] = value
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

    fun setDbhChordAlgorithm(raw: String) = update {
        _state.value = _state.value.copy(dbhChordAlgorithm = raw)
        it[Keys.dbhChordAlgorithm] = raw
    }

    fun setDbhEdgeAdjustDefault(value: Boolean) = update {
        _state.value = _state.value.copy(dbhEdgeAdjustDefault = value)
        it[Keys.dbhEdgeAdjustDefault] = value
    }

    /// Clamped on write AND on read (see the loader): a value from a future
    /// build, or a corrupt preference, must not be able to produce a bracket
    /// wider than the screen or narrower than the fit's minimum gap.
    fun setDbhBracketHalfWidth(value: Float) = update {
        val clamped = clampBracketHalfWidth(value)
        _state.value = _state.value.copy(dbhBracketHalfWidth = clamped)
        it[Keys.dbhBracketHalfWidth] = clamped
    }

    fun setMapType(value: String) = update {
        val raw = if (value == "normal") "normal" else "satellite"
        _state.value = _state.value.copy(mapType = raw)
        it[Keys.mapType] = raw
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


    fun setCountry(value: Country) = update {
        _state.value = _state.value.copy(country = value)
        it[Keys.country] = value.raw
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
