// Port of iOS Screens/VoiceSpeciesPicker.swift + Common/VoiceRecognizer.swift.
// Phase 7 — push-to-talk species picker.
//
// A self-contained Compose push-to-talk species picker (built for the
// retired add-tree stepper; kept as a reusable component). Hold the mic
// button, say the species name, release — the
// transcript is matched against the candidate species catalogue by
// `SpeciesVoiceMatcher` and the best code (if any) is fed back into the
// owning view's `onMatch` callback.
//
// The `SpeechRecognizer` class wraps android.speech.SpeechRecognizer into
// a plain state holder with the same three published fields as the iOS
// Speech-framework wrapper:
//
//   • isAvailable — whether the device supports speech recognition
//   • isListening — live state; drives the mic-button UI
//   • transcript  — latest best-guess string (updates as the user speaks)
//
// On devices without a recognition service, `isAvailable` is false and the
// row renders dimmed "Voice input unavailable"; everything else keeps
// working normally.

package com.hcjeong.forestix.ui.screens.tree

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.hcjeong.forestix.common.RegionalSpecies
import com.hcjeong.forestix.ui.theme.Forestix
import com.hcjeong.forestix.ui.theme.ForestixRadius
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// MARK: - Candidate

/// Kotlin analogue of the iOS `(code, commonName, scientificName)` tuple.
data class SpeciesVoiceCandidate(
    val code: String,
    val commonName: String,
    val scientificName: String,
)

// MARK: - Match voice → species code

object SpeciesVoiceMatcher {
    /// Return the single best matching species code for a spoken phrase.
    /// Matches case-insensitively against:
    ///   • `code` exactly
    ///   • `commonName` as a substring (tokens of length >= 3)
    ///   • `scientificName` as a substring (tokens of length >= 3)
    ///
    /// Returns null if the input is whitespace or no candidate scores > 0.
    fun bestMatch(spoken: String, candidates: List<SpeciesVoiceCandidate>): String? {
        val normalized = spoken.lowercase(Locale.US).trim()
        if (normalized.isEmpty()) return null

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }

        var bestScore = 0
        var bestCode: String? = null
        for (c in candidates) {
            var score = 0
            if (c.code.lowercase(Locale.US) == normalized) score += 100
            val cn = c.commonName.lowercase(Locale.US)
            val sn = c.scientificName.lowercase(Locale.US)
            for (t in tokens) {
                if (t.length < 3) continue
                if (cn.contains(t)) score += 10
                if (sn.contains(t)) score += 8
            }
            if (score > 0 && (bestCode == null || score > bestScore)) {
                bestScore = score
                bestCode = c.code
            }
        }
        return bestCode
    }
}

// MARK: - SpeechRecognizer wrapper

/// Push-to-talk wrapper around android.speech.SpeechRecognizer, mirroring
/// the iOS `SpeechRecognizer` ObservableObject in Common/VoiceRecognizer.swift.
///
/// Hold-to-talk usage pattern:
///
///     val rec = remember { SpeechRecognizer(context) }
///     // on press begin
///     rec.start()
///     // on press end
///     rec.stop()
///     // transcript finalizes shortly after; isListening flips false
class SpeechRecognizer(private val context: Context) {

    private val _isAvailable = MutableStateFlow(
        android.speech.SpeechRecognizer.isRecognitionAvailable(context))
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    /// Engine failure surfaced by the last session (null = none). Cleared
    /// on every start().
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var recognizer: android.speech.SpeechRecognizer? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrEmpty()) _transcript.value = text
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!text.isNullOrEmpty()) _transcript.value = text
            stopInternal()
        }

        override fun onError(error: Int) {
            // NO_MATCH / SPEECH_TIMEOUT just mean nothing intelligible was
            // heard — the empty-transcript path already covers that.
            when (error) {
                android.speech.SpeechRecognizer.ERROR_NO_MATCH,
                android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> Unit
                android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    _lastError.value = "Speech permission was denied. Enable the microphone permission for Forestix, then try again."
                else -> _lastError.value = "Speech engine failed (code $error)."
            }
            stopInternal()
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // MARK: - Start / stop

    /// Begins a listening session. No-op when unavailable or already
    /// listening. Must be called from the main thread (Compose callbacks are).
    fun start() {
        if (!_isAvailable.value || _isListening.value) return
        _transcript.value = ""
        _lastError.value = null

        val r = recognizer ?: android.speech.SpeechRecognizer
            .createSpeechRecognizer(context)
            .also { it.setRecognitionListener(listener); recognizer = it }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        _isListening.value = true
        r.startListening(intent)
    }

    /// Ends the audio capture; the final transcript arrives via onResults
    /// shortly after, which flips `isListening` to false.
    fun stop() {
        recognizer?.stopListening()
    }

    /// Release the platform recognizer. Call from onDispose.
    fun destroy() {
        recognizer?.destroy()
        recognizer = null
        _isListening.value = false
    }

    private fun stopInternal() {
        _isListening.value = false
    }
}

