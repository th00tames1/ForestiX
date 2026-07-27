// CONFIDENCE EXPLAINER — field report F8.
//
// The tier chip on the per-tree report is tappable, and this is what it
// opens. A cruiser was being shown "Green" / "Yellow" / "Red" with no way to
// learn what moved it, so the grade read as a mood rather than a criterion.
//
// The copy below is written from the ACTUAL checks, so it stays true:
//   • DBH  — DBHEstimator's §7.1 sanity tree (inlier count, arc coverage,
//            fitted-radius sanity, RMSE/r, σ_r/r, per-frame radius spread).
//   • Height — HeightEstimator's §7.9 matrix (σ_H/H ≤ 5 %, walk-back ≤ 25 m
//            and ≤ 30 m, top aim angle ≤ 75°).
// And from `combineChecks`: any hard failure ⇒ red; two cautions ⇒ red; one
// caution ⇒ yellow; none ⇒ green.
//
// Wording is shared verbatim with the iOS sibling (Screens/TierExplainer.swift).

package com.hcjeong.forestix.ui.screens.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor

/// Which measurement's tiers are being explained.
enum class TierExplainerKind(val raw: String, val title: String) {
    DIAMETER("diameter", "Diameter confidence"),
    HEIGHT("height", "Height confidence"),
}

/// Plain-language explanation of the confidence tiers, opened by tapping the
/// tier chip on the per-tree report. Every string here is byte-identical to
/// the iOS sheet.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TierExplainerSheet(kind: TierExplainerKind, onDismiss: () -> Unit) {
    val colors = Forestix.colors
    val type = Forestix.type
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestixSpace.md)
                .navigationBarsPadding()
                .padding(bottom = ForestixSpace.lg),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // Inline centred title + trailing Done, mirroring the iOS
            // NavigationStack toolbar.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                Text(
                    kind.title,
                    style = type.bodyBold,
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) { Text("Done") }
            }

            ExplainerCard {
                Text(
                    "Every measurement is graded the moment it is computed. " +
                        "The grade travels with the record and into your exports. " +
                        "It is never a gate — you can keep a red reading.",
                    style = type.body,
                    color = colors.textSecondary,
                )
            }

            ExplainerSection("What the colours mean") {
                TierRow("green", "Every check passed. Take the number as it stands.")
                TierRow(
                    "yellow",
                    "One check fell short. The number is usable — treat it as a " +
                        "little softer than a green one.",
                )
                TierRow(
                    "red",
                    "A check failed outright, or two fell short. Re-measure if the " +
                        "tree is still in front of you; if it isn't, keep it. Red is " +
                        "recorded honestly, not discarded.",
                )
            }

            ExplainerSection("What moves it") {
                tierDrivers(kind).forEach { (title, detail) ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, style = type.bodyBold, color = colors.textPrimary)
                        Text(detail, style = type.caption, color = colors.textSecondary)
                    }
                }
            }

            ExplainerCard {
                Text(
                    "One caution makes it yellow. Two make it red. Anything that " +
                        "fails outright is red on its own.",
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/// What actually drives the tier, per measurement. Every threshold quoted
/// here is the one the estimator really applies.
private fun tierDrivers(kind: TierExplainerKind): List<Pair<String, String>> = when (kind) {
    TierExplainerKind.DIAMETER -> listOf(
        "Fit quality" to
            "How closely a circle matches the trunk points the scanner returned. " +
            "Leftover error above 7% of the fitted radius fails; 5–7% is a caution. " +
            "It is judged against the radius, not in millimetres, so a small stem is " +
            "held to a tighter tolerance than a big one.",
        "Radius precision" to
            "How repeatable that radius is. Worse than ±5% fails; ±2–5% is a caution.",
        "Coverage" to
            "How much of the trunk's circumference the scan actually saw. Below 30° " +
            "fails; 30–45° is a caution. Step around the stem a little, or stand " +
            "where the whole face is in view.",
        "Surface points" to
            "How many depth points landed on the trunk. Fewer than 10 fails; 10–20 " +
            "is a caution. Move closer and fill the crosshair with bark, not gaps.",
        "Steadiness" to
            "How much the fitted radius swings between frames of the burst. Above " +
            "10% fails; 5–10% is a caution. Brace the phone and let it settle before " +
            "you capture.",
    )
    TierExplainerKind.HEIGHT -> listOf(
        "Precision" to
            "The height's own uncertainty measured against the height itself. Worse " +
            "than ±5% is a caution. It grows with a long walk-back and with a steep " +
            "aim, so both of the next two feed it.",
        "Aim angle" to
            "How steeply you sighted the treetop. Steeper than 75° above level is a " +
            "caution — you are too close to the tree. Walk back until you can see the " +
            "top comfortably.",
        "Walk-back distance" to
            "How far you moved from the trunk. More than 25 m is a caution, and past " +
            "30 m tracking drift adds a second one — which on its own is enough to " +
            "make the reading red.",
    )
}

@Composable
private fun ExplainerSection(header: String, content: @Composable () -> Unit) {
    val colors = Forestix.colors
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        Text(
            header.uppercase(java.util.Locale.US),
            style = Forestix.type.sectionHead,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = ForestixSpace.xs),
        )
        ExplainerCard { content() }
    }
}

@Composable
private fun ExplainerCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(Forestix.colors.surface)
            .padding(ForestixSpace.md),
        verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm),
    ) {
        content()
    }
}

@Composable
private fun TierRow(rawTier: String, detail: String) {
    val descriptor = confidenceDescriptor(rawTier)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(descriptor.color))
            Text(
                rawTier.replaceFirstChar { it.uppercase() },
                style = Forestix.type.bodyBold,
                color = descriptor.color,
            )
        }
        Text(detail, style = Forestix.type.caption, color = Forestix.colors.textSecondary)
    }
}
