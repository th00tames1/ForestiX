// Distance measurement — two modes:
//
//   • Live distance — device-to-object distance at the crosshair, updated
//     every frame.
//   • Two-point distance — tap once to drop point A, tap again for B; the
//     overlay draws a thick white-bordered line with a yellow point at
//     each end and a centred distance label in a rounded white pill.
//
// Source toggle (LiDAR / AR):
//   • LiDAR (default when supported) — scene-mesh raycast.
//   • AR — estimated-plane raycast (works on non-LiDAR devices). When the
//     device has no LiDAR the toggle starts on AR and the LiDAR button is
//     disabled.
//
// The screen logs every accepted reading to `QuickMeasureHistory` as a
// `.distance` kind so it appears in Field Log and CSV / bundle exports.

import SwiftUI
import Common
import Models
import Sensors
import AR
import simd

#if canImport(UIKit)
import UIKit
#endif

public struct DistanceMeasureScreen: View {

    @EnvironmentObject private var history: QuickMeasureHistory
    @EnvironmentObject private var settings: AppSettings
    @StateObject private var session = ARKitSessionManager()
    @StateObject private var raycaster = ARCenterRaycaster()
    @Environment(\.scenePhase) private var scenePhase

    public enum Mode: String, CaseIterable, Identifiable {
        case live = "Live"
        case twoPoint = "Two-point"
        public var id: String { rawValue }
    }

    @State private var mode: Mode = .live

    /// Live continuous distance from device to whatever the crosshair
    /// hits. Updated by a timer at ~20 Hz.
    @State private var liveDistanceM: Double?

    /// Two-point screen positions (in the AR view's screen space) +
    /// world positions. Screen positions drive the overlay drawing; the
    /// world positions are the source of truth for the actual distance.
    @State private var pointA: SIMD3<Float>?
    @State private var pointB: SIMD3<Float>?
    @State private var screenA: CGPoint?
    @State private var screenB: CGPoint?

    @State private var captureFailureReason: String?
    @State private var liveTimer: Timer?

    public init() {}

