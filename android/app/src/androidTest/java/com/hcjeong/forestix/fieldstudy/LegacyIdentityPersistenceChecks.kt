package com.hcjeong.forestix.fieldstudy

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.hcjeong.forestix.data.*
import java.io.File
import java.util.UUID

/** A genuine v9 SQLite fixture, migrated by production Room migration.
 * All databases/logs live in the native runner's owned UUID cache directory. */
internal object LegacyIdentityPersistenceChecks {
    suspend fun run(base:Context,parent:File){
        val root=File(parent,"identity-migration");check(root.mkdir())
        val context=object:ContextWrapper(base){override fun getFilesDir()=root;override fun getApplicationContext():Context=this}
        val file=File(root,"v9.sqlite");val plot=UUID.randomUUID()
        val legacy=QuickMeasureEntry(kind=MeasureKind.DBH,value=25.125,sigma=.75,confidenceRaw="yellow",method="silhouette",
            createdAt=1700000000123,treeNumber=7,treeName="0007",plotID=plot,speciesCode="DF",position=StemPosition.DBH,
            damageCodes=listOf("x","y"),note="synthetic,\n🌲",latitude=45.123456789,longitude=-123.123456789,
            photoPath="synthetic-${UUID.randomUUID()}.jpg",captureMode="manual",truth=25.0,truthSource="capture",truthUnit=null,
            positionSource="manual",timeSource="typed")
        val expected=EntryRow.from(legacy)
        SQLiteDatabase.openOrCreateDatabase(file,null).use{db->
            db.execSQL("CREATE TABLE entries (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, value REAL NOT NULL, secondaryValue REAL, sigma REAL, confidenceRaw TEXT NOT NULL, method TEXT NOT NULL, createdAt INTEGER NOT NULL, treeNumber INTEGER, treeName TEXT, plotID TEXT, speciesCode TEXT, position TEXT, damageCodes TEXT NOT NULL, note TEXT, latitude REAL, longitude REAL, photoPath TEXT, captureMode TEXT, truth REAL, truthSource TEXT, truthUnit TEXT, positionSource TEXT, timeSource TEXT)")
            db.execSQL("CREATE TABLE plots (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, unitName TEXT NOT NULL, acres REAL, typeRaw TEXT NOT NULL, baf REAL, radiusFt REAL, parentPlotID TEXT, nestedKind TEXT, createdAt INTEGER NOT NULL, isDefault INTEGER NOT NULL)")
            db.execSQL("INSERT INTO plots VALUES (?,?,?,?,?,?,?,?,?,?,?)",arrayOf(plot.toString(),"SYNTHETIC","",null,"fixed",null,null,null,null,1700000000000,1))
            db.execSQL("INSERT INTO entries VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",arrayOf(expected.id,expected.kind,expected.value,
                expected.secondaryValue,expected.sigma,expected.confidenceRaw,expected.method,expected.createdAt,expected.treeNumber,expected.treeName,
                expected.plotID,expected.speciesCode,expected.position,expected.damageCodes,expected.note,expected.latitude,expected.longitude,
                expected.photoPath,expected.captureMode,expected.truth,expected.truthSource,expected.truthUnit,expected.positionSource,expected.timeSource))
            db.version=9
        }
        fun open()=Room.databaseBuilder(base,ForestixDatabase::class.java,file.absolutePath).addMigrations(QUICK_MEASURE_MIGRATION_9_10).build()
        var db=open()
        try{
            check(db.dao().allEntries().single()==expected){"v9 migration changed original columns"}
            val history=QuickMeasureHistory.get(context,db.dao())
            val next=legacy.copy(id=UUID.randomUUID(),treeNumber=8,truth=null,truthSource=null)
            history.saveCapturedReading(next)
            check(history.backfillTruths(mapOf(next.id to 30.0))==0){"Legacy backfill reached an identified result"}
            val identified=next.identifyingMeasurement().settingTruth(25.0,TruthSource.CAPTURE.raw)
            db.dao().upsertEntry(EntryRow.from(identified))
            val repair=TruthUnitRepair.Change(TruthUnitRepair.Quantity.DIAMETER,25.0,63.5,"reading",next.id.toString())
            check(history.repairTruthUnits(mapOf(next.id to repair))==0){"Legacy unit repair reached an identified result"}
            // Production log repair must also keep identified/malformed IDs
            // as recorded, including original GT/error/unit cells.
            val header=ResearchLog.COLUMNS
            fun row(id:String)=header.map{mapOf("measurement_id" to id,"measure_type" to "dbh","tree_id" to "7",
                "true_value" to "25","measured_value" to "30","error" to "5")[it].orEmpty()}
            val modern=row(next.id.toString());val malformed=row("invalid")
            val rows=listOf(header,modern,malformed,row(""))
            val text=rows.joinToString("\n",postfix="\n"){it.joinToString(","){cell->ResearchLog.csvEscape(cell)}}
            val log=File(root,"forestix_research_log.csv");log.writeText(text)
            check(ResearchLog.repairImperialTruths(context,false).rows.size==1&&log.readText()==text)
            check(ResearchLog.repairImperialTruths(context,true).written==1)
            val repaired=ResearchLog.parseRecords(log.readText());check(repaired[1]==modern&&repaired[2]==malformed)
            check(ResearchLog.repairImperialTruths(context,true).written==0)
            db.close();db=open();val restored=db.dao().allEntries().associateBy{it.id}
            check(restored[legacy.id.toString()]==expected){"Existing legacy observation changed during native identity checks"}
            check(restored[next.id.toString()]==EntryRow.from(identified)){"Identified result changed after SQLite reopen"}
        }finally{db.close()}
    }
}
