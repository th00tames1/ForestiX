// Test factory helpers — minimal Plot/Tree builders so math tests stay
// focused on the quantity under test rather than on field boilerplate.
// All defaults are arbitrary-but-legal; override only what each test cares about.

import Foundation
import Models
import Common

func makePlot(
    id: UUID = UUID(),
    projectId: UUID = UUID(),
    plotAreaAcres: Float = 0.1,
    plotNumber: Int = 1
) -> Plot {
    Plot(
        id: id,
        projectId: projectId,
        plannedPlotId: nil,
        plotNumber: plotNumber,
        centerLat: 0,
        centerLon: 0,
        positionSource: .manual,
        positionTier: .D,
        gpsNSamples: 0,
        gpsMedianHAccuracyM: 0,
        gpsSampleStdXyM: 0,
        offsetWalkM: nil,
        slopeDeg: 0,
        aspectDeg: 0,
        plotAreaAcres: plotAreaAcres,
        startedAt: Date(timeIntervalSince1970: 0),
        closedAt: nil,
        closedBy: nil,
        notes: "",
        coverPhotoPath: nil,
        panoramaPath: nil
    )
}

func makeTree(
    id: UUID = UUID(),
    plotId: UUID = UUID(),
    treeNumber: Int = 1,
    speciesCode: String = "DF",
    status: TreeStatus = .live,
    dbhCm: Float,
    heightM: Float? = nil,
    deletedAt: Date? = nil
) -> Tree {
    Tree(
        id: id,
        plotId: plotId,
        treeNumber: treeNumber,
        speciesCode: speciesCode,
        status: status,
        dbhCm: dbhCm,
        dbhMethod: .manualCaliper,
        dbhSigmaMm: nil,
        dbhRmseMm: nil,
        dbhCoverageDeg: nil,
        dbhNInliers: nil,
        dbhConfidence: .green,
        dbhIsIrregular: false,
        heightM: heightM,
        heightMethod: heightM == nil ? nil : .manualEntry,
        heightSource: heightM == nil ? nil : "measured",
        heightSigmaM: nil,
        heightDHM: nil,
        heightAlphaTopDeg: nil,
        heightAlphaBaseDeg: nil,
        heightConfidence: heightM == nil ? nil : .green,
        bearingFromCenterDeg: nil,
        distanceFromCenterM: nil,
        boundaryCall: nil,
        crownClass: nil,
        damageCodes: [],
        isMultistem: false,
        parentTreeId: nil,
        notes: "",
        photoPath: nil,
        rawScanPath: nil,
        createdAt: Date(timeIntervalSince1970: 0),
        updatedAt: Date(timeIntervalSince1970: 0),
        deletedAt: deletedAt
    )
}
