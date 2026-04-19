# Phase 6 — Export & Report (v0.6-phase6)

**Definition of Done (spec §9.2):** _"All three export formats produce
valid files."_

Phase 6 lands six exporters on top of the Phase 1 plan-only skeleton
(CSV × 5, GeoJSON × 2, Shapefile × 3 zipped, PDF × 1) plus a one-shot
"Export all" path that drops every artefact into a timestamped session
folder and hands the URL to the iOS share sheet.

All **279 tests pass** (`swift test`) — 257 carried over from Phase 5,
plus 22 new tests in `ExportTests/` that cover round-trip parsing,
golden-file byte hashes, and a full dry-run that materialises every
artefact on disk.

---

## 1. What shipped

### Pure Swift exporters (Export/)

| File | Purpose | Notes |
| --- | --- | --- |
| `CSVExporter.swift` (extended) | tree-level, plot-level, stand-summary CSVs | RFC-4180 quoting preserved for newlines, commas, embedded quotes. SI column names (`dbh_cm`, `ba_per_acre_m2`). |
| `GeoJSONExporter.swift` (extended) | full-cruise `FeatureCollection`: strata polygons + planned points + measured plot points | Measured plots carry `positionTier`, `closedAt`, GPS stats; planned plots carry `visited: Bool`. |
| `ShapefileExporter.swift` *(new)* | `.shp + .shx + .dbf + .prj + .cpg` zipped | ESRI spec written from scratch; Point + Polygon only. No external deps. |
| `ZipWriter.swift` *(new)* | stored-method PKZIP | CRC32 + DOS date/time + central directory. Passes `unzip`, macOS Archive Utility, and Python's `zipfile`. |
| `PDFReportBuilder.swift` *(new)* | Core Graphics PDF (no PDFKit author API) | Cross-platform (iOS + macOS test host). 7-page report out of box. |
| `ExportBundle.swift` *(new)* | shared denormalization layer | Reads every repo once, computes `PlotStats` and the three `StandStat`s, shares across all exporters. |
| `FullCruiseExport.swift` *(new)* | one-shot orchestrator | Session folder `Exports/<sanitizedName>_<yyyyMMdd_HHmmss>/`, progress callback, atomic writes. |

### UI wiring

- `ViewModels/ExportViewModel.swift` — Phase 1 buttons preserved; new
  `exportAll()`, `exportPDFReport()`, `exportTreesCSV()`,
  `exportPlotsCSV()`, `exportStandSummaryCSV()`,
  `exportCruiseGeoJSON()`, `exportShapefilePlots()`. Drives a Combine
  `progress: Double` and `progressLabel: String` off the
  `FullCruiseExporter` callback.
- `Screens/ExportScreen.swift` — three sections ("Full cruise export",
  "Individual formats", "Plan exports"), a live progress bar, and a
  "Last export folder" quick-share row. iOS share sheet wrapper carries
  over from Phase 1.

### Tests added (22)

| Suite | Count | Covers |
| --- | --- | --- |
| `TreeLevelCSVTests` | 4 | header + row count, RFC-4180 quoting of notes/damage-codes, deleted-at, SI unit columns |
| `PlotAndStandCSVTests` | 3 | header, bundle-computed per-plot stats, metric × stratum rows |
| `GeoJSONCruiseTests` | 3 | measured-plot points, visited-flag distinction, determinism |
| `ShapefileExporterTests` | 6 | round-trip via in-test parser (SHP header, Point geom, Polygon geom, DBF fields, empty-layer error, zip byte-stability mod DOS timestamp) |
| `PDFReportBuilderTests` | 2 | page count via `CGPDFDocument`, writable-to-disk |
| `GoldenFileTests` | 4 | SHA-256 hash of trees/plots/stand CSV + cruise GeoJSON |
| `FullExportDryRunTest` | 1 | all 11 artefacts present in the session folder, every file ≥ 1 byte |

---

## 2. Sample export (Cascade Demo fixture)

Folder path: `Exports/Cascade-Demo_20231114_221320/` (UTC stamp; project
name sanitized to alphanumerics + dashes).

| Artefact | Size | Purpose |
| --- | --- | --- |
| `trees.csv` | 4,332 B | 15 trees × 34 columns (including one soft-deleted) |
| `plots.csv` | 1,067 B | 3 plots + per-plot TPA/BA/QMD/V |
| `stand-summary.csv` | 591 B | 3 metrics × (TOTAL + 1 stratum) |
| `strata.csv` | 134 B | 2 stratum rows |
| `planned-plots.csv` | 374 B | 4 planned plots (3 visited, 1 skipped) |
| `cruise.geojson` | 5,708 B | Feature collection (2 polygons + 4 planned pts + 3 measured pts) |
| `plan.geojson` | 3,276 B | Phase 1 subset |
| `plots-shp.zip` | 1,417 B | Point shapefile: 3 plot centres (.shp + .shx + .dbf + .prj + .cpg) |
| `planned-shp.zip` | 1,577 B | Point shapefile: 4 planned plots |
| `strata-shp.zip` | 1,497 B | Polygon shapefile: 2 strata |
| `report.pdf` | 45,710 B | 7 pages (cover, stand summary, 3 plot pages, methodology, tree appendix) |

