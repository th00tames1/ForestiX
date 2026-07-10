# Volume equations in Forestix

_This note explains the status of the volume-equation coefficients that
ship with the app and how to replace them for a production cruise._

## Status

Forestix implements four volume-equation **forms** (spec §7.7):

| Form id | Implementation | File |
|---|---|---|
| `bruce` | Bruce log-log DBH + H | [BruceDouglasFir.swift](../TimberCruisingApp/InventoryEngine/VolumeEquations/BruceDouglasFir.swift) |
| `chambers_foltz` | Chambers-Foltz log-log | [ChambersFoltzHemlock.swift](../TimberCruisingApp/InventoryEngine/VolumeEquations/ChambersFoltzHemlock.swift) |
| `schumacher_hall` | generic Schumacher-Hall | [SchumacherHall.swift](../TimberCruisingApp/InventoryEngine/VolumeEquations/SchumacherHall.swift) |
| `table_lookup` | bilinear-interpolated DBH × H lookup | [TableLookup.swift](../TimberCruisingApp/InventoryEngine/VolumeEquations/TableLookup.swift) |

The app ships a Pacific-Northwest starter set seeded on first launch
from [VolumeEquationsPNW.json](../TimberCruisingApp/Models/Resources/VolumeEquationsPNW.json):

| Equation id | Form | Purpose | Status |
|---|---|---|---|
| `bruce-df-pnw` | bruce | Douglas-fir | ⚠️ placeholder coefficients |
| `chambers-foltz-wh-pnw` | chambers_foltz | western hemlock | ⚠️ placeholder coefficients |
| `schumacher-hall-rc-placeholder` | schumacher_hall | western redcedar | ⚠️ placeholder coefficients |
| `schumacher-hall-ra-placeholder` | schumacher_hall | red alder | ⚠️ placeholder coefficients |

> **Every** coefficient set marked with the word `PLACEHOLDER` in its
> `sourceCitation` has not been verified against a primary-source
> publication. They are numerical shape-holders chosen to produce
> mathematically well-behaved values in the range of reasonable PNW
> DBH / height combinations, **not** quantitatively correct volumes.

The Cruise Design screen surfaces a 🟧 placeholder badge next to any
equation whose citation contains the word `PLACEHOLDER`, so cruisers
are visually reminded before trusting the output.

## Why placeholders?

Forestix is engineered so the volume engine is pluggable: swapping in a
verified coefficient set is a pure data edit, no code change. The
thesis and field-pilot work scope includes **calibration of these
coefficients** as one of its evaluation outputs (see the abstract:
_"Performance will be assessed at both tree and plot levels, with
emphasis on measurement accuracy, agreement in stand-level metrics,
volume estimation, and field efficiency."_). Shipping verified
coefficients before the pilot runs would bake in an assumption the
pilot is meant to validate.

## How to replace coefficients for production use

### Option 1 — Edit the seed JSON and reinstall

1. Open [VolumeEquationsPNW.json](../TimberCruisingApp/Models/Resources/VolumeEquationsPNW.json).
2. For each equation, replace the `coefficients` dictionary with the
   values published for your species / region. Update
   `sourceCitation` to the primary reference (remove the word
   `PLACEHOLDER`; the CruiseDesign screen then switches the badge to
   "verified").
3. Rebuild + re-install. The seed loader runs only on first launch
   (the species table must be empty), so either:
   - Wipe existing Forestix data via **Settings → Danger zone →
     Erase all Forestix data**, or
   - Edit the values live via **Settings → Diagnostics** (future
     Phase 8.x work — currently there is no in-app equation editor).

### Option 2 — Override at runtime via API

The same JSON shape is `Decodable`, so any internal tooling can
populate the `VolumeEquationRepository` directly:

```swift
let seed = try SeedData.bundledVolumeEquations()   // current snapshot
for eq in seed { _ = try volRepo.update(eq) }       // or create
```

## Recommended primary sources (to replace the placeholders)

- **Douglas-fir (Bruce form)** — Bruce & DeMars (1974), *Volume Equations
  for Second-Growth Douglas-fir*, USDA Forest Service Res. Note
  PNW-239; also Flewelling & McFadden (1986) for the
  Bruce-with-breast-height refinement.
- **Western hemlock (Chambers & Foltz)** — Chambers & Foltz (1979),
  *The 1979 Western Hemlock Volume Equation*.
- **Western redcedar** — BLM Western Oregon cubic-foot form
  (generic Schumacher-Hall refit); no widely adopted bruce-style
  publication exists, so a locally-calibrated Schumacher-Hall is
  recommended.
- **Red alder** — Curtis, Herman, & DeMars (1974), *Height Growth and
  Site Index for Red Alder*.

## Editable per species

While the generic `merchFraction = 0.85` is used in the placeholder
set, production cruises should compute merchantable volume from the
stump-to-top-DIB formula when the equation supports it
(`merchantableVolumeM3(dbhCm:heightM:topDibCm:stumpHeightCm:)` on the
`VolumeEquation` protocol is already the primary API; the fraction
shortcut is a fallback).

## Quality gate before the thesis field pilot

Before the 20-plot pilot in [FIELD_PILOT.md](FIELD_PILOT.md) starts
producing numbers anyone should trust, the following must be checked
off:

- [ ] Replace every `PLACEHOLDER` citation with a primary-source
      coefficient set for the target region.
- [ ] On a handful of known-volume trees (e.g. bucked logs measured
      with Smalian or Huber), hand-verify that the app's
      `totalVolumeM3` falls within ±5 % of the hand-computed value.
- [ ] Re-run [GoldenFileTests](../Tests/ExportTests/GoldenFileTests.swift)
      after any coefficient change (new hashes expected) and update
      the `Golden.*` constants.
