package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.*
import com.hcjeong.forestix.sensors.RawCaptureStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class LegacyIdentityBoundaryTest {
    private fun entry(id:UUID=UUID.randomUUID(),truth:Double?=null)=QuickMeasureEntry(id=id,kind=MeasureKind.DBH,value=25.0,sigma=1.0,
        confidenceRaw="green",method="silhouette",createdAt=100000,treeNumber=7,truth=truth,truthSource="capture")
    @Test fun identityAndEveryCopySurviveRoomProjectionWithoutChangingMeasurement(){
        val old=entry();assertTrue(old.allowsLegacyInference);val e=old.identifyingMeasurement()
        for(c in listOf(e.copy(plotID=UUID.randomUUID()),e.settingTruth(27.0),e.settingCreatedAt(200000),e.copy(note="test"))){
            val restored=EntryRow.from(c).toDomain();assertEquals(e.id.toString(),restored.measurementId)
            assertFalse(restored.allowsLegacyInference);assertEquals(25.0,restored.value,0.0);assertEquals(1.0,restored.sigma!!,0.0)
        }
        val malformed=old.copy(measurementId="malformed");assertFalse(malformed.allowsLegacyInference)
        assertTrue(runCatching{malformed.identifyingMeasurement()}.isFailure)
        val csv=QuickMeasureExport.buildCsv(listOf(e,old),emptyList()).toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val rows=ResearchLog.parseRecords(csv);val column=rows.first().indexOf("measurement_id")
        assertTrue(column>=0);assertEquals(e.id.toString(),rows[1][column]);assertEquals("",rows[2][column])
        val height=QuickMeasureEntry.typed(MeasureKind.HEIGHT,20.0,7,plotID=null)
        assertEquals(height.id.toString(),height.measurementId)
        val bundle=QuickMeasureExport.buildBundleZip(listOf(e,height),emptyList(),com.hcjeong.forestix.sensors.LogRule.SCRIBNER)
        val files=mutableMapOf<String,String>()
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bundle)).use{zip->
            while(true){val member=zip.nextEntry?:break;files[member.name]=zip.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF");zip.closeEntry()}}
        for((name,id) in listOf("Stems.csv" to e.id,"Heights.csv" to height.id)){
            val cells=ResearchLog.parseRecords(files.getValue(name));val col=cells.first().indexOf("measurement_id")
            assertTrue(col>=0);assertEquals(id.toString(),cells[1][col])}
    }
    @Test fun legacyTruthPlannerNeverUsesIdentifiedReadingsOrCaptures(){
        val old=entry();val modern=old.identifyingMeasurement()
        val truth=TruthBackfill.ManifestTruth(UUID.randomUUID().toString(),MeasureKind.DBH,7,30.0,100000,"1970-01-01T00:01:40Z")
        assertEquals(30.0,TruthBackfill.plan(listOf(truth),listOf(old),null,1).attachments[old.id]!!,0.0)
        assertTrue(TruthBackfill.plan(listOf(truth),listOf(modern),null,1).attachments.isEmpty())
        assertTrue(TruthBackfill.plan(listOf(truth.copy(measurementId=UUID.randomUUID().toString())),listOf(old),null,1).attachments.isEmpty())
        fun summary(legacy:Boolean)=RawCaptureStore.Summary(UUID.randomUUID().toString(),"dbh","1970-01-01T00:01:40Z",7,"quick",25.0,"cm","green",25.0,null,"pass",1,legacy)
        assertTrue(TruthBackfill.recoverable(listOf(summary(false))).isEmpty())
        val p=TruthUnitRepair.plan(listOf(summary(true)),listOf(entry(truth=25.0).identifyingMeasurement()),emptyList(),TruthUnitRepair.Kept())
        assertTrue(p.readings.isEmpty());assertEquals(1,p.captures.size)
        assertTrue(TruthUnitRepair.plan(listOf(summary(false)),emptyList(),emptyList(),TruthUnitRepair.Kept()).captures.isEmpty())
    }
    @Test fun identifiedExportUsesExactUUIDNeverRebasesTruthOrFallsBackOnValueTime(){
        val id=UUID.randomUUID().toString();val other=UUID.randomUUID().toString()
        val header=listOf("measurement_id","measure_type","tree_id","measured_value","true_value","error","truth_unit","timestamp_iso")
        val row=listOf(id,"dbh","7","25.00","30.00","-5.00","cm","1970-01-01T00:01:40Z")
        val exact=ResearchExport.Reading("dbh",99,25.0,Long.MAX_VALUE,40.0,"cm",id,id)
        val c=ResearchExport.classify(listOf(header,row),listOf(exact))!!
        assertEquals(row,c.kept.single().dropLast(1));assertEquals(1,c.counts.live);assertTrue(c.heldBack.isEmpty())
        val rival=ResearchExport.Reading("dbh",7,25.0,100000,30.0,"cm",other)
        val missing=ResearchExport.classify(listOf(header,row),listOf(rival))!!
        assertEquals(1,missing.counts.unclassified);assertTrue(missing.heldBack.isEmpty())
        assertEquals(2,ResearchExport.classify(listOf(header,row,row),listOf(exact))!!.counts.unclassified)
        val invalid=row.toMutableList().also{it[0]="not-a-uuid"}
        assertEquals(1,ResearchExport.classify(listOf(header,invalid),listOf(rival))!!.counts.unclassified)
        val legacy=row.toMutableList().also{it[0]=""}
        assertEquals(1,ResearchExport.classify(listOf(header,legacy),listOf(exact))!!.counts.unclassified)
        assertEquals(1,ResearchExport.classify(listOf(header,legacy),listOf(rival))!!.counts.live)
    }
}
