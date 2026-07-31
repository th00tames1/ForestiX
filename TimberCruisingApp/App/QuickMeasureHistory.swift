// On-device log of one-off diameter / height measurements captured from
// the Quick Measure entry point. These are NOT Tree/Plot records —
// just the last-N readings a cruiser wants to glance back at or export
// without opening the full project workflow.
//
// Storage strategy (durability for the app's most-used surface):
//
// • Primary: a JSONL sidecar file at
//       `Application Support/Forestix/quick-measure.jsonl`
//   One line per entry, append-only. Survives UserDefaults resets,
//   which is the single biggest data-loss footgun in the old design.
//
// • Cache: the last N entries encoded into UserDefaults as a single
//   blob — fast to read on launch, no disk I/O for the first paint.
//   If the cache fails to decode (schema drift after an app update,
//   corruption), we fall back to replaying the JSONL.
//
// • Schema versioning: every file write is prefixed by a single-line
//   header `#v 1`. Future entry-model changes bump the version and
//   add an explicit migration rather than `try?`-swallowing decode
//   errors and silently returning `[]`.

import Foundation
import Models
import Common
import Sensors

// MARK: - Entry

/// Lightweight Quick Measure plot — owned entirely by `QuickMeasureHistory`,
/// distinct from the Core Data plot used by the Advanced cruise
/// workflow. Quick Measure cruisers can group readings into plots
/// (each with a name, optional unit, optional acreage) without
/// committing to the full project / stratum / cruise design pipeline.
/// A single "default" plot exists at all times so the simplest
/// "tap Diameter, scan, save" path keeps working with no setup.
public struct QuickMeasurePlot: Codable, Identifiable, Sendable, Equatable {
    public let id: UUID
    /// Human-friendly plot name. The default plot is always called
    /// "Quick measurements" and can't be deleted.
    public var name: String
    /// Optional management unit / stand name — multi-unit cruise
    /// support. Empty string treated as nil.
    public var unitName: String
    /// Plot acreage. nil = unknown / unset.
    public var acres: Double?
    /// Plot type — fixed-radius / variable / tally / measure.
    public var typeRaw: String
    /// BAF for variable-radius (ft²/ac) — ignored for other types.
    public var baf: Double?
    /// Plot-radius in feet for fixed-radius plots.
    public var radiusFt: Double?
    /// Optional parent plot id — when present, this plot is a nested
    /// concentric sub-plot of the parent (typically a smaller radius
    /// for submerchantable / biomass / regeneration tally). Models
    /// concentric fixed-radius plots at the same
    /// plot center. The parent's coordinates are inherited
    /// implicitly; only the radius (and any tally-class restriction)
    /// differs.
    public var parentPlotID: UUID?
    /// Free-form sub-plot category — e.g. "biomass", "sawtimber",
    /// "regen". Surfaced in reports so the cruiser can split the
    /// stand-and-stock by tally class.
    public var nestedKind: String?
    public let createdAt: Date
    /// True for the auto-created "Quick measurements" plot.
    public let isDefault: Bool

    public init(id: UUID = UUID(),
                name: String,
                unitName: String = "",
                acres: Double? = nil,
                typeRaw: String = "fixed",
                baf: Double? = nil,
                radiusFt: Double? = nil,
                parentPlotID: UUID? = nil,
                nestedKind: String? = nil,
                createdAt: Date = Date(),
                isDefault: Bool = false) {
        self.id = id
        self.name = name
        self.unitName = unitName
        self.acres = acres
        self.typeRaw = typeRaw
        self.baf = baf
        self.radiusFt = radiusFt
        self.parentPlotID = parentPlotID
        self.nestedKind = nestedKind
        self.createdAt = createdAt
        self.isDefault = isDefault
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id        = try c.decode(UUID.self,   forKey: .id)
        self.name      = try c.decode(String.self, forKey: .name)
        self.unitName  = (try? c.decode(String.self, forKey: .unitName)) ?? ""
        self.acres     = try c.decodeIfPresent(Double.self, forKey: .acres)
        self.typeRaw   = (try? c.decode(String.self, forKey: .typeRaw)) ?? "fixed"
        self.baf       = try c.decodeIfPresent(Double.self, forKey: .baf)
        self.radiusFt  = try c.decodeIfPresent(Double.self, forKey: .radiusFt)
        self.parentPlotID = try c.decodeIfPresent(UUID.self, forKey: .parentPlotID)
        self.nestedKind   = try c.decodeIfPresent(String.self, forKey: .nestedKind)
        self.createdAt = try c.decode(Date.self,   forKey: .createdAt)
        self.isDefault = (try? c.decode(Bool.self, forKey: .isDefault)) ?? false
    }

    /// True if this plot is a nested sub-plot (has a parent).
    public var isNested: Bool { parentPlotID != nil }
}

public struct QuickMeasureEntry: Codable, Identifiable, Sendable, Equatable {

    public enum Kind: String, Codable, Sendable {
        case dbh
        case height
        /// Tree crown — `value` is crown width (m), `secondaryValue` is
        /// crown height (m). Captured via 4-tap AR flow (L, R, bottom, top).
        case crown
        /// Single distance reading — `value` is distance (m). When the
        /// reading is a two-point measurement, `method` carries
        /// "two-point.lidar" / "two-point.ar"; the camera-to-target
        /// variant uses "live.lidar" / "live.ar".
        case distance
        /// Sampling plot record — `value` is plot radius (m).
        /// `secondaryValue` carries the plot area in m².
        case samplingPlot
    }

    /// Where on the stem the reading was taken — DBH (1.3 m), butt,
    /// upper stem at a specific height, or stump. Optional; older
    /// entries default to `dbh` for diameter readings.
    public enum StemPosition: String, Codable, Sendable, CaseIterable {
        case dbh
        case butt
        case upperStem
        case stump

        public var displayName: String {
            switch self {
            case .dbh:        return "DBH"
            case .butt:       return "Butt"
            case .upperStem:  return "Upper stem"
            case .stump:      return "Stump"
            }
        }
    }

    public let id: UUID
    public let kind: Kind
    public let value: Double
    /// Paired second metric for compound readings. Crown stores height
    /// here (m) alongside width in `value`; sampling plot stores area
    /// (m²). nil for legacy entries and single-value kinds.
    public let secondaryValue: Double?
    public let sigma: Double?
    public let confidenceRaw: String
    public let method: String
    /// WHEN THIS READING WAS MEASURED.
    ///
    /// Settable — `var`, as the cruise `Tree.createdAt` already is — because a
    /// value that would not go into the app at the tree is written in a
    /// notebook and typed in at the office, and the reading then carries the
    /// OFFICE time. It is not decoration: the validation analysis paired 97
    /// trees across two phones on it, `TruthBackfill` matches a manifest truth
    /// to a reading by nearest timestamp, and the export classifier reads
    /// live-vs-superseded partly off time order.
    ///
    /// Which is exactly why NOTHING assigns to it directly. Every re-statement
    /// goes through `settingCreatedAt`, which stamps `timeSource` in the same
    /// breath; a bare assignment would leave a desk-typed time indistinguishable
    /// from a sensor-stamped one, which is the one thing this must never be.
    public var createdAt: Date
    public let treeNumber: Int?
    /// Cruiser-typed name for the tree ("Plot3-T07"), chosen in the measure
    /// chooser before the scan. nil for every reading taken before naming
    /// existed and for any tree left unnamed — read it through
    /// `displayTreeName`, never raw, so those readings keep looking the way
    /// they always have.
    public let treeName: String?

    /// Plot the reading belongs to. Older entries (pre-Phase 2) and
    /// the auto-migrated default-plot entries share the same default
    /// plot id assigned by `QuickMeasureHistory`.
    public let plotID: UUID?
    /// FIA species code — short string the regional species lists
    /// surface (e.g. "DF", "PP", "RO"). nil = unspecified.
    public let speciesCode: String?
    /// Stem position the reading was taken at. nil = legacy entry.
    public let position: StemPosition?
    /// Damage codes — multiple short tags ("sweep", "fork",
    /// "broken-top", "rot"). Empty array = no damage noted.
    public let damageCodes: [String]
    /// Free-text cruiser note. nil = no note.
    public let note: String?
    /// Capture context (map home): GPS fix + AR-view snapshot taken at the
    /// moment the cruiser hit Accept. Optional — entries recorded before
    /// this feature (or without a fix) simply have none.
    public let latitude: Double?
    public let longitude: Double?
    /// Filename inside MeasurePhotoStore.directory (not a full path, so
    /// container moves between installs don't break it).
    public let photoPath: String?
    /// How the reading was captured: "auto" (automatic edge-finding),
    /// "manual" (DBH ADJUST edge-bracket mode) or "typed" (a number the
    /// cruiser entered by hand). nil for height / other kinds and for
    /// entries recorded before this field existed.
    public let captureMode: String?
    /// Hand-measured GROUND TRUTH for this reading — the tape diameter or
    /// the pole/clinometer height the accuracy study compares against.
    /// Same unit as `value` (cm for diameter, m otherwise).
    ///
    /// It lives ON the reading, not in the raw-capture manifest keyed on
    /// tree number: a truth typed against a tree number alone could not say
    /// WHICH of that tree's readings it was measured for, and it vanished
    /// with the raw-capture bundle. nil = no truth was ever entered — never
    /// zero, which would read as a measured 0 cm.
    public let truth: Double?

