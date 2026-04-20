# Abstract ↔ Implementation coverage

This note documents, claim-by-claim, how the submitted abstract maps
onto the Forestix codebase as of the `v0.7` branch + follow-up
commits.

## The abstract (as submitted)

> Timber cruising remains essential for stand-level planning and
> harvesting decisions, but conventional field inventory is
> labor-intensive and often relies on manual measurements. Furthermore,
> data collection in the field is susceptible to random errors arising
> from individual judgment and operator-dependent decisions, which may
> affect measurement consistency and overall inventory reliability.
> Recently, sensor-equipped smartphones have created new opportunities
> for tree measurement; however, most existing applications focus
> primarily on individual tree attributes rather than the broader
> workflow needed for operational timber cruising. This study presents
> the development and evaluation of a smartphone-based timber cruising
> workflow that integrates sensor-based tree measurement with automated
> inventory summaries. **The proposed application is designed to support
> plot navigation, tapeless fixed-area plot establishment with boundary
> visualization, diameter at breast height (DBH) estimation, and tree
> height estimation.** Using these inputs together with local volume
> equations, the app automatically calculates plot- and stand-level
> metrics such as trees per acre, basal area, quadratic mean diameter,
> and volume. The system is intended for offline field use and allows
> users to review and edit measurements during data collection. The
> workflow will be evaluated under field conditions by comparing
> smartphone-derived inventory results with conventional cruise data
> collected using standard forestry instruments. Performance will be
> assessed at both tree and plot levels, with emphasis on measurement
> accuracy, agreement in stand-level metrics, volume estimation, and
> field efficiency. This work presents a practical approach for
> digital timber cruising by combining smartphone-based sensing
> capabilities with conventional cruising workflows to improve data
> management efficiency.

## Claim-by-claim mapping

| # | Abstract claim | Implementation | Code anchor |
|---|---|---|---|
| 1 | sensor-based tree measurement integrated with automated inventory summaries | ✅ full loop shipped | `Sensors/DBHEstimator.swift`, `Sensors/HeightEstimator.swift`, `InventoryEngine/PlotStats.swift`, `InventoryEngine/StandStats.swift` |
| 2 | plot navigation | ✅ compass + distance + GPS-tier badge + arrival haptic | `Screens/NavigationScreen.swift`, `Positioning/LocationService.swift` |
| 3 | tapeless fixed-area plot establishment | ✅ GPS averaging + offset-from-opening | `Positioning/GPSAveraging.swift`, `Positioning/OffsetFromOpening.swift`, `Screens/PlotCenterScreen.swift` |
| 4 | with boundary visualization | ✅ **fixed this commit** — RealityKit ring now renders in the AR scene | `Screens/ARBoundarySceneView.swift`, `AR/PlotBoundaryRenderer.swift` |
| 5 | DBH estimation | ✅ fixed guide line + RANSAC + Taubin + calibration | `Sensors/DBHEstimator.swift`, `Sensors/CircleFit/*.swift` |
| 6 | tree height estimation | ✅ VIO walk-off tangent `H = d_h · (tan α_top − tan α_base)` | `Sensors/HeightEstimator.swift` |
| 7 | local volume equations | ⚠️ framework complete, **coefficients are PLACEHOLDERS** — documented in [VOLUME_EQUATIONS.md](VOLUME_EQUATIONS.md) | `InventoryEngine/VolumeEquations/*.swift`, `Models/Resources/VolumeEquationsPNW.json` |
| 8 | TPA / BA / QMD / volume | ✅ per-plot + §7.5 stratified stand mean + SE + CI95 | `InventoryEngine/PlotStats.swift`, `InventoryEngine/StandStats.swift` |
| 9 | offline field use | ✅ Core Data sqlite + offline tile cache + on-device Speech + local-only analytics | `Persistence/CoreDataStack.swift`, `Basemap/OfflineBasemap.swift`, `Common/VoiceRecognizer.swift`, `Common/ForestixLogger.swift` |
| 10 | review and edit measurements during collection | ✅ swipe-edit, soft-delete + undelete | `Screens/PlotTallyScreen.swift`, `Screens/TreeDetailScreen.swift` |
| 11 | field evaluation vs conventional cruise data | 🕐 future work — protocol in [FIELD_PILOT.md](FIELD_PILOT.md) | — |
| 12 | tree-level + plot-level accuracy assessment | 🕐 future work — protocol in [FIELD_PILOT.md](FIELD_PILOT.md) §4–7 | — |

