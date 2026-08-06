// Snapshot coverage for the one tree form — the four sections a cruiser reads
// a stem back from, in the order `TreeDetailScreen` draws them:
//
//     Detail  →  Measurement  →  Computed  →  Recorded
//
// These images are the form's contract made visible. They fail loudly if a row
// moves between sections, if the Computed section stops carrying both a basal
// area AND a volume, if "Stem position" ever comes back, or if a field a kind
// of tree does not carry is dropped instead of reading "—".

#if canImport(UIKit)
import XCTest
import SwiftUI
import SnapshotTesting
import Models
import Persistence
@testable import UI

@MainActor
final class TreeFormSnapshotTests: XCTestCase {

    override func setUp() {
        super.setUp()
        isRecording = ProcessInfo.processInfo.environment["RECORD_SNAPSHOTS"] == "1"
    }

    /// An iPhone 13 that is as tall as the form is long. The four sections do
    /// not fit on a real screen at once, and the thing under test IS the whole
    /// run of them in order, so the snapshot takes the scroll view's full
    /// length rather than its first screenful.
    private var wholeForm: ViewImageConfig {
        var config = ViewImageConfig.iPhone13
        config.size = CGSize(width: 390, height: 1500)
        return config
    }

    private func host(_ viewModel: TreeDetailViewModel) -> UIHostingController<some View> {
        let environment = AppEnvironment.preview()
        // The preview store starts empty, and an unseeded store has no equation
        // for any species — which would make the Volume row read "—" for the
        // uninteresting reason. Seed the starter set the app itself ships with
        // so the row is exercised on its real path.
        _ = try? SeedDataLoader.bootstrapIfNeeded(
            speciesRepo: environment.speciesRepository,
            volRepo: environment.volumeEquationRepository)
        let view = NavigationStack {
            TreeDetailScreen(viewModel: viewModel)
        }
        .environmentObject(environment)
        .environmentObject(environment.settings)
        return UIHostingController(rootView: view)
    }

    /// A tree measured at a FIXED instant. `preview()` stamps the sample stem
    /// with "now", and the Detail section's Time row prints it — so without
    /// pinning the clock the image differs on every run and the comparison is
    /// worthless.
    private func stem(_ mutate: (inout Tree) -> Void = { _ in }) -> Tree {
        var tree = TreeDetailViewModel.preview().tree
        tree.createdAt = Date(timeIntervalSince1970: 1_753_000_000)
        mutate(&tree)
        return tree
    }

    /// The whole form on a tree that carries everything: a species, a diameter
    /// and a height, so the Computed section has both numbers to show.
    func testCruiseTreeFullForm() {
        assertSnapshot(of: host(TreeDetailViewModel.preview(tree: stem())),
                       as: .image(on: wholeForm))
    }

    /// The same form on a tree with no height. Volume cannot be computed from a
    /// diameter alone, so the row STAYS and reads "—": the rule that keeps this
    /// form and the field log's record sheet the same shape.
    func testTreeWithoutHeightKeepsTheVolumeRow() {
        let noHeight = stem {
            $0.heightM = nil
            $0.heightSigmaM = nil
            $0.heightConfidence = nil
        }
        assertSnapshot(of: host(TreeDetailViewModel.preview(tree: noHeight)),
                       as: .image(on: wholeForm))
    }
}
#endif
