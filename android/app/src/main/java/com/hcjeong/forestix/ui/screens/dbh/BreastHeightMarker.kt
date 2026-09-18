package com.hcjeong.forestix.ui.screens.dbh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.common.BreastHeightMarkerLayout
import kotlin.math.roundToInt

@Composable
internal fun BreastHeightMarker(pointPx: Offset, label: String,
                                stemLeftFraction: Float?, stemRightFraction: Float?) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = BreastHeightMarkerLayout.make(
            pointPx.x / density.density, pointPx.y / density.density,
            maxWidth.value, maxHeight.value,
            stemLeftFraction?.times(maxWidth.value), stemRightFraction?.times(maxWidth.value),
        ) ?: return@BoxWithConstraints
        Canvas(Modifier.fillMaxSize().semantics {
            contentDescription = "Ground reference height, $label"
        }) {
            for (tick in listOfNotNull(layout.leftTick, layout.rightTick)) {
                val start = Offset(tick.start.dp.toPx(), layout.y.dp.toPx())
                val end = Offset(tick.endInclusive.dp.toPx(), layout.y.dp.toPx())
                drawLine(Color.Black.copy(alpha = 0.65f), start, end, 4.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, start, end, 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        layout.labelCenterX?.let { x ->
            Text(label,
                style = TextStyle(fontSize = 12.sp, lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
                color = Color.Black, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.offset {
                    IntOffset(
                        ((x - BreastHeightMarkerLayout.LABEL_WIDTH / 2) * density.density).roundToInt(),
                        ((layout.y - BreastHeightMarkerLayout.LABEL_HEIGHT / 2) * density.density).roundToInt(),
                    )
                }.size(BreastHeightMarkerLayout.LABEL_WIDTH.dp, BreastHeightMarkerLayout.LABEL_HEIGHT.dp)
                    .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(4.dp)),
            )
        }
    }
}
