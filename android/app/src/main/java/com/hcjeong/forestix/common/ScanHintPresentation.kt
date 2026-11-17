package com.hcjeong.forestix.common

/** Introductions only. Capture errors and tracking warnings must bypass this gate. */
class ScanHintPresentation {
    companion object { const val DURATION_MS = 4_000L }
    private val seen = mutableSetOf<String>()
    var visibleKey: String? = null
        private set
    fun begin(key: String?): Boolean {
        visibleKey = null
        if (key == null || !seen.add(key)) return false
        visibleKey = key
        return true
    }
    fun dismiss(key: String?) { if (visibleKey == key) visibleKey = null }
    fun isVisible(key: String?) = key != null && key == visibleKey
}
