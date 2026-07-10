# Forestix — Field Pilot Script

This document describes a **20-plot bench + yard + stand pilot** intended
to validate that the Phase 0–7 build is field-ready before a paid cruise.
Follow the sections in order.

Target time: **one full working day** (≈ 8 h) from device check to final
export.

---

## 0 · Equipment checklist

Minimum:

- [ ] iPhone with LiDAR (iPhone 12 Pro or newer) — **fully charged**
- [ ] Backup power bank (≥ 10 000 mAh)
- [ ] Diameter tape or caliper (for Task 2 ground truth)
- [ ] Laser rangefinder or 30 m tape (for Task 3 ground truth)
- [ ] A flat wall (Task 1a)
- [ ] A cylindrical object of known diameter (Task 1b) — e.g. a PVC pipe
      with a diameter marked in mm
- [ ] 20 labelled target trees in a mixed-age stand, 4+ cm DBH

---

## 1 · Bench calibration (office / yard, 30 min)

### 1a. Wall calibration

1. Open **Settings → Run Calibration → Wall fit**.
2. Stand 1.5 m from a flat wall. Aim the phone so the LiDAR sees the
   wall.
3. Start the capture; slowly sweep the phone left-right for 10 s.
4. Expected result: **depth noise σ ≤ 6 mm**. If higher, clean the
   TrueDepth / LiDAR module and retry.

### 1b. Cylinder calibration

1. Place the calibration cylinder at 1.5 m. Measure its diameter with a
   caliper (ground truth).
2. In **Settings → Calibration → Cylinder fit**, run 5 captures at
   different phone rolls (0°, ±30°, ±60°).
3. Expected result: **DBH α ∈ [-2, +2] mm, β ∈ [0.98, 1.02]**. Outside
   that window, repeat — the tape may be slipping.

---

## 2 · Pre-field checklist (5 min)

1. Open any project → **Tools → Pre-field check**.
2. All seven rows should be green. Expected yellow on "GPS check"
   (confirmed in yard → Task 3 below).
3. Common failures and fixes:

| Row | Fix |
| --- | --- |
| LiDAR + AR | Check that this is the right device. Non-LiDAR → manual-only mode. |
| Calibration | Re-run the wizard from Task 1. |
| Basemap | Download an offline region via the Map screen while on Wi-Fi. |
| Species list | Settings → Species (Phase 1 import via GeoJSON / JSON). |
| Storage < 500 MB | Offload old exports / backups from Files app. |
| Battery < 50 % | Plug in for 20 min before driving out. |

---

## 3 · Yard GPS validation (10 min)

1. Stand in the open sky for 3 min; launch **Plot Centre** screen.
2. Tier A should be achieved within 120 s with modal accuracy
   **< 5 m** (iPhone GNSS expectation).
3. Record the achieved tier in the pilot log.

---

## 4 · 20-plot stand cruise (4–6 h)

1. **Create project**: metric, Douglas-fir–Hemlock, fixed-area 0.1 ac.
2. **Define strata + plan**: one rectangular stratum is fine; enable
   systematic grid with spacing such that you generate ≈ 20 plots.
3. For each plot:
   1. Use the Navigation screen to walk to the plot centre. Arrival
      haptic should fire within 5 m.
   2. Capture plot centre via GPS averaging (until Tier A) OR VIO offset
      walk-off (if under canopy).
   3. Tap **Add tree**.
   4. DBH: align the fixed horizontal guide line at 1.37 m above ground,
      scan for 4–6 s. Record confidence tier.
   5. Height: follow the walk-off prompt; aim at base then apex. Only
      every 3rd live tree (subsample rule) gets a measured height.
   6. On red-tier scans, retake or switch to manual and log it in the
      tree's notes field.
   7. After 10 trees, close the plot. Plot close haptic should fire.
4. Target per plot: **≤ 12 min from arrival to close** for a dense plot.

### Bench side-by-side for every 5th tree

- Measure DBH with a tape. Record the delta.
- If |delta| > 1 cm on any green-tier scan, flag the tree — this is
  feeder data for **§12 open question #3** (LiDAR depth noise in
  forest lighting).

---

## 5 · Plot / stand review (20 min)

1. Open **Stand Summary**. Verify:
   - Closed plot count matches your field count.
   - TPA, BA/ac, V/ac numbers look plausible for the stand type.
   - Per-stratum means have a sensible SE + CI95.
2. Open each plot's summary; spot-check:
   - Live tree count = observed tree count
   - QMD ≈ tape-measured QMD from the side-by-side samples
   - No red-tier trees left without a "retake or manual" note

---

## 6 · Backup + export (15 min)

1. **Settings → Back up all projects**. Verify a `.tcproj` file is
   produced; share to your cloud for safe keeping.
2. **Project → Export all**. Expect 11 artefacts (PDF, 5× CSV, 2×
   GeoJSON, 3× Shapefile ZIP). Sizes typical for a 20-plot cruise:
   - `report.pdf`: 200–400 KB (scales with plot count)
   - `trees.csv`: 20–60 KB (≈ 200 trees)
   - `plots-shp.zip`: ~3 KB
3. Open the PDF in Files app; confirm:
   - Cover page with project name + owner + date + plot count
   - Stand summary table + bar chart
   - One page per plot with per-species breakdown
   - Methodology page with calibration values
   - Tree appendix

---

## 7 · Post-field debrief (30 min)

Document each of these into a shared field log for the engineering
team:

- Wall calibration depth noise (mm)
- Cylinder calibration α / β
- Median GPS H accuracy + final tier distribution
- DBH delta samples (tape vs Forestix, by confidence tier)
- Height delta samples (rangefinder vs Forestix, walk-off vs manual)
- Count of trees where the subsample rule fired
- Any plots that triggered the crash-recovery resume prompt
- Screenshots of the low-battery banner / LiDAR-absent banner (if
  observed)
- Export total size on disk

The analytics log (**Settings → Export analytics log**) should be
attached to the debrief — it carries per-scan durations and
confidence tiers.

---

## 8 · Success criteria for "ready to sell"

| Metric | Threshold |
| --- | --- |
| DBH delta (green tier) | ≤ 1 cm on 90 % of side-by-side samples |
| Height delta (green tier) | ≤ 1 m on 90 % of side-by-side samples |
| Time per full plot (10 trees) | ≤ 12 min on 90 % of plots |
| Crashes or save failures | 0 |
| Battery at day end (no charging) | ≥ 20 % |
| PDF + CSV exports | All 11 artefacts produced without error |

Missing any of these ⇒ triage with the team; not a blocker by itself
but all must be green before paid cruises begin.

---

## Open questions (§12) this pilot will answer

1. **VIO drift under canopy** — the VIO-offset plot centres measured
   against a Tier-A GPS reference (Task 4).
2. **DBH guide-line alignment** — Task 4 DBH delta samples.
3. **LiDAR depth noise in forest lighting** — Task 1a wall fit numbers.
4. **LiDAR in rain / fog** — opportunistic only; if weather cooperates,
   repeat Task 4 plot 12–20 in light rain.
5. **ARKit robustness in evergreen canopy** — frequency of yellow /
   red tier on height scans under dense canopy.
