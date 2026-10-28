package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

/** Synthetic application masks only; compares the frozen pre-optimization decoder. */
class OptimizedMaskSelectionTest {
    private fun fixture(zero:Boolean=false):Pair<FloatArray,FloatArray> {
        val head=FloatArray(37*8400)
        for(i in 0 until 9){
            head[i]=320f+(i%3-1)*10;head[8400+i]=320f
            head[2*8400+i]=20f+i*40;head[3*8400+i]=200f+i*50
            head[4*8400+i]=.8f-i*.01f
            for(c in 0 until 32)head[(5+c)*8400+i]=if(c==0)1f else ((c+i)%7-3)*.03f
        }
        val proto=FloatArray(32*25600){i->if(zero)0f else if(i<25600)1f else ((i*73+19)%251-125)/256f}
        return head to proto
    }
    private fun compare(head:FloatArray,proto:FloatArray,w:Int,h:Int){
        val old=FrozenYoloAlignmentMask.select(head,proto,w,h)
        val now=YoloAlignmentMask.select(head,proto,w,h)
        assertEquals(old==null,now==null)
        if(old!=null && now!=null){
            assertEquals(old.score,now.score,0f)
            fun logits(mask:Any):FloatArray=mask.javaClass.getDeclaredField("logits").let{it.isAccessible=true;it.get(mask) as FloatArray}
            val oldValues=logits(old);val newValues=logits(now)
            assertArrayEquals(IntArray(25600){oldValues[it].toRawBits()},IntArray(25600){newValues[it].toRawBits()})
            for(y in 0 until h step maxOf(1,h/96))for(x in 0 until w step maxOf(1,w/96))assertEquals(old.contains(x,y),now.contains(x,y))
        }
    }
    @Test fun selectedMaskAndEveryLogitRemainBitExactAcrossOrientations(){
        val(head,proto)=fixture()
        for((w,h)in listOf(96 to 160,160 to 96,127 to 191,640 to 640,1 to 1))compare(head,proto,w,h)
    }
    @Test fun zeroLogitsAndMissingTargetsRemainMissing(){
        val(head,proto)=fixture(true);compare(head,proto,96,160)
        head.fill(0f);compare(head,proto,96,160)
    }
    @Test fun invalidAndClippedCandidateHandlingIsUnchanged(){
        val(head,proto)=fixture()
        head[0]=Float.NaN;head[1]=Float.POSITIVE_INFINITY;head[2*8400+2]=-1f
        head[2*8400+3]=1500f;head[3*8400+3]=1500f
        compare(head,proto,96,160)
    }
}
