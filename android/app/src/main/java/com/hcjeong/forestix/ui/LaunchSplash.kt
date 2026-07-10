// Launch splash shown while AppEnvironment (Room + bootstrap) is being
// constructed — mirror of the iOS LaunchSplash. Shows the lab logo on the
// canvas colour (no white flash on cold start); MainActivity keeps this up
// for a minimum of 3 s so the first satellite-tile load happens behind it.

package com.hcjeong.forestix.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.R
import com.hcjeong.forestix.ui.theme.Forestix

@Composable
fun LaunchSplash() {
    val colors = Forestix.colors
    Box(
        Modifier.fillMaxSize().background(colors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.lab_logo),
            contentDescription = "Lab logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(180.dp),
        )
    }
}