    /// How `truth` came to sit on this reading — the same shape of provenance
    /// stamp as `captureMode` and `positionSource`, and read through
    /// `truthRecordedSource` for the same reason.
    ///
    /// nil is not an unknown: until the recovery pass existed, the ONLY writer
    /// of `truth` was a number the cruiser typed against this reading (scan
    /// screen or field log), so an unlabelled truth IS a typed one.
    /// `TruthSource.capture` marks a truth that was typed for a raw CAPTURE
    /// and attached to this reading by inference — same tree, same kind, same
    /// plot, nearest timestamp. It is the cruiser's own tape number either
    /// way, but the analysis must be able to separate "typed here" from
    /// "matched here", because the second one carries a matching assumption
    /// the first does not.
    public let truthSource: String?

    /// The unit `truth` was TYPED in — a `TruthInput.Unit` raw ("cm" | "in" |
    /// "m" | "ft"). The stored number is always the metric base, so nothing
    /// else on the reading says whether the cruiser was working in inches or
    /// centimetres, exactly as on the raw-capture manifest (`truth_unit`) and
    /// in the research CSV.
    ///
    /// nil means NOT STATED — never "metric". It is the marker `TruthUnitRepair`
    /// keys on: a truth carrying no unit was typed before the unit toggle
    /// existed, and one carrying a unit was entered knowingly and is already
    /// right. Writing the unit is therefore what makes the repair idempotent —
    /// the repaired value no longer matches the rule that selected it.
    public let truthUnit: String?

    /// Where this reading's coordinate came from — a `PositionSource` raw
    /// string, the SAME vocabulary the cruise `Plot` records ("gpsSingle",
    /// "manual", …), so one word means one thing in both worlds.
    ///
    /// nil on every row written before this column existed. That is not an
    /// unknown: until the field log let a coordinate be typed, the
    /// Accept-time GPS snapshot was the ONLY thing that ever wrote
    /// latitude/longitude, so an unlabelled located row is a device fix.
    /// `positionRecordedSource` states that rule once and everything that
    /// displays or exports the source reads it through there — a typed
    /// coordinate that exported as if the GPS produced it would be the same
    /// class of error as a typed diameter carrying a sensor σ.
    public let positionSource: String?

    /// How this reading's `createdAt` came to be what it is — the fourth
    /// provenance stamp, the same shape as `captureMode`, `truthSource` and
    /// `positionSource`, and read through `timeRecordedSource` for the same
    /// reason.
    ///
    /// nil is not an unknown: until the record sheet could set a time, the ONLY
    /// writer of `createdAt` was the clock at the moment the reading was
    /// recorded, so an unlabelled time IS a device-stamped one.
    /// `TimeSource.typed` marks a time the cruiser set by hand from their
    /// notebook. Both are the cruiser's honest account of when the tree was
    /// measured, but the analysis must be able to separate them: a hand-set
    /// time is a claim about the past, and the joins that depend on time
    /// (phone-to-phone pairing, `TruthBackfill`, live-vs-superseded) inherit
    /// that claim's uncertainty. It is exported as its own column so that
    /// separation survives leaving the phone.
    public let timeSource: String?

    public init(
        id: UUID = UUID(),
        kind: Kind,
        value: Double,
        secondaryValue: Double? = nil,
        sigma: Double?,
        confidenceRaw: String,
        method: String,
        createdAt: Date = Date(),
        treeNumber: Int? = nil,
        treeName: String? = nil,
        plotID: UUID? = nil,
        speciesCode: String? = nil,
        position: StemPosition? = nil,
        damageCodes: [String] = [],
        note: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        photoPath: String? = nil,
        captureMode: String? = nil,
        truth: Double? = nil,
        truthSource: String? = nil,
        truthUnit: String? = nil,
        positionSource: String? = nil,
        timeSource: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.value = value
        self.secondaryValue = secondaryValue
        self.sigma = sigma
        self.confidenceRaw = confidenceRaw
        self.method = method
        self.createdAt = createdAt
        self.treeNumber = treeNumber
        self.treeName = treeName
        self.plotID = plotID
        self.speciesCode = speciesCode
        self.position = position
        self.damageCodes = damageCodes
        self.note = note
        self.latitude = latitude
        self.longitude = longitude
        self.photoPath = photoPath
        self.captureMode = captureMode
        self.truth = truth
        self.truthSource = truthSource
        self.truthUnit = truthUnit
        self.positionSource = positionSource
        self.timeSource = timeSource
    }

