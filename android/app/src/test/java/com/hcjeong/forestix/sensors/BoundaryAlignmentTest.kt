package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import kotlinx.serialization.json.*
import java.io.File

class BoundaryAlignmentTest {
    private fun grid() = BoundaryAlignment.Grid(100,100,50,DoubleArray(10000){1.0},30.0,70.0,100.0)
    @Test fun unsupportedMaskPreservesManualPlacement() {
        val g=grid()
        val r=BoundaryAlignment.correct(g,4.0,{_,_->false},{listOf(28.0 to 72.0)})!!
        assertEquals(30.0,r.left,0.0);assertEquals(70.0,r.right,0.0)
        assertEquals("mask_core_gap",r.leftReason)
    }
    @Test fun partialAdjustmentIsAppliedOnceFromOriginalGuides() {
        val g=grid()
        val r=BoundaryAlignment.correct(g,4.0,{_,_->true},{listOf(28.0 to 72.0)})!!
        assertEquals(29.0,r.left,1e-10);assertEquals(71.0,r.right,1e-10)
        assertEquals(BoundaryAlignment.diameter(g,29.0,71.0)!!.first,r.diameterCm,1e-10)
    }
    @Test fun nearForegroundRetainsBothSides() {
        val g=grid();for(y in 48..52)for(x in 40..60)g.depth[y*100+x]=0.5
        val r=BoundaryAlignment.correct(g,4.0,{_,_->true},{listOf(28.0 to 72.0)})!!
        assertEquals("depth_occlusion",r.leftReason);assertEquals(30.0,r.left,0.0)
    }
    @Test fun optionalPrivateReplayMatchesAnalysis() {
        val path=System.getenv("FORESTIX_NATIVE_CASES");assumeTrue(path!=null)
        var count=0
        File(path!!).forEachLine { line -> count++
            val c=Json.parseToJsonElement(line).jsonObject
            fun num(k:String)=c.getValue(k).jsonPrimitive.double
            fun ints(k:String)=c.getValue(k).jsonPrimitive.int
            val w=ints("width");val h=ints("height")
            val g=BoundaryAlignment.Grid(w,h,ints("row"),c.getValue("depth").jsonArray.map{it.jsonPrimitive.double}.toDoubleArray(),num("left"),num("right"),num("focal"))
            val mask=c.getValue("mask").jsonArray;val segments=c.getValue("segments").jsonArray
            val r=BoundaryAlignment.correct(g,num("scale"),{x,y->mask[y*w+x].jsonPrimitive.int>0},{y->segments[y].jsonArray.map{val p=it.jsonArray;p[0].jsonPrimitive.double to p[1].jsonPrimitive.double}})!!
            assertEquals(c.getValue("key").jsonPrimitive.content,num("expected"),r.diameterCm,1e-8)
        }
        assertEquals(199,count)
    }
}
