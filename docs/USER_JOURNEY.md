# Forestix — User journey walkthrough

This document follows a single cruiser from "first launch" through
"drive home with a finished cruise" so you can see exactly what the app
does, at each step, with what algorithms and what on-screen affordances.
Use it to validate the design matches your field workflow before
investing in a full pilot.

## Part 1 — Before the field (office, Wi-Fi available)

### 1.1 · Install the app

- Plug the iPhone into a Mac with Xcode.
- Open `Forestix.xcodeproj`, select the `Forestix` scheme, pick your
  device, tap ▶.
- Xcode signs the app with your Apple Developer team (the project uses
  `CODE_SIGN_STYLE = Automatic`), installs it, and launches.
- On first launch, iOS asks for Camera, Location, Motion, Microphone,
  and Speech Recognition permission. All are needed for a full cruise;
  Microphone + Speech are only used while you hold the voice-picker
  button.

### 1.2 · Create a project

- **Home screen** → tap **+** (New Project).
- Enter name, owner, and unit system (Imperial / Metric).
- The **Project dashboard** opens:
  - Summary (area, strata, plots)
  - Strata section (empty)
  - Cruise Plan (Design cruise, Plot map)
  - Field work (Go cruise — **locked** until a CruiseDesign exists)
  - Tools (Pre-field check, Export plan, Settings)

### 1.3 · Import the AOI boundary

- Top-right Import menu → GeoJSON or KML.
- Files app → pick the harvest block polygon.
- The app parses the GeoJSON geometry, computes area via spherical
  excess, writes a `Stratum` row.
- **Algorithms:**
  `Geo/GeoJSONImporter.parsePolygon` → `InventoryEngine` not involved;
  area from `Geo/CoordinateConversions.sphericalExcessAreaM2` → acres.

### 1.4 · Design the cruise

- Dashboard → **Design cruise**.
- Choose plot type (fixed-area 0.1 ac or variable-radius BAF 20),
  sampling scheme (systematic grid / stratified random / manual),
  species + volume equations.
- Hit "Generate planned plots" — runs `Geo/SamplingGenerator`:
  - Systematic grid: spacing in meters, random origin inside stratum.
  - Stratified random: N plots uniformly inside each stratum.
  - Manual: draw-to-add on the map.
- The Go cruise row in the dashboard unlocks.

### 1.5 · Download the offline basemap

- Plot map → toolbar → Download tiles.
- `Basemap/OfflineBasemap.downloadAOI(buffer: 1 km, zoom: 12…17)` queues
  `MKTileOverlay` tile fetches with progress reporting.
- All XYZ requests go through the user-supplied provider (no default
  provider ships — see Settings → Basemap tiles).

### 1.6 · Calibrate the phone

- Settings → **Run Calibration**. Two procedures:
  1. **Wall fit** — stand 1.5 m from a flat wall, record 30 depth
     frames. PCA-fit a plane, residual RMS → `depthNoiseMm`, mean →
     `lidarBiasMm`.
  2. **Cylinder fit** — scan a known-diameter cylinder at 5 rolls,
     linear-regress (measured, true) → `dbhCorrectionAlpha`,
     `dbhCorrectionBeta`.
- Values persist to `Project`.

### 1.7 · Pre-field checklist

- Dashboard → **Pre-field check**.
- Seven gates:
  1. LiDAR + AR self-test
  2. GPS (yard must be retried there — yellow here)
  3. Calibration (depthNoiseMm > 0, dbhCorrectionBeta > 0)
  4. Offline basemap (tiles cached)
  5. Species + volume equations
  6. Storage ≥ 500 MB
  7. Battery ≥ 50 %
- All green ⇒ "Ready for field" banner.

### 1.8 · Back up the project

- Settings → **Back up all projects** — dumps a `.tcproj` (stored
  ZIP: `manifest.json` + WAL-checkpointed SQLite + photos + scans).
- Share to iCloud or email for safe keeping.

---

## Part 2 — In the field (under canopy, no cell service)

### 2.1 · Start the cruise

- Home → your project → **Go cruise**.
- The **Cruise flow screen** shows two sections:
  - To do (unvisited planned plots, sorted by number)
  - Already visited (if resuming a later day)
- Tap a row to begin.

### 2.2 · Navigate to the plot

- **NavigationScreen** opens:
  - Big compass arrow (rotates toward the target bearing)
  - Distance readout in meters
  - GPS tier badge top-right (A green → D red)
- **Algorithms:**
  - Bearing: great-circle `atan2` formula in `Geo/CoordinateConversions.bearingDeg`.
  - Distance: haversine in the same file.
  - Heading: `CLLocationManager.trueHeading` if available, else magnetic
    + declination correction.
