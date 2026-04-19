// Phase 4 snapshot coverage for §5.1 NavigationScreen. States:
// searching (no GPS yet), live distance + tier A/C, arrived, denied.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import Foundation
@testable import UI
import Models
import Positioning

@MainActor
final class NavigationScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    private func target() -> PlannedPlot {
        PlannedPlot(
            id: UUID(uuidString: "11111111-2222-3333-4444-555555555555")!,
            projectId: UUID(),
            stratumId: UUID(),
            plotNumber: 1,
            plannedLat: 45.12345,
            plannedLon: -122.67890,
            visited: false)
    }

    private func host(_ vm: NavigationViewModel) -> UIHostingController<some View> {
        let view = NavigationStack { NavigationScreen(viewModel: vm) }
        return UIHostingController(rootView: view)
    }

    func testSearching() {
        let vm = NavigationViewModel.preview(
            target: target(),
            distanceM: nil, bearingDeg: nil,
            tier: .D, authStatus: .notDetermined)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testLiveTierAWithArrow() {
        let vm = NavigationViewModel.preview(
            target: target(),
            distanceM: 48.6, bearingDeg: 42,
            tier: .A)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testTierCWeakAccuracy() {
        let vm = NavigationViewModel.preview(
            target: target(),
            distanceM: 132.4, bearingDeg: 215,
            tier: .C)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testArrived() {
        let vm = NavigationViewModel.preview(
            target: target(),
            distanceM: 3.2, bearingDeg: 0,
            tier: .B, hasArrived: true)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }

    func testLocationDenied() {
        let vm = NavigationViewModel.preview(
            target: target(),
            distanceM: nil, bearingDeg: nil,
            tier: .D, authStatus: .denied)
        assertSnapshot(of: host(vm), as: .image(on: .iPhone13))
    }
}
#endif
