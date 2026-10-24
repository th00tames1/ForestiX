package com.hcjeong.forestix.data.fieldstudy

import com.hcjeong.forestix.data.cruise.Tree
import java.util.UUID

object FieldTreeMeasurementIdentity {
    fun prepare(proposed:Tree,previous:Tree?=null):Tree{
        val next=FieldCruiseTreeUpdate.snapshot(proposed)
        for((id,old) in listOf(next.dbhMeasurementId to previous?.dbhMeasurementId,next.heightMeasurementId to previous?.heightMeasurementId))
            if(id!=null&&!sameId(id,old)&&!validFieldUuid(id))throw FieldStudyException("invalid","Inventory measurement UUID")
        if(previous!=null){
            if(dbh(next)!=dbh(previous)&&sameId(next.dbhMeasurementId,previous.dbhMeasurementId))next.dbhMeasurementId=null
            if(height(next)!=height(previous)&&sameId(next.heightMeasurementId,previous.heightMeasurementId))next.heightMeasurementId=null
        }
        return next
    }
    private fun sameId(a:String?,b:String?)=if(a!=null&&b!=null&&validFieldUuid(a)&&validFieldUuid(b))UUID.fromString(a)==UUID.fromString(b)else a==b
    private fun dbh(t:Tree)=listOf(t.dbhCm,t.dbhMethod,t.dbhSigmaMm,t.dbhRmseMm,t.dbhCoverageDeg,t.dbhNInliers,t.dbhConfidence,t.dbhIsIrregular,t.dbhCaptureMode,t.dbhEstimatorEpoch)
    private fun height(t:Tree)=listOf(t.heightM,t.heightMethod,t.heightSource,t.heightSigmaM,t.heightDHM,t.heightAlphaTopDeg,t.heightAlphaBaseDeg,t.heightConfidence)
}
