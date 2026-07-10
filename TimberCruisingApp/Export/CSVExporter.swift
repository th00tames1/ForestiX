// Spec §8 Export/CSVExporter. Phase 1 shipped plan-only exports; Phase 6
// extends the enum with tree-level, plot-level, and stand-summary CSVs.
//
// All rows use RFC 4180 quoting: any field containing comma, quote, or
// newline is wrapped in double quotes with `"` escaped as `""`. Lines use
// CRLF terminators. Floats are locale-independent (dot-decimal, %f).
//
// Column names include SI units where applicable ("_cm", "_m", "_deg",
// "_m2_per_acre", etc.) so downstream consumers don't have to guess.
// Per spec: volumes are always m³, lengths m, diameters cm — the Project
// unit system does **not** translate CSV column units (PDF does). CSV is
// the raw machine-readable dump and intentionally single-unit.

import Foundation
import Models
import InventoryEngine

public enum CSVExporter {

    // MARK: - Stratum list (Phase 1)

    public static func stratumListCSV(strata: [Stratum]) -> String {
        var lines: [String] = ["stratum_id,name,area_acres"]
        for s in strata {
            lines.append([
                quote(s.id.uuidString),
                quote(s.name),
                format(Double(s.areaAcres), places: 4)
            ].joined(separator: ","))
        }
        return join(lines)
    }

    // MARK: - Planned plots (Phase 1)

    public static func plannedPlotsCSV(
        plannedPlots: [PlannedPlot],
        strata: [Stratum] = []
    ) -> String {
        let byId: [UUID: Stratum] = Dictionary(uniqueKeysWithValues: strata.map { ($0.id, $0) })
        var lines: [String] = [
            "plot_number,stratum_id,stratum_name,planned_lat,planned_lon,visited"
        ]
        let sorted = plannedPlots.sorted { $0.plotNumber < $1.plotNumber }
        for p in sorted {
            let stratumName = p.stratumId.flatMap { byId[$0]?.name } ?? ""
            lines.append([
                "\(p.plotNumber)",
                quote(p.stratumId?.uuidString ?? ""),
                quote(stratumName),
                format(p.plannedLat, places: 7),
                format(p.plannedLon, places: 7),
                p.visited ? "true" : "false"
            ].joined(separator: ","))
        }
        return join(lines)
    }

    // MARK: - Tree-level CSV (Phase 6)

