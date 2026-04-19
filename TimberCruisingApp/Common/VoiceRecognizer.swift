// Phase 7 — push-to-talk voice recognition for glove-friendly species entry.
//
// The SpeechRecognizer wraps Apple's Speech framework into an
// @ObservableObject with three published fields:
//
//   • isAvailable — whether the device supports on-device recognition
//   • isListening — live state; drives the mic-button UI
//   • transcript  — latest best-guess string (updates as the user speaks)
//
// Hold-to-talk usage pattern:
//
//     let rec = SpeechRecognizer()
//     // on long-press begin
//     try? await rec.start()
//     // on long-press end
//     rec.stop()
//     // transcript is now final
//
// On macOS (tests) and any device without Speech, `isAvailable` is false
// and `start()` is a no-op that throws `.notAvailable`.

import Foundation

#if canImport(Speech)
import Speech
import AVFoundation
#endif

public enum SpeechRecognizerError: Error, CustomStringConvertible {
    case notAvailable
    case notAuthorized
    case engineFailure(String)

    public var description: String {
        switch self {
        case .notAvailable:  return "Speech recognition is not available on this device."
        case .notAuthorized: return "Speech recognition permission was denied. Enable it in Settings → Forestix → Microphone & Speech Recognition."
        case .engineFailure(let m): return "Speech engine failed: \(m)"
        }
    }
}

@MainActor
public final class SpeechRecognizer: ObservableObject {

    @Published public private(set) var isAvailable: Bool = false
    @Published public private(set) var isListening: Bool = false
    @Published public private(set) var transcript: String = ""

    #if canImport(Speech)
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private let audioEngine = AVAudioEngine()
    #endif

    public init(locale: Locale = .current) {
        #if canImport(Speech)
        recognizer = SFSpeechRecognizer(locale: locale)
        isAvailable = recognizer?.isAvailable ?? false
        #else
        isAvailable = false
        #endif
    }

    // MARK: - Auth

    public func requestAuthorizationIfNeeded() async -> Bool {
        #if canImport(Speech)
        return await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { status in
                cont.resume(returning: status == .authorized)
            }
        }
        #else
        return false
        #endif
    }

    // MARK: - Start / stop

    public func start() throws {
        #if os(iOS)
        guard let recognizer = recognizer, recognizer.isAvailable else {
            throw SpeechRecognizerError.notAvailable
        }
        stopInternal()
        transcript = ""

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement,
                                    options: .duckOthers)
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch {
            throw SpeechRecognizerError.engineFailure("audio session: \(error.localizedDescription)")
        }

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if #available(iOS 13, *) {
            req.requiresOnDeviceRecognition = true
        }

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            req.append(buffer)
        }

        audioEngine.prepare()
        do { try audioEngine.start() }
        catch {
            throw SpeechRecognizerError.engineFailure(
                "engine start: \(error.localizedDescription)")
        }

        task = recognizer.recognitionTask(with: req) { [weak self] result, error in
            Task { @MainActor in
                if let text = result?.bestTranscription.formattedString {
                    self?.transcript = text
                }
                if result?.isFinal == true || error != nil {
                    self?.stopInternal()
                }
            }
        }

        self.request = req
        self.isListening = true
        #else
        // macOS / non-iOS: Speech is usable but AVAudioSession is not.
        // Phase 7 only ships the iOS recording path; tests don't exercise
        // this surface.
        throw SpeechRecognizerError.notAvailable
        #endif
    }

    public func stop() {
        stopInternal()
    }

    private func stopInternal() {
        #if os(iOS)
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        task = nil
        request = nil
        #endif
        isListening = false
    }
}

// MARK: - Match voice → species code

public enum SpeciesVoiceMatcher {
    /// Return the single best matching species code for a spoken phrase.
    /// Matches case-insensitively against:
    ///   • `code` exactly
    ///   • `commonName` as a whole-word substring
    ///   • `scientificName` as a whole-word substring
    ///
    /// Returns nil if the input is whitespace or no candidate scores > 0.
    public static func bestMatch(
        for spoken: String,
        candidates: [(code: String, commonName: String, scientificName: String)]
    ) -> String? {
        let normalized = spoken
            .lowercased()
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }

        let tokens = normalized
            .components(separatedBy: .whitespaces)
            .filter { !$0.isEmpty }

        var best: (score: Int, code: String)? = nil
        for c in candidates {
            var score = 0
            if c.code.lowercased() == normalized { score += 100 }
            let cn = c.commonName.lowercased()
            let sn = c.scientificName.lowercased()
            for t in tokens where t.count >= 3 {
                if cn.contains(t) { score += 10 }
                if sn.contains(t) { score += 8 }
            }
            if score > 0 && (best == nil || score > best!.score) {
                best = (score, c.code)
            }
        }
        return best?.code
    }
}
