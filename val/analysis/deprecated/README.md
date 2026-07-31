# Retired analyses — do not cite, do not re-run

Everything in this folder was written before two defects were found, and every
number in it is affected by at least one of them. Kept only so the record of
what was tried survives; nothing here should reach the manuscript.

## Why they are wrong

**1. The iOS guide-axis bug (fixed in `ea00935`).** The diameter written to the
field log and the one kept in the raw-capture bundle were computed against
separately chosen walk axes. When they differed the bracket was read against the
wrong extent and the logged reading came out ~1.38x high. It affected 60 of 107
iOS diameters — most of the McDunn plot. **Every script here that reads a
diameter out of the exported CSV is reading corrupted iOS values**, which is
most of them: `extract.py`, `match.py`, `match2.py`, `paired.py`, `final.py`,
`variance.py`, `residual.py`, `range_confound.py`, `drift*.py`, `relayer.py`,
`cylinder.py`, `stats.py`, `REPORT.md`, `verified_pairs.csv`, `clean_pairs.csv`,
`per_tree.csv`, `figure_agreement.py`.

Conclusions drawn from them that are now known to be false:
- "McDunn iOS DBH is unusable / reads 1.40x tape" — it reads 1.03x.
- "iOS is less accurate than Android on diameter" — the reverse.
- "the geometric layers help less than the operating range does" — computed on
  the corrupted values; the geometry is worth 2-3 percentage points.
- "stand within 1.5 m (iOS) / 1.0 m (Android)" — range and diameter are
  confounded (rho = +0.686) and iOS reverses sign within a size band.

**2. The wrong shipped baseline.** `sweep.py`, `by_size.py`, `tangent_test.py`
and `holdout.py` were written after the axis fix and use good diameters, but
they model the shipped estimator as medianing the WHOLE bracket. It medians the
middle half (`bracketCoreRange`), which is itself a partial near-face
correction, so those scripts credit the proposed change with a gain the app
already had. They also modelled Android's span the iOS way; Android rounds each
handle to a pixel first. Superseded by `sweep2.py` / `by_size2.py`, which
reproduce each platform's stored values to a median ratio of 0.9995 (iOS) and
0.9996 (Android).

`tangent_test.py` additionally contains a circular argument: its "geometry
predicts X, we removed X" table compares `chord/tan - 1` against
`(chord - tan)/truth`, which are the same quantity whenever truth is near tan.
It is not evidence and was withdrawn.

## What replaced them

`build_final.py` -> `final_pairs.csv` (100 trees, both plots, every diameter
recomputed from raw depth on one path), then `analyze_final.py`, `sweep2.py`,
`by_size2.py`, `verify_fix.py`, `simulate_repair.py`, `bundles.py`, and the
manuscript suite under `../paper/`.