## What this commit adds to strengthen the abstract

Three gaps flagged in the Option-C audit:

### (a) AR boundary visualisation

Before: `ARBoundaryScreen` rendered a black background + a target
reticle — the 72-vertex ring existed in `ARBoundaryViewModel.ringVertices`
but **nothing drew it** on the RealityKit scene.

After: new [Screens/ARBoundarySceneView.swift](../TimberCruisingApp/Screens/ARBoundarySceneView.swift)
is a `UIViewRepresentable` that:
- attaches an `ARView` to the same `ARSession` the VM is already
  running,
- subscribes to `ringVertices` / `centerWorld`,
- builds a `ModelEntity` via `PlotBoundaryRenderer.makeRingEntity` in
  an anchor-local frame, and
- re-anchors it in the AR scene whenever the data changes.

Cruiser now sees the 11.28 m fixed-area ring overlaid on the ground,
anchored at the plot centre they tapped. Borderline-tree judgement
(§7.8) becomes visual instead of numeric.

### (b) Species + volume-equation visibility on CruiseDesign

Before: `CruiseDesignScreen` let the cruiser pick plot type + sampling
scheme but gave **zero visibility into which species + equations
would be used** — the PNW seed was loaded but invisible from the
design screen.

After: new **Species & volume equations** section on CruiseDesignScreen
lists every seeded species with:
- code + common name + scientific name
- the linked equation's form (`bruce`, `chambers_foltz`, etc.)
- a 🟧 **placeholder** badge vs. 🟢 **verified** badge depending on
  whether the equation's `sourceCitation` contains the word
  `PLACEHOLDER`.
- an explicit 🔴 row when a species points at a missing equation id.

`CruiseDesignViewModel.availableSpecies` + `volumeEquationsById` are
now loaded on `refresh()`.

### (c) Volume-equation placeholder documentation

New [docs/VOLUME_EQUATIONS.md](VOLUME_EQUATIONS.md):
- Lists the four implemented equation forms.
- Flags each seeded PNW coefficient set as ⚠️ placeholder.
- Explains *why* (research-design transparency — coefficients are a
  pilot-output, not a pilot-input).
- Gives two replacement paths (edit JSON + reinstall; runtime update
  via the repository).
- Lists primary-source recommendations (Bruce & DeMars 1974,
  Chambers & Foltz 1979, Curtis-Herman-DeMars 1974).
- Defines a quality gate before the 20-plot pilot.

The thesis / paper can cite this doc when describing the volume-engine
design without overclaiming coefficient accuracy.

## Remaining honest caveats for the paper

1. **No real-device validation yet.** All 296 tests pass against
   synthetic ARKit fixtures. No iPhone has run the scan pipeline end-
   to-end under canopy. This is literally the pilot the abstract
   promises ("will be evaluated under field conditions").
2. **Volume coefficients are placeholders.** The abstract's "using
   these inputs together with local volume equations" is true at
   the engine / schema level but the numbers it produces today are
   not quantitatively trustworthy until step #2 of
   [VOLUME_EQUATIONS.md](VOLUME_EQUATIONS.md)'s quality gate is done.
3. **Species catalogue is read-only in-app.** Cruisers can see the
   seeded PNW set but can't add / edit / delete species from the UI
   yet. For a research project shipping with the PNW set hard-coded
   is fine; for a commercial release this is Phase 8 work.
4. **DBH / Height scans still need a clearer "tap to capture"
   on-screen instruction** — see `docs/USER_SIM_FINDINGS.md` §
   "Remaining friction".

## Verification

```
swift test                         → Executed 296 tests, 0 failures
xcodebuild -sdk iphoneos           → ** BUILD SUCCEEDED **
```
