// Confidence framework — 1:1 port of iOS Common/ConfidenceTier.swift
// (spec §6.1 + §7.9). Shared by the height + DBH estimators so Android
// produces the same green/yellow/red tiers as iOS.

package com.hcjeong.forestix.sensors

enum class ConfidenceTier(val raw: String) { GREEN("green"), YELLOW("yellow"), RED("red") }

enum class Severity { REJECT, WARN }

data class Check(val passed: Boolean, val severity: Severity, val reason: String)

fun check(passed: Boolean, sev: Severity, reason: String = "") = Check(passed, sev, reason)

/// §7.9 combine rule:
///   any failed REJECT  -> RED
///   >= 2 failed WARN    -> RED
///   >= 1 failed WARN    -> YELLOW
///   else                -> GREEN
fun combineChecks(checks: List<Check>): ConfidenceTier {
    if (checks.any { !it.passed && it.severity == Severity.REJECT }) return ConfidenceTier.RED
    val warnFails = checks.count { !it.passed && it.severity == Severity.WARN }
    return when {
        warnFails >= 2 -> ConfidenceTier.RED
        warnFails >= 1 -> ConfidenceTier.YELLOW
        else -> ConfidenceTier.GREEN
    }
}
