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
import simd

public struct HeightScanScreen: View {

    @StateObject private var viewModel: HeightScanViewModel
    @StateObject private var raycaster = ARCenterRaycaster()
    public var onResult: (HeightResult) -> Void = { _ in }
    /// Fires when the cruiser explicitly accepts the result shown on
    /// screen (state → .accepted). Hosts that want to persist only on
    /// user confirmation should use this instead of `onResult`.
    public var onAccept: (HeightResult) -> Void = { _ in }
    /// When true, overlays the ARKit scene-reconstruction mesh on top of
    /// the camera feed — useful visual confirmation that LiDAR is
    /// sampling the scene while the cruiser walks off and aims.
    public var showMeshOverlay: Bool = false

    public init(viewModel: @autoclosure @escaping () -> HeightScanViewModel,
                onResult: @escaping (HeightResult) -> Void = { _ in },
                onAccept: @escaping (HeightResult) -> Void = { _ in },
                showMeshOverlay: Bool = false) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
        self.showMeshOverlay = showMeshOverlay
    }

    public var body: some View {
        ZStack {
            // Live AR camera feed shared with the HeightScanViewModel's
            // session. The scene markers come from the VM and pin the
            // anchor / top / base reference points in world space so
            // the cruiser can pan away and come back without losing
            // track of where the measurement started. The raycaster
            // captures a weak ref to the ARView so button handlers can
            // turn "cruiser tapped while aiming here" into a world hit.
            ARCameraView(manager: viewModel.session,
                         debugMeshOverlay: showMeshOverlay,
                         sceneMarkers: viewModel.sceneMarkers,
                         raycaster: raycaster)
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
        .onChange(of: viewModel.state) { _, newState in
            if newState == .accepted, let r = viewModel.result {
                onAccept(r)
            }
        }
    }

    // MARK: - Overlay chrome per stage

    /// Always-visible centre crosshair so the cruiser can see exactly
    /// which world point each button will capture. Label changes with
    /// state to explain what the next tap will do.
    @ViewBuilder
    private var overlayChrome: some View {
        if let label = crosshairLabel {
            crosshair(label: label)
                .accessibilityIdentifier(crosshairIdentifier)
        }
    }

    private var crosshairLabel: String? {
        switch viewModel.state {
        case .idle, .anchorSet: return "Aim at tree base"
        case .walking:          return "Walk back — aim stays on tree"
        case .aimTopArmed:      return "Aim at treetop"
        case .aimBaseArmed:     return "Aim at tree base"
        case .aimTopCaptured,
             .computed,
             .rejected,
             .accepted,
             .manualEntry:
            return nil
        }
    }

    private var crosshairIdentifier: String {
        switch viewModel.state {
        case .aimTopArmed:  return "heightScan.crosshair.top"
        case .aimBaseArmed: return "heightScan.crosshair.base"
        default:            return "heightScan.crosshair"
        }
    }

    /// Ring + cross mark — the cross explicitly pinpoints the world
    /// pixel a raycast will sample from, making "what am I actually
    /// tagging" unambiguous.
    private func crosshair(label: String) -> some View {
        VStack(spacing: 8) {
            ZStack {
                Circle()
                    .strokeBorder(Color.yellow, lineWidth: 2)
                    .frame(width: 36, height: 36)
                Rectangle()
                    .fill(Color.yellow)
                    .frame(width: 14, height: 1.5)
                Rectangle()
                    .fill(Color.yellow)
                    .frame(width: 1.5, height: 14)
            }
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
                Button("Anchor Here") { anchorTap() }
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
                Button("Aim Top") { aimTopTap() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("heightScan.aimTopButton")
                Button("Retake") { viewModel.retake() }
                    .buttonStyle(.bordered)
            }
        case .aimBaseArmed:
            HStack(spacing: 12) {
                Button("Aim Base") { aimBaseTap() }
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

    // MARK: - Tap handlers with raycast

    /// Anchor tap — the crosshair is on the tree base, so screen-centre
    /// raycasts the ground plane and that hit becomes the anchor.
    /// Without a hit we fall back to the camera position (spec flow).
    private func anchorTap() {
        viewModel.anchorHereNow(screenCenterHit: raycaster.screenCenterHit())
    }

    /// Aim Top — crosshair on treetop. The sky has no plane, so the
    /// raycast will almost always miss. Instead, project the camera's
    /// forward ray out to the known horizontal distance `d_h` so the
    /// yellow marker lands roughly at the treetop the cruiser aimed at.
    private func aimTopTap() {
        let hit = raycaster.screenCenterHit()
            ?? raycaster.forwardPointAtHorizontalDistance(viewModel.dhMeters)
        viewModel.captureTopNow(screenCenterHit: hit)
    }

    /// Aim Base — crosshair near the ground at the tree base. Ground
    /// raycast should nearly always hit. Fall back to the same forward-
    /// projection as Aim Top on the rare miss.
    private func aimBaseTap() {
        let hit = raycaster.screenCenterHit()
            ?? raycaster.forwardPointAtHorizontalDistance(viewModel.dhMeters)
        viewModel.captureBaseNow(screenCenterHit: hit)
    }
}
