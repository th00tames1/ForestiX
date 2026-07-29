// Room persistence backing QuickMeasureHistory. The iOS app uses a
// UserDefaults cache + append-only JSONL sidecar; Room gives us the same
// durability guarantee (survives process death, queryable) with less
// hand-rolled file plumbing. Entries are stored newest-first at the query
// layer via `ORDER BY createdAt DESC`.

package com.hcjeong.forestix.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "entries")
data class EntryRow(
    @PrimaryKey val id: String,
    val kind: String,
    val value: Double,
    val secondaryValue: Double?,
    val sigma: Double?,
    val confidenceRaw: String,
    val method: String,
    val createdAt: Long,
    val treeNumber: Int?,
    val treeName: String?,
    val plotID: String?,
    val speciesCode: String?,
    val position: String?,
    val damageCodes: String,   // pipe-joined
    val note: String?,
    val latitude: Double?,
    val longitude: Double?,
    val photoPath: String?,
    val captureMode: String?,
    val truth: Double?,
) {
    fun toDomain() = QuickMeasureEntry(
        id = UUID.fromString(id),
        kind = MeasureKind.fromRaw(kind),
        value = value,
        secondaryValue = secondaryValue,
        sigma = sigma,
        confidenceRaw = confidenceRaw,
        method = method,
        createdAt = createdAt,
        treeNumber = treeNumber,
        treeName = treeName,
        plotID = plotID?.let { UUID.fromString(it) },
        speciesCode = speciesCode,
        position = StemPosition.fromRaw(position),
        damageCodes = if (damageCodes.isEmpty()) emptyList() else damageCodes.split("|"),
        note = note,
        latitude = latitude,
        longitude = longitude,
        photoPath = photoPath,
        captureMode = captureMode,
        truth = truth,
    )

    companion object {
        fun from(e: QuickMeasureEntry) = EntryRow(
            id = e.id.toString(),
            kind = e.kind.raw,
            value = e.value,
            secondaryValue = e.secondaryValue,
            sigma = e.sigma,
            confidenceRaw = e.confidenceRaw,
            method = e.method,
            createdAt = e.createdAt,
            treeNumber = e.treeNumber,
            treeName = e.treeName,
            plotID = e.plotID?.toString(),
            speciesCode = e.speciesCode,
            position = e.position?.raw,
            damageCodes = e.damageCodes.joinToString("|"),
            note = e.note,
            latitude = e.latitude,
            longitude = e.longitude,
            photoPath = e.photoPath,
            captureMode = e.captureMode,
            truth = e.truth,
        )
    }
}

@Entity(tableName = "plots")
data class PlotRow(
    @PrimaryKey val id: String,
    val name: String,
    val unitName: String,
    val acres: Double?,
    val typeRaw: String,
    val baf: Double?,
    val radiusFt: Double?,
    val parentPlotID: String?,
    val nestedKind: String?,
    val createdAt: Long,
    val isDefault: Boolean,
) {
    fun toDomain() = QuickMeasurePlot(
        id = UUID.fromString(id),
        name = name,
        unitName = unitName,
        acres = acres,
        typeRaw = typeRaw,
        baf = baf,
        radiusFt = radiusFt,
        parentPlotID = parentPlotID?.let { UUID.fromString(it) },
        nestedKind = nestedKind,
        createdAt = createdAt,
        isDefault = isDefault,
    )

    companion object {
        fun from(p: QuickMeasurePlot) = PlotRow(
            id = p.id.toString(),
            name = p.name,
            unitName = p.unitName,
            acres = p.acres,
            typeRaw = p.typeRaw,
            baf = p.baf,
            radiusFt = p.radiusFt,
            parentPlotID = p.parentPlotID?.toString(),
            nestedKind = p.nestedKind,
            createdAt = p.createdAt,
            isDefault = p.isDefault,
        )
    }
}

@Dao
interface QuickMeasureDao {
    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    fun observeEntries(): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries ORDER BY createdAt DESC")
    suspend fun allEntries(): List<EntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntry(row: EntryRow)

    @Query("DELETE FROM entries WHERE id = :id")
    suspend fun deleteEntry(id: String)

    @Query("DELETE FROM entries")
    suspend fun clearEntries()

    @Query("UPDATE entries SET plotID = :newPlot WHERE plotID = :oldPlot")
    suspend fun rehomeEntries(oldPlot: String, newPlot: String?)

    @Query("SELECT * FROM plots ORDER BY createdAt DESC")
    fun observePlots(): Flow<List<PlotRow>>

    @Query("SELECT * FROM plots")
    suspend fun allPlots(): List<PlotRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlot(row: PlotRow)

    @Query("DELETE FROM plots WHERE id = :id")
    suspend fun deletePlot(id: String)
}

class Converters {
    @TypeConverter fun boolToInt(b: Boolean) = if (b) 1 else 0
    @TypeConverter fun intToBool(i: Int) = i != 0
}

/// v2: capture context columns (latitude/longitude/photoPath) for the
/// map-home feature — additive, migrated in place so field data survives.
val QUICK_MEASURE_MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN latitude REAL")
        db.execSQL("ALTER TABLE entries ADD COLUMN longitude REAL")
        db.execSQL("ALTER TABLE entries ADD COLUMN photoPath TEXT")
    }
}

/// v3: DBH capture-mode tag ("auto" / "manual" edge-bracket) — additive;
/// null for older rows and for non-DBH kinds.
val QUICK_MEASURE_MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN captureMode TEXT")
    }
}

/// v4: the cruiser's name for the tree ("Plot3-T07") — additive and
/// nullable; a null reads as the "#<treeNumber>" the log always showed, so
/// nothing recorded before naming existed changes appearance.
val QUICK_MEASURE_MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN treeName TEXT")
    }
}

/// v5: the hand-measured ground truth typed against a reading — additive
/// and nullable, so every row written before the field existed reads back
/// as "no truth entered" rather than as a measured zero.
val QUICK_MEASURE_MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE entries ADD COLUMN truth REAL")
    }
}

@Database(entities = [EntryRow::class, PlotRow::class], version = 5, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ForestixDatabase : RoomDatabase() {
    abstract fun dao(): QuickMeasureDao
}