// MARK: - VoiceSpeciesPicker

@Composable
fun VoiceSpeciesPicker(
    candidates: List<SpeciesVoiceCandidate>,
    onMatch: (String) -> Unit,
) {
    val colors = Forestix.colors
    val type = Forestix.type
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val recognizer = remember { SpeechRecognizer(context) }
    DisposableEffect(Unit) { onDispose { recognizer.destroy() } }

    val isAvailable by recognizer.isAvailable.collectAsState()
    val isListening by recognizer.isListening.collectAsState()
    val transcript by recognizer.transcript.collectAsState()
    val engineError by recognizer.lastError.collectAsState()

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var lastMatchCode by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // iOS beginRecognitionIfIdle() starts listening as soon as the
            // authorization request resolves, so the hold that popped the
            // prompt isn't silently consumed. The system dialog cancels the
            // Android press, so start the recognizer here — it self-stops
            // on silence, so there's no stuck-listening risk.
            errorMessage = null
            lastMatchCode = null
            recognizer.start()
        } else {
            errorMessage = "Speech permission was denied. Enable the microphone permission for Forestix, then try again."
        }
    }

    // Resolve the transcript when a listening session ends (iOS
    // `.onChange(of: recognizer.isListening)` parity).
    LaunchedEffect(isListening) {
        if (isListening) return@LaunchedEffect
        engineError?.let { errorMessage = it }
        if (transcript.isEmpty()) return@LaunchedEffect
        val code = SpeciesVoiceMatcher.bestMatch(transcript, candidates)
        if (code != null) {
            lastMatchCode = code
            onMatch(code)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        } else {
            errorMessage = "Couldn't match \"$transcript\" to a species. Try again or tap a species below."
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(ForestixRadius.control)
            .background(colors.primary.copy(alpha = 0.08f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.GraphicEq else Icons.Filled.Mic,
                contentDescription = if (isListening)
                    "Listening — release to match species"
                else
                    "Hold to speak species name",
                tint = when {
                    isListening -> colors.confidenceBad
                    isAvailable -> colors.primary
                    else -> colors.textTertiary
                },
                modifier = Modifier
                    .size(44.dp)
                    .pointerInput(isAvailable) {
                        detectTapGestures(onPress = {
                            if (isAvailable && !recognizer.isListening.value) {
                                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    errorMessage = null
                                    lastMatchCode = null
                                    recognizer.start()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            tryAwaitRelease()
                            recognizer.stop()
                        })
                    },
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    when {
                        isListening -> "Listening…"
                        isAvailable -> "Hold to speak a species name"
                        else -> "Voice input unavailable"
                    },
                    style = type.body,
                    color = if (isAvailable) colors.textPrimary else colors.textSecondary,
                )
                if (transcript.isNotEmpty()) {
                    Text(
                        "\"$transcript\"",
                        style = type.caption,
                        fontStyle = FontStyle.Italic,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
                lastMatchCode?.let { code ->
                    Text(
                        "Matched: ${RegionalSpecies.nameForCode(code)}",
                        style = type.caption,
                        color = colors.confidenceOk,
                    )
                }
            }
            Spacer(Modifier)
        }
        errorMessage?.let { err ->
            Text(err, style = type.caption, color = colors.confidenceWarn)
        }
    }
}