**External validation**

```
$ unzip -l plots-shp.zip
    Length      Date    Time    Name
   ---------  ----      -----   ----
         184  04-19-2026 18:07   plots.shp
         124  04-19-2026 18:07   plots.shx
         466  04-19-2026 18:07   plots.dbf
         145  04-19-2026 18:07   plots.prj
           6  04-19-2026 18:07   plots.cpg
   ---------                     -------
         925                     5 files
```

```
$ head -c 8 report.pdf
%PDF-1.3
$ grep -c '/Type /Page' report.pdf   # 7 expected
7
```

---

## 3. Layout of a stand-summary CSV row (for downstream GIS / R users)

```
metric,stratum_key,stratum_name,n_plots,mean,se_mean,variance,area_acres,weight,ci95_half_width,df_satterthwaite
tpa,TOTAL,(all strata),3,46.6667,3.3333,,1.0000,1.0000,14.3433,2.0000
tpa,__unstratified__,Unstratified,3,46.6667,,33.333333,1.0000,1.0000,,
ba_per_acre_m2,TOTAL,(all strata),3,3.3982,0.1267,,1.0000,1.0000,0.5452,2.0000
...
```

- `TOTAL` rows carry `se_mean`, `ci95_half_width`, and
  `df_satterthwaite` (§7.5).
- Stratum rows carry `variance`, `area_acres`, and `weight`.
- Units on column names: `tpa` = trees/ac, `ba_per_acre_m2` = m²/ac,
  `gross_v_per_acre_m3` = m³/ac.

---

## 4. Why pure-Swift shapefile + CG-direct PDF?

- **Shapefile**: the only SPM-distributed libraries that read ESRI are
  either thin wrappers around GDAL (iOS-unfriendly) or unmaintained.
  The on-disk format for Point + Polygon + a single-disk stored ZIP is
  ~400 lines of well-documented binary — we pay that cost once and get
  a dependency-free, license-clean Export target. The in-test `SHPParser`
  + `DBFParser` + `ZipReader` in `ShapefileExporterTests.swift`
  round-trips the output to prove semantics.
- **PDF**: `UIGraphicsPDFRenderer` is iOS-only and needs a different
  code path on macOS. `CGContext(consumer:mediaBox:)` is Core Graphics
  on both platforms, produces proper conforming PDFs, and reads back
  via `CGPDFDocument` with no platform-specific plumbing. Text layout
  uses CoreText (`CTFramesetter`) rather than NSAttributedString
  extensions so we don't drag UIKit into a leaf module.

---

## 5. Known gaps → deferred

- **GPX track export in the "Export all" path** is still keyed off the
  yet-to-be-written `TrackLogRepository.readNDJSON(_:)` wiring.
  `GPXExporter` itself is unchanged from Phase 4 and works fine for
  callers that can provide waypoints + track points directly.
- **PDF charts** are rendered by simple CG bar-drawing rather than
  Swift Charts `ImageRenderer` snapshots. Works cross-platform and
  keeps the Export module free of SwiftUI; a Phase 7.1 polish pass can
  swap in Swift Charts once the test harness can run on an iOS
  simulator.
- **Swift 6 strict concurrency warnings**: `RepositoryExportDataSource`
  touches `AppEnvironment` (a `@MainActor`-isolated struct) from
  protocol methods that the compiler still infers as nonisolated. The
  code is correct (all calls happen on the main actor in production)
  but emits six "will be an error in Swift 6" warnings. Flagged for the
  Phase-7 concurrency tidy-up along with the rest of the UI layer.

---

## 6. Files touched

**Added (Phase 6)**:

```
TimberCruisingApp/Export/ShapefileExporter.swift
TimberCruisingApp/Export/ZipWriter.swift
TimberCruisingApp/Export/PDFReportBuilder.swift       # was 2-line stub
TimberCruisingApp/Export/ExportBundle.swift
TimberCruisingApp/Export/FullCruiseExport.swift
Tests/ExportTests/ExportFixtures.swift
Tests/ExportTests/TreeLevelCSVTests.swift
Tests/ExportTests/PlotAndStandCSVTests.swift
Tests/ExportTests/GeoJSONCruiseTests.swift
Tests/ExportTests/ShapefileExporterTests.swift
Tests/ExportTests/PDFReportBuilderTests.swift
Tests/ExportTests/GoldenFileTests.swift
Tests/ExportTests/FullExportDryRunTest.swift
phase6_report.md
```

**Modified**:

```
Package.swift                                         # +InventoryEngine dep on ExportTests
TimberCruisingApp/Export/CSVExporter.swift            # tree/plot/stand CSV
TimberCruisingApp/Export/GeoJSONExporter.swift        # cruise(…) entry
TimberCruisingApp/ViewModels/ExportViewModel.swift    # Phase 6 actions
TimberCruisingApp/Screens/ExportScreen.swift          # format picker + progress bar
```

---

## 7. Next

Phase 7 (§9.2): **Field Validation Readiness** — settings,
calibration screens, pre-field checklist, error-recovery polish. The
tide goes out on the concurrency warnings then too.