    // Custom decoding so entries written before any new field existed
    // still parse cleanly — the schema-version header lets us add
    // fields like this without forcing a destructive migration.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id            = try c.decode(UUID.self,   forKey: .id)
        self.kind          = try c.decode(Kind.self,   forKey: .kind)
        self.value         = try c.decode(Double.self, forKey: .value)
        self.secondaryValue = try c.decodeIfPresent(Double.self, forKey: .secondaryValue)
        self.sigma         = try c.decodeIfPresent(Double.self, forKey: .sigma)
        self.confidenceRaw = try c.decode(String.self, forKey: .confidenceRaw)
        self.method        = try c.decode(String.self, forKey: .method)
        self.createdAt     = try c.decode(Date.self,   forKey: .createdAt)
        self.treeNumber    = try c.decodeIfPresent(Int.self,   forKey: .treeNumber)
        self.treeName      = try c.decodeIfPresent(String.self, forKey: .treeName)
        self.plotID        = try c.decodeIfPresent(UUID.self,  forKey: .plotID)
        self.speciesCode   = try c.decodeIfPresent(String.self, forKey: .speciesCode)
        self.position      = try c.decodeIfPresent(StemPosition.self, forKey: .position)
        self.damageCodes   = (try? c.decode([String].self, forKey: .damageCodes)) ?? []
        self.note          = try c.decodeIfPresent(String.self, forKey: .note)
        self.latitude      = try c.decodeIfPresent(Double.self, forKey: .latitude)
        self.longitude     = try c.decodeIfPresent(Double.self, forKey: .longitude)
        self.photoPath     = try c.decodeIfPresent(String.self, forKey: .photoPath)
        self.captureMode   = try c.decodeIfPresent(String.self, forKey: .captureMode)
        self.truth         = try c.decodeIfPresent(Double.self, forKey: .truth)
        self.truthSource   = try c.decodeIfPresent(String.self, forKey: .truthSource)
        self.truthUnit     = try c.decodeIfPresent(String.self, forKey: .truthUnit)
        self.positionSource = try c.decodeIfPresent(String.self,
                                                    forKey: .positionSource)
        self.timeSource    = try c.decodeIfPresent(String.self, forKey: .timeSource)
    }

    /// The vocabulary of `truthSource`. One case, because there is exactly one
    /// way a truth arrives that is not "the cruiser typed it here"; a second
    /// case for "typed" would have to be back-stamped onto a corpus that never
    /// recorded it, which is a claim about data we do not have.
    public enum TruthSource: String, Sendable {
        /// Recovered from a raw-capture manifest by `TruthBackfill` and
        /// attached to this reading by (kind, tree, plot, nearest time).
        case capture
    }

    /// The source to SHOW and to EXPORT for this reading's truth: the stored
    /// one, or "typed" for a truth that predates the column (see
    /// `truthSource`). nil only when there is no truth.
    public var truthRecordedSource: String? {
        guard truth != nil else { return nil }
        return truthSource ?? "typed"
    }

    /// The source to SHOW and to EXPORT for this reading's coordinate: the
    /// stored one, or `gpsSingle` for a located row that predates the
    /// column (see `positionSource`). nil only when there is no coordinate.
    public var positionRecordedSource: String? {
        guard latitude != nil, longitude != nil else { return nil }
        return positionSource ?? PositionSource.gpsSingle.rawValue
    }

    /// The vocabulary of `timeSource`.
    ///
    /// Unlike `TruthSource`, this one names BOTH states, because both are
    /// claims we can actually make: `device` is what an absent stamp means and
    /// is provable (nothing but the clock could have written a time before this
    /// feature existed), and `typed` is written the moment a cruiser sets one.
    public enum TimeSource: String, Sendable {
        /// Set by hand from the record sheet — a notebook time typed at a desk.
        case typed
        /// The clock stamped it when the reading was recorded. Never STORED —
        /// it is what an absent `timeSource` means, and what
        /// `timeRecordedSource` returns for one.
        case device
    }

    /// The source to SHOW and to EXPORT for this reading's time.
    ///
    /// Never nil, unlike its three siblings: every reading has a time, so the
    /// column is never blank and "old row" can never be confused with "no
    /// answer". A row this app has not touched exports `device`.
    public var timeRecordedSource: String {
        timeSource ?? TimeSource.device.rawValue
    }

    /// True when this reading's time was SET BY HAND rather than stamped by
    /// the clock. The one test every surface uses before printing the marker,
    /// so a hand-set time cannot be flagged on one screen and silent on the
    /// next.
    public var hasHandSetTime: Bool {
        timeSource == TimeSource.typed.rawValue
    }

    /// True when NOTHING was captured for this reading — the number came off a
    /// tape or a notebook, not a sensor. Decided FROM THE ENTRY ALONE, because
    /// a reading carries no raw-capture id: the bundles are joined to readings
    /// after the fact by (kind, tree, nearest time), which is the very join a
    /// hand-set time disturbs, so it cannot be the thing that decides how to
    /// warn about one.
    ///
    /// Two markers, either of which settles it, and both are written by the
    /// scan screens themselves:
    ///   • `captureMode == "typed"` — stamped by the DBH screen's manual-entry
    ///     arm and by every `typed` / `typedValue` path, for both kinds.
    ///   • `method` is the kind's manual arm ("manualVisual" / "manualEntry"),
    ///     which is what the DBH screen branches on to write that stamp in the
    ///     first place, and which is present on hand-typed readings recorded
    ///     before `captureMode` existed.
    ///
    /// The compound kinds (crown, distance, sampling plot) have no typed arm —
    /// `typedMethodRaw(for:)` returns nil for them — so they fall through as
    /// captured, which they are: every one of them comes from AR taps.
    public var isTypedReading: Bool {
        if captureMode == "typed" { return true }
        guard let manual = QuickMeasureEntry.typedMethodRaw(for: kind) else {
            return false
        }
        return method == manual
    }

    /// This reading with the time it was MEASURED re-stated by hand.
    ///
    /// Nothing else moves — when a tree was measured is a fact ABOUT the
    /// reading, not a change to the measurement, and the estimator is frozen —
    /// but `timeSource` is stamped `typed` in the same call, so the two travel
    /// as the one fact they are. There is no way to set the time without the
    /// stamp, which is what "may be edited but never silently" means in code.
    ///
    /// The caller has already put `newValue` through
    /// `MeasuredTimeInput.resolve`; `QuickMeasureHistory.setMeasuredTime` runs
    /// that rule again at the write.
    public func settingCreatedAt(_ newValue: Date) -> QuickMeasureEntry {
        QuickMeasureEntry(
            id: id, kind: kind, value: value,
            secondaryValue: secondaryValue, sigma: sigma,
            confidenceRaw: confidenceRaw, method: method,
            createdAt: newValue, treeNumber: treeNumber, treeName: treeName,
            plotID: plotID,
            speciesCode: speciesCode, position: position,
            damageCodes: damageCodes, note: note,
            latitude: latitude, longitude: longitude,
            photoPath: photoPath, captureMode: captureMode, truth: truth,
            truthSource: truthSource, truthUnit: truthUnit,
            positionSource: positionSource,
            timeSource: TimeSource.typed.rawValue)
    }

    /// This reading with its coordinate replaced by one the cruiser TYPED,
    /// or cleared back to no position at all (`nil, nil`).
    ///
    /// Nothing else moves — where a reading was taken is an observation
    /// ABOUT it, not a change to the measurement — but the source is
    /// restamped `manual` so no later reader can mistake a hand-entered
    /// coordinate for a device fix. Clearing drops the source too: an
    /// absent position has no provenance to record.
    public func settingPosition(latitude newLat: Double?,
                                longitude newLon: Double?) -> QuickMeasureEntry {
        let hasFix = newLat != nil && newLon != nil
        return QuickMeasureEntry(
            id: id, kind: kind, value: value,
            secondaryValue: secondaryValue, sigma: sigma,
            confidenceRaw: confidenceRaw, method: method,
            createdAt: createdAt, treeNumber: treeNumber, treeName: treeName,
            plotID: plotID,
            speciesCode: speciesCode, position: position,
            damageCodes: damageCodes, note: note,
            latitude: hasFix ? newLat : nil,
            longitude: hasFix ? newLon : nil,
            photoPath: photoPath, captureMode: captureMode, truth: truth,
            truthSource: truthSource, truthUnit: truthUnit,
            positionSource: hasFix ? PositionSource.manual.rawValue : nil,
            timeSource: timeSource)
    }

    /// How this reading's tree is labelled anywhere a tree is named — the
    /// cruiser's name when there is one, else the bare "#12" the log and the
    /// map pins have always shown. nil only for a reading that belongs to no
    /// tree at all (a sampling plot, a loose distance).
    public var displayTreeName: String? {
        if let treeName { return treeName }
        return treeNumber.map { "#\($0)" }
    }

    /// Unit string for `value`. cm for diameter, m elsewhere.
    public var valueUnit: String {
        switch kind {
        case .dbh:    return "cm"
        case .height, .crown, .distance, .samplingPlot: return "m"
        }
    }

    /// Unit string for `sigma`. `mm` for diameter (millimetre-scale
    /// RANSAC RMSE) and `m` for height (metres of combined geometric
    /// uncertainty). Other kinds use `m`.
    public var sigmaUnit: String {
        switch kind {
        case .dbh:    return "mm"
        case .height, .crown, .distance, .samplingPlot: return "m"
        }
    }

    /// Unit for `secondaryValue` when present. Crown stores height in
    /// metres; sampling plot stores area in m².
    public var secondaryValueUnit: String {
        switch kind {
        case .crown:        return "m"
        case .samplingPlot: return "m²"
        case .dbh, .height, .distance: return ""
        }
    }

    // MARK: Hand-typed readings

    /// `method` raw a hand-typed reading of `kind` carries — the same
    /// manual-entry arms the scan screens stamp on a typed diameter /
    /// height. nil for the compound kinds, which have no typed arm.
    public static func typedMethodRaw(for kind: Kind) -> String? {
        switch kind {
        case .dbh:    return "manualVisual"
        case .height: return "manualEntry"
        case .crown, .distance, .samplingPlot: return nil
        }
    }

    /// This reading's method once it is typed: the manual arm when the kind
    /// has one, otherwise whatever produced the reading originally.
    public var typedMethodRaw: String {
        QuickMeasureEntry.typedMethodRaw(for: kind) ?? method
    }

    /// This reading re-stated with a value the cruiser TYPED.
    ///
    /// A typed number has no geometry behind it, so it cannot keep the
    /// sensor's σ or its edge provenance: σ is dropped (never zeroed — ±0
    /// claims a perfect measurement and poisons the σ column the accuracy
    /// work reads) and `captureMode` becomes "typed", the vocabulary the
    /// scan screens already stamp. `createdAt` is kept: correcting a number
    /// is not a second visit to the tree.
    public func typedValue(_ newValue: Double) -> QuickMeasureEntry {
        QuickMeasureEntry(
            id: id, kind: kind, value: newValue,
            secondaryValue: secondaryValue,
            sigma: nil,
            confidenceRaw: confidenceRaw,
            method: typedMethodRaw,
            createdAt: createdAt, treeNumber: treeNumber, treeName: treeName,
            plotID: plotID,
            speciesCode: speciesCode, position: position,
            damageCodes: damageCodes, note: note,
            latitude: latitude, longitude: longitude,
            photoPath: photoPath, captureMode: "typed", truth: truth,
            truthSource: truthSource, truthUnit: truthUnit,
            positionSource: positionSource,
            timeSource: timeSource)
    }

    /// This reading re-homed into another plot. Every other field is
    /// carried across verbatim: the two re-homing paths (default-plot
    /// bootstrap, plot deletion) used to rebuild the entry from a partial
    /// argument list, which silently dropped the GPS fix, the photo, the
    /// capture mode — and would now drop the ground truth.
    ///
    /// The third caller is the cruiser's own move (`moveEntries`), and it is
    /// the reason this matters most: re-filing a reading must change nothing
    /// ABOUT the reading. A move that quietly lost a σ or a tape number would
    /// cost the accuracy study the observation, not just its grouping.
    public func inPlot(_ newPlotID: UUID?) -> QuickMeasureEntry {
        QuickMeasureEntry(
            id: id, kind: kind, value: value,
            secondaryValue: secondaryValue, sigma: sigma,
            confidenceRaw: confidenceRaw, method: method,
            createdAt: createdAt, treeNumber: treeNumber, treeName: treeName,
            plotID: newPlotID,
            speciesCode: speciesCode, position: position,
            damageCodes: damageCodes, note: note,
            latitude: latitude, longitude: longitude,
            photoPath: photoPath, captureMode: captureMode, truth: truth,
            truthSource: truthSource, truthUnit: truthUnit,
            positionSource: positionSource,
            timeSource: timeSource)
    }

    /// This reading with the two free-form details the quick-edit sheet owns
    /// replaced. Everything else is carried across verbatim.
    ///
    /// Same reason `inPlot` exists. The edit sheet used to rebuild the entry
    /// from an argument list and silently dropped `positionSource` off the end
    /// of it — so opening the sheet on a reading whose coordinate the cruiser
    /// had TYPED, changing the species, and saving re-labelled that coordinate
    /// a device fix. Adding `truthSource` to the same list would have made it
    /// two provenance fields lost to the same shape, so the list is gone: only
    /// what is being edited is named, and nothing else can fall off.
    public func settingDetails(speciesCode newSpecies: String?,
                               note newNote: String?) -> QuickMeasureEntry {
        QuickMeasureEntry(
            id: id, kind: kind, value: value,
            secondaryValue: secondaryValue, sigma: sigma,
            confidenceRaw: confidenceRaw, method: method,
            createdAt: createdAt, treeNumber: treeNumber, treeName: treeName,
            plotID: plotID,
            speciesCode: newSpecies, position: position,
            damageCodes: damageCodes, note: newNote,
            latitude: latitude, longitude: longitude,
            photoPath: photoPath, captureMode: captureMode, truth: truth,
            truthSource: truthSource, truthUnit: truthUnit,
            positionSource: positionSource,
            timeSource: timeSource)
    }

    /// This reading with its ground truth set (or cleared with nil).
    /// Nothing else moves — a truth is an observation ABOUT the reading,
    /// not a change to it.
    ///
    /// `source` travels WITH the value because the two are one fact: a truth
    /// and how it got here. It defaults to nil, which is what every hand-typed
    /// path means; only the recovery pass passes `TruthSource.capture`.
    /// Clearing drops the source too — an absent truth has no provenance,
    /// exactly as `settingPosition` treats an absent coordinate.
    ///
    /// `unit` travels the same way and for the same reason, and defaults to nil
    /// because the hand-typed paths do not record it today. A NEW value is a
    /// different fact from the one that was here, so it never inherits the old
    /// value's unit: carrying `truthUnit` across a retype would leave a number
    /// stamped with a unit nobody typed it in.
    public func settingTruth(_ newTruth: Double?,
                             source: String? = nil,
                             unit: String? = nil) -> QuickMeasureEntry {
        QuickMeasureEntry(
            id: id, kind: kind, value: value,
            secondaryValue: secondaryValue, sigma: sigma,
            confidenceRaw: confidenceRaw, method: method,
            createdAt: createdAt, treeNumber: treeNumber, treeName: treeName,
            plotID: plotID,
            speciesCode: speciesCode, position: position,
            damageCodes: damageCodes, note: note,
            latitude: latitude, longitude: longitude,
            photoPath: photoPath, captureMode: captureMode, truth: newTruth,
            truthSource: newTruth == nil ? nil : source,
            truthUnit: newTruth == nil ? nil : unit,
            positionSource: positionSource,
            timeSource: timeSource)
    }

    /// This reading with its ground truth RE-BASED into the unit it was
    /// actually typed in — `TruthUnitRepair`'s only mutator on a reading.
    ///
    /// Distinct from `settingTruth` because it is not a new observation: the
    /// tape number is the same number the cruiser wrote down, and only the
    /// scale it was stored at is being corrected. So `truthSource` is KEPT
    /// (this truth still arrived here by the recovery pass's matching, and
    /// re-labelling it "typed" would be a provenance claim nobody made), while
    /// `truthUnit` is stamped — which is what makes the repair one-shot: the
    /// rule that selected this reading requires an absent unit.
    public func repairingTruthUnit(base newTruth: Double,
                                   unit: String) -> QuickMeasureEntry {
        QuickMeasureEntry(
            id: id, kind: kind, value: value,
            secondaryValue: secondaryValue, sigma: sigma,
            confidenceRaw: confidenceRaw, method: method,
            createdAt: createdAt, treeNumber: treeNumber, treeName: treeName,
            plotID: plotID,
            speciesCode: speciesCode, position: position,
            damageCodes: damageCodes, note: note,
            latitude: latitude, longitude: longitude,
            photoPath: photoPath, captureMode: captureMode, truth: newTruth,
            truthSource: truthSource, truthUnit: unit,
            positionSource: positionSource,
            timeSource: timeSource)
    }

    /// A brand-new reading the cruiser typed for a tree the sensors never
    /// measured. No σ, no GPS fix, no photo — none of those exist for a
    /// number that came off a tape.
    ///
    /// `speciesCode` rides along because a tree entered by hand is named and
    /// identified in the same breath as it is measured; it is an observation
    /// about the stem, not about the number, so carrying it changes nothing
    /// about how the reading is stamped. It defaults to nil so the row
    /// editor's "complete this half-measured tree" path is untouched.
    public static func typed(kind: Kind,
                             value: Double,
                             treeNumber: Int?,
                             treeName: String? = nil,
                             plotID: UUID?,
                             speciesCode: String? = nil,
                             truth: Double? = nil) -> QuickMeasureEntry {
        QuickMeasureEntry(
            kind: kind, value: value,
            sigma: nil,
            // "yellow" is what the scan screens' own manual-entry path
            // stamps — a typed reading is usable but unverified.
            confidenceRaw: "yellow",
            method: typedMethodRaw(for: kind) ?? "manualVisual",
            treeNumber: treeNumber, treeName: treeName, plotID: plotID,
            speciesCode: speciesCode,
            position: kind == Kind.dbh ? StemPosition.dbh : nil,
            captureMode: "typed", truth: truth)
    }
}

