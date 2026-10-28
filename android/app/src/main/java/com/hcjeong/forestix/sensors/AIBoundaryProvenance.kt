package com.hcjeong.forestix.sensors

import com.hcjeong.forestix.data.fieldstudy.FieldAIProvenance

object AIBoundaryProvenance {
    fun snapshot(enabled:Boolean,usingBracket:Boolean,lastInferenceId:String?,lastHadMask:Boolean,boundaryInferenceId:String?,userEdited:Boolean):FieldAIProvenance {
        val applied=usingBracket&&boundaryInferenceId!=null&&!userEdited
        return FieldAIProvenance(enabled=enabled,inferenceRan=lastInferenceId!=null||boundaryInferenceId!=null,
            maskDetected=applied||lastInferenceId!=null&&lastHadMask,boundaryApplied=applied,userAdjustedBoundary=userEdited,
            inferenceId=if(applied)boundaryInferenceId else lastInferenceId)
    }
}
