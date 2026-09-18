package com.hcjeong.forestix.ui.screens.dbh

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp

internal const val DEPTH_MOTION_HINT = "Move phone slowly side to side"

/** Compact replacement for the top no-lock banner; no touch handlers. */
@Composable
internal fun DepthMotionHint(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "depthMotionHint")
    val shift by motion.animateFloat(-6f, 6f,
        animationSpec = infiniteRepeatable(tween(950), RepeatMode.Reverse),
        label = "phoneSway")
    Row(modifier
        .testTag("dbhScan.depthMotionHint")
        .clearAndSetSemantics {
            contentDescription = DEPTH_MOTION_HINT
            liveRegion = LiveRegionMode.Polite
        }
        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White,
                modifier = Modifier.size(18.dp))
            Icon(Icons.Outlined.PhoneAndroid, null, tint = Color.White,
                modifier = Modifier.offset(x = shift.dp).size(32.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White,
                modifier = Modifier.size(18.dp))
    }
}
