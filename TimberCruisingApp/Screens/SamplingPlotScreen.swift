// Sampling plot — aim the centre crosshair at the plot centre and tap the
// "+" capture button to drop a short white stake plus a translucent ring
// marking the sampling boundary. Adjust the radius via the slider at the
// top. When the device moves outside the ring the border flashes red and
// the device vibrates until the cruiser walks back inside.
//
// UI matches the shared Measure-app layout: right-centre capture button,
// bottom-right LiDAR/AR toggle, and a compact centred status panel.

import SwiftUI
import Common
import Models
import Sensors
import AR
import simd

#if canImport(UIKit)
import UIKit
#endif

public struct SamplingPlotScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var session = ARKitSessionManager()
    @StateObject private var raycaster = ARCenterRaycaster()
    @Environment(\.scenePhase) private var scenePhase

    @State private var center: SIMD3<Float>?
    @State private var radiusM: Double = 8.0
    @State private var isOutside: Bool = false
    @State private var distanceFromCenterM: Double?
    @State private var pollTimer: Timer?
    @State private var flashOn: Bool = false
    @State private var captureFailureReason: String?
    #if canImport(UIKit)
    @State private var feedbackGen: UINotificationFeedbackGenerator?
    #endif

    // Stable marker ids so ARCameraView diffs (instead of tearing down and
    // rebuilding every marker on every body re-eval — the 0.2 s flash timer
    // re-evaluates `markers`, and fresh random UUIDs there made it rebuild
    // the 96-segment ring continuously, which tanked the frame rate).
    private static let baseSphereId = UUID(uuidString: "00000000-5A11-0000-0000-000000000001") ?? UUID()
    private static let poleId       = UUID(uuidString: "00000000-5A11-0000-0000-000000000002") ?? UUID()
    private static let topSphereId  = UUID(uuidString: "00000000-5A11-0000-0000-000000000003") ?? UUID()
    private static let ringId       = UUID(uuidString: "00000000-5A11-0000-0000-000000000004") ?? UUID()

    public init() {}

    public var body: some View {
        ZStack {
            ARCameraView(manager: session,
                         debugMeshOverlay: false,
                         sceneMarkers: markers,
                         raycaster: raycaster)
                .ignoresSafeArea()

            // Live red border flash whenever the device is outside the
            // sampling area.
            if isOutside {
                Rectangle()
                    .stroke(flashOn ? Color.red.opacity(0.95) : Color.red.opacity(0.35),
                            lineWidth: 12)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }

            if center == nil { centerCrosshair }

            // Top radius slider only.
            VStack(spacing: 0) {
                topControls
                Spacer()
            }

            // Right-centre capture button.
            MeasureControlColumn(capture: placePlotIfNeeded)

            // Bottom-right LiDAR/AR toggle.
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    MeasureSourceToggleButton()
                        .padding(.trailing, 18)
                        .padding(.bottom, 96)
                }
            }

            // Bottom-centre compact status panel.
            VStack {
                Spacer()
                bottomPanel
            }
        }
        .navigationTitle("Sampling Plot")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            session.run()
            startPolling()
            #if canImport(UIKit)
            feedbackGen = UINotificationFeedbackGenerator()
            feedbackGen?.prepare()
            #endif
        }
        .onDisappear {
            session.pause()
            stopPolling()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:                session.run(); startPolling()
            case .inactive, .background: session.pause(); stopPolling()
            @unknown default:            break
            }
        }
    }

    // MARK: - Top controls (radius slider)

    private var topControls: some View {
        VStack(spacing: 8) {
            HStack {
                Text("Sampling radius")
                    .font(ForestixType.sectionHead)
                    .tracking(1.2)
                    .foregroundStyle(.white.opacity(0.85))
                Spacer()
                Text(String(format: "%.1f m", radiusM))
                    .font(ForestixType.data)
                    .foregroundStyle(.white)
            }
            Slider(value: $radiusM, in: 1.0...30.0, step: 0.5)
                .tint(ForestixPalette.confidenceWarn)
                .accessibilityIdentifier("samplingPlot.radius")
        }
        .padding(10)
        .background(.ultraThinMaterial)
        .cornerRadius(10)
        .padding(.horizontal)
        .padding(.top, 8)
    }

    // MARK: - Bottom panel (centred, half-width)

    private var bottomPanel: some View {
        MeasureStatusPanel {
            if let reason = captureFailureReason {
                Text(reason)
                    .font(ForestixType.caption)
                    .foregroundStyle(.white)
            }
            Text(statusTitle)
                .font(.callout)
                .fontWeight(isOutside ? .bold : .regular)
                .foregroundStyle(.white)
            Text(distanceLine)
                .font(ForestixType.dataSmall)
                .foregroundStyle(.white.opacity(0.85))
            HStack(spacing: 12) {
                Button("Reset") { reset() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
                    .disabled(center == nil)
                Button("Save") { savePlot() }
                    .buttonStyle(.forestixProminent)
                    .frame(maxWidth: .infinity)
                    .disabled(center == nil)
                    .accessibilityIdentifier("samplingPlot.save")
            }
            .padding(.top, 2)
        }
    }

    private var statusTitle: String {
        if center == nil { return "Aim, then tap + to drop the plot centre" }
        return isOutside ? "Outside sampling area — walk back inside" : "Inside sampling area"
    }

    private var distanceLine: String {
        guard let d = distanceFromCenterM else { return "—" }
        return String(format: "Centre: %.2f m · area: %.1f m²",
                      d, .pi * radiusM * radiusM)
    }

    // MARK: - Center crosshair

    private var centerCrosshair: some View {
        ZStack {
            Circle().strokeBorder(Color.black.opacity(0.5), lineWidth: 4).frame(width: 36, height: 36)
            Circle().strokeBorder(Color.white, lineWidth: 2).frame(width: 32, height: 32)
            Rectangle().fill(Color.white).frame(width: 12, height: 1.5)
            Rectangle().fill(Color.white).frame(width: 1.5, height: 12)
        }
    }

    // MARK: - Plot placement

    private func placePlotIfNeeded() {
        guard center == nil else { return }
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(3.0)
        else {
            captureFailureReason = "Couldn't read scene depth. Aim at the ground and try again."
            return
        }
        captureFailureReason = nil
        center = hit
    }

    private func reset() {
        center = nil
        isOutside = false
        distanceFromCenterM = nil
        flashOn = false
    }

    private func savePlot() {
        guard center != nil else { return }
        let area = .pi * radiusM * radiusM
        history.append(QuickMeasureEntry(
            kind: .samplingPlot,
            value: radiusM,
            secondaryValue: area,
            sigma: nil,
            confidenceRaw: "green",
            method: "ar.tap",
            plotID: history.activePlotID))
    }

    // MARK: - Polling (distance + flash + haptics)

    private func startPolling() {
        pollTimer?.invalidate()
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
            Task { @MainActor in
                tickOutsideCheck()
                flashOn.toggle()
            }
        }
    }

    private func stopPolling() {
        pollTimer?.invalidate()
        pollTimer = nil
    }

    @MainActor
    private func tickOutsideCheck() {
        guard let center else {
            isOutside = false
            distanceFromCenterM = nil
            return
        }
        guard let camPos = currentCameraPosition() else { return }
        let dx = camPos.x - center.x
        let dz = camPos.z - center.z
        let d = Double(sqrt(dx * dx + dz * dz))
        distanceFromCenterM = d
        let nowOutside = d > radiusM
        if nowOutside != isOutside {
            isOutside = nowOutside
            if nowOutside { triggerOutsideHaptics() }
        }
        if isOutside, flashOn { triggerOutsideHaptics() }
    }

    private func currentCameraPosition() -> SIMD3<Float>? {
        #if canImport(ARKit) && os(iOS)
        guard let view = raycaster.arview,
              let frame = view.session.currentFrame
        else { return nil }
        let t = frame.camera.transform
        return SIMD3<Float>(t.columns.3.x, t.columns.3.y, t.columns.3.z)
        #else
        return nil
        #endif
    }

    private func triggerOutsideHaptics() {
        #if canImport(UIKit)
        feedbackGen?.notificationOccurred(.warning)
        feedbackGen?.prepare()
        #endif
    }

    // MARK: - Scene markers

    private var markers: [ARSceneMarker] {
        guard let c = center else { return [] }
        // Stable ids → ARCameraView only rebuilds a marker when its position
        // or shape changes (e.g. radius), never on the flash timer. The ring
        // colour is kept constant (in/out is shown by the red border flash +
        // the panel) so an in/out flip doesn't churn the geometry either.
        return [
            // Exact-centre red sphere so the tapped point is unmistakable.
            ARSceneMarker(id: Self.baseSphereId,
                          worldPosition: c,
                          shape: .sphere(radiusM: 0.07),
                          colorRGBA: SIMD4<Float>(1, 0.25, 0.25, 1)),
            // Tall white pole rising from the tapped point.
            ARSceneMarker(id: Self.poleId,
                          worldPosition: SIMD3<Float>(c.x, c.y + 0.6, c.z),
                          shape: .cylinder(radiusM: 0.05, heightM: 1.2),
                          colorRGBA: SIMD4<Float>(1, 1, 1, 1)),
            // Bright top sphere — visible from across the plot.
            ARSceneMarker(id: Self.topSphereId,
                          worldPosition: SIMD3<Float>(c.x, c.y + 1.2, c.z),
                          shape: .sphere(radiusM: 0.12),
                          colorRGBA: SIMD4<Float>(1, 0.85, 0.15, 1)),
            // Thick boundary ring (constant cyan).
            ARSceneMarker(id: Self.ringId,
                          worldPosition: SIMD3<Float>(c.x, c.y + 0.02, c.z),
                          shape: .ring(radiusM: Float(radiusM), thicknessM: 0.4),
                          colorRGBA: SIMD4<Float>(0.2, 0.85, 1, 1)),
        ]
    }
}
