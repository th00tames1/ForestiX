package com.hcjeong.forestix.common

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Same synthetic contract as Swift. No field, camera or benchmark input. */
class AutoRecoveryPolicyTest {
    private fun contract(): JsonObject = javaClass.classLoader!!
        .getResourceAsStream("auto-recovery-v1.json")!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
    @Test fun bothPlatformsShareRecoveryLimitsAndMessages() {
        val c = contract()
        assertEquals(1, c.getValue("schema").jsonPrimitive.int)
        fun seconds(name: String) = c.getValue(name).jsonPrimitive.double
        assertEquals(seconds("live_error_retry_seconds"), AutoSafetyPolicy.LIVE_ERROR_RETRY_MILLIS / 1000.0, 0.0)
        assertEquals(seconds("paired_input_wait_seconds"), AutoSafetyPolicy.PAIRED_INPUT_WAIT_MILLIS / 1000.0, 0.0)
        assertEquals(seconds("paired_input_poll_seconds"), AutoSafetyPolicy.PAIRED_INPUT_POLL_MILLIS / 1000.0, 0.0)
        assertEquals(seconds("capture_processing_seconds"), AutoSafetyPolicy.CAPTURE_PROCESSING_MILLIS / 1000.0, 0.0)
        assertEquals(seconds("auto_maximum_seconds"), AutoSafetyPolicy.MAXIMUM_SECONDS, 0.0)
        assertEquals(c.getValue("messages").jsonObject.mapValues { it.value.jsonPrimitive.content }, mapOf(
            "live_retry" to AutoSafetyPolicy.LIVE_RETRY_MESSAGE,
            "input" to AutoSafetyPolicy.CAPTURE_INPUT_MESSAGE,
            "changed" to AutoSafetyPolicy.CAPTURE_CHANGED_MESSAGE,
            "no_stem" to AutoSafetyPolicy.CAPTURE_NO_STEM_MESSAGE,
            "invalid_depth" to AutoSafetyPolicy.CAPTURE_INVALID_DEPTH_MESSAGE,
            "inference" to AutoSafetyPolicy.CAPTURE_INFERENCE_MESSAGE,
            "timeout" to AutoSafetyPolicy.CAPTURE_TIMEOUT_MESSAGE,
            "busy" to AutoSafetyPolicy.CAPTURE_BUSY_MESSAGE
        ))
        assertEquals(AutoSafetyPolicy.CAPTURE_BUSY_MESSAGE, DeadlineWorkException.Busy().message)
        assertEquals(AutoSafetyPolicy.CAPTURE_TIMEOUT_MESSAGE, DeadlineWorkException.TimedOut().message)
    }
    @Test fun errorsBackOffWithoutChangingHealthyCadenceOrMissingStemBehaviour() {
        for (element in contract().getValue("delays").jsonArray) {
            val row = element.jsonObject
            val cadence = (row.getValue("cadence_seconds").jsonPrimitive.double * 1000).toLong()
            val expected = (row.getValue("expected_seconds").jsonPrimitive.double * 1000).toLong()
            assertEquals(row.getValue("name").jsonPrimitive.content, expected,
                AutoSafetyPolicy.liveInferenceDelayMillis(cadence, row.getValue("failed").jsonPrimitive.boolean))
        }
    }
    @Test fun invalidCadenceCannotCreateAnErrorSpinLoop() {
        for (invalid in listOf(-1L, Long.MIN_VALUE)) for (failed in listOf(false, true)) {
            assertEquals(750L, AutoSafetyPolicy.liveInferenceDelayMillis(invalid, failed))
        }
    }
}
