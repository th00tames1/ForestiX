// Spec §8 Export/CSVExporter. Phase 1 scope only exports plan data:
//   • stratum-list.csv   — one row per Stratum (name + area_acres)
//   • planned-plots.csv  — one row per PlannedPlot (plot #, lat, lon, stratum)
//
// Phase 6 will add tree-level and plot-level exports once field-capture is
// implemented. All rows use RFC 4180 quoting: any field containing comma,
// quote, or newline is wrapped in double quotes with `"` escaped as `""`.

import Foundation
import Models

public enum CSVExporter {

    // MARK: - Stratum list

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

    // MARK: - Planned plots

    public static func plannedPlotsCSV(
        plannedPlots: [PlannedPlot],
        strata: [Stratum] = []
    ) -> String {
        let byId: [UUID: Stratum] = Dictionary(uniqueKeysWithValues: strata.map { ($0.id, $0) })
        var lines: [String] = [
            "plot_number,stratum_id,stratum_name,planned_lat,planned_lon,visited"
        ]
        // Sort for deterministic file content — CSV diffs should be stable.
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

    // MARK: - RFC 4180 helpers

    static func quote(_ s: String) -> String {
        if s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    static func format(_ v: Double, places: Int) -> String {
        // Avoid locale-sensitive formatting; CSV fields must be dot-decimal.
        String(format: "%.\(places)f", v)
    }

    static func join(_ lines: [String]) -> String {
        // RFC 4180 specifies CRLF terminators.
        lines.joined(separator: "\r\n") + "\r\n"
    }
}
