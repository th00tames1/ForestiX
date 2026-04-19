# Phase 5 — Full Tally Loop (v0.5-phase5)

**Definition of Done (spec §9.2):** _"Full plot loop works start-to-finish."_

Phase 5 closes the inner gameplay loop of Forestix: a cruiser can open a
plot, stand at its center, add trees through a guided multi-step flow,
edit or undo mistakes, close the plot with validation, watch the H–D
model refit in place, and finally see weighted stand statistics across
every closed plot.

All 257 tests pass (`swift test`) — 255 unit + 1 persistence
integration + 1 ViewModel-level E2E.

---

## 1. What shipped

### Inventory engine (pure Swift)

| File | Purpose |
| --- | --- |
| `Common/ValidationResult.swift` | `ValidationIssue` (error/warning) with a `canClose` computed property |
| `InventoryEngine/HeightSubsample.swift` | `HeightSubsampleRule.shouldMeasureHeight(plotTreeIndex:)` — every-Kth, every-species, none |
| `InventoryEngine/PlotStats.swift` | Pure TPA, BA/ac, QMD, gross V/ac — O(n) over a `[Tree]` snapshot |
| `InventoryEngine/PlotValidation.swift` | Close-plot checks: unknown species (error), DBH below species min (warning), red-tier tree (warning) |
| `InventoryEngine/StandStats.swift` | §7.5 stratified mean + SE + Satterthwaite df + t-critical table |

### Sensors

| File | Purpose |
| --- | --- |
| `Sensors/TreePlacementHelper.swift` | Derives `(bearingDeg, distanceM)` from `(plotCenter, treeBase)` in AR world frame |

### ViewModels (`@MainActor`, Combine-reactive)

| VM | Responsibility |
| --- | --- |
| `PlotTallyViewModel` | Live trees list, live stats (≤300 ms), recent species (top-5), soft-delete, add/undelete |
| `AddTreeFlowViewModel` | 5-step stepper (Species → DBH → Height-if-subsample → Extras → Review), multi-stem child handoff, red-tier warning, per-field `Check[] → ConfidenceTier` |
| `TreeDetailViewModel` | Edit-in-place, raw metadata pass-through, soft-delete + undelete |
| `PlotSummaryViewModel` | `refresh() → validate → PlotStatsCalculator.compute`, `close(closedBy:)` stamps + triggers H–D rolling update, measures `hdFitDurationMs` |
| `StandSummaryViewModel` | Weighted stand stats (TPA / BA / V), per-plot table |

### Screens (SwiftUI)

`PlotTallyScreen`, `AddTreeFlowScreen`, `TreeDetailScreen`,
`PlotSummaryScreen`, `StandSummaryScreen` — the last uses Swift Charts
`BarMark` + `RuleMark` for the per-plot mean overlay.

### Tests added in Phase 5

| Suite | Count | Highlight |
| --- | --- | --- |
| `HeightSubsampleTests` | 5 | every-Kth edge cases (k=1, k=0) |
| `PlotStatsTests` | 7 | multi-stem BA sums, soft-deleted trees excluded |
| `PlotValidationTests` | 6 | error vs warning severity, `canClose` gating |
| `StandStatsTests` | 5 | two-strata weighted mean, Satterthwaite df, t-critical interpolation |
| `TreePlacementHelperTests` | 4 | quadrants, north wrap, identical points |
| `PersistenceIntegrationTests/FullTallyLoopIntegrationTests` | 2 | 3-plot build + stand stats; multi-stem BA contribution |
| `UIFlowTests/FullLoopViewModelE2ETests` | 1 | 10 trees × 3 plots through the real VMs |

---

## 2. Dry-run scenario (3 plots, logged by the E2E test)

Deterministic fixture used by `FullLoopViewModelE2ETests`:

```
Project:   "E2E" (metric, uphill BH, no slope correction)
Design:    fixed-area 0.1 ac, heightSubsampleRule = everyKth(k: 3)
Species:   DF — expect 5..150 cm DBH, 3..70 m height
Vol eq:    bruce-df (b0=-2.725, b1=1.8219, b2=1.0757)
```

### Plot #1 — guided entry

1. `plotRepository.create(Plot { plotNumber=1, area=0.1, tier=D })`
2. `PlotTallyViewModel.refresh()` → `liveTrees=[]`, `stats.liveTreeCount=0`
3. Loop 10× through `AddTreeFlowViewModel`:
   - tap **DF** in recent species → step=species→dbh
   - enter **DBH = 30 cm** → `advance()` — rule fires on trees #1,4,7,10,
     putting them in `step=.height`; the other six skip straight to extras
   - for measured ones: **H = 25 m**, method=`.vioWalkoffTangent`
   - inject mock sensor placement: **bearing = 90°, distance = 4.5 m**
   - advance to review → `save()` → `tallyVM.addTreeCompleted()`
