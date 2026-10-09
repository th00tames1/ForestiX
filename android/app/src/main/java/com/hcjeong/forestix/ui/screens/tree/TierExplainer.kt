// CONFIDENCE EXPLAINER — field report F8, corrected by field report item 6.
//
// The confidence chip is tappable, and this is what it opens. A cruiser was
// being shown a bare grade with no way to learn what moved it, so the grade
// read as a mood rather than a criterion. There is ONE set of words on every
// cruiser surface, taken from `confidenceDescriptor`: Good, Fair, Check. The
// stored enum stays green / yellow / red and every export is untouched — the
// enum simply never reaches a cruiser's eyes.
//
// EVERY NUMBER ON THIS SHEET IS READ FROM THE THRESHOLD THE CODE APPLIES.
// `DBHEstimator.TierThresholds` and `HeightEstimator`'s constants are
// formatted into the sentences below at render time. Prose copies of those
// numbers are what this sheet used to carry, and a prose copy is a promise
// to update two things whenever one of them moves.
//
// WHAT THE PROSE USED TO SAY, AND WHY IT WAS WRONG. The diameter half of
// this sheet listed five drivers — shape match, radius precision, arc
// coverage, surface points, per-frame spread — all of them taken from
// `DBHEstimator.estimate`, the §7.1 partial-arc circle fit. That is NOT the
// method the app captures with. Since the edge-bracket (Adjust) became the
// default path, a diameter is graded by `bracketChordEstimate`, which
// applies exactly one rule: frame-to-frame agreement of the burst's
// diameters against FRAME_SPREAD_GREEN. None of those five checks run on it,
// and it sets arc coverage, RMSE and sigma to zero. A cruiser reading the
// old sheet was being taught the criteria of a path their capture had not
// taken. The frame-agreement rule now leads, and the circle-fit criteria are
// kept in their own section that says when they apply.
//
// The height half was and remains accurate: HeightEstimator's §7.9 matrix
// (sigma_H/H, walk-back, top aim angle) grades every height, and every
// threshold quoted comes from the constants it checks against.
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
import com.hcjeong.forestix.sensors.DBHEstimator
import com.hcjeong.forestix.sensors.HeightEstimator
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace
import com.hcjeong.forestix.ui.theme.confidenceDescriptor
import java.util.Locale
import kotlin.math.roundToInt

/// Which measurement's tiers are being explained.
enum class TierExplainerKind(val raw: String, val title: String) {
    DIAMETER("diameter", "Diameter confidence"),
    HEIGHT("height", "Height confidence"),
}