    public var body: some View {
        GeometryReader { proxy in
            ZStack {
                // No 3D sphere markers — the points + line are drawn as a
                // crisp 2D overlay (below) that tracks the projected world
                // points, so nothing floats away from the markers.
                ARCameraView(manager: session,
                             debugMeshOverlay: false,
                             sceneMarkers: [],
                             raycaster: raycaster)
                    .ignoresSafeArea()

                // Center crosshair always visible — that's the active probe
                // the "+" button captures against.
                centerCrosshair

                // Two-point overlay (line + endpoints + label).
                twoPointOverlay(in: proxy.size)

                // Right-centre capture button + Live/Two-point toggle beneath.
                MeasureControlColumn(capture: { capture(in: proxy.size) }) {
                    MeasurePillButton(mode == .live ? "Live" : "2-Pt",
                                      systemImage: "arrow.left.and.right") {
                        mode = (mode == .live) ? .twoPoint : .live
                        resetTwoPoint()
                    }
                }

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

                // Bottom-centre status / value panel.
                VStack {
                    Spacer()
                    bottomPanel(in: proxy.size)
                }
            }
        }
        .navigationTitle("Distance")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear {
            session.run()
            startLiveTimer()
        }
        .onDisappear {
            session.pause()
            stopLiveTimer()
        }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:                session.run(); startLiveTimer()
            case .inactive, .background: session.pause(); stopLiveTimer()
            @unknown default:            break
            }
        }
    }

    // MARK: - Capture (+) button handler

    /// The "+" button captures against the centre crosshair. In Live mode
    /// it logs the current device→target distance; in Two-point mode it
    /// drops the next point (A, then B, then restarts).
    private func capture(in size: CGSize) {
        switch mode {
        case .live:
            saveLiveReading()
        case .twoPoint:
            let centre = CGPoint(x: size.width / 2, y: size.height / 2)
            placeTwoPointTap(at: centre, in: size)
        }
    }

    // MARK: - Centre crosshair

    private var centerCrosshair: some View {
        ZStack {
            Circle()
                .strokeBorder(Color.black.opacity(0.5), lineWidth: 4)
                .frame(width: 36, height: 36)
            Circle()
                .strokeBorder(Color.white, lineWidth: 2)
                .frame(width: 32, height: 32)
            Rectangle().fill(Color.black.opacity(0.6))
                .frame(width: 14, height: 3)
            Rectangle().fill(Color.black.opacity(0.6))
                .frame(width: 3, height: 14)
            Rectangle().fill(Color.white)
                .frame(width: 12, height: 1.5)
            Rectangle().fill(Color.white)
                .frame(width: 1.5, height: 12)
        }
        .accessibilityHidden(true)
    }

    // MARK: - Two-point overlay

    @ViewBuilder
    private func twoPointOverlay(in size: CGSize) -> some View {
        if mode == .twoPoint {
            ZStack {
                // Line A → B (thick white border).
                if let a = currentScreenPoint(for: pointA, fallback: screenA, in: size),
                   let b = currentScreenPoint(for: pointB, fallback: screenB, in: size) {
                    Path { p in
                        p.move(to: a); p.addLine(to: b)
                    }
                    .stroke(Color.white,
                            style: StrokeStyle(lineWidth: 7,
                                               lineCap: .round))
                    Path { p in
                        p.move(to: a); p.addLine(to: b)
                    }
                    .stroke(Color.yellow,
                            style: StrokeStyle(lineWidth: 3.5,
                                               lineCap: .round))
                }

                // Point markers.
                if let a = currentScreenPoint(for: pointA, fallback: screenA, in: size) {
                    pointDot.position(a)
                }
                if let b = currentScreenPoint(for: pointB, fallback: screenB, in: size) {
                    pointDot.position(b)
                }

                // Centred distance label between A and B.
                if let a = currentScreenPoint(for: pointA, fallback: screenA, in: size),
                   let b = currentScreenPoint(for: pointB, fallback: screenB, in: size),
                   let d = twoPointDistanceM {
                    distancePill(text: formatDistance(d))
                        .position(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
                }
            }
            .allowsHitTesting(false)
        }
    }

    private var pointDot: some View {
        ZStack {
            Circle()
                .fill(Color.white)
                .frame(width: 22, height: 22)
            Circle()
                .fill(Color.yellow)
                .frame(width: 16, height: 16)
        }
    }

    private func distancePill(text: String) -> some View {
        Text(text)
            .font(.system(size: 16, weight: .bold, design: .monospaced))
            .foregroundStyle(Color.black)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.white))
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.black.opacity(0.25), lineWidth: 1))
            .shadow(color: Color.black.opacity(0.25), radius: 3, x: 0, y: 1)
    }

    /// Project a stored world position to the current screen position
    /// when possible (so the points / line track the scene as the
    /// camera moves). Falls back to the screen position captured at
    /// tap time if projection isn't available.
    private func currentScreenPoint(for world: SIMD3<Float>?,
                                    fallback screen: CGPoint?,
                                    in size: CGSize) -> CGPoint? {
        if let projected = projectToScreen(world, viewSize: size) {
            return projected
        }
        return screen
    }

    // MARK: - Bottom panel

    @ViewBuilder
    private func bottomPanel(in size: CGSize) -> some View {
        MeasureStatusPanel {
            if let reason = captureFailureReason {
                Text(reason)
                    .font(ForestixType.caption)
                    .foregroundStyle(.white)
            }
            Text(mode == .live ? "DEVICE → TARGET" : "POINT A → POINT B")
                .font(ForestixType.sectionHead)
                .tracking(1.2)
                .foregroundStyle(.white.opacity(0.75))
            Text(currentDistanceString)
                .font(ForestixType.dataLarge)
                .foregroundStyle(.white)
            if mode == .twoPoint {
                Text(twoPointHint)
                    .font(ForestixType.caption)
                    .foregroundStyle(.white.opacity(0.85))
            }
            actionRow
                .padding(.top, 2)
        }
    }

    private var currentDistanceString: String {
        let value = mode == .live ? liveDistanceM : twoPointDistanceM
        return value.map(formatDistance) ?? "—"
    }

    private var twoPointHint: String {
        switch (pointA, pointB) {
        case (nil, _):       return "Aim, tap + to place point A"
        case (_, nil):       return "Aim, tap + to place point B"
        default:             return "Tap Save to log this reading"
        }
    }

    @ViewBuilder
    private var actionRow: some View {
        switch mode {
        case .live:
            Button("Save") { saveLiveReading() }
                .buttonStyle(.borderedProminent)
                .disabled(liveDistanceM == nil)
                .accessibilityIdentifier("distance.saveLive")
        case .twoPoint:
            HStack(spacing: 12) {
                Button("Reset") { resetTwoPoint() }
                    .buttonStyle(.bordered)
                Button("Save") { saveTwoPointReading() }
                    .buttonStyle(.borderedProminent)
                    .disabled(twoPointDistanceM == nil)
                    .accessibilityIdentifier("distance.saveTwoPoint")
            }
        }
    }

    // MARK: - Capture handlers

    private func placeTwoPointTap(at screenPoint: CGPoint,
                                  in size: CGSize) {
        guard let hit = hitForCurrentSource() else {
            captureFailureReason = "No surface in view. Move slightly so the camera picks up texture, then tap again."
            return
        }
        captureFailureReason = nil
        if pointA == nil {
            pointA = hit
            screenA = screenPoint
        } else if pointB == nil {
            pointB = hit
            screenB = screenPoint
        } else {
            // Third tap restarts: drop A, treat new point as A.
            pointA = hit
            screenA = screenPoint
            pointB = nil
            screenB = nil
        }
    }

    private func resetTwoPoint() {
        pointA = nil; pointB = nil; screenA = nil; screenB = nil
        captureFailureReason = nil
    }

    private func saveLiveReading() {
        guard let d = liveDistanceM else { return }
        history.append(QuickMeasureEntry(
            kind: .distance,
            value: d,
            sigma: nil,
            confidenceRaw: "green",
            method: "live.\(settings.measurementSource.displayName.lowercased())",
            plotID: history.activePlotID))
    }

    private func saveTwoPointReading() {
        guard let d = twoPointDistanceM else { return }
        history.append(QuickMeasureEntry(
            kind: .distance,
            value: d,
            sigma: nil,
            confidenceRaw: "green",
            method: "two-point.\(settings.measurementSource.displayName.lowercased())",
            plotID: history.activePlotID))
        resetTwoPoint()
    }

    // MARK: - Source-aware raycast

    private func hitForCurrentSource() -> SIMD3<Float>? {
        // Drive the raycaster's mesh preference from the LiDAR/AR toggle.
        // In LiDAR mode the mesh raycast runs first; in AR mode it's
        // skipped and we use the estimated-plane path (plus a forward-ray
        // fallback so a tap still lands somewhere on featureless aim).
        switch settings.measurementSource {
        case .lidar:
            raycaster.preferLiDARMesh = true
            return raycaster.screenCenterHit()
        case .ar:
            raycaster.preferLiDARMesh = false
            return raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(3.0)
        }
    }

    // MARK: - Live distance polling

    private func startLiveTimer() {
        liveTimer?.invalidate()
        liveTimer = Timer.scheduledTimer(withTimeInterval: 0.05,
                                          repeats: true) { _ in
            Task { @MainActor in
                liveDistanceM = currentDeviceToCenterDistance()
            }
        }
    }

    private func stopLiveTimer() {
        liveTimer?.invalidate()
        liveTimer = nil
    }

    /// Distance from the AR camera (device) to whatever the screen-
    /// centre raycast hits. nil when no surface is in view.
    private func currentDeviceToCenterDistance() -> Double? {
        #if canImport(ARKit) && os(iOS)
        // Keep the live readout on the same path the toggle selects.
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit(),
              let view = raycaster.arview,
              let frame = view.session.currentFrame
        else { return nil }
        let t = frame.camera.transform
        let cam = SIMD3<Float>(t.columns.3.x,
                                t.columns.3.y,
                                t.columns.3.z)
        return Double(simd_distance(cam, hit))
        #else
        return nil
        #endif
    }

    // MARK: - Derived two-point distance

    private var twoPointDistanceM: Double? {
        guard let a = pointA, let b = pointB else { return nil }
        return Double(simd_distance(a, b))
    }

    // MARK: - Helpers

    private func formatDistance(_ m: Double) -> String {
        if m < 1 { return String(format: "%.0f cm", m * 100) }
        return String(format: "%.2f m", m)
    }

    /// Project a stored world-space point onto the current camera's
    /// screen plane so two-point markers track the scene as the camera
    /// moves. Returns nil on non-iOS hosts or when projection fails.
    private func projectToScreen(_ p: SIMD3<Float>?,
                                 viewSize: CGSize) -> CGPoint? {
        guard let p else { return nil }
        #if canImport(ARKit) && os(iOS)
        guard let view = raycaster.arview else { return nil }
        let projected = view.project(p)
        guard let projected else { return nil }
        return CGPoint(x: projected.x, y: projected.y)
        #else
        _ = viewSize
        return nil
        #endif
    }

    // Scene markers intentionally omitted — the two-point points + line are
    // rendered as a 2D overlay (twoPointOverlay) that tracks the projected
    // world points, so there are no oversized 3D spheres drifting from the
    // drawn markers.
}
