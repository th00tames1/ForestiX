package com.hcjeong.forestix.sensors

import java.io.File
import kotlinx.serialization.json.*

/** Small checksummed sidecars, included by existing whole-bundle exports.
 * Only runs when raw recording runs, never in the live mask/preview loop. */
object DbhDepthComparisonIO {
    data class Geometry(val axis:GuideAxis,val leftFraction:Double,val rightFraction:Double)
    data class Input(val frame:ArDepthFrame,val source:RawCapturedFrame,val geometry:Geometry?)

    fun annotate(source:RawCapturedFrame,g:Geometry?):RawCapturedFrame {
        if(g==null)return source
        val values=mapOf("comparison_axis" to if(g.axis is GuideAxis.Col)"col"else"row",
            "comparison_fixed" to when(val a=g.axis){is GuideAxis.Col->a.x;is GuideAxis.Row->a.y}.toString(),
            "comparison_left_fraction" to g.leftFraction.toString(),"comparison_right_fraction" to g.rightFraction.toString())
        return source.copy(depthDiagnostics=source.depthDiagnostics+values)
    }
    private fun recordedGeometry(source:RawCapturedFrame):Geometry? {
        val d=source.depthDiagnostics
        if(!d.containsKey("comparison_axis"))return null
        val fixed=d.getValue("comparison_fixed").toInt()
        val axis=when(d.getValue("comparison_axis")){"row"->GuideAxis.Row(fixed);"col"->GuideAxis.Col(fixed);else->error("Recorded comparison axis")}
        return Geometry(axis,d.getValue("comparison_left_fraction").toDouble(),d.getValue("comparison_right_fraction").toDouble())
    }
    fun requireOriginalAssociation(report:JsonObject,evidence:RawCaptureEvidence){
        require(report.getValue("measurement_id").jsonPrimitive.content==evidence.measurementId){"Comparison/capture identity"}
        val rows=report.getValue("frames").jsonArray.map{it.jsonObject}
        val sources=rows.map{RawCaptureEvidence.frame(it.getValue("source").jsonObject)}
        require(sources==evidence.frames){"Comparison source differs from canonical capture"}
        require(rows.zip(sources).all{(row,source)->geometry(row["geometry"])==recordedGeometry(source)}){"Comparison boundary differs from canonical capture"}
    }

    private fun estimateJson(e:DbhDepthCandidates.Estimate)=buildJsonObject {
        put("candidate_id",e.id);put("status",e.status);put("applied_to_result",false)
        fun number(key:String,value:Double?){if(value!=null&&value.isFinite())put(key,value)}
        number("raw_diameter_cm",e.rawDiameterCm);number("diameter_cm",e.diameterCm);number("depth_m",e.depthM)
        put("raw_pixels",e.rawPixels);put("axis_samples",e.axisSamples);put("axis_coverage",e.axisCoverage)
        number("relative_mad",e.relativeMad);number("paired_offset_m",e.offsetM)
    }
    private fun geometryJson(g:Geometry?)=g?.let{buildJsonObject{
        put("axis",if(it.axis is GuideAxis.Col)"col"else"row")
        put("fixed",when(val a=it.axis){is GuideAxis.Col->a.x;is GuideAxis.Row->a.y})
        put("left_fraction",it.leftFraction);put("right_fraction",it.rightFraction)
    }}?:JsonNull
    private fun geometry(o:JsonElement?):Geometry? {
        if(o==null||o==JsonNull)return null
        val g=o.jsonObject;val fixed=g.getValue("fixed").jsonPrimitive.int
        val axis=when(g.getValue("axis").jsonPrimitive.content){"col"->GuideAxis.Col(fixed);"row"->GuideAxis.Row(fixed);else->error("Comparison axis")}
        val left=g.getValue("left_fraction").jsonPrimitive.double;val right=g.getValue("right_fraction").jsonPrimitive.double
        require(left.isFinite()&&right.isFinite()&&left>=0&&right>left&&right<1){"Comparison bracket"}
        return Geometry(axis,left,right)
    }
    private fun calibrationJson(cal:ProjectCalibration)=buildJsonObject {
        put("alpha",cal.dbhCorrectionAlpha);put("beta",cal.dbhCorrectionBeta);put("epoch",cal.dbhCalibrationEpoch)
    }
    private fun calibration(o:JsonObject)=ProjectCalibration(
        dbhCorrectionAlpha=o.getValue("alpha").jsonPrimitive.float,
        dbhCorrectionBeta=o.getValue("beta").jsonPrimitive.float,
        dbhCalibrationEpoch=o.getValue("epoch").jsonPrimitive.int).also{
            require(it.dbhCorrectionAlpha.isFinite()&&it.dbhCorrectionBeta.isFinite()){"Comparison calibration"}}

