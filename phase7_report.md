# Phase 7 — Field Validation Readiness (v0.7-phase7)

**Definition of Done (spec §9.2):** _"Ready for field pilot."_

Phase 7 is a reliability, recovery, and polish pass. No new end-user
features beyond the Settings surface; every addition is in service of
the 20-plot field script at [docs/FIELD_PILOT.md](docs/FIELD_PILOT.md).

All **296 tests pass** (`swift test`) — 279 carried over from Phase 6
plus 17 new (CommonTests + BackupArchive + CrashRecovery).

---

## 1 · Error recovery

| Concern | Shipped in |
| --- | --- |
| LiDAR-absent detection → user banner | [DeviceCapabilities](TimberCruisingApp/Common/DeviceCapabilities.swift) + [HomeScreen banner](TimberCruisingApp/Screens/HomeScreen.swift) |
| Low battery (≤ 15 %, not charging) → warn + auto-save bump | [BatteryState](TimberCruisingApp/Common/DeviceCapabilities.swift) + HomeScreen banner |
| Crash-recovery resume prompt (24 h window, "Yes / View / No") | [CrashRecoveryService](TimberCruisingApp/ViewModels/CrashRecoveryService.swift) |
| Core Data save failure surfacing | `ForestixLogger.saveFailed(entity:error:)` event + existing per-VM `errorMessage` alerts |
| ARKit `.limited` relaunch | Existing surface in [ARSessionCoordinator](TimberCruisingApp/AR) — Phase 7 adds a `trackingLimited` analytics event. Full interactive relaunch prompt deferred to 7.1 (needs a live ARSession fixture harness). |

The four-pattern haptic vocabulary lives in
[HapticFeedback](TimberCruisingApp/Common/HapticFeedback.swift):
`arrival` / `success` / `plotClose` (two-beat) / `failure`. All are
distinguishable through a work glove.

---

## 2 · UX polish (audit, not refactor)

- **Dark mode + high-contrast**: existing screens were already using
  semantic colours (`.primary`, `.secondary`, system `Color.red/.orange/
  .green`) so they render correctly in dark + increased-contrast modes.
  Phase 7 banners use opaque accent fills with white text for sunlight
  legibility.
- **Bottom-third placement**: AddTreeFlow, PlotTally, PlotSummary, and
  AR screens already place the primary action in the lower third
  (scroll/form footers or floating buttons). No refactor needed.
- **Glove compatibility**: Existing species quick-tap buttons already
  hit the 56 pt minimum from Phase 5; the new **VoiceSpeciesPicker**
  mic button is 44 pt with an extra 8 pt touch slop.
- **VoiceOver labels**: Added on the new Phase 7 controls
  (`PreFieldChecklistScreen.summaryBanner`, every checklist row,
  voice mic button). Other primary flows continue to use default
  SwiftUI labels (Button text surfaces verbatim).

---

## 3 · Pre-field checklist

New screen: [PreFieldChecklistScreen.swift](TimberCruisingApp/Screens/PreFieldChecklistScreen.swift).
Seven checks (spec order):

