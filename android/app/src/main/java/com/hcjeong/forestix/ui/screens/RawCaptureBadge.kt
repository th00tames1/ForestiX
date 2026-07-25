// Raw-capture recording state, made VISIBLE on the AR scan screens.
//
// The validation corpus is collected in the field, once, with no second
// chance: a measurement must either RECORD AND SAY SO, or FAIL LOUDLY. Two
// pieces of chrome do that here, on both DBH and Height (and identically on
// iOS):
//
//  REC pill      — persistent while the recorder is armed
//                  (developer mode + "Record raw captures"), so the cruiser
//                  can see at a glance that the burst is being kept.
//  Outcome pill  — after every capture attempt: what actually happened.
//                  Saved reads in the OK colour; NOT saved reads in the
//                  warning colour with the reason, and never looks like a
//                  success.

package com.hcjeong.forestix.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hcjeong.forestix.ui.theme.Forestix
import kotlinx.coroutines.delay

/// Outcome of the last capture attempt, as shown to the cruiser.
/// `saved = false` is ALWAYS rendered in the failure colour.
data class RawCaptureStatus(val text: String, val saved: Boolean)

/// Standard outcome strings — worded identically to iOS so a saved capture
/// and a lost one read the same on both platforms.
object RawCaptureStrings {
    const val SAVED = "Raw capture saved"

    /// Developer mode on, recorder off: the session is producing NO corpus.
    const val OFF_NOTICE =
        "Raw capture OFF — nothing is being recorded (Settings › Developer)"

    /// Settings ASK for recording but the recorder is not actually armed —
    /// the state in which the REC pill used to keep glowing while nothing was
    /// being kept. Shown in the failure colour, never as REC.
    const val NOT_ARMED = "NOT RECORDING — recorder not armed"

    /// A typed truth that is QUEUED against a bundle still being written. It
    /// is not in any manifest yet, so the field keeps the text and says why.
    const val TRUTH_PENDING = "Truth pending — kept on screen until the capture is written"

    const val TRUTH_WRITE_FAILED = "Truth NOT saved — write failed"

    const val TRUTH_NOT_A_NUMBER = "Not a number — truth not saved"

    fun saved(frames: Int?): String =
        if (frames != null && frames > 0) "$SAVED · $frames frames" else SAVED

    fun notSaved(reason: String?): String =
        if (reason.isNullOrBlank()) "NOT SAVED" else "NOT SAVED — $reason"

    /// The capture already failed before Accept — the typed value stays put.
    fun truthCaptureFailed(reason: String?): String =
        "Capture NOT saved (${reason ?: "write failed"}) — truth kept on screen"

    /// The bundle write failed while a typed truth was QUEUED against it: the
    /// pairing is gone. The number is named either way — put back in the field
    /// when it was free, otherwise only announced, because the operator has
    /// since typed something newer that must not be overwritten.
    fun truthLost(value: String, restored: Boolean): String =
        if (restored) "Capture NOT saved — truth $value NOT stored, put back in the field"
        else "Capture NOT saved — truth $value NOT stored (field holds newer text)"
}

/// REC + outcome stack, pinned top-left under the back button. Renders
/// nothing when the recorder is idle and there is no outcome to report.
/// `storageLow` re-reads with each capture so a phone that fills up mid-plot
/// says so on the pill itself.
///
/// `armed` is the RECORDER's real state (ArSessionHub.rawDepthArmed), never
/// the Settings flag: a safety indicator must not lie. `requested` is what
/// Settings asked for — when it is on and the recorder is NOT armed (an arm
/// token clobbered by an activity recreation, say) the pill flips to a loud
/// NOT RECORDING instead of staying red as if it were keeping the burst.
@Composable
fun BoxScope.RawCaptureBadge(
    armed: Boolean,
    requested: Boolean,
    storageLow: Boolean,
    status: RawCaptureStatus?,
) {
    // A screen arms its token from a DisposableEffect, i.e. one frame after
    // its first composition — so report a mismatch only once it has PERSISTED,
    // otherwise every entry flashes NOT RECORDING for a frame.
    var settled by remember(armed, requested) { mutableStateOf(false) }
    LaunchedEffect(armed, requested) {
        delay(500)
        settled = true
    }
    val disarmed = requested && !armed && settled
    if (!armed && !disarmed && status == null) return
    Column(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 16.dp, top = 68.dp)
            .widthIn(max = 260.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (armed) RecPill(storageLow) else if (disarmed) NotArmedPill()
        status?.let { OutcomePill(it) }
    }
}

/// Recording was asked for but the recorder is not armed — the burst is NOT
/// being kept. Same failure colour as a NOT SAVED outcome.
@Composable
private fun NotArmedPill() {
    OutcomePill(RawCaptureStatus(RawCaptureStrings.NOT_ARMED, saved = false))
}

@Composable
private fun RecPill(storageLow: Boolean) {
    val colors = Forestix.colors
    val rim = if (storageLow) colors.confidenceWarn else colors.confidenceBad
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.65f))
            .border(1.dp, rim, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(colors.confidenceBad))
        Text(
            if (storageLow) "REC · LOW STORAGE" else "REC",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            color = Color.White,
        )
    }
}

@Composable
private fun OutcomePill(status: RawCaptureStatus) {
    val colors = Forestix.colors
    Text(
        status.text,
        style = TextStyle(
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        ),
        color = Color.White,
        maxLines = 2,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (status.saved) colors.confidenceOk.copy(alpha = 0.92f)
                else colors.confidenceBad.copy(alpha = 0.95f),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/// Dev-block notice: developer mode is on but raw capture is off, so the
/// field session is producing no corpus.
@Composable
fun RawCaptureOffNotice() {
    Text(
        RawCaptureStrings.OFF_NOTICE,
        style = Forestix.type.caption,
        color = Forestix.colors.confidenceWarn,
    )
}

/// Inline warning under a typed ground-truth field.
@Composable
fun TruthFieldWarning(text: String) {
    Text(text, style = Forestix.type.caption, color = Forestix.colors.confidenceWarn)
}

/// Inline NOTE under a typed ground-truth field — a non-failure state the
/// operator still has to see, e.g. a truth queued against a bundle whose
/// write hasn't finished, which is why the field was not cleared.
@Composable
fun TruthFieldNote(text: String) {
    Text(text, style = Forestix.type.caption, color = Forestix.colors.textSecondary)
}
