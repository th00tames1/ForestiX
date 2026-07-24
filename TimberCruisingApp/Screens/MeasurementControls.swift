// Shared AR-measurement control chrome — visual language built around
// a right-edge vertical stack of floating round buttons with
// soft drop shadows (offset .zero, opacity ~0.3, radius 2–4) over the live
// camera, white-tinted SF Symbols, plus a centred half-width readout.
//
//   • Back button  — 44 pt floating circle pinned top-left; the AR screens
//                    run full-bleed with the system nav bar hidden, so this
//                    is the only exit affordance.
//   • Capture "+"  — 70 pt solid-white record-style button (the primary).
//   • Secondary    — 52 pt flat-scrim circular icon buttons stacked under
//                    the capture button (mode toggle, etc.).
//   • Source toggle — 52 pt circular icon button pinned bottom-right.
//   • Status panel — centred, capped-width, flat black scrim.
//
// Scrims are flat black 0.55 (no materials) so the chrome renders
// pixel-identical to the Android app's Compose overlays.

import SwiftUI

// MARK: - Shadow token (for the floating buttons)

private extension View {
    func floatingShadow() -> some View {
        shadow(color: .black.opacity(0.3), radius: 3, x: 0, y: 0)
    }
}

// MARK: - Back button (top-left, every AR screen)

/// Circular floating back button pinned top-left so every full-bleed AR
/// screen has a consistent, always-visible exit affordance over the
/// camera feed (the system nav bar is hidden there). `dismiss` resolves
/// against whichever presentation is active, so the same button pops a
/// NavigationStack push and closes a fullScreenCover alike. Mirrors the
/// Android `MeasureBackButton`.
public struct MeasureBackButton: View {
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        Button {
            dismiss()
        } label: {
            ZStack {
                Circle().fill(Color.black.opacity(0.55)).frame(width: 44, height: 44)
                Circle().stroke(.white.opacity(0.18), lineWidth: 0.5).frame(width: 44, height: 44)
                Image(systemName: "chevron.left")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .floatingShadow()
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Back")
        .accessibilityIdentifier("measure.back")
    }
}

/// Top-leading placement wrapper for `MeasureBackButton` — leading 16 /
/// top 16 below the safe area, matching the Android chrome. Drop this
/// into the screen's root ZStack.
public struct MeasureBackButtonRow: View {
    public init() {}

    public var body: some View {
        VStack {
            HStack {
                MeasureBackButton()
                Spacer()
            }
            .padding(.leading, ForestixSpace.md)
            .padding(.top, ForestixSpace.md)
            Spacer()
        }
    }
}

// MARK: - Capture (+) button

/// Big circular capture button. Aim the centre crosshair, tap this to
/// record the point — the camera-style record button.
public struct MeasureCaptureButton: View {
    private let systemImage: String
    private let action: () -> Void

    public init(systemImage: String = "plus", action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(.white).frame(width: 70, height: 70)
                Circle().stroke(.black.opacity(0.10), lineWidth: 1).frame(width: 70, height: 70)
                Image(systemName: systemImage)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(ForestixPalette.primary)
            }
            .floatingShadow()
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("measure.capture")
    }
}

// MARK: - Circular secondary button (icon + optional caption)

/// 52 pt flat-scrim circular icon button with an optional caption beneath,
/// matching a floating control look. Used for mode + source.
public struct MeasureCircleButton: View {
    private let systemImage: String
    private let caption: String?
    private let dim: Bool
    private let action: () -> Void