    /// Emit one row per Tree, in `treeNumber` order, including soft-deleted
    /// rows (with `deleted_at` populated) so the CSV is a lossless dump.
    public static func treesCSV(trees: [Tree]) -> String {
        let header = [
            "id", "plot_id", "tree_number", "species_code", "status",
            "dbh_cm", "dbh_method",
            "dbh_sigma_mm", "dbh_rmse_mm", "dbh_coverage_deg",
            "dbh_n_inliers", "dbh_confidence", "dbh_is_irregular",
            "height_m", "height_method", "height_source",
            "height_sigma_m", "height_dh_m",
            "height_alpha_top_deg", "height_alpha_base_deg", "height_confidence",
            "bearing_from_center_deg", "distance_from_center_m", "boundary_call",
            "crown_class", "damage_codes",
            "is_multistem", "parent_tree_id",
            "notes", "photo_path", "raw_scan_path",
            "created_at", "updated_at", "deleted_at"
        ].joined(separator: ",")
        var lines: [String] = [header]
        let sorted = trees.sorted {
            if $0.plotId != $1.plotId {
                return $0.plotId.uuidString < $1.plotId.uuidString
            }
            return $0.treeNumber < $1.treeNumber
        }
        for t in sorted {
            var cells: [String] = []
            cells.append(quote(t.id.uuidString))
            cells.append(quote(t.plotId.uuidString))
            cells.append("\(t.treeNumber)")
            cells.append(quote(t.speciesCode))
            cells.append(quote(String(describing: t.status)))
            cells.append(format(Double(t.dbhCm), places: 2))
            cells.append(quote(String(describing: t.dbhMethod)))
            cells.append(optional(t.dbhSigmaMm, places: 2))
            cells.append(optional(t.dbhRmseMm, places: 2))
            cells.append(optional(t.dbhCoverageDeg, places: 2))
            cells.append(t.dbhNInliers.map(String.init) ?? "")
            cells.append(quote(String(describing: t.dbhConfidence)))
            cells.append(t.dbhIsIrregular ? "true" : "false")
            cells.append(optional(t.heightM, places: 2))
            cells.append(t.heightMethod.map { quote(String(describing: $0)) } ?? "")
            cells.append(quote(t.heightSource ?? ""))
            cells.append(optional(t.heightSigmaM, places: 2))
            cells.append(optional(t.heightDHM, places: 2))
            cells.append(optional(t.heightAlphaTopDeg, places: 2))
            cells.append(optional(t.heightAlphaBaseDeg, places: 2))
            cells.append(t.heightConfidence.map { quote(String(describing: $0)) } ?? "")
            cells.append(optional(t.bearingFromCenterDeg, places: 2))
            cells.append(optional(t.distanceFromCenterM, places: 2))
            cells.append(quote(t.boundaryCall ?? ""))
            cells.append(quote(t.crownClass ?? ""))
            cells.append(quote(t.damageCodes.joined(separator: ";")))
            cells.append(t.isMultistem ? "true" : "false")
            cells.append(quote(t.parentTreeId?.uuidString ?? ""))
            cells.append(quote(t.notes))
            cells.append(quote(t.photoPath ?? ""))
            cells.append(quote(t.rawScanPath ?? ""))
            cells.append(iso8601(t.createdAt))
            cells.append(iso8601(t.updatedAt))
            cells.append(t.deletedAt.map(iso8601) ?? "")
            lines.append(cells.joined(separator: ","))
        }
        return join(lines)
    }

    // MARK: - Plot-level CSV (Phase 6)

    /// Per-plot position + aggregate row. `statsByPlot` supplies the already-
    /// computed PlotStats (tallies exclude soft-deleted trees); pass an empty
    /// dict to emit position-only rows.
    public static func plotsCSV(
        plots: [Plot],
        statsByPlot: [UUID: PlotStats] = [:]
    ) -> String {
        let header = [
            "plot_id", "plot_number", "project_id", "planned_plot_id",
            "center_lat", "center_lon",
            "position_source", "position_tier",
            "gps_n_samples", "gps_median_h_accuracy_m", "gps_sample_std_xy_m",
            "offset_walk_m",
            "slope_deg", "aspect_deg", "plot_area_acres",
            "n_trees_live",
            "tpa", "ba_per_acre_m2", "qmd_cm",
            "gross_v_per_acre_m3", "merch_v_per_acre_m3",
            "started_at", "closed_at", "closed_by", "notes"
        ].joined(separator: ",")
        var lines: [String] = [header]
        let sorted = plots.sorted { $0.plotNumber < $1.plotNumber }
        for p in sorted {
            let s = statsByPlot[p.id]
            var cells: [String] = []
            cells.append(quote(p.id.uuidString))
            cells.append("\(p.plotNumber)")
            cells.append(quote(p.projectId.uuidString))
            cells.append(quote(p.plannedPlotId?.uuidString ?? ""))
            cells.append(format(p.centerLat, places: 7))
            cells.append(format(p.centerLon, places: 7))
            cells.append(quote(String(describing: p.positionSource)))
            cells.append(quote(String(describing: p.positionTier)))
            cells.append("\(p.gpsNSamples)")
            cells.append(format(Double(p.gpsMedianHAccuracyM), places: 3))
            cells.append(format(Double(p.gpsSampleStdXyM), places: 3))
            cells.append(optional(p.offsetWalkM, places: 2))
            cells.append(format(Double(p.slopeDeg), places: 2))
            cells.append(format(Double(p.aspectDeg), places: 2))
            cells.append(format(Double(p.plotAreaAcres), places: 4))
            cells.append(s.map { "\($0.liveTreeCount)" } ?? "")
            cells.append(s.map { format(Double($0.tpa), places: 2) } ?? "")
            cells.append(s.map { format(Double($0.baPerAcreM2), places: 4) } ?? "")
            cells.append(s.map { format(Double($0.qmdCm), places: 2) } ?? "")
            cells.append(s.map { format(Double($0.grossVolumePerAcreM3), places: 4) } ?? "")
            cells.append(s.map { format(Double($0.merchVolumePerAcreM3), places: 4) } ?? "")
            cells.append(iso8601(p.startedAt))
            cells.append(p.closedAt.map(iso8601) ?? "")
            cells.append(quote(p.closedBy ?? ""))
            cells.append(quote(p.notes))
            lines.append(cells.joined(separator: ","))
        }
        return join(lines)
    }

