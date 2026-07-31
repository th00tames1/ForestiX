#!/usr/bin/env python3
"""The estimator's geometry: what the shipped chord identity assumes, and what it costs.

THE CLAIM UNDER TEST. The shipped diameter estimator takes a bracketed silhouette
width w px at depth z, with focal f px, and inverts

    d = w * z / (f - w/2)                                          (chord form)

Rearranged, d = w (z + d/2) / f: a FLAT chord of length d, hung at the stem's
AXIS depth (near face plus one radius). But the bracket handles are not the ends
of a flat chord — they sit on the silhouette, where the line of sight is TANGENT
to the bole. For a circular cylinder of diameter d whose near face is at depth z,
the tangent rays leave the lens at +/- alpha with tan(alpha) = k = w/(2f), and the
exact inversion is

    d = 2 z k (k + sqrt(k^2 + 1))                                  (tangent form)

Both take z as the NEAR-FACE depth, so they are directly comparable, and their
ratio is a pure function of k:

    d_chord / d_tangent = 1 / [ (1 - k) (k + sqrt(k^2 + 1)) ]  =  1 + k^2/2 + O(k^3)

which exceeds 1 for every k > 0. The chord form therefore over-reads every stem,
by more the wider the stem sits in the frame. This script measures how much, on
this corpus, from the stored depth frames.

WHAT IS RECOMPUTED, AND WHY IT MATCHES THE REST OF THE MANUSCRIPT. The baseline
here is not a model of the shipped estimator; it IS the column the rest of the
paper analyses. `../analysis/build_final.py` recomputed all 200 diameters from
their own depth frames with one implementation (axis-matched focal, fractional
span, median depth over the bracket's middle half), because the recording binary
changed partway through collection. This script re-extracts that identical
per-frame sample -- (z, span, focal) -- and swaps ONLY the inversion, so the
"shipped" row reproduces `core.load()`'s measured column and every other row
differs from it in exactly one thing. The reproduction is checked, not assumed.

`../analysis/sweep2.py` is the source of the candidate ladder and of the 0.031754 R
near-face offset; its own scanline models Android's pixel-rounded span, which is a
different baseline from the manuscript's, so the extraction below follows
build_final and the offset formula is imported from sweep2 and verified against it.
"""
from __future__ import annotations

import core
plt = core.use_style()
df = core.load()

import csv
import datetime as dt
import glob
import json
import math
import os
import statistics as st
import struct
import sys

import numpy as np
import pandas as pd

ANALYSIS = os.path.join(core.VAL, "analysis")
sys.path.insert(0, ANALYSIS)
import sweep2  # noqa: E402  -- for OFFSET_CORE and its self-consistent solver

PT = dt.timezone(dt.timedelta(hours=-7))
IN = 2.54
IOS_MERGE = {27: 26}          # build_final.py: iOS 26 + 27 are one stem
MATCH_WINDOW_S = 120          # build_final.py's capture <-> reading window
OFFSET = sweep2.OFFSET_CORE   # 1 - sqrt(1 - 1/16) = 0.031754 R


# --------------------------------------------------------------------------
# The two inversions, and the identity that relates them
# --------------------------------------------------------------------------

def chord(z, span, focal):
    """Shipped: flat chord hung at the axis depth."""
    return span * z / (focal - span / 2) * 100.0


def tangent(z, span, foc):
    """Exact circular-cylinder inversion from tangent rays; z is the near face."""
    k = span / (2 * foc)
    return 2 * z * k * (k + math.sqrt(k * k + 1)) * 100.0


def tangent_offset(z_med, span, foc, offset=OFFSET):
    """Tangent, with the median depth walked forward to the near face.

    The shipped fit medians depth over the bracket's MIDDLE HALF. A uniform
    sample across |x| <= R/2 of a circular face has its median at |x| = R/4,
    which sits R(1 - sqrt(1 - 1/16)) = 0.031754 R BEHIND the near face. Solving
    z_near = z_med - offset * R with R = g z_near / 2 in closed form:

        d = g z_med / (1 + offset g / 2),   g = 2k(k + sqrt(k^2+1))
    """
    k = span / (2 * foc)
    g = 2 * k * (k + math.sqrt(k * k + 1))
    return g * z_med / (1 + offset * g / 2) * 100.0


