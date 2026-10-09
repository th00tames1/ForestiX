package com.hcjeong.forestix.ui.screens.dbh

import kotlin.math.abs
import kotlin.math.sin

internal data class DepthMotionHintOffset(val xDp: Float, val yDp: Float)

/** One horizontal cycle followed by one vertical cycle; never a phone rotation. */
internal fun depthMotionHintOffset(phase: Float): DepthMotionHintOffset {
    val progress = phase.coerceIn(0f, 2f)
    val vertical = progress >= 1f
    val cycle = if (vertical) progress - 1f else progress
    val sine = sin(2.0 * Math.PI * cycle).toFloat()
    // Ease to rest at the centre so switching axes (or restarting) does not snap.
    val sway = sine * abs(sine)
    return if (vertical) DepthMotionHintOffset(0f, 4f * sway)
    else DepthMotionHintOffset(6f * sway, 0f)
}
