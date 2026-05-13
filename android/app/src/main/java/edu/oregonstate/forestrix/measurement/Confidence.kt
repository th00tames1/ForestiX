package edu.oregonstate.forestrix.measurement

enum class ConfidenceTier {
    GREEN,
    YELLOW,
    RED;

    val displayName: String
        get() = when (this) {
            GREEN -> "Green"
            YELLOW -> "Yellow"
            RED -> "Red"
        }
}

enum class Severity { REJECT, WARN }

data class Check(
    val passed: Boolean,
    val severity: Severity,
    val reason: String
)

fun check(passed: Boolean, severity: Severity, reason: String): Check =
    Check(passed = passed, severity = severity, reason = reason)

fun combineChecks(checks: List<Check>): ConfidenceTier {
    if (checks.any { !it.passed && it.severity == Severity.REJECT }) {
        return ConfidenceTier.RED
    }
    val warnCount = checks.count { !it.passed && it.severity == Severity.WARN }
    return when {
        warnCount >= 2 -> ConfidenceTier.RED
        warnCount == 1 -> ConfidenceTier.YELLOW
        else -> ConfidenceTier.GREEN
    }
}

fun firstRejectReason(checks: List<Check>): String? =
    checks.firstOrNull { !it.passed && it.severity == Severity.REJECT }?.reason
