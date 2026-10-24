package com.hcjeong.forestix.data.fieldstudy

import java.util.UUID

data class FieldTargetSourceFeature(val sourceFeatureId:String,val latitude:Double,val longitude:Double,val attributes:Map<String,String>){
    fun value(field:String)=if(field=="\$feature_id")sourceFeatureId else attributes[field]
}
data class FieldTargetSource(val layerId:String,val name:String,val features:List<FieldTargetSourceFeature>,val skippedRecords:Int=0){
    val fields:List<String> get()=listOf("\$feature_id")+features.flatMap{it.attributes.keys}.toSet().sorted()
}
data class FieldTargetMapping(val treeIdField:String,val labelField:String?=null,val altitudeField:String?=null,
    val altitudeUnit:FieldReferenceUnit=FieldReferenceUnit.M,val accuracyField:String?=null,
    val referenceFields:Map<String,String> = emptyMap(),val referencePlannedRepeat:Int?=null)
data class FieldTargetImportBatch(val sourceName:String,val sourceLayerId:String,val studyId:String,
    val protocolFingerprint:String,val mapping:FieldTargetMapping,val targets:List<FieldStudyTarget>,
    val references:List<FieldReferenceObservation>,val skippedRecords:Int,val schema:Int=1,
    val id:String=newFieldId(),val importedAt:String=java.time.Instant.now().toString()){
    fun validate(developerMode:Boolean){
        val targetIds=targets.map{it.id}.toSet()
        fieldRequire(schema==1&&validFieldUuid(id)&&sourceName.isNotEmpty()&&studyId.isNotEmpty()&&targets.isNotEmpty()&&
            targets.size<=10000&&references.size<=100000&&targetIds.size==targets.size&&
            references.map{it.id}.toSet().size==references.size&&skippedRecords>=0&&
            targets.all{it.studyId==studyId&&it.sourceLayerId==sourceLayerId}&&references.all{it.targetId in targetIds},"target import batch")
        if(references.isNotEmpty()&&!developerMode)throw FieldStudyException("developer_required","Ground-truth editing requires developer mode.")
        targets.forEach{it.validate()};references.forEach{it.validate(developerMode)}
        val slots=references.map{FieldFingerprint.digest(listOf(it.targetId,it.method.id,(it.plannedRepeat?:0).toString(),it.referenceRepeat.toString()))}
        fieldRequire(slots.toSet().size==slots.size&&references.all{it.supersedesId==null},"duplicate imported reference slot")
    }
}
object FieldTargetMaterializer{
    fun make(source:FieldTargetSource,mapping:FieldTargetMapping,configuration:FieldStudyConfiguration,developerMode:Boolean):FieldTargetImportBatch{
        val c=configuration;c.validate()
        val fields=source.fields.toSet()
        val mapped=listOf(mapping.treeIdField)+listOfNotNull(mapping.labelField,mapping.altitudeField,mapping.accuracyField)+mapping.referenceFields.values
        fieldRequire(c.studyId.isNotBlank()&&source.features.size<=10000&&source.features.size.toLong()*mapping.referenceFields.size<=100000&&mapping.treeIdField.isNotEmpty()&&
            mapped.all{it in fields}&&
            mapping.altitudeUnit.supports(FieldStudyKind.HEIGHT)&&
            (mapping.referencePlannedRepeat==null||mapping.referencePlannedRepeat in 1..c.plannedRepetitions),"target field mapping or study ID")
        if(mapping.referenceFields.isNotEmpty()&&!developerMode)throw FieldStudyException("developer_required","Ground-truth editing requires developer mode.")
        val targets=ArrayList<FieldStudyTarget>();val refs=ArrayList<FieldReferenceObservation>()
        for(f in source.features){
            fun text(field:String)=f.value(field)?.trim()?.takeIf{it.isNotEmpty()}
                ?:throw FieldStudyException("invalid","Missing $field in feature ${f.sourceFeatureId}")
            fun number(field:String?):Double?{
                val raw=field?.let{f.value(it)?.trim()}?.takeIf{it.isNotEmpty()}?:return null
                return raw.toDoubleOrNull()?.takeIf{it.isFinite()}
                    ?:throw FieldStudyException("invalid","Numeric field $field in feature ${f.sourceFeatureId}")
            }
            val treeId=text(mapping.treeIdField)
            val target=FieldStudyTarget(c.studyId,treeId,mapping.labelField?.let{ text(it) }?:treeId,f.latitude,f.longitude,
                number(mapping.altitudeField)?.let{mapping.altitudeUnit.baseValue(it)},number(mapping.accuracyField),
                source.layerId,f.sourceFeatureId,attributes=f.attributes)
            target.validate();targets.add(target)
            for((methodId,field)in mapping.referenceFields.toSortedMap()){
                val method=c.referenceMethods.firstOrNull{it.id==methodId&&it.enabled}
                    ?:throw FieldStudyException("invalid","Unknown reference method $methodId")
                number(field)?.let{refs.add(FieldReferenceObservation(targetId=target.id,method=method,inputValue=it,
                    plannedRepeat=mapping.referencePlannedRepeat,operatorId=c.operatorId,source="${source.layerId}:${f.sourceFeatureId}:$field"))}
            }
        }
        return FieldTargetImportBatch(source.name,source.layerId,c.studyId,c.protocolFingerprint,mapping,targets,refs,source.skippedRecords)
            .also{it.validate(developerMode)}
    }
}