// MARK: - Store

@MainActor
public final class QuickMeasureHistory: ObservableObject {

    public enum Keys {
        public static let entries = "tc.quickMeasure.entries"
        public static let plots   = "tc.quickMeasure.plots"
        public static let activePlot = "tc.quickMeasure.activePlot"
    }

    /// Current schema version stamped on every JSONL sidecar write.
    /// Bumped to 2 in Phase 2 — entries gained plotID / speciesCode /
    /// position / damageCodes / note fields; a `QuickMeasurePlot` set
    /// is also persisted alongside. 3 adds the ground-truth field; it
    /// needs no migrator (absent decodes as nil), so a v2 sidecar still
    /// replays without one. 4 adds `positionSource` — where a reading's
    /// coordinate came from — on the same terms: absent decodes as nil,
    /// which `positionRecordedSource` reads as the device fix it can only
    /// have been. 5 adds `truthSource` — how a ground truth came to sit on
    /// the reading — on the same terms again: absent decodes as nil, which
    /// `truthRecordedSource` reads as the typed value it can only have been.
    /// 6 adds `truthUnit` — the unit a ground truth was typed in. Same terms
    /// again (absent decodes as nil), and nil keeps its manifest meaning: NOT
    /// STATED, never "metric". A v5 sidecar replays under 6 unchanged, and a
    /// build reading a 6 sidecar with a v5 decoder simply ignores the key.
    /// 7 adds `timeSource` — how a reading's `createdAt` came to be what it is.
    /// Same terms once more: absent decodes as nil, which `timeRecordedSource`
    /// reads as the device stamp it can only have been, because until the
    /// record sheet could set a time nothing but the clock ever wrote one.
    public static let schemaVersion: Int = 7

    @Published public private(set) var entries: [QuickMeasureEntry] = []
    /// All Quick Measure plots known to the app, newest first. Always
    /// contains at least the auto-created default plot.
    @Published public private(set) var plots: [QuickMeasurePlot] = []
    /// Currently-selected plot — readings save into this plot unless
    /// the cruiser explicitly picks another one. Defaults to the
    /// default plot on a fresh install.
    @Published public var activePlotID: UUID?
    /// Fires `true` when a new append has pushed the history within
    /// 5 % of the cap — the UI can surface a toast so the cruiser
    /// archives before silent truncation kicks in.
    @Published public private(set) var isNearCapacity: Bool = false

    private let defaults: UserDefaults
    private let capacity: Int
    private let sidecarURL: URL?

    public init(defaults: UserDefaults = .standard,
                capacity: Int = 500,
                sidecarURL: URL? = nil) {
        self.defaults = defaults
        self.capacity = capacity
        let resolved = sidecarURL ?? Self.defaultSidecarURL()
        self.sidecarURL = resolved
        self.entries = Self.loadBest(defaults: defaults, sidecar: resolved)
        self.plots   = Self.loadPlots(from: defaults)

        // First-launch + Phase-2 migration: ensure a default plot
        // exists and every legacy entry without a plotID is moved
        // into it. Mutates `entries` + `plots` and persists once.
        bootstrapDefaultPlotIfNeeded()

        if let raw = defaults.string(forKey: Keys.activePlot),
           let id = UUID(uuidString: raw),
           plots.contains(where: { $0.id == id }) {
            self.activePlotID = id
        } else {
            self.activePlotID = plots.first(where: { $0.isDefault })?.id
        }

        self.recomputeCapacityFlag()
    }

    /// Test / preview factory backed by an isolated UserDefaults suite
    /// and a temp-directory sidecar (so tests don't collide with real
    /// app data).
    public static func ephemeral(capacity: Int = 500) -> QuickMeasureHistory {
        let name = "tc.quickMeasure.preview.\(UUID().uuidString)"
        let ud = UserDefaults(suiteName: name) ?? .standard
        let tmp = FileManager.default.temporaryDirectory
            .appendingPathComponent("qm-\(UUID().uuidString).jsonl")
        return QuickMeasureHistory(defaults: ud, capacity: capacity,
                                   sidecarURL: tmp)
    }

    // MARK: Mutations

    public func append(_ entry: QuickMeasureEntry) {
        var next = entries
        next.insert(entry, at: 0)
        if next.count > capacity {
            next = Array(next.prefix(capacity))
        }
        entries = next
        appendToSidecar(entry)
        persistCache()
        recomputeCapacityFlag()
    }

