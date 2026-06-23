// Minimal launch placeholder shown while AppEnvironment (Room + bootstrap)
// is being constructed — mirror of the iOS LaunchSplash. Uses the canvas
// colour so there's no white flash on cold start.

package com.hcjeong.forestix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixSpace

@Composable
fun LaunchSplash() {
    val colors = Forestix.colors
    Column(
        Modifier.fillMaxSize().background(colors.canvas),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "FORESTIX",
            style = Forestix.type.sectionHead.copy(fontSize = 16.sp, letterSpacing = 3.sp, fontWeight = FontWeight.SemiBold),
            color = colors.primary,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(ForestixSpace.md))
        CircularProgressIndicator(color = colors.primary)
    }
}
