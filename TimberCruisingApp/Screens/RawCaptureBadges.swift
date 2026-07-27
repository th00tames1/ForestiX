// RAW-CAPTURE RECORDING STATE — the chrome that makes "am I recording?" and
// "did that capture actually save?" impossible to get wrong in the field.
//
// Shared by the DBH and Height scan screens so the two read identically, and
// worded identically to the Android sibling.
//
// Rules this file exists to enforce:
//   • Every capture attempt ends in an unmistakable outcome: SAVED, or NOT
//     SAVED in warning colour with the reason. A failure never looks like a
//     success. THIS is the rule that carries the weight.
//   • Developer mode ON with recording OFF says so, in the dev block.
//
// FIELD REPORT F2 — the permanent red REC pill is no longer rendered on the
// scan screens. The cruiser found a pill that never changes to be noise, and
// the per-capture outcome above already answers "did that one save?". The
// component is kept (below) but has no call sites.

import SwiftUI
import Common
import Sensors

// MARK: - Armed indicator

/// Persistent "recording armed" pill. Also calls out low storage, because a
/// full phone is the one condition that turns every later capture into a
/// failure and there is no recovering the trees you already walked past.
///
/// RETIRED FROM THE SCAN SCREENS (field report F2) — kept so the indicator
/// can come back for a bench session without rebuilding it. A phone that
/// fills up is still reported, per capture, by `RawCaptureOutcomePill`.
struct RawCaptureRecPill: View {

    /// Re-read on each capture so a phone that fills up mid-plot says so.
    let storageLow: Bool

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(ForestixPalette.confidenceBad)
                .frame(width: 8, height: 8)
            Text(storageLow ? "REC · LOW STORAGE" : "REC")
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                .foregroundStyle(.white)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.black.opacity(0.65), in: Capsule())
        .overlay(
            Capsule().stroke(storageLow ? ForestixPalette.confidenceWarn
                                        : ForestixPalette.confidenceBad,
                             lineWidth: 1))
        .accessibilityIdentifier("rawCapture.recPill")
    }
}

// MARK: - Per-capture outcome

/// SAVED / NOT SAVED for the last capture attempt. Colour + wording differ
/// hard enough that a glance can't confuse the two.
struct RawCaptureOutcomePill: View {

    let outcome: RawCaptureOutcome

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: outcome.isSaved
                  ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 11, weight: .bold))
            Text(text)
                .font(.system(size: 11, weight: .bold, design: .monospaced))
                // A failure reason can carry a tail ("typed ground truth
                // discarded — re-enter it") that must not be clipped away.
                .lineLimit(4)
                .multilineTextAlignment(.leading)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(outcome.isSaved
                    ? ForestixPalette.confidenceOk.opacity(0.92)
                    : ForestixPalette.confidenceBad.opacity(0.95),
                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
        .frame(maxWidth: 260, alignment: .leading)
        .accessibilityIdentifier("rawCapture.outcomePill")
    }

    private var text: String {
        switch outcome {
        case .saved(_, let frames):
            return frames > 0 ? "Raw capture saved · \(frames) frames"
                              : "Raw capture saved"
        case .failed(let reason):
            return "NOT SAVED — \(reason)"
        }
    }
}

// MARK: - Dev-block notice

/// Shown in the developer block when Developer mode is on but raw capture is
/// off: the field session is producing NO corpus, which is exactly the thing
/// you don't want to discover at the end of the day.
struct RawCaptureOffNotice: View {
    var body: some View {
        Text("Raw capture OFF — nothing is being recorded (Settings › Developer)")
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.confidenceWarn)
            .accessibilityIdentifier("rawCapture.offNotice")
    }
}

// MARK: - Truth field helpers

/// Inline warning under a typed ground-truth field: a value outside the
/// plausibility window, or characters that don't parse as a number.
struct TruthFieldWarning: View {
    let text: String
    var body: some View {
        Text(text)
            .font(ForestixType.caption)
            .foregroundStyle(ForestixPalette.confidenceWarn)
            .accessibilityIdentifier("rawCapture.truthWarning")
    }
}

/// The typed truth is QUEUED against a bundle whose writer hasn't finished.
/// Queued is not durable — that writer can still fail and take the sidecar
/// with it — so the field deliberately keeps the value, and this line says
/// why it didn't clear. Neutral (not a warning colour): nothing is wrong yet.
/// Lives on the dark scan-screen dev block, hence the white tint.
struct TruthFieldPending: View {
    var body: some View {
        Text("Truth queued — waiting for the capture to finish writing")
            .font(ForestixType.caption)
            .foregroundStyle(.white.opacity(0.85))
            .accessibilityIdentifier("rawCapture.truthPending")
    }
}
