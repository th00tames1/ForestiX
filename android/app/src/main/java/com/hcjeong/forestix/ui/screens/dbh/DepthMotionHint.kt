package com.hcjeong.forestix.ui.screens.dbh

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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

internal const val DEPTH_MOTION_HINT = "Move phone slowly left and right, then up and down"

/** Compact replacement for the top no-lock banner; no touch handlers. */
@Composable
internal fun DepthMotionHint(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "depthMotionHint")
    val phase by motion.animateFloat(0f, 2f,
        animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing)),
        label = "phoneSway")
    val shift = depthMotionHintOffset(phase)
    Box(modifier
        .testTag("dbhScan.depthMotionHint")
        .clearAndSetSemantics {
            contentDescription = DEPTH_MOTION_HINT
            liveRegion = LiveRegionMode.Polite
        }
        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp)) {
        // A fixed footprint keeps the banner still while the upright phone
        // translates. Small arrows leave the camera view mostly unobstructed.
        Box(Modifier.size(width = 96.dp, height = 64.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White,
                modifier = Modifier.align(Alignment.CenterStart).size(18.dp))
            Icon(Icons.Filled.ArrowUpward, null, tint = Color.White,
                modifier = Modifier.align(Alignment.TopCenter).size(14.dp))
            Icon(Icons.Outlined.PhoneAndroid, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center)
                    .offset(x = shift.xDp.dp, y = shift.yDp.dp).size(28.dp))
            Icon(Icons.Filled.ArrowDownward, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomCenter).size(14.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White,
                modifier = Modifier.align(Alignment.CenterEnd).size(18.dp))
        }
    }
}
