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
    version = 3,
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
    }
}
