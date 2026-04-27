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
    @Environment(\.scenePhase) private var scenePhase
    public var onResult: (DBHResult) -> Void = { _ in }
    /// Fired when the cruiser explicitly accepts the on-screen result
    /// (state → .accepted). Use this for flows that want to persist the
    /// reading only after the cruiser has confirmed it — Quick Measure
    /// doesn't record a measurement until Accept is tapped.
    public var onAccept: (DBHResult) -> Void = { _ in }

    // Two independent diagnostic overlays — cruisers asked to see the
    // mesh and the VIO feature points at the same time, so they're no
    // longer mutually exclusive. Either, both, or neither can be on.
    @State private var meshOn: Bool
    @State private var pointsOn: Bool

    public init(viewModel: @autoclosure @escaping () -> DBHScanViewModel,
                onResult: @escaping (DBHResult) -> Void = { _ in },
                onAccept: @escaping (DBHResult) -> Void = { _ in },
                showMeshOverlay: Bool = false) {
        _viewModel = StateObject(wrappedValue: viewModel())
        self.onResult = onResult
        self.onAccept = onAccept
        // Both diagnostic overlays default ON — the cruiser asked to
        // see the mesh AND the feature points at the same time as soon
        // as the scan opens, instead of having to flip both toggles by
        // hand on every entry. The chips on the top-right still let
        // them turn either off if the overlay obscures the trunk.
        _meshOn   = State(initialValue: true)
        _pointsOn = State(initialValue: true)
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
                         debugMeshOverlay: meshOn,
                         debugPointsOverlay: pointsOn,
                         sceneMarkers: cylinderMarkers)
                .ignoresSafeArea()

            GeometryReader { geo in
                ZStack {
                    guideLine(height: geo.size.height)
                    fitChord(in: geo.size)
                    // Crosshair ring is now positioned by GeometryReader
                    // at exactly (centerX, midY) so the guide line
                    // passes through the centre of the ring, not above
                    // or below it. The live preview pills sit
                    // independently below — they're no longer in a
                    // VStack with the ring (which was pushing the ring
                    // off-centre).
                    crosshairRing
                        .position(x: geo.size.width / 2,
                                  y: geo.size.height / 2)
                    livePreviewBadge
                        .position(x: geo.size.width / 2,
                                  y: geo.size.height / 2
                                       + Self.crosshairOuterRadius
                                       + 28)
                }
                .frame(width: geo.size.width, height: geo.size.height)
            }
            .accessibilityElement(children: .ignore)

            // Screen-wide tap catcher — the spec's "tap trunk center"
            // gesture was missing from the view. Sits between the AR
            // feed and the bottom action panel so buttons still win.
            tapCatcher

            VStack(spacing: 0) {
                topStrip
                overlayPicker
                Spacer()
                bottomPanel
            }
        }
        .navigationTitle("Diameter")
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
        .onChange(of: scenePhase) { _, phase in
            // Stop the AR session + depth subscription when the user
            // backgrounds the app — without this the camera, LiDAR,
            // and Combine chain keep running inside a screen that's
            // no longer on screen, drain battery fast, and return
            // from background with a stale tracking state.
            switch phase {
            case .active:     viewModel.onAppear()
            case .inactive, .background: viewModel.onDisappear()
            @unknown default: break
            }
        }
    }

    // MARK: - Top status strip

    /// Thin row pinned just under the nav bar. Shows the GPS accuracy
    /// badge on the left so the cruiser can tell from the scan screen
    /// whether they're under canopy without leaving the flow.
    private var topStrip: some View {
        HStack {
            GPSAccuracyBadge()
            Spacer()
        }
        .padding(.horizontal, ForestixSpace.sm)
        .padding(.top, ForestixSpace.xs)
    }

    // MARK: - Diagnostic overlay toggles

    /// Two independent capsule toggles pinned to the top-right corner.
    /// Mesh and Points can be on at the same time — useful when the
    /// cruiser wants to judge both surface reconstruction AND tracking
    /// coverage at a single glance.
    private var overlayPicker: some View {
        HStack(spacing: ForestixSpace.xs) {
            Spacer()
            overlayChip(label: "Mesh",
                        systemImage: "square.grid.3x3",
                        isOn: $meshOn,
                        accessibilityId: "dbhScan.overlay.mesh")
            overlayChip(label: "Points",
                        systemImage: "circle.grid.3x3.fill",
                        isOn: $pointsOn,
                        accessibilityId: "dbhScan.overlay.points")
        }
        .padding(.trailing, ForestixSpace.sm)
        .padding(.top, ForestixSpace.xs)
    }

    private func overlayChip(label: String,
                             systemImage: String,
                             isOn: Binding<Bool>,
                             accessibilityId: String) -> some View {
        Button {
            isOn.wrappedValue.toggle()
        } label: {
            HStack(spacing: 4) {
                Image(systemName: systemImage)
                    .font(.system(size: 11, weight: .semibold))
                Text(label)
                    .font(.system(size: 12, weight: .semibold))
            }
            .padding(.horizontal, ForestixSpace.sm)
            .padding(.vertical, 6)
            .foregroundStyle(isOn.wrappedValue
                             ? Color.white
                             : Color.white.opacity(0.75))
            .background(
                Capsule()
                    .fill(isOn.wrappedValue
                          ? ForestixPalette.primary.opacity(0.85)
                          : Color.black.opacity(0.35))
            )
            .overlay(
                Capsule().stroke(Color.white.opacity(0.20), lineWidth: 0.5)
            )
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityId)
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
        // Dual-stroke line for sun-glare readability: a thin dark halo
        // under a bright white line. On either a bright sky or dark
        // foliage background, at least one of the two strokes has
        // enough contrast to stay visible.
        ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(height: 3)
            Rectangle()
                .fill(Color.white.opacity(0.9))
                .frame(height: 1.5)
        }
        .frame(height: 3)
        .position(x: UIScreenWidth() / 2, y: height / 2)
        .accessibilityIdentifier("dbhScan.guideLine")
    }

    /// Outer radius of the crosshair ring (including the dark halo).
    /// Used as a layout anchor for the live preview pills so they sit
    /// just below the ring without overlapping it.
    private static let crosshairOuterRadius: CGFloat = 36
    /// Vertical extent (above + below the chord) of the side
    /// indicators. Tall, deliberately visible bars — the cruiser
    /// asked for more pop on these so a "fit locked" reads at a
    /// glance even with the trunk only partly in frame.
    private static let chordIndicatorHalfHeight: CGFloat = 22

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
            let half = Self.chordIndicatorHalfHeight
            ZStack(alignment: .topLeading) {
                // Main chord line — slightly thicker so it reads
                // even when overdrawn on top of the LiDAR mesh.
                Rectangle()
                    .fill(ForestixPalette.confidenceOk.opacity(0.95))
                    .frame(width: x1 - x0, height: 4)
                    .position(x: (x0 + x1) / 2, y: y)
                // Left side indicator — tall vertical bar with a dark
                // halo for sun-readability. Length is 2 × half so it
                // pops well above and below the chord line.
                sideIndicator(x: x0, y: y, half: half)
                // Right side indicator
                sideIndicator(x: x1, y: y, half: half)
            }
            .accessibilityIdentifier("dbhScan.fitChord")
            .allowsHitTesting(false)
        }
    }

    /// Trunk-side indicator: dark halo bar with a coloured bar on
    /// top, centred at (x, y) and 2×half tall.
    private func sideIndicator(x: CGFloat, y: CGFloat, half: CGFloat) -> some View {
        ZStack {
            Rectangle()
                .fill(Color.black.opacity(0.55))
                .frame(width: 5, height: 2 * half)
            Rectangle()
                .fill(ForestixPalette.confidenceOk)
                .frame(width: 3, height: 2 * half)
        }
        .position(x: x, y: y)
    }

    private var crosshairRing: some View {
        let color: Color = viewModel.crosshairIsStable
            ? ForestixPalette.confidenceOk
            : ForestixPalette.confidenceBad
        let outer = Self.crosshairOuterRadius * 2     // 72 pt total
        let inner = outer - 8                          // ring inset
        return ZStack {
            // Dual-stroke ring — dark halo underneath the coloured
            // ring so the crosshair stays visible against both sky
            // and foliage. Sized to match the cruiser's request for
            // a clearly-visible target ring on the AR feed.
            Circle()
                .strokeBorder(Color.black.opacity(0.6), lineWidth: 5)
                .frame(width: outer, height: outer)
            Circle()
                .strokeBorder(color, lineWidth: 2.5)
                .frame(width: inner, height: inner)
        }
        .accessibilityIdentifier("dbhScan.crosshair")
        .accessibilityLabel(viewModel.crosshairIsStable
                            ? "Depth stable — tap to capture"
                            : "Aligning — move closer or steadier")
    }

    /// Two pills floating below the crosshair:
    ///   • "Ø ~ 34.5 cm"  — the diameter estimate (bold, primary)
    ///   • "1.25 m to center" — camera-to-stem-axis distance (dimmer)
    /// The diameter pill uses the ⌀ symbol (U+2300) rather than "DBH"
    /// because cruisers don't always measure at breast height —
    /// "diameter" is the generic term.
    @ViewBuilder
    private var livePreviewBadge: some View {
        if let cm = viewModel.previewDbhCm {
            VStack(spacing: 3) {
                Text(String(format: "⌀ ~ %.1f cm", cm))
                    .font(ForestixType.data)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Color.black.opacity(0.65))
                    .clipShape(Capsule())
                    .accessibilityIdentifier("dbhScan.livePreview")
                if let d = viewModel.distanceToStemCenterM {
                    Text(String(format: "%.2f m to center", d))
                        .font(ForestixType.dataSmall)
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.black.opacity(0.45))
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
        case .aligning:     return "Align guide to the trunk, uphill side. Tap the stem centre."
        case .armed:        return "Depth stable. Tap the trunk centre."
        case .capturing:    return "Capturing… hold steady."
        case .fitted:       return "Scan complete. Accept, retake, or add a second view."
        case .accepted:     return "Saved."
        case .rejected:     return viewModel.result?.rejectionReason
                                 ?? "Scan rejected. Try again."
        case .manualEntry:  return "Enter diameter manually in cm."
        }
    }

    @ViewBuilder
    private func resultPanel(_ r: DBHResult) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                // Monospaced number so the value aligns with the FIELD
                // LOG on the home screen — a cruiser reading the log
                // expects the same glyph widths everywhere.
                Text(String(format: "%.1f cm", r.diameterCm))
                    .font(ForestixType.dataLarge)
                    .foregroundStyle(.white)
                Spacer()
                tierChip(r.confidence)
            }
            Text(tierHint(r.confidence))
                .font(ForestixType.caption)
                .foregroundStyle(.white.opacity(0.9))
            Text(String(
                format: "arc %.0f°   rmse %.1f mm   σ_r %.1f mm   n %d",
                r.arcCoverageDeg, r.rmseMm, r.sigmaRmm, r.nInliers))
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white.opacity(0.65))
        }
        .foregroundStyle(.white)
        .accessibilityIdentifier("dbhScan.resultPanel")
    }

    /// Short cruiser-actionable sentence matching the tier. The spec
    /// metrics below stay for diagnostics; this line tells the cruiser
    /// what to actually do next.
    private func tierHint(_ tier: ConfidenceTier) -> String {
        switch tier {
        case .green:  return "Good — wide arc, low scatter. Safe to record."
        case .yellow: return "Fair — narrow arc or noisier fit. Consider a second pass."
        case .red:    return "Check — step left 1 m and retake, or enter manually."
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

    @ViewBuilder
    private var manualEntryPanel: some View {
        HStack {
            TextField("Diameter in cm", text: $viewModel.manualDbhCm)
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
