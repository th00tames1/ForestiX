// Vibration helper — the Android analogue of iOS
// UINotificationFeedbackGenerator(.warning), used by the sampling-plot
// out-of-bounds alarm.

package com.hcjeong.forestix.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class Haptics(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /// Warning pattern — two short pulses, mirroring the iOS .warning feel.
    fun warn() {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 80, 60), -1))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(longArrayOf(0, 60, 80, 60), -1)
        }
    }
}
