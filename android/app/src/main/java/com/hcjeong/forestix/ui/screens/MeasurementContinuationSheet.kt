// Post-save continuation dialog — after a DBH or height is recorded, the
// next logical action (height on the same tree, or the next tree's
// diameter) is one tap away instead of dumping the cruiser back to the
// hub. Port of the iOS MeasurementContinuationSheet.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.ui.theme.Forestix

/// What the cruiser chose from the continuation dialog.
enum class ContinuationAction { MEASURE_HEIGHT_SAME_TREE, START_NEW_TREE_DIAMETER, DONE }

/// Which measurement was just saved (drives title + primary action).
enum class ContinuationOrigin { AFTER_DIAMETER, AFTER_HEIGHT }

@Composable
fun MeasurementContinuationDialog(
    origin: ContinuationOrigin,
    treeNumber: Int?,
    treeSummary: String?,
    onAction: (ContinuationAction) -> Unit,
) {
    val type = Forestix.type
    val colors = Forestix.colors
    AlertDialog(
        onDismissRequest = { onAction(ContinuationAction.DONE) },
        confirmButton = {},
        title = {
            Text(if (origin == ContinuationOrigin.AFTER_DIAMETER) "Diameter saved" else "Height saved")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                treeNumber?.let { n ->
                    Text(
                        "Tree #$n" + (treeSummary?.let { " · $it" } ?: ""),
                        style = type.caption, color = colors.textSecondary,
                    )
                }
                if (origin == ContinuationOrigin.AFTER_DIAMETER) {
                    Button(
                        onClick = { onAction(ContinuationAction.MEASURE_HEIGHT_SAME_TREE) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Measure height — same tree") }
                }
                OutlinedButton(
                    onClick = { onAction(ContinuationAction.START_NEW_TREE_DIAMETER) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Next tree — new diameter") }
                TextButton(
                    onClick = { onAction(ContinuationAction.DONE) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Done") }
            }
        },
    )
}
