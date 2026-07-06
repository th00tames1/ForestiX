// Port of iOS ViewModels/PreFieldChecklistViewModel.swift.
// Phase 7 — pre-field readiness check (spec §9.2 Phase 7):
//   1. AR self-test (iOS: LiDAR; Android: ARCore availability)
//   2. GPS fix achievable at Tier A (yard-tested separately — always warn)
//   3. Calibration data present on the project (depth noise, DBH α/β)
//   4. Offline basemap downloaded (tiles present at expected path)
//   5. Species list + volume equations exist
//   6. Storage free > 500 MB
//   7. Battery > 50 %
//
// Each check produces a `ChecklistItem` (.PASS / .WARN / .FAIL + user-facing
// explanation). The view model aggregates them and flips "ready" green only
// when every check passes.

package com.hcjeong.forestix.ui.screens.project

import android.content.Context
import android.os.BatteryManager
import android.os.StatFs
import com.google.ar.core.ArCoreApk
import com.hcjeong.forestix.AppEnvironment
import com.hcjeong.forestix.data.cruise.Project
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ChecklistItem(
    val id: String,
    val title: String,
    val severity: Severity,
    val message: String,
) {
    enum class Severity { PASS, WARN, FAIL }
}

class PreFieldChecklistViewModel(val project: Project) {

    private val _items = MutableStateFlow<List<ChecklistItem>>(emptyList())
    val items: StateFlow<List<ChecklistItem>> = _items.asStateFlow()

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    val errorMessage = MutableStateFlow<String?>(null)

    private var env: AppEnvironment? = null

    fun configure(environment: AppEnvironment) {
        this.env = environment
    }

    suspend fun runAll(context: Context) {
        val env = env ?: return
        val out = mutableListOf<ChecklistItem>()

        // 1. AR self-test. iOS checks DeviceCapabilities.hasLiDAR; the
        // Android analogue is ARCore availability on this device.
        val availability = try {
            ArCoreApk.getInstance().checkAvailability(context)
        } catch (_: Exception) {
            null
        }
        if (availability != null && availability.isSupported) {
            out.add(ChecklistItem(id = "lidar", title = "Depth + AR",
                severity = ChecklistItem.Severity.PASS,
                message = "ARCore supported on this device. AR session will run."))
        } else {
            out.add(ChecklistItem(id = "lidar", title = "Depth + AR",
                severity = ChecklistItem.Severity.WARN,
                message = "No ARCore support detected. Forestix will fall back to manual-only DBH and tape-tangent height. Bring a caliper and a laser rangefinder."))
        }

        // 2. GPS — we can't actually wait for a fix here (that requires a
        // live location session), so field pilots yard-test this separately.
        out.add(ChecklistItem(id = "gps", title = "GPS check",
            severity = ChecklistItem.Severity.WARN,
            message = "Confirm a Tier A fix in the yard before driving to the stand. Use the Plot Centre screen with the phone flat in open sky."))

        // 3. Calibration values on the project.
        if (project.depthNoiseMm > 0 && project.dbhCorrectionBeta > 0) {
            out.add(ChecklistItem(id = "calibration", title = "Calibration",
                severity = ChecklistItem.Severity.PASS,
                message = "Depth noise and DBH correction parameters are set."))
        } else {
            out.add(ChecklistItem(id = "calibration", title = "Calibration",
                severity = ChecklistItem.Severity.FAIL,
                message = "Run the wall + cylinder calibration in Settings → Calibration before you leave cell service. Without it DBH confidence will report red tier."))
        }

        // 4. Offline basemap — tiles live under filesDir/basemap-tiles
        // (see TileFetcher). Simple heuristic: folder exists and contains
        // at least one entry.
        val hasBasemap = checkOfflineBasemap(context)
        out.add(
            if (hasBasemap) {
                ChecklistItem(id = "basemap", title = "Offline basemap",
                    severity = ChecklistItem.Severity.PASS,
                    message = "Tiles for the project extent are cached.")
            } else {
                ChecklistItem(id = "basemap", title = "Offline basemap",
                    severity = ChecklistItem.Severity.WARN,
                    message = "No tiles cached. The map view will fall back to the bare tile URL, which requires a network connection.")
            }
        )

        // 5. Species + volume equations.
        try {
            val species = env.speciesConfigRepository.list()
            val equations = env.volumeEquationRepository.list()
            val missingEq = species.filter { sp ->
                equations.none { it.id == sp.volumeEquationId }
            }
            when {
                species.isEmpty() -> out.add(ChecklistItem(id = "species", title = "Species list",
                    severity = ChecklistItem.Severity.FAIL,
                    message = "No species configured. Add species in Settings → Species before field work."))
                missingEq.isNotEmpty() -> out.add(ChecklistItem(id = "species", title = "Species list",
                    severity = ChecklistItem.Severity.FAIL,
                    message = "Missing volume equations for: ${missingEq.joinToString(", ") { it.code }}. Plot volume numbers will be 0 for those species."))
                else -> out.add(ChecklistItem(id = "species", title = "Species list",
                    severity = ChecklistItem.Severity.PASS,
                    message = "${species.size} species configured, each linked to a volume equation."))
            }
        } catch (e: Exception) {
            out.add(ChecklistItem(id = "species", title = "Species list",
                severity = ChecklistItem.Severity.FAIL,
                message = "Database read failed: ${e.message ?: e}. Restart the app and try again."))
        }

        // 6. Storage.
        val bytes = availableBytes(context)
        val mb = bytes / 1_048_576L
        if (mb >= 500) {
            out.add(ChecklistItem(id = "storage", title = "Storage",
                severity = ChecklistItem.Severity.PASS,
                message = "$mb MB free — enough for a full-day cruise."))
        } else {
            out.add(ChecklistItem(id = "storage", title = "Storage",
                severity = ChecklistItem.Severity.FAIL,
                message = "Only $mb MB free (need ≥ 500 MB). Offload photos / scans or delete old exports, then re-run the check."))
        }

        // 7. Battery.
        val battery = batteryState(context)
        val pct = (battery.level * 100).toInt()
        if (battery.level >= 0.5f || battery.isCharging) {
            out.add(ChecklistItem(id = "battery", title = "Battery",
                severity = ChecklistItem.Severity.PASS,
                message = "$pct % — ready for a full day."))
        } else {
            out.add(ChecklistItem(id = "battery", title = "Battery",
                severity = ChecklistItem.Severity.WARN,
                message = "Battery at $pct %. Charge before driving to the stand; bring a backup power bank."))
        }

        _items.value = out
        _isReady.value = out.all { it.severity == ChecklistItem.Severity.PASS }
    }

    // MARK: - Helpers

    private fun checkOfflineBasemap(context: Context): Boolean {
        val dir = File(context.filesDir, "basemap-tiles")
        val contents = dir.listFiles() ?: return false
        return contents.isNotEmpty()
    }

    /// iOS StorageProbe.availableBytes() analogue.
    private fun availableBytes(context: Context): Long = try {
        StatFs(context.filesDir.absolutePath).availableBytes
    } catch (_: Exception) {
        0L
    }

    /// iOS BatteryState.current() analogue.
    private data class BatteryState(val level: Float, val isCharging: Boolean)

    private fun batteryState(context: Context): BatteryState {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return BatteryState(level = 1f, isCharging = false)
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val level = if (capacity in 0..100) capacity / 100f else 1f
        return BatteryState(level = level, isCharging = bm.isCharging)
    }
}
