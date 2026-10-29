package com.hcjeong.forestix.common

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resumeWithException

sealed class DeadlineWorkException(message:String):IllegalStateException(message) {
    class Busy:DeadlineWorkException(AutoSafetyPolicy.CAPTURE_BUSY_MESSAGE)
    class TimedOut:DeadlineWorkException(AutoSafetyPolicy.CAPTURE_TIMEOUT_MESSAGE)
}

class SingleFlightGate {
    private val occupied=AtomicBoolean(false)
    fun acquire()=occupied.compareAndSet(false,true)
    fun release(){occupied.set(false)}
    fun <T> run(operation:()->T):T {
        if(!acquire())throw DeadlineWorkException.Busy()
        try{return operation()}finally{release()}
    }
}

/** A timed-out native call keeps its lease until it actually finishes.
 * The caller resumes promptly; late output is discarded, never saved. */
class DeadlineWorkExecutor {
    companion object {
        private val workers=Executors.newCachedThreadPool { job -> Thread(job,"forestix-capture").apply{isDaemon=true} }
        private val timer=ScheduledThreadPoolExecutor(1) { job -> Thread(job,"forestix-capture-deadline").apply{isDaemon=true} }
            .apply{removeOnCancelPolicy=true}
    }
    private val gate=SingleFlightGate()
    class Context(private val active:()->Boolean) {
        fun checkCancellation(){if(!active())throw kotlinx.coroutines.CancellationException("Capture no longer active")}
        fun <T> retryBusy(operation:()->T):T {
            while(true){
                checkCancellation()
                try{return operation()}catch(_:DeadlineWorkException.Busy){Thread.sleep(10)}
            }
        }
    }
    suspend fun <T> run(timeoutMillis:Long,onFinish:()->Unit={},operation:(Context)->T):T {
        if(!gate.acquire()){
            onFinish();throw DeadlineWorkException.Busy()
        }
        return suspendCancellableCoroutine { continuation ->
            val delivered=AtomicBoolean(false)
            val deadline=AtomicReference<java.util.concurrent.ScheduledFuture<*>?>()
            continuation.invokeOnCancellation { delivered.set(true);deadline.get()?.cancel(false) }
            deadline.set(timer.schedule({
                if(delivered.compareAndSet(false,true))continuation.resumeWithException(DeadlineWorkException.TimedOut())
            },maxOf(1,timeoutMillis),TimeUnit.MILLISECONDS))
            if(!continuation.isActive)deadline.get()?.cancel(false)
            workers.execute {
                var result=try { Result.success(operation(Context{continuation.isActive&&!delivered.get()})) }
                    catch(error:Throwable){Result.failure(error)}
                try { onFinish() } catch(error:Throwable){result=Result.failure(error)}
                gate.release();deadline.get()?.cancel(false)
                if(delivered.compareAndSet(false,true))continuation.resumeWith(result)
            }
        }
    }
}