- Phone buzzes (arrival haptic) when within 5 m.

### 2.3 · Record the plot centre (open sky)

- **PlotCenterScreen** runs a 60 s averaging window:
  - Subscribes to 1 Hz `CLLocation` samples with H-accuracy ≤ 20 m.
  - After 60 s (or when tier A conditions are met earlier): median of
    sample set in local ENU, converts median back to lat/lon, returns
    a `PlotCenterResult{tier: A/B/C/D}`.
- Tier reference (spec §7.3.1):
  - **A** — ≥ 50 accepted samples, median horizontal accuracy ≤ 2 m.
  - **B** — ≥ 30 samples, ≤ 5 m.
  - **C** — any samples, ≤ 10 m.
  - **D** — unreliable; surfaces "Try Offset".

### 2.4 · Under canopy → offset-from-opening

When tier is C/D, the screen shows a big **"Try Offset from opening"** button.

- **OffsetFlowScreen**:
  1. Walk to nearby opening → record a 30 s GPS fix + ARKit world
     pose.
  2. Walk back to plot centre → tap "I'm back" → records the ARKit
     pose delta.
  3. `Positioning/OffsetFromOpening.apply` converts the pose delta to
     a lat/lon offset (ENU → lat/lon linear approximation).
  4. Walk distance > 200 m ⇒ tier D (drift too high).
- Once accepted, the coordinator creates a `Plot` row in Core Data
  (with source = `.vioOffset`) and advances.

### 2.5 · Tally the plot

- **PlotTallyScreen** is the cruise workhorse:
  - Header: plot number, live TPA / BA/ac / QMD / V/ac (REQ-TAL-005:
    updates within 300 ms of every tree add)
  - Tree list: #, species, DBH, H, status, confidence tier color
  - Row swipe: Edit / Soft-delete
  - Footer: big **Add Tree** button (full-width, ≥ 56 pt)
  - Toolbar: **AR Boundary** (visualise the plot ring)
  - Bottom-corner **Close Plot**

### 2.6 · Add a tree (5-step stepper)

Tap **Add Tree** → `AddTreeFlowScreen`:

1. **Species** — quick-tap 3-col grid of recent 5 species (56 pt
   buttons); alphabetical list below. **Voice picker** (Phase 7) —
   hold mic, say "Douglas-fir", release. `SpeciesVoiceMatcher` scores
   the transcript against every configured species; best match selects.
2. **DBH** — number field + method picker (LiDAR / caliper / manual).
   To invoke the DBH scan:
   - Tap **Scan DBH** → opens `DBHScanScreen`.
   - Align the fixed horizontal guide line at 1.37 m (spec §13 TL;DR
     4 — the line never moves; the cruiser moves the phone).
   - 4–6 s capture: algorithm collects depth pixels on the guide row,
     back-projects them to world space, runs RANSAC + Taubin circle
     fit on each frame, weighted-average across frames.
   - Returns `DBHResult{dbhCm, confidence}` with calibration (α, β)
     applied.
3. **Height** (only if subsample rule fires; e.g. every-3rd tree).
   - **Scan Height** → `HeightScanScreen`:
     - Stage 1: walk to tree base, tap **Set anchor** → ARKit world
       anchor recorded.
     - Stage 2: walk back so the whole tree is in frame. Live "Move
       back X m" hint comes from expected height × tangent window.
     - Stage 3: aim at top → capture α_top + pitch.
     - Stage 4: aim at base → capture α_base + pitch.
     - `HeightEstimator.apply`:
       `H = d_h · (tan α_top − tan α_base)`
       where `d_h` is `simd_distance(anchorPose.position, cameraPose.position)`.
     - σ_H propagates σ_dh, σ_pitch, σ_anchor.
4. **Extras** — status (live/dead), crown class, damage codes,
   bearing + distance from plot centre (auto-filled from AR if
   session is live).
5. **Review** — confidence tiers displayed. **Save** writes a new
   `Tree` row. Multi-stem: **Save & add stem** keeps the species and
   placement, re-enters DBH for the next stem.

### 2.7 · Confidence tiers (every measurement)

- Green: passed all sanity checks.
- Yellow: one soft flag (e.g. DBH outside species range).
- Red: at least one error; Save requires an explicit acknowledgement.
- Algorithm: `Common/ConfidenceTier.combineChecks(_:)` per spec §7.9.

### 2.8 · Close the plot

Tap **Close Plot** → `PlotSummaryScreen`:

