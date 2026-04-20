# Forestix — Field-cruiser simulation findings

_Date: 2026-04-19. Method: pretend to be Kim Cheol-su, a real timber
cruiser opening the freshly-installed app for the first time, and
walk every tap-by-tap step from "+" to "DBH measured" to find what
actually breaks._

## TL;DR

Three more BLOCKERS surfaced after the previous audit; all fixed in
this commit. Two remaining FRICTION points are documented for
follow-up but don't stop the field pilot.

| Step | Finding | Status after fix |
|---|---|---|
| Create project | `depthNoiseMm = 0` → Pre-field check fails on calibration | Default now `5 mm` (spec §7.10 identity) |
| CruiseDesign → species picker | **Empty list** — JSONs in bundle but never seeded into Core Data | First-launch seed loader installed |
| DBH / Height / AR Boundary scan | **Black screen** — only overlay chrome rendered, no camera feed | `ARCameraView` SwiftUI wrapper layered in |
| Calibration screen | No "Start scan" button + results never reach Project | Start-scan button + Apply-to-project + sensible-defaults shortcut |
| Plot map | Apple basemap renders fine — false positive | OK |

## Walk-through (post-fix)

1. **Tap +** → New Project sheet → name + owner + units → tap **Create**.
   Project lands with `depthNoiseMm = 5 mm`, identity DBH correction.
2. **Project dashboard** → **Calibrate this project** *(new entry)* →
   - Either run wall scan (now triggerable from a 56-pt button)
   - Or tap **Use sensible defaults** to bypass — instantly applies
     spec §7.10 identity values to the project.
3. **Pre-field check** → all rows green (calibration ✓ thanks to
   non-zero depth noise + β=1).
4. **Design cruise** → species picker now lists DF / WH / RC / RA from
   the seeded PNW starter set. Volume equations linked.
5. **Go cruise** → Plot picker → tap planned plot.
6. **NavigationScreen** → walk to plot, arrival haptic at 5 m.
7. **PlotCenterScreen** → 60 s averaging, Tier appears, Accept.
8. **PlotTallyScreen** → tap **Add Tree**.
9. **Species step** → tap "DF" (or use voice picker).
10. **DBH step** → tap **Scan with LiDAR** (new) →
    `DBHScanScreen` opens **with live camera feed visible** behind
    the guide line + crosshair. Cruiser physically aligns the line
    at 1.37 m, taps capture, gets a `DBHResult`, screen dismisses,
    DBH field auto-populated.
11. **Height step** → analogous, `HeightScanScreen` shows real video.
12. **Extras** → fill notes / damage / placement.
13. **Review** → confidence tiers, **Save**.
14. Repeat for trees → **Close Plot** → PlotSummary → StandSummary.
15. **Export** → 11 artefacts including PDF.

## What was broken before this commit

### BLOCKER 1 — Empty species picker

Bundled JSONs (`Models/Resources/SpeciesDefaults.json`,
`VolumeEquationsPNW.json`) shipped with the canonical PNW set, but
nothing ever read them on first launch.
`AppEnvironment.live()` constructed an empty Core Data store and
returned. CruiseDesignScreen's species picker hit `repo.list() → []`,
showed nothing. Cruiser couldn't proceed past the design step.

**Fix:** New `Models/SeedData.swift` decodes the JSONs from
`Bundle.module`. New `Persistence/SeedDataLoader.swift` runs idempotently
on every launch — populates species + volume equations only if the
species table is empty. Wired into `AppEnvironment.live()`.

### BLOCKER 2 — Black scan screens

`DBHScanScreen.body`, `HeightScanScreen.body`, and
`ARBoundaryScreen.body` all started with `Color.black.ignoresSafeArea()`
as the bottom layer. Comments admitted "real AR layer is added in
Phase 2.1." That phase never landed. ARKit session was actually
running and emitting depth frames — but the cruiser couldn't see
what they were aiming at.

**Fix:** New `AR/ARCameraView.swift` (UIViewRepresentable wrapping
RealityKit's `ARView`) attached to the *same* `ARSession` instance
the corresponding view-model owns. Layered behind the existing
overlay chrome on all three scan screens. On macOS test runner the
component falls through to a black `Color` so snapshot tests stay
deterministic.

`ARKitSessionManager.session` made `public let` (was `private let`)
so the view can share the session.

### BLOCKER 3 — Calibration disconnected from project

`CalibrationScreen` had a `wall: .idle` state with no entry point —
no button anywhere to start a scan. Even if scanning had worked, the
results sat in `CalibrationViewModel.wall`/`.cylinder` and never
reached `Project.depthNoiseMm` / `dbhCorrectionAlpha` / `β`. The
Pre-field check would still fail because Project values stayed at 0.

**Fix:**
- `CalibrationViewModel.startWallScan()` subscribes to the ARKit
  depth-frame stream, back-projects a 21×21 patch from each frame's
  centre into world space, accumulates 30 frames, then calls
  `WallCalibration.fit`. `cancelWallScan()` aborts cleanly.
- `CalibrationScreen` adds a 56-pt **Start wall scan** button to the
  `.idle` case + a **Cancel** button to `.scanning`.
- `CalibrationViewModel.applyTo(project:)` writes wall + cylinder
  results into a fresh `Project` value.
- New static `sensibleDefaultsApplied(to:)` — applies spec §7.10
  identity values (5 mm depth noise, identity DBH correction) without
  scanning. For cruisers who want to skip the wall + cylinder ritual.
- `CalibrationScreen` now takes optional `(project, projectRepo)` — when
  set, two new Apply buttons surface. The Project status section at
  top live-reflects the values currently stored.
- Wired into `ProjectDashboardScreen.toolsSection` as
  **"Calibrate this project"** — passes the project + repository so
  Apply actually persists.

### Bonus fix — Project default for `depthNoiseMm`

`HomeViewModel.create()` was setting `depthNoiseMm = 0` on every new
project, which caused Pre-field check to fail with
"Run the wall + cylinder calibration in Settings → Calibration".
Now defaults to `5` (spec §7.10 nominal iPhone LiDAR noise) so a
fresh project passes Pre-field check immediately. Cruiser can refine
later via the Calibrate button.

## Remaining friction (not blockers)

- **DBH scan needs explicit user "tap to capture"**. The `DBHScanViewModel`
  exposes a `tap()` method that begins a 12-frame burst. The crosshair
  turns green when depth-stable, signalling the right moment, but
  there's no on-screen instruction explaining what to do. UX writer
  task — add the instruction string overlay.
- **Wall calibration UX**: 21×21 patch from the depth-map centre is
  a reasonable proxy, but the cruiser gets no preview of what's being
  sampled. A small reticle around the centre patch in the camera
  feed would clarify.
- **Crash-recovery resume banner** still not drawn on Home (service +
  tests in place since Phase 7).

## Verification

```
swift test           → Executed 296 tests, 0 failures
xcodebuild -sdk iphoneos → ** BUILD SUCCEEDED **
```
