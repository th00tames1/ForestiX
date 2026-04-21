// Spec §5.2 authoritative DBH scan layout + §4.3 state machine.
//
// Layout is the overlay chrome specified in §5.2:
//   • AR view (or black placeholder in previews/macOS) fills the screen.
//   • Fixed horizontal guide line at y = screen_height/2.
//   • Center crosshair, red until depth-stable then green.
//   • Status banner + result panel at the bottom.
//   • Action row: Retake / Manual / Dual-view / Accept — shown per state.
//
// Per Phase 2 decision #5, snapshot tests only render the overlay chrome;
// the AR view is Color.black so visuals are deterministic across hosts.

import SwiftUI
import Common
import Models
import Sensors
import AR

public struct DBHScanScreen: View {

    @StateObject private var viewModel: DBHScanViewModel
    public var onResult: (DBHResult) -> Void = { _ in }
    /// Fired when the cruiser explicitly accepts the on-screen result
    /// (state → .accepted). Use this for flows that want to persist the
    /// reading only after the cruiser has confirmed it — Quick Measure
    /// doesn't record a measurement until Accept is tapped.
    public var onAccept: (DBHResult) -> Void = { _ in }
    /// When true, overlays the ARKit scene-reconstruction mesh on top of
    /// the camera feed so the cruiser can visually confirm that LiDAR is
    /// actually sampling the trunk surface. Safe on non-LiDAR devices
    /// (the overlay simply won't render anything).
    public var showMeshOverlay: Bool = false

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in },
                onAccept: @escaping (DBHResult) -> Void = { _ in },
                showMeshOverlay: Bool = false) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
        self.showMeshOverlay = showMeshOverlay
    }

    public var body: some View {
        ZStack {
            // Live AR camera feed wired to the same ARSession the
            // DBHScanViewModel is consuming depth frames from. Snapshot
            // tests (macOS host) fall back to a black background via
            // ARCameraView's #else branch. The cylinder marker renders
            // the live single-frame fit as a translucent blue cylinder
            // at the trunk's world position — world-anchored, so it
            // stays locked to the tree as the phone moves.
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: showMeshOverlay,
                         sceneMarkers: cylinderMarkers)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    guideLine(height: geo.size.height)
                    fitChord(in: geo.size)
                    crosshair
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .accessibilityElement(children: .ignore)

            // Screen-wide tap catcher — the spec's "tap trunk center"
            // gesture was missing from the view. Sits between the AR
            // feed and the bottom action panel so buttons still win.
            tapCatcher

            VStack {
                Spacer()
                bottomPanel
            }
        }
        .navigationTitle("DBH Scan")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.onAppear() }
        .onDisappear { viewModel.onDisappear() }
        .onChange(of: viewModel.result?.diameterCm) { _, newValue in
            // Fire the host callback as soon as the VM publishes a result.
            // The host (e.g. AddTreeFlowScreen) decides whether to dismiss
            // the cover or stay on screen for retake.
            if newValue != nil, let r = viewModel.result {
                onResult(r)
            }
        }
        .onChange(of: viewModel.state) { _, newState in
            // Separate "accept" hook so callers that want to persist
            // only on an explicit user confirmation (Quick Measure) can
            // distinguish a fitted preview from a committed reading.
            if newState == .accepted, let r = viewModel.result {
                onAccept(r)
            }
        }
    }

    // MARK: - Tap capture

    /// Transparent overlay that forwards taps on the AR region to the
    /// view model. The bottom action panel sits above this on the
    /// Z-axis, so button taps continue to work — this catcher only
    /// receives taps that miss the panel.
    private var tapCatcher: some View {
        Color.clear
            .contentShape(Rectangle())
            .accessibilityIdentifier("dbhScan.tapCatcher")
            .onTapGesture {
                // The crosshair sits at screen centre and the ViewModel
                // uses `guideRow = depth.height / 2` for the fit, so a
                // tap pixel at the depth-map centre aligns the fit with
                // the crosshair the cruiser just lined up on the trunk.
                let frame = viewModel.session.latestDepthFrame
                let width  = Double(frame?.width  ?? 256)
                let height = Double(frame?.height ?? 192)
                viewModel.tap(at: SIMD2(width / 2.0, height / 2.0))
            }
    }

    // MARK: - Chrome

    private func guideLine(height: CGFloat) -> some View {
        Rectangle()
            .fill(Color(white: 0.5).opacity(0.5))
            .frame(height: 1.5)
            .position(x: UIScreenWidth() / 2, y: height / 2)
            .accessibilityIdentifier("dbhScan.guideLine")
    }

    /// Bright green horizontal segment spanning the trunk edges the
    /// single-frame fit identified along the guide row — a visual
    /// "this is what I'm measuring" so the cruiser can tell when the
    /// fit has locked onto the trunk vs wandered into the background.
    /// Only drawn once the crosshair has stabilised; hidden during
    /// capture / fit / accept so it doesn't distract from the result.
    @ViewBuilder
    private func fitChord(in size: CGSize) -> some View {
        if let fit = viewModel.previewFit,
           viewModel.crosshairIsStable,
           fit.stripRightFraction > fit.stripLeftFraction {
            let x0 = size.width * CGFloat(fit.stripLeftFraction)
            let x1 = size.width * CGFloat(fit.stripRightFraction)
            let y  = size.height / 2
            ZStack(alignment: .topLeading) {
                // Main chord line
                Rectangle()
                    .fill(Color.green.opacity(0.9))
                    .frame(width: x1 - x0, height: 3)
                    .position(x: (x0 + x1) / 2, y: y)
                // Left end cap
                Rectangle()
                    .fill(Color.green)
                    .frame(width: 2, height: 16)
                    .position(x: x0, y: y)
                // Right end cap
                Rectangle()
                    .fill(Color.green)
                    .frame(width: 2, height: 16)
                    .position(x: x1, y: y)
            }
            .accessibilityIdentifier("dbhScan.fitChord")
            .allowsHitTesting(false)
        }
    }

    private var crosshair: some View {
        let color: Color = viewModel.crosshairIsStable ? .green : .red
        return VStack(spacing: 6) {
            Circle()
                .strokeBorder(color, lineWidth: 2)
                .frame(width: 28, height: 28)
                .accessibilityIdentifier("dbhScan.crosshair")
            livePreviewBadge
        }
    }

    /// Two pills floating below the crosshair:
    ///   • "DBH ~ 34.5 cm"  — the diameter estimate (bold, primary)
    ///   • "1.25 m to center" — camera-to-stem-axis distance (dimmer)
    /// The two values were previously unlabelled "~ 34.5 cm" + "1.25 m"
    /// which looked like two distances. Making the diameter explicit
    /// as "DBH" removes that ambiguity.
    @ViewBuilder
    private var livePreviewBadge: some View {
        if let cm = viewModel.previewDbhCm {
            VStack(spacing: 3) {
                Text(String(format: "DBH ~ %.1f cm", cm))
                    .font(.caption.bold())
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.black.opacity(0.55))
                    .clipShape(Capsule())
                    .accessibilityIdentifier("dbhScan.livePreview")
                if let d = viewModel.distanceToStemCenterM {
                    Text(String(format: "%.2f m to center", d))
                        .font(.caption2)
                        .monospacedDigit()
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(.black.opacity(0.40))
                        .clipShape(Capsule())
                        .accessibilityIdentifier("dbhScan.distanceBadge")
                }
            }
        } else {
            Color.clear.frame(height: 40)
        }
    }

    // MARK: - AR markers

    /// Blue translucent cylinder rendered at the live preview fit.
    /// The cylinder is 1 m tall centred on the guide-row world Y so
    /// it visually "sleeves" the trunk at DBH height. Empty when no
    /// preview is available.
    private var cylinderMarkers: [ARSceneMarker] {
        guard let fit = viewModel.previewFit,
              let y = viewModel.guideRowWorldY
        else { return [] }
        let pos = SIMD3<Float>(
            Float(fit.centerWorldXZ.x),
            y,
            Float(fit.centerWorldXZ.y))   // SIMD2.y = world Z
        return [
            ARSceneMarker(
                id: Self.cylinderMarkerId,
                worldPosition: pos,
                shape: .cylinder(radiusM: Float(fit.radiusM), heightM: 1.0),
                colorRGBA: SIMD4(0.30, 0.65, 1.00, 0.45))
        ]
    }

    /// Stable UUID so the cylinder anchor doesn't get torn down and
    /// rebuilt on every frame — just its position / radius updates.
    /// Must be a valid hex UUID: 0-9 / a-f only, no alphabetic filler.
    /// The `?? UUID()` fallback keeps a typo from crashing the scan
    /// screen — worst case the cylinder anchor gets rebuilt each
    /// frame instead of diffed, which is ugly but not fatal.
    private static let cylinderMarkerId: UUID =
        UUID(uuidString: "00DBC415-0000-0000-0000-000000000001") ?? UUID()

    // MARK: - Bottom panel

    @ViewBuilder
    private var bottomPanel: some View {
        VStack(spacing: 12) {
            if let banner = viewModel.unsupportedBanner {
                bannerView(banner, tint: .orange)
            }
            statusBanner
            if let result = viewModel.result, viewModel.state != .manualEntry {
                resultPanel(result)
            }
            if viewModel.state == .manualEntry {
                manualEntryPanel
            }
            actionRow
        }
        .padding()
        .background(.ultraThinMaterial)
        .cornerRadius(12)
        .padding(.horizontal)
        .padding(.bottom, 24)
    }

    private var statusBanner: some View {
        Text(statusText)
            .font(.callout)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("dbhScan.statusBanner")
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle:         return "Starting camera…"
        case .aligning:     return "Align guide to DBH, uphill side. Tap stem center."
        case .armed:        return "Depth stable. Tap the trunk center."
        case .capturing:    return "Capturing… hold steady."
        case .fitted:       return "Scan complete. Accept, retake, or add a second view."
        case .accepted:     return "Saved."
        case .rejected:     return viewModel.result?.rejectionReason
                                 ?? "Scan rejected. Try again."
        case .manualEntry:  return "Enter DBH manually in cm."
        }
    }

    @ViewBuilder
    private func resultPanel(_ r: DBHResult) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("DBH: \(String(format: "%.1f", r.diameterCm)) cm")
                    .font(.title3).bold()
                Spacer()
                tierChip(r.confidence)
            }
            Text(String(
                format: "Arc: %.0f°   RMSE: %.1f mm   σ_r: %.1f mm   n: %d",
                r.arcCoverageDeg, r.rmseMm, r.sigmaRmm, r.nInliers))
                .font(.caption)
                .foregroundStyle(.white.opacity(0.9))
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("dbhScan.resultPanel")
    }

    private func tierChip(_ tier: ConfidenceTier) -> some View {
        let d = ConfidenceStyle.descriptor(for: tier.rawValue)
        return Text(d.label.uppercased())
            .font(.system(size: 10, weight: .semibold, design: .default))
            .tracking(0.8)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .overlay(
                RoundedRectangle(cornerRadius: ForestixRadius.chip,
                                 style: .continuous)
                    .stroke(d.color, lineWidth: 0.75)
            )
            .foregroundStyle(d.color)
    }

    @ViewBuilder
    private var manualEntryPanel: some View {
        HStack {
            TextField("DBH in cm", text: $viewModel.manualDbhCm)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier("dbhScan.manualInput")
            Button("Save") { viewModel.submitManualEntry() }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("dbhScan.manualSave")
        }
    }

    // MARK: - Actions

    @ViewBuilder
    private var actionRow: some View {
        switch viewModel.state {
        case .fitted:
            HStack(spacing: 12) {
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.bordered)
                Button("Dual-view") { /* v0.3+ */ }
                    .buttonStyle(.bordered).disabled(true)
                Spacer()
                Button("Accept") { viewModel.accept() }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.result?.confidence == .red)
            }
        case .rejected:
            HStack(spacing: 12) {
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.borderedProminent)
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.bordered)
            }
        case .manualEntry:
            HStack(spacing: 12) {
                Button("Cancel") { viewModel.retake() }
                    .buttonStyle(.bordered)
            }
        case .idle, .aligning, .armed, .capturing, .accepted:
            EmptyView()
        }
    }

    private func bannerView(_ text: String, tint: Color) -> some View {
        Text(text)
            .font(.callout).bold()
            .foregroundStyle(.white)
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(tint.opacity(0.8))
            .cornerRadius(8)
            .accessibilityIdentifier("dbhScan.unsupportedBanner")
    }

    // Cross-platform screen width accessor — UIScreen is iOS-only.
    private func UIScreenWidth() -> CGFloat {
        #if canImport(UIKit)
        return UIScreen.main.bounds.width
        #else
        return 400
        #endif
    }
}
