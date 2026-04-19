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

public struct DBHScanScreen: View {

    @StateObject private var viewModel: DBHScanViewModel
    public var onResult: (DBHResult) -> Void = { _ in }

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in }) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
    }

    public var body: some View {
        ZStack {
            // AR view placeholder — real AR layer is added in Phase 2.1.
            Color.black.ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    guideLine(height: geo.size.height)
                    crosshair
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .accessibilityElement(children: .ignore)

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
        return Circle()
            .strokeBorder(color, lineWidth: 2)
            .frame(width: 28, height: 28)
            .accessibilityIdentifier("dbhScan.crosshair")
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
