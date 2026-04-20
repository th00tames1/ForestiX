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
    /// When true, overlays the ARKit scene-reconstruction mesh on top of
    /// the camera feed so the cruiser can visually confirm that LiDAR is
    /// actually sampling the trunk surface. Safe on non-LiDAR devices
    /// (the overlay simply won't render anything).
    public var showMeshOverlay: Bool = false

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in },
                showMeshOverlay: Bool = false) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.showMeshOverlay = showMeshOverlay
    }

    public var body: some View {
        ZStack {
            // Live AR camera feed wired to the same ARSession the
            // DBHScanViewModel is consuming depth frames from. Snapshot
            // tests (macOS host) fall back to a black background via
            // ARCameraView's #else branch.
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: showMeshOverlay)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    guideLine(height: geo.size.height)
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

    /// Small pill floating below the crosshair with the live DBH
    /// estimate. Rendered only while a preview value is available
    /// (the VM clears it during capture / after accept / etc.).
    @ViewBuilder
    private var livePreviewBadge: some View {
        if let cm = viewModel.previewDbhCm {
            Text(String(format: "~ %.1f cm", cm))
                .font(.caption.bold())
                .monospacedDigit()
                .foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(.black.opacity(0.55))
                .clipShape(Capsule())
                .accessibilityIdentifier("dbhScan.livePreview")
        } else {
            Color.clear.frame(height: 20)
        }
    }

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
        let (label, color): (String, Color) = {
            switch tier {
            case .green:  return ("green",  .green)
            case .yellow: return ("yellow", .yellow)
            case .red:    return ("red",    .red)
            }
        }()
        return Text(label)
            .font(.caption2).bold()
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(color.opacity(0.3))
            .overlay(RoundedRectangle(cornerRadius: 6).stroke(color, lineWidth: 1))
            .cornerRadius(6)
            .foregroundStyle(color)
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
