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
    @Environment(\.scenePhase) private var scenePhase
    @EnvironmentObject private var settings: AppSettings
    public var onResult: (HeightResult) -> Void = { _ in }
    /// Fires when the cruiser explicitly accepts the result shown on
    /// screen (state → .accepted). Hosts that want to persist only on
    /// user confirmation should use this instead of `onResult`.
    /// `metadata` carries optional species / damage / note attached
    /// via `ScanMetadataSheet`.
    public var onAccept: (HeightResult, ScanMetadata) -> Void = { _, _ in }

    public struct ScanMetadata {
        public var speciesCode: String?
        public var damageCodes: [String]
        public var note: String
        public init(speciesCode: String? = nil,
                    damageCodes: [String] = [],
                    note: String = "") {
            self.speciesCode = speciesCode
            self.damageCodes = damageCodes
            self.note = note
        }
    }

    @State private var metaSpecies: String?
    @State private var metaDamage: [String] = []
    @State private var metaNote: String = ""
    @State private var presentingMetadata = false
    /// When true, overlays the ARKit scene-reconstruction mesh on top of
    /// the camera feed — useful visual confirmation that LiDAR is
    /// sampling the scene while the cruiser walks off and aims.
    public var showMeshOverlay: Bool = false

    public init(viewModel: @autoclosure @escaping () -> HeightScanViewModel,
                onResult: @escaping (HeightResult) -> Void = { _ in },
                onAccept: @escaping (HeightResult, ScanMetadata) -> Void = { _, _ in },
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
            VStack(spacing: 0) {
                // Same GPS-accuracy strip as the Diameter scan — gives
                // the cruiser a single-glance read on canopy quality
                // before they anchor.
                HStack {
                    GPSAccuracyBadge()
                    Spacer()
                }
                .padding(.horizontal, ForestixSpace.sm)
                .padding(.top, ForestixSpace.xs)
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
                let meta = ScanMetadata(
                    speciesCode: metaSpecies,
                    damageCodes: metaDamage,
                    note: metaNote)
                onAccept(r, meta)
            }
        }
        .sheet(isPresented: $presentingMetadata) {
            ScanMetadataSheet(
                kind: .height,
                speciesCode: $metaSpecies,
                position: .constant(nil),
                damageCodes: $metaDamage,
                note: $metaNote)
        }
        .onChange(of: scenePhase) { _, phase in
            // Same rationale as DBH scan: without this the ARKit
            // session, CoreMotion pitch buffer, and depth subscription
            // all keep running while the app is backgrounded.
            switch phase {
            case .active:     viewModel.onAppear()
            case .inactive, .background: viewModel.onDisappear()
            @unknown default: break
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
        case .idle, .anchorSet: return "Aim at trunk (eye level)"
        case .walking:          return "Walk back — aim stays on tree"
        case .aimTopArmed:      return "Aim at treetop"
        case .aimBaseArmed:     return "Aim at trunk + ground"
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
            // Dual-stroke + dark halo for sun-glare readability: a
            // plain yellow ring disappears against sky. The black
            // halo underneath gives the chrome contrast against any
            // background.
            ZStack {
                Circle()
                    .strokeBorder(Color.black.opacity(0.6), lineWidth: 4)
                    .frame(width: 40, height: 40)
                Circle()
                    .strokeBorder(ForestixPalette.confidenceWarn, lineWidth: 2)
                    .frame(width: 36, height: 36)
                Rectangle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 16, height: 3.5)
                Rectangle()
                    .fill(Color.black.opacity(0.6))
                    .frame(width: 3.5, height: 16)
                Rectangle()
                    .fill(ForestixPalette.confidenceWarn)
                    .frame(width: 14, height: 1.5)
                Rectangle()
                    .fill(ForestixPalette.confidenceWarn)
                    .frame(width: 1.5, height: 14)
            }
            Text(label)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 4)
                .background(Color.black.opacity(0.65))
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
            if let reason = viewModel.anchorFailureReason {
                bannerView(reason, tint: .orange)
                    .accessibilityIdentifier("heightScan.anchorFailureBanner")
                    .onTapGesture { viewModel.clearAnchorFailure() }
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
        case .idle, .anchorSet:   return "Aim at the trunk at eye level, then tap Anchor Here."
        case .walking:            return "Walk back. Live walk-back distance shown below."
        case .aimTopArmed:        return "Aim at the treetop, then tap Aim Top."
        case .aimTopCaptured:     return "Top captured."
        case .aimBaseArmed:       return "Aim at where the trunk meets the ground, then tap Aim Base."
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
            Text("Walked back " + MeasurementFormatter.distance(
                m: Double(viewModel.dhMeters), in: settings.unitSystem))
                .font(ForestixType.dataLarge)
                .foregroundStyle(.white)
            Text(walkHintText)
                .font(ForestixType.caption)
                .foregroundStyle(ForestixPalette.confidenceWarn)
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
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                // Rejected results carry heightM = 0 from HeightEstimator
                // because the two-tangent formula goes wild outside its
                // operating envelope (close range, near-vertical aim).
                // Show a dash instead of the misleading number — the
                // rejection reason in the status banner explains why.
                if r.confidence == .red {
                    Text("—")
                        .font(ForestixType.dataLarge)
                        .foregroundStyle(.white)
                } else {
                    Text(MeasurementFormatter.height(
                        m: Double(r.heightM), in: settings.unitSystem))
                        .font(ForestixType.dataLarge)
                        .foregroundStyle(.white)
                    Text(MeasurementFormatter.heightSigma(
                        m: Double(r.sigmaHm), in: settings.unitSystem))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(.white.opacity(0.75))
                }
                Spacer()
                tierChip(r.confidence)
            }
            Text(tierHint(r.confidence))
                .font(ForestixType.caption)
                .foregroundStyle(.white.opacity(0.9))
            HStack {
                Spacer()
                Button {
                    presentingMetadata = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "tag")
                            .font(.system(size: 11, weight: .semibold))
                        Text(metadataChipLabel)
                            .font(ForestixType.dataSmall)
                    }
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .overlay(Capsule().stroke(.white.opacity(0.4), lineWidth: 0.5))
                    .foregroundStyle(.white)
                }
                .accessibilityIdentifier("heightScan.editMetadata")
            }
            .padding(.top, 2)
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("heightScan.resultPanel")
    }

    private var metadataChipLabel: String {
        var bits: [String] = []
        if let s = metaSpecies, !s.isEmpty { bits.append(s) }
        if !metaDamage.isEmpty { bits.append("\(metaDamage.count) tag") }
        if bits.isEmpty { return "Add details" }
        return bits.joined(separator: " · ")
    }

    /// Actionable one-liner per tier — same pattern as the Diameter
    /// result panel so the cruiser gets consistent guidance.
    private func tierHint(_ tier: ConfidenceTier) -> String {
        switch tier {
        case .green:  return "Good — geometry in sweet spot."
        case .yellow: return "Fair — long walk-off or steep aim. Acceptable."
        case .red:    return "Check — retake, or enter a tape estimate manually."
        }
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

    private var manualEntryPanel: some View {
        HStack {
            TextField(settings.unitSystem == .metric
                      ? "Height in metres"
                      : "Height in feet",
                      text: $viewModel.manualHeightM)
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

    /// Anchor tap — cruiser stands 1–3 m from the tree, aims the
    /// crosshair at the trunk's base, and taps. The screen-centre
    /// raycast (LiDAR mesh first, plane fallback) returns the 3D
    /// world point of that trunk-base; the view model stores it as
    /// the anchor. If the raycast misses, the view model surfaces
    /// `anchorFailureReason` and the screen banner explains how to
    /// reframe.
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
