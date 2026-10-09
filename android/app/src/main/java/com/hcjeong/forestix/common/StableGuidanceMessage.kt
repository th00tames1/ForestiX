package com.hcjeong.forestix.common

/** Presentation only: capture validity and live edge tracking are never delayed. */
class StableGuidanceMessage(initial: String?, now: Double) {
    var displayed: String? = initial
        private set
    private var candidate = initial
    private var candidateSince = now
    private var displayedSince = now
    fun update(message: String?, now: Double): String? {
        if (now < candidateSince || now < displayedSince) {
            displayed = message; candidate = message
            candidateSince = now; displayedSince = now
        }
        if (message != candidate) { candidate = message; candidateSince = now }
        if (candidate != displayed && now - candidateSince >= 1.0 && now - displayedSince >= 1.5) {
            displayed = candidate; displayedSince = now
        }
        return displayed
    }
}
