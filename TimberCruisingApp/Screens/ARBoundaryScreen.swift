// Spec §5.1 + §7.8 + REQ-BND-001..004.
//
// Two states:
//   • center not set — "Stand at the plot center, then tap Set Center".
//   • center set     — overlay shows radius + live distance to center;
//                      15 m drift banner appears when user walks out.
//
// The RealityKit ring render is the iOS-only responsibility of the
// Sensors/ARKit glue layer that owns the live ARView; this screen
// renders a deterministic black backdrop + chrome so snapshot tests
// and macOS previews are meaningful. Tap handler calls
// `setCenterAtCurrentCamera()` on iOS, falls back to a synthetic
// `setCenter(.zero)` on macOS so the center-set branch is still
// exercisable from previews.

import SwiftUI
import simd
import Common
import Sensors
import AR

public struct ARBoundaryScreen: View {

    @StateObject private var viewModel: ARBoundaryViewModel

    public init(viewModel: @autoclosure @escaping () -> ARBoundaryViewModel) {
        _viewModel = StateObject(wrappedValue: viewModel())
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            centerReticle
            VStack {
                Spacer()
                bottomPanel
            }
        }
        .navigationTitle("Plot Boundary")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .onAppear { viewModel.onAppear() }
        .onDisappear { viewModel.onDisappear() }
    }

    // MARK: - Reticle

    @ViewBuilder
    private var centerReticle: some View {
        if viewModel.centerWorld == nil {
            VStack(spacing: 8) {
                Circle()
                    .strokeBorder(Color.yellow, lineWidth: 2)
                    .frame(width: 48, height: 48)
                Text("Stand at plot center")
                    .font(.caption.bold())
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(.black.opacity(0.5))
                    .cornerRadius(4)
            }
            .accessibilityIdentifier("arBoundary.centerReticle")
        } else {
            ringSummary
        }
    }

    private var ringSummary: some View {
        VStack(spacing: 4) {
            Circle()
                .strokeBorder(Color.green, lineWidth: 2)
                .frame(width: 120, height: 120)
            Text(String(format: "R = %.2f m", viewModel.radiusM))
                .font(.caption.bold())
                .foregroundStyle(.green)
        }
        .accessibilityIdentifier("arBoundary.ringSummary")
    }

    // MARK: - Bottom panel

    @ViewBuilder
    private var bottomPanel: some View {
        VStack(spacing: 12) {
            if viewModel.isDrifted {
                driftBanner
            }
            statusBanner
            actionRow
        }
        .padding()
        .background(.ultraThinMaterial)
        .cornerRadius(12)
        .padding(.horizontal)
        .padding(.bottom, 24)
    }

    private var driftBanner: some View {
        Text(String(
            format: "You are %.1f m from plot center (>%.0f m limit). "
                  + "Re-seat the center.",
            viewModel.userDistanceM,
            viewModel.driftRadiusM))
            .font(.callout).bold()
            .foregroundStyle(.white)
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.orange.opacity(0.85))
            .cornerRadius(8)
            .accessibilityIdentifier("arBoundary.driftBanner")
    }

    private var statusBanner: some View {
        Text(statusText)
            .font(.callout)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityIdentifier("arBoundary.statusBanner")
    }

    private var statusText: String {
        if viewModel.centerWorld == nil {
            return "Stand at the plot center and tap Set Center."
        }
        return String(
            format: "Plot R = %.2f m · distance to center = %.1f m",
            viewModel.radiusM,
            viewModel.userDistanceM)
    }

    @ViewBuilder
    private var actionRow: some View {
        if viewModel.centerWorld == nil {
            HStack {
                Button("Set Center") { setCenterAction() }
                    .buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("arBoundary.setCenterButton")
            }
        } else {
            HStack(spacing: 12) {
                Button("Reset") { viewModel.clearCenter() }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("arBoundary.resetButton")
                Spacer()
            }
        }
    }

    private func setCenterAction() {
        #if canImport(ARKit) && os(iOS)
        if !viewModel.setCenterAtCurrentCamera() {
            viewModel.setCenter(SIMD3<Float>(0, 0, 0))
        }
        #else
        viewModel.setCenter(SIMD3<Float>(0, 0, 0))
        #endif
    }
}
