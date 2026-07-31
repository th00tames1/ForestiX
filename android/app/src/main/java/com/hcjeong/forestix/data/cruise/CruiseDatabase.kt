// Cruise persistence store — port of iOS Persistence/CoreDataStack.swift
// (spec §8 + §11 NFR "WAL on Core Data; save after every Tree save").
//
// Room opens SQLite in WAL journal mode by default on modern Android, giving
// the same durability posture as the iOS stack's explicit WAL pragma. Every
// repository write is a suspend DAO call that commits before returning, so
// "save after every Tree save" holds by construction.
//
// Deliberately SEPARATE from ForestixDatabase (data/QuickMeasureDb.kt): the
// quick-measure history and the timber-cruising project store evolve on
// independent schemas, exactly as the iOS app keeps UserDefaults history
// apart from the Core Data store.

package com.hcjeong.forestix.data.cruise

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/// Port of iOS `CoreDataError` (CoreDataStack.swift). The model-loading cases
/// have no Room analogue; the two that survive are the ones repositories and
/// mappers actually throw.
sealed class CruiseDataError(override val message: String) : Exception(message) {
    /// A stored raw value no longer maps onto a model enum.
    class MappingFailed(detail: String) : CruiseDataError("Mapping failed: $detail")

    /// update/delete addressed a row that does not exist.
    class NotFound(id: String) : CruiseDataError("Record not found: $id")

    /// A write was refused because the record carries a value the model says
    /// cannot exist — a canopy cover of 130 %, an aspect of 400°. The message
    /// is the sentence the cruiser reads, built by the model, so the refusal
    /// says the same thing wherever it surfaces.
    class InvalidValue(detail: String) : CruiseDataError(detail)
}

@Database(
    entities = [
        ProjectEntity::class,
        StratumEntity::class,
        CruiseDesignEntity::class,
        PlannedPlotEntity::class,
        PlotEntity::class,
        TreeEntity::class,
        SpeciesConfigEntity::class,
        VolumeEquationEntity::class,
        HeightDiameterFitEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
@TypeConverters(CruiseConverters::class)
abstract class CruiseDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun stratumDao(): StratumDao
    abstract fun cruiseDesignDao(): CruiseDesignDao
    abstract fun plannedPlotDao(): PlannedPlotDao
    abstract fun plotDao(): PlotDao
    abstract fun treeDao(): TreeDao
    abstract fun speciesConfigDao(): SpeciesConfigDao
    abstract fun volumeEquationDao(): VolumeEquationDao
    abstract fun heightDiameterFitDao(): HeightDiameterFitDao

    companion object {
        /// Database filename — the Room analogue of iOS
        /// `CoreDataStack.defaultStoreURL()`'s "TimberCruising.sqlite".
        const val NAME = "timber-cruising.db"

        /// v1 → v2: cruise-mode map pins (v3 redesign) — trees carry the
        /// GPS fix captured at Accept. Nullable REALs, no backfill.
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE TreeEntity ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE TreeEntity ADD COLUMN longitude REAL")
            }
        }

        /// v2 → v3: PlannedPlot gains `skipped` (inaccessible plots the
        /// cruiser can't reach). NOT NULL DEFAULT 0 — existing rows are
        /// pending, not skipped.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlannedPlotEntity ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0")
            }
        }

        /// v3 → v4: Tree gains `treeName`, the cruiser's own label for the
        /// tree. Nullable TEXT, no backfill — an unnamed tree reads as
        /// "#<treeNumber>" exactly as before.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE TreeEntity ADD COLUMN treeName TEXT")
            }
        }

        /// v4 → v5: Tree gains `dbhCaptureMode` — which estimator found the
        /// diameter's edges ("auto", "manual", "typed"). Nullable TEXT, no
        /// backfill: rows written before the column existed genuinely have
        /// no provenance, and guessing one would corrupt the algorithm
        /// comparison this field is recorded for.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE TreeEntity ADD COLUMN dbhCaptureMode TEXT")
            }
        }

        /// v5 → v6: PlannedPlot gains `plannedSource` — how the planned
        /// coordinate was placed ("manual" when the cruiser drew it on the
        /// map). Nullable TEXT, no backfill: every row that exists at this
        /// point was laid by the sampling generator, and null is what "not
        /// hand-placed" is spelled as. Backfilling "manual" would claim a
        /// finger placed a grid the design computed.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlannedPlotEntity ADD COLUMN plannedSource TEXT")
            }
        }

        /// v6 → v7: Project gains `dbhCalibrationEpoch` — which diameter
        /// estimator its cylinder calibration was fitted against.
        ///
        /// DEFAULT 0, NOT the current epoch, and the difference matters. A
        /// project migrating in was calibrated under an older estimator, and
        /// backfilling the current epoch would assert the opposite: that its
        /// alpha/beta still apply. They do not — the fitted correction and the
        /// new geometry's own correction would stack and the project would
        /// under-read silently. 0 reads as "not fitted against this
        /// estimator", which is exactly true, and the calibration screen asks
        /// for a re-scan. A project that never calibrated is unaffected
        /// either way: the identity correction is valid at every epoch.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ProjectEntity ADD COLUMN dbhCalibrationEpoch INTEGER NOT NULL DEFAULT 0")
            }
        }

        /// v7 → v8: Plot gains the rest of its site description —
        /// `groundElevationM` (metres, the app's canonical length) and
        /// `canopyCoverPct` (0–100 %).
        ///
        /// NULLABLE, no backfill and no DEFAULT 0. Nobody stood on these plots
        /// with an altimeter, so their elevation is unknown, not sea level;
        /// their canopy is unknown, not a clearcut. A DEFAULT 0 would make
        /// every plot recorded before today claim two readings it never had,
        /// and no later screen could tell those apart from a coastal plot in
        /// the open.
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE PlotEntity ADD COLUMN groundElevationM REAL")
                db.execSQL("ALTER TABLE PlotEntity ADD COLUMN canopyCoverPct REAL")
            }
        }
    }
}
