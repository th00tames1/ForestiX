package com.hcjeong.forestix.sensors

import org.junit.Assert.*
import org.junit.Test

class YoloTrunkMaskTest {
    private fun head(vararg boxes:FloatArray):FloatArray {
        val n=8400;val d=FloatArray(37*n)
        for((i,b) in boxes.withIndex()) { d[i]=(b[1]+b[3])/2;d[n+i]=(b[2]+b[4])/2;d[2*n+i]=b[3]-b[1];d[3*n+i]=b[4]-b[2];d[4*n+i]=b[0];d[5*n+i]=1f }
        return d
    }
    private fun prototype(first:(Int)->Float={1f}):FloatArray = FloatArray(32*25600){if(it<25600)first(it) else 0f}
    @Test fun oneClassPortraitLetterbox() {
        val m=requireNotNull(YoloAlignmentMask.select(head(floatArrayOf(.8f,200f,80f,440f,560f)),prototype(),200,400))
        assertEquals(.8f,m.score,0f);assertTrue(m.contains(25,50));assertTrue(m.contains(174,349))
        assertFalse(m.contains(24,50));assertFalse(m.contains(175,349))
    }
    @Test fun centralTargetRatherThanHighestConfidenceBackground() {
        val m=requireNotNull(YoloAlignmentMask.select(head(floatArrayOf(.95f,0f,0f,150f,640f),floatArrayOf(.7f,240f,0f,400f,640f)),prototype(),640,640))
        assertEquals(.7f,m.score,0f);assertTrue(m.contains(320,320));assertFalse(m.contains(100,320))
    }
    @Test fun resizedLogitsAndSafeFallback() {
        val m=requireNotNull(YoloAlignmentMask.select(head(floatArrayOf(.8f,0f,0f,640f,640f)),prototype { if(it%160<80)-1f else 1f },640,640))
        assertFalse(m.contains(319,320));assertTrue(m.contains(320,320))
        assertNull(YoloAlignmentMask.select(head(floatArrayOf(.9f,0f,0f,100f,640f)),prototype(),640,640))
        assertNull(YoloAlignmentMask.select(floatArrayOf(),floatArrayOf(),640,640))
    }
}
