// Stocking & Density gauge — port of iOS Screens/StockingGauge.swift.
// A 5-band gradient bar with a position marker. Lets the cruiser see at
// a glance whether a stand is understocked / fully stocked / over-dense
// without parsing four separate density numbers.
//
// The classic stocking-percent ranges (USFS / standard silviculture):
//   • 0–25 %   → understocked
//   • 25–35 %  → low stocking
//   • 35–60 %  → adequately stocked
//   • 60–100 % → fully stocked / over-dense
//
// Uses the design-system tier colours so the gauge stays consistent with
// the QUAL chip and the GPS / Tilt badges. Caller passes the current
// relative-density percentage (Curtis RD or similar) and a short label
// naming the regime; the gauge does the rest.

package com.hcjeong.forestix.ui.screens.stand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

/// Port of iOS `StockingGauge`.
/// - relativeDensityPct: current relative density as a percent (0…100+).
///   Values above 100 clamp to the right edge of the bar.
/// - regimeLabel: short regime label rendered as a pill above the gauge —
///   e.g. "Understocked", "Adequately stocked", "Over-dense".
@Composable
fun StockingGauge(relativeDensityPct: Double, regimeLabel: String) {
    val colors = Forestix.colors
    val type = Forestix.type

    val regimeColor: Color = when {
        relativeDensityPct < 25 -> colors.confidenceBad    // understocked
        relativeDensityPct < 35 -> colors.confidenceWarn   // low
        relativeDensityPct < 60 -> colors.confidenceOk     // adequate
        else -> colors.confidenceWarn                      // over-dense
    }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs),
    ) {
        // Regime pill
        Row {
            Text(
                regimeLabel,
                style = type.sectionHead.copy(letterSpacing = 1.2.sp),
                color = regimeColor,
                modifier = Modifier
                    .border(0.75.dp, regimeColor, CircleShape)
                    .padding(horizontal = ForestixSpace.xs, vertical = 3.dp),
            )
            Spacer(Modifier.weight(1f))
        }

        // Gradient bar + marker
        val okColor = colors.confidenceOk
        val warnColor = colors.confidenceWarn
        val badColor = colors.confidenceBad
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(18.dp),
        ) {
            val barHeight = 10.dp.toPx()
            val barTop = 0f
            // 5-band gradient — left red (under), through amber and green
            // (adequate), to amber on the right (over-dense).
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    0.00f to badColor,
                    0.25f to warnColor,
                    0.45f to okColor,
                    0.60f to okColor,
                    1.00f to warnColor,
                ),
                topLeft = Offset(0f, barTop),
                size = Size(size.width, barHeight),
                cornerRadius = CornerRadius(barHeight / 2f, barHeight / 2f),
            )
            // Position marker — black halo + white pin so it pops on
            // every band of the gradient.
            val clamped = relativeDensityPct.coerceIn(0.0, 100.0)
            val x = size.width * (clamped / 100.0).toFloat()
            val markerCenterY = barTop + barHeight / 2f
            val haloW = 4.dp.toPx()
            val haloH = 18.dp.toPx()
            drawRoundRect(
                color = Color.Black,
                topLeft = Offset(x - haloW / 2f, markerCenterY - haloH / 2f),
                size = Size(haloW, haloH),
                cornerRadius = CornerRadius(haloW / 2f, haloW / 2f),
            )
            val pinW = 2.dp.toPx()
            val pinH = 16.dp.toPx()
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(x - pinW / 2f, markerCenterY - pinH / 2f),
                size = Size(pinW, pinH),
                cornerRadius = CornerRadius(pinW / 2f, pinW / 2f),
            )
        }

        // The percent tick row ("0% 25% 35% 60% 100%") used to sit here. The
        // axis was a relative-density index — Reineke SDI over a hard-coded
        // generic Max SDI of 717 — and nothing on screen named SDI, Reineke,
        // or what the percentage was a percentage OF. The coloured bar, the
        // marker and the word pill above say everything the gauge can
        // honestly say, in words a cruiser can act on.
    }
}
