// Post-save continuation sheet — after a DBH or height is recorded, the
// next logical action (height on the same tree, or the next tree's
// diameter) is one tap away instead of dumping the cruiser back to the
// hub. Port of the iOS MeasurementContinuationSheet: bottom sheet with a
// "Tree #N" title bar, a masthead ("Diameter saved" / "Height saved" +
// "What's next on tree #N?"), and icon + subtitle action cards.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ui.clickableNoRipple
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import com.hcjeong.forestix.ui.theme.ForestixSpace

/// What the cruiser chose from the continuation sheet.
enum class ContinuationAction { MEASURE_HEIGHT_SAME_TREE, START_NEW_TREE_DIAMETER, DONE }

/// Which measurement was just saved (drives the masthead + primary action).
enum class ContinuationOrigin { AFTER_DIAMETER, AFTER_HEIGHT }

private val ContinuationOrigin.headlineSubject: String
    get() = when (this) {
        ContinuationOrigin.AFTER_DIAMETER -> "Diameter saved"
        ContinuationOrigin.AFTER_HEIGHT -> "Height saved"
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementContinuationSheet(
    origin: ContinuationOrigin,
    treeNumber: Int,
    treeAlreadyHasHeight: Boolean,
    onAction: (ContinuationAction) -> Unit,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    ModalBottomSheet(
        onDismissRequest = { onAction(ContinuationAction.DONE) },
        containerColor = colors.canvas,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ForestixSpace.md)
                .navigationBarsPadding()
                .padding(bottom = ForestixSpace.xl),
            verticalArrangement = Arrangement.spacedBy(ForestixSpace.md),
        ) {
            // Title bar — inline centred "Tree #N" + leading Done, the iOS
            // NavigationStack toolbar (cancellationAction) chrome.
            Box(Modifier.fillMaxWidth().height(44.dp)) {
                TextButton(
                    onClick = { onAction(ContinuationAction.DONE) },
                    modifier = Modifier.align(Alignment.CenterStart),
                ) { Text("Done") }
                Text(
                    "Tree #$treeNumber",
                    style = type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            // Masthead — origin subject headline + the what's-next line.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(origin.headlineSubject, style = type.title, color = colors.textPrimary)
                Text(
                    "What's next on tree #$treeNumber?",
                    style = type.body,
                    color = colors.textSecondary,
                )
            }

            // Action cards.
            Column(verticalArrangement = Arrangement.spacedBy(ForestixSpace.sm)) {
                // Same-tree height capture is the primary recommendation
                // after a DBH. Hidden when the tree already has height,
                // and not shown after a height scan.
                if (origin == ContinuationOrigin.AFTER_DIAMETER && !treeAlreadyHasHeight) {
                    ContinuationActionCard(
                        title = "Measure height",
                        subtitle = "Stay on tree #$treeNumber",
                        icon = Icons.Filled.Height,
                        style = CardStyle.PRIMARY,
                    ) { onAction(ContinuationAction.MEASURE_HEIGHT_SAME_TREE) }
                }
                ContinuationActionCard(
                    title = "Next tree",
                    subtitle = "Start tree #${treeNumber + 1} with a fresh diameter scan",
                    icon = Icons.Filled.AddCircleOutline,
                    style = if (origin == ContinuationOrigin.AFTER_HEIGHT || treeAlreadyHasHeight) {
                        CardStyle.PRIMARY
                    } else {
                        CardStyle.SECONDARY
                    },
                ) { onAction(ContinuationAction.START_NEW_TREE_DIAMETER) }
                ContinuationActionCard(
                    title = "Back to hub",
                    subtitle = "Stop measuring for now",
                    icon = Icons.Outlined.Home,
                    style = CardStyle.TERTIARY,
                ) { onAction(ContinuationAction.DONE) }
            }
        }
    }
}

private enum class CardStyle { PRIMARY, SECONDARY, TERTIARY }

/// Port of the iOS ActionCard: 44 glyph tile + title/subtitle + chevron on
/// a card whose fill/border encode the recommendation strength.
@Composable
private fun ContinuationActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    style: CardStyle,
    onClick: () -> Unit,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    val primary = style == CardStyle.PRIMARY
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.card)
            .background(if (primary) colors.primary.copy(alpha = 0.10f) else colors.surface)
            .border(
                width = if (primary) 1.dp else 0.5.dp,
                color = if (primary) colors.primary.copy(alpha = 0.35f) else colors.divider,
                shape = ForestixRadius.card,
            )
            .clickableNoRipple(onClick)
            .padding(ForestixSpace.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ForestixSpace.md),
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(ForestixRadius.control)
                .background(if (primary) colors.primary else colors.primaryMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (primary) Color.White else colors.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = type.bodyBold, color = colors.textPrimary)
            Text(subtitle, style = type.caption, color = colors.textSecondary, maxLines = 2)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = if (style == CardStyle.TERTIARY) colors.textTertiary else colors.textSecondary,
            modifier = Modifier.size(12.dp),
        )
    }
}
