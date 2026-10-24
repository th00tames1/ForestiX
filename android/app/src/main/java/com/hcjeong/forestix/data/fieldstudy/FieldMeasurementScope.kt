package com.hcjeong.forestix.data.fieldstudy

import java.util.Collections
import java.time.Instant

internal fun <T> frozenFieldList(values:Collection<T>):List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <K,V> frozenFieldMap(values:Map<K,V>):Map<K,V> = Collections.unmodifiableMap(LinkedHashMap(values))
fun FieldStudyConfiguration.frozenSnapshot()=copy(referenceMethods=frozenFieldList(referenceMethods),pinnedRuntimeSettings=frozenFieldMap(pinnedRuntimeSettings))
fun FieldStudyTarget.frozenSnapshot()=copy(attributes=frozenFieldMap(attributes))

/** Immutable tree-chain metadata, not an estimator/GPS/camera capture gate.
 * Inputs are defensively copied, including nested mutable collections. */
class FieldMeasurementScope(id:String=newFieldId(),configuration:FieldStudyConfiguration,target:FieldStudyTarget?,
    references:List<FieldReferenceObservation>,val createdAt:String=Instant.now().toString()){
    val id=id
    val configuration=configuration.frozenSnapshot()
    val target=target?.frozenSnapshot()
    val references=frozenFieldList(references.sortedBy{it.id})
    init{validate()}
    fun references(kind:FieldStudyKind)=references.filter{it.method.kind==kind}
    fun validate(){
        configuration.validate();target?.validate()
        fieldRequire(validFieldUuid(id)&&configuration.enabled&&createdAt.isNotEmpty()&&
            (target==null||target.studyId==configuration.studyId)&&references.map{it.id}.toSet().size==references.size,"measurement scope")
        val enabled=configuration.referenceMethods.filter{it.enabled}.map{it.id}.toSet()
        for(r in references){r.validate(true);fieldRequire(r.targetId==target?.id&&r.method.id in enabled&&
            (r.plannedRepeat==null||r.plannedRepeat==configuration.plannedRepeat),"scope reference binding")}
    }
    override fun equals(other:Any?)=other is FieldMeasurementScope&&id==other.id&&configuration==other.configuration&&
        target==other.target&&references==other.references&&createdAt==other.createdAt
    override fun hashCode()=listOf(id,configuration,target,references,createdAt).hashCode()
}
object FieldMeasurementReferences {
    fun visible(scope:FieldMeasurementScope?,kind:FieldStudyKind,developerMode:Boolean):List<FieldReferenceObservation> =
        if(developerMode&&scope?.configuration?.showReferencesDuringMeasurement==true)scope.references(kind)else emptyList()
    fun preview(configuration:FieldStudyConfiguration,targetId:String?,observations:List<FieldReferenceObservation>,kind:FieldStudyKind,developerMode:Boolean):List<FieldReferenceObservation>{
        if(!developerMode||!configuration.enabled||!configuration.showReferencesDuringMeasurement||targetId==null)return emptyList()
        val enabled=configuration.referenceMethods.filter{it.enabled}.map{it.id}.toSet();val superseded=observations.mapNotNull{it.supersedesId}.toSet()
        return observations.filter{it.targetId==targetId&&it.method.kind==kind&&it.method.id in enabled&&it.id !in superseded&&
            (it.plannedRepeat==null||it.plannedRepeat==configuration.plannedRepeat)}.sortedBy{it.id}
    }
}
