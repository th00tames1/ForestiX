// swift-tools-version: 6.0
// ForestiX modules and tests. UI and ARKit integration require iOS.
// For non-UI macOS tests use tools/validation/run_host_tests.py with a full
// Xcode DEVELOPER_DIR; it omits iOS-only UI and binary package dependencies.

import PackageDescription
import Foundation

// Local research files may coexist with the app checkout. Only these model
// resources are eligible for distribution; unknown files are excluded, not
// deleted. Keeping the model optional preserves model-free clone builds.
let sensorDirectory = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent().appendingPathComponent("TimberCruisingApp/Sensors")
let approvedSensorModels = ["README.md", "yolo26_alignment.onnx"]
let localSensorModels = (try? FileManager.default.contentsOfDirectory(
    atPath: sensorDirectory.appendingPathComponent("Models").path)) ?? []
let excludedSensorPaths = ["MobileBoundaryAligner.swift"].filter {
    FileManager.default.fileExists(atPath: sensorDirectory.appendingPathComponent($0).path)
} + localSensorModels.filter { !approvedSensorModels.contains($0) }.map { "Models/\($0)" }
let sensorModelResources: [Resource] = approvedSensorModels.filter {
    FileManager.default.fileExists(atPath: sensorDirectory.appendingPathComponent("Models/\($0)").path)
}.map { .process("Models/\($0)") }

