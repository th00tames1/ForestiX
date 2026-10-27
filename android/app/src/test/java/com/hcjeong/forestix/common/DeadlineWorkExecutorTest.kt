package com.hcjeong.forestix.common

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeadlineWorkExecutorTest {
    @Test fun successAndErrorsAreUnchanged()=runBlocking {
        val worker=DeadlineWorkExecutor()
        assertEquals(42,worker.run(1000){42})
        try { worker.run<Int>(1000){error("native failure")};fail("Error swallowed") }
        catch(error:IllegalStateException){assertEquals("native failure",error.message)}
    }
    @Test fun timeoutReturnsWhileNativeStillOwnsLeaseAndLateOutputIsDiscarded()=runBlocking {
        val worker=DeadlineWorkExecutor();val finish=CountDownLatch(1);val began=CountDownLatch(1)
        val closed=AtomicInteger()
        try {
            val start=System.nanoTime()
            try { worker.run(40,onFinish={closed.incrementAndGet()}){began.countDown();finish.await();99};fail("Late output escaped") }
            catch(_:DeadlineWorkException.TimedOut){}
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<700)
            assertEquals(0L,began.count);assertEquals(0,closed.get())
            try { worker.run(1000){1};fail("Overlapping operation queued") }
            catch(_:DeadlineWorkException.Busy){}
            finish.countDown()
            withTimeout(1000){while(closed.get()!=1)delay(2)}
            var value:Int?=null
            withTimeout(1000){while(value==null){try{value=worker.run(1000){7}}catch(_:DeadlineWorkException.Busy){delay(2)}}}
            assertEquals(7,value)
        } finally {finish.countDown()}
    }
    @Test fun cancellationIsPromptAndDoesNotCloseNativeInputEarly()=runBlocking {
        val worker=DeadlineWorkExecutor();val finish=CountDownLatch(1);val began=CountDownLatch(1);val closed=AtomicInteger()
        val job=launch {worker.run(10000,onFinish={closed.incrementAndGet()}){began.countDown();finish.await();99}}
        withTimeout(1000){while(began.count!=0L)delay(2)}
        job.cancel();withTimeout(500){job.join()}
        assertEquals(0,closed.get())
        try {worker.run(1000){1};fail("Cancelled native operation overlapped")}catch(_:DeadlineWorkException.Busy){}
        finish.countDown();withTimeout(1000){while(closed.get()!=1)delay(2)}
        assertEquals(1,closed.get())
    }
    @Test fun busyLiveInferenceIsRetriedAndItsDeadlineStillApplies()=runBlocking {
        val gate=SingleFlightGate();assertTrue(gate.acquire())
        val release=launch {delay(25);gate.release()}
        assertEquals(23,DeadlineWorkExecutor().run(1000){work->work.retryBusy{gate.run{23}}})
        release.join()
        try { DeadlineWorkExecutor().run<Int>(30){work->work.retryBusy{throw DeadlineWorkException.Busy()}};fail("Busy wait escaped deadline") }
        catch(_:DeadlineWorkException.TimedOut){}
    }
}
