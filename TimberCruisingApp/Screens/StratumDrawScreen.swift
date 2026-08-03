// Phase 7.4 — draw a stratum on the map.
//
// The Phase 1 flow required a prepared GeoJSON/KML file; this screen
// gives the cruiser a self-sufficient alternative: open the map, tap
// the corners of the harvest block, type a name, save.
//
// Uses iOS 17+ `Map` + `MapReader` so tapping the screen converts back
// to a WGS84 coordinate via `MapProxy.convert(_:from:)`. On macOS (test
// runner) the map is a neutral placeholder — the VM layer is fully
// platform-agnostic so the save path is still exercisable from
// previews and unit tests.

import SwiftUI
import Common
import Models
import Basemap
#if canImport(MapKit)
import MapKit
#endif

public struct StratumDrawScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: StratumDrawViewModel
    @Environment(\.dismiss) private var dismiss

    /// The base layer the cruiser picked in Map settings › Map type.
    /// A boundary drawn on imagery when the cruiser asked for streets
    /// (or the reverse) makes them second-guess where they are tapping,
    /// so this screen follows the same `tc.mapType` the map home does.
    ///
    /// Read straight from the key rather than through the injected
    /// `AppSettings`: this screen is pushed from the cruise-setup sheet,
    /// which does not carry that object in its environment, and an
    /// `@EnvironmentObject` that is not there is a crash, not a
    /// fallback.
    @AppStorage(AppSettings.Keys.mapType)
    private var mapTypeRaw: String = BasemapType.default.rawValue

    /// Read the same way and for the same reason as `mapTypeRaw` above: this
    /// screen has no `AppSettings` in its environment. The area readout is the
    /// only thing a cruiser has to tell them whether the block they just drew
    /// is the size they meant, and hardcoded to acres it left a metric cruiser
    /// converting in their head to size a stand.
    @AppStorage(AppSettings.Keys.unitSystem)
    private var unitSystemRaw: String = UnitSystem.imperial.rawValue

    private var areaUnit: AreaUnit {
        AppSettings.unitSystem(fromRaw: unitSystemRaw).areaUnit
    }

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue:
            StratumDrawViewModel(project: project))
    }

    public var body: some View {
        VStack(spacing: 0) {
            helpBanner
            mapArea
            controlPanel
        }
        .navigationTitle("Draw stratum")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { viewModel.configure(with: environment) }
        .alert("Error",
               isPresented: Binding(
                get: { viewModel.errorMessage != nil },
                set: { if !$0 { viewModel.errorMessage = nil } })
        ) {
            Button("OK", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .onChange(of: viewModel.didSave) { _, saved in
            if saved { dismiss() }
        }
    }

    // MARK: - Help banner

    @ViewBuilder private var helpBanner: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("How to use", systemImage: "hand.tap.fill")
                .font(.subheadline.bold())
            Text("Tap the block's corners in order. Area appears after 3 points. Undo removes the last.")
                .font(.caption)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.tint.opacity(0.10))
    }

    // MARK: - Map area

    #if canImport(MapKit) && os(iOS)
    @ViewBuilder private var mapArea: some View {
        MapReader { proxy in
            Map {
                // Render tapped vertices as annotations.
                ForEach(Array(viewModel.vertices.enumerated()), id: \.offset) { idx, v in
                    Annotation("\(idx + 1)", coordinate: v) {
                        ZStack {
                            Circle().fill(.red).frame(width: 22, height: 22)
                            Text("\(idx + 1)")
                                .font(.caption.bold())
                                .foregroundStyle(.white)
                        }
                    }
                }
                // Closed polygon preview (fill + stroke) if ≥3 points.
                if viewModel.vertices.count >= 3 {
                    MapPolygon(coordinates: viewModel.vertices)
                        .foregroundStyle(.green.opacity(0.20))
                        .stroke(.green, lineWidth: 2)
                }
            }
            .mapStyle(selectedMapStyle)
            .mapControls {
                MapCompass()
                MapScaleView()
            }
            .onTapGesture { screenPoint in
                if let coord = proxy.convert(screenPoint, from: .local) {
                    viewModel.addVertex(coord)
                }
            }
        }
        .frame(maxHeight: .infinity)
    }

    /// Satellite → imagery, Normal → the standard street map, matching
    /// what Map settings › Map type selected.
    private var selectedMapStyle: MapStyle {
        BasemapType.fromRaw(mapTypeRaw) == .satellite ? .imagery : .standard
    }
    #else
    @ViewBuilder private var mapArea: some View {
        VStack(spacing: 12) {
            Image(systemName: "map")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text("Map is iOS-only")
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(ForestixPalette.surfaceRaised)
    }
    #endif

    // MARK: - Control panel

    @ViewBuilder private var controlPanel: some View {
        VStack(spacing: 12) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(viewModel.vertexCountLabel)
                        .font(.subheadline.bold())
                    if viewModel.areaAcres > 0 {
                        Text(String(format: "Area: %.3f %@",
                                    areaUnit.fromAcres(viewModel.areaAcres),
                                    areaUnit.abbreviation))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                Spacer()
                Button {
                    viewModel.removeLast()
                } label: {
                    Label("Undo", systemImage: "arrow.uturn.backward")
                }
                .disabled(viewModel.vertices.isEmpty)
                Button(role: .destructive) {
                    viewModel.clear()
                } label: {
                    Label("Clear", systemImage: "trash")
                }
                .disabled(viewModel.vertices.isEmpty)
            }

            TextField("Stratum name (e.g. Block 1, North block)",
                      text: $viewModel.name)
                .textFieldStyle(.roundedBorder)
                #if os(iOS)
                .textInputAutocapitalization(.words)
                #endif
                .accessibilityIdentifier("stratumDraw.name")

            Button {
                viewModel.save()
            } label: {
                HStack {
                    if viewModel.isSaving { ProgressView() }
                    Text(viewModel.isSaving ? "Saving…" : "Save stratum")
                        .bold()
                }
                .frame(maxWidth: .infinity, minHeight: 56)
            }
            .buttonStyle(.forestixProminent)
            .disabled(!viewModel.canSave)
            .accessibilityIdentifier("stratumDraw.save")
        }
        .padding()
        .background(Material.regular)
    }
}
