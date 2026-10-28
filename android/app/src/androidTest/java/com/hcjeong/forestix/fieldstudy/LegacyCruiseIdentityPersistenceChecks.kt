package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.hcjeong.forestix.data.cruise.*
import com.hcjeong.forestix.sensors.*
import java.io.File
import java.lang.reflect.Modifier
import java.util.UUID

/** Genuine production v9 DDL, including all nine tables and six indices.
 * Creates only an owned synthetic SQLite fixture, never the live database. */
internal object LegacyCruiseIdentityPersistenceChecks {
    private val legacyDDL=listOf(
        "CREATE TABLE IF NOT EXISTS `ProjectEntity` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `projectDescription` TEXT NOT NULL, `owner` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `units` TEXT NOT NULL, `breastHeightConvention` TEXT NOT NULL, `slopeCorrection` INTEGER NOT NULL, `lidarBiasMm` REAL NOT NULL, `depthNoiseMm` REAL NOT NULL, `dbhCorrectionAlpha` REAL NOT NULL, `dbhCorrectionBeta` REAL NOT NULL, `dbhCalibrationEpoch` INTEGER NOT NULL, `vioDriftFraction` REAL NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `StratumEntity` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `name` TEXT NOT NULL, `areaAcres` REAL NOT NULL, `polygonGeoJSON` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_StratumEntity_projectId` ON `StratumEntity` (`projectId`)",
        "CREATE TABLE IF NOT EXISTS `CruiseDesignEntity` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `plotType` TEXT NOT NULL, `plotAreaAcres` REAL, `baf` REAL, `samplingScheme` TEXT NOT NULL, `gridSpacingMeters` REAL, `heightSubsampleRuleJSON` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_CruiseDesignEntity_projectId` ON `CruiseDesignEntity` (`projectId`)",
        "CREATE TABLE IF NOT EXISTS `PlannedPlotEntity` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `stratumId` TEXT, `plotNumber` INTEGER NOT NULL, `plannedLat` REAL NOT NULL, `plannedLon` REAL NOT NULL, `visited` INTEGER NOT NULL, `skipped` INTEGER NOT NULL, `plannedSource` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_PlannedPlotEntity_projectId` ON `PlannedPlotEntity` (`projectId`)",
        "CREATE TABLE IF NOT EXISTS `PlotEntity` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `plannedPlotId` TEXT, `plotNumber` INTEGER NOT NULL, `centerLat` REAL NOT NULL, `centerLon` REAL NOT NULL, `positionSource` TEXT NOT NULL, `positionTier` TEXT NOT NULL, `gpsNSamples` INTEGER NOT NULL, `gpsMedianHAccuracyM` REAL NOT NULL, `gpsSampleStdXyM` REAL NOT NULL, `offsetWalkM` REAL, `slopeDeg` REAL NOT NULL, `aspectDeg` REAL NOT NULL, `groundElevationM` REAL, `canopyCoverPct` REAL, `plotAreaAcres` REAL NOT NULL, `startedAt` INTEGER NOT NULL, `closedAt` INTEGER, `closedBy` TEXT, `notes` TEXT NOT NULL, `coverPhotoPath` TEXT, `panoramaPath` TEXT, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_PlotEntity_projectId` ON `PlotEntity` (`projectId`)",
        "CREATE TABLE IF NOT EXISTS `TreeEntity` (`id` TEXT NOT NULL, `plotId` TEXT NOT NULL, `treeNumber` INTEGER NOT NULL, `treeName` TEXT, `speciesCode` TEXT NOT NULL, `status` TEXT NOT NULL, `dbhCm` REAL NOT NULL, `dbhMethod` TEXT NOT NULL, `dbhSigmaMm` REAL, `dbhRmseMm` REAL, `dbhCoverageDeg` REAL, `dbhNInliers` INTEGER, `dbhConfidence` TEXT NOT NULL, `dbhCaptureMode` TEXT, `dbhEstimatorEpoch` INTEGER, `dbhIsIrregular` INTEGER NOT NULL, `heightM` REAL, `heightMethod` TEXT, `heightSource` TEXT, `heightSigmaM` REAL, `heightDHM` REAL, `heightAlphaTopDeg` REAL, `heightAlphaBaseDeg` REAL, `heightConfidence` TEXT, `bearingFromCenterDeg` REAL, `distanceFromCenterM` REAL, `boundaryCall` TEXT, `crownClass` TEXT, `damageCodesJSON` TEXT NOT NULL, `isMultistem` INTEGER NOT NULL, `parentTreeId` TEXT, `notes` TEXT NOT NULL, `photoPath` TEXT, `rawScanPath` TEXT, `latitude` REAL, `longitude` REAL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_TreeEntity_plotId` ON `TreeEntity` (`plotId`)",
        "CREATE TABLE IF NOT EXISTS `SpeciesConfigEntity` (`code` TEXT NOT NULL, `commonName` TEXT NOT NULL, `scientificName` TEXT NOT NULL, `volumeEquationId` TEXT NOT NULL, `merchTopDibCm` REAL NOT NULL, `stumpHeightCm` REAL NOT NULL, `expectedDbhMinCm` REAL NOT NULL, `expectedDbhMaxCm` REAL NOT NULL, `expectedHeightMinM` REAL NOT NULL, `expectedHeightMaxM` REAL NOT NULL, PRIMARY KEY(`code`))",
        "CREATE TABLE IF NOT EXISTS `VolumeEquationEntity` (`id` TEXT NOT NULL, `form` TEXT NOT NULL, `coefficientsJSON` TEXT NOT NULL, `unitsIn` TEXT NOT NULL, `unitsOut` TEXT NOT NULL, `sourceCitation` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `HeightDiameterFitEntity` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `speciesCode` TEXT NOT NULL, `modelForm` TEXT NOT NULL, `coefficientsJSON` TEXT NOT NULL, `nObs` INTEGER NOT NULL, `rmse` REAL NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_HeightDiameterFitEntity_projectId` ON `HeightDiameterFitEntity` (`projectId`)"
    )
    suspend fun run(base:Context,parent:File){
        val root=File(parent,"cruise-identity-migration");check(root.mkdir())
        val file=File(root,"v9.sqlite")
        val legacy=Tree(id=UUID.randomUUID(),plotId=UUID.randomUUID(),treeNumber=7,treeName="0007",speciesCode="DF",status=TreeStatus.DEAD_STANDING,
            dbhCm=25.125f,dbhMethod=DBHMethod.MANUAL_CALIPER,dbhSigmaMm=.75f,dbhRmseMm=2f,dbhCoverageDeg=90f,dbhNInliers=60,dbhConfidence=ConfidenceTier.YELLOW,dbhIsIrregular=true,
            dbhCaptureMode="manual",dbhEstimatorEpoch=7,heightM=21f,heightMethod=HeightMethod.MANUAL_ENTRY,heightSource="measured",heightSigmaM=.2f,heightDHM=13.625f,
            heightAlphaTopDeg=50f,heightAlphaBaseDeg=-5f,heightConfidence=ConfidenceTier.GREEN,bearingFromCenterDeg=25f,distanceFromCenterM=4f,boundaryCall="in",crownClass="dominant",
            damageCodes=listOf("x,y","line\n🌲"),isMultistem=true,parentTreeId=UUID.randomUUID(),notes="synthetic,\n\"original\"",photoPath="old.jpg",rawScanPath="old.raw",
            latitude=45.123456789,longitude=-123.123456789,createdAt=1700000000123,updatedAt=1700000000456,deletedAt=null)
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            legacyDDL.forEach{db.execSQL(it)}
            val columns=db.rawQuery("PRAGMA table_info(TreeEntity)",null).use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getString(1))}}
            check("dbhMeasurementId" !in columns&&"heightMeasurementId" !in columns)
            val row=TreeMapper.apply(legacy);val values=ContentValues()
            // Reflection is confined to this synthetic fixture. It populates
            // every original schema column from the production native mapper.
            for(field in TreeEntity::class.java.declaredFields.filter{!Modifier.isStatic(it.modifiers)&&it.name in columns}){
                field.isAccessible=true;val value=field.get(row);val key=field.name
                when(value){
                    null->values.putNull(key);is UUID->values.put(key,value.toString());is String->values.put(key,value)
                    is Float->values.put(key,value);is Double->values.put(key,value);is Int->values.put(key,value);is Long->values.put(key,value)
                    is Boolean->values.put(key,value);else->error("Unexpected native fixture value")
                }
            }
            check(values.keySet()==columns);check(db.insertOrThrow("TreeEntity",null,values)>0);db.version=9
        }
        fun open()=Room.databaseBuilder(base,CruiseDatabase::class.java,file.absolutePath).addMigrations(CruiseDatabase.MIGRATION_9_10).build()
        var db=open()
        try{
            val repository=RoomTreeRepository(db.treeDao())
            check(repository.readCurrent(legacy.id,true)==legacy){"Cruise migration changed original fields or backfilled identity"}
            val dbh=legacy.copy(dbhCm=26f,dbhMeasurementId=UUID.randomUUID().toString())
            val first=repository.replaceIfUnchanged(dbh,legacy);check(first==dbh)
            val height=first.copy(heightM=23f,heightMeasurementId=UUID.randomUUID().toString())
            val saved=repository.replaceIfUnchanged(height,first);check(saved==height)
            db.close();db=open()
            val reopened=RoomTreeRepository(db.treeDao());check(reopened.readCurrent(legacy.id,true)==saved)
            val edited=reopened.update(saved.copy(notes="metadata edit"));check(edited.dbhMeasurementId==saved.dbhMeasurementId&&edited.heightMeasurementId==saved.heightMeasurementId)
            val unattributed=reopened.update(edited.copy(dbhCm=27f))
            check(unattributed.dbhMeasurementId==null&&unattributed.heightMeasurementId==saved.heightMeasurementId)
            val newCapture=unattributed.copy(dbhCm=28f,dbhMeasurementId=UUID.randomUUID().toString())
            reopened.replaceIfUnchanged(newCapture,unattributed)
            check(reopened.replaceIfUnchanged(saved,newCapture)==saved){"Undo did not restore exact per-kind identity"}
        }finally{db.close()}
    }
}
