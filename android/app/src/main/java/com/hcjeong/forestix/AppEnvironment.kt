// Composition root — the Android analogue of iOS AppEnvironment. Owns the
// long-lived singletons (settings + measurement history + the timber-cruising
// store and its repositories) and is created once by ForestixApplication,
// then handed to Compose via a CompositionLocal so any screen can read them
// like @EnvironmentObject.

package com.hcjeong.forestix

import android.content.Context
import android.util.Log
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.Room
import com.hcjeong.forestix.data.AppSettings
import com.hcjeong.forestix.data.ForestixDatabase
import com.hcjeong.forestix.data.QuickMeasureHistory
import com.hcjeong.forestix.data.cruise.CruiseDatabase
import com.hcjeong.forestix.data.cruise.CruiseDesignRepository
import com.hcjeong.forestix.data.cruise.HeightDiameterFitRepository
import com.hcjeong.forestix.data.cruise.PlannedPlotRepository
import com.hcjeong.forestix.data.cruise.PlotRepository
import com.hcjeong.forestix.data.cruise.ProjectRepository
import com.hcjeong.forestix.data.cruise.RoomCruiseDesignRepository
import com.hcjeong.forestix.data.cruise.RoomHeightDiameterFitRepository
import com.hcjeong.forestix.data.cruise.RoomPlannedPlotRepository
import com.hcjeong.forestix.data.cruise.RoomPlotRepository
import com.hcjeong.forestix.data.cruise.RoomProjectRepository
import com.hcjeong.forestix.data.cruise.RoomSpeciesConfigRepository
import com.hcjeong.forestix.data.cruise.RoomStratumRepository
import com.hcjeong.forestix.data.cruise.RoomTreeRepository
import com.hcjeong.forestix.data.cruise.RoomVolumeEquationRepository
import com.hcjeong.forestix.data.cruise.SeedDataLoader
import com.hcjeong.forestix.data.cruise.SpeciesConfigRepository
import com.hcjeong.forestix.data.cruise.StratumRepository
import com.hcjeong.forestix.data.cruise.TreeRepository
import com.hcjeong.forestix.data.cruise.VolumeEquationRepository
import com.hcjeong.forestix.sensors.ProjectCalibration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppEnvironment private constructor(
    val settings: AppSettings,
    val history: QuickMeasureHistory,
    // Timber-cruising store (iOS CoreDataStack analogue) + repositories.
    val cruiseDatabase: CruiseDatabase,
    val projectRepository: ProjectRepository,
    val stratumRepository: StratumRepository,
    val cruiseDesignRepository: CruiseDesignRepository,
    val plannedPlotRepository: PlannedPlotRepository,
    val plotRepository: PlotRepository,
    val treeRepository: TreeRepository,
    val speciesConfigRepository: SpeciesConfigRepository,
    val volumeEquationRepository: VolumeEquationRepository,
    val heightDiameterFitRepository: HeightDiameterFitRepository,
) {
    /// Calibration the shared quick-measure scan screens apply. iOS injects
    /// the project's ProjectCalibration into the scan view models when they
    /// are launched from the cruise add-tree chain; Android's DBH/Height scan screens are shared
    /// nav routes, so the flow publishes the active project's calibration
    /// here on entry and restores identity when it leaves. Outside the flow
    /// this stays identity — matching the iOS quick-measure screens, which
    /// always pass `.identity`.
    val activeScanCalibration = MutableStateFlow(ProjectCalibration.identity)

    companion object {
        private const val TAG = "AppEnvironment"

        @Volatile private var INSTANCE: AppEnvironment? = null
        private val createLock = Mutex()

        /// Process-wide singleton, memoized like QuickMeasureHistory.get —
        /// MainActivity's produceState re-runs create() on every Activity
        /// recreation, and both Room databases must be built exactly once
        /// per process (iOS AppEnvironment.live() runs once, guarded in
        /// ContentView's .task).
        suspend fun create(context: Context): AppEnvironment {
            INSTANCE?.let { return it }
            return createLock.withLock {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }
        }

        private suspend fun build(app: Context): AppEnvironment {
            // Bind the local-only event logger to the app context once, so its
            // emit sites (plot open, backup create/restore, crash-recovery
            // prompt) stay Context-free like the iOS static logger.
            com.hcjeong.forestix.common.ForestixLogger.init(app)

            val db = Room.databaseBuilder(app, ForestixDatabase::class.java, "forestix.db")
                .addMigrations(
                    com.hcjeong.forestix.data.QUICK_MEASURE_MIGRATION_1_2,
                    com.hcjeong.forestix.data.QUICK_MEASURE_MIGRATION_2_3,
                    com.hcjeong.forestix.data.QUICK_MEASURE_MIGRATION_3_4,
                    com.hcjeong.forestix.data.QUICK_MEASURE_MIGRATION_4_5,
                )
                .build()
            val history = QuickMeasureHistory.get(app, db.dao())

            val cruiseDb = Room.databaseBuilder(
                app, CruiseDatabase::class.java, CruiseDatabase.NAME)
                .addMigrations(
                    CruiseDatabase.MIGRATION_1_2,
                    CruiseDatabase.MIGRATION_2_3,
                    CruiseDatabase.MIGRATION_3_4,
                    CruiseDatabase.MIGRATION_4_5,
                )
                .build()
            val speciesConfigRepository = RoomSpeciesConfigRepository(cruiseDb.speciesConfigDao())
            val volumeEquationRepository = RoomVolumeEquationRepository(cruiseDb.volumeEquationDao())

            // First-launch seeding of the PNW species / volume-equation set
            // (idempotent; iOS calls SeedDataLoader.bootstrapIfNeeded on
            // every launch). A seed failure must not block app startup.
            try {
                SeedDataLoader.bootstrapIfNeeded(
                    app, speciesConfigRepository, volumeEquationRepository)
            } catch (e: Exception) {
                Log.w(TAG, "Seed data bootstrap failed", e)
            }

            val settings = AppSettings(app)
            // Depth-capability cache (both process singletons, so wiring
            // here is leak-free): persist the AR session's definitive
            // Depth-API verdict. Only a NEGATIVE gates anything —
            // DBHScanScreen shows its unsupported blocker without probing
            // — and any later positive report clears a stale negative, so
            // the cache can never block a supported device.
            com.hcjeong.forestix.ar.ArSessionHub.onDepthVerdict = { supported ->
                val unsupported = !supported
                if (settings.state.value.depthUnsupported != unsupported) {
                    settings.setDepthUnsupported(unsupported)
                }
            }

            return AppEnvironment(
                settings = settings,
                history = history,
                cruiseDatabase = cruiseDb,
                projectRepository = RoomProjectRepository(cruiseDb.projectDao()),
                stratumRepository = RoomStratumRepository(cruiseDb.stratumDao()),
                cruiseDesignRepository = RoomCruiseDesignRepository(cruiseDb.cruiseDesignDao()),
                plannedPlotRepository = RoomPlannedPlotRepository(cruiseDb.plannedPlotDao()),
                plotRepository = RoomPlotRepository(cruiseDb.plotDao()),
                treeRepository = RoomTreeRepository(cruiseDb.treeDao()),
                speciesConfigRepository = speciesConfigRepository,
                volumeEquationRepository = volumeEquationRepository,
                heightDiameterFitRepository = RoomHeightDiameterFitRepository(
                    cruiseDb.heightDiameterFitDao()),
            )
        }
    }
}

val LocalAppEnvironment = staticCompositionLocalOf<AppEnvironment> {
    error("AppEnvironment not provided")
}
