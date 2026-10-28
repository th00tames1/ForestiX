package com.hcjeong.forestix.data.fieldstudy

object FieldMeasurementSnapshot {
    fun merge(scanned:List<FieldMeasurementRecord>,current:List<FieldMeasurementRecord>,baselineIds:Set<String>):List<FieldMeasurementRecord>{
        val live=current.associateBy{it.id};val scannedIds=scanned.mapTo(HashSet()){it.id}
        return scanned.map{disk->live[disk.id]?.takeIf{it.revision>disk.revision}?:disk}+
            current.filter{it.id !in baselineIds&&it.id !in scannedIds}
    }
}
