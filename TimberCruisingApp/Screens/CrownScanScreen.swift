// Crown scan — same conceptual flow as Height: aim the centre crosshair
// at four crown reference points (left edge, right edge, lowest branch,
// highest branch) and tap to capture each in turn. The estimator then
// returns crown width = horizontal(L, R) and crown height = vertical(T, B).
//
// LiDAR mesh raycast first, plane fallback. On non-LiDAR devices the
// scene mesh isn't available so plane fallback supplies the world point.

import SwiftUI
import Common
import Models
import Sensors
import AR
import simd

#if canImport(UIKit)
import UIKit
#endif

public struct CrownScanScreen: View {

    public enum Step: Int, CaseIterable {
        case left, right, bottom, top, done
    }

    @EnvironmentObject private var settings: AppSettings
    @StateObject private var session = ARKitSessionManager()
    @StateObject private var raycaster = ARCenterRaycaster()
    @Environment(\.scenePhase) private var scenePhase

    @State private var step: Step = .left
    @State private var leftPt:   SIMD3<Float>?
    @State private var rightPt:  SIMD3<Float>?
    @State private var bottomPt: SIMD3<Float>?
    @State private var topPt:    SIMD3<Float>?
    @State private var widthM:   Double?
    @State private var heightM:  Double?
    @State private var captureFailureReason: String?

    /// Fires when the cruiser explicitly accepts the result.
    public var onAccept: (_ widthM: Double, _ heightM: Double) -> Void

    public init(onAccept: @escaping (Double, Double) -> Void = { _, _ in }) {
        self.onAccept = onAccept
    }

    public var body: some View {
        ZStack {
            ARCameraView(manager: session,
                         debugMeshOverlay: true,
                         sceneMarkers: markers,
                         raycaster: raycaster)
                .ignoresSafeArea()
            overlayChrome

            // Right-centre capture button (hidden once the four points are
            // in and the result is shown).
            if step != .done {
                MeasureControlColumn(capture: { capture(step) })
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

            // Bottom-centre status / result panel.
            VStack {
                Spacer()
                bottomPanel
            }
        }
        .navigationTitle("Crown")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { session.run() }
        .onDisappear { session.pause() }
        .onChange(of: scenePhase) { _, phase in
            switch phase {
            case .active:                session.run()
            case .inactive, .background: session.pause()
            @unknown default:            break
            }
        }
    }

    // MARK: - Overlay chrome

    @ViewBuilder
    private var overlayChrome: some View {
        if let label = crosshairLabel {
            VStack(spacing: 8) {
                ZStack {
                    Circle()
                        .strokeBorder(Color.black.opacity(0.6), lineWidth: 4)
                        .frame(width: 40, height: 40)
                    Circle()
                        .strokeBorder(ForestixPalette.confidenceWarn,
                                      lineWidth: 2)
                        .frame(width: 36, height: 36)
                    Rectangle().fill(Color.black.opacity(0.6))
                        .frame(width: 16, height: 3.5)
                    Rectangle().fill(Color.black.opacity(0.6))
                        .frame(width: 3.5, height: 16)
                    Rectangle().fill(ForestixPalette.confidenceWarn)
                        .frame(width: 14, height: 1.5)
                    Rectangle().fill(ForestixPalette.confidenceWarn)
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
    }

    private var crosshairLabel: String? {
        switch step {
        case .left:   return "Aim at crown's LEFT edge"
        case .right:  return "Aim at crown's RIGHT edge"
        case .bottom: return "Aim at LOWEST branch"
        case .top:    return "Aim at HIGHEST branch"
        case .done:   return nil
        }
    }

    // MARK: - Bottom panel

    private var bottomPanel: some View {
        MeasureStatusPanel {
            if let reason = captureFailureReason {
                Text(reason)
                    .font(ForestixType.caption)
                    .foregroundStyle(.white)
            }
            Text(statusText)
                .font(.callout)
                .foregroundStyle(.white)
            if step == .done, let w = widthM, let h = heightM {
                Text(String(format: "Width %.2f m · Height %.2f m", w, h))
                    .font(ForestixType.dataLarge)
                    .foregroundStyle(.white)
            }
            actionRow
                .padding(.top, 2)
        }
    }

    private var statusText: String {
        switch step {
        case .left:   return "Aim at the crown's LEFT edge, tap +"
        case .right:  return "Aim at the RIGHT edge, tap +"
        case .bottom: return "Aim at the LOWEST branch, tap +"
        case .top:    return "Aim at the HIGHEST branch, tap +"
        case .done:   return "Crown width + height computed"
        }
    }

    @ViewBuilder
    private var actionRow: some View {
        if step == .done {
            HStack(spacing: 12) {
                Button("Retake") { reset() }
                    .buttonStyle(.bordered)
                Button("Accept") {
                    if let w = widthM, let h = heightM { onAccept(w, h) }
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("crownScan.accept")
            }
        } else if step != .left {
            // Allow restarting mid-capture.
            Button("Retake") { reset() }
                .buttonStyle(.bordered)
        }
    }

    // MARK: - Capture / state transitions

    private func capture(_ which: Step) {
        raycaster.preferLiDARMesh = settings.measurementSource == .lidar
        guard let hit = raycaster.screenCenterHit()
                ?? raycaster.forwardPointAtHorizontalDistance(8)
        else {
            captureFailureReason = "Couldn't read scene depth. Move slightly so the camera picks up surface texture, then tap again."
            return
        }
        captureFailureReason = nil
        switch which {
        case .left:   leftPt   = hit; step = .right
        case .right:  rightPt  = hit; step = .bottom
        case .bottom: bottomPt = hit; step = .top
        case .top:    topPt    = hit; computeResult()
        case .done:   break
        }
    }

    private func computeResult() {
        guard let l = leftPt, let r = rightPt,
              let b = bottomPt, let t = topPt else { return }
        // Width: horizontal (XZ) distance between L and R points.
        let dx = l.x - r.x
        let dz = l.z - r.z
        widthM = Double(sqrt(dx * dx + dz * dz))
        // Height: pure vertical (Y) delta between top and bottom.
        heightM = Double(abs(t.y - b.y))
        step = .done
    }

    private func reset() {
        leftPt = nil; rightPt = nil; bottomPt = nil; topPt = nil
        widthM = nil; heightM = nil
        captureFailureReason = nil
        step = .left
    }

    // MARK: - Markers

    private var markers: [ARSceneMarker] {
        var out: [ARSceneMarker] = []
        if let p = leftPt {
            out.append(ARSceneMarker(
                worldPosition: p,
                shape: .sphere(radiusM: 0.05),
                colorRGBA: SIMD4<Float>(1, 0.85, 0, 1)))
        }
        if let p = rightPt {
            out.append(ARSceneMarker(
                worldPosition: p,
                shape: .sphere(radiusM: 0.05),
                colorRGBA: SIMD4<Float>(1, 0.85, 0, 1)))
        }
        if let p = bottomPt {
            out.append(ARSceneMarker(
                worldPosition: p,
                shape: .sphere(radiusM: 0.05),
                colorRGBA: SIMD4<Float>(0.2, 0.7, 1, 1)))
        }
        if let p = topPt {
            out.append(ARSceneMarker(
                worldPosition: p,
                shape: .sphere(radiusM: 0.05),
                colorRGBA: SIMD4<Float>(0.2, 0.7, 1, 1)))
        }
        return out
    }
}
