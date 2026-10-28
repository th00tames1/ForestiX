package com.hcjeong.forestix.geo

import com.hcjeong.forestix.data.fieldstudy.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream

/** Point-only, bounded importer. Join physical SHP/DBF rows BEFORE filtering null/deleted records. */
object FieldTargetFileReader{
    const val maximumBytes=16*1024*1024
    fun read(data:ByteArray,filename:String,encodingOverride:String?=null):FieldTargetSource{
        fieldRequire(data.size<=maximumBytes,"target file exceeds 16 MiB")
        val ext=filename.substringAfterLast('.',"").lowercase(Locale.US)
        val layerId=FieldFingerprint.digest(listOf("target-source-v1",Base64.getEncoder().encodeToString(data)))
        if(ext=="geojson"||ext=="json")return geoJSON(data,filename,layerId)
        fieldRequire(ext=="zip","use a SHP ZIP (.shp/.dbf/.prj) or GeoJSON")
        val files=linkedMapOf<String,ByteArray>();val names=HashSet<String>();var entries=0;var total=0
        ZipInputStream(ByteArrayInputStream(data)).use{zip->
            while(true){val entry=zip.nextEntry?:break
                fieldRequire(++entries<=1000,"ZIP entry count")
                val key=entry.name.lowercase(Locale.US)
                val included=!entry.isDirectory&&!entry.name.startsWith("__MACOSX/")
                if(included)fieldRequire(names.add(key),"duplicate ZIP filenames")
                val keep=included&&key.substringAfterLast('.') in setOf("shp","dbf","prj","cpg")
                // Bound even directory/ignored payloads: closeEntry must not
                // inflate an unbounded discarded ZIP entry behind this guard.
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);var size=0
                while(true){val count=zip.read(buffer);if(count<0)break
                    size+=count;total+=count;fieldRequire(size<=maximumBytes&&total<=32*1024*1024,"ZIP component size")
                    if(keep)output.write(buffer,0,count)
                }
                if(keep)files[key]=output.toByteArray()
                zip.closeEntry()
            }
        }
        val shapes=files.keys.filter{it.endsWith(".shp")}
        fieldRequire(shapes.size==1,"ZIP must contain exactly one point layer")
        val stem=shapes.single().dropLast(4)
        fun sidecar(ext:String)=files["$stem.$ext"]?:throw FieldStudyException("missing","SHP sidecar .$ext")
        BoundaryCRS.rejectionFor(sidecar("prj").toString(Charsets.UTF_8))?.let{throw FieldStudyException("invalid",it)}
        val rows=dbf(sidecar("dbf"),encodingOverride?:files["$stem.cpg"]?.toString(Charsets.US_ASCII))
        val points=shape(sidecar("shp"));fieldRequire(points.size==rows.size,"SHP/DBF record count mismatch")
        val features=ArrayList<FieldTargetSourceFeature>();var skipped=0
        for(index in points.indices){val point=points[index];val row=rows[index]
            if(point==null||row==null){skipped++;continue}
            val attrs=row.toMutableMap();point.z?.let{attrs["\$geometry_z"]=it.toString()}
            features.add(FieldTargetSourceFeature((index+1).toString(),point.y,point.x,attrs))
        }
        fieldRequire(features.isNotEmpty(),"no active point records")
        return FieldTargetSource(layerId,filename,features,skipped)
    }
    private fun geoJSON(data:ByteArray,filename:String,layerId:String):FieldTargetSource{
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
        val root=Json.parseToJsonElement(text).jsonObject
        GeoJSONCRS.rejectionAnywhere(root)?.let{throw FieldStudyException("invalid",it)}
        val objects=when(root["type"]?.jsonPrimitive?.content){
            "FeatureCollection"->root["features"]?.jsonArray?.map{it.jsonObject}?:throw FieldStudyException("invalid","GeoJSON features")
            "Feature"->listOf(root)
            else->throw FieldStudyException("invalid","GeoJSON FeatureCollection or Feature required")}
        fieldRequire(objects.size<=10000,"target count")
        val features=ArrayList<FieldTargetSourceFeature>();var skipped=0
        for((index,obj)in objects.withIndex()){
            fieldRequire(obj["type"]?.jsonPrimitive?.content=="Feature","GeoJSON feature type")
            if(obj["geometry"]==JsonNull){skipped++;continue}
            val geometry=obj["geometry"]?.jsonObject?:throw FieldStudyException("invalid","GeoJSON geometry")
            fieldRequire(geometry["type"]?.jsonPrimitive?.content=="Point","point geometry required at feature ${index+1}")
            val coordinates=geometry["coordinates"]?.jsonArray?:throw FieldStudyException("invalid","point coordinates")
            fieldRequire(coordinates.size in 2..3&&coordinates.all{it is JsonPrimitive&&!it.isString&&it.doubleOrNull?.isFinite()==true},"point coordinates")
            val x=coordinates[0].jsonPrimitive.double;val y=coordinates[1].jsonPrimitive.double;validatePoint(x,y)
            val attrs=linkedMapOf<String,String>();val props=obj["properties"]
            if(props!=null&&props!=JsonNull){
                val properties=props.jsonObject;fieldRequire(properties.size<=100,"attribute count")
                for((key,v)in properties){
                    fieldRequire(!key.startsWith("$")&&key.toByteArray().size<=200,"reserved or long attribute name")
                    if(v==JsonNull)continue
                    fieldRequire(v is JsonPrimitive,"target properties must be scalar: $key")
                    val value=v.jsonPrimitive.content;fieldRequire(value.toByteArray().size<=4096,"attribute length");attrs[key]=value
                }
            }
            if(coordinates.size==3)attrs["\$geometry_z"]=coordinates[2].jsonPrimitive.content
            val id=obj["id"]?.takeIf{it is JsonPrimitive&&it!=JsonNull}?.jsonPrimitive?.content?:(index+1).toString()
            features.add(FieldTargetSourceFeature(id,y,x,attrs))
        }
        fieldRequire(features.isNotEmpty(),"no point records")
        return FieldTargetSource(layerId,filename,features,skipped)
    }
    private data class Point(val x:Double,val y:Double,val z:Double?)
    private fun shape(b:ByteArray):List<Point?>{
        val little=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);val big=little.duplicate().order(ByteOrder.BIG_ENDIAN)
        fieldRequire(b.size>=100&&big.getInt(0)==9994&&little.getInt(28)==1000&&
            big.getInt(24).toLong()*2==b.size.toLong()&&little.getInt(32) in listOf(1,11,21),"point SHP header")
        val fileType=little.getInt(32);var cursor=100;val result=ArrayList<Point?>()
        while(cursor<b.size){
            fieldRequire(result.size<10000&&cursor+8<=b.size&&big.getInt(cursor)==result.size+1,"SHP record sequence")
            val length=big.getInt(cursor+4).toLong()*2;cursor+=8
            fieldRequire(length>=4&&length<=b.size-cursor,"SHP record length");val type=little.getInt(cursor)
            if(type==0){fieldRequire(length==4L,"null SHP record length");result.add(null)}
            else{
                fieldRequire(type==fileType&&(length==(when(type){1->20L;21->28L;else->36L})||type==11&&length==28L),"mixed or nonpoint SHP records")
                val x=little.getDouble(cursor+4);val y=little.getDouble(cursor+12);validatePoint(x,y)
                val z=if(type==11)little.getDouble(cursor+20)else null
                fieldRequire(z==null||z.isFinite(),"SHP Z coordinate");result.add(Point(x,y,z))
            };cursor+=length.toInt()
        };return result
    }
    private fun validatePoint(x:Double,y:Double)=fieldRequire(x.isFinite()&&y.isFinite()&&x in -180.0..180.0&&y in -90.0..90.0,"WGS 84 point coordinates")
    private fun dbf(b:ByteArray,encoding:String?):List<Map<String,String>?>{
        val le=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        fieldRequire(b.size>=33&&(b[0].toInt()and 255) in listOf(3,0x83),"dBASE III table required")
        val count=le.getInt(4).toLong()and 0xffffffffL;val header=le.getShort(8).toInt()and 65535;val record=le.getShort(10).toInt()and 65535
        fieldRequire(count<=10000&&header>=33&&header<=b.size&&(header-33)%32==0&&record>0&&
            count<=(b.size-header)/record&&b[header-1].toInt()==13,"DBF header or record count")
        val codec=when(encoding?.trim()?.lowercase(Locale.US)){
            null,"","auto"->Charsets.US_ASCII
            "utf-8","utf8","65001"->Charsets.UTF_8
            "1252","windows-1252","cp1252"->Charset.forName("windows-1252")
            "949","cp949","ms949"->Charset.forName("MS949")
            else->throw FieldStudyException("invalid","Unsupported DBF encoding")}
        fun decode(start:Int,length:Int)=try{codec.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b,start,length)).toString().trim{it<=' '}}
            catch(e:Exception){throw FieldStudyException("invalid","DBF text requires a matching .cpg or explicit UTF-8/Windows-1252/CP949 encoding")}
        val fields=ArrayList<Pair<String,Int>>();var size=1
        for(o in 32 until header-1 step 32){
            val name=decode(o,(0 until 11).firstOrNull{b[o+it].toInt()==0}?:11);val width=b[o+16].toInt()and 255
            fieldRequire(name.isNotEmpty()&&!name.startsWith("$")&&fields.none{it.first==name}&&
                b[o+11].toInt() in listOf(67,78,70,76,68)&&width>0&&fields.size<100,"unsupported or duplicate DBF field")
            fields.add(name to width);size+=width
        }
        fieldRequire(size==record,"DBF field widths")
        return (0 until count.toInt()).map{index->val start=header+index*record
            fieldRequire(b[start].toInt() in listOf(32,42),"DBF deletion flag")
            if(b[start].toInt()==42)null else{var p=start+1;val values=linkedMapOf<String,String>()
                for((name,width)in fields){values[name]=decode(p,width);p+=width};values}
        }
    }
}
