// Shared AR-measurement control chrome — visual language aligned with
// SlashScan: a right-edge vertical stack of floating round buttons with
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

// MARK: - Shadow token (matches SlashScan's floating buttons)

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
/// record the point — the Measure-app / SlashScan record button.
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
/// matching the SlashScan floating control look. Used for mode + source.
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

/// Back-compat alias — older call sites pass a title + optional symbol.
public struct MeasurePillButton: View {
    private let title: String
    private let systemImage: String?
    private let action: () -> Void

    public init(_ title: String, systemImage: String? = nil, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.action = action
    }

    public var body: some View {
        MeasureCircleButton(systemImage: systemImage ?? "circle", caption: title, action: action)
    }
}

// MARK: - Right-edge control column

/// Capture button vertically centred on the trailing edge, with any extra
/// controls stacked just below it — the SlashScan right-rail layout.
public struct MeasureControlColumn<Extra: View>: View {
    private let capture: () -> Void
    private let captureSymbol: String
    private let extra: Extra

    public init(captureSymbol: String = "plus",
                capture: @escaping () -> Void,
                @ViewBuilder extra: () -> Extra = { EmptyView() }) {
        self.captureSymbol = captureSymbol
        self.capture = capture
        self.extra = extra()
    }

    public var body: some View {
        HStack {
            Spacer()
            VStack(spacing: 14) {
                MeasureCaptureButton(systemImage: captureSymbol, action: capture)
                extra
            }
            .padding(.trailing, 18)
        }
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