let package = Package(
    name: "TimberCruisingApp",
    platforms: [
        // Must match (or be ≤) the hosting Xcode project's deployment
        // target. The Forestix.xcodeproj app target is iOS 18.2, so we
        // bump the package to .v18 — otherwise SPM compiles against
        // iOS 17 regardless and rejects iOS-18-only APIs like
        // `MeshResource.generateCylinder(height:radius:)`.
        .iOS(.v18),
        .macOS(.v14)   // enables `swift test` on developer macOS hosts
    ],
    products: [
        .library(name: "Common", targets: ["Common"]),
        .library(name: "Models", targets: ["Models"]),
        .library(name: "Persistence", targets: ["Persistence"]),
        .library(name: "InventoryEngine", targets: ["InventoryEngine"]),
        .library(name: "Geo", targets: ["Geo"]),
        .library(name: "Basemap", targets: ["Basemap"]),
        .library(name: "Export", targets: ["Export"]),
        .library(name: "Sensors", targets: ["Sensors"]),
        .library(name: "AR", targets: ["AR"]),
        .library(name: "Positioning", targets: ["Positioning"]),
        .library(name: "UI", targets: ["UI"])
    ],
    dependencies: [
        .package(
            url: "https://github.com/pointfreeco/swift-snapshot-testing",
            from: "1.17.0"
        ),
        // ON-DEVICE SEGMENTATION. The Auto diameter path can read the stem's
        // edges out of a YOLO-seg mask instead of walking the depth map; see
        // Sensors/TreeSegmenter.swift. iOS ONLY — the runtime ships as an
        // iOS/macCatalyst xcframework, and `Sensors` still has to compile on
        // a macOS host for the test suites, so the dependency below is
        // platform-conditioned and every use of it is behind
        // `#if canImport(OnnxRuntimeBindings)`.
        .package(
            url: "https://github.com/microsoft/onnxruntime-swift-package-manager",
            from: "1.20.0"
        )
    ],
    targets: [
        // MARK: - Phase 0

        .target(
            name: "Common",
            path: "TimberCruisingApp/Common"
        ),
        .target(
            name: "Models",
            dependencies: ["Common"],
            path: "TimberCruisingApp/Models",
            resources: [
                .process("Resources")
            ]
        ),
        .target(
            name: "Persistence",
            dependencies: ["Common", "Models", "InventoryEngine"],
            path: "TimberCruisingApp/Persistence",
            resources: [
                .process("TimberCruising.xcdatamodeld")
            ]
        ),
        .target(
            name: "InventoryEngine",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/InventoryEngine"
        ),

        // MARK: - Phase 1

        .target(
            name: "Geo",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/Geo"
        ),
        .target(
            name: "Basemap",
            dependencies: ["Common", "Geo"],
            path: "TimberCruisingApp/Basemap"
        ),
        .target(
            name: "Export",
            dependencies: ["Common", "Models", "Geo", "InventoryEngine"],
            path: "TimberCruisingApp/Export"
        ),
        // MARK: - Phase 2

        .target(
            name: "Sensors",
            // See the package dependency note: iOS-only, and guarded at every
            // import so the macOS host build of this target still succeeds.
            dependencies: ["Common", "Models",
                .product(name: "onnxruntime",
                         package: "onnxruntime-swift-package-manager",
                         condition: .when(platforms: [.iOS]))
            ],
            path: "TimberCruisingApp/Sensors",
            exclude: excludedSensorPaths,
            // Explicit production sources prevent newly added local experiment
            // files from silently becoming app code, even with a cached manifest.
            sources: [
                "AIBoundaryProvenance.swift", "ARKitSessionManager.swift",
                "BoundaryAlignment.swift", "CircleFit/KasaFit.swift",
                "CircleFit/RANSACCircle.swift", "CircleFit/TaubinFit.swift",
                "DBHCameraSnapshot.swift", "DBHEstimator.swift",
                "DBHMeasurementDepth.swift", "DistanceSmoother.swift",
                "HeightEstimator.swift", "IMUHelpers.swift", "LiDARCalibration.swift",
                "LiveAutoCadence.swift", "LiveStemTracking.swift", "PLYExporter.swift",
                "PointCloud/BackProjection.swift", "PointCloud/OutlierRemoval.swift",
                "RawAIInference.swift", "RawCaptureEvidence.swift",
                "RawCapturePresentation.swift", "RawCaptureReplay.swift",
                "RawCaptureStore.swift", "RawCaptureWriteGuard.swift",
                "RawDepthGridIO.swift", "RawIMUCapture.swift", "RawInertialRecording.swift",
                "RawOperatorEditJournal.swift", "SamplingStats.swift",
                "TreePlacementHelper.swift", "TreeSegmenter.swift", "VolumeConversion.swift",
                "YoloAlignmentMask.swift", "YoloBoundaryAligner.swift",
                "YoloImagePreparation.swift", "YoloInferenceWorkspace.swift", "YoloModelIdentity.swift"
            ],
            resources: sensorModelResources
        ),

        // MARK: - Phase 3

        .target(
            name: "AR",
            dependencies: ["Common", "Models", "Sensors"],
            path: "TimberCruisingApp/AR"
        ),

        // MARK: - Phase 4

        .target(
            name: "Positioning",
            dependencies: ["Common", "Models"],
            path: "TimberCruisingApp/Positioning"
        ),

        .target(
            name: "UI",
            dependencies: [
                "Common", "Models", "Persistence",
                "InventoryEngine", "Geo", "Basemap", "Export",
                "Sensors", "AR", "Positioning"
            ],
            path: "TimberCruisingApp",
            exclude: [
                "Common", "Models", "Persistence", "InventoryEngine",
                "Geo", "Basemap", "Export", "Sensors", "AR",
                "Positioning"
            ],
            sources: ["App", "Screens", "ViewModels"]
        ),

        // MARK: - Tests

        .testTarget(
            name: "CommonTests",
            dependencies: ["Common"],
            path: "Tests/CommonTests"
        ),
        .testTarget(
            name: "InventoryEngineTests",
            dependencies: ["InventoryEngine", "Models", "Common"],
            path: "Tests/InventoryEngineTests"
        ),
        .testTarget(
            name: "GeoTests",
            dependencies: ["Geo", "Models", "Common"],
            path: "Tests/GeoTests"
        ),
        .testTarget(
            name: "BasemapTests",
            dependencies: ["Basemap", "Geo", "Common"],
            path: "Tests/BasemapTests"
        ),
        .testTarget(
            name: "ExportTests",
            dependencies: ["Export", "Models", "Geo", "Common", "InventoryEngine"],
            path: "Tests/ExportTests",
            resources: [.copy("Fixtures")]
        ),
        .testTarget(
            name: "SensorsTests",
            dependencies: ["Sensors", "Models", "Common"],
            path: "Tests/SensorsTests"
        ),
        .testTarget(
            name: "ARTests",
            dependencies: ["AR", "Common"],
            path: "Tests/ARTests"
        ),
        .testTarget(
            name: "PositioningTests",
            dependencies: ["Positioning", "Models", "Common"],
            path: "Tests/PositioningTests"
        ),
        .testTarget(
            name: "PersistenceIntegrationTests",
            dependencies: [
                "Persistence", "Models", "Common", "InventoryEngine"
            ],
            path: "Tests/PersistenceIntegrationTests"
        ),
        .testTarget(
            name: "UIFlowTests",
            dependencies: [
                "UI", "Persistence", "Models", "Common", "InventoryEngine"
            ],
            path: "Tests/UIFlowTests"
        ),
        .testTarget(name: "CaptureRuntimeTests",dependencies: ["UI","Persistence","Sensors","Models","Common"],path: "Tests/CaptureRuntimeTests"),
        .testTarget(
            name: "UISnapshotTests",
            dependencies: [
                "UI", "Persistence",
                .product(name: "SnapshotTesting", package: "swift-snapshot-testing")
            ],
            path: "Tests/UISnapshotTests"
        )
    ],
    // We're on swift-tools-version 6.0 so we can use .v18 in platforms,
    // but the rest of the codebase was written against Swift 5 semantics
    // (especially its concurrency model). Pinning the language mode to
    // .v5 keeps the build green without a full Swift 6 migration, which
    // is its own multi-day project.
    swiftLanguageModes: [.v5]
)