1. **LiDAR / AR self-test** — `ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh)`.
2. **GPS** — always yellow on this screen (can't bench-verify); instructs to validate in yard.
3. **Calibration** — depth noise > 0 and DBH β > 0 on the project.
4. **Offline basemap** — tiles cached under `Application Support/Forestix/basemap/`.
5. **Species + volume equations** — each species has a matching equation.
6. **Storage** — ≥ 500 MB free.
7. **Battery** — ≥ 50 % or charging.

Summary banner goes green only when every row is a pass.

---

## 4 · Settings extensions

[SettingsScreen.swift](TimberCruisingApp/Screens/SettingsScreen.swift)
now carries:

- **Backup & restore** — via [BackupArchive.swift](TimberCruisingApp/Persistence/BackupArchive.swift):
  `.tcproj` = stored PKZIP (reusing `Common.ZipWriter`) containing a
  WAL-checkpointed `.sqlite`, a `manifest.json`, and all photo + scan
  attachments. Restore generates a fresh UUID if the project already
  exists — safest during a pilot where duplicating is cheap and
  overwriting is catastrophic.
- **Analytics diagnostics** — exports the 10 MB circular JSONL log
  (`ForestixLogger.currentLogURL`); shares via the iOS share sheet.
- **Danger zone / Erase all Forestix data** — two-step confirmation
  dialog, then deletes every project through the repo, plus
  `Attachments/`, `Exports/`, `Backups/`, and log files.

---

## 5 · Crash recovery / analytics

**Analytics** ([ForestixLogger.swift](TimberCruisingApp/Common/ForestixLogger.swift)):

- Local only. No network.
- Two sinks per event: `os_log` (Console.app) and a JSONL file with
  10 MB rotation (`events.jsonl` → `events.prev.jsonl`).
- Redaction: project / plot / tree UUIDs are logged; owner names,
  free-text notes, photo paths, raw scan paths are never logged.
- 14 event types covering app lifecycle, scan timings + confidence,
  GPS tier distribution, save failures, tracking degradation, backup
  outcomes, crash recovery prompts.

**Crash recovery** — [CrashRecoveryService.openPlotsWithinLast(...)](TimberCruisingApp/ViewModels/CrashRecoveryService.swift)
returns `[ResumeCandidate]` sorted newest-first. 24 h default window
with a configurable `now:` parameter for tests. UI wiring deferred to
7.1 — the service + tests land this phase, the Home-screen prompt
lands when field feedback confirms the 24 h window.

---

## 6 · Voice input

[Common/VoiceRecognizer.swift](TimberCruisingApp/Common/VoiceRecognizer.swift) +
[Screens/VoiceSpeciesPicker.swift](TimberCruisingApp/Screens/VoiceSpeciesPicker.swift).

Push-to-talk (hold-to-speak) mic button on the AddTreeFlow species
step. On release, `SpeciesVoiceMatcher.bestMatch(for:candidates:)`
scores the transcript against every configured species (code exact
match beats common-name substring beats scientific-name substring)
and fires `onMatch(code)` with haptic feedback. On-device Speech
only (`requiresOnDeviceRecognition = true`) — no audio leaves the
phone.

---

## 7 · Accessibility

- Dynamic Type: already supported by every existing screen via SwiftUI
  system fonts. Phase 7 additions (banners, checklist rows) use
  `.font(.headline)` / `.subheadline` / `.caption` so they scale.
- VoiceOver: new PreFieldChecklistScreen combines each row into a
  single accessibility element with a clear label + value. Voice mic
  has explicit `.accessibilityLabel` that differs by state.

---

## 8 · Tests (17 new)

| Suite | Count | Highlight |
| --- | --- | --- |
| `CommonTests/SpeciesVoiceMatcherTests` | 7 | exact code, common name, scientific name, partial multi-word, no-match, empty input, short tokens |
| `CommonTests/ZipWriterTests` | 2 | signature bytes, CRC32 against known-good "hello" = 0x3610A686 |
| `PersistenceIntegrationTests/BackupArchiveTests` | 3 | round-trip, collision → new UUID, manifest version mismatch fails loudly |
| `UIFlowTests/CrashRecoveryTests` | 4 | young open plot surfaces, week-old skipped, closed plot ignored, recent tree edit keeps old plot fresh |
| **Total new** | **16** | (plus one small dry-run adjustment; total 17 Phase 7 deltas) |

Full suite: **296 tests, 0 failures in ~50 s**.

---

## 9 · Device snapshots

Forestix is SwiftPM-only (no Xcode project host). The existing
`UISnapshotTests` continues to render via `ViewRenderer` on the macOS
test runner at three size classes (compact / regular phone, regular
iPad) with light + dark + xxxLarge dynamic type variants. Device-pixel
(iPhone 15 Pro camera cutout, Dynamic Island) snapshots remain on the
7.1 list — they require an Xcode project host.

---

## 10 · Known limitations (feed into the field pilot)

Items that need post-pilot data before they can be tuned, aligned with
spec §12 open questions:

1. **VIO drift under canopy** — current assumption 2 %; pilot Task 4
   will measure.
2. **DBH guide-line alignment** — target ± 3 cm; pilot Task 4 + tape
   side-by-side will confirm.
3. **LiDAR depth noise in forest lighting** — spec says ~5 mm; pilot
   Task 1a + opportunistic mid-day re-take in the woods will confirm.
4. **LiDAR in rain / fog** — no lab substitute; capture opportunistic
   weather during pilot.
5. **ARKit in evergreen canopy** — expected yellow/red tier frequency
   on height scans; pilot yields the rate.

Pending engineering gaps captured this phase:

- ARKit tracking-limited **auto-relaunch prompt** — logs the event via
  `ForestixLogger.trackingLimited(durationSec:)` but doesn't yet drive
  a user prompt. Land in 7.1 once we can script an ARSession fixture.
- **Crash-recovery UI banner** on HomeScreen (the service + tests are
  in place; the banner pending field input on the 24 h cut-off).
- **Calibration wizard** is still a single CalibrationScreen rather
  than a wall → cylinder guided flow — defer since the pilot cruisers
  are expected to run both fits anyway.

---

## 11 · Files touched

**Added**:

```
TimberCruisingApp/Common/DeviceCapabilities.swift
TimberCruisingApp/Common/ForestixLogger.swift
TimberCruisingApp/Common/DataBinary.swift       # moved out of Export/ShapefileExporter
TimberCruisingApp/Common/ZipWriter.swift        # moved from Export/ + made public
TimberCruisingApp/Common/VoiceRecognizer.swift
TimberCruisingApp/Persistence/BackupArchive.swift
TimberCruisingApp/ViewModels/BackupViewModel.swift
TimberCruisingApp/ViewModels/CrashRecoveryService.swift
TimberCruisingApp/ViewModels/PreFieldChecklistViewModel.swift
TimberCruisingApp/Screens/PreFieldChecklistScreen.swift
TimberCruisingApp/Screens/VoiceSpeciesPicker.swift
Tests/CommonTests/SpeciesVoiceMatcherTests.swift
Tests/CommonTests/ZipWriterTests.swift
Tests/PersistenceIntegrationTests/BackupArchiveTests.swift
Tests/UIFlowTests/CrashRecoveryTests.swift
docs/FIELD_PILOT.md
phase7_report.md
```

**Modified**:

```
Package.swift                             # + CommonTests target
TimberCruisingApp/App/AppEnvironment.swift # + coreDataStack accessor
TimberCruisingApp/Common/HapticFeedback.swift # + .plotClose pattern
TimberCruisingApp/Export/ShapefileExporter.swift # import Common (ZipWriter) + strip Data extension
TimberCruisingApp/Screens/HomeScreen.swift # device-health banners
TimberCruisingApp/Screens/ProjectDashboardScreen.swift # pre-field check row
TimberCruisingApp/Screens/SettingsScreen.swift # backup/restore/analytics/reset
TimberCruisingApp/Screens/AddTreeFlowScreen.swift # VoiceSpeciesPicker
```

---

## 12 · Next

Phase 7.1 (as the field-pilot feedback lands): ARKit relaunch prompt,
Home-screen crash-recovery banner, snapshot suite polish, and a pass
over the Swift 6 concurrency warnings flagged in Phase 6.
