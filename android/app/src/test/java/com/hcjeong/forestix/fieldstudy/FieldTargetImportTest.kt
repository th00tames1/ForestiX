package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.geo.FieldTargetFileReader
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Synthetic application fixtures only. */
class FieldTargetImportTest{
    @Test fun ignoredDirectoryPayloadCannotBypassInflationLimit(){
        val bytes=ByteArrayOutputStream()
        ZipOutputStream(bytes).use{zip->zip.putNextEntry(ZipEntry("ignored/"))
            val block=ByteArray(8192)
            repeat(FieldTargetFileReader.maximumBytes/block.size+1){zip.write(block)};zip.closeEntry()}
        val error=assertThrows(FieldStudyException::class.java){FieldTargetFileReader.read(bytes.toByteArray(),"synthetic.zip")}
        assertTrue(error.message!!.contains("ZIP component size"))
    }
    private fun fixture(cpg:Boolean=true,badCount:Boolean=false):ByteArray{
        fun le(n:Int)=ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array()
        fun be(n:Int)=ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(n).array()
        fun number(n:Double)=ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(n).array()
        val shp=ByteArrayOutputStream();val header=ByteArray(100)
        be(9994).copyInto(header,0);le(1000).copyInto(header,28);le(1).copyInto(header,32);shp.write(header)
        for(i in 0..3){val body=if(i==1)le(0)else le(1)+number(-123.0+i/10000.0)+number(45.0)
            shp.write(be(i+1)+be(body.size/2)+body)}
        val shapes=shp.toByteArray();be(shapes.size/2).copyInto(shapes,24)
        val names=listOf("TREE_ID","GT","NAME");val widths=listOf(8,8,20);val dbfHeader=ByteArray(129)
        dbfHeader[0]=3;le(if(badCount)3 else 4).copyInto(dbfHeader,4);dbfHeader[8]=129.toByte();dbfHeader[10]=37
        for(i in names.indices){val p=32+i*32;names[i].toByteArray().copyInto(dbfHeader,p)
            dbfHeader[p+11]=(if(i==1)78 else 67).toByte();dbfHeader[p+16]=widths[i].toByte()};dbfHeader[128]=13
        val dbf=ByteArrayOutputStream();dbf.write(dbfHeader)
        for(i in 0..3){dbf.write(if(i==2)42 else 32)
            for((text,width)in listOf("00${i+1}" to 8,(20+i).toString() to 8,"합성${i+1}" to 20)){
                val bytes=text.toByteArray();dbf.write(bytes);dbf.write(ByteArray(width-bytes.size){32})}}
        val wgs="GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"Degree\",0.0174532925199433]]"
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use{zip->
            val entries=mutableListOf("trees.shp" to shapes,"trees.dbf" to dbf.toByteArray(),"trees.prj" to wgs.toByteArray())
            if(cpg)entries.add("trees.cpg" to "UTF-8".toByteArray())
            for((name,bytes)in entries){zip.putNextEntry(ZipEntry("test/$name"));zip.write(bytes);zip.closeEntry()}
        };return output.toByteArray()
    }
    @Test fun physicalRowsDoNotShiftAfterNullOrDeletedRecords(){
        val source=FieldTargetFileReader.read(fixture(),"synthetic.zip")
        assertEquals(2,source.skippedRecords);assertEquals(listOf("001","004"),source.features.map{it.attributes["TREE_ID"]})
        assertEquals(listOf("1","4"),source.features.map{it.sourceFeatureId});assertEquals("합성4",source.features.last().attributes["NAME"])
        assertEquals(-122.9997,source.features.last().longitude,1e-9)
        val c=FieldStudyConfiguration(studyId="SYNTHETIC",operatorId="TEST")
        val map=FieldTargetMapping("TREE_ID",referenceFields=mapOf("dbh-gt" to "GT"))
        val batch=FieldTargetMaterializer.make(source,map,c,true)
        assertEquals(listOf(20.0,23.0),batch.references.map{it.inputValue})
        assertThrows(FieldStudyException::class.java){FieldTargetMaterializer.make(source,map,c,false)}
    }
    @Test fun encodingCountAndUnsupportedShapesAreExplicitFailures(){
        assertThrows(Exception::class.java){FieldTargetFileReader.read(fixture(false),"synthetic.zip")}
        assertEquals(2,FieldTargetFileReader.read(fixture(false),"synthetic.zip","UTF-8").features.size)
        assertThrows(Exception::class.java){FieldTargetFileReader.read(fixture(badCount=true),"synthetic.zip")}
        for(input in listOf(
            """{"type":"Feature","crs":{"type":"name","properties":{"name":"EPSG:3857"}},"geometry":{"type":"Point","coordinates":[0,0]}}""",
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[true,0]}}""",
            """{"type":"Feature","properties":{"id":{"nested":1}},"geometry":{"type":"Point","coordinates":[0,0]}}""",
            """{"type":"Feature","geometry":{"type":"MultiPoint","coordinates":[[0,0]]}}""",
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[181,0]}}"""
        ))assertThrows(Exception::class.java){FieldTargetFileReader.read(input.toByteArray(),"synthetic.geojson")}
    }
    @Test fun importIsAtomicIdempotentDeveloperGatedAndImmutable(){
        val json="""{"type":"FeatureCollection","features":[{"type":"Feature","id":"f1","properties":{"id":"007","dbh":"10","alt":100},"geometry":{"type":"Point","coordinates":[-123,45]}},{"type":"Feature","properties":{},"geometry":null}]}"""
        val source=FieldTargetFileReader.read(json.toByteArray(),"synthetic.geojson");assertEquals(1,source.skippedRecords)
        val base=FieldStudyConfiguration(studyId="SYNTHETIC",operatorId="TEST")
        val c=base.copy(referenceMethods=base.referenceMethods.map{if(it.id=="dbh-gt")it.copy(unit=FieldReferenceUnit.INCH)else it})
        val mapping=FieldTargetMapping("id",altitudeField="alt",altitudeUnit=FieldReferenceUnit.FT,referenceFields=mapOf("dbh-gt" to "dbh"))
        val batch=FieldTargetMaterializer.make(source,mapping,c,true)
        assertEquals("007",batch.targets[0].treeId);assertEquals(30.48,batch.targets[0].altitudeM!!,1e-9);assertEquals(25.4,batch.references[0].baseValue,1e-9)
        val root=Files.createTempDirectory("forestix-import-test-").toFile()
        try{
            val archive=FieldStudyArchive(root);archive.saveConfiguration(c,true)
            assertThrows(FieldStudyException::class.java){archive.commitImport(batch,false)};assertTrue(archive.loadTargets().isEmpty())
            archive.commitImport(batch,true);archive.commitImport(batch,true)
            assertEquals(batch,FieldStudyJson.importBatch(FieldStudyJson.importJson(batch).toString()))
            assertEquals(1,archive.loadTargets().size);assertEquals(1,archive.scanReferences().values.size)
            assertThrows(FieldStudyException::class.java){archive.commitImport(FieldTargetMaterializer.make(source,mapping,c,true),true)}
            assertThrows(FieldStudyException::class.java){archive.saveTargets(emptyList())}
            val old=batch.references[0];val replacement=old.copy(id=newFieldId(),inputValue=11.0,baseValue=11*2.54,supersedesId=old.id)
            archive.appendReference(replacement,true);assertEquals(2,archive.scanReferences().values.size)
            assertThrows(FieldStudyException::class.java){FieldTargetMaterializer.make(source.copy(features=source.features+source.features),mapping,c,true)}
        }finally{root.deleteRecursively()}
    }
}
