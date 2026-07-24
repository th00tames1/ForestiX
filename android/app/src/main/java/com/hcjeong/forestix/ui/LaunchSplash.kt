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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.R

@Composable
fun LaunchSplash() {
    // WHITE background always — the AFSL lab logo is black line-art, so it
    // can't sit on the dark canvas. 300 dp (was 180) so the wordmark reads.
    Box(
        Modifier.fillMaxSize().background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(R.drawable.lab_logo),
            contentDescription = "Lab logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(300.dp),
        )
    }
}
