package com.hcjeong.forestix.common

/// AR guide height above the tapped ground, independent of display units
/// and of the constants inside volume and height–diameter equations.
enum class BreastHeightGuideHeight(val raw: String, val meters: Double) {
    METERS_130("1.30", 1.30),
    METERS_137("1.37", Units.BREAST_HEIGHT_M);

    val metricLabel: String get() = "$raw m"
    val imperialLabel: String get() = if (this == METERS_130) "4.27 ft" else "4.5 ft"

    companion object {
        fun fromRaw(raw: String?): BreastHeightGuideHeight =
            entries.firstOrNull { it.raw == raw } ?: METERS_130
    }
}
