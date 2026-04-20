// Spec §5.3 HeightScan layout + §4.4 state machine.
//
// Five stages render distinct chrome over a black AR placeholder:
//   1. anchorSet        — "Touch phone to tree base" + [Anchor Here]
//   2. walking          — live d_h + "Move back X m" hint + [Continue]
//   3. aimTopArmed      — crosshair on sky + [Aim Top]
//   4. aimBaseArmed     — crosshair on ground + [Aim Base]
//   5. computed         — H ± σ_H panel + [Retake] / [Accept]
//
// Per Phase 2 Decision #5 (carried over) the AR view is a deterministic
// black placeholder so snapshot tests compare only the overlay chrome.
// The real ARView is layered in when the Phase 3 device path lands.

import SwiftUI
import Common
import Models
import Sensors
import AR

public struct HeightScanScreen: View {

    @StateObject private var viewModel: HeightScanViewModel
    public var onResult: (HeightResult) -> Void = { _ in }

    public init(viewModel: @autoclosure @escaping () -> HeightScanViewModel,
                onResult: @escaping (HeightResult) -> Void = { _ in }) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
    }

    public var body: some View {
        ZStack {
            // Live AR camera feed shared with the HeightScanViewModel's
            // session — without this the cruiser couldn't see the tree
            // base / top to aim at.
            ARCameraView(manager: viewModel.session)
                .ignoresSafeArea()
            overlayChrome
            VStack {
                Spacer()
                bottomPanel
            }
        }
        .navigationTitle("Height")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.onAppear() }
        .onDisappear { viewModel.onDisappear() }
        .onChange(of: viewModel.result?.heightM) { _, newValue in
            if newValue != nil, let r = viewModel.result {
                onResult(r)
            }
        }
    }

    // MARK: - Overlay chrome per stage

    @ViewBuilder
    private var overlayChrome: some View {
        switch viewModel.state {
        case .aimTopArmed:
            crosshair(label: "Aim at treetop")
                .accessibilityIdentifier("heightScan.crosshair.top")
        case .aimBaseArmed:
            crosshair(label: "Aim at tree base")
                .accessibilityIdentifier("heightScan.crosshair.base")
        default:
            EmptyView()
        }
    }

    private func crosshair(label: String) -> some View {
        VStack(spacing: 8) {
            Circle()
                .strokeBorder(Color.yellow, lineWidth: 2)
                .frame(width: 36, height: 36)
            Text(label)
                .font(.caption.bold())
                .foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(.black.opacity(0.5))
                .cornerRadius(4)
        }
    }

    // MARK: - Bottom panel

    @ViewBuilder
    private var bottomPanel: some View {
        VStack(spacing: 12) {
            if viewModel.trackingDroppedDuringMeasurement {
                bannerView(
                    "AR tracking dropped during measurement.",
                    tint: .orange)
                    .accessibilityIdentifier("heightScan.trackingBanner")
            }
            statusBanner
            stagePanel
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
            .accessibilityIdentifier("heightScan.statusBanner")
    }

    private var statusText: String {
        switch viewModel.state {
        case .idle, .anchorSet:   return "Touch phone to tree base."
        case .walking:            return "Walk back. Live d_h shown below."
        case .aimTopArmed:        return "Aim at treetop, then tap Aim Top."
        case .aimTopCaptured:     return "Top captured."
        case .aimBaseArmed:       return "Aim at tree base, then tap Aim Base."
        case .computed:           return "Height computed."
        case .accepted:           return "Saved."
        case .rejected:           return viewModel.result?.rejectionReason
                                       ?? "Rejected."
        case .manualEntry:        return "Enter height manually in metres."
        }
    }

    // MARK: - Stage-specific content

    @ViewBuilder
    private var stagePanel: some View {
        switch viewModel.state {
        case .walking:
            walkingReadout
        case .computed, .rejected:
            if let r = viewModel.result { resultPanel(r) }
        case .manualEntry:
            manualEntryPanel
        default:
            EmptyView()
        }
    }

    private var walkingReadout: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(String(format: "d_h = %.1f m", viewModel.dhMeters))
                .font(.title3).bold()
                .foregroundStyle(.white)
            Text(walkHintText)
                .font(.callout)
                .foregroundStyle(.yellow)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("heightScan.walkingReadout")
    }

    private var walkHintText: String {
        let delta = viewModel.walkHintMeters
        let expected = viewModel.expectedHeightM
        if delta > 0.1 {
            return "Move back \(String(format: "%.1f", delta)) m "
                   + "(target ≈ 0.6–1.0 · \(Int(expected)) m)"
        } else if delta < -0.1 {
            return "Move forward \(String(format: "%.1f", -delta)) m"
        } else {
            return "You're in the sweet-spot band. Continue."
        }
    }

    @ViewBuilder
    private func resultPanel(_ r: HeightResult) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("H = \(String(format: "%.1f", r.heightM)) m "
                     + "± \(String(format: "%.1f", r.sigmaHm)) m")
                    .font(.title3).bold()
                Spacer()
                tierChip(r.confidence)
            }
            Text(String(
                format: "d_h = %.1f m, α_top = %.1f°, α_base = %.1f°",
                r.dHm,
                Double(r.alphaTopRad) * 180 / .pi,
                Double(r.alphaBaseRad) * 180 / .pi))
                .font(.caption)
                .foregroundStyle(.white.opacity(0.9))
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("heightScan.resultPanel")
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

    private var manualEntryPanel: some View {
        HStack {
            TextField("Height in metres", text: $viewModel.manualHeightM)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .keyboardType(.decimalPad)
                #endif
                .accessibilityIdentifier("heightScan.manualInput")
            Button("Save") { viewModel.submitManualEntry() }
                .buttonStyle(.borderedProminent)
        }
    }

    // MARK: - Actions

    @ViewBuilder
    private var actionRow: some View {
        switch viewModel.state {
        case .idle, .anchorSet:
            HStack(spacing: 12) {
                Button("Anchor Here") { viewModel.anchorHereNow() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("heightScan.anchorButton")
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.bordered)
            }
        case .walking:
            HStack(spacing: 12) {
                Button("Continue") { viewModel.continueToAimTop() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("heightScan.continueButton")
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
            }
        case .aimTopArmed, .aimTopCaptured:
            HStack(spacing: 12) {
                Button("Aim Top") { viewModel.captureTopNow() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("heightScan.aimTopButton")
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
            }
        case .aimBaseArmed:
            HStack(spacing: 12) {
                Button("Aim Base") { viewModel.captureBaseNow() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("heightScan.aimBaseButton")
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
            }
        case .computed:
            HStack(spacing: 12) {
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
                Button("Manual") { viewModel.enterManualEntry() }
                    .buttonStyle(.bordered)
                Spacer()
                Button("Accept") { viewModel.accept() }
                    .buttonStyle(.borderedProminent)
                    .disabled(viewModel.result?.confidence == .red)
                    .accessibilityIdentifier("heightScan.acceptButton")
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
        case .accepted:
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
    }
}