- Validation run (`InventoryEngine/PlotValidation.run`):
  - Unknown species → error
  - DBH below species min → warning
  - Red-tier trees → warning listing tree #'s
- Plot stats computed (`InventoryEngine/PlotStatsCalculator.compute`):
  - TPA, BA/ac (fixed) or treeFactor × sum (BAF), QMD, V/ac
- Tap **Close plot**:
  - Stamps `closedAt`, `closedBy`
  - Triggers `HDModel.rollingUpdate` — species-wise Näslund re-fit
    with existing + freshly measured (H, D) pairs (async <500 ms).
  - Plays the distinctive two-beat "plot close" haptic.
- The coordinator then pushes `StandSummaryScreen` for roll-up review.

### 2.9 · Stand summary

`StandSummaryScreen`:

- `StandStatsCalculator.compute` — stratified mean (Ȳ = Σ w_h · ȳ_h),
  SE, Satterthwaite df, 95 % CI for TPA / BA / V.
- Per-stratum breakdown table.
- Swift Charts bar charts by stratum + per-plot overlay.

---

## Part 3 — Back at the truck / office (cell reappears)

### 3.1 · Export

- Project → Export → **Export all**. Writes 11 artefacts into
  `Documents/Exports/<name>_<UTC stamp>/`:
  - `trees.csv`, `plots.csv`, `stand-summary.csv`, `strata.csv`, `planned-plots.csv`
  - `cruise.geojson`, `plan.geojson`
  - `plots-shp.zip`, `planned-shp.zip`, `strata-shp.zip`
  - `report.pdf` (cover, stand summary, per-plot pages, methodology, tree appendix)
- Share via the iOS share sheet to Files, iCloud, AirDrop, email.

### 3.2 · Back-up before you leave the truck

- Settings → Back up all projects.
- `.tcproj` = stored ZIP with a WAL-checkpointed SQLite + manifest +
  all `<tree-uuid>.jpg` / `<tree-uuid>.ply`.

### 3.3 · Analytics log (optional)

- Settings → Export analytics log.
- Local-only JSONL with per-scan timings, GPS tier distribution, save
  failures, tracking-limited events. No PII, no network.

---

## End-to-end algorithm map

| Field step | Algorithm | Source file | Spec § |
|---|---|---|---|
| Navigate | Great-circle bearing + haversine | `Geo/CoordinateConversions` | §7.3 |
| GPS averaging | Local-ENU median + tier rules | `Positioning/GPSAveraging` | §7.3.1 |
| Offset method | ARKit world-pose delta | `Positioning/OffsetFromOpening` | §7.3.2 |
| DBH scan | RANSAC + Taubin circle fit | `Sensors/CircleFit/*` | §7.1 |
| Height | `H = d_h·(tan α_top − tan α_base)` | `Sensors/HeightEstimator` | §7.2 |
| AR boundary ring | 72-vertex line strip + ground mesh snap | `AR/PlotBoundaryRenderer` | §7.8 |
| Live plot stats | TPA, BA/ac, QMD, V/ac | `InventoryEngine/PlotStats` | §7.6 |
| Plot validation | Unknown-species / below-min-DBH / red-tier | `InventoryEngine/PlotValidation` | §7.6 |
| Stand summary | Stratified mean + Satterthwaite df + 95 % CI | `InventoryEngine/StandStats` | §7.5 |
| H-D imputation | Näslund fit (Gauss-Newton) | `InventoryEngine/HDModel` | §7.4 |
| Volume | Bruce DF / Chambers-Foltz WH / Schumacher-Hall / TableLookup | `InventoryEngine/VolumeEquations/*` | §7.7 |

---

## Known caveats for the first field pilot

Reiterating from [docs/AUDIT_PHASE_0_TO_7.md](AUDIT_PHASE_0_TO_7.md):

- **DBH / Height scan are not yet inline buttons in AddTreeFlow.** The
  cruiser can reach them via the separate screens (push from
  AddTreeFlow will be wired in Phase 7.1) but today you type the value
  manually after scanning.
- **AR session is not shared across DBH → Height → Boundary** on the
  same plot. Each sub-screen spins up its own session. Correct
  (nothing leaks) but burns extra battery; flagged.
- **Crash-recovery resume prompt** renders nothing yet on Home — the
  `CrashRecoveryService` and tests are in place; the banner lands in
  7.1.
- **Calibration wizard** is one screen (wall + cylinder) rather than a
  guided two-step flow. Cruiser training can compensate.

None of these block the 20-plot field pilot defined in
[docs/FIELD_PILOT.md](FIELD_PILOT.md).
