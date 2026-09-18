package com.hcjeong.forestix.common

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/// Screen coordinates are dp, matching iOS points. Off-screen heights are
/// hidden rather than clamped to a different position on the screen.
data class BreastHeightMarkerLayout(
    val leftTick: ClosedFloatingPointRange<Float>?,
    val rightTick: ClosedFloatingPointRange<Float>?,
    val labelCenterX: Float?,
    val y: Float,
) {
    companion object {
        const val LABEL_WIDTH = 64f
        const val LABEL_HEIGHT = 22f

        fun make(x: Float, y: Float, width: Float, height: Float,
                 stemLeft: Float? = null, stemRight: Float? = null): BreastHeightMarkerLayout? {
            if (!listOf(x, y, width, height).all { it.isFinite() } ||
                width <= 24 || height <= 24 || x < 0 || x > width ||
                y < 12 || y > height - 12) return null
            var left = x - 24
            var right = x + 24
            if (stemLeft != null && stemRight != null && stemLeft.isFinite() &&
                stemRight.isFinite() && stemLeft < stemRight && x in stemLeft..stemRight &&
                abs(y - height / 2) <= 44) {
                left = min(left, stemLeft - 8)
                right = max(right, stemRight + 8)
            }
            val l = if (left - 12 >= 12) (left - 12)..left else null
            val r = if (right + 12 <= width - 12) right..(right + 12) else null
            if (l == null && r == null) return null
            val label = when {
                r != null && r.endInclusive + 6 + LABEL_WIDTH <= width - 12 ->
                    r.endInclusive + 6 + LABEL_WIDTH / 2
                l != null && l.start - 6 - LABEL_WIDTH >= 12 ->
                    l.start - 6 - LABEL_WIDTH / 2
                else -> null
            }
            return BreastHeightMarkerLayout(l, r, label, y)
        }
    }
}