    public init(systemImage: String,
                caption: String? = nil,
                dim: Bool = false,
                action: @escaping () -> Void) {
        self.systemImage = systemImage
        self.caption = caption
        self.dim = dim
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            VStack(spacing: 3) {
                ZStack {
                    Circle().fill(Color.black.opacity(0.55)).frame(width: 52, height: 52)
                    Circle().stroke(.white.opacity(0.18), lineWidth: 0.5).frame(width: 52, height: 52)
                    Image(systemName: systemImage)
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(.white.opacity(dim ? 0.55 : 1))
                }
                .floatingShadow()
                if let caption {
                    Text(caption)
                        .font(.system(size: 10, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.white.opacity(dim ? 0.55 : 0.95))
                        .shadow(color: .black.opacity(0.5), radius: 1)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Top instruction banner (U1)

/// Stage-guidance banner pinned top-centre on every AR measure screen —
/// the instruction strings moved OUT of the bottom status panel so the
/// bottom edge is free for the camera-app shutter row. Locked geometry
/// (identical on Android): black 0.65 fill, white 14 medium text,
/// corner radius 10, max width 340, horizontally centred at 150 below
/// the safe area (clears the GPS-badge / mini-map row).
///
/// `extra` renders below the banner inside the same 340 pt column —
/// used for the orange failure/unsupported banners so warnings travel
/// with the guidance instead of the value panel.
public struct MeasureTopBanner<Extra: View>: View {
    private let text: String?
    private let extra: Extra

    public init(_ text: String?,
                @ViewBuilder extra: () -> Extra = { EmptyView() }) {
        self.text = text
        self.extra = extra()
    }

    public var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: 8) {
                if let text, !text.isEmpty {
                    Text(text)
                        .font(.system(size: 14, weight: .medium))
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(Color.black.opacity(0.65),
                                    in: RoundedRectangle(cornerRadius: 10,
                                                         style: .continuous))
                        .floatingShadow()
                        // Guidance text must never swallow taps meant
                        // for the AR view (caliper edge taps); the
                        // `extra` warnings stay interactive (Height's
                        // anchor-failure banner clears on tap).
                        .allowsHitTesting(false)
                        .accessibilityIdentifier("measure.topBanner")
                }
                extra
            }
            .frame(maxWidth: 340)
            .padding(.top, 150)
            .padding(.horizontal, ForestixSpace.md)
            Spacer()
        }
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Bottom-centre shutter row (U2)

/// Camera-app capture layout: the 70 pt white shutter bottom-centre
/// (16 above the safe area), flanked left/right by the screens' 52 pt
/// secondary rail buttons in fixed-width slots so the shutter stays
/// exactly centred whether zero, one, or two flanks are present.
public struct MeasureShutterRow: View {

    /// One 52 pt flank button (icon + caption) beside the shutter.
    /// `dim` renders it at reduced opacity (e.g. the two-point Save
    /// before both points exist) without removing the slot.
    public struct Flank {
        let systemImage: String
        let caption: String
        let dim: Bool
        let action: () -> Void

        public init(systemImage: String, caption: String,
                    dim: Bool = false,
                    action: @escaping () -> Void) {
            self.systemImage = systemImage
            self.caption = caption
            self.dim = dim
            self.action = action
        }
    }

    private let showsShutter: Bool
    private let captureSymbol: String
    private let capture: () -> Void
    private let leading: Flank?
    private let trailing: Flank?

    public init(showsShutter: Bool = true,
                captureSymbol: String = "plus",
                capture: @escaping () -> Void,
                leading: Flank? = nil,
                trailing: Flank? = nil) {
        self.showsShutter = showsShutter
        self.captureSymbol = captureSymbol
        self.capture = capture
        self.leading = leading
        self.trailing = trailing
    }

    public var body: some View {
        // Fixed 52 pt side slots, 28 pt gaps (Android parity): the
        // shutter stays exactly centred whether zero, one, or two flanks
        // are present, and flank circles centre against the shutter.
        HStack(spacing: 28) {
            flankSlot(leading)
            if showsShutter {
                MeasureCaptureButton(systemImage: captureSymbol,
                                     action: capture)
            } else {
                // Caliper-style flows capture by tapping the AR view —
                // keep the slot so the flanks hold their positions.
                Color.clear.frame(width: 70, height: 70)
            }
            flankSlot(trailing)
        }
        .padding(.bottom, 16)
        .frame(maxWidth: .infinity)
    }

    @ViewBuilder
    private func flankSlot(_ flank: Flank?) -> some View {
        ZStack {
            Color.clear.frame(width: 52, height: 70)
            if let flank {
                MeasureCircleButton(systemImage: flank.systemImage,
                                    caption: flank.caption,
                                    dim: flank.dim,
                                    action: flank.action)
            }
        }
    }
}

// MARK: - Live-value pill (U2 value strip)

/// One line of the compact live-value strip directly above the shutter
/// row — the dark-glass capsule language of the under-crosshair pills.
/// `large` renders `ForestixType.dataLarge` (the primary value line);
/// default lines are `dataSmall`; `dimmed` drops to 0.85 white on a
/// lighter 0.45 scrim. Identical to Android's MeasureValuePill.
public struct MeasureValuePill: View {
    private let text: String
    private let large: Bool
    private let dimmed: Bool

    public init(_ text: String, large: Bool = false, dimmed: Bool = false) {
        self.text = text
        self.large = large
        self.dimmed = dimmed
    }

    public var body: some View {
        Text(text)
            .font(large ? ForestixType.dataLarge : ForestixType.dataSmall)
            .foregroundStyle(.white.opacity(dimmed ? 0.85 : 1))
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.black.opacity(dimmed ? 0.45 : 0.65),
                        in: Capsule())
    }
}

// MARK: - LiDAR / AR source toggle button (bottom-right)

/// Circular icon button that flips `AppSettings.measurementSource`. The
/// icon + caption show the active path; disabled (and shown as AR) on
/// devices without LiDAR. Developer-mode chrome only — field mode pins
/// LiDAR devices to the mesh path with no user-facing switch, so the
/// measurement screens render this solely when `settings.developerMode`.
public struct MeasureSourceToggleButton: View {
    @EnvironmentObject private var settings: AppSettings

    public init() {}

    public var body: some View {
        let source = settings.measurementSource
        let supported = settings.deviceSupportsLiDAR
        MeasureCircleButton(
            systemImage: source == .lidar ? "cube.transparent" : "camera.viewfinder",
            caption: source.displayName,
            dim: !supported
        ) {
            guard supported else { return }
            settings.measurementSource = (source == .lidar) ? .ar : .lidar
        }
        .accessibilityIdentifier("measurement.sourceToggle")
    }
}

// MARK: - Centred, half-width status panel

/// Compact readout pinned to the bottom-centre. Capped width with centre-
/// aligned content so it no longer spans the whole bottom edge. Sits a
/// fixed 16 pt above the bottom safe-area inset on both platforms.
public struct MeasureStatusPanel<Content: View>: View {
    private let content: Content

    public init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    public var body: some View {
        VStack(spacing: 6) {
            content
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: 340)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(.white.opacity(0.12), lineWidth: 0.5)
        )
        .floatingShadow()
        .padding(.bottom, 16)
    }
}
