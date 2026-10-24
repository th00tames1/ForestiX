package com.hcjeong.forestix.sensors

/** Single worker only: fast target 16 Hz, the original 8 Hz when thermally limited. */
object LiveAutoCadence {
    const val IDLE_MILLIS = 62L
    fun delayMillis(elapsedSeconds:Double,thermallyLimited:Boolean=false):Long {
        val period=if(thermallyLimited)0.125 else 0.0625
        if(!elapsedSeconds.isFinite() || elapsedSeconds<0)return (period*1000).toLong()
        return maxOf(if(thermallyLimited)20L else 2L,((period-elapsedSeconds)*1000).toLong())
    }
}
