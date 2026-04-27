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
    /// support per SilvaCruise. Empty string treated as nil.
    public var unitName: String
    /// Plot acreage. nil = unknown / unset.
    public var acres: Double?
    /// Plot type — fixed-radius / variable / tally / measure.
    public var typeRaw: String
    /// BAF for variable-radius (ft²/ac) — ignored for other types.
    public var baf: Double?
    /// Plot-radius in feet for fixed-radius plots.
    public var radiusFt: Double?
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
                createdAt: Date = Date(),
                isDefault: Bool = false) {
        self.id = id
        self.name = name
        self.unitName = unitName
        self.acres = acres
        self.typeRaw = typeRaw
        self.baf = baf
        self.radiusFt = radiusFt
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
        self.createdAt = try c.decode(Date.self,   forKey: .createdAt)
        self.isDefault = (try? c.decode(Bool.self, forKey: .isDefault)) ?? false
    }
}

public struct QuickMeasureEntry: Codable, Identifiable, Sendable, Equatable {

    public enum Kind: String, Codable, Sendable {
        case dbh
        case height
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
    public let sigma: Double?
    public let confidenceRaw: String
    public let method: String
    public let createdAt: Date
    public let treeNumber: Int?

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

    public init(
        id: UUID = UUID(),
        kind: Kind,
        value: Double,
        sigma: Double?,
        confidenceRaw: String,
        method: String,
        createdAt: Date = Date(),
        treeNumber: Int? = nil,
        plotID: UUID? = nil,
        speciesCode: String? = nil,
        position: StemPosition? = nil,
        damageCodes: [String] = [],
        note: String? = nil
    ) {
        self.id = id
        self.kind = kind
        self.value = value
        self.sigma = sigma
        self.confidenceRaw = confidenceRaw
        self.method = method
        self.createdAt = createdAt
        self.treeNumber = treeNumber
        self.plotID = plotID
        self.speciesCode = speciesCode
        self.position = position
        self.damageCodes = damageCodes
        self.note = note
    }

    // Custom decoding so entries written before any new field existed
    // still parse cleanly — the schema-version header lets us add
    // fields like this without forcing a destructive migration.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id            = try c.decode(UUID.self,   forKey: .id)
        self.kind          = try c.decode(Kind.self,   forKey: .kind)
        self.value         = try c.decode(Double.self, forKey: .value)
        self.sigma         = try c.decodeIfPresent(Double.self, forKey: .sigma)
        self.confidenceRaw = try c.decode(String.self, forKey: .confidenceRaw)
        self.method        = try c.decode(String.self, forKey: .method)
        self.createdAt     = try c.decode(Date.self,   forKey: .createdAt)
        self.treeNumber    = try c.decodeIfPresent(Int.self,   forKey: .treeNumber)
        self.plotID        = try c.decodeIfPresent(UUID.self,  forKey: .plotID)
        self.speciesCode   = try c.decodeIfPresent(String.self, forKey: .speciesCode)
        self.position      = try c.decodeIfPresent(StemPosition.self, forKey: .position)
        self.damageCodes   = (try? c.decode([String].self, forKey: .damageCodes)) ?? []
        self.note          = try c.decodeIfPresent(String.self, forKey: .note)
    }

    /// Unit string for `value`. `cm` for diameter, `m` for height.
    public var valueUnit: String {
        switch kind {
        case .dbh:    return "cm"
        case .height: return "m"
        }
    }

