// Shared chrome — a compact, centre-titled top app bar + canvas background
// matching the iOS `.navigationTitle(...).navigationBarTitleDisplayMode(
// .inline)` look (17 pt semibold title in a ~48 pt bar), with a back
// chevron that pops the nav stack. Also hosts the shared trailing
// swipe-to-delete row (G9) and the borderless grouped-form text field.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForestixScaffold(
    nav: NavController,
    title: String,
    actions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = Forestix.colors
    Scaffold(
        containerColor = colors.canvas,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        title,
                        style = Forestix.type.bodyBold.copy(fontSize = 17.sp),
                        color = colors.textPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.primary)
                    }
                },
                actions = { actions() },
                expandedHeight = 48.dp,
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = colors.canvas,
                    titleContentColor = colors.textPrimary,
                ),
            )
        },
        modifier = Modifier.background(colors.canvas),
    ) { padding ->
        content(padding)
    }
}

/// G9 — the app-wide delete affordance: trailing swipe with a destructive
/// red background, "Delete" + trash glyph, full swipe allowed (mirror of
/// the iOS `.swipeActions(edge: .trailing, allowsFullSwipe: true)` rows).
/// `shape` clips the reveal so rounded card rows keep their corners.
@Composable
fun SwipeToDeleteRow(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = Forestix.colors
    // The handler is read at gesture time, not captured from the first
    // composition — the field log recycles these rows across trees, so a
    // swipe must delete the tree the row is showing NOW.
    val delete = rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                delete.value()
                // FALSE — the swipe FIRES the action but never settles the
                // box dismissed.
                //
                // It used to return true, which left the row permanently
                // collapsed to its red "Delete" background. That was
                // survivable while every swipe deleted immediately, and
                // became a data-integrity problem when the field log started
                // asking for confirmation on a tree carrying more than one
                // reading: the cruiser swipes, reads the dialog, taps
                // Cancel, and is left looking at a red bar where a tree with
                // a diameter and a height used to be. The readings are safe
                // on disk and still export, but in the stand the rational
                // response is to walk back and re-measure — which is how a
                // duplicate of that tree gets into an accuracy-validation
                // dataset. Refusing the settle keeps the row on screen and
                // lets the confirmation dialog decide.
                false
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = modifier.clip(shape),
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(colors.confidenceBad)
                    .padding(horizontal = ForestixSpace.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.Delete, contentDescription = null,
                    tint = Color.White, modifier = Modifier.size(18.dp))
                Text("Delete", style = Forestix.type.bodyBold, color = Color.White)
            }
        },
    ) {
        content()
    }
}

/// Borderless text field for grouped-form rows — the Compose stand-in for
/// a plain `TextField` inside an iOS inset-grouped `Form` (no outline, no
/// underline, placeholder in textTertiary).
@Composable
fun FormTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        textStyle = type.body.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(placeholder, style = type.body, color = colors.textTertiary)
                }
                inner()
            }
        },
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
