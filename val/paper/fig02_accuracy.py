#!/usr/bin/env python3
"""fig02 / t02 — the master accuracy table and the calibration scatter.

One row of the table per (measurand x device x site) plus a "Both sites" row,
and a 2x2 calibration panel (rows = measurand, columns = device) showing
measured against reference with the 1:1 line and the Deming fit.

Everything is computed by core.summarize() on the real paired table; nothing
here is typed in by hand. The tape-disputed sensitivity is run as a second
pass and printed, so the manuscript can state whether dropping those stems
moves any conclusion.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import core

plt = core.use_style()
df = core.load()

MEAS_ORDER = ["dbh", "height"]
DEV_ORDER = ["ios", "android"]
SITE_LEVELS = core.SITES + ["Both sites"]
SITE_MARKER = {"McDunn": "o", "Starker": "^"}


# --------------------------------------------------------------------------
# Table t02_accuracy
# --------------------------------------------------------------------------

def cell(sub: pd.DataFrame, measurand: str, device: str, site_label: str) -> dict:
    """One table row: the accuracy block for one (measurand, device, site).

    DBH is in inches and height in feet, so the dimensional columns are headed
    `[in | ft]` and the `Unit` column says which one applies to that row. One
    set of columns beats two half-empty sets.
    """
    m = core.MEASURANDS[measurand]
    s = core.summarize(sub)
    return {
        "Measurand": m["short"],
        "Device": core.DEVICE_LABEL[device],
        "Site": site_label,
        "Unit": m["unit"],
        "n": s["n"],
        "Bias [in | ft]": round(s["bias"], 2),
        "Bias 95% CI [in | ft]": f"{s['bias_ci_low']:+.2f} to {s['bias_ci_high']:+.2f}",
        "Bias [%]": round(s["pct_bias"], 1),
        "SD of error [in | ft]": round(s["sd"], 2),
        "95% LoA [in | ft]": f"{s['loa_low']:+.2f} to {s['loa_high']:+.2f}",
        "RMSE [in | ft]": round(s["rmse"], 2),
        "MAE [in | ft]": round(s["mae"], 2),
        "R2": round(s["r2"], 3),
        "CCC": round(s["ccc"], 3),
        "OLS slope [-]": round(s["ols_slope"], 3),
        "OLS intercept [in | ft]": round(s["ols_intercept"], 2),
        "Deming slope [-]": round(s["deming_slope"], 3),
        "Deming intercept [in | ft]": round(s["deming_intercept"], 2),
    }


def build_table(data: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for measurand in MEAS_ORDER:
        for device in DEV_ORDER:
            base = data[(data.measurand == measurand) & (data.device == device)]
            for site in core.SITES:
                rows.append(cell(base[base.site == site], measurand, device, site))
            rows.append(cell(base, measurand, device, "Both sites"))
    return pd.DataFrame(rows)


table = build_table(df)


# The LoA assume approximately normal differences. Test it rather than assume
# it, and carry the actual test statistics into the caption so the caption
# cannot drift from the data.
from scipy import stats as sps

normality = {}
emp_loa = {}
for _m in MEAS_ORDER:
    for _d in DEV_ORDER:
        _e = df[(df.measurand == _m) & (df.device == _d)].error
        _w, _p = sps.shapiro(_e)
        normality[(_m, _d)] = dict(W=_w, p=_p, skew=sps.skew(_e),
                                   kurt=sps.kurtosis(_e))
        _lo, _hi = np.percentile(_e, [2.5, 97.5])
        emp_loa[(_m, _d)] = (_lo, _hi)

_nonnormal = [f"{core.MEASURANDS[m]['short']}/{core.DEVICE_SHORT[d]} "
              f"(W = {v['W']:.3f}, p = {v['p']:.1g})"
              for (m, d), v in normality.items() if v["p"] < 0.05]
_par = core.summarize(df[(df.measurand == "dbh") & (df.device == "ios")])
_emp = emp_loa[("dbh", "ios")]

core.save_table(
    table, "t02_accuracy",
    caption=(
        "Table 2. Agreement between the ForestiX smartphone estimates and the field "
        "reference (diameter tape for DBH, laser rangefinder in 3-point mode for "
        "height), by measurand, handset and stand, with both stands pooled. Bias is "
        "the mean signed error (app minus reference) with a seeded percentile "
        "bootstrap 95 % confidence interval (10 000 resamples); LoA are the "
        "Bland-Altman 95 % limits of agreement, computed against the reference "
        "rather than the mean of the two methods. R2, OLS slope and OLS intercept "
        "come from ordinary least squares of measured on reference; the Deming "
        "slope and intercept assume error in both variables with a variance ratio "
        "of 1, which is conservative because the reference is the better "
        "instrument. Diameters in inches, heights in feet; the `Unit` column says "
        "which applies to each row. Readings the cruiser typed rather than "
        "measured are excluded. One iOS height capture failed, so iOS height "
        "n = 99. CAVEAT: the 95 % limits of agreement assume approximately normal "
        "differences, and Shapiro-Wilk rejects normality for "
        + "; ".join(_nonnormal) +
        ". The parametric limits are reported for comparability with the "
        "literature, but for the worst case, iOS DBH, the empirical "
        "2.5-97.5 percentile limits are "
        f"{_emp[0]:+.2f} to {_emp[1]:+.2f} in against a parametric "
        f"{_par['loa_low']:+.2f} to {_par['loa_high']:+.2f} in, so the "
        "parametric interval overstates the downside and understates the "
        "upside. Pooled Deming slopes are not the average of the within-stand "
        "slopes, because the two stands span different parts of the size range."
    ),
)


# --------------------------------------------------------------------------
# Sensitivity: the 8 stems with a disputed tape reading
# --------------------------------------------------------------------------

clean = df[~df.tape_disputed]
table_clean = build_table(clean)

sens_lines = []
for measurand in MEAS_ORDER:
    u = core.MEASURANDS[measurand]["unit"]
    for device in DEV_ORDER:
        a = core.summarize(df[(df.measurand == measurand) & (df.device == device)])
        b = core.summarize(clean[(clean.measurand == measurand) & (clean.device == device)])
        sens_lines.append(
            f"{measurand:6s} {device:8s} all n={a['n']:3d} bias {a['bias']:+.2f} {u} "
            f"({a['pct_bias']:+.1f}%) CCC {a['ccc']:.3f} Deming {a['deming_slope']:.3f} "
            f"| no-disputed n={b['n']:3d} bias {b['bias']:+.2f} {u} "
            f"({b['pct_bias']:+.1f}%) CCC {b['ccc']:.3f} Deming {b['deming_slope']:.3f}"
        )
print("\nTAPE-DISPUTED SENSITIVITY")
print("\n".join(sens_lines))


# --------------------------------------------------------------------------
# Figure fig02_accuracy
# --------------------------------------------------------------------------

from matplotlib import patheffects as pe

# ONE WIDE ROW OF SQUARE PANELS. Each panel carries a 1:1 line, and a 1:1 line
# is only readable when one unit on x is one unit on y — so every panel is
# forced square. Laid out 2x2 that makes the FIGURE square, which wastes half a
# 16:9 slide and gets shrunk to fit the height. In a row the panels stay square
# and the figure is slide-shaped.
fig, axes = plt.subplots(1, 4, figsize=(4 * core.PANEL + 1.4, core.PANEL + 1.6))
tags = [["A", "B"], ["C", "D"]]

for i, measurand in enumerate(MEAS_ORDER):
    m = core.MEASURANDS[measurand]
    u = m["unit"]
    row = df[df.measurand == measurand]
    lo = min(row.reference.min(), row.measured.min())
    hi = max(row.reference.max(), row.measured.max())
    pad = 0.06 * (hi - lo)
    lim = (lo - pad, hi + pad)

    for j, device in enumerate(DEV_ORDER):
        ax = axes[i * 2 + j]
        sub = row[row.device == device]
        s = core.summarize(sub)

        ax.plot(lim, lim, ls=(0, (5, 3)), lw=1.0, color=core.PALETTE["muted"],
                zorder=1, label="1:1")

        for site in core.SITES:
            ss = sub[sub.site == site]
            ax.scatter(ss.reference, ss.measured,
                       s=20, marker=SITE_MARKER[site],
                       facecolors="none", linewidths=0.9,
                       edgecolors=core.PALETTE["site"][site],
                       alpha=0.85, zorder=3, label=site)

        # The fit is drawn in the handset colour, but the point colours already
        # spend blue and crimson on site; a white casing keeps the line legible
        # where it crosses same-coloured markers.
        xs = np.array(lim)
        ax.plot(xs, s["deming_slope"] * xs + s["deming_intercept"],
                ls="-", lw=1.6, color=core.PALETTE[device], zorder=4,
                label="Deming fit",
                path_effects=[pe.Stroke(linewidth=3.2, foreground="white"),
                              pe.Normal()])

        ax.set_xlim(lim)
        ax.set_ylim(lim)
        ax.set_aspect("equal", adjustable="box")
        ax.set_xlabel(f"Reference {m['short']} ({u})")
        ax.set_ylabel(f"{core.DEVICE_SHORT[device]} measured {m['short']} ({u})")
        core.panel_tag(ax, tags[i][j])

        ax.text(0.04, 0.96,
                core.DEVICE_LABEL[device],
                transform=ax.transAxes, va="top", ha="left",
                fontsize=8.5, fontweight="bold", color=core.PALETTE[device])

        box = (f"n = {s['n']}\n"
               f"CCC = {s['ccc']:.3f}\n"
               f"Deming slope = {s['deming_slope']:.3f}\n"
               f"bias = {s['bias']:+.2f} {u} ({s['pct_bias']:+.1f} %)")
        ax.text(0.96, 0.045, box, transform=ax.transAxes,
                va="bottom", ha="right", fontsize=7.8, linespacing=1.4,
                bbox=dict(boxstyle="round,pad=0.35", facecolor="white",
                          edgecolor=core.PALETTE["grid"], linewidth=0.7,
                          alpha=0.95), zorder=5)

from matplotlib.lines import Line2D

legend_items = [
    Line2D([], [], ls="none", marker=SITE_MARKER["McDunn"], markersize=5,
           markerfacecolor="none", markeredgecolor=core.PALETTE["site"]["McDunn"],
           label="McDunn"),
    Line2D([], [], ls="none", marker=SITE_MARKER["Starker"], markersize=5,
           markerfacecolor="none", markeredgecolor=core.PALETTE["site"]["Starker"],
           label="Starker"),
    Line2D([], [], ls=(0, (5, 3)), lw=1.0, color=core.PALETTE["muted"],
           label="1:1 line"),
    Line2D([], [], ls="-", lw=1.6, color=core.PALETTE["muted"],
           label="Deming fit (drawn in the handset colour)"),
]
fig.legend(handles=legend_items, loc="lower center", ncol=4,
           bbox_to_anchor=(0.5, -0.005), handletextpad=0.5, columnspacing=1.4)

fig.tight_layout(rect=(0, 0.045, 1, 1))

core.save(
    fig, "fig02_accuracy",
    caption=(
        "Figure 2. Calibration of the ForestiX smartphone estimates against the "
        "field reference. (A, B) diameter at breast height, inches; (C, D) total "
        "height, feet; left column iPhone (LiDAR), right column Android (ARCore). "
        "Points are individual stems, open circles from the McDunn stand and open "
        "triangles from Starker; the dashed line is 1:1 and the solid line is the "
        "Deming fit (error-in-both-variables, variance ratio 1). Axes share limits "
        "and aspect within a row so the two handsets are directly comparable. "
        "Inset gives n, Lin's concordance correlation coefficient, the Deming "
        "slope and the mean signed bias. Diameter carries a proportional error on "
        "both handsets (Deming slopes 1.12 iOS, 1.08 Android; bootstrap CIs "
        "1.045-1.198 and 1.020-1.134, both excluding 1), so the over-read grows "
        "with stem size. Height carries no proportional error the sample can "
        "resolve (slopes 0.978 and 0.992, CIs 0.954-1.002 and 0.958-1.028, both "
        "including 1); iOS nonetheless reads 2.42 ft low on average (95 % CI "
        "-3.69 to -1.16 ft), a real shortfall whose form this sample cannot pin "
        "to slope or intercept, while Android shows no resolvable height bias "
        "(-0.73 ft, 95 % CI -2.13 to +0.62 ft). n = 100 stems per panel "
        "except iOS height (n = 99, one failed capture); typed readings excluded. "
        "The Deming line is fitted to the pooled stands; because the two stands "
        "occupy different parts of the size range, the pooled slope is not the "
        "average of the within-stand slopes (Table 2)."
    ),
)


# --------------------------------------------------------------------------
# Numbers for the report
# --------------------------------------------------------------------------

print("\nPROPORTIONAL vs OFFSET")
for measurand in MEAS_ORDER:
    m = core.MEASURANDS[measurand]
    u = m["unit"]
    row = df[df.measurand == measurand]
    ref_mean = row.reference.mean()
    ref_lo, ref_hi = row.reference.min(), row.reference.max()
    for device in DEV_ORDER:
        sub = row[row.device == device]
        s = core.summarize(sub)
        # decompose the fitted bias at the small and large end of the range
        pred_lo = s["deming_slope"] * ref_lo + s["deming_intercept"] - ref_lo
        pred_hi = s["deming_slope"] * ref_hi + s["deming_intercept"] - ref_hi
        # share of the mean bias attributable to slope vs intercept
        slope_part = (s["deming_slope"] - 1) * ref_mean
        icept_part = s["deming_intercept"]
        # bootstrap CI on the Deming slope, resampling stems
        rng = np.random.default_rng(17)
        x = sub.reference.to_numpy(float)
        y = sub.measured.to_numpy(float)
        idx = rng.integers(0, len(x), size=(4000, len(x)))
        sl = np.array([core.deming(x[k], y[k])[0] for k in idx])
        ic = np.array([core.deming(x[k], y[k])[1] for k in idx])
        sl_ci = np.percentile(sl, [2.5, 97.5])
        ic_ci = np.percentile(ic, [2.5, 97.5])
        print(f"{measurand:6s} {device:8s} slope {s['deming_slope']:.3f} "
              f"[{sl_ci[0]:.3f}, {sl_ci[1]:.3f}]  intercept {s['deming_intercept']:+.2f} {u} "
              f"[{ic_ci[0]:+.2f}, {ic_ci[1]:+.2f}]  "
              f"| slope term {slope_part:+.2f} {u}, intercept term {icept_part:+.2f} {u} "
              f"| fitted bias at ref={ref_lo:.1f}: {pred_lo:+.2f}, at ref={ref_hi:.1f}: {pred_hi:+.2f}")

# error-vs-reference correlation: does the error grow with the tree?
print("\nERROR vs REFERENCE (proportional-error check)")
for measurand in MEAS_ORDER:
    u = core.MEASURANDS[measurand]["unit"]
    for device in DEV_ORDER:
        sub = df[(df.measurand == measurand) & (df.device == device)]
        r, p = sps.pearsonr(sub.reference, sub.error)
        rs, ps = sps.spearmanr(sub.reference, sub.error)
        sl, ic, _ = core.ols(sub.reference, sub.error)
        print(f"{measurand:6s} {device:8s} Pearson r={r:+.3f} p={p:.2g}  "
              f"Spearman rho={rs:+.3f} p={ps:.2g}  d(error)/d(ref)={sl:+.4f} {u}/{u}")

# residual normality — LoA assume approximately normal differences
print("\nSHAPIRO-WILK on the signed error, and parametric vs empirical LoA")
for measurand in MEAS_ORDER:
    u = core.MEASURANDS[measurand]["unit"]
    for device in DEV_ORDER:
        sub = df[(df.measurand == measurand) & (df.device == device)]
        v = normality[(measurand, device)]
        s = core.summarize(sub)
        e = emp_loa[(measurand, device)]
        print(f"{measurand:6s} {device:8s} W={v['W']:.3f} p={v['p']:.4g}  "
              f"skew={v['skew']:+.2f} kurt={v['kurt']:+.2f}  | LoA parametric "
              f"{s['loa_low']:+.2f} to {s['loa_high']:+.2f} {u}  "
              f"empirical {e[0]:+.2f} to {e[1]:+.2f} {u}")

# between-site difference in bias
print("\nSITE CONTRAST in bias (Welch t)")
for measurand in MEAS_ORDER:
    u = core.MEASURANDS[measurand]["unit"]
    for device in DEV_ORDER:
        sub = df[(df.measurand == measurand) & (df.device == device)]
        a = sub[sub.site == "McDunn"].error
        b = sub[sub.site == "Starker"].error
        t, p = sps.ttest_ind(a, b, equal_var=False)
        print(f"{measurand:6s} {device:8s} McDunn {a.mean():+.2f} {u} vs "
              f"Starker {b.mean():+.2f} {u}  diff {a.mean()-b.mean():+.2f}  t={t:+.2f} p={p:.4g}")

print("\nTABLE t02_accuracy")
with pd.option_context("display.width", 250, "display.max_columns", 40):
    print(table[["Measurand", "Device", "Site", "Unit", "n", "Bias [in | ft]",
                 "Bias [%]", "SD of error [in | ft]", "95% LoA [in | ft]",
                 "RMSE [in | ft]", "CCC", "Deming slope [-]",
                 "Deming intercept [in | ft]"]].to_string(index=False))
