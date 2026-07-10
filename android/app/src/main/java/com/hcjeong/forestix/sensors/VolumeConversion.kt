// Tree-volume conversion — DBH + height -> board feet (Scribner /
// International 1/4" / Doyle). Direct port of iOS Sensors/VolumeConversion.swift.
// Storage is DBH cm + height m; volume math runs in inches + feet.

package com.hcjeong.forestix.sensors

import kotlin.math.floor

enum class LogRule(val displayName: String) {
    SCRIBNER("Scribner Decimal C"),
    INTERNATIONAL("International \u00BC\u2033"),
    DOYLE("Doyle");

    companion object {
        fun fromRaw(raw: String): LogRule =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SCRIBNER
    }
}

object VolumeConversion {

    /// Conventional spec foot for all three rules.
    const val LOG_LENGTH_FT = 16.0

    /// Board-foot volume of a tree from DBH (cm) + total height (m).
    /// Merchantable height = totalHeight * merchantableRatio (default 0.6).
    /// Returns null for trees too small to bother with.
    fun boardFeet(
        dbhCm: Double,
        totalHeightM: Double,
        rule: LogRule,
        merchantableRatio: Double = 0.6,
    ): Double? {
        val dbhIn = dbhCm / 2.54
        val totalFt = totalHeightM * 3.28084
        val merchFt = totalFt * merchantableRatio
        if (dbhIn < 4.0 || merchFt < 8.0) return null

        val nLogsExact = merchFt / LOG_LENGTH_FT
        val fullLogs = floor(nLogsExact).toInt()
        val lastLogLen = (nLogsExact - fullLogs) * LOG_LENGTH_FT

        var totalBF = 0.0
        for (i in 0 until fullLogs) {
            val topDiamIn = topDiameter(dbhIn, (i + 1) / nLogsExact)
            val baseDiamIn = topDiameter(dbhIn, i / nLogsExact)
            val dIn = (baseDiamIn + topDiamIn) / 2
            totalBF += boardFeetPerLog(dIn, LOG_LENGTH_FT, rule)
        }
        if (lastLogLen >= 8.0) {
            val topDiamIn = topDiameter(dbhIn, 1.0)
            val baseDiamIn = topDiameter(dbhIn, fullLogs / nLogsExact)
            val dIn = (baseDiamIn + topDiamIn) / 2
            totalBF += boardFeetPerLog(dIn, lastLogLen, rule)
        }
        return maxOf(0.0, totalBF)
    }

    fun boardFeetPerLog(smallEndDIn: Double, lengthFt: Double, rule: LogRule): Double {
        val d = smallEndDIn
        val l = lengthFt
        return when (rule) {
            LogRule.SCRIBNER ->
                maxOf(0.0, ((0.79 * d * d - 2 * d - 4) / 16.0) * l)
            LogRule.INTERNATIONAL -> {
                val perFoot = (0.04976 * d * d - 1.86 * d) / 16.0
                maxOf(0.0, perFoot * l)
            }
            LogRule.DOYLE -> {
                val small = maxOf(0.0, d - 4)
                (small * small / 16.0) * l
            }
        }
    }

    private fun topDiameter(dbhIn: Double, positionAlongStem: Double): Double {
        val p = positionAlongStem.coerceIn(0.0, 1.0)
        return dbhIn * (1.0 - 0.6 * p)
    }
}
