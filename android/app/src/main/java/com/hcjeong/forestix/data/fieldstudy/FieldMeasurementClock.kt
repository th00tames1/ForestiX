package com.hcjeong.forestix.data.fieldstudy

/** Callers supply elapsedRealtimeNanos / 1e9 on Android, never wall-clock time. */
class FieldMeasurementClock(now:Double) {
    private val started=if(now.isFinite()&&now>=0)now else 0.0
    private var last=started
    private var backgroundStarted:Double?=null
    private var accumulatedBackground=0.0
    private var attemptStarted:Double?=null
    private var attemptBackgroundAtStart=0.0
    private var firstResult:Double?=null
    private var attemptFirstResult:Double?=null
    private var accepted:Double?=null
    private var valid=now.isFinite()&&now>=0
    private fun advance(now:Double):Boolean {
        if(!now.isFinite()||now<last){valid=false;return false};last=now;return true
    }
    private fun totalBackground(now:Double)=accumulatedBackground+(backgroundStarted?.let{maxOf(0.0,now-it)}?:0.0)
    fun setForeground(foreground:Boolean,now:Double){
        if(!advance(now))return
        if(foreground){backgroundStarted?.let{accumulatedBackground+=now-it;backgroundStarted=null}}
        else if(backgroundStarted==null)backgroundStarted=now
    }
    fun beginAttempt(now:Double){
        if(!advance(now))return
        attemptStarted=now;attemptBackgroundAtStart=totalBackground(now);attemptFirstResult=null;accepted=null
    }
    fun noteResult(validResult:Boolean,now:Double){
        if(!advance(now)||!validResult)return
        if(firstResult==null)firstResult=now-started
        if(attemptFirstResult==null)attemptStarted?.let{attemptFirstResult=now-it}
    }
    fun noteAccepted(now:Double){if(advance(now)&&accepted==null)accepted=now-started}
    fun snapshot(now:Double):FieldTimingSnapshot {
        advance(now)
        val elapsed=last-started;val bg=minOf(elapsed,totalBackground(last))
        val ae=attemptStarted?.let{last-it};val active=ae?.let{maxOf(0.0,it-(bg-attemptBackgroundAtStart))}
        return FieldTimingSnapshot(elapsed,maxOf(0.0,elapsed-bg),bg,ae,active,firstResult,attemptFirstResult,accepted,valid)
    }
}
