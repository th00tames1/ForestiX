// Spec §6.1 (tree-related enums) + §6.2 (Tree). REQ-TAL-001..006,
// REQ-DBH-001..009, REQ-HGT-001..007.

import Foundation
import Common

// MARK: - §6.1 Enumerations (tree/DBH/height)

public enum TreeStatus: String, Codable, Sendable {
    case live
    case deadStanding
    case deadDown
    case cull
}

public enum DBHMethod: String, Codable, Sendable {
    case lidarPartialArcSingleView
    case lidarPartialArcDualView
    case lidarIrregular
    /// Phase 19 — pixel-width chord / silhouette method. Default LiDAR
    /// path. Records that the diameter came from the projected trunk
    /// width × depth / fx identity rather than a circle fit.
    case lidarChordSilhouette
    /// LEGACY, decode-only. The AR-caliper (two-tap trunk edges) and
    /// AR-motion (VIO circle fit) capture arms were removed — they recorded
    /// no raw captures, so nothing could be re-derived from them. Trees
    /// already tallied with either arm still carry these raw strings, so the
    /// cases stay so the Core Data mapper can decode them; nothing produces
    /// them any more. Raw strings MUST keep matching Android so
    /// cross-platform exports join.
    case arCaliper
    case arVioCircleFit
    case manualCaliper
    case manualVisual
}

public enum HeightMethod: String, Codable, Sendable {
    case vioWalkoffTangent
    case tapeTangent        // manual tape distance + tangent
    case manualEntry
    case imputedHD
}

// MARK: - §6.2 Tree

public struct Tree: Identifiable, Codable, Sendable {
    public let id: UUID
    public let plotId: UUID
    public var treeNumber: Int
    /// The cruiser's own name for this tree ("Plot3-T07"). nil for a tree
    /// that was never named, and every display falls back to
    /// "Tree #treeNumber" through `displayTitle`.
    public var treeName: String?
    public var speciesCode: String
    public var status: TreeStatus

    // DBH
    public var dbhCm: Float
    public var dbhMethod: DBHMethod
    public var dbhSigmaMm: Float?           // uncertainty
    public var dbhRmseMm: Float?
    public var dbhCoverageDeg: Float?
    public var dbhNInliers: Int?
    public var dbhConfidence: ConfidenceTier
    /// HOW the diameter's edges were found: "auto" (silhouette edge-finder),
    /// "manual" (the ADJUST bracket the cruiser placed), or "typed" (hand
    /// entered, no sensor involved).
    ///
    /// The cruise tree row used to record no provenance at all — `dbhMethod`
    /// reads the same for a bracket and an auto fit — so a corpus mixing the
    /// two could not be split at analysis time. That matters now that the
    /// bracket is the default path: the algorithm comparison this data is
    /// collected for needs to know which estimator produced each diameter.
    /// nil for rows written before the field existed.
    public var dbhCaptureMode: String?
    public var dbhIsIrregular: Bool

    // Height
    public var heightM: Float?
    public var heightMethod: HeightMethod?
    public var heightSource: String?        // "measured" or "imputed"
    public var heightSigmaM: Float?
    public var heightDHM: Float?
    public var heightAlphaTopDeg: Float?
    public var heightAlphaBaseDeg: Float?
    public var heightConfidence: ConfidenceTier?

    // Geometry within plot
    public var bearingFromCenterDeg: Float?
    public var distanceFromCenterM: Float?
    public var boundaryCall: String?        // "in" / "borderline" / "out" for BAF plots

    // Attributes
    public var crownClass: String?
    public var damageCodes: [String]
    public var isMultistem: Bool
    public var parentTreeId: UUID?

    public var notes: String
    public var photoPath: String?
    public var rawScanPath: String?         // optional .ply

    public var createdAt: Date
    public var updatedAt: Date
    public var deletedAt: Date?             // soft-delete

    // GPS fix captured at Accept (cruise-mode map pins). Optional —
    // trees recorded without a fix simply don't appear on the map.
    public var latitude: Double?
    public var longitude: Double?