    fun evaluate(id:String,inputs:List<Input>,cal:ProjectCalibration,version:Int=DbhDepthCandidates.VERSION):JsonObject {
        val ids=DbhDepthCandidates.ids(version)
        require(inputs.size in 1..32&&inputs.map{it.source.role}.distinct().size==inputs.size){"Comparison input count/roles"}
        return buildJsonObject {
            put("version",version);put("measurement_id",id)
            put("mode","shadow_only");put("automatic_selection",false);put("calibration",calibrationJson(cal))
            put("parameters",buildJsonObject{
                put("sampling_fraction",BoundaryAlignment.SAMPLING_FRACTION);put("minimum_raw_confidence",DbhMeasurementDepth.MIN_CONFIDENCE)
                put("orthogonal_radius_pixels",2);put("experimental_minimum_raw_pixels",6);put("experimental_minimum_axis_samples",3)
                put("experimental_minimum_axis_coverage",.5);put("experimental_maximum_relative_mad",.05)
                if(version>=2)put("dense_ray_quantile",.25)
            })
            put("frames",JsonArray(inputs.map{input->
                require(input.source.width==input.frame.width&&input.source.height==input.frame.height&&
                    input.source.depthInputPolicy==input.frame.depthInputPolicy){"Comparison source geometry/policy"}
                val g=input.geometry;val extent=if(g?.axis is GuideAxis.Col)input.frame.height else input.frame.width
                val r=DbhDepthCandidates.evaluate(input.frame,g?.axis?:GuideAxis.Row(-1),g?.leftFraction?.times(extent),g?.rightFraction?.times(extent),cal)
                buildJsonObject{
                    put("source",RawCaptureEvidence.frameJson(input.source));put("geometry",geometryJson(g))
                    put("estimates",JsonArray(r.estimates.filter{it.id in ids}.map(::estimateJson)))
                }
            }))
        }
    }

    fun csv(report:JsonObject):String {
        fun cell(text:String)="\""+text.replace("\"","\"\"")+"\""
        val header=listOf("measurement_id","input_role","source_frame_id","input_depth_sha256","raw_depth_sha256","raw_confidence_sha256",
            "candidate_id","status","applied_to_result","diameter_cm","raw_diameter_cm","depth_m","raw_pixels","axis_samples","axis_coverage","relative_mad","paired_offset_m")
        return buildString {
            append(header.joinToString(","));append('\n')
            for(value in report.getValue("frames").jsonArray){
                val f=value.jsonObject;val s=f.getValue("source").jsonObject
                val raw=s["auxiliary_raw_depth"]?.jsonObject
                for(e in f.getValue("estimates").jsonArray.map{it.jsonObject}){
                    fun JsonObject.text(key:String)=get(key)?.jsonPrimitive?.content.orEmpty()
                    val row=listOf(report.text("measurement_id"),s.text("role"),s.text("source_frame_id"),
                        s.getValue("depth").jsonObject.text("sha256"),raw?.get("depth")?.jsonObject?.text("sha256").orEmpty(),
                        raw?.get("confidence")?.jsonObject?.text("sha256").orEmpty())+
                        listOf("candidate_id","status","applied_to_result","diameter_cm","raw_diameter_cm","depth_m","raw_pixels","axis_samples","axis_coverage","relative_mad","paired_offset_m").map{e.text(it)}
                    append(row.joinToString(",",transform=::cell));append('\n')
                }
            }
        }
    }

    /** Caller catches diagnostic failure independently of the primary raw
     * recorder, so a comparison cannot prevent saving original evidence. */
    fun write(directory:File,id:String,inputs:List<Input>,cal:ProjectCalibration,version:Int=DbhDepthCandidates.VERSION):JsonObject {
        val report=evaluate(id,inputs,cal,version)
        val jsonProof=RawCaptureEvidenceIO.write(report.toString().toByteArray(Charsets.UTF_8),"dbh_depth_candidates.json",directory)
        val csvProof=RawCaptureEvidenceIO.write(csv(report).toByteArray(Charsets.UTF_8),"dbh_depth_candidates.csv",directory)
        return buildJsonObject {
            put("version",version);put("mode","shadow_only");put("measurement_id",id)
            put("report",RawCaptureEvidence.proofJson(jsonProof));put("csv",RawCaptureEvidence.proofJson(csvProof))
        }
    }

    /** Verify sidecars AND original source bytes, then rerun every candidate.
     * No trust in a cached candidate number or a legacy dense fallback. */
    fun replay(directory:File,id:String,descriptor:JsonObject):JsonObject {
        val version=descriptor.getValue("version").jsonPrimitive.int
        DbhDepthCandidates.ids(version) // Fail closed on unknown future versions.
        require(
            descriptor.getValue("measurement_id").jsonPrimitive.content==id&&descriptor.getValue("mode").jsonPrimitive.content=="shadow_only"){"Comparison identity/version"}
        val text=RawCaptureEvidence.proof(descriptor.getValue("report").jsonObject).read(directory).toString(Charsets.UTF_8)
        val saved=Json.parseToJsonElement(text).jsonObject
        require(saved.getValue("measurement_id").jsonPrimitive.content==id&&saved.getValue("version").jsonPrimitive.int==version){"Comparison report identity/version"}
        val csvBytes=RawCaptureEvidence.proof(descriptor.getValue("csv").jsonObject).read(directory)
        require(csvBytes.contentEquals(csv(saved).toByteArray(Charsets.UTF_8))){"Comparison CSV/report mismatch"}
        val inputs=saved.getValue("frames").jsonArray.map{row->
            val r=row.jsonObject;val source=RawCaptureEvidence.frame(r.getValue("source").jsonObject)
            Input(RawCaptureEvidenceIO.readFrame(source,directory),source,geometry(r["geometry"]))
        }
        val rerun=evaluate(id,inputs,calibration(saved.getValue("calibration").jsonObject),version)
        require(rerun==saved){"Comparison replay mismatch"}
        return rerun
    }
}
