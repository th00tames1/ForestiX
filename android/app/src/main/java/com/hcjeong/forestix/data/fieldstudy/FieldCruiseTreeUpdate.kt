package com.hcjeong.forestix.data.fieldstudy

import com.hcjeong.forestix.data.cruise.Tree

object FieldCruiseTreeUpdate {
    fun snapshot(tree:Tree)=tree.copy(damageCodes=tree.damageCodes.toList())
    /** DBH replacement preserves all unrelated inventory metadata. No estimator math. */
    fun dbh(previous:Tree,measurement:Tree,speciesOverride:String?):Tree {
        if(previous.plotId!=measurement.plotId||previous.deletedAt!=null)throw FieldStudyException("conflict","Inventory scope changed")
        val notes=when{measurement.notes.isEmpty()||measurement.notes==previous.notes->previous.notes
            previous.notes.isEmpty()->measurement.notes;else->previous.notes+"\n"+measurement.notes}
        val codes=previous.damageCodes.toMutableList();measurement.damageCodes.forEach{if(it !in codes)codes.add(it)}
        val noGps=previous.latitude==null&&previous.longitude==null
        val noLocal=previous.bearingFromCenterDeg==null&&previous.distanceFromCenterM==null
        return previous.copy(dbhCm=measurement.dbhCm,dbhMethod=measurement.dbhMethod,dbhSigmaMm=measurement.dbhSigmaMm,
            dbhRmseMm=measurement.dbhRmseMm,dbhCoverageDeg=measurement.dbhCoverageDeg,dbhNInliers=measurement.dbhNInliers,
            dbhConfidence=measurement.dbhConfidence,dbhIsIrregular=measurement.dbhIsIrregular,
            dbhCaptureMode=measurement.dbhCaptureMode,dbhEstimatorEpoch=measurement.dbhEstimatorEpoch,
            dbhMeasurementId=measurement.dbhMeasurementId,
            speciesCode=speciesOverride?:previous.speciesCode,damageCodes=codes.toList(),notes=notes,
            latitude=if(noGps)measurement.latitude else previous.latitude,longitude=if(noGps)measurement.longitude else previous.longitude,
            bearingFromCenterDeg=if(noLocal)measurement.bearingFromCenterDeg else previous.bearingFromCenterDeg,
            distanceFromCenterM=if(noLocal)measurement.distanceFromCenterM else previous.distanceFromCenterM,
            photoPath=measurement.photoPath?:previous.photoPath,rawScanPath=measurement.rawScanPath,updatedAt=measurement.updatedAt)
    }
}
