// Phase 7 — push-to-talk species picker.
//
// A self-contained SwiftUI component for the AddTreeFlowScreen species
// step. Hold the mic button, say the species name, release — the
// transcript is matched against the candidate species catalogue by
// `SpeciesVoiceMatcher` and the best code (if any) is fed back into the
// owning view's `onMatch` callback.
//
// On non-iOS hosts (macOS test runner) the recognizer is unavailable, so
// the view renders a dimmed "Voice input unavailable" row and
// everything else keeps working normally.

import SwiftUI
import Common

struct VoiceSpeciesPicker: View {

    let candidates: [(code: String, commonName: String, scientificName: String)]
    let onMatch: (String) -> Void

    @StateObject private var recognizer = SpeechRecognizer()
    @State private var errorMessage: String?
    @State private var lastMatchCode: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                micButton
                VStack(alignment: .leading, spacing: 2) {
                    Text(recognizer.isListening ? "Listening…" :
                         (recognizer.isAvailable
                            ? "Hold to speak a species name"
                            : "Voice input unavailable"))
                        .font(.subheadline)
                        .foregroundStyle(recognizer.isAvailable
                                         ? .primary : .secondary)
                    if !recognizer.transcript.isEmpty {
                        Text("\"\(recognizer.transcript)\"")
                            .font(.caption.italic())
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                    }
                    if let code = lastMatchCode {
                        Text("Matched: \(code)")
                            .font(.caption.bold())
                            .foregroundStyle(.green)
                    }
                }
                Spacer()
            }
            if let err = errorMessage {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.orange)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .background(.tint.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
    }

    private var micButton: some View {
        Image(systemName: recognizer.isListening
              ? "waveform.circle.fill"
              : "mic.circle.fill")
            .font(.system(size: 44))
            .foregroundStyle(recognizer.isListening
                             ? .red
                             : (recognizer.isAvailable ? .accentColor : .gray))
            .accessibilityLabel(recognizer.isListening
                                ? "Listening — release to match species"
                                : "Hold to speak species name")
            .accessibilityIdentifier("voiceSpecies.mic")
            #if os(iOS)
            .simultaneousGesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { _ in beginRecognitionIfIdle() }
                    .onEnded { _ in endRecognition() }
            )
            #endif
            .onChange(of: recognizer.isListening) { _, listening in
                if !listening { resolveTranscript() }
            }
    }

    // MARK: - Flow

    private func beginRecognitionIfIdle() {
        guard recognizer.isAvailable, !recognizer.isListening else { return }
        errorMessage = nil
        lastMatchCode = nil
        Task {
            let ok = await recognizer.requestAuthorizationIfNeeded()
            guard ok else {
                errorMessage = "Speech permission was denied. Enable it in Settings → Forestix → Microphone, then try again."
                return
            }
            do { try recognizer.start() }
            catch {
                errorMessage = "\(error)"
            }
        }
    }

    private func endRecognition() {
        recognizer.stop()
    }

    private func resolveTranscript() {
        guard !recognizer.transcript.isEmpty else { return }
        if let code = SpeciesVoiceMatcher.bestMatch(
            for: recognizer.transcript,
            candidates: candidates) {
            lastMatchCode = code
            onMatch(code)
            HapticFeedback.play(.success)
        } else {
            errorMessage = "Couldn't match \"\(recognizer.transcript)\" to a species. Try again or tap a species below."
            HapticFeedback.play(.failure)
        }
    }
}
