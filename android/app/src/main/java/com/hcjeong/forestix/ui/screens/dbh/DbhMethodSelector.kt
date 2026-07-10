// DBH method selector — a small pill toggle that picks the capture method
// on the DBH scan screen: the depth-API chord/circle fit ("Depth") vs the
// two-tap trunk-edge AR caliper ("Caliper"). Mirrors the SlashScan floating
// control look used by the Distance Live/Two-point pill.
//
// Developer mode ONLY (field fix, both platforms): normal mode is
// depth-only, so this pill never renders there — DBHScanScreen gates it
// behind settings.developerMode.

package com.hcjeong.forestix.ui.screens.dbh

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix

/// The DBH capture flows the scan screen supports (3-way, matching iOS
/// DBHMethodSource: LiDAR depth / AR motion / AR caliper).
enum class DbhCaptureMethod { DEPTH, MOTION, CALIPER }

/// Segmented Depth | Motion | Caliper pill. The selected segment is filled
/// with the primary tint; tapping a segment selects it.
@Composable
fun DbhMethodSelector(
    method: DbhCaptureMethod,
    onSelect: (DbhCaptureMethod) -> Unit,
    depthEnabled: Boolean,
) {
    Row(
        Modifier
            .shadow(3.dp, RoundedCornerShape(20.dp), clip = false)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .padding(3.dp),
    ) {
        Segment(
            label = "Depth",
            selected = method == DbhCaptureMethod.DEPTH,
            dim = !depthEnabled,
            onClick = { if (depthEnabled) onSelect(DbhCaptureMethod.DEPTH) },
        )
        Segment(
            label = "Motion",
            selected = method == DbhCaptureMethod.MOTION,
            dim = false,
            onClick = { onSelect(DbhCaptureMethod.MOTION) },
        )
        Segment(
            label = "Caliper",
            selected = method == DbhCaptureMethod.CALIPER,
            dim = false,
            onClick = { onSelect(DbhCaptureMethod.CALIPER) },
        )
    }
}

@Composable
private fun Segment(label: String, selected: Boolean, dim: Boolean, onClick: () -> Unit) {
    val colors = Forestix.colors
    val bg = if (selected) colors.primary else Color.Transparent
    val fg = when {
        // Dark ink on the primary fill — iOS methodSegment parity.
        selected -> colors.primaryInk
        dim -> Color.White.copy(alpha = 0.40f)
        else -> Color.White.copy(alpha = 0.85f)
    }
    Text(
        label,
        style = Forestix.type.dataSmall.copy(
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
        ),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(bg)
            .clickableNoRipple(onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}