    public init(
        id: UUID,
        plotId: UUID,
        treeNumber: Int,
        treeName: String? = nil,
        speciesCode: String,
        status: TreeStatus,
        dbhCm: Float,
        dbhMethod: DBHMethod,
        dbhSigmaMm: Float?,
        dbhRmseMm: Float?,
        dbhCoverageDeg: Float?,
        dbhNInliers: Int?,
        dbhConfidence: ConfidenceTier,
        dbhIsIrregular: Bool,
        dbhCaptureMode: String? = nil,
        heightM: Float?,
        heightMethod: HeightMethod?,
        heightSource: String?,
        heightSigmaM: Float?,
        heightDHM: Float?,
        heightAlphaTopDeg: Float?,
        heightAlphaBaseDeg: Float?,
        heightConfidence: ConfidenceTier?,
        bearingFromCenterDeg: Float?,
        distanceFromCenterM: Float?,
        boundaryCall: String?,
        crownClass: String?,
        damageCodes: [String],
        isMultistem: Bool,
        parentTreeId: UUID?,
        notes: String,
        photoPath: String?,
        rawScanPath: String?,
        createdAt: Date,
        updatedAt: Date,
        deletedAt: Date?,
        latitude: Double? = nil,
        longitude: Double? = nil
    ) {
        self.id = id
        self.plotId = plotId
        self.treeNumber = treeNumber
        self.treeName = treeName
        self.speciesCode = speciesCode
        self.status = status
        self.dbhCm = dbhCm
        self.dbhMethod = dbhMethod
        self.dbhSigmaMm = dbhSigmaMm
        self.dbhRmseMm = dbhRmseMm
        self.dbhCoverageDeg = dbhCoverageDeg
        self.dbhNInliers = dbhNInliers
        self.dbhConfidence = dbhConfidence
        self.dbhIsIrregular = dbhIsIrregular
        self.dbhCaptureMode = dbhCaptureMode
        self.heightM = heightM
        self.heightMethod = heightMethod
        self.heightSource = heightSource
        self.heightSigmaM = heightSigmaM
        self.heightDHM = heightDHM
        self.heightAlphaTopDeg = heightAlphaTopDeg
        self.heightAlphaBaseDeg = heightAlphaBaseDeg
        self.heightConfidence = heightConfidence
        self.bearingFromCenterDeg = bearingFromCenterDeg
        self.distanceFromCenterM = distanceFromCenterM
        self.boundaryCall = boundaryCall
        self.crownClass = crownClass
        self.damageCodes = damageCodes
        self.isMultistem = isMultistem
        self.parentTreeId = parentTreeId
        self.notes = notes
        self.photoPath = photoPath
        self.rawScanPath = rawScanPath
        self.createdAt = createdAt
        self.updatedAt = updatedAt
        self.deletedAt = deletedAt
        self.latitude = latitude
        self.longitude = longitude
    }
}

// MARK: - What the cruise surfaces call a tree

/// The one rule for turning a tree's identity into the words on screen.
///
/// It lives apart from `Tree` because the tally loop has to label the tree it
/// is ABOUT to write — a target number and a pending name, with no row behind
/// them yet. Both callers must produce the same string, so both come through
/// here rather than one of them re-typing the format.
///
/// Ported 1:1 from / to Android `data/cruise/TreeLabel.kt`: a cruise split
/// across an iPhone and an Android phone must not print one stem two ways.
public enum TreeLabel {

    /// The cruiser's own name when they gave the tree one, else
    /// "Tree #<number>" — the same shape as the field log's
    /// `FieldLogRowModel.title`, so the two worlds call a tree one thing.
    ///
    /// The name is used VERBATIM. It was trimmed on the way in (the chooser
    /// and the tally both trim before storing) precisely so nothing downstream
    /// has to guess whether " Plot3-T07" and "Plot3-T07" are the same stem.
    public static func title(name: String?, number: Int) -> String {
        name ?? "Tree #\(number)"
    }

    /// How many glyphs a map pin can actually hold.
    ///
    /// The pin is a 30 pt teardrop with a 10.5 pt monospaced label drawn
    /// INSIDE it (see `BasemapMapView.teardropHead` and the Android
    /// `MapView` PIN branch). Four monospaced characters is what fits at
    /// full size; past that iOS shrinks the type towards illegible and
    /// Android simply overflows the drop.
    public static let pinLabelMaxChars = 4

    /// What a MAP PIN calls this tree — the short form of `title`.
    ///
    /// The map used to print "T104" even for a tree the cruiser had named,
    /// which is the whole complaint. It cannot simply print the name: at
    /// four glyphs "Starker32" becomes "Star", and every tree in a stand
    /// named by one convention shares that prefix, so the pin would stop
    /// distinguishing the very trees it exists to distinguish.
    ///
    /// So the rule is TRAILING-BIASED, because that is where a cruiser's
    /// naming scheme puts the part that varies:
    ///   • no name          → "T<number>", exactly as before;
    ///   • name that fits   → the name, whole;
    ///   • longer name with a trailing digit run → those digits
    ///     ("Starker32" → "32", "Plot3-T07" → "07");
    ///   • otherwise        → the last few characters ("Big Doug" → "Doug").
    /// Nothing is ellipsised: at four glyphs a "…" spends a quarter of the
    /// label saying "there is more", which the peek already says by
    /// printing the full `title` the moment the pin is tapped.
    ///
    /// A named pin therefore has no leading "T" and an unnamed one does,
    /// which is itself the signal that this stem is called something.
    public static func pinTitle(name: String?, number: Int) -> String {
        guard let name, !name.isEmpty else { return "T\(number)" }
        if name.count <= pinLabelMaxChars { return name }
        let tailDigits = String(name.reversed()
            .prefix { $0.isASCII && $0.isNumber }
            .reversed())
        if !tailDigits.isEmpty {
            return String(tailDigits.suffix(pinLabelMaxChars))
        }
        return String(name.suffix(pinLabelMaxChars))
    }
}

extension Tree {
    /// What every cruise surface calls this tree — see `TreeLabel.title`.
    public var displayTitle: String {
        TreeLabel.title(name: treeName, number: treeNumber)
    }
}