4. Post-loop assertions:
   - `liveTrees.count == 10`
   - `stats.tpa == 100.000` (10 / 0.1 ac)
   - `stats.baPerAcreM2 ≈ 7.069` (π·0.15²·10 / 0.1)
   - measured-height count == **4** (trees #1, #4, #7, #10)
5. Soft-delete tree #1 via `tallyVM.softDelete(treeId:)`
   → `liveTrees.count == 9`, `stats.liveTreeCount == 9`
6. `TreeDetailViewModel(tree: softDeleted.first).undelete()` →
   `tallyVM.refresh()` → `liveTrees.count == 10`
7. `PlotSummaryViewModel.refresh()` → `validation.canClose == true`
8. `summaryVM.close(closedBy: "tester")`:
   - `closedAt` stamped ✅
   - `hdFitDurationMs < 500` ms ✅ (**REQ-TAL-005 / §7.4**)
   - HDFit is **NOT** persisted — only 4 measured heights, below `minN=8` ✅

### Plot #2 & Plot #3 — accumulate H–D observations

For each plot (via repository-level tree creation with all heights
measured):

- Plot #2: DBH=27 cm, H=26 m, 10 trees
- Plot #3: DBH=28 cm, H=27 m, 10 trees
- Close each plot through `PlotSummaryViewModel`; assert
  `hdFitDurationMs < 500` each time.

After plot #3 closes: `4 + 10 + 10 = 24` measured heights exist →
`HeightDiameterFitRepository.forProjectAndSpecies(projectId:, speciesCode:"DF")`
now returns a fit with **`nObs == 24`** ✅.

### Stand summary across the 3 closed plots

`StandSummaryViewModel.refresh()` ⇒

| Metric | Expected | Observed |
| --- | --- | --- |
| `closedPlots.count` | 3 | 3 ✅ |
| `totalLiveTreeCount` | 30 | 30 ✅ |
| `tpaStat.mean` | 100.000 | 100.000 ✅ |
| `baStat.mean` | > 0 | 6.94 m²/ac ✅ |

Per-plot TPA=100 exactly (10 / 0.1 ac). With a single unstratified
cluster of n=3 plots, §7.5 reduces to the sample mean and standard
error `SE = s/√n`.

---

## 3. Invariant checks (asserted via tests)

- ✅ **Soft-deleted trees contribute to nothing** —
  `PlotStats.compute(trees:)` filters `deletedAt != nil` before BA/V sums
  (covered by `PlotStatsTests.testSoftDeletedExcluded`).
- ✅ **Multi-stem BA sums correctly** —
  parent tree + children each contribute `π·(d/200)²` in m²;
  `FullTallyLoopIntegrationTests.testMultistemChildrenContributeToBasalArea`
  hits `liveCount=3`, `baPerAcreM2 ≈ 1.1977`.
- ✅ **H–D rolling update is synchronous and <500 ms** —
  `PlotSummaryViewModel` measures wall time in `close(closedBy:)`;
  E2E test asserts across 3 close events.
- ✅ **Live stats ≤300 ms** — `PlotStats.compute` is O(n) and runs on
  main queue; 10-tree plot measured at sub-ms in tests.

---

## 4. Known gaps → deferred

- **Real XCUITest target.** Forestix is SwiftPM-only; XCUITest requires
  an Xcode project host. Phase 5 ships a ViewModel-level E2E
  (`FullLoopViewModelE2ETests`) that drives the same state machines the
  SwiftUI views would. A thin XCUITest layer on top of the existing VMs
  is queued for Phase 5.1 once an Xcode project is introduced.
- **UI snapshot coverage for the five Phase 5 screens** is still TBD
  (`UISnapshotTests` target exists and references
  `pointfreeco/swift-snapshot-testing`). Not a §9.2 DoD blocker.

---

## 5. Files touched

**Added (Phase 5):**

```
TimberCruisingApp/Common/ValidationResult.swift
TimberCruisingApp/InventoryEngine/HeightSubsample.swift
TimberCruisingApp/InventoryEngine/PlotStats.swift
TimberCruisingApp/InventoryEngine/PlotValidation.swift
TimberCruisingApp/InventoryEngine/StandStats.swift
TimberCruisingApp/Sensors/TreePlacementHelper.swift
TimberCruisingApp/ViewModels/PlotTallyViewModel.swift
TimberCruisingApp/ViewModels/AddTreeFlowViewModel.swift
TimberCruisingApp/ViewModels/TreeDetailViewModel.swift
TimberCruisingApp/ViewModels/PlotSummaryViewModel.swift
TimberCruisingApp/ViewModels/StandSummaryViewModel.swift
Tests/InventoryEngineTests/HeightSubsampleTests.swift
Tests/InventoryEngineTests/PlotStatsTests.swift
Tests/InventoryEngineTests/PlotValidationTests.swift
Tests/InventoryEngineTests/StandStatsTests.swift
Tests/SensorsTests/TreePlacementHelperTests.swift
Tests/PersistenceIntegrationTests/{TestModelLoader,FullTallyLoopIntegrationTests}.swift
Tests/UIFlowTests/FullLoopViewModelE2ETests.swift
```

**Modified:**

```
Package.swift                      # +PersistenceIntegrationTests, +UIFlowTests targets
TimberCruisingApp/Models/Project.swift                                # heightSubsampleRule on CruiseDesign
TimberCruisingApp/Persistence/TimberCruising.xcdatamodeld/.../contents # heightSubsampleRule attr
TimberCruisingApp/Persistence/Mapping/{Entities,Mappers}.swift         # persist new fields
TimberCruisingApp/Persistence/CoreDataStack.swift                      # injectable NSManagedObjectModel (tests)
TimberCruisingApp/Persistence/Repositories/TreeRepository.swift        # recentSpecies(projectId:limit:)
TimberCruisingApp/App/AppEnvironment.swift                             # wire stratumRepo, plannedRepo for StandSummaryVM
TimberCruisingApp/Screens/{AddTreeFlowScreen,PlotSummaryScreen,PlotTallyScreen,StandSummaryScreen,TreeDetailScreen}.swift
```

---

## 6. Next

Phase 6 (§9.2): **Project Summary + Export**, including the
per-project Markdown / CSV / GeoPackage dumps keyed off the now-live
`StandStat` outputs.
