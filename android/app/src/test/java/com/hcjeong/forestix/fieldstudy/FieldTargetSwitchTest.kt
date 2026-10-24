package com.hcjeong.forestix.fieldstudy

import com.hcjeong.forestix.data.fieldstudy.*
import com.hcjeong.forestix.ui.PendingTreeNumber
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FieldTargetSwitchTest {
    @Test fun confirmationDismissalDoesNotCancelConfirmedSwitch()=runBlocking<Unit>{
        val switcher=FieldTargetSwitchCoordinator(this);var ran=0
        switcher.request("a","0003",true){ran++}
        assertEquals("0003",switcher.pending.value?.label);assertFalse(switcher.busy.value);assertEquals(0,ran)
        switcher.dismissConfirmation();switcher.confirm();assertEquals(0,ran)
        switcher.request("a","0003",true){ran++};switcher.confirm();assertTrue(switcher.busy.value)
        switcher.dismissConfirmation();switcher.waitForCurrentSwitch()
        assertEquals(1,ran);assertFalse(switcher.busy.value);assertNull(switcher.pending.value);assertNull(switcher.error.value)
    }
    @Test fun cancelledObsoleteCompletionCannotApplyOrClearLaterSwitch()=runBlocking<Unit>{
        val switcher=FieldTargetSwitchCoordinator(this);val events=ArrayList<String>()
        val first=CompletableDeferred<Unit>();val second=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        switcher.request("a","003",false){withContext(NonCancellable){entered.complete(Unit);first.await()};currentCoroutineContext().ensureActive();events.add("a")}
        entered.await();switcher.request("ignored","duplicate",false){events.add("ignored")}
        switcher.cancel();assertFalse(switcher.busy.value)
        switcher.request("b","004",false){second.await();currentCoroutineContext().ensureActive();events.add("b")}
        first.complete(Unit);repeat(10){yield()}
        assertTrue(switcher.busy.value);assertTrue(events.isEmpty());assertNull(switcher.error.value)
        second.complete(Unit);switcher.waitForCurrentSwitch()
        assertEquals(listOf("b"),events);assertFalse(switcher.busy.value)
    }
    @Test fun preparationFailureReportsAndRetryCanSucceed()=runBlocking<Unit>{
        val switcher=FieldTargetSwitchCoordinator(this);var ran=0
        switcher.request("a","003",false){throw FieldStudyException("missing","Synthetic target")}
        switcher.waitForCurrentSwitch();assertNotNull(switcher.error.value);assertFalse(switcher.busy.value)
        switcher.request("b","004",false){ran++};switcher.waitForCurrentSwitch()
        assertEquals(1,ran);assertNull(switcher.error.value)
    }
    @Test fun failedHandoffCanClearOnlyItsOwnPayloadAndNeverCarriesPriorTruth(){
        PendingTreeNumber.consume()
        try{
            val a=FieldStudyTarget("SYNTHETIC","0003","003");val b=FieldStudyTarget("SYNTHETIC","0004","004")
            val old=PendingTreeNumber.stage(PendingTreeNumber.Lock(number=7,fieldTarget=a,truth=25.0,replaceExisting=true))
            val next=PendingTreeNumber.stage(PendingTreeNumber.Lock(number=8,name="0004",fieldTarget=b))
            PendingTreeNumber.clearStaged(old)
            val actual=requireNotNull(PendingTreeNumber.consume())
            assertSame(next,actual);assertEquals(b,actual.fieldTarget);assertNull(actual.truth);assertFalse(actual.replaceExisting)
            assertEquals(8,actual.number);assertEquals("0004",actual.name)
            val staged=PendingTreeNumber.stage(next);PendingTreeNumber.clearStaged(staged);assertNull(PendingTreeNumber.consume())
        }finally{PendingTreeNumber.consume()}
    }
}
