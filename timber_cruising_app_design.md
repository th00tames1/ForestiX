# Smartphone Timber Cruising App — Development Spec v0.3

**Purpose of this document**
This is an implementation spec. It is written to be consumed by an LLM coding agent and by human developers. Each requirement has a stable ID (e.g., `REQ-DBH-003`) so that implementation tasks can be assigned and referenced. Each algorithm is specified as a typed contract (Input / Output / Invariants / Failure Modes) so that implementation follows the same shape as tests.

**Target platform**: iOS 17+, iPhone Pro / iPad Pro with LiDAR. Swift 5.9+, SwiftUI primary with UIKit where ARKit needs it.

**Language**: Spec is English-first so code-generation models get clean tokens. Comments and user-facing strings may be localized later.

---

## Table of Contents

- [§1 Glossary](#1-glossary)
- [§2 Product Overview](#2-product-overview)
- [§3 Functional Requirements](#3-functional-requirements)
- [§4 User Workflows](#4-user-workflows)
- [§5 Screens](#5-screens)
- [§6 Data Model (Swift Types + Core Data)](#6-data-model)
- [§7 Core Algorithms](#7-core-algorithms)
- [§8 Module & File Layout](#8-module--file-layout)
- [§9 Implementation DAG and Phases](#9-implementation-dag-and-phases)
- [§10 Testing Strategy](#10-testing-strategy)
- [§11 Non-Functional Requirements](#11-nfr)
- [§12 Open Questions (deferred to post-implementation)](#12-open-questions)

---

## §1 Glossary

Understand these before reading requirements.

| Term | Definition |
|------|------------|
| **Cruise** | Field inventory of a forest stand: walking plots and measuring trees to estimate per-acre stand statistics. |
| **Plot** | A small sample area (fixed-area or variable-radius) where every qualifying tree is measured. |
| **Fixed-area plot** | A circular plot with a fixed radius (e.g., 1/10 acre = radius 11.35 m). Every tree inside is measured. |
| **Variable-radius plot (BAF plot / prism plot)** | Sampling scheme where each tree's inclusion depends on its DBH × a Basal Area Factor. |
| **DBH** | Diameter at breast height. In the US: diameter of the stem at 4.5 ft (1.37 m) above ground on the uphill side. This app uses 1.37 m as default (configurable). |
| **BA** | Basal area = π · DBH² / 4. The cross-sectional area of a stem at DBH. |
| **BA/ac** | Basal area per acre, summed across plot trees × expansion factor. |
| **TPA** | Trees per acre. |
| **QMD** | Quadratic mean diameter = √(Σ DBH² / n). |
| **Volume equation** | Species-specific function producing stem volume from DBH and (optionally) height. |
| **Expansion factor (EF)** | For fixed-area plots, EF = 1 / plot_area_acres. Multiplies per-tree counts/values to per-acre quantities. |
| **BAF** | Basal Area Factor, used in variable-radius sampling. Each "in" tree contributes exactly BAF ft²/ac of basal area regardless of size. |
| **Stratum** | A sub-area of the cruise project treated as homogeneous for sampling. |
| **H–D model** | Height–Diameter model, used to impute heights for trees where only DBH was measured. |
| **VIO** | Visual-Inertial Odometry. ARKit's continuous world-frame tracking of device position. |
| **ARKit world frame** | Gravity-aligned 3D frame established at ARSession start. Y is up. If `worldAlignment = .gravityAndHeading`, Z is approximately south. |
| **Confidence tier** | `green` / `yellow` / `red`. Attached to every measurement. See [§7.9](#79-confidence-framework). |
| **Guide line (DBH)** | A fixed, semi-transparent horizontal gray line drawn at the vertical center of the DBH scan camera view. The cruiser physically moves the phone so this line visually overlaps the DBH point on the trunk. The line does not move with the camera; it is an image-space UI element at a fixed pixel row. See [REQ-DBH-002](#reqs-dbh) and [§7.1](#71-dbh-guide-align-partial-arc-circle-fit). |

---

## §2 Product Overview

### §2.1 One-line summary

An iOS field-inventory app that replaces calipers, tape, and clinometer for forestry timber cruising. The cruiser visits plots, measures each tree's DBH with LiDAR (guide-align circle fit), measures heights on a subsample (VIO walk-off tangent), and the app computes plot and stand statistics offline.

### §2.2 Primary user journey

1. Office: cruiser creates a Project with stratum polygons, plot design, species list, and sampling grid.
2. Field: navigates to each plot center using GPS (with offline basemap).
3. Confirms plot center; the phone's AR view renders the plot boundary.
4. For each in-plot tree: picks species, scans DBH, (optionally) measures height, saves.
5. Closes the plot; app shows plot-level stats (TPA, BA/ac, QMD, V/ac).
6. After all plots: app shows stand-level stats with sampling error; cruiser exports CSV / GeoJSON / PDF.

### §2.3 Design principles

1. **Offline-first.** No field operation may require network.
2. **Trust the cruiser's judgment.** The app does not auto-detect the DBH height, species, or borderline in/out calls. It records sensor evidence and does math. (DBH example: the cruiser physically aligns a screen guide line to the DBH point on the trunk; the app does not try to find 1.37 m above ground.)
3. **Every measurement carries uncertainty and a confidence tier.** Cruiser can decide whether to re-measure.
4. **Fail-soft.** Any sensor failure → manual fallback with clear UI.
5. **Gloved hands.** 44×44 pt minimum tap targets, 56×56 pt preferred. Single-hand primary actions. Haptic on all state transitions.

---

## §3 Functional Requirements

Each requirement has an ID. Implement and test one-by-one.

### §3.1 Project & Design (REQ-PRJ-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-PRJ-001 | Create, list, open, archive, and delete Projects. | CRUD persists across app restarts. |
| REQ-PRJ-002 | Import stratum boundaries as GeoJSON or KML. Each stratum has name and area. | Parsing works for WGS84 polygons; area auto-computed if not present. |
| REQ-PRJ-003 | Configure cruise design: plot type (fixed-area / variable-radius), plot size or BAF, sampling scheme. | Settings saved per Project. |
| REQ-PRJ-004 | Generate planned plots: systematic grid (with spacing and random offset), stratified random (n per stratum), or manual list. | Planned plots appear on project map. |
| REQ-PRJ-005 | Configure species list with per-species volume equation. Provide PNW defaults (DF, WH, RC, RA). | Cruiser can add custom species. |
| REQ-PRJ-006 | Pre-download basemap tiles (zoom 12–17) for the project AOI plus 1 km buffer. | Offline map functions in Airplane Mode. |

### §3.2 Navigation (REQ-NAV-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-NAV-001 | Show current location on map with all planned plots. | Indicates visited vs remaining. |
| REQ-NAV-002 | Navigate to the next unvisited plot with compass arrow and live distance. | Haptic pulse when within 5 m. |
| REQ-NAV-003 | Display live GPS quality tier (A/B/C/D) based on `horizontalAccuracy`. See [§7.3](#73-gps-under-canopy). | Tier color on screen at all times. |
| REQ-NAV-004 | Record a track log (GPX exportable). | Turn on/off per cruise session. |

### §3.3 Plot Center (REQ-CTR-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-CTR-001 | GPS Averaging mode: collect ≥ 30 samples at 1 Hz with `horizontalAccuracy` ≤ 20 m, take median of x/y. | Accept when sample_std_xy < 5 m AND median accuracy < 10 m. |
| REQ-CTR-002 | Offset-from-Opening mode (VIO): capture GPS fix at an opening, walk back to plot under continuous ARKit tracking, subtract displacement. See [§7.3.2](#732-offset-from-opening). | Valid only if walk distance < 200 m AND tracking state remains `.normal`. |
| REQ-CTR-003 | VIO Chain mode: keep ARKit session alive plot-to-plot (< 200 m between), record each center in a shared frame; opportunistic GPS fixes tighten drift. | Optional, v0.4+. |
| REQ-CTR-004 | External RTK mode: accept NMEA/LLH stream from a Bluetooth RTK receiver. | Optional, v1.5+. |
| REQ-CTR-005 | Save position source and tier on every Plot record. | Tier A/B/C/D visible and queryable. |

### §3.4 Plot Boundary Visualization (REQ-BND-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-BND-001 | For fixed-area plot, render a 72-vertex ring on the ARKit ground mesh centered at the plot center anchor. | Ring visible through trees; stays anchored when user walks. |
| REQ-BND-002 | Slope-correct ring vertices by projecting each vertex to the nearest point on the LiDAR ground mesh. | Correction toggleable in Settings. |
| REQ-BND-003 | For variable-radius plot, per-stem limiting distance is computed as a function of DBH and BAF; a stem highlights green (in) / red (out) / yellow (borderline within ±0.2 m of limit). | Computed live when the stem is in view. |
| REQ-BND-004 | Warn if user walked > 15 m from center (drift risk). | Visible on AR view. |

### §3.5 DBH Scan (REQ-DBH-XXX) <a name="reqs-dbh"></a>

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-DBH-001 | Tap "Scan DBH" on the Add Tree screen opens an AR camera view using LiDAR depth. | Depth map streams at ≥ 15 Hz. |
| REQ-DBH-002 | Render a **fixed, semi-transparent horizontal gray line at the vertical center of the camera view** (y_guide = screen_height / 2). The line does not track anything; it stays at the same pixel row. The cruiser physically moves the phone so this line overlaps the DBH point on the trunk. The line is 1.5 pt thick, opacity 0.5, color RGB(128,128,128), extends full screen width. | Line visible in bright sun and low light. |
| REQ-DBH-003 | Render a center crosshair at the image center indicating where the cruiser should tap the trunk center. | Crosshair color changes from red → green when the pixel under it has a stable, near-range (< 3 m) depth reading. |
| REQ-DBH-004 | On tap at the trunk center, capture a burst of 10–15 depth frames over 0.5–1.0 s, execute the circle-fit pipeline, and show the result. See [§7.1](#71-dbh-guide-align-partial-arc-circle-fit). | P90 latency: tap → result shown < 3 s. |
| REQ-DBH-005 | Display: DBH value in cm (1 decimal), ±σ_r in mm, arc coverage in °, confidence tier color. | All four values visible. |
| REQ-DBH-006 | Offer actions: Accept / Re-scan / Dual-view (add second scan from opposite side and refit) / Manual entry. | All actions reachable with a single tap. |
| REQ-DBH-007 | Save the raw point set (.ply) for the measurement if a per-project opt-in is enabled. | File written to sandboxed documents directory. |
| REQ-DBH-008 | For "irregular" stems (fluted, buttressed, forked), the cruiser can flag the tree; LiDAR measurement is stored with method `lidar_irregular` and the cruiser is prompted to also enter a caliper/d-tape value. | Flag persists and is visible in Tree detail. |
| REQ-DBH-009 | Reject a scan (red tier) if any of: `n_inliers < 20`, `arc_coverage < 45°`, `r_fit < 2.5 cm` or `r_fit > 100 cm`, `rmse/r > 5%`, `frame_burst_radius_CoV > 10%`. Show the user *why* it was rejected. | All reject reasons are human-readable strings. |

### §3.6 Height Measurement (REQ-HGT-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-HGT-001 | Height measurement uses the VIO walk-off tangent method described in [§7.2](#72-height-vio-walk-off-tangent). | Formula correct per unit tests. |
| REQ-HGT-002 | Flow: (a) cruiser touches phone to tree base and taps "Anchor Here"; (b) walks back; (c) aims at top, taps; (d) aims at base, taps; (e) app shows H ± σ_H. | State machine enforces order. |
| REQ-HGT-003 | Live HUD shows current `d_h` and a "move back/forward X m" hint optimized for geometry (target 0.6·H_expected ≤ d_h ≤ 1.0·H_expected). | Hint updates at ≥ 5 Hz. |
| REQ-HGT-004 | On each aim-tap, angle α is taken as the median of CMDeviceMotion pitch samples over ±200 ms around the tap. | Sample count logged. |
| REQ-HGT-005 | Reject (red) if: `H < 1.5 m`, `H > 100 m`, `|α_top| > 85°`, `d_h < 3 m`, or ARKit tracking state is `.limited` at any point during the measurement. | Reject reason shown. |
| REQ-HGT-006 | Fallback: manual tape distance entry + angle-only tap. Record `method = tape_tangent`. | Selectable at step (b). |
| REQ-HGT-007 | Subsample rule per Project: measure height on every k-th tree, OR on ≥ n per species × DBH-class. Non-measured trees get heights from the H–D model. See [§7.4](#74-hd-height-diameter-model). | Rule configurable. |

### §3.7 Tree Tally (REQ-TAL-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-TAL-001 | Add Tree flow: Species → DBH → Height (if subsample) → Extras (status, damage, notes, photo) → Save. | Back-navigation safe. |
| REQ-TAL-002 | Species picker: quick-tap grid, with the project's 5 most recently used at the top. | 3-column grid; 56 pt tap targets. |
| REQ-TAL-003 | Tree statuses: Live, Dead-standing, Dead-down, Cull. | Affects volume calculation. |
| REQ-TAL-004 | Multi-stem tree: one tree record with an array of stems; each stem's DBH measured separately. `BA_tree = Σ BA_stem`. | Correct aggregation in plot stats. |
| REQ-TAL-005 | Plot Tally screen shows live plot-level stats (TPA, BA/ac, QMD, V/ac) updated after each save. | Update within 300 ms. |
| REQ-TAL-006 | Edit existing tree: changes audited (updated_at); soft delete (deleted_at not null) keeps row for history. | Deleted trees excluded from stats but visible in "Deleted" filter. |

### §3.8 Plot Close & Stand Aggregation (REQ-AGG-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-AGG-001 | On Close Plot, run validation: unknown species, DBH below project minimum, trees with red-tier DBH. Show warnings; allow override. | Warnings listed; cruiser confirms. |
| REQ-AGG-002 | Per-plot stats: n_trees, TPA, BA/ac, QMD, gross V/ac, net V/ac, dominant species (by BA). | Computed offline. |
| REQ-AGG-003 | Stand-level stats stratified by Stratum: ȳ_h, SE(ȳ_h), weighted mean Ŷ, SE(Ŷ), 95% CI. See [§7.5](#75-stand-level-statistics). | Values match hand-calculated reference test. |
| REQ-AGG-004 | Export: tree-level CSV, plot-level CSV, stand summary CSV, GeoJSON of plot centers + stratum, PDF report. | All files produced from one "Export" action. |

### §3.9 Settings & Calibration (REQ-CAL-XXX)

| ID | Requirement | Acceptance |
|----|-------------|------------|
| REQ-CAL-001 | Unit system: imperial or metric, per Project. | All screens update. |
| REQ-CAL-002 | Breast height convention setting: `uphill` / `mid` / `any`. This is a cruiser reminder only; it does not change the guide line rendering. | Shown as label above guide line ("Align to DBH, uphill side"). |
| REQ-CAL-003 | Wall calibration: scan a flat wall, fit a plane, record residual RMS as `depth_noise_mm` and mean as `depth_bias_mm` for the Project. | Values persisted. |
| REQ-CAL-004 | Cylinder calibration: scan PVC cylinders of known diameter; fit a linear correction `DBH_true = α + β · DBH_raw`. | Correction applied in engine. |
| REQ-CAL-005 | Compass declination: auto from WMM lookup for current location, manual override possible. | Shown in Settings. |

---

## §4 User Workflows

Step-by-step, with named states. Each workflow is a state machine; transitions are explicit so the LLM can build SwiftUI navigation deterministically.

### §4.1 Workflow: Start a cruise day

```
AppLaunch → Home → ProjectList → ProjectDashboard → PlotMap
```

### §4.2 Workflow: Complete one plot (happy path)

```
PlotMap
  → [tap next plot]
  → Navigation (states: far / near / arrived)
  → PlotCenterConfirmation
        ├─ mode A: GPSAveraging (green tier) → confirmed
        └─ mode B: OffsetFromOpening (needed when A yellow/red)
  → ARBoundaryView
  → PlotTally (loop over trees)
        each tree: AddTreeFlow = SpeciesPick → DBHScan → HeightScan? → Extras → Save
  → ClosePlotConfirmation
  → PlotSummary
  → PlotMap (next)
```

### §4.3 Workflow: DBH scan (detailed state machine)

State: `DBHScanState { idle, aligning, armed, capturing, fitted, accepted, rejected }`

```
idle         [enter screen]
  → aligning [camera + guide line rendered, user aligns phone physically]
        user sees center crosshair red → green when depth stable
  → armed    [crosshair green, user ready]
        user taps trunk center
  → capturing [burst of 10–15 depth frames over 0.5–1.0 s]
  → fitted   [circle fit complete; show DBH ± σ_r + tier]
        [tier == green or yellow] → accepted on user tap
        [tier == red]             → rejected (show reason), back to aligning
        [manual]                  → NumericEntry, saved as manual
        [dual-view]               → capture second view, refit
```

### §4.4 Workflow: Height measure (detailed state machine)

State: `HeightState { idle, anchorSet, walking, aimTopArmed, aimTopCaptured, aimBaseArmed, computed, accepted, rejected }`

```
idle         [enter screen]
  → anchorSet   [user touches phone to base, taps "Anchor"]
  → walking     [user walks back; live d_h displayed; geometry hint]
  → aimTopArmed [user stops and raises phone]
                user taps "Aim Top" → α_top recorded
  → aimTopCaptured
  → aimBaseArmed [user lowers phone]
                user taps "Aim Base" → α_base recorded
  → computed    [H = d_h · (tan α_top − tan α_base), σ_H computed]
                [tier green/yellow] → accepted
                [tier red]          → rejected (show reason)
```

### §4.5 Workflow: Plot center under bad GPS

```
PlotCenterConfirmation
  [GPS averaging running]
  after 60 s:
    accuracy OK → save, tier A or B
    accuracy bad → prompt "Try Offset-from-Opening?"
        user accepts:
          → OffsetFlow
              1. keep ARKit running
              2. walk to opening (live distance shown)
              3. "Capture fix here" → 30 s averaging
              4. walk back
              5. "Confirm plot center" → compute center = opening_fix + enu(Δ_vio)
              6. save, tier B or C
        user declines:
          → save current median, tier C or D, recommend revisit
```

---

## §5 Screens

All screens live in `Screens/` and are SwiftUI Views. Name = file name.

### §5.1 Screen list

| Screen | File | Notes |
|--------|------|-------|
| Home | `HomeScreen.swift` | Project list, "New Project", "Import" |
| ProjectDashboard | `ProjectDashboardScreen.swift` | Progress, "Go Cruise" |
| CruiseDesign | `CruiseDesignScreen.swift` | Stratum map, plot-size, species, sampling |
| PlotMap | `PlotMapScreen.swift` | Project map with plot status |
| Navigation | `NavigationScreen.swift` | Compass arrow + distance + GPS tier |
| PlotCenter | `PlotCenterScreen.swift` | GPS averaging UI + offset fallback |
| ARBoundary | `ARBoundaryScreen.swift` | AR view + ring overlay |
| PlotTally | `PlotTallyScreen.swift` | Main working screen, tree table + live stats |
| AddTreeFlow | `AddTreeFlowScreen.swift` | Stepper: Species → DBH → Height → Extras |
| DBHScan | `DBHScanScreen.swift` | AR camera + guide line + crosshair |
| HeightScan | `HeightScanScreen.swift` | 4-stage flow |
| TreeDetail | `TreeDetailScreen.swift` | View / edit existing tree |
| PlotSummary | `PlotSummaryScreen.swift` | Close-out screen |
| StandSummary | `StandSummaryScreen.swift` | Full cruise stats |
| Export | `ExportScreen.swift` | Format picker + share sheet |
| Settings | `SettingsScreen.swift` | Units, breast height convention, calibration |

### §5.2 DBHScan screen layout (authoritative)

```
┌──────────────────────────────────┐
│ < Back   DBH Scan          [i]   │ ← top bar
├──────────────────────────────────┤
│                                  │
│                                  │
│                                  │
│ ─────── guide line (fixed) ───── │ ← at y = screen_height / 2
│             + crosshair          │   horizontal 1.5 pt, opacity 0.5, gray
│                                  │
│                                  │
│                                  │
├──────────────────────────────────┤
│  Status: "Align guide to DBH,    │ ← status banner
│           uphill side. Tap       │
│           stem center."          │
│                                  │
│  DBH: 42.3 cm ± 0.8 cm  [green]  │ ← appears after fit
│  Arc: 72°  RMSE: 1.1 mm          │
├──────────────────────────────────┤
│ [Retake] [Manual] [Dual-view]    │
│ [✓ Accept]                       │
└──────────────────────────────────┘
```

### §5.3 HeightScan screen layout (authoritative)

```
Stage 1 (anchorSet):
  ┌──────────────────────────────┐
  │ "Touch phone to tree base"   │
  │        [Anchor Here]          │
  └──────────────────────────────┘

Stage 2 (walking):
  ┌──────────────────────────────┐
  │  d_h = 18.4 m                │
  │  "Move back ~7 m more"       │
  │        [Continue]            │
  └──────────────────────────────┘

Stage 3 (aimTopArmed):
  ┌──────────────────────────────┐
  │   crosshair on sky           │
  │  "Aim at treetop, tap"       │
  │        [Aim Top]             │
  └──────────────────────────────┘

Stage 4 (aimBaseArmed):
  ┌──────────────────────────────┐
  │   crosshair on ground        │
  │  "Aim at tree base, tap"     │
  │        [Aim Base]            │
  └──────────────────────────────┘

Stage 5 (computed):
  ┌──────────────────────────────┐
  │  H = 38.2 m ± 0.9 m  [green] │
  │  d_h=25.0, α_top=56.4°       │
  │   α_base=-3.7°               │
  │ [Retake]       [✓ Accept]    │
  └──────────────────────────────┘
```

---

## §6 Data Model

Implement as Core Data entities, exposed to app code as Swift structs. Types below are the canonical shape. Persistence layer adapts.

### §6.1 Enumerations

```swift
enum UnitSystem: String, Codable { case imperial, metric }

enum PlotType: String, Codable { case fixedArea, variableRadius }

enum SamplingScheme: String, Codable {
    case systematicGrid, stratifiedRandom, manual
}

enum BreastHeightConvention: String, Codable { case uphill, mid, any, custom }

enum TreeStatus: String, Codable {
    case live, deadStanding, deadDown, cull
}

enum DBHMethod: String, Codable {
    case lidarPartialArcSingleView
    case lidarPartialArcDualView
    case lidarIrregular
    case manualCaliper
    case manualVisual
}

enum HeightMethod: String, Codable {
    case vioWalkoffTangent
    case tapeTangent        // manual tape distance + tangent
    case manualEntry
    case imputedHD
}

enum PositionSource: String, Codable {
    case gpsAveraged, vioOffset, vioChain, externalRTK, manual
}

enum PositionTier: String, Codable { case A, B, C, D }

enum ConfidenceTier: String, Codable { case green, yellow, red }
```

### §6.2 Core entities

```swift
struct Project: Identifiable, Codable {
    let id: UUID
    var name: String
    var description: String
    var owner: String
    var createdAt: Date
    var updatedAt: Date
    var units: UnitSystem
    var breastHeightConvention: BreastHeightConvention
    var slopeCorrection: Bool
    // Calibration
    var lidarBiasMm: Float
    var depthNoiseMm: Float
    var dbhCorrectionAlpha: Float    // from cylinder calibration; default 0
    var dbhCorrectionBeta: Float     // default 1
    var vioDriftFraction: Float      // default 0.02
}

struct Stratum: Identifiable, Codable {
    let id: UUID
    let projectId: UUID
    var name: String
    var areaAcres: Float
    var polygonGeoJSON: String       // WGS84
}

struct CruiseDesign: Identifiable, Codable {
    let id: UUID
    let projectId: UUID
    var plotType: PlotType
    var plotAreaAcres: Float?        // required if fixedArea
    var baf: Float?                  // required if variableRadius
    var samplingScheme: SamplingScheme
    var gridSpacingMeters: Float?    // required if systematicGrid
}

struct PlannedPlot: Identifiable, Codable {
    let id: UUID
    let projectId: UUID
    var stratumId: UUID?
    var plotNumber: Int
    var plannedLat: Double
    var plannedLon: Double
    var visited: Bool
}

struct Plot: Identifiable, Codable {
    let id: UUID
    let projectId: UUID
    var plannedPlotId: UUID?
    var plotNumber: Int
    var centerLat: Double
    var centerLon: Double
    var positionSource: PositionSource
    var positionTier: PositionTier
    var gpsNSamples: Int
    var gpsMedianHAccuracyM: Float
    var gpsSampleStdXyM: Float
    var offsetWalkM: Float?          // non-nil for vioOffset
    var slopeDeg: Float
    var aspectDeg: Float
    var plotAreaAcres: Float         // denormalized from CruiseDesign for robustness
    var startedAt: Date
    var closedAt: Date?
    var closedBy: String?
    var notes: String
    var coverPhotoPath: String?
    var panoramaPath: String?        // for re-navigation
}

struct Tree: Identifiable, Codable {
    let id: UUID
    let plotId: UUID
    var treeNumber: Int
    var speciesCode: String
    var status: TreeStatus

    // DBH
    var dbhCm: Float
    var dbhMethod: DBHMethod
    var dbhSigmaMm: Float?           // uncertainty
    var dbhRmseMm: Float?
    var dbhCoverageDeg: Float?
    var dbhNInliers: Int?
    var dbhConfidence: ConfidenceTier
    var dbhIsIrregular: Bool

    // Height
    var heightM: Float?
    var heightMethod: HeightMethod?
    var heightSource: String?        // "measured" or "imputed"
    var heightSigmaM: Float?
    var heightDHM: Float?
    var heightAlphaTopDeg: Float?
    var heightAlphaBaseDeg: Float?
    var heightConfidence: ConfidenceTier?

    // Geometry within plot
    var bearingFromCenterDeg: Float?
    var distanceFromCenterM: Float?
    var boundaryCall: String?        // "in" / "borderline" / "out" for BAF plots

    // Attributes
    var crownClass: String?
    var damageCodes: [String]
    var isMultistem: Bool
    var parentTreeId: UUID?

    var notes: String
    var photoPath: String?
    var rawScanPath: String?         // optional .ply

    var createdAt: Date
    var updatedAt: Date
    var deletedAt: Date?             // soft-delete
}

struct SpeciesConfig: Identifiable, Codable {
    let code: String                 // e.g., "DF"
    var id: String { code }
    var commonName: String
    var scientificName: String
    var volumeEquationId: String
    var merchTopDibCm: Float
    var stumpHeightCm: Float
    var expectedDbhMinCm: Float
    var expectedDbhMaxCm: Float
    var expectedHeightMinM: Float
    var expectedHeightMaxM: Float
}

struct VolumeEquation: Identifiable, Codable {
    let id: String
    var form: String                 // e.g., "bruce", "schumacher_hall"
    var coefficients: [String: Float]
    var unitsIn: String              // e.g., "cm, m"
    var unitsOut: String             // e.g., "m3"
    var sourceCitation: String
}

struct HeightDiameterFit: Identifiable, Codable {
    let id: UUID
    let projectId: UUID
    let speciesCode: String
    var modelForm: String            // "naslund"
    var coefficients: [String: Float]
    var nObs: Int
    var rmse: Float
    var updatedAt: Date
}
```

### §6.3 Measurement result types (transient, passed up from sensor layer)

```swift
struct DBHResult {
    let diameterCm: Float
    let centerXZ: SIMD2<Float>           // in ARKit world frame
    let arcCoverageDeg: Float
    let rmseMm: Float
    let sigmaRmm: Float
    let nInliers: Int
    let confidence: ConfidenceTier
    let method: DBHMethod
    let rawPointsPath: String?
    let rejectionReason: String?         // non-nil if confidence == .red
}

struct HeightResult {
    let heightM: Float
    let dHm: Float
    let alphaTopRad: Float
    let alphaBaseRad: Float
    let sigmaHm: Float
    let confidence: ConfidenceTier
    let method: HeightMethod
    let rejectionReason: String?
}

struct PlotCenterResult {
    let lat: Double
    let lon: Double
    let source: PositionSource
    let tier: PositionTier
    let nSamples: Int
    let medianHAccuracyM: Float
    let sampleStdXyM: Float
    let offsetWalkM: Float?
}
```

---

## §7 Core Algorithms

Each algorithm uses a fixed spec format:
- **Purpose**
- **Inputs** (types)
- **Outputs** (types)
- **Invariants** (must hold before/after)
- **Failure modes** (what can go wrong + behavior)
- **Algorithm** (numbered steps)
- **Pseudocode**
- **Done criteria** (unit-testable)

### §7.1 DBH: Guide-Align Partial-Arc Circle Fit

**Purpose.** Estimate tree DBH from a single-view LiDAR capture, where the cruiser has physically aligned a fixed on-screen horizontal guide line to the DBH point on the trunk.

**Inputs:**
```swift
struct DBHScanInput {
    let frames: [ARDepthFrame]           // 10–15 frames, each with depth, confidence, intrinsics, pose
    let tapPixel: CGPoint                // user tap pixel (image coords)
    let guideRowY: Int                   // fixed: screen_height / 2 in image coords (constant per device)
    let projectCalibration: ProjectCalibration  // depth_noise_mm, dbh_correction_alpha, dbh_correction_beta
}

struct ARDepthFrame {
    let depth: [[Float]]                 // 256×192 meters
    let confidence: [[UInt8]]            // 0/1/2
    let intrinsics: simd_float3x3        // K
    let cameraPoseWorld: simd_float4x4   // T_world_cam
    let timestamp: TimeInterval
}
```

**Outputs:** `DBHResult` (see §6.3) or `nil` on hard failure.

**Invariants:**
- At least 5 valid frames in input.
- `tapPixel` is within image bounds.
- `guideRowY` equals `depth.height / 2` for the capture device (fixed per session).
- ARKit world frame is gravity-aligned (`worldAlignment = .gravityAndHeading` or `.gravity`).

**Failure modes:**
| Condition | Behavior |
|-----------|----------|
| `d_tap` (depth at tap) outside [0.5, 3.0] m | Return `nil` with rejection `"Move closer or step back; tap depth \(d_tap) m out of range"` |
| Confidence at tap < medium | Return `nil` with `"Trunk surface not reliably seen; try a cleaner stem area"` |
| Fewer than 30 back-projected points | Return `nil` with `"Not enough surface points; hold steadier or move closer"` |
| Circle fit RANSAC never finds ≥ 20 inliers | Return `nil` with `"Could not fit a circle"` |
| Final result fails sanity checks in §7.1 Step 9 | Return `DBHResult` with `confidence = .red` and specific reason string |

**Algorithm (numbered steps):**

1. **Burst validity:** if `frames.count < 5`, return nil.
2. **Depth at tap:** take the 5×5-pixel median depth around `tapPixel` in the last frame. Call it `d_tap`. Reject if outside [0.5, 3.0] m or confidence < medium.
3. **Stem strip per frame:** for each frame, at image row `y = guideRowY`, iterate over columns `x = 0..width-1`. Keep pixel `(x, y)` if:
   - `confidence[y][x] >= 1` (medium or high)
   - `|depth[y][x] − d_tap| < 0.15` m
   - `(x, y)` is in the connected depth region containing `tapPixel` at the same depth tolerance
4. **Back-project to world XZ:** for every kept pixel, compute
   ```
   Xc = (x − cx) · d / fx
   Yc = (y − cy) · d / fy
   Zc = d
   Pworld = T_world_cam · [Xc; Yc; Zc; 1]
   ```
   Retain only `(Pworld.x, Pworld.z)`. Append to combined set `P`.
5. **Point count check:** if `|P| < 30`, return nil.
6. **Outlier removal:** statistical outlier removal with k=8 neighbors, σ_mult=2.0 → `P_clean`.
7. **RANSAC + Taubin circle fit:**
   - For 500 iterations: sample 3 points, compute circle (Kasa algebraic for speed), count inliers with tolerance `max(3 mm, 2 × depth_noise_mm / 1000)`.
   - Select best iteration.
   - Refit Taubin on inliers → final `(center*, r*)`.
8. **Metrics:**
   - `rmse = sqrt(mean((|P_i − center*| − r*)²))` over inliers.
   - `arc_coverage_deg = unwrapped_angular_span(inliers, center*)`.
   - `sigma_r = depth_noise_mm/1000 / (sqrt(n_inliers) · sin(arc_coverage_deg · π / 360))`.
   - `radius_CoV_across_frames`: refit Taubin per-frame on that frame's stem strip only, compute CoV of radii.
9. **Sanity tree (apply ALL checks):**

    | Check | Reject (red) if | Warn (yellow) if |
    |-------|-----------------|------------------|
    | n_inliers | < 20 | 20–30 |
    | arc_coverage_deg | < 45° | 45°–60° |
    | r_fit | < 2.5 cm or > 100 cm | — |
    | rmse / r_fit | > 5% | 3%–5% |
    | sigma_r / r_fit | > 5% | 2%–5% |
    | frame_burst_radius_CoV | > 10% | 5%–10% |
    | `|d_tap − distance(cam_center, closest_fitted_arc_point)|` | > 5 cm | 3–5 cm |

    Combine per §7.9.
10. **Apply calibration:** `DBH_corrected_cm = alpha + beta · (2 · r* · 100)`.
11. **Return** `DBHResult`.

**Pseudocode:**

```swift
func estimateDBH(input: DBHScanInput) -> DBHResult? {
    guard input.frames.count >= 5 else { return nil }

    // Step 2
    guard let dTap = medianDepth(input.frames.last!,
                                 around: input.tapPixel,
                                 radius: 2),
          0.5...3.0 ~= dTap,
          confidenceAt(input.frames.last!, input.tapPixel) >= 1
    else {
        return redResult(reason: "Tap depth or confidence out of range")
    }

    // Steps 3–4
    var pointsXZ: [SIMD2<Float>] = []
    for frame in input.frames {
        let strip = extractGuideRowStemStrip(
            frame: frame,
            guideRowY: input.guideRowY,
            dTap: dTap,
            deltaDepth: 0.15,
            seedPixel: input.tapPixel)
        pointsXZ.append(contentsOf:
            strip.map { backProjectToWorldXZ($0, frame) })
    }

    // Step 5
    guard pointsXZ.count >= 30 else {
        return redResult(reason: "Too few trunk points (\(pointsXZ.count))")
    }

    // Step 6
    let cleaned = statisticalOutlierRemoval(pointsXZ, k: 8, sigma: 2.0)

    // Step 7
    let noiseM = input.projectCalibration.depthNoiseMm / 1000
    let tol = max(0.003, 2 * noiseM)
    guard let fit = ransacTaubinCircle(cleaned, iters: 500, inlierTol: tol)
    else { return redResult(reason: "Could not fit a circle") }

    // Step 8
    let rmse = computeRMSE(fit.inliers, center: fit.center, radius: fit.radius)
    let arcDeg = arcCoverageDeg(fit.inliers, center: fit.center)
    let sigmaR = noiseM / (sqrt(Float(fit.inliers.count)) *
                           sin(arcDeg * .pi / 360))
    let radiusCoV = perFrameRadiusCoV(input.frames, guideRowY: input.guideRowY,
                                       dTap: dTap)

    // Step 9
    let tier = combineChecks([
        check(fit.inliers.count >= 20,      sev: .reject),
        check(fit.inliers.count >= 30,      sev: .warn),
        check(arcDeg >= 45,                 sev: .reject),
        check(arcDeg >= 60,                 sev: .warn),
        check(fit.radius >= 0.025 && fit.radius <= 1.0, sev: .reject),
        check(rmse / fit.radius <= 0.05,    sev: .reject),
        check(rmse / fit.radius <= 0.03,    sev: .warn),
        check(sigmaR / fit.radius <= 0.05,  sev: .reject),
        check(sigmaR / fit.radius <= 0.02,  sev: .warn),
        check(radiusCoV <= 0.10,            sev: .reject),
        check(radiusCoV <= 0.05,            sev: .warn)
    ])
    if tier == .red { return redResult(reason: "Quality below threshold") }

    // Step 10
    let cal = input.projectCalibration
    let dbhCmRaw = 2 * fit.radius * 100
    let dbhCm = cal.dbhCorrectionAlpha + cal.dbhCorrectionBeta * dbhCmRaw

    return DBHResult(
        diameterCm: dbhCm,
        centerXZ: fit.center,
        arcCoverageDeg: arcDeg,
        rmseMm: rmse * 1000,
        sigmaRmm: sigmaR * 1000,
        nInliers: fit.inliers.count,
        confidence: tier,
        method: .lidarPartialArcSingleView,
        rawPointsPath: saveRawPLYIfOptedIn(cleaned),
        rejectionReason: nil)
}
```

**Done criteria (unit-testable):**
- [ ] Given synthetic point sets on arcs of 30°, 60°, 90°, 180°, 270°, 360° with known radius 15/30/50 cm and Gaussian noise 5 mm, the pipeline returns the known radius within σ_r and reports arc coverage within ±5°.
- [ ] Given an input with only 15 inliers, the function returns a red-tier result with a human-readable reason.
- [ ] Given a 45°-arc input with very clean points, the function returns yellow tier.
- [ ] Given a calibration with (α=0.2, β=0.98), the returned DBH equals 0.2 + 0.98 · (2r·100).

### §7.2 Height: VIO Walk-off Tangent

**Purpose.** Measure tree total height using two angle taps (top and base) from the same standing position and the horizontal distance from ARKit VIO between the base anchor and the tap position.

**Why the formula works (phone eye-level cancels):** at the standing position, phone world-Y is `y_B`. If `α` is phone pitch (up positive), and horizontal distance to the tree axis is `d_h`, then `y_top = y_B + d_h · tan α_top` and `y_base = y_B + d_h · tan α_base`. Subtracting: `H = y_top − y_base = d_h · (tan α_top − tan α_base)`. The term `y_B` vanishes, so we do **not** need to know the phone's height above ground. This is the reason the method works on slopes.

**Inputs:**
```swift
struct HeightMeasureInput {
    let anchorPointWorld: SIMD3<Float>   // P_A from anchor-on-base tap
    let standingPointWorld: SIMD3<Float> // P_B from when user taps Aim Top / Aim Base
    let alphaTopRad: Float               // pitch, positive up
    let alphaBaseRad: Float              // pitch, positive up (usually negative)
    let trackingStateWasNormalThroughout: Bool
    let projectCalibration: ProjectCalibration   // vioDriftFraction
}
```

**Outputs:** `HeightResult` or nil.

**Invariants:**
- `trackingStateWasNormalThroughout == true` for the entire measurement window.
- ARKit world is gravity-aligned.
- α_top > α_base (looking up is a larger angle than looking down).

**Failure modes:**
| Condition | Behavior |
|-----------|----------|
| `d_h < 3 m` | Return red with `"Too close; step back"` |
| `d_h > 30 m` | Return yellow (not red); mark high-drift |
| `\|α_top\| > 85°` | Return red with `"Top angle too steep; step back"` |
| Tracking was limited at any step | Return red with `"AR tracking lost mid-measurement"` |
| `H < 1.5` or `H > 100` | Return red with `"Computed height unreasonable"` |

**Algorithm:**

1. Compute horizontal distance:
   ```
   d_h = sqrt((P_B.x − P_A.x)² + (P_B.z − P_A.z)²)
   ```
2. Check invariants & failure modes above.
3. Compute:
   ```
   H = d_h · (tan α_top − tan α_base)
   ```
4. Compute uncertainty:
   ```
   σ_d = vioDriftFraction · d_h      // e.g., 0.02 · d_h
   σ_α = 0.3° = 0.00524 rad           // IMU pitch noise, constant
   σ_H² = (tan α_top − tan α_base)² · σ_d²
        + d_h² · sec⁴(α_top) · σ_α²
        + d_h² · sec⁴(α_base) · σ_α²
   ```
5. Tier from:
   - red if any failure mode triggers
   - yellow if `σ_H / H > 0.05` OR `d_h > 25` OR `|α_top| > 75°`
   - green otherwise
6. Return `HeightResult`.

**Pseudocode:**

```swift
func measureHeight(input: HeightMeasureInput) -> HeightResult? {
    let dh = simd_length(SIMD2<Float>(input.standingPointWorld.x - input.anchorPointWorld.x,
                                       input.standingPointWorld.z - input.anchorPointWorld.z))

    guard input.trackingStateWasNormalThroughout else {
        return redHeightResult(reason: "AR tracking lost")
    }
    guard dh >= 3 else {
        return redHeightResult(reason: "Too close (d_h=\(dh) m)")
    }
    guard abs(input.alphaTopRad) < 85 * .pi / 180 else {
        return redHeightResult(reason: "Top angle too steep")
    }

    let H = dh * (tan(input.alphaTopRad) - tan(input.alphaBaseRad))

    guard H >= 1.5 && H <= 100 else {
        return redHeightResult(reason: "Computed height \(H) m out of range")
    }

    let sigmaD = input.projectCalibration.vioDriftFraction * dh
    let sigmaAlpha: Float = 0.3 * .pi / 180
    let term1 = pow(tan(input.alphaTopRad) - tan(input.alphaBaseRad), 2) * pow(sigmaD, 2)
    let term2 = dh * dh * pow(1/cos(input.alphaTopRad), 4) * sigmaAlpha * sigmaAlpha
    let term3 = dh * dh * pow(1/cos(input.alphaBaseRad), 4) * sigmaAlpha * sigmaAlpha
    let sigmaH = sqrt(term1 + term2 + term3)

    let tier: ConfidenceTier
    if sigmaH / H > 0.05 || dh > 25 || abs(input.alphaTopRad) > 75 * .pi / 180 {
        tier = .yellow
    } else {
        tier = .green
    }

    return HeightResult(
        heightM: H,
        dHm: dh,
        alphaTopRad: input.alphaTopRad,
        alphaBaseRad: input.alphaBaseRad,
        sigmaHm: sigmaH,
        confidence: tier,
        method: .vioWalkoffTangent,
        rejectionReason: nil)
}
```

**Done criteria:**
- [ ] For `d_h = 25, α_top = 56.9°, α_base = -3.66°`, returns `H ≈ 40 m` and `σ_H ≈ 0.9 m` (tolerance 0.1 m).
- [ ] For `d_h = 2 m`, returns red.
- [ ] For `α_top = 88°`, returns red.
- [ ] With `trackingStateWasNormalThroughout = false`, returns red regardless of other values.
- [ ] σ_H monotonically increases with `d_h` for fixed angles.

### §7.3 GPS Under Canopy

Four strategies in priority order. Each is a separate function; the UI calls them as the user moves through the flow.

#### §7.3.1 GPS Averaging

**Inputs:**
```swift
struct GPSAveragingInput {
    let samples: [CLLocation]            // collected at 1 Hz
    let maxAcceptableAccuracyM: Float    // default 20
}
```

**Output:** `PlotCenterResult` or nil.

**Algorithm:**
1. Filter to samples where `horizontalAccuracy <= maxAcceptableAccuracyM`.
2. If remaining count < 30, return nil.
3. Convert to local ENU plane centered on sample 0.
4. Compute medians of east and north; convert back to lat/lon.
5. Compute `sample_std_xy = sqrt(var_east + var_north)` over accepted samples.
6. Tier:
   - A if `median_hAccuracy < 5 m` and `sample_std_xy < 3 m`
   - B if `median_hAccuracy < 10 m` and `sample_std_xy < 5 m`
   - C if `median_hAccuracy < 20 m`
   - D otherwise (reject)

**Done criteria:**
- [ ] Synthetic samples with 3 m scatter and accuracy 5 m → tier A.
- [ ] Synthetic with 15 m scatter → tier C.

#### §7.3.2 Offset-from-Opening

**Purpose.** When canopy blocks GPS, walk to an opening with clear sky, get a clean fix there, and subtract the AR-tracked walk displacement.

**Inputs:**
```swift
struct OffsetFromOpeningInput {
    let openingFix: PlotCenterResult         // from GPSAveraging in the opening
    let openingPointWorld: SIMD3<Float>      // ARKit position when fix was confirmed
    let plotPointWorld: SIMD3<Float>         // ARKit position at plot center
    let trackingStateWasNormalThroughout: Bool
    let compassHeadingDegAtOpening: Double   // ARKit must be .gravityAndHeading aligned
}
```

**Output:** `PlotCenterResult` or nil.

**Algorithm:**
1. If tracking was not normal throughout, return nil.
2. `walkDistance = simd_length(plotPointWorld - openingPointWorld)`.
3. If `walkDistance > 200 m`, tier drops to D (too much drift expected).
4. Compute `Δ_world = plotPointWorld − openingPointWorld` in ARKit world.
5. Because ARKit used `.gravityAndHeading`, world X is approximately East and world −Z is approximately North. Apply exact rotation:
   ```
   east  = Δ_world.x
   north = −Δ_world.z
   ```
6. Convert (east, north) displacement to lat/lon delta at the opening fix:
   ```
   dLat = north / 111320
   dLon = east  / (111320 · cos(openingFix.lat · π / 180))
   ```
7. Plot center = opening + (dLat, dLon).
8. Tier:
   - inherited from openingFix, with a one-step demotion if walkDistance > 100 m.

**Done criteria:**
- [ ] A 50 m north walk from an opening tier-A fix produces a plot center 50 m north, tier A (or B if >100 m).
- [ ] A 250 m walk → tier D.

### §7.4 H–D (Height–Diameter) Model

**Purpose.** Impute heights for trees that do not have a measured height.

**Form:** Näslund `H = 1.3 + D² / (a + b · D)²` where D is DBH in cm, H is height in m.

**Fit:** per project, per species, whenever a plot is closed if `n_measured ≥ 8` for that species in that project.

**Algorithm:**
1. Take all `Tree` rows in the Project with `heightM != nil` and `dbhCm > 0` and `speciesCode = X`.
2. Fit `a`, `b` by nonlinear least squares minimizing `Σ (H − 1.3 − D²/(a+b·D)²)²`. Use Gauss-Newton with initial guess `a = 0.1·D_mean, b = 0.05`.
3. Record RMSE.
4. For new tree with DBH D and no height:
   `H_imputed = 1.3 + D² / (a + b·D)²`
5. If species has < 8 measured trees, use project-wide pooled fit and flag the imputation as "pooled".

**Done criteria:**
- [ ] With synthetic data generated from Näslund with known (a, b), recover (a, b) within 5%.
- [ ] With 5 trees and no pool available, returns nil and marks the imputation flag.

### §7.5 Stand-Level Statistics

**Purpose.** Given plot-level values (y_j = BA/ac or V/ac per plot), compute stand totals with stratified sampling error.

**Algorithm:**
```
For stratum h with area A_h, plots j = 1..n_h, plot values y_{h,j}:
  ȳ_h      = mean(y_{h,j})
  s²_h     = variance(y_{h,j}, unbiased)
  var_ȳ_h  = s²_h / n_h · (1 − n_h/N_h)     // FPC; N_h = A_h / plot_area
  Ŷ        = Σ_h A_h · ȳ_h
  var_Ŷ    = Σ_h A_h² · var_ȳ_h
  SE_Ŷ     = sqrt(var_Ŷ)
  df       = Satterthwaite( {s²_h, n_h, A_h} )
  CI95     = Ŷ ± t_{0.975, df} · SE_Ŷ
```

Skip FPC if N_h is unknown (treat as infinite population).

**Done criteria:**
- [ ] Single stratum with 5 plots, plot values [100, 110, 95, 105, 100] → Ŷ = A · 102, SE matches hand calc.

### §7.6 Plot & Tree Computations

Pure functions. No sensor dependencies.

```swift
func basalAreaM2(dbhCm: Float) -> Float {
    let dM = dbhCm / 100
    return .pi * dM * dM / 4
}

func tpa(plot: Plot, trees: [Tree]) -> Float {
    let ef = 1.0 / plot.plotAreaAcres
    return Float(trees.filter { $0.deletedAt == nil }.count) * ef
}

func baPerAcre(plot: Plot, trees: [Tree]) -> Float {
    let ef = 1.0 / plot.plotAreaAcres
    return trees
        .filter { $0.deletedAt == nil }
        .reduce(0) { $0 + basalAreaM2(dbhCm: $1.dbhCm) } * ef
}

func qmd(trees: [Tree]) -> Float {
    let live = trees.filter { $0.deletedAt == nil }
    guard !live.isEmpty else { return 0 }
    let sumSq = live.reduce(0) { $0 + $1.dbhCm * $1.dbhCm }
    return sqrt(sumSq / Float(live.count))
}
```

For variable-radius BAF plots:
```swift
func treeFactorBAF(tree: Tree, baf: Float) -> Float {
    return baf / basalAreaM2(dbhCm: tree.dbhCm)
}
func baPerAcreBAF(trees: [Tree], baf: Float) -> Float {
    return Float(trees.filter { $0.deletedAt == nil }.count) * baf
}
```

### §7.7 Volume Engine

```swift
protocol VolumeEquation {
    func totalVolumeM3(dbhCm: Float, heightM: Float) -> Float
    func merchantableVolumeM3(dbhCm: Float, heightM: Float,
                              topDibCm: Float, stumpHeightCm: Float) -> Float
}
```

Default implementations:
- `BruceDouglasFir`
- `ChambersFoltzHemlock`
- `SchumacherHall` (generic, configurable coeffs)
- `TableLookup` (user-provided)

Each loaded from the project's SpeciesConfig + VolumeEquation records.

**Done criteria:**
- [ ] Given published coefficient values for Douglas-fir and representative (D=40 cm, H=30 m), the result matches the published reference volume within 2%.

### §7.8 AR Plot Boundary Rendering

**Purpose.** Draw a circle of radius R on the ARKit scene, visible when the user looks around.

**Inputs:** center anchor, radius R (m), slope-correction flag.

**Algorithm:**
1. Sample 72 vertices on the horizontal circle at center, radius R.
2. If slope-correction: for each vertex, cast a vertical ray onto the ARKit ground mesh (LiDAR sceneReconstruction). Replace vertex with hit point.
3. Build a line strip mesh; material: green emissive, width 2 cm, alpha 0.6.
4. Attach to an ARAnchor at the center; RealityKit renders it every frame.

**Done criteria:**
- [ ] On a flat floor, the ring is a circle of correct radius.
- [ ] On a sloped surface, slope-corrected vertices follow the mesh.

### §7.9 Confidence Framework

Used by every measurement.

```swift
enum Severity { case reject, warn }

struct Check {
    let passed: Bool
    let severity: Severity
    let reason: String
}

func combineChecks(_ checks: [Check]) -> ConfidenceTier {
    let rejectFail = checks.contains { !$0.passed && $0.severity == .reject }
    if rejectFail { return .red }
    let warnCount = checks.filter { !$0.passed && $0.severity == .warn }.count
    if warnCount >= 2 { return .red }
    if warnCount >= 1 { return .yellow }
    return .green
}
```

### §7.10 Calibration Procedures

**Wall calibration:**
1. User points phone at a flat wall 1–2 m away.
2. Capture 30 frames.
3. Merge all valid depth pixels to world XYZ.
4. Fit a plane by PCA.
5. `depth_noise_mm = RMS(residuals) · 1000`.
6. `depth_bias_mm = mean(residuals) · 1000`.
7. Save to `Project.depthNoiseMm`, `Project.lidarBiasMm`.

**Cylinder calibration:**
1. User scans PVC pipes of known diameters (e.g., 10 cm, 20 cm, 30 cm), one at a time.
2. For each scan, run §7.1 pipeline to get `DBH_measured`.
3. Collect pairs `(DBH_measured, DBH_true)`.
4. Linear regression: `DBH_true = α + β · DBH_measured`.
5. Save `(α, β)` to Project.

---

## §8 Module & File Layout

```
TimberCruisingApp/
├── App/
│   ├── TimberCruisingApp.swift        // @main
│   └── AppEnvironment.swift           // DI container
├── Screens/                           // SwiftUI Views (§5.1)
│   ├── HomeScreen.swift
│   ├── ProjectDashboardScreen.swift
│   ├── CruiseDesignScreen.swift
│   ├── PlotMapScreen.swift
│   ├── NavigationScreen.swift
│   ├── PlotCenterScreen.swift
│   ├── ARBoundaryScreen.swift
│   ├── PlotTallyScreen.swift
│   ├── AddTreeFlowScreen.swift
│   ├── DBHScanScreen.swift
│   ├── HeightScanScreen.swift
│   ├── TreeDetailScreen.swift
│   ├── PlotSummaryScreen.swift
│   ├── StandSummaryScreen.swift
│   ├── ExportScreen.swift
│   └── SettingsScreen.swift
├── ViewModels/                        // ObservableObject per screen
│   ├── DBHScanViewModel.swift
│   ├── HeightScanViewModel.swift
│   └── ...
├── Models/                            // Swift structs from §6
│   ├── Project.swift
│   ├── Plot.swift
│   ├── Tree.swift
│   ├── SpeciesConfig.swift
│   └── MeasurementResults.swift       // DBHResult, HeightResult, PlotCenterResult
├── InventoryEngine/                   // Pure functions, no sensors
│   ├── BasalAreaMath.swift
│   ├── VolumeEquations/
│   │   ├── VolumeEquation.swift
│   │   ├── BruceDouglasFir.swift
│   │   └── ...
│   ├── HDModel.swift                  // Näslund fit
│   ├── StandStatistics.swift          // stratified SE
│   └── ExpansionFactors.swift
├── Sensors/
│   ├── DBHEstimator.swift             // §7.1
│   ├── HeightEstimator.swift          // §7.2
│   ├── CircleFit/
│   │   ├── TaubinFit.swift
│   │   ├── KasaFit.swift
│   │   └── RANSACCircle.swift
│   ├── PointCloud/
│   │   ├── BackProjection.swift
│   │   └── OutlierRemoval.swift
│   ├── ARKitSessionManager.swift
│   ├── LiDARCalibration.swift         // §7.10
│   └── IMUHelpers.swift               // pitch median, etc.
├── Positioning/
│   ├── GPSAveraging.swift             // §7.3.1
│   ├── OffsetFromOpening.swift        // §7.3.2
│   ├── VIOChain.swift                 // §7.3 strategy C
│   ├── ExternalRTKReceiver.swift      // v1.5
│   └── PositionTierEvaluator.swift
├── AR/
│   ├── PlotBoundaryRenderer.swift     // §7.8
│   └── GroundMeshSampler.swift
├── Persistence/
│   ├── CoreDataStack.swift
│   ├── Repositories/
│   │   ├── ProjectRepository.swift
│   │   ├── PlotRepository.swift
│   │   ├── TreeRepository.swift
│   │   └── ...
├── Geo/
│   ├── GeoJSONImporter.swift
│   ├── KMLImporter.swift
│   ├── SamplingGenerator.swift        // systematic grid etc.
│   └── CoordinateConversions.swift    // lat/lon ↔ ENU
├── Export/
│   ├── CSVExporter.swift
│   ├── GeoJSONExporter.swift
│   └── PDFReportBuilder.swift
├── Basemap/
│   ├── TileCache.swift
│   └── OfflineBasemap.swift
├── Common/
│   ├── ConfidenceTier.swift           // §7.9
│   ├── Units.swift                    // imperial/metric conversions
│   └── HapticFeedback.swift
└── Resources/
    ├── SpeciesDefaults.json
    ├── VolumeEquationsPNW.json
    └── Localizable.strings
```

**Key rule for the LLM implementer:** `InventoryEngine/` has zero dependencies on `Sensors/`, `AR/`, or `Positioning/`. Everything in it is a pure function testable with XCTest without mocking sensors.

---

## §9 Implementation DAG and Phases

Implement in this order. Later modules may depend only on earlier ones.

### §9.1 Dependency DAG

```
Common ──┐
         ├─→ Models
         │      ├─→ Persistence ─┐
         │      │                 ├─→ ViewModels ─→ Screens ─→ App
         │      └─→ InventoryEngine ─────────────↑
         │
         ├─→ Geo ─────┬──────────────────────────↑
         │            └─→ Basemap ───────────────↑
         │
         ├─→ Sensors ─┬─→ AR ────────────────────↑
         │            └─→ Positioning ───────────↑
         │
         └─→ Export ─────────────────────────────↑
```

### §9.2 Phases (map to v0.x milestones)

**Phase 0 — Foundations (no UI work yet)**
- Implement `Common/` (units, haptics, ConfidenceTier).
- Implement `Models/` (all Swift structs from §6).
- Implement `Persistence/` (Core Data + repositories).
- Implement `InventoryEngine/` fully with unit tests.
- DONE when: `swift test` passes all pure-math tests. No sensors.

**Phase 1 — Project & Plot CRUD (UI, but no sensors yet)**
- Implement `Geo/` importers and sampling generators.
- Implement `Basemap/` offline tiles.
- Implement Screens: Home, ProjectDashboard, CruiseDesign, PlotMap, Export.
- DONE when: user can create a project, define strata, generate planned plots, export CSV of plan.

**Phase 2 — DBH Sensor Path (isolated)**
- Implement `Sensors/ARKitSessionManager.swift`.
- Implement `Sensors/CircleFit/`, `Sensors/PointCloud/`.
- Implement `Sensors/DBHEstimator.swift` per §7.1.
- Implement DBHScanScreen + DBHScanViewModel per §5.2.
- Implement `Sensors/LiDARCalibration.swift` per §7.10.
- DONE when: DBH scan works in app against a real tree and returns DBHResult.

**Phase 3 — Height & AR Boundary**
- Implement `Sensors/IMUHelpers.swift`.
- Implement `Sensors/HeightEstimator.swift` per §7.2.
- Implement HeightScanScreen per §5.3.
- Implement `AR/PlotBoundaryRenderer.swift` per §7.8.
- Implement ARBoundaryScreen.
- DONE when: user can measure tree height and see an AR boundary circle for a fixed-area plot.

**Phase 4 — Positioning under Canopy**
- Implement `Positioning/GPSAveraging.swift` per §7.3.1.
- Implement `Positioning/OffsetFromOpening.swift` per §7.3.2.
- Implement NavigationScreen + PlotCenterScreen.
- DONE when: user can record a plot center under any GPS condition with a documented tier.

**Phase 5 — Full Tally Loop**
- Implement PlotTallyScreen, AddTreeFlowScreen, TreeDetailScreen, PlotSummaryScreen, StandSummaryScreen.
- Wire live stats (TPA, BA/ac, QMD, V/ac) to inventory engine.
- Implement H–D model rolling update on plot close.
- DONE when: full plot loop works start-to-finish.

**Phase 6 — Export & Report**
- Implement `Export/CSVExporter.swift`, `GeoJSONExporter.swift`, `PDFReportBuilder.swift`.
- Implement ExportScreen.
- DONE when: all three export formats produce valid files.

**Phase 7 — Field Validation Readiness**
- Settings, calibration screens, error recovery polish.
- Pre-field checklist screen.
- DONE when: ready for field pilot.

---

## §10 Testing Strategy

### §10.1 Unit tests (required before merging each module)

- `InventoryEngine/` — 100% coverage required. Tests live in `InventoryEngineTests/`.
- `Sensors/CircleFit/` — synthetic point sets with known radius and arc coverage.
- `Sensors/DBHEstimator.swift` — fake ARDepthFrame fixtures; verify all Done Criteria in §7.1.
- `Sensors/HeightEstimator.swift` — verify all Done Criteria in §7.2.
- `Positioning/GPSAveraging.swift` — synthetic CLLocation arrays; verify tier logic.

### §10.2 Integration tests

- Round-trip: create project → simulate tree additions via direct repository writes → plot close → stand summary → CSV export → parse CSV back → values match.
- Height imputation: measure 10 synthetic heights → fit H–D → impute 90 → distribution reasonable.

### §10.3 Snapshot tests

- All Screens have light/dark/large-type snapshot tests.

### §10.4 Manual field tests (prior to v0.5 release)

- Bench: wall calibration, cylinder calibration.
- Yard: measure trees with known caliper/tape values; compare DBH and height.
- Real cruise: 20 paired plots at Starker or McDonald-Dunn.

---

## §11 NFR

| Dimension | Requirement |
|-----------|-------------|
| Offline | All field-path screens must function with Airplane Mode on. |
| Battery | 8 hours of operation with one 10000 mAh power bank. LiDAR active only during scans. |
| Latency | DBH scan tap → result < 3 s (P90). Plot stats refresh < 300 ms. |
| DBH accuracy | Bias < 0.5 cm, RMSE < 1.5 cm over DBH 15–80 cm. |
| Height accuracy | Bias < 0.5 m, RMSE < 1.5 m over H 10–45 m. |
| Plot BA agreement | Within 5% of conventional cruise, plot-by-plot. |
| Data safety | No field-recorded Tree or Plot should be lost on crash. WAL on Core Data; save after every Tree save. |

---

## §12 Open Questions (deferred)

These questions exist and are known. They do not block implementation of v0.1–v0.5. They will be resolved by empirical field testing with the implemented app.

1. Actual ARKit VIO drift rate under closed canopy (current assumption: 2% of walked distance). Will be measured post-implementation.
2. Actual cruiser alignment accuracy of the DBH guide line to the true 1.37 m point (hypothesis: within ±3 cm, similar to Biltmore stick). Will be measured post-implementation.
3. Actual iPhone LiDAR depth noise under forest lighting (spec says ~5 mm; to be confirmed by wall calibration in field).
4. LiDAR reliability in rain/fog (adjust UX messaging based on field experience).
5. Tracking robustness of ARKit in feature-poor evergreen canopy (may require falling back to manual tape for height measurement more often than planned).

These will drive tuning of:
- `vioDriftFraction` default
- DBH confidence thresholds
- User-facing messaging during degraded conditions

---

## §13 TL;DR for the LLM implementer

1. Start with Phase 0. Do not touch sensors or UI until `InventoryEngine/` passes all tests.
2. Every requirement has an ID. Work through them in order within a phase.
3. Every algorithm has a typed contract. Do not invent new inputs or outputs; extend the existing types if needed and flag it.
4. DBH uses a **fixed horizontal guide line** at `y = screen_height / 2`. The line never moves. The cruiser moves the phone to align the line with the DBH point on the trunk. The algorithm only looks at the depth pixels on that exact image row (§7.1 Step 3).
5. Height uses the VIO walk-off formula `H = d_h · (tan α_top − tan α_base)`. The phone's eye-level is not needed; it cancels algebraically.
6. Every measurement returns a `ConfidenceTier`. Red tier must include a human-readable rejection reason.
7. `InventoryEngine/` has zero imports from `Sensors/`, `AR/`, `Positioning/`. Keep it pure.
