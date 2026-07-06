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
    companion object {
        private const val TAG = "AppEnvironment"

        suspend fun create(context: Context): AppEnvironment {
            val app = context.applicationContext
            val db = Room.databaseBuilder(app, ForestixDatabase::class.java, "forestix.db").build()
            val history = QuickMeasureHistory.get(app, db.dao())

            val cruiseDb = Room.databaseBuilder(
                app, CruiseDatabase::class.java, CruiseDatabase.NAME).build()
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

            return AppEnvironment(
                settings = AppSettings(app),
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
