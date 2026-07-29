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

/// Per-entry unit switch that sits next to a typed ground-truth field.
///
/// The field opens in the cruiser's ACTIVE unit system; this changes it for
/// THIS entry only, and the field's label is driven from the same value, so
/// what is typed and what is read can never disagree. The button shows the
/// unit currently in force — the cruiser reads the state, not the action.
///
/// CROSS-PLATFORM: same square, same unit text, same accessibility wording as the
/// Android `TruthUnitToggle`.
///
/// The 32 pt square is what is DRAWN; the tappable area around it is 44 pt,
/// Apple's minimum touch target (Android's twin uses 48 dp, that platform's
/// own minimum). The visual size is a matter of fitting the row; the touch
/// target is a matter of hitting it with a glove on.
struct TruthUnitToggle: View {

    /// Which surface the toggle is sitting on. The scan panels are a dark
    /// camera overlay and the field log is a standard light Form — the same
    /// white-on-white that once made the scan-screen truth fields invisible
    /// would happen here if one styling served both.
    enum Chrome {
        case darkPanel
        case form
    }

    let unit: TruthInput.Unit
    let onToggle: () -> Void
    /// Identifier suffixed per screen so a UI test can name the one it means.
    let identifier: String
    var chrome: Chrome = .darkPanel

    private var ink: Color {
        chrome == .darkPanel ? .white : ForestixPalette.textPrimary
    }
    private var fill: Color {
        chrome == .darkPanel ? .white.opacity(0.12) : ForestixPalette.surfaceRaised
    }
    private var stroke: Color {
        chrome == .darkPanel ? .white.opacity(0.4) : ForestixPalette.divider
    }

    var body: some View {
        Button(action: onToggle) {
            Text(unit.rawValue)
                .font(ForestixType.caption)
                .foregroundStyle(ink)
                .frame(width: 32, height: 32)
                .background(
                    RoundedRectangle(cornerRadius: 5)
                        .fill(fill)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 5)
                        .stroke(stroke, lineWidth: 0.5)
                )
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // One string, not a label + a hint: the Android sibling exposes a
        // single content description, and the two must read identically.
        .accessibilityLabel("Unit for this entry: \(unit.rawValue). Tap to switch.")
        .accessibilityIdentifier(identifier)
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
