// Spec §3.1 REQ-PRJ-004 "Planned plots appear on project map." Uses the
// SwiftUI iOS 17 / macOS 14 Map API with MapPolygon + Marker content.

import SwiftUI
import Models
#if canImport(MapKit)
import MapKit
import Geo
#endif

public struct PlotMapScreen: View {

    @EnvironmentObject private var environment: AppEnvironment
    @StateObject private var viewModel: PlotMapViewModel

    public init(project: Project) {
        _viewModel = StateObject(wrappedValue: PlotMapViewModel(project: project))
    }

    public var body: some View {
        content
            .navigationTitle("Plot Map")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .task {
                viewModel.configure(with: environment)
                viewModel.refresh()
            }
            .alert("Something went wrong",
                   isPresented: Binding(
                        get: { viewModel.errorMessage != nil },
                        set: { if !$0 { viewModel.errorMessage = nil } })
            ) {
                Button("OK", role: .cancel) { viewModel.errorMessage = nil }
            } message: {
                Text(viewModel.errorMessage ?? "")
            }
    }

    #if canImport(MapKit)
    @ViewBuilder
    private var content: some View {
        if let centroid = viewModel.centroid {
            Map(initialPosition: .region(.init(
                center: CLLocationCoordinate2D(
                    latitude: centroid.latitude,
                    longitude: centroid.longitude),
                span: .init(latitudeDelta: 0.03, longitudeDelta: 0.03)
            ))) {
                ForEach(viewModel.strataShapes) { shape in
                    if let outer = shape.rings.first {
                        MapPolygon(coordinates: outer.map {
                            CLLocationCoordinate2D(latitude: $0.latitude,
                                                   longitude: $0.longitude)
                        })
                        .foregroundStyle(.green.opacity(0.18))
                        .stroke(.green, lineWidth: 2)
                    }
                }
                ForEach(viewModel.plannedPlots) { plot in
                    Marker("#\(plot.plotNumber)", coordinate: CLLocationCoordinate2D(
                        latitude: plot.plannedLat,
                        longitude: plot.plannedLon
                    ))
                    .tint(plot.visited ? .gray : .blue)
                }
            }
        } else {
            emptyState
        }
    }
    #else
    @ViewBuilder
    private var content: some View { emptyState }
    #endif

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "map").font(.system(size: 40)).foregroundStyle(.secondary)
            Text("No map data yet").font(.headline)
            Text("Import strata and generate planned plots to see them on the map.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}
