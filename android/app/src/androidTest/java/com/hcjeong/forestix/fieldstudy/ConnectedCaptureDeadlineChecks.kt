package com.hcjeong.forestix.fieldstudy

import android.os.SystemClock
import com.hcjeong.forestix.common.DeadlineWorkExecutor
import com.hcjeong.forestix.common.DeadlineWorkException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*

/** Physical-ARM scheduling only. Never opens a user repository, changes
 * preferences, starts AR/GPS, captures an image or accepts a measurement. */
internal object ConnectedCaptureDeadlineChecks {
    fun run()=runBlocking {
        val worker=DeadlineWorkExecutor();val finish=CountDownLatch(1);val closed=AtomicInteger()
        try {
            val began=SystemClock.elapsedRealtime()
            try{worker.run(100,onFinish={closed.incrementAndGet()}){finish.await();99};error("Late output escaped")}
            catch(_:DeadlineWorkException.TimedOut){}
            check(SystemClock.elapsedRealtime()-began<1000){"Deadline waited for native completion"}
            check(closed.get()==0){"Native-owned input closed early"}
            try{worker.run(1000){1};error("Native jobs overlapped")}
            catch(_:DeadlineWorkException.Busy){}
            finish.countDown();withTimeout(1000){while(closed.get()!=1)delay(5)}
            var recovered=false
            withTimeout(1000){while(!recovered){
                try{check(worker.run(1000){7}==7);recovered=true}
                catch(_:DeadlineWorkException.Busy){delay(5)}
            }}
        } finally{finish.countDown()}
    }
}
