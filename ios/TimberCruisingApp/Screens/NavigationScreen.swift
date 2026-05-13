// Spec §5.1 NavigationScreen. REQ-NAV-002/003/004.
//
// Compass arrow pointing to the planned plot, live distance readout,
// GPS tier badge (A/B/C/D) that recolors with accuracy, and a track-
// log toggle per session. The arrow rotates purely via CSS/SwiftUI
// transforms — no AR, no MapKit — so it's cheap under canopy where
// basemap tiles don't load.

import SwiftUI
import Models
import Positioning

public struct NavigationScreen: View {

    @StateObject private var viewModel: NavigationViewModel

    public init(viewModel: @autoclosure @escaping () -> NavigationViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            VStack(spacing: 24) {
                header
                Spacer()
                compass
                Spacer()
                distanceReadout
                trackLogToggle
                authStatusBanner
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
        .foregroundStyle(.white)
        .navigationTitle("Navigate")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Plot \(viewModel.target.plannedLabel)")
                    .font(.headline)
                Text(String(format: "%.5f, %.5f",
                            viewModel.target.plannedLat,
                            viewModel.target.plannedLon))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.6))
            }
            Spacer()
            tierBadge
        }
    }

    private var tierBadge: some View {
        Text("GPS \(viewModel.tier.rawValue)")
            .font(.caption.bold())
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(tierColor.opacity(0.25))
            .overlay(
                RoundedRectangle(cornerRadius: 6)
                    .stroke(tierColor, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .foregroundStyle(tierColor)
    }

    private var tierColor: Color {
        // 4 GPS tiers collapse to the design system's 3 instrument hues:
        // A → good, B/C → usable, D → bad. Keeps navigation-screen
        // colours consistent with the rest of the app (no raw system
        // .green/.yellow/.orange competing with muted tokens elsewhere).
        switch viewModel.tier {
        case .A: return ForestixPalette.confidenceOk
        case .B, .C: return ForestixPalette.confidenceWarn
        case .D: return ForestixPalette.confidenceBad
        }
    }

    // MARK: - Compass

    private var compass: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.25), lineWidth: 2)
                .frame(width: 220, height: 220)
            Text("N")
                .font(.caption.bold())
                .offset(y: -115)
            if let rot = viewModel.arrowRotationDeg {
                Arrow()
                    .fill(viewModel.hasArrived ? Color.green : Color.white)
                    .frame(width: 40, height: 140)
                    .rotationEffect(.degrees(rot))
            } else {
                Image(systemName: "location.slash")
                    .font(.system(size: 60))
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
    }

    private struct Arrow: Shape {
        func path(in rect: CGRect) -> Path {
            var p = Path()
            p.move(to: CGPoint(x: rect.midX, y: rect.minY))
            p.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
            p.addLine(to: CGPoint(x: rect.midX, y: rect.maxY - rect.height * 0.3))
            p.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
            p.closeSubpath()
            return p
        }
    }

    // MARK: - Bottom

    private var distanceReadout: some View {
        VStack(spacing: 4) {
            if let d = viewModel.distanceM {
                Text(String(format: "%.1f m", d))
                    .font(.system(size: 44, weight: .bold).monospacedDigit())
                if viewModel.hasArrived {
                    Text("Arrived — plot within \(Int(viewModel.arrivalRadiusM)) m")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            } else {
                Text("—")
                    .font(.system(size: 44, weight: .bold).monospacedDigit())
                    .foregroundStyle(.white.opacity(0.4))
            }
        }
    }

    private var trackLogToggle: some View {
        Toggle("Record track log", isOn: $viewModel.isTrackLogEnabled)
            .tint(.green)
            .font(.callout)
    }

    @ViewBuilder
    private var authStatusBanner: some View {
        switch viewModel.authStatus {
        case .denied, .restricted:
            Text("Location access denied — enable in Settings to navigate.")
                .font(.caption)
                .foregroundStyle(.red)
                .multilineTextAlignment(.center)
        case .notDetermined:
            Text("Requesting location permission…")
                .font(.caption)
                .foregroundStyle(.yellow)
        case .unsupported:
            Text("Location unsupported on this platform.")
                .font(.caption)
                .foregroundStyle(.orange)
        case .authorized, .authorizedWhenInUse:
            EmptyView()
        }
    }
}

private extension PlannedPlot {
    var plannedLabel: String {
        String(id.uuidString.prefix(6))
    }
}