    /// Replace an existing entry in place, matched by id, and persist —
    /// the quick-peek "Edit this tree" mutator. No-op if the id is gone.
    /// Mirrors `delete`'s persistence (rewrite the sidecar + cache); the
    /// caller supplies a fully-formed entry carrying the same id.
    public func update(_ entry: QuickMeasureEntry) {
        guard let idx = entries.firstIndex(where: { $0.id == entry.id })
        else { return }
        entries[idx] = entry
        rewriteSidecar()
        persistCache()
        recomputeCapacityFlag()
    }

    /// Attach recovered ground truths to readings, in ONE persist.
    ///
    /// The per-entry `update` rewrites the whole sidecar and the whole cache
    /// each time; a recovery pass touching a hundred readings would do that a
    /// hundred times over on a phone the cruiser is holding. This applies the
    /// whole set, then persists once.
    ///
    /// REFUSES to overwrite: an id whose reading already carries a truth is
    /// skipped here as well as in the planner, because this is the call that
    /// actually writes and the guarantee has to hold at the write. Returns how
    /// many readings were actually changed — the number the cruiser is shown,
    /// not the number that was requested.
    @discardableResult
    public func backfillTruths(_ attachments: [UUID: Double]) -> Int {
        guard !attachments.isEmpty else { return 0 }
        var changed = 0
        var next = entries
        for (idx, e) in next.enumerated() {
            guard let value = attachments[e.id], e.truth == nil else { continue }
            next[idx] = e.settingTruth(
                value, source: QuickMeasureEntry.TruthSource.capture.rawValue)
            changed += 1
        }
        guard changed > 0 else { return 0 }
        entries = next
        rewriteSidecar()
        persistCache()
        return changed
    }

    /// Re-state WHEN one reading was measured, and put the log back in order.
    ///
    /// ONE READING AT A TIME, deliberately. The cruiser was offered a bulk
    /// offset and declined it: their notebook holds a per-tree time to the
    /// minute, so they will type each one anyway and a shared offset buys
    /// nothing. Keeping it singular also keeps the provenance simple — every
    /// hand-set time is one deliberate act on one reading.
    ///
    /// RE-CHECKS AT THE WRITE, like `repairTruthUnits`: the screen already put
    /// the picked time through `MeasuredTimeInput.resolve`, but this is the
    /// call that actually writes, so the rule has to hold here too. A future
    /// time is refused and NOTHING is written.
    ///
    /// RE-SORTS, which is the whole point of the feature. `entries` is
    /// newest-first by contract — the field log takes each tree's newest
    /// diameter and height off the head of a group, the summary header's LAST
    /// cell reads `entries.first`, and `treeName(forTreeNumber:plotID:)` reads
    /// the tail — and a corrected time that left the array in its old order
    /// would move the row in the log (rows sort on their own earliest
    /// `createdAt`) while those head/tail readings still answered from the
    /// order the reading was ADDED in. Sorted stably, so readings sharing a
    /// minute keep the order they already had rather than shuffling under a
    /// cruiser who is looking at them.
    ///
    /// REFUSES A NO-OP. Opening the editor and saving the minute that was
    /// already there is not an edit, and stamping `typed` on it would claim a
    /// hand-set time for a reading nobody re-timed — and would quietly throw
    /// away the seconds a sensor stamp carries, which are what makes a capture
    /// and its manifest match to better than a minute. The comparison is
    /// minute-to-minute because that is the precision the cruiser picks in.
    ///
    /// Returns true only when a reading actually moved.
    @discardableResult
    public func setMeasuredTime(id: UUID, to newTime: Date) -> Bool {
        guard let idx = entries.firstIndex(where: { $0.id == id }) else { return false }
        guard case .time(let stamped) = MeasuredTimeInput.resolve(newTime)
        else { return false }
        guard MeasuredTimeInput.truncatedToMinute(entries[idx].createdAt) != stamped
        else { return false }
        var next = entries
        next[idx] = next[idx].settingCreatedAt(stamped)
        entries = Self.newestFirst(next)
        rewriteSidecar()
        persistCache()
        return true
    }

    /// `entries` in the order the whole app expects to read it: newest first,
    /// ties in the order they were already in.
    ///
    /// Swift's `sorted(by:)` gives no stability guarantee, and an unstable sort
    /// over a log where a burst of readings can share a second would reorder
    /// rows nobody touched. Sorting on (time, original position) makes it
    /// stable without depending on the algorithm.
    static func newestFirst(_ list: [QuickMeasureEntry]) -> [QuickMeasureEntry] {
        list.enumerated()
            .sorted { a, b in
                a.element.createdAt == b.element.createdAt
                    ? a.offset < b.offset
                    : a.element.createdAt > b.element.createdAt
            }
            .map(\.element)
    }

    /// Two truth values are the SAME value inside this band — the field log's
    /// `valueEpsilon` and `TruthBackfill.valueEpsilon` by the same reasoning:
    /// everything that writes a truth writes the metric base through one
    /// parser, so the only difference between two copies is float round-trip.
    static let truthValueEpsilon: Double = 0.001

    /// Re-base ground truths that were stored at the wrong scale, in ONE
    /// persist — `TruthUnitRepair`'s write into the reading log.
    ///
    /// Same batching reason as `backfillTruths`: two hundred `update` calls
    /// would rewrite the whole sidecar two hundred times on a phone in the
    /// cruiser's hand.
    ///
    /// RE-CHECKS AT THE WRITE. The plan is computed off the main actor and the
    /// log can have moved since, so each reading must STILL be the one that was
    /// planned for: the truth still the pre-repair number, and still carrying
    /// no unit. A reading the cruiser retyped in between is left exactly as
    /// they left it. Returns how many readings actually changed — the number
    /// reported, never the number requested.
    @discardableResult
    public func repairTruthUnits(
        _ repairs: [UUID: (before: Double, after: Double, unit: String)]
    ) -> Int {
        guard !repairs.isEmpty else { return 0 }
        var changed = 0
        var next = entries
        for (idx, e) in next.enumerated() {
            guard let r = repairs[e.id],
                  e.truthUnit == nil,
                  let stored = e.truth,
                  abs(stored - r.before) <= Self.truthValueEpsilon
            else { continue }
            next[idx] = e.repairingTruthUnit(base: r.after, unit: r.unit)
            changed += 1
        }
        guard changed > 0 else { return 0 }
        entries = next
        rewriteSidecar()
        persistCache()
        return changed
    }

    /// Saves `entry` as THE reading of its kind for its (plot, tree) — any
    /// earlier reading of the same kind on the same tree is removed rather
    /// than left behind as a second one.
    ///
    /// This is what "measure it again" means from the field log: the log
    /// shows one row per (plot, tree) and picks the newest reading of each
    /// kind, so an appended re-measure left the superseded number invisible
    /// on screen but still in the CSV, where it read as a second tree
    /// visit. Readings with no tree number are never merged (the field log
    /// gives each its own row), so those just append.
    public func replaceReading(_ entry: QuickMeasureEntry) {
        guard let tree = entry.treeNumber else {
            append(entry)
            return
        }
        let plot = entry.plotID ?? defaultPlotID()
        let superseded = entries.filter {
            $0.id != entry.id && $0.kind == entry.kind
                && $0.treeNumber == tree
                && ($0.plotID ?? defaultPlotID()) == plot
        }
        for old in superseded {
            if let photo = old.photoPath { MeasurePhotoStore.delete(photo) }
        }
        var next = entries.filter { e in !superseded.contains { $0.id == e.id } }
        if let idx = next.firstIndex(where: { $0.id == entry.id }) {
            next[idx] = entry
        } else {
            next.insert(entry, at: 0)
            if next.count > capacity { next = Array(next.prefix(capacity)) }
        }
        entries = next
        rewriteSidecar()
        persistCache()
        recomputeCapacityFlag()
    }

    public func delete(id: UUID) {
        if let photo = entries.first(where: { $0.id == id })?.photoPath {
            MeasurePhotoStore.delete(photo)
        }
        entries.removeAll { $0.id == id }
        rewriteSidecar()
        persistCache()
        recomputeCapacityFlag()
    }

    public func clearAll() {
        entries = []
        rewriteSidecar()
        persistCache()
        recomputeCapacityFlag()
    }

    // MARK: - Plot management

    /// Adds a new plot to the front of the plot list, persists, and
    /// makes it the active plot.
    ///
    /// `makeActive` is true for every path that creates a plot in order to
    /// measure INTO it, which is all of them but one: the field log's move
    /// picker names a destination for readings already taken, and re-pointing
    /// the next scan because the cruiser tidied up some old ones would be a
    /// silent change to where their next measurement lands. Moving data and
    /// choosing where new data goes are different acts.
    @discardableResult
    public func createPlot(name: String,
                            unitName: String = "",
                            acres: Double? = nil,
                            typeRaw: String = "fixed",
                            baf: Double? = nil,
                            radiusFt: Double? = nil,
                            parentPlotID: UUID? = nil,
                            nestedKind: String? = nil,
                            makeActive: Bool = true) -> QuickMeasurePlot {
        let plot = QuickMeasurePlot(
            name: name, unitName: unitName, acres: acres,
            typeRaw: typeRaw, baf: baf, radiusFt: radiusFt,
            parentPlotID: parentPlotID, nestedKind: nestedKind,
            createdAt: Date(), isDefault: false)
        plots.insert(plot, at: 0)
        if makeActive { activePlotID = plot.id }
        persistPlots()
        return plot
    }