    // MARK: - Stand summary CSV (Phase 6)

    /// Three metrics (TPA, BA, Volume), one total row + one row per stratum.
    /// Layout: `metric,stratum_key,stratum_name,n_plots,mean,se_mean,
    /// variance,area_acres,weight,ci95_half_width,df_satterthwaite`.
    public static func standSummaryCSV(
        tpa: StandStat,
        ba: StandStat,
        volume: StandStat,
        stratumNamesByKey: [String: String] = [:]
    ) -> String {
        let header = [
            "metric", "stratum_key", "stratum_name",
            "n_plots", "mean", "se_mean", "variance",
            "area_acres", "weight",
            "ci95_half_width", "df_satterthwaite"
        ].joined(separator: ",")
        var lines: [String] = [header]

        func emit(_ metric: String, stat: StandStat) {
            // Total row.
            lines.append([
                quote(metric),
                quote("TOTAL"),
                quote("(all strata)"),
                "\(stat.nPlots)",
                format(stat.mean, places: 4),
                format(stat.seMean, places: 4),
                "",
                format(stat.byStratum.values.reduce(0) { $0 + $1.areaAcres },
                       places: 4),
                "1.0000",
                format(stat.ci95HalfWidth, places: 4),
                format(stat.dfSatterthwaite, places: 4)
            ].joined(separator: ","))
            let totalArea = stat.byStratum.values.reduce(0) { $0 + $1.areaAcres }
            let sortedKeys = stat.byStratum.keys.sorted()
            for key in sortedKeys {
                guard let ss = stat.byStratum[key] else { continue }
                let weight = totalArea > 0 ? ss.areaAcres / totalArea : 0
                lines.append([
                    quote(metric),
                    quote(key),
                    quote(stratumNamesByKey[key] ?? key),
                    "\(ss.nPlots)",
                    format(ss.mean, places: 4),
                    "",
                    format(ss.variance, places: 6),
                    format(ss.areaAcres, places: 4),
                    format(weight, places: 4),
                    "",
                    ""
                ].joined(separator: ","))
            }
        }
        emit("tpa", stat: tpa)
        emit("ba_per_acre_m2", stat: ba)
        emit("gross_v_per_acre_m3", stat: volume)
        return join(lines)
    }

    // MARK: - RFC 4180 helpers

    static func quote(_ s: String) -> String {
        if s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    static func format(_ v: Double, places: Int) -> String {
        String(format: "%.\(places)f", v)
    }

    static func optional(_ v: Float?, places: Int) -> String {
        v.map { format(Double($0), places: places) } ?? ""
    }

    static func join(_ lines: [String]) -> String {
        lines.joined(separator: "\r\n") + "\r\n"
    }

    private static let iso8601Formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    static func iso8601(_ d: Date) -> String { iso8601Formatter.string(from: d) }

    /// UTF-8 BOM prefix — consumers like Excel on Windows need this to
    /// recognise non-ASCII content. Call sites decide whether to prepend.
    public static let utf8BOM: Data = Data([0xEF, 0xBB, 0xBF])
}
