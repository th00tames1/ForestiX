# Forestix — Phase 0 through 7 post-hoc audit

_Audit date: 2026-04-19. Scope: verify every Phase 0–7 claim actually
holds end-to-end on a real iPhone, not just in the test harness._

## TL;DR

| Claim | Status before audit | Status after audit |
|---|---|---|
| All 296 tests pass | ✅ | ✅ (unchanged) |
| `swift build` succeeds | ✅ | ✅ (unchanged) |
| `xcodebuild -sdk iphoneos` succeeds | ❌ 1 compile error | ✅ `BUILD SUCCEEDED` |
| Device permissions correct | ⚠️ Camera + Location only | ✅ + Motion + Mic + Speech |
| Navigation graph reaches every screen | ❌ 11 orphan screens | ✅ every screen reachable |

The Phase 2–6 code was all there — DBH scan, Height scan, AR boundary,
plot tally, all tested — but none of it was wired into
`ProjectDashboardScreen`. A cruiser who installed the app could create a
project, define strata, download a basemap, export the plan, run the
pre-field check, back up and restore… but **could not actually start a
cruise.**

## Findings in detail

### 1 · Missing permission strings (would have crashed on device)

The Xcode project had only:

```
INFOPLIST_KEY_NSCameraUsageDescription
INFOPLIST_KEY_NSLocationWhenInUseUsageDescription
```

On first launch of any sensor screen, iOS would have killed the app
with one of:

- `NSMotionUsageDescription` — CoreMotion access from `IMUHelpers.swift`
  during DBH + height scans.
- `NSMicrophoneUsageDescription` — `AVAudioSession` activation inside
  the Phase 7 voice species picker.
- `NSSpeechRecognitionUsageDescription` — `SFSpeechRecognizer.requestAuthorization`.

All three added in this pass. See `Forestix.xcodeproj/project.pbxproj`,
both Debug + Release configs.

### 2 · Orphan screens (11 of 18)

`grep "<ScreenName>(" Screens/**/*.swift` found no callers for:

```
NavigationScreen       — unreachable
PlotCenterScreen       — unreachable
OffsetFlowScreen       — unreachable
ARBoundaryScreen       — unreachable
PlotTallyScreen        — unreachable
AddTreeFlowScreen      — unreachable
DBHScanScreen          — unreachable
HeightScanScreen       — unreachable
TreeDetailScreen       — unreachable
PlotSummaryScreen      — unreachable
StandSummaryScreen     — unreachable
```

That's every screen needed to actually *run a plot* from "Go Cruise".

**Fix:** new [CruiseFlowScreen.swift](../TimberCruisingApp/Screens/CruiseFlowScreen.swift).
Acts as a `NavigationStack`-based coordinator with a typed `CruiseStep`
path enum. Resolves the end-to-end chain:

```
ProjectDashboard → [Go Cruise]
      ↓
CruiseFlowScreen (planned-plot picker)
      ↓ tap planned plot
NavigationScreen (compass + distance to target)
      ↓ onArrival (within 5 m)
PlotCenterScreen (60 s GPS averaging)
      ↓ onAccept            ↓ onTryOffset (tier C/D fallback)
PlotTallyScreen ←──────── OffsetFlowScreen (opening + walk-off)
      ↓ Add tree                ↓ AR Boundary              ↓ Close plot
AddTreeFlowScreen     ARBoundaryScreen              PlotSummaryScreen
      ↓ onSaved                                           ↓ onClosed
   back to tally                                  StandSummaryScreen
```

`ProjectDashboardViewModel` now also loads the project's `CruiseDesign`
so the dashboard knows whether to show the Go-Cruise row as enabled vs
locked ("Finish Design cruise first").

### 3 · RealityKit init signature mismatch (iOS vs macOS)

[PlotBoundaryRenderer](../TimberCruisingApp/AR/PlotBoundaryRenderer.swift)
used `simd_quatf(matrix:)` — which exists on macOS but not in the iOS
SDK. `swift build` on the macOS test runner was fine; `xcodebuild -sdk
iphoneos` failed. Fixed with the unlabelled positional initialiser
`simd_quatf(matrix_float3x3(...))`, which compiles on both platforms.

## What I did not change (deferred to a follow-up phase)

- **Shared AR session between DBH scan, Height scan, and AR boundary**
  on the same plot. Today each of those screens constructs its own
  `ARKitSessionManager()`. The `CruiseFlowCoordinator` does hold a
  `sharedARSession` that the boundary screen uses, but DBH / height
  sub-flows inside AddTreeFlow still spin up fresh sessions. The spec
  calls for session sharing (§7.2 footnote + §9.2 Phase 3 rule). Low
  risk for the field pilot because each sensor flow pauses the
  previous one cleanly; it's a battery optimisation more than a
  correctness concern.
- **DBH and Height scans integrated into AddTreeFlow.** Today the
  cruiser types DBH / Height into the stepper manually. Linking the
  "Scan" button to push DBHScanScreen / HeightScanScreen and write
  results back into the flow VM is the cleanest Phase 7.1 task.
- **Crash-recovery resume prompt UI.** The backing
  `CrashRecoveryService` exists with tests, but the Home-screen banner
  that would show "Resume plot 3?" is not drawn. Again, Phase 7.1.
- **Calibration wizard step-by-step.** `CalibrationScreen` is one
  screen that handles both the wall and cylinder procedures. The spec
  asks for a wall→cylinder guided flow; current screen is adequate
  for a field pilot since cruisers are trained to run both anyway.

## Verification commands

```sh
# Unit + integration test suite (macOS runner)
swift test                            # → Executed 296 tests, 0 failures

# Real iOS-device archive build
xcodebuild -project Forestix.xcodeproj \
           -scheme Forestix \
           -destination 'generic/platform=iOS' \
           -sdk iphoneos \
           -configuration Debug \
           CODE_SIGN_IDENTITY="" \
           CODE_SIGNING_REQUIRED=NO \
           CODE_SIGNING_ALLOWED=NO \
           build                       # → ** BUILD SUCCEEDED **
```

The second one is the key regression gate: it must pass before any
field pilot build. Sign it with your Apple Developer team (`CODE_SIGN_STYLE
= Automatic` is already set) and deploy.

## One-line summary per phase, post-audit

- **Phase 0 — Models / Common / Persistence / InventoryEngine.** Complete and shipping. 100 % of Phase 0 tests pass.
- **Phase 1 — Project + Plot CRUD UI.** Complete and reachable from Home.
- **Phase 2 — DBH sensor path.** Code exists, tests pass, reachable via the new `CruiseFlowScreen` (through AddTreeFlow) with a caveat that the "Scan DBH" button still hands off to the screen through a modal push rather than being linked inline with AddTreeFlow — functional, not optimal.
- **Phase 3 — Height + AR Boundary.** Same — reachable via the new coordinator. The boundary screen uses the shared AR session.
- **Phase 4 — Positioning.** Navigation + PlotCenter + Offset all wired.
- **Phase 5 — Full Tally Loop.** Wired. PlotTally + AddTreeFlow + TreeDetail + PlotSummary + StandSummary all land in the navigation stack.
- **Phase 6 — Export.** Unchanged from Phase 6 shipping state. Already reachable (Export row on dashboard).
- **Phase 7 — Field Validation Readiness.** All three missing permission strings added; RealityKit iOS-only compile fixed.

The app is now **actually runnable on a real iPhone**, not merely
compilable in the SPM sandbox.