    /// Re-homes readings into `plotID`, in ONE persist.
    ///
    /// The field log's move (see Screens/QuickMove.swift) exists because a
    /// reading's plot could be chosen before the measurement and never after
    /// it. This is the write behind it, and it is deliberately a SET rather
    /// than a per-entry `update`: that one rewrites the whole sidecar and the
    /// whole cache each time, and a tree's diameter and height must not be
    /// two separate rewrites with a window in between where half the stem has
    /// moved.
    ///
    /// Only `plotID` changes — `inPlot` carries every other field across
    /// verbatim, including the ground truth and both provenance stamps.
    /// Refuses an unknown destination outright rather than filing readings
    /// under an id no plot answers to. Returns how many readings actually
    /// changed, which is the number the caller may report.
    @discardableResult
    public func moveEntries(_ ids: Set<UUID>, toPlot plotID: UUID) -> Int {
        guard !ids.isEmpty,
              plots.contains(where: { $0.id == plotID }) else { return 0 }
        var changed = 0
        var next = entries
        for (idx, entry) in next.enumerated() {
            guard ids.contains(entry.id), entry.plotID != plotID else { continue }
            next[idx] = entry.inPlot(plotID)
            changed += 1
        }
        guard changed > 0 else { return 0 }
        entries = next
        rewriteSidecar()
        persistCache()
        return changed
    }

    /// Plots nested under `id`, sorted by creation time. Empty for
    /// non-parent plots.
    public func nestedChildren(of id: UUID) -> [QuickMeasurePlot] {
        plots
            .filter { $0.parentPlotID == id }
            .sorted { $0.createdAt < $1.createdAt }
    }

    public func renamePlot(id: UUID, to newName: String) {
        guard let idx = plots.firstIndex(where: { $0.id == id }) else { return }
        plots[idx].name = newName
        persistPlots()
    }

    public func deletePlot(id: UUID) {
        // Default plot is permanent — protects the migrated legacy
        // log from accidental deletion.
        guard let idx = plots.firstIndex(where: { $0.id == id }),
              !plots[idx].isDefault else { return }
        plots.remove(at: idx)
        // Re-home any orphaned entries to the default plot.
        let defaultID = plots.first(where: { $0.isDefault })?.id
        let updated = entries.map { entry -> QuickMeasureEntry in
            entry.plotID == id ? entry.inPlot(defaultID) : entry
        }
        entries = updated
        if activePlotID == id {
            activePlotID = defaultID
        }
        persistPlots()
        rewriteSidecar()
        persistCache()
    }

    public func setActivePlot(id: UUID) {
        guard plots.contains(where: { $0.id == id }) else { return }
        activePlotID = id
        defaults.set(id.uuidString, forKey: Keys.activePlot)
    }

    /// Convenience accessor for filtering displays by current plot.
    public func entries(forPlot id: UUID?) -> [QuickMeasureEntry] {
        guard let id else { return entries }
        return entries.filter { ($0.plotID ?? defaultPlotID()) == id }
    }

    public func plot(id: UUID) -> QuickMeasurePlot? {
        plots.first { $0.id == id }
    }

    public func defaultPlotID() -> UUID? {
        plots.first(where: { $0.isDefault })?.id
    }

    /// Bootstraps the default plot on first launch and re-homes any
    /// legacy entries that pre-date Phase 2 (no `plotID`) into it.
    /// Idempotent — safe to call on every init.
    private func bootstrapDefaultPlotIfNeeded() {
        if !plots.contains(where: { $0.isDefault }) {
            let def = QuickMeasurePlot(
                name: "Quick measurements",
                unitName: "",
                acres: nil,
                typeRaw: "fixed",
                createdAt: entries.last?.createdAt ?? Date(),
                isDefault: true)
            plots.append(def)
        }
        guard let defaultID = plots.first(where: { $0.isDefault })?.id
        else { return }

        var migrated = false
        let updated = entries.map { entry -> QuickMeasureEntry in
            if entry.plotID == nil {
                migrated = true
                return entry.inPlot(defaultID)
            }
            return entry
        }
        if migrated {
            entries = updated
            rewriteSidecar()
            persistCache()
        }
        persistPlots()
    }

    private func persistPlots() {
        do {
            let data = try JSONEncoder().encode(plots)
            defaults.set(data, forKey: Keys.plots)
        } catch {}
    }

    private static func loadPlots(from defaults: UserDefaults) -> [QuickMeasurePlot] {
        guard let data = defaults.data(forKey: Keys.plots) else { return [] }
        return (try? JSONDecoder().decode([QuickMeasurePlot].self, from: data)) ?? []
    }

    // MARK: - Tree identity helpers

    /// Most recently used tree number across the log. Used by the
    /// scan flow to offer "Continue tree #N" without forcing the
    /// cruiser to retype the number every time.
    public var lastTreeNumber: Int? {
        entries.first(where: { $0.treeNumber != nil })?.treeNumber
    }

    /// All distinct tree numbers in the log, sorted ascending. Lets
    /// the picker show a quick history of trees the cruiser has
    /// already started — they can resume measuring an older one.
    public var distinctTreeNumbers: [Int] {
        var seen = Set<Int>()
        var out: [Int] = []
        for e in entries {
            if let n = e.treeNumber, !seen.contains(n) {
                seen.insert(n)
                out.append(n)
            }
        }
        return out.sorted()
    }

    /// Next tree number to suggest when the cruiser starts a new tree.
    /// `max(existing) + 1`, or 1 on a fresh log.
    public var suggestedNextTreeNumber: Int {
        (distinctTreeNumbers.max() ?? 0) + 1
    }

    /// The name already recorded against a tree, if it has one. `entries` is
    /// newest-first, so the LAST match is the tree's first reading — a
    /// re-measurement or a chained height picks up the name that reading was
    /// given instead of arriving nameless and splitting the tree in two in
    /// the export.
    public func treeName(forTreeNumber n: Int?, plotID: UUID?) -> String? {
        guard let n else { return nil }
        let fallback = defaultPlotID()
        return entries.last {
            $0.treeNumber == n
                && ($0.plotID ?? fallback) == (plotID ?? fallback)
                && $0.treeName != nil
        }?.treeName
    }

    /// The species already recorded against a tree, if any reading carries
    /// one. `entries` is newest-first and this takes the FIRST match, which is
    /// the cruiser's latest word on that stem — the same rule the map pin's
    /// peek card already reads a species by, so the chooser and the pin cannot
    /// disagree about what species a tree is.
    ///
    /// Deliberately the opposite end of the log from `treeName(forTreeNumber:
    /// plotID:)`, which takes the tree's FIRST reading. A name is an
    /// identifier other surfaces and the export already join on, so it must
    /// not change under them; a species is an observation, and a correction
    /// made later is the better of the two.
    public func speciesCode(forTreeNumber n: Int?, plotID: UUID?) -> String? {
        guard let n else { return nil }
        let fallback = defaultPlotID()
        return entries.first {
            $0.treeNumber == n
                && ($0.plotID ?? fallback) == (plotID ?? fallback)
                && Self.hasSpecies($0)
        }?.speciesCode
    }

    /// The name to offer for the next tree — the HIGHEST name in the series
    /// the cruiser is currently using, stepped on by `TreeNameSequence`. nil
    /// on a log that has never been named, and then the chooser's field simply
    /// starts empty.
    ///
    /// This used to step on the most recent name, which is not the same thing:
    /// a re-measurement is appended carrying the name it already had, so
    /// re-measuring T01 after T03 made the log's newest name "T01" and the
    /// chooser proposed "T02" — a name a different stem already wears. The
    /// number suggestion beside it is `max + 1` and cannot collide; the name
    /// now matches that rule. `entries` is newest-first, which is the order
    /// `nextInSeries` expects.
    public var suggestedNextTreeName: String? {
        TreeNameSequence.nextInSeries(entries.compactMap(\.treeName))
    }

    /// The species to offer for the next tree — the code on the most recent
    /// reading that carries one. nil on a log where nothing has been given a
    /// species, and then the picker simply opens unset.
    ///
    /// It lives here beside the name suggestion because it is the same kind of
    /// rule and the two are read together; the chooser and the field log's
    /// new-tree sheet both take it from here rather than each deciding what
    /// "the last species" means.
    ///
    /// Unlike the name, this is NOT a series that steps on — a stand is
    /// usually one species tree after tree, so the last one seen is the
    /// suggestion. Blank and whitespace-only codes are skipped: a reading
    /// saved with the species left unset must not propose "" as a species.
    ///
    /// This is a suggestion for a control, never a recorded observation. What
    /// the caller does with an untouched one is the caller's decision — see
    /// the measure chooser.
    public var suggestedNextSpeciesCode: String? {
        entries.first(where: Self.hasSpecies)?.speciesCode
    }

