package com.hcjeong.forestix.sensors

import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RawCapturePresentationTest {
    @Test fun recoveryExportDoesNotRequireAReadableManifestAndCannotStartWhileBusy(){
        for(parsed in 0..3){
            val inventory=RawCaptureStore.Inventory(3,parsed)
            assertTrue(RawCaptureExportAvailability.canExportRaw(inventory,false))
            assertFalse(RawCaptureExportAvailability.canExportRaw(inventory,true))
        }
        assertFalse(RawCaptureExportAvailability.canExportRaw(RawCaptureStore.Inventory(0,0),false))
    }
    private fun manifest(id:String?=null,evidence:JsonObject?=null)=buildJsonObject{
        put("kind","dbh");id?.let{put("measurement_id",it)};evidence?.let{put("capture_evidence",it)}
        put("result_live",buildJsonObject{put("value",31.0);put("sigma",1.0);put("tier","green")})}
    private fun evidence(id:String,value:Double?,valid:Boolean)=buildJsonObject{put("measurement_id",id)
        put("displayed_result",buildJsonObject{put("value",value?.let{JsonPrimitive(it)}?:JsonNull);put("valid",valid);put("sigma",.5);put("tier","yellow")})}
    @Test fun actualAndCanonicalStayDistinctAndLegacyDoesNotGetInventedIdentity(){
        val legacy=RawCapturePresentation.from(manifest().toString());assertEquals(31.0,legacy.displayedValue!!,0.0)
        assertTrue(legacy.allowsTruth);assertFalse(legacy.usesExactEvidence)
        val id=UUID.randomUUID().toString();assertNull(RawCapturePresentation.from(manifest(id).toString()).displayedValue)
        val actual=RawCapturePresentation.from(manifest(id,evidence(id,25.0,true)).toString())
        assertEquals(25.0,actual.displayedValue!!,0.0);assertEquals(31.0,actual.canonicalValue!!,0.0)
        assertEquals(.5,actual.sigma!!,0.0);assertEquals("yellow",actual.tier);assertTrue(actual.allowsTruth)
    }
    @Test fun failedMissingZeroMalformedAndMisboundEvidenceCannotBecomeNumericMeasurements(){
        val id=UUID.randomUUID().toString()
        for(value in listOf(null,0.0,25.0)){
            val failed=RawCapturePresentation.from(manifest(id,evidence(id,value,false)).toString())
            assertNull(failed.displayedValue);assertFalse(failed.allowsTruth)
            assertNull(RawCapturePresentation.from(manifest(id,evidence(UUID.randomUUID().toString(),value,true)).toString()).displayedValue)
        }
        assertNull(RawCapturePresentation.from(manifest(id,buildJsonObject{}).toString()).displayedValue)
    }
}
