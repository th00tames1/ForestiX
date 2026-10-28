package com.hcjeong.forestix.sensors

/** Float32 CPU settings backed by a physical-device benchmark, not core count alone. */
internal object LiveAutoInferencePolicy {
    fun cpuThreads(socModel:String,availableProcessors:Int):Int =
        if(socModel.equals("s5e9945",ignoreCase=true) && availableProcessors>=8)5 else 2
}
