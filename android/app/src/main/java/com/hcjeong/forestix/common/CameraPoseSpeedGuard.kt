package com.hcjeong.forestix.common

import com.hcjeong.forestix.ar.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/** Keep the policy and tolerances identical to iOS CameraPoseSpeedGuard. */
class CameraPoseSpeedGuard {
    enum class Verdict { USABLE, IGNORED, CHECKING, RETAKE }
    companion object { const val MAXIMUM_SPEED_MPS = 4f }
    private data class Sample(val p: Vec3, val x: Float, val z: Float, val r: Float, val t: Double)
    private var previous: Sample? = null
    private var last: Sample? = null
    private var latestTime: Double? = null
    private var quick = false
    private var suspectRange: Float? = null
    private var suspectError = 0f
    private var witness: Sample? = null
    private var station: Sample? = null
    private var recent: Sample? = null
    private var stationCount = 0
    var state = Verdict.IGNORED
        private set
    fun reset() { last = null; previous = null; latestTime = null; state = Verdict.IGNORED; interrupt() }
    fun interrupt(): Verdict {
        if (state == Verdict.RETAKE) return state
        if (state != Verdict.CHECKING) { quick = previous != null; suspectRange = null }
        else if (witness != null || recent != null) quick = false
        witness = null; station = null; recent = null; stationCount = 0
        state = if (last == null) Verdict.IGNORED else Verdict.CHECKING
        return state
    }
    private fun admit(s: Sample, preceding: Sample?): Verdict {
        previous = preceding; last = s; quick = false; witness = null; suspectRange = null
        station = null; recent = null; stationCount = 0; state = Verdict.USABLE
        return state
    }
    private fun len(x: Float, z: Float) = sqrt(x*x + z*z)
    fun observe(p: Vec3, t: Double, trackedAnchor: Vec3?): Verdict {
        if (state == Verdict.RETAKE) return state
        if (!t.isFinite()) return interrupt()
        val good = last
        val newest = latestTime
        val a = trackedAnchor
        if (newest != null && t-newest < 0.01) {
            if (state == Verdict.CHECKING) return state
            if (t == newest && good != null && good.t == t && good.p == p && a != null &&
                good.x == p.x-a.x && good.z == p.z-a.z) return Verdict.USABLE
            return Verdict.IGNORED
        }
        latestTime = t
        if (a == null || !p.x.isFinite() || !p.y.isFinite() || !p.z.isFinite() ||
            !a.x.isFinite() || !a.y.isFinite() || !a.z.isFinite()) return interrupt()
        val x=p.x-a.x; val z=p.z-a.z; val r=len(x,z)
        if (!r.isFinite()) return interrupt()
        val s=Sample(p,x,z,r,t)
        if (good == null) return admit(s,null)
        val elapsed=t-good.t; val radialDelta=abs(r-good.r)
        val tol=minOf(.10f,maxOf(.025f,.008f*good.r))
        val prev=previous
        if (state != Verdict.CHECKING) {
            val limit=MAXIMUM_SPEED_MPS*elapsed.toFloat()
            var radialImpulse=false
            if (prev!=null && good.t-prev.t<=.30 && elapsed<=.30) {
                val dt=elapsed.toFloat(); val dt0=(good.t-prev.t).toFloat()
                val expectedStep=(good.r-prev.r)*dt/dt0
                val step=r-good.r; val surprise=step-expectedStep
                val floor=maxOf(.50f,.025f*good.r)
                radialImpulse=abs(step)>floor && step*surprise>0 && abs(surprise)>floor+2*dt*(dt+dt0)
            }
            if (elapsed <= .5 && len(x-good.x,z-good.z) <= limit && radialDelta <= limit && !radialImpulse) return admit(s,good)
            quick=false
            if (elapsed <= .30 && prev != null && good.t-prev.t <= .30) {
                quick=true; suspectRange=r
                val rate=(good.r-prev.r)/(good.t-prev.t).toFloat()
                suspectError=r-(good.r+rate*elapsed.toFloat())
            }
            state=Verdict.CHECKING
            return state
        }
        val w=witness
        if (quick && prev != null && elapsed <= .70) {
            val rate=(good.r-prev.r)/(good.t-prev.t).toFloat()
            val from=w ?: good
            val reversed=suspectRange?.let { (r-it)*(if (suspectError>=0) 1f else -1f) <= -.35f*abs(suspectError) } ?: true
            if ((w!=null || suspectRange==null || abs(suspectError)<=tol || reversed) && abs(rate) <= MAXIMUM_SPEED_MPS && abs(r-(good.r+rate*elapsed.toFloat())) <= tol &&
                abs(r-from.r) <= MAXIMUM_SPEED_MPS*(t-from.t).toFloat() && (w==null || t-from.t <= .30)) {
                if (w!=null) return admit(s,w)
                witness=s
            } else { quick=false; witness=null }
        } else { quick=false; witness=null }

        val near=radialDelta <= tol
        val first=station; val rec=recent
        if (first!=null && rec!=null && t-rec.t <= .30 && len(x-first.x,z-first.z) <= .08f &&
            near == (abs(first.r-good.r) <= tol)) {
            stationCount++
            if (near && stationCount>=3 && t-first.t >= .35) return admit(s,rec)
            if (!near && stationCount>=6 && t-first.t >= 2.0) { state=Verdict.RETAKE; return state }
        } else { station=s; stationCount=1 }
        recent=s
        return state
    }
}