    /// Unit string for `sigma`. `mm` for diameter (millimetre-scale
    /// RANSAC RMSE) and `m` for height (metres of combined geometric
    /// uncertainty).
    public var sigmaUnit: String {
        switch kind {
        case .dbh:    return "mm"
        case .height: return "m"
        }
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
    /// is also persisted alongside.
    public static let schemaVersion: Int = 2

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

    public func delete(id: UUID) {
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
    @discardableResult
    public func createPlot(name: String,
                            unitName: String = "",
                            acres: Double? = nil,
                            typeRaw: String = "fixed",
                            baf: Double? = nil,
                            radiusFt: Double? = nil) -> QuickMeasurePlot {
        let plot = QuickMeasurePlot(
            name: name, unitName: unitName, acres: acres,
            typeRaw: typeRaw, baf: baf, radiusFt: radiusFt,
            createdAt: Date(), isDefault: false)
        plots.insert(plot, at: 0)
        activePlotID = plot.id
        persistPlots()
        return plot
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
            guard entry.plotID == id else { return entry }
            return QuickMeasureEntry(
                id: entry.id, kind: entry.kind, value: entry.value,
                sigma: entry.sigma, confidenceRaw: entry.confidenceRaw,
                method: entry.method, createdAt: entry.createdAt,
                treeNumber: entry.treeNumber,
                plotID: defaultID,
                speciesCode: entry.speciesCode, position: entry.position,
                damageCodes: entry.damageCodes, note: entry.note)
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
                return QuickMeasureEntry(
                    id: entry.id, kind: entry.kind, value: entry.value,
                    sigma: entry.sigma, confidenceRaw: entry.confidenceRaw,
                    method: entry.method, createdAt: entry.createdAt,
                    treeNumber: entry.treeNumber,
                    plotID: defaultID,
                    speciesCode: entry.speciesCode, position: entry.position,
                    damageCodes: entry.damageCodes, note: entry.note)
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

    /// Returns a brief description of an existing tree's measurements
    /// (e.g. "DIA 34.5 cm · HGT 28 m") for the picker UI. Returns
    /// `nil` if the log has nothing for that tree number.
    public func summary(forTreeNumber n: Int) -> String? {
        let owned = entries.filter { $0.treeNumber == n }
        guard !owned.isEmpty else { return nil }
        let dbh = owned.first { $0.kind == .dbh }
        let hgt = owned.first { $0.kind == .height }
        var parts: [String] = []
        if let d = dbh {
            parts.append(String(format: "DIA %.1f cm", d.value))
        }
        if let h = hgt {
            parts.append(String(format: "HGT %.1f m", h.value))
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

        let headers = ["id", "timestamp", "plot", "tree", "kind",
                       "value", "value_unit", "sigma", "sigma_unit",
                       "species", "position", "damage", "note",
                       "confidence", "method"]
        var out = headers.map(Self.csvField).joined(separator: ",")
        out += "\r\n"

        for e in entries {
            let sigma = e.sigma.map { String(format: "%.3f", $0) } ?? ""
            let plotName = e.plotID
                .flatMap { id in plots.first { $0.id == id } }
                .map(\.name) ?? ""
            let row = [
                e.id.uuidString,
                iso.string(from: e.createdAt),
                plotName,
                e.treeNumber.map(String.init) ?? "",
                e.kind.rawValue,
                String(format: "%.3f", e.value),
                e.valueUnit,
                sigma,
                e.sigma == nil ? "" : e.sigmaUnit,
                e.speciesCode ?? "",
                e.position?.rawValue ?? "",
                e.damageCodes.joined(separator: "|"),
                e.note ?? "",
                e.confidenceRaw,
                e.method
            ].map(Self.csvField).joined(separator: ",")
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

    // MARK: - Multi-table CSV bundle (Arboreal-style 5-file export)

    /// Writes a ZIP bundle containing five CSV files modelled on the
    /// Arboreal Forest export schema:
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
        var trees = "plot_id,plot,tree_number,species,damage,note\r\n"
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
            let row = [
                any.plotID?.uuidString ?? "",
                plotName,
                any.treeNumber.map(String.init) ?? "",
                species, dmg, note
            ].map(Self.csvField).joined(separator: ",")
            trees += row + "\r\n"
        }

        // -- Stems.csv (one per DBH measurement) --
        var stems = "id,plot_id,tree_number,timestamp,dbh_cm,sigma_mm,position,confidence,method\r\n"
        for e in entries where e.kind == .dbh {
            let row = [
                e.id.uuidString,
                e.plotID?.uuidString ?? "",
                e.treeNumber.map(String.init) ?? "",
                iso.string(from: e.createdAt),
                String(format: "%.3f", e.value),
                e.sigma.map { String(format: "%.3f", $0) } ?? "",
                e.position?.rawValue ?? "",
                e.confidenceRaw, e.method
            ].map(Self.csvField).joined(separator: ",")
            stems += row + "\r\n"
        }

        // -- Heights.csv (one per Height measurement) --
        var heights = "id,plot_id,tree_number,timestamp,height_m,sigma_m,confidence,method\r\n"
        for e in entries where e.kind == .height {
            let row = [
                e.id.uuidString,
                e.plotID?.uuidString ?? "",
                e.treeNumber.map(String.init) ?? "",
                iso.string(from: e.createdAt),
                String(format: "%.3f", e.value),
                e.sigma.map { String(format: "%.3f", $0) } ?? "",
                e.confidenceRaw, e.method
            ].map(Self.csvField).joined(separator: ",")
            heights += row + "\r\n"
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
    private static func loadBest(defaults: UserDefaults,
                                  sidecar: URL?) -> [QuickMeasureEntry] {
        if let data = defaults.data(forKey: Keys.entries),
           let decoded = try? JSONDecoder().decode(
                [QuickMeasureEntry].self, from: data) {
            return decoded
        }
        return loadSidecar(sidecar)
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
