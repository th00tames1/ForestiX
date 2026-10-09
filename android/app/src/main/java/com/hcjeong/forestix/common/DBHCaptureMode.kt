package com.hcjeong.forestix.common

/** Committed measurement only; live preview remains continuous. */
enum class DBHCaptureMode(val raw: String) {
    SINGLE("single"), MULTI_5("multi5");

    fun frameCount(developerMode: Boolean): Int =
        if (developerMode && this == MULTI_5) 5 else 1

    companion object {
        fun fromRaw(raw: String?): DBHCaptureMode =
            entries.firstOrNull { it.raw == raw } ?: SINGLE
    }
}