/// Plain-language explanation of the confidence tiers, opened by tapping a
/// tier chip. Every string here is byte-identical to the iOS sheet.
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
                TierExplainerCopy.drivers(kind).forEach { (title, detail) ->
                    DriverRow(title, detail)
                }
            }

            // Diameter only, and only because the app has two grading paths
            // for it. Naming the section after the method keeps the sheet
            // from implying these checks ran on a capture that never went
            // near them.
            val secondary = TierExplainerCopy.secondaryDrivers(kind)
            if (secondary.isNotEmpty()) {
                ExplainerSection("If the circle fit was used") {
                    Text(
                        "The circle fit is not the capture the app takes by default. " +
                            "When it is the method that measured a stem, these are the " +
                            "checks it applies.",
                        style = type.caption,
                        color = colors.textSecondary,
                    )
                    secondary.forEach { (title, detail) -> DriverRow(title, detail) }
                }
            }

            ExplainerCard {
                Text(
                    TierExplainerCopy.combineRule(kind),
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/// Every sentence on the explainer, assembled from the constants the
/// estimators check against. Mirror of iOS `TierExplainerCopy`.
object TierExplainerCopy {

    // MARK: Numbers

    /// "7%" — a fraction threshold as the cruiser reads it. Whole percents
    /// print without a decimal point; anything else keeps one, so a
    /// threshold moved to 0.075 would render "7.5%" rather than silently
    /// rounding to the old number.
    fun percent(fraction: Double): String {
        // Quantise to 0.1% BEFORE deciding whether this is a whole percent:
        // 0.07 * 100 is 7.000000000000001 in binary floating point, and a
        // bare equality test against the rounded value renders the shipped
        // 7% threshold as "7.0%".
        val v = Math.round(fraction * 1000) / 10.0
        if (Math.abs(v - Math.round(v)) < 0.0001) return "${Math.round(v)}%"
        return String.format(Locale.US, "%.1f", v) + "%"
    }

    /// "30°" from a threshold in degrees.
    fun degrees(value: Double): String {
        val v = Math.round(value * 10) / 10.0
        if (Math.abs(v - Math.round(v)) < 0.0001) return "${Math.round(v)}°"
        return String.format(Locale.US, "%.1f", v) + "°"
    }

    /// "25 m" from a threshold in metres. Always metric: these are the
    /// estimator's own gates, not a measured value, and a cruiser working in
    /// feet still needs to recognise the number the code tests.
    fun metres(value: Float): String {
        val v = Math.round(value.toDouble() * 10) / 10.0
        if (Math.abs(v - Math.round(v)) < 0.0001) return "${Math.round(v)} m"
        return String.format(Locale.US, "%.1f", v) + " m"
    }

    fun drivers(kind: TierExplainerKind): List<Pair<String, String>> = when (kind) {
        TierExplainerKind.DIAMETER -> diameterDrivers()
        TierExplainerKind.HEIGHT -> heightDrivers()
    }

    /// The other diameter path's criteria. Empty for height, which has only
    /// one grading path.
    fun secondaryDrivers(kind: TierExplainerKind): List<Pair<String, String>> = when (kind) {
        TierExplainerKind.DIAMETER -> circleFitDrivers()
        TierExplainerKind.HEIGHT -> emptyList()
    }

    fun combineRule(kind: TierExplainerKind): String = when (kind) {
        TierExplainerKind.DIAMETER -> diameterCombineRule
        TierExplainerKind.HEIGHT -> heightCombineRule
    }

    // MARK: Diameter — the default capture

    fun diameterDrivers(): List<Pair<String, String>> {
        val t = DBHEstimator.TierThresholds
        val spread = percent(t.FRAME_SPREAD_GREEN)
        val frames = t.MIN_USABLE_FRAMES.toString()
        var d = "How closely the frames of one burst agreed on the width. "
        d += "A spread up to " + spread + " of their average is Good; wider than that is Fair. "
        d += "Fewer than " + frames + " usable frames is Check. "
        d += "On an Adjust capture — the one the app takes by default — this is the whole grade, "
        d += "and none of the circle-fit checks below are applied to it. "
        d += "Brace the phone and let it settle before you capture."
        return listOf("Frame agreement" to d)
    }

    val diameterCombineRule: String
        get() {
            var s = "A default Adjust capture is graded on frame agreement alone. "
            s += "Where the circle fit measured the stem instead, one caution makes it Fair, "
            s += "two make it Check, and anything that fails outright is Check on its own."
            return s
        }

    // MARK: Diameter — the circle-fit path

    fun circleFitDrivers(): List<Pair<String, String>> {
        val t = DBHEstimator.TierThresholds
        val rows = ArrayList<Pair<String, String>>(5)

        var shape = "How closely a round trunk matches the points the scanner returned. "
        shape += "Left-over error above " + percent(t.RMSE_OVER_RADIUS_REJECT) +
            " of the trunk's radius fails; "
        shape += percent(t.RMSE_OVER_RADIUS_WARN) + "–" +
            percent(t.RMSE_OVER_RADIUS_REJECT) + " is a caution. "
        shape += "It is judged against the size of the stem, not in millimetres, " +
            "so a small stem is held to a tighter tolerance than a big one."
        rows.add("Shape match" to shape)

        var precision = "How repeatable that radius is. "
        precision += "Worse than ±" + percent(t.SIGMA_OVER_RADIUS_REJECT) + " of it fails; "
        precision += "±" + percent(t.SIGMA_OVER_RADIUS_WARN) + "–" +
            percent(t.SIGMA_OVER_RADIUS_REJECT) + " is a caution."
        rows.add("How much it could be out" to precision)

        var coverage = "How much of the trunk's circumference the scan actually saw. "
        coverage += "Below " + degrees(t.MIN_ARC_DEG_REJECT) + " fails; "
        coverage += degrees(t.MIN_ARC_DEG_REJECT) + "–" +
            degrees(t.MIN_ARC_DEG_WARN) + " is a caution. "
        coverage += "Step around the stem a little, or stand where the whole face is in view."
        rows.add("Coverage" to coverage)

        var points = "How many depth points landed on the trunk. "
        points += "Fewer than " + t.MIN_INLIERS_REJECT + " fails; "
        points += "" + t.MIN_INLIERS_REJECT + "–" + t.MIN_INLIERS_WARN + " is a caution. "
        points += "Move closer and fill the crosshair with bark, not gaps."
        rows.add("Surface points" to points)

        var steady = "How much the width wanders from shot to shot while the phone is capturing. "
        steady += "Above " + percent(t.RADIUS_COV_REJECT) + " fails; "
        steady += percent(t.RADIUS_COV_WARN) + "–" + percent(t.RADIUS_COV_REJECT) +
            " is a caution. "
        steady += "Brace the phone and let it settle before you capture."
        rows.add("Steadiness" to steady)

        return rows
    }

    // MARK: Height

    fun heightDrivers(): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>(3)

        var sigma = "How far the height could be off, set against the height itself. "
        sigma += "Worse than ±" + percent(HeightEstimator.SIGMA_RATIO_YELLOW.toDouble()) +
            " is a caution. "
        sigma += "It grows with a long walk-back and with a steep aim, " +
            "so both of the next two feed it."
        rows.add("How much it could be out" to sigma)

        val topDeg = Math.toDegrees(HeightEstimator.MAX_ALPHA_TOP_YELLOW.toDouble())
        var aim = "How steeply you sighted the treetop. "
        aim += "Steeper than " + degrees(topDeg.roundToInt().toDouble()) +
            " above level is a caution — you are too close to the tree. "
        aim += "Walk back until you can see the top comfortably."
        rows.add("Aim angle" to aim)

        var walk = "How far you moved from the trunk. "
        walk += "More than " + metres(HeightEstimator.YELLOW_DH_M) + " is a caution, and past "
        walk += metres(HeightEstimator.HIGH_DRIFT_DH_M)
        walk += " the phone is no longer sure how far you actually walked, which adds a second one — "
        walk += "enough on its own to make the reading Check."
        rows.add("Walk-back distance" to walk)

        return rows
    }

    const val heightCombineRule: String =
        "One caution makes it Fair. Two make it Check. " +
            "Anything that fails outright is Check on its own."
}

@Composable
private fun DriverRow(title: String, detail: String) {
    val colors = Forestix.colors
    val type = Forestix.type
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = type.bodyBold, color = colors.textPrimary)
        Text(detail, style = type.caption, color = colors.textSecondary)
    }
}

@Composable
private fun ExplainerSection(header: String, content: @Composable () -> Unit) {
    val colors = Forestix.colors
    Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.xs)) {
        Text(
            header.uppercase(Locale.US),
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