    /// A reading carries a species when the code is present AND not blank.
    /// `.whitespacesAndNewlines`, matching Kotlin's `isBlank()` in the Android
    /// sibling, so the same log proposes the same species on both phones.
    private static func hasSpecies(_ e: QuickMeasureEntry) -> Bool {
        guard let code = e.speciesCode else { return false }
        return !code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Returns a brief description of an existing tree's measurements
    /// (e.g. "DBH 34.5 cm · Height 28 m") for the picker UI. Returns
    /// `nil` if the log has nothing for that tree number.
    public func summary(forTreeNumber n: Int) -> String? {
        let owned = entries.filter { $0.treeNumber == n }
        guard !owned.isEmpty else { return nil }
        let dbh = owned.first { $0.kind == .dbh }
        let hgt = owned.first { $0.kind == .height }
        var parts: [String] = []
        if let d = dbh {
            parts.append(String(format: "DBH %.1f cm", d.value))
        }
        if let h = hgt {
            parts.append(String(format: "Height %.2f m", h.value))
        }
        if parts.isEmpty { return "—" }
        return parts.joined(separator: " · ")
    }

    // MARK: CSV export

    /// Writes the current history as RFC-4180-compliant CSV to
    /// `Documents/Exports/quick-measure-<ts>.csv` and returns the URL.
    ///
    /// • Every field is quoted and embedded quotes are doubled.
    /// • Line separator is CRLF (Excel on Windows expects it).
    /// • A UTF-8 BOM prefix keeps Excel happy on double-byte content.
    /// • Units are now explicit per-field columns (`value_unit`,
    ///   `sigma_unit`) so spreadsheet formulas can't accidentally mix
    ///   DBH millimetres with height metres.
    public func exportCSV() -> URL? {
        guard !entries.isEmpty else { return nil }
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil,
                                     create: true) else { return nil }
        let dir = docs.appendingPathComponent("Exports", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)

        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]
        let stamp = iso.string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let url = dir.appendingPathComponent("quick-measure-\(stamp).csv")

        // "truth_source" is deliberately the LAST column — same order as the
        // Android exporter so the two platforms' CSVs diff clean, and APPENDED
        // rather than inserted beside "truth" so a reader written against the
        // existing column order still lines up.
        // "truth" sits beside the value it is the truth OF, in the same
        // unit (`value_unit`); blank means none was ever entered.
        //
        // position_source says where latitude/longitude came from
        // ("gpsSingle" for a device fix, "manual" for one the cruiser
        // typed). Without it a hand-entered coordinate exported exactly
        // like a satellite one.
        //
        // truth_source is the same idea one column over: "typed" for a number
        // entered against this reading, "capture" for one recovered from a
        // raw-capture manifest and matched to it. Blank means no truth. The
        // accuracy work must be able to drop the matched ones and still have
        // a corpus, so the distinction cannot live only in a commit message.
        //
        // time_source qualifies "timestamp", and is the only one of the four
        // that is never blank: every reading has a time. "device" is the clock
        // at the moment the reading was recorded; "typed" is a time the cruiser
        // set by hand from a notebook. The analysis joins on time — across
        // phones, to raw-capture manifests, and for live-vs-superseded — so it
        // must be able to see which timestamps are claims about the past.
        let headers = ["id", "timestamp", "plot", "tree", "tree_name", "kind",
                       "value", "value_unit", "truth",
                       "secondary_value", "secondary_unit",
                       "sigma", "sigma_unit",
                       "species", "position", "damage", "note",
                       "confidence", "method",
                       "latitude", "longitude", "photo", "capture_mode",
                       "position_source", "truth_source", "time_source"]
        var out = headers.map(Self.csvField).joined(separator: ",")
        out += "\r\n"

        for e in entries {
            let sigma = e.sigma.map { String(format: "%.3f", $0) } ?? ""
            let lat = e.latitude.map { String(format: "%.6f", $0) } ?? ""
            let lon = e.longitude.map { String(format: "%.6f", $0) } ?? ""
            let plotName = e.plotID
                .flatMap { id in plots.first { $0.id == id } }
                .map(\.name) ?? ""
            let secVal = e.secondaryValue.map { String(format: "%.3f", $0) } ?? ""
            // Split into typed sub-arrays — a single 20-element literal of
            // mixed optional-map expressions blows up Swift's type-checker.
            var cols: [String] = [
                e.id.uuidString,
                iso.string(from: e.createdAt),
                plotName,
                e.treeNumber.map(String.init) ?? "",
                e.treeName ?? "",
                e.kind.rawValue,
                String(format: "%.3f", e.value),
                e.valueUnit,
                e.truth.map { String(format: "%.3f", $0) } ?? ""
            ]
            cols += [
                secVal,
                e.secondaryValue == nil ? "" : e.secondaryValueUnit,
                sigma,
                e.sigma == nil ? "" : e.sigmaUnit,
                e.speciesCode ?? "",
                e.position?.rawValue ?? "",
                e.damageCodes.joined(separator: "|")
            ]
            cols += [
                e.note ?? "",
                e.confidenceRaw,
                e.method,
                lat,
                lon,
                e.photoPath ?? "",
                e.captureMode ?? "",
                e.positionRecordedSource ?? "",
                e.truthRecordedSource ?? "",
                e.timeRecordedSource
            ]
            let row = cols.map(Self.csvField).joined(separator: ",")
            out += row + "\r\n"
        }

        // UTF-8 BOM + body. Without the BOM Excel on Windows
        // misinterprets any non-ASCII (e.g. µ / °) as Latin-1.
        var data = Data([0xEF, 0xBB, 0xBF])
        data.append(out.data(using: .utf8) ?? Data())