def overread(k):
    """d_chord / d_tangent - 1, the pure-geometry over-read, as a fraction."""
    k = np.asarray(k, float)
    return 1.0 / ((1 - k) * (k + np.sqrt(k * k + 1))) - 1.0


ESTIMATORS = [
    ("shipped chord", chord),
    ("tangent", tangent),
    ("tangent + 0.031754R", tangent_offset),
]


# --------------------------------------------------------------------------
# The per-frame sample, lifted verbatim from build_final.frame_diameter
# --------------------------------------------------------------------------

def f2(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def T(s):
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(PT)
    except Exception:
        return None


def depth_of(path, w, h, fmt):
    raw = open(path, "rb").read()
    n = w * h
    if fmt == "f32m":
        return None if len(raw) < n * 4 else [
            (v if 0.001 <= v <= 8.0 else 0.0)
            for v in struct.unpack_from("<%df" % n, raw, 0)]
    if fmt == "u16mm":
        return None if len(raw) < n * 2 else [
            (v / 1000.0 if 1 <= v <= 8000 else 0.0)
            for v in struct.unpack_from("<%dH" % n, raw, 0)]
    return None


def core_range(i_lo, i_hi):
    span = i_hi - i_lo
    if span < 8:
        return i_lo, i_hi
    q = span // 4
    return i_lo + q, i_hi - q


def frame_sample(frame, dirpath, lo, hi):
    """(z, span_px, focal_px) for one frame -- the shipped fit's own inputs."""
    w, h = frame.get("width"), frame.get("height")
    axis, dfile = frame.get("axis"), frame.get("depth_file")
    focal = f2(frame.get("fx")) if axis == "row" else f2(frame.get("fy"))
    if focal is None:
        focal = f2(frame.get("fx"))
    if not (w and h and focal and dfile):
        return None
    p = os.path.join(dirpath, dfile)
    if not os.path.exists(p):
        return None
    dep = depth_of(p, w, h, frame.get("format"))
    if dep is None:
        return None
    tap = frame.get("tap_px") or [w // 2, h // 2]
    extent = w if axis == "row" else h
    fixed = int(tap[1]) if axis == "row" else int(tap[0])
    if not (0 <= fixed < (h if axis == "row" else w)):
        return None
    span = (hi - lo) * extent
    i0 = max(0, math.ceil(lo * extent))
    i1 = min(extent - 1, math.floor(hi * extent))
    if i1 - i0 < 3 or span < 2 or focal - span / 2 <= 1:
        return None
    c0, c1 = core_range(i0, i1)
    vals = []
    for i in range(c0, c1 + 1):
        px, py = (i, fixed) if axis == "row" else (fixed, i)
        v = dep[py * w + px]
        if v > 0:
            vals.append(v)
    if len(vals) < 3:
        return None
    z = st.median(vals)
    if not (0.3 <= z <= 5.0):
        return None
    return z, span, focal


def captures(plat):
    out = []
    for path in sorted(glob.glob(os.path.join(core.VAL, "raw", plat, "**", "manifest.json"),
                                 recursive=True)):
        try:
            m = json.load(open(path))
        except Exception:
            continue
        if m.get("kind") != "dbh":
            continue
        d = m.get("dbh") or {}
        br = d.get("bracket") or {}
        if not br.get("enabled"):
            continue
        lo, hi = f2(br.get("left")), f2(br.get("right"))
        if lo is None or hi is None:
            continue
        lo, hi = min(lo, hi), max(lo, hi)
        dirp = os.path.dirname(path)
        samples = [s for s in (frame_sample(fr, dirp, lo, hi)
                               for fr in (d.get("frames") or [])) if s]
        if not samples:
            continue
        t = T(m.get("created_at"))
        out.append(dict(tree=(m.get("context") or {}).get("tree_number"),
                        t=t.replace(tzinfo=None) if t else None,
                        samples=samples))
    return out


def capture_value(samples, fn):
    """One capture's diameter: the median across its burst, as build_final does."""
    return st.median([fn(z, s, f) for z, s, f in samples])


def capture_k(samples):
    return st.median([s / (2 * f) for _, s, f in samples])


# --------------------------------------------------------------------------
# Match captures to the manuscript's stems
# --------------------------------------------------------------------------

CAPS = {p: captures(p) for p in ("ios", "android")}
print(f"raw bracketed DBH captures: "
      f"ios {len(CAPS['ios'])}, android {len(CAPS['android'])}")

pairs = pd.read_csv(core.PAIRS)
pairs = pairs[pairs.kind == "dbh"]

rows, unmatched, ratios = [], [], []
for _, r in pairs.iterrows():
    flags = str(r.get("flags") or "")
    for dev in ("ios", "android"):
        stored = r.get(f"{dev}_value")           # cm, the manuscript's measured
        truth = r.get("truth")                   # cm, the tape
        if pd.isna(stored) or pd.isna(truth):
            continue
        if f"{dev}-typed" in flags:              # core.load() drops these; so do we
            continue
        if str(r.get(f"{dev}_value_source")) != "raw-depth":
            unmatched.append((r["pair_id"], dev, "not recomputed from depth"))
            continue
        tree = r.get(f"tree_{dev}")
        when = dt.datetime.strptime(r[f"{dev}_time"], "%Y-%m-%d %H:%M:%S")
        cands = [c for c in CAPS[dev]
                 if c["t"] and c["tree"] is not None
                 and (c["tree"] == tree or IOS_MERGE.get(c["tree"], c["tree"]) == tree)
                 and abs((c["t"] - when).total_seconds()) <= MATCH_WINDOW_S]
        if not cands:
            unmatched.append((r["pair_id"], dev, "no capture in window"))
            continue
        # Of the retakes inside the window, take the one the manuscript kept:
        # the one whose chord value IS the stored value.
        best = min(cands, key=lambda c: abs(capture_value(c["samples"], chord) - stored))
        got = capture_value(best["samples"], chord)
        ratios.append(got / stored)
        if abs(got / stored - 1) > 0.005:
            unmatched.append((r["pair_id"], dev, f"chord/stored = {got/stored:.4f}"))
            continue
        rec = dict(stem=r["pair_id"], site=r["plot"], device=dev,
                   reference=truth / IN, stored=stored / IN,
                   k=capture_k(best["samples"]), n_frames=len(best["samples"]),
                   tape_disputed="TAPE-MISMATCH" in flags)
        for name, fn in ESTIMATORS:
            rec[name] = capture_value(best["samples"], fn) / IN
        rows.append(rec)

E = pd.DataFrame(rows)
print(f"matched and reproduced: {len(E)} of {len(pairs) * 2} device-stem readings")
print(f"chord/stored ratio: median {np.median(ratios):.5f}, "
      f"within 0.5 % on {sum(1 for x in ratios if abs(x-1) < 0.005)}/{len(ratios)}")
for u in unmatched:
    print("   not used:", u)

# --- verification 1: our closed-form offset solver == sweep2's iterative one ---
worst = 0.0
for c in CAPS["ios"][:40] + CAPS["android"][:40]:
    for z, s, f in c["samples"]:
        a = tangent_offset(z, s, f)
        b = sweep2.tangent_selfconsistent(z, s, f, OFFSET)
        if b:
            worst = max(worst, abs(a - b) / b)
print(f"closed-form vs sweep2 iterative offset solver: max rel diff {worst:.2e}")

# --- verification 2: the observed chord/tangent ratio IS the k formula ---
obs_ratio = (E["shipped chord"] / E["tangent"]).values
pred_ratio = 1 + overread(E["k"].values)
resid = np.abs(obs_ratio - pred_ratio)
print(f"observed d_chord/d_tangent vs formula at median k: "
      f"max |diff| {resid.max():.2e}, median {np.median(resid):.2e}")


# --------------------------------------------------------------------------
# Table
# --------------------------------------------------------------------------

def block(sub, name):
    err = sub[name] - sub["reference"]
    pct = err / sub["reference"] * 100
    s_ols, i_ols, r2 = core.ols(sub["reference"], sub[name])
    s_dem, _ = core.deming(sub["reference"], sub[name])
    plo, phi = core.bootstrap_ci(pct)
    lo, hi = core.bootstrap_ci(err)
    return dict(n=len(sub),
                bias_in=err.mean(), bias_ci_low_in=lo, bias_ci_high_in=hi,
                bias_pct=pct.mean(), bias_pct_ci_low=plo, bias_pct_ci_high=phi,
                bias_cm=err.mean() * IN,
                median_pct=pct.median(),
                rmse_in=core.rmse(err), mae_in=err.abs().mean(),
                rmse_cm=core.rmse(err) * IN, mae_cm=err.abs().mean() * IN,
                ols_slope=s_ols, ols_intercept_in=i_ols, deming_slope=s_dem,
                ccc=core.ccc(sub["reference"], sub[name]))


trows = []
for subset, mask in (("all stems", np.ones(len(E), bool)),
                     ("excl. disputed tape", ~E.tape_disputed.values)):
    for dev in ("ios", "android"):
        sub = E[mask & (E.device == dev).values]
        for name, _ in ESTIMATORS:
            b = block(sub, name)
            trows.append(dict(subset=subset, device=core.DEVICE_LABEL[dev],
                              estimator=name, **b,
                              k_median=sub.k.median(), k_min=sub.k.min(),
                              k_max=sub.k.max(),
                              k_p5=sub.k.quantile(0.05), k_p95=sub.k.quantile(0.95)))
T11 = pd.DataFrame(trows)
for c in T11.columns:
    if T11[c].dtype.kind == "f":
        T11[c] = T11[c].round(4)

core.save_table(T11, "t11_estimator", """
Table 11. Diameter estimator geometry, evaluated against the diameter tape on the
stems whose depth frames could be re-inverted (iOS n = %d, Android n = %d). Every
row uses the SAME per-frame sample -- axis-matched focal, fractional bracket span,
median depth over the bracket's middle half -- and differs only in the inversion:
(i) the shipped chord identity d = w z / (f - w/2), which reproduces the measured
column analysed elsewhere in this manuscript; (ii) the exact circular-cylinder
tangent inversion d = 2 z k (k + sqrt(k^2+1)), k = w/2f; (iii) that inversion with
the median depth walked 0.031754 R forward to the near face, the offset implied by
medianing a uniform sample over the middle half of a circular face. Bias, RMSE and
MAE are in inches (cm alongside); bias CIs are seeded percentile bootstraps.
Slopes and CCC are against the tape and describe the SHAPE of what each inversion
leaves behind. k statistics are per device and identical across the three estimator
rows of a device. The disputed-tape subset removes the %d DBH stems flagged
TAPE-MISMATCH; it moves no bias by more than %.2f in and changes no conclusion.
One iOS stem (McD014) is absent because its diameter was an Auto capture with no
stored bracket and so cannot be re-inverted; the other 199 device-stem readings
reproduce the manuscript's measured column exactly under the chord row.
""" % ((E.device == "ios").sum(), (E.device == "android").sum(),
       E[E.tape_disputed].stem.nunique(),
       max(abs(T11[T11.subset == "all stems"].set_index(["device", "estimator"]).bias_in
               - T11[T11.subset == "excl. disputed tape"]
               .set_index(["device", "estimator"]).bias_in))))

pd.set_option("display.width", 200)
print(T11[["subset", "device", "estimator", "n", "bias_in", "bias_pct",
           "bias_pct_ci_low", "bias_pct_ci_high", "median_pct", "rmse_in",
           "mae_in", "ols_slope", "ols_intercept_in", "deming_slope", "ccc",
           "k_median"]].to_string(index=False))


# --------------------------------------------------------------------------
# How much of the bias does the geometry explain?
# --------------------------------------------------------------------------
SHARE = {}
for dev in ("ios", "android"):
    sub = E[E.device == dev]
    b0 = ((sub["shipped chord"] - sub["reference"]) / sub["reference"] * 100).mean()
    b1 = ((sub["tangent"] - sub["reference"]) / sub["reference"] * 100).mean()
    b2 = ((sub["tangent + 0.031754R"] - sub["reference"]) / sub["reference"] * 100).mean()
    # The shift IS the mean geometric over-read: a check, not a coincidence.
    mean_over = overread(sub.k.values).mean() * 100
    shift = ((1 + b0 / 100) / (1 + b1 / 100) - 1) * 100
    plo, phi = core.bootstrap_ci((sub["tangent + 0.031754R"] - sub["reference"])
                                 / sub["reference"] * 100)
    SHARE[dev] = dict(chord=b0, tan=b1, tan_off=b2,
                      removed=(b0 - b1) / b0 * 100, residual=b1,
                      mean_over=mean_over, res_lo=plo, res_hi=phi,
                      dem=core.deming(sub["reference"], sub["tangent + 0.031754R"])[0],
                      over_at_median=overread(sub.k.median()) * 100)
    print(f"{dev}: %bias chord {b0:+.2f} -> tangent {b1:+.2f} "
          f"({(b0-b1)/b0*100:.0f} % of the bias removed) -> +offset {b2:+.2f}"
          f" | mean over-read {mean_over:+.2f} % vs realised shift {shift:+.2f} %"
          f" | over-read at median k {overread(sub.k.median())*100:+.2f} %")


# --------------------------------------------------------------------------
# Off-axis sensitivity: what the centring assumption is worth
# --------------------------------------------------------------------------
# A cylinder whose axis sits psi off the optical axis still subtends the same
# angular width 2*alpha, but the pinhole maps angle to pixels through tan(),
# so the PIXEL width becomes f[tan(psi+alpha) - tan(psi-alpha)] instead of
# 2f tan(alpha) -- an inflation of about sec^2(psi). Both inversions above
# assume psi = 0, so that inflation passes straight into d.
k_med = float(E.k.median())
alpha = math.atan(k_med)
OFFAXIS = []
for psi_deg in (2.5, 5.0, 10.0, 15.0):
    psi = math.radians(psi_deg)
    infl = (math.tan(psi + alpha) - math.tan(psi - alpha)) / (2 * math.tan(alpha))
    k2 = k_med * infl
    d_ratio = (k2 * (k2 + math.sqrt(k2 * k2 + 1))) / (k_med * (k_med + math.sqrt(k_med ** 2 + 1)))
    OFFAXIS.append((psi_deg, infl, 1 / math.cos(psi) ** 2, d_ratio))
    print(f"psi {psi_deg:4.1f} deg: width x{infl:.4f} (sec^2 = {1/math.cos(psi)**2:.4f}) "
          f"-> diameter x{d_ratio:.4f}  ({(d_ratio-1)*100:+.2f} %)")


# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
fig, (axA, axB) = plt.subplots(1, 2, figsize=(core.FIG_W * 1.42, core.FIG_H * 1.0))

# ---- Panel A: the curve, and where this corpus sits on it -----------------
kk = np.linspace(0, max(0.42, E.k.max() * 1.12), 500)
axA.plot(kk, overread(kk) * 100, color=core.PALETTE["reference"], lw=1.8, zorder=5,
         label=r"$d_{chord}/d_{tangent}-1$")
axA.set_xlabel(r"silhouette half-width in focal lengths, $k = w/2f$  (unitless)")
axA.set_ylabel("chord-form over-read (%)")
axA.set_xlim(0, kk.max())
axA.set_ylim(0, overread(kk.max()) * 100 * 1.05)

axK = axA.twinx()
axK.grid(False)
bins = np.linspace(0, kk.max(), 27)
styles = {"ios": ("-", 1.5), "android": ("--", 1.5)}
for dev in ("ios", "android"):
    v = E.loc[E.device == dev, "k"].values
    ls, lw = styles[dev]
    axK.hist(v, bins=bins, histtype="step", color=core.PALETTE[dev], lw=lw,
             linestyle=ls, label=f"{core.DEVICE_LABEL[dev]} (n={len(v)})")
    axK.hist(v, bins=bins, color=core.PALETTE[dev], alpha=0.10)
axK.set_ylabel("captures per bin")
axK.set_ylim(0, axK.get_ylim()[1] * 2.6)     # keep the histogram in the lower half
axK.spines["right"].set_visible(True)
# the curve and its median markers belong ON TOP of the histogram
axA.set_zorder(axK.get_zorder() + 1)
axA.patch.set_visible(False)

# medians, marked on the curve
lab = []
for dev, mk in (("ios", 5), ("android", 7)):
    km = float(E.loc[E.device == dev, "k"].median())
    o = overread(km) * 100
    axA.plot([km, km], [0, o], color=core.PALETTE[dev], lw=1.0,
             linestyle=styles[dev][0], alpha=0.9, zorder=4)
    axA.plot([km], [o], marker="o" if dev == "ios" else "^", ms=6,
             color=core.PALETTE[dev], mec="white", mew=0.8, zorder=6)
    lab.append(f"{core.DEVICE_SHORT[dev]}: median $k$ = {km:.3f} $\\to$ {o:+.2f} %"
               f"   (mean over-read {SHARE[dev]['mean_over']:+.2f} %)")
axA.text(0.035, 0.975, "\n".join(lab), transform=axA.transAxes, va="top", ha="left",
         fontsize=8, linespacing=1.5, zorder=10,
         bbox=dict(boxstyle="round,pad=0.35", fc="white", ec="#B9C2CC", lw=0.6))
axA.text(0.035, 0.795,
         r"$\dfrac{d_{chord}}{d_{tangent}}=\dfrac{1}{(1-k)\,(k+\sqrt{k^2+1})}\;\approx\;1+\dfrac{k^2}{2}$",
         transform=axA.transAxes, va="top", ha="left", fontsize=9, zorder=10,
         color=core.PALETTE["reference"],
         bbox=dict(boxstyle="round,pad=0.30", fc="white", ec="none", alpha=0.85))
h1, l1 = axA.get_legend_handles_labels()
h2, l2 = axK.get_legend_handles_labels()
axA.legend(h1 + h2, l1 + l2, loc="upper left", bbox_to_anchor=(0.035, 0.63),
           fontsize=7.5, handlelength=2.2)
core.panel_tag(axA, "A")

# ---- Panel B: paired shift per capture ------------------------------------
POS = {"ios": [0.0, 1.0, 2.0], "android": [3.4, 4.4, 5.4]}
MARK = {"ios": "o", "android": "^"}
axB.axhline(0, color=core.PALETTE["reference"], lw=0.9, ls=(0, (4, 3)), zorder=2)
names = [n for n, _ in ESTIMATORS]

for dev in ("ios", "android"):
    sub = E[E.device == dev]
    xs = POS[dev]
    Y = np.column_stack([(sub[n] - sub["reference"]) / sub["reference"] * 100
                         for n in names])
    for row in Y:
        axB.plot(xs, row, color=core.PALETTE[dev], lw=0.5, alpha=0.20, zorder=3,
                 solid_capstyle="round")
    axB.plot(np.repeat(xs, len(Y)).reshape(3, -1), Y.T, ls="none",
             marker=MARK[dev], ms=2.0, color=core.PALETTE[dev], alpha=0.35, zorder=4)
    for j, x in enumerate(xs):
        m = Y[:, j].mean()
        axB.plot([x], [m], marker=MARK[dev], ms=9, color=core.PALETTE[dev],
                 mec="white", mew=1.2, zorder=7)
        axB.annotate(f"{m:+.2f} %", (x, m), textcoords="offset points",
                     xytext=(0, 13 if dev == "ios" else 13), ha="center",
                     fontsize=8, fontweight="bold", color=core.PALETTE[dev],
                     zorder=8,
                     bbox=dict(boxstyle="round,pad=0.18", fc="white", ec="none",
                               alpha=0.85))
    axB.plot(xs, Y.mean(axis=0), color=core.PALETTE[dev], lw=1.6,
             ls=styles[dev][0], zorder=6, label=core.DEVICE_LABEL[dev])

axB.set_xticks(POS["ios"] + POS["android"])
axB.set_xticklabels(["chord\n(shipped)", "tangent", "tangent\n+0.032R"] * 2,
                    fontsize=7.5)
axB.set_xlim(-0.55, 5.95)
axB.set_ylabel("error against diameter tape (%)")
axB.set_xlabel("inversion applied to the same depth frames")
axB.legend(loc="upper right", fontsize=8, handlelength=2.4)
for dev in ("ios", "android"):
    axB.text(np.mean(POS[dev]), 1.015, core.DEVICE_SHORT[dev],
             transform=axB.get_xaxis_transform(), ha="center", va="bottom",
             fontsize=8.5, fontweight="bold", color=core.PALETTE[dev])
core.panel_tag(axB, "B")

fig.tight_layout(w_pad=2.4)
core.save(fig, "fig11_estimator", """
Figure 11. The shipped diameter inversion is the wrong geometry, and the size of
the error is set by how wide the stem sits in the frame. (A) Relative over-read of
the shipped chord identity d = w z / (f - w/2) against the exact circular-cylinder
tangent inversion d = 2 z k (k + sqrt(k^2+1)), plotted from the closed form
1/[(1-k)(k+sqrt(k^2+1))] - 1 (solid line, left axis) against k = w/2f, the
silhouette half-width in focal lengths. Histograms (right axis, iOS solid, Android
dashed) show where this corpus's %d re-inverted captures fall on that curve;
markers give each device's median k and the over-read it implies. (B) The same
captures scored against the diameter tape under three inversions of one identical
per-frame depth sample: thin lines are individual captures, heavy markers the mean
percentage error. Every capture moves down, because the over-read is positive for
all k. The tangent form removes %.0f %% (iOS) and %.0f %% (Android) of the chord
form's mean bias; the 0.031754 R near-face offset removes a little more. A positive
bias survives both -- iOS +%.2f %% (95 %% bootstrap CI %+.2f to %+.2f), Android
+%.2f %% (%+.2f to %+.2f) -- and this geometry does not explain it. Both inversions
assume the stem is centred on the optical axis; an off-axis stem inflates the
measured silhouette width by about sec^2(psi), which at psi = 10 deg is +3.1 %% in
width and +3.8 %% in diameter at this corpus's median k, larger than the correction
shown here.
""" % (len(E), SHARE["ios"]["removed"], SHARE["android"]["removed"],
       SHARE["ios"]["tan_off"], SHARE["ios"]["res_lo"], SHARE["ios"]["res_hi"],
       SHARE["android"]["tan_off"], SHARE["android"]["res_lo"],
       SHARE["android"]["res_hi"]))

print("\nwrote", os.path.join(core.FIGDIR, "fig11_estimator.png"))
print("wrote", os.path.join(core.RESDIR, "t11_estimator.csv"))
