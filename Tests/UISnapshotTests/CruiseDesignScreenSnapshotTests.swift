// Phase 1 snapshot coverage for the cruise-design form.

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import Models
@testable import UI

@MainActor
final class CruiseDesignScreenSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = false
    }

    func testCruiseDesignEmptyForm() {
        let environment = AppEnvironment.preview()
        let now = Date(timeIntervalSince1970: 0)
        let project = Project(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000001")!,
            name: "Snapshot",
            description: "",
            owner: "",
            createdAt: now,
            updatedAt: now,
            units: .imperial,
            breastHeightConvention: .imperial4_5ft,
            slopeCorrection: true,
            lidarBiasMm: 0,
            depthNoiseMm: 0,
            dbhCorrectionAlpha: 0,
            dbhCorrectionBeta: 1,
            vioDriftFraction: 0.02
        )
        let view = NavigationStack { CruiseDesignScreen(project: project) }
            .environmentObject(environment)
            .environmentObject(environment.settings)
        let host = UIHostingController(rootView: view)
        assertSnapshot(of: host, as: .image(on: .iPhone13))
    }
}
#endif