        do {
            try data.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /// RFC-4180 field quoting: wraps every value in `"…"` and doubles
    /// any embedded double-quotes. CR / LF inside a field survive
    /// because the surrounding quotes escape them.
    private static func csvField(_ s: String) -> String {
        "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    // MARK: - Multi-table CSV bundle (5-file export)

    /// Writes a ZIP bundle containing five CSV files following a
    /// multi-table export schema:
    ///
    ///   • Samples.csv      — one row per plot
    ///   • Trees.csv        — one row per (plot, treeNumber) pair
    ///   • Stems.csv        — one row per diameter measurement
    ///   • Heights.csv      — one row per height measurement
    ///   • Calculations.csv — per-plot derived stats (BA/ac, TPA,
    ///                        QMD, mean H, BF/ac when DBH+H present)
    ///
    /// Returns the URL of the generated zip in Documents/Exports/,
    /// or nil if the log is empty / disk write failed.
    public func exportBundle(logRule: LogRule = .scribner) -> URL? {
        guard !entries.isEmpty else { return nil }

        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime]

        // -- Samples.csv --
        var samples = "id,name,unit,acres,type,baf_ft2_ac,radius_ft,created\r\n"
        for p in plots {
            let row = [
                p.id.uuidString,
                p.name,
                p.unitName,
                p.acres.map { String(format: "%.3f", $0) } ?? "",
                p.typeRaw,
                p.baf.map { String(format: "%.0f", $0) } ?? "",
                p.radiusFt.map { String(format: "%.1f", $0) } ?? "",
                iso.string(from: p.createdAt)
            ].map(Self.csvField).joined(separator: ",")
            samples += row + "\r\n"
        }

        // -- Trees.csv (plot × treeNumber, with first-found metadata) --
        var trees = "plot_id,plot,tree_number,tree_name,species,damage,note\r\n"
        let byPlotTree = Dictionary(grouping: entries) { e -> String in
            "\(e.plotID?.uuidString ?? "")|\(e.treeNumber ?? -1)"
        }
        for (_, group) in byPlotTree.sorted(by: { $0.key < $1.key }) {
            guard let any = group.first else { continue }
            let plotName = any.plotID
                .flatMap { id in plots.first { $0.id == id } }?.name ?? ""
            let species = group.compactMap { $0.speciesCode }.first ?? ""
            let dmg = Set(group.flatMap { $0.damageCodes }).joined(separator: "|")
            let note = group.compactMap { $0.note }.first ?? ""
            // Any one reading on the tree carries the name; take the first
            // that has one rather than `any`'s, which may be a later
            // re-measurement recorded before the name existed.
            let name = group.compactMap { $0.treeName }.first ?? ""
            let row = [
                any.plotID?.uuidString ?? "",
                plotName,
                any.treeNumber.map(String.init) ?? "",
                name, species, dmg, note
            ].map(Self.csvField).joined(separator: ",")
            trees += row + "\r\n"
        }

        // -- Stems.csv (one per DBH measurement) --
        // "capture_mode", "truth_source" then "time_source" appended as the
        // LAST columns (Android parity). truth_source qualifies truth_cm:
        // "typed" here, "capture" when the value was recovered from a
        // raw-capture manifest and matched to this stem. Blank when there is
        // no truth. time_source qualifies "timestamp" and is never blank —
        // "device" for the clock, "typed" for a time set by hand.
        var stems = "id,plot_id,tree_number,timestamp,dbh_cm,truth_cm,sigma_mm,position,confidence,method,capture_mode,truth_source,time_source\r\n"
        for e in entries where e.kind == .dbh {
            // Spelled out rather than inlined: adding the truth column tipped
            // this literal past what the type-checker will solve in one go.
            let truth: String = e.truth.map { String(format: "%.3f", $0) } ?? ""
            let sigma: String = e.sigma.map { String(format: "%.3f", $0) } ?? ""
            let row: [String] = [
                e.id.uuidString,
                e.plotID?.uuidString ?? "",
                e.treeNumber.map(String.init) ?? "",
                iso.string(from: e.createdAt),
                String(format: "%.3f", e.value),
                truth,
                sigma,
                e.position?.rawValue ?? "",
                e.confidenceRaw, e.method,
                e.captureMode ?? "",
                e.truthRecordedSource ?? "",
                e.timeRecordedSource
            ]
            stems += row.map(Self.csvField).joined(separator: ",") + "\r\n"
        }

        // -- Heights.csv (one per Height measurement) --
        // "truth_source" then "time_source" appended LAST — see Stems.csv.
        var heights = "id,plot_id,tree_number,timestamp,height_m,truth_m,sigma_m,confidence,method,truth_source,time_source\r\n"
        for e in entries where e.kind == .height {
            let truth: String = e.truth.map { String(format: "%.3f", $0) } ?? ""
            let sigma: String = e.sigma.map { String(format: "%.3f", $0) } ?? ""
            let row: [String] = [
                e.id.uuidString,
                e.plotID?.uuidString ?? "",
                e.treeNumber.map(String.init) ?? "",
                iso.string(from: e.createdAt),
                String(format: "%.3f", e.value),
                truth,
                sigma,
                e.confidenceRaw, e.method,
                e.truthRecordedSource ?? "",
                e.timeRecordedSource
            ]
            heights += row.map(Self.csvField).joined(separator: ",") + "\r\n"
        }

        // -- Calculations.csv (per-plot summary) --
        var calcs = "plot_id,plot,trees,ba_per_acre_ft2,tpa,qmd_cm,mean_h_m,total_bf,bf_per_acre,log_rule\r\n"
        for p in plots {
            let plotEntries = entries.filter { $0.plotID == p.id }
            guard !plotEntries.isEmpty else { continue }
            let byTree = Dictionary(grouping: plotEntries) { $0.treeNumber ?? -1 }
            let dbhTrees = byTree.compactMap { (_, group) -> (Double, Double?)? in
                guard let dbh = group.first(where: { $0.kind == .dbh })?.value
                else { return nil }
                let h = group.first(where: { $0.kind == .height })?.value
                return (dbh, h)
            }
            guard !dbhTrees.isEmpty else { continue }
            let acres = max(p.acres ?? 0.1, 0.05)
            let baFt2 = dbhTrees.map { (dbh, _) -> Double in
                let inches = dbh / 2.54
                return 0.005454 * inches * inches
            }.reduce(0, +)
            let baPerAcre = baFt2 / acres
            let tpa = Double(dbhTrees.count) / acres
            let qmd = (dbhTrees.map { $0.0 * $0.0 }.reduce(0, +)
                       / Double(dbhTrees.count)).squareRoot()
            let heightsM = dbhTrees.compactMap { $0.1 }
            let meanH = heightsM.isEmpty
                ? "" : String(format: "%.2f",
                              heightsM.reduce(0, +) / Double(heightsM.count))
            var bfTotal: Double = 0
            for (dbh, hOpt) in dbhTrees {
                guard let h = hOpt,
                      let bf = VolumeConversion.boardFeet(
                          dbhCm: dbh, totalHeightM: h, rule: logRule)
                else { continue }
                bfTotal += bf
            }
            let bfPerAcre = bfTotal > 0
                ? String(format: "%.0f", bfTotal / acres) : ""
            let row = [
                p.id.uuidString,
                p.name,
                String(dbhTrees.count),
                String(format: "%.1f", baPerAcre),
                String(format: "%.1f", tpa),
                String(format: "%.2f", qmd),
                meanH,
                bfTotal > 0 ? String(format: "%.0f", bfTotal) : "",
                bfPerAcre,
                logRule.rawValue
            ].map(Self.csvField).joined(separator: ",")
            calcs += row + "\r\n"
        }

        // -- ZIP it --
        let bom = Data([0xEF, 0xBB, 0xBF])
        func payload(_ s: String) -> Data {
            var d = bom
            d.append(s.data(using: .utf8) ?? Data())
            return d
        }
        let archive = ZipWriter.storedArchive(files: [
            ("Samples.csv",      payload(samples)),
            ("Trees.csv",        payload(trees)),
            ("Stems.csv",        payload(stems)),
            ("Heights.csv",      payload(heights)),
            ("Calculations.csv", payload(calcs))
        ])

        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil, create: true)
        else { return nil }
        let dir = docs.appendingPathComponent("Exports", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let stamp = iso.string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let url = dir.appendingPathComponent("quick-measure-bundle-\(stamp).zip")
        do {
            try archive.write(to: url)
            return url
        } catch {
            return nil
        }
    }

    /// Delete export CSVs older than `maxAge` from the `Exports`
    /// directory. Call once at app launch to stop stale exports from
    /// accumulating forever in user-visible Documents.
    public static func sweepOldExports(olderThan maxAge: TimeInterval
                                        = 7 * 24 * 3600) {
        let fm = FileManager.default
        guard let docs = try? fm.url(for: .documentDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil, create: false)
        else { return }
        let dir = docs.appendingPathComponent("Exports", isDirectory: true)
        guard let contents = try? fm.contentsOfDirectory(
                at: dir,
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles])
        else { return }
        let cutoff = Date().addingTimeInterval(-maxAge)
        for url in contents where url.lastPathComponent.hasPrefix("quick-measure-") {
            let mod = (try? url.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate) ?? Date()
            if mod < cutoff { try? fm.removeItem(at: url) }
        }
    }

    // MARK: Capacity awareness

    private func recomputeCapacityFlag() {
        isNearCapacity = entries.count >= Int(Double(capacity) * 0.95)
    }

    // MARK: Sidecar (JSONL)

    /// Canonical on-disk location for the sidecar. `Application
    /// Support` is preserved by iCloud Backup but hidden from the
    /// user-visible Files app.
    public static func defaultSidecarURL() -> URL? {
        let fm = FileManager.default
        guard let base = try? fm.url(for: .applicationSupportDirectory,
                                     in: .userDomainMask,
                                     appropriateFor: nil, create: true)
        else { return nil }
        let dir = base.appendingPathComponent("Forestix", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("quick-measure.jsonl")
    }

    private func appendToSidecar(_ entry: QuickMeasureEntry) {
        guard let url = sidecarURL else { return }
        let fm = FileManager.default
        if !fm.fileExists(atPath: url.path) {
            // First write establishes the header line.
            let header = "#v \(Self.schemaVersion)\n"
            try? header.data(using: .utf8)?.write(to: url)
        }
        guard let data = try? JSONEncoder().encode(entry),
              let line = (String(data: data, encoding: .utf8) ?? "") + "\n" as String?
        else { return }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        _ = try? handle.seekToEnd()
        try? handle.write(contentsOf: Data(line.utf8))
    }

    /// Full rewrite — used after delete / clearAll. Cheap at our sizes
    /// (< 500 entries × ~160 bytes = 80 kB).
    private func rewriteSidecar() {
        guard let url = sidecarURL else { return }
        var out = "#v \(Self.schemaVersion)\n"
        for e in entries.reversed() {   // oldest-first on disk for debugging
            guard let data = try? JSONEncoder().encode(e),
                  let line = String(data: data, encoding: .utf8)
            else { continue }
            out += line + "\n"
        }
        try? out.data(using: .utf8)?.write(to: url)
    }

    // MARK: Cache (UserDefaults)

    private func persistCache() {
        do {
            let data = try JSONEncoder().encode(entries)
            defaults.set(data, forKey: Keys.entries)
        } catch {
            // Non-fatal — the JSONL sidecar is the durable store.
        }
    }

    // MARK: Loading

    /// Tries the UserDefaults cache first (fast path). If missing or
    /// unreadable, replays the JSONL sidecar. Last resort: empty log.
    ///
    /// SORTED on the way in, whichever source answered. The whole app reads
    /// `entries` as newest-first, and the sidecar is an APPEND log: a reading
    /// whose time was later set by hand sits in the file where it was written,
    /// not where its time belongs, so replaying that file in order would hand
    /// the app a list that quietly breaks the contract. Stable, so readings
    /// sharing an instant keep the order the log recorded them in.
    private static func loadBest(defaults: UserDefaults,
                                  sidecar: URL?) -> [QuickMeasureEntry] {
        if let data = defaults.data(forKey: Keys.entries),
           let decoded = try? JSONDecoder().decode(
                [QuickMeasureEntry].self, from: data) {
            return newestFirst(decoded)
        }
        return newestFirst(loadSidecar(sidecar))
    }

    private static func loadSidecar(_ url: URL?) -> [QuickMeasureEntry] {
        guard let url, let text = try? String(contentsOf: url, encoding: .utf8)
        else { return [] }
        var lines = text.split(separator: "\n", omittingEmptySubsequences: true)
            .map(String.init)
        // Strip the optional schema header.
        if let first = lines.first, first.hasPrefix("#v") {
            lines.removeFirst()
            // Future: parse version and dispatch to a migrator.
        }
        var out: [QuickMeasureEntry] = []
        for line in lines {
            guard let data = line.data(using: .utf8),
                  let entry = try? JSONDecoder().decode(
                      QuickMeasureEntry.self, from: data)
            else { continue }
            out.append(entry)
        }
        // Sidecar is oldest-first; the view expects newest-first.
        return out.reversed()
    }
}
