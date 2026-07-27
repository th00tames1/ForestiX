// CONFIDENCE EXPLAINER — field report F8.
//
// The tier chip on the per-tree report is tappable, and this is what it
// opens. A cruiser was being shown a bare grade with no way to learn what
// moved it, so it read as a mood rather than a criterion.
//
// ONE vocabulary, everywhere: the chip, this sheet and the plot-close
// warnings all say Good / Fair / Check. The stored enum stays
// green / yellow / red and every export is untouched — the enum simply
// never reaches a cruiser's eyes.
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
                        "It is never a gate — you can keep a Check reading.",
                    style = type.body,
                    color = colors.textSecondary,
                )
            }

            ExplainerSection("What the grades mean") {
                TierRow("green", "Every check passed. Take the number as it stands.")
                TierRow(
                    "yellow",
                    "One check fell short. The number is usable — treat it as a " +
                        "little softer than a Good one.",
                )
                TierRow(
                    "red",
                    "A check failed outright, or two fell short. Re-measure if the " +
                        "tree is still in front of you; if it isn't, keep it. A Check " +
                        "reading is recorded honestly, not discarded.",
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
                    "One caution makes it Fair. Two make it Check. Anything that " +
                        "fails outright is Check on its own.",
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
        // "Fit quality" / "the fitted radius" were the estimator's own
        // words for its circle fit — the last of that vocabulary left on a
        // cruiser surface. The rows now say what the cruiser is looking at;
        // every THRESHOLD quoted is still the estimator's real one.
        "Shape match" to
            "How closely a round trunk matches the points the scanner returned. " +
            "Left-over error above 7% of the trunk's radius fails; 5–7% is a caution. " +
            "It is judged against the size of the stem, not in millimetres, so a small " +
            "stem is held to a tighter tolerance than a big one.",
        "How much it could be out" to
            "How repeatable that radius is. Worse than ±5% of it fails; ±2–5% is a caution.",
        "Coverage" to
            "How much of the trunk's circumference the scan actually saw. Below 30° " +
            "fails; 30–45° is a caution. Step around the stem a little, or stand " +
            "where the whole face is in view.",
        "Surface points" to
            "How many depth points landed on the trunk. Fewer than 10 fails; 10–20 " +
            "is a caution. Move closer and fill the crosshair with bark, not gaps.",
        "Steadiness" to
            "How much the width wanders from shot to shot while the phone is " +
            "capturing. Above 10% fails; 5–10% is a caution. Brace the phone and " +
            "let it settle before you capture.",
    )
    TierExplainerKind.HEIGHT -> listOf(
        "How much it could be out" to
            "How far the height could be off, set against the height itself. Worse " +
            "than ±5% is a caution. It grows with a long walk-back and with a steep " +
            "aim, so both of the next two feed it.",
        "Aim angle" to
            "How steeply you sighted the treetop. Steeper than 75° above level is a " +
            "caution — you are too close to the tree. Walk back until you can see the " +
            "top comfortably.",
        // "tracking drift" is the AR-internals phrase, and "the distance may
        // have crept" was only half a step away from it — the sheet that
        // TEACHES the checks can't be the last place either survives. It now
        // says what goes wrong in the cruiser's terms. Both DISTANCES are the
        // estimator's real ones.
        "Walk-back distance" to
            "How far you moved from the trunk. More than 25 m is a caution, and past " +
            "30 m the phone is no longer sure how far you actually walked, which adds " +
            "a second one — enough on its own to make the reading Check.",
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
            // The word the CHIP shows for this tier — Good / Fair / Check —
            // never the stored enum ("green"/"yellow"/"red"). This sheet
            // exists to explain the chip, so it cannot be the one place that
            // names the same reading in a second vocabulary. The dot still
            // carries the colour. Stored values and every export are
            // untouched: this reads the same descriptor the chip reads.
            Text(
                descriptor.label,
                style = Forestix.type.bodyBold,
                color = descriptor.color,
            )
        }
        Text(detail, style = Forestix.type.caption, color = Forestix.colors.textSecondary)
    }
}
