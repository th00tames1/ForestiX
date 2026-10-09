// Spec §6.2 (Plot). REQ-CTR-001..005, REQ-AGG-001..003.

import Foundation

public struct Plot: Identifiable, Codable, Sendable {
    public let id: UUID
    public let projectId: UUID
    public var plannedPlotId: UUID?
    public var plotNumber: Int
    public var centerLat: Double
    public var centerLon: Double
    public var positionSource: PositionSource
    public var positionTier: PositionTier
    public var gpsNSamples: Int
    public var gpsMedianHAccuracyM: Float
    public var gpsSampleStdXyM: Float
    public var offsetWalkM: Float?          // non-nil for vioOffset
    public var slopeDeg: Float
    public var aspectDeg: Float
    /// Ground elevation at the plot centre, in METRES.
    ///
    /// Metres because every stored length in this app is metric and the
    /// display layer converts once (see `MeasurementFormatter`); a US cruise
    /// reads and types feet, and the sheet does that conversion. Storing the
    /// cruiser's units instead would make the number mean different things in
    /// different projects, which is unrecoverable after the fact.
    ///
    /// nil is NOT sea level — it is "nobody recorded an elevation here". The
    /// two are different answers and 0 m is a real coastal elevation, so the
    /// unset case gets its own value rather than a sentinel that a tidewater
    /// plot could legitimately wear.
    public var groundElevationM: Float?
    /// Canopy cover over the plot, 0–100 %. Unitless, so it is the same
    /// number in both unit systems. nil means not recorded; 0 % is a real
    /// reading on a clearcut.
    public var canopyCoverPct: Float?
    public var plotAreaAcres: Float         // denormalized from CruiseDesign for robustness
    public var startedAt: Date
    public var closedAt: Date?
    public var closedBy: String?
    public var notes: String
    public var coverPhotoPath: String?
    public var panoramaPath: String?        // for re-navigation

    public init(
        id: UUID,
        projectId: UUID,
        plannedPlotId: UUID?,
        plotNumber: Int,
        centerLat: Double,
        centerLon: Double,
        positionSource: PositionSource,
        positionTier: PositionTier,
        gpsNSamples: Int,
        gpsMedianHAccuracyM: Float,
        gpsSampleStdXyM: Float,
        offsetWalkM: Float?,
        slopeDeg: Float,
        aspectDeg: Float,
        groundElevationM: Float? = nil,
        canopyCoverPct: Float? = nil,
        plotAreaAcres: Float,
        startedAt: Date,
        closedAt: Date?,
        closedBy: String?,
        notes: String,
        coverPhotoPath: String?,
        panoramaPath: String?
    ) {
        self.id = id
        self.projectId = projectId
        self.plannedPlotId = plannedPlotId
        self.plotNumber = plotNumber
        self.centerLat = centerLat
        self.centerLon = centerLon
        self.positionSource = positionSource
        self.positionTier = positionTier
        self.gpsNSamples = gpsNSamples
        self.gpsMedianHAccuracyM = gpsMedianHAccuracyM
        self.gpsSampleStdXyM = gpsSampleStdXyM
        self.offsetWalkM = offsetWalkM
        self.slopeDeg = slopeDeg
        self.aspectDeg = aspectDeg
        self.groundElevationM = groundElevationM
        self.canopyCoverPct = canopyCoverPct
        self.plotAreaAcres = plotAreaAcres
        self.startedAt = startedAt
        self.closedAt = closedAt
        self.closedBy = closedBy
        self.notes = notes
        self.coverPhotoPath = coverPhotoPath
        self.panoramaPath = panoramaPath
    }
}

public extension Plot {

    /// TRUE when this plot has a real recorded CENTRE.
    ///
    /// (0, 0) is the sentinel for "no centre": it is what a Plot is born
    /// with before a centre is captured, and what the map's "Remove
    /// plot" writes back when a cruiser takes the plot off the map. The
    /// two cases are deliberately indistinguishable — a plot that never
    /// had a centre and a plot whose centre was cleared are the same
    /// thing, and every surface must treat them the same.
    ///
    /// This lives on the model rather than on any one screen because the
    /// question is asked from three different layers — the map, the
    /// summaries and every exporter — and a second definition of "has a
    /// centre" is how (0, 0) ends up shipping as a real coordinate in
    /// the Gulf of Guinea.
    ///
    /// Non-finite coordinates count as NO centre for the same reason: a
    /// NaN is not a position, and writing one into a GeoJSON or a
    /// shapefile produces a file no GIS can open.
    var hasCentre: Bool {
        centerLat.isFinite && centerLon.isFinite
            && (centerLat != 0 || centerLon != 0)
    }

    /// Why this plot's site description cannot be stored, in the words the
    /// cruiser reads — or nil when all four fields are inside their ranges.
    ///
    /// Lives on the model because BOTH the sheet that types these numbers and
    /// the repository that writes them ask the same question, and a screen
    /// that allows what storage refuses (or the reverse) is a save button that
    /// fails for no stated reason. The repository is the one that MUST ask:
    /// the sheet is only today's caller, and a canopy cover of 130 % written
    /// by tomorrow's importer is just as wrong.
    ///
    /// A NaN fails every one of these comparisons and is therefore refused
    /// along with the out-of-range values, which is the intent — a NaN aspect
    /// is not a direction.
    var siteDescriptionRejection: String? {
        guard (0...90).contains(slopeDeg) else {
            return "Slope must be between 0 and 90 degrees."
        }
        // Upper bound EXCLUSIVE: 360° and 0° are the same bearing, and two
        // spellings of north is how a north-facing plot sorts into two groups
        // in the same table. Callers wrap before they save.
        guard aspectDeg >= 0, aspectDeg < 360 else {
            return "Aspect must be a bearing from 0 up to (but not including) 360 degrees."
        }
        // Below the Dead Sea shore or above Everest is a typo, most often a
        // figure in feet typed into a field that stores metres. The sentence
        // names BOTH units because the cruiser reading it may be working in
        // either, and it is the same sentence on both platforms.
        if let elevation = groundElevationM, !(-500...9000).contains(elevation) {
            return "Elevation must be between -500 and 9000 m (-1640 to 29528 ft)."
        }
        if let cover = canopyCoverPct, !(0...100).contains(cover) {
            return "Canopy cover must be between 0 and 100 %."
        }
        return nil
    }
}
