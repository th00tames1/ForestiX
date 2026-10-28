package com.hcjeong.forestix.data

import com.hcjeong.forestix.data.fieldstudy.*

/** Same plan is used before preparation and inside the actual Room transaction. */
object QuickCaptureReplacement {
    fun rows(all:List<EntryRow>,row:EntryRow,replacing:Boolean,defaultPlotId:String?):List<EntryRow> =
        if(!replacing||row.treeNumber==null)emptyList()else all.filter{it.id!=row.id&&it.kind==row.kind&&it.treeNumber==row.treeNumber&&
            (it.plotID?:defaultPlotId)==(row.plotID?:defaultPlotId)}
    fun snapshots(rows:List<EntryRow>):List<FieldQuickReplacementSnapshot> = rows.sortedBy{it.id}.map{row->
        val proof=FieldResultSaveIntent.quickProof(row.id,row.kind,row.value,row.measurementId)?:throw FieldStudyException("invalid","Replacement kind")
        FieldQuickReplacementSnapshot(proof,"android.entry_row_json.v1",FieldStudyExportApplication.entryJson(row).toString()).also{it.validate()}}
    fun validate(rows:List<EntryRow>,expected:List<FieldQuickReplacementSnapshot>?){
        if(expected!=null&&snapshots(rows)!=expected)throw FieldStudyException("conflict","Replacement rows changed before save")
    }
}
