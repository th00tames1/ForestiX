package com.hcjeong.forestix.common

object HeightSampleFreshness {
    fun isRecent(ageSeconds: Double): Boolean = ageSeconds.isFinite() && ageSeconds in 0.0..0.5
}
