#!/usr/bin/env python3
"""fig06 — does accuracy hold across the size range, or only on average?

Binning is on the REFERENCE value. Binning on the phone's own reading would
sort its over-reads into the next class up and manufacture a size trend out of
the error itself.

The class summary answers "where does it break"; the regression of percent
error on reference size answers "does it degrade smoothly", which no eyeball
reading of six boxes can settle. Regression standard errors are HC3-robust
because percent error is heteroscedastic by construction — a fixed absolute
error is a large percentage of a small stem and a small percentage of a big one.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import statsmodels.api as sm
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()

CLASS_ORDER = {
    "dbh": [f"{lo}–{hi}" if hi < 999 else f"{lo}+" for lo, hi in core.DBH_CLASSES],
    "height": [f"{lo}–{hi}" if hi < 999 else f"{lo}+" for lo, hi in core.HEIGHT_CLASSES],
}
SMALL_N = 10          # below this a class summary is indicative only
MARKER = {"ios": "o", "android": "^"}
HATCH = {"ios": "", "android": "///"}

df["cls"] = [core.size_class(v, k) for v, k in zip(df.reference, df.measurand)]


# --------------------------------------------------------------------------
# Per-class summary
# --------------------------------------------------------------------------

def class_table(data: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for kind in core.MEASURANDS:
        unit = core.MEASURANDS[kind]["unit"]
        for dev in core.DEVICES:
            for cls in CLASS_ORDER[kind]:
                sub = data[(data.measurand == kind) & (data.device == dev)
                           & (data.cls == cls)]
                if len(sub) == 0:
                    continue
                lo, hi = core.bootstrap_ci(sub.pct_error)
                rows.append(dict(
                    measurand=core.MEASURANDS[kind]["short"],
                    device=core.DEVICE_SHORT[dev],
                    size_class=cls, unit=unit, n=len(sub),
                    ref_min=sub.reference.min(), ref_max=sub.reference.max(),
                    mean_pct_error=sub.pct_error.mean(),
                    pct_ci_low=lo, pct_ci_high=hi,
                    median_pct_error=sub.pct_error.median(),
                    bias=sub.error.mean(),
                    rmse=core.rmse(sub.error),
                    mae=sub.abs_error.mean(),
                    small_n=len(sub) < SMALL_N,
                ))
    return pd.DataFrame(rows)


tab = class_table(df)
tab_nodisp = class_table(df[~df.tape_disputed])


# --------------------------------------------------------------------------
# Does accuracy degrade with size? Regression of % error on reference size.
# --------------------------------------------------------------------------

def size_trend(data: pd.DataFrame, kind: str, dev: str) -> dict:
    sub = data[(data.measurand == kind) & (data.device == dev)].dropna(
        subset=["pct_error", "reference"])
    X = sm.add_constant(sub.reference.to_numpy(float))
    y = sub.pct_error.to_numpy(float)
    fit = sm.OLS(y, X).fit(cov_type="HC3")
    ci = fit.conf_int()[1]
    rho, p_rho = sps.spearmanr(sub.reference, sub.pct_error)
    # the same question in real units — is the ABSOLUTE error size-dependent?
    fit_abs = sm.OLS(sub.error.to_numpy(float), X).fit(cov_type="HC3")
    ci_abs = fit_abs.conf_int()[1]
    bp = sm.stats.diagnostic.het_breuschpagan(fit.resid, X)
    return dict(
        measurand=kind, device=dev, n=len(sub),
        slope=fit.params[1], lo=ci[0], hi=ci[1], p=fit.pvalues[1],
        intercept=fit.params[0], r2=fit.rsquared,
        spearman=rho, spearman_p=p_rho,
        abs_slope=fit_abs.params[1], abs_lo=ci_abs[0], abs_hi=ci_abs[1],
        abs_p=fit_abs.pvalues[1],
        bp_p=bp[3],
    )


trend = pd.DataFrame([size_trend(df, k, d)
                      for k in core.MEASURANDS for d in core.DEVICES])
trend_nodisp = pd.DataFrame([size_trend(df[~df.tape_disputed], k, d)
                             for k in core.MEASURANDS for d in core.DEVICES])


def shape_tests(data: pd.DataFrame, kind: str, dev: str) -> dict:
    """A null LINEAR slope does not mean no size dependence.

    Two checks the straight line cannot make: a quadratic term, for the
    U-shape the class means hint at, and Kruskal-Wallis across the classes,
    which assumes neither normality nor a monotone form.
    """
    sub = data[(data.measurand == kind) & (data.device == dev)].dropna(
        subset=["pct_error", "reference"])
    r = sub.reference.to_numpy(float)
    rc = r - r.mean()
    X = sm.add_constant(np.column_stack([rc, rc ** 2]))
    fit = sm.OLS(sub.pct_error.to_numpy(float), X).fit(cov_type="HC3")
    groups = [g.pct_error.to_numpy(float) for _, g in sub.groupby("cls")
              if len(g) > 0]
    kw = sps.kruskal(*groups) if len(groups) > 1 else (np.nan, np.nan)
    lev = sps.levene(*groups, center="median") if len(groups) > 1 else (np.nan,) * 2
    return dict(measurand=kind, device=dev, n=len(sub),
                quad_coef=fit.params[2], quad_p=fit.pvalues[2],
                kruskal_H=kw[0], kruskal_p=kw[1],
                levene_p=lev[1])


shape = pd.DataFrame([shape_tests(df, k, d)
                      for k in core.MEASURANDS for d in core.DEVICES])

# Is a size trend really a SITE trend? Check how size classes split by site.
site_mix = (df[df.device == "ios"]
            .groupby(["measurand", "cls", "site"]).size().unstack(fill_value=0))


# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
fig, axes = plt.subplots(2, 2, figsize=(core.FIG_W * 1.19, core.FIG_H * 1.50))
tags = [["A", "B"], ["C", "D"]]

for r, kind in enumerate(core.MEASURANDS):
    unit = core.MEASURANDS[kind]["unit"]
    short = core.MEASURANDS[kind]["short"]
    axis_name = "DBH" if kind == "dbh" else "height"
    classes = [c for c in CLASS_ORDER[kind]
               if ((df.measurand == kind) & (df.cls == c)).any()]
    x = np.arange(len(classes))
    off = 0.19

    # ---- left: percent error by class -----------------------------------
    axL = axes[r, 0]
    axL.axhline(0, color=core.PALETTE["reference"], lw=1.0, zorder=1)
    for j, dev in enumerate(core.DEVICES):
        pos = x + (j * 2 - 1) * off
        vals = [df[(df.measurand == kind) & (df.device == dev)
                   & (df.cls == c)].pct_error.dropna().to_numpy()
                for c in classes]
        bp = axL.boxplot(vals, positions=pos, widths=0.32, patch_artist=True,
                         showfliers=False, medianprops=dict(
                             color="white", lw=1.4), zorder=3)
        for box in bp["boxes"]:
            box.set(facecolor=core.PALETTE[dev], alpha=0.85,
                    edgecolor=core.PALETTE[dev], lw=0.9, hatch=HATCH[dev])
        for part in ("whiskers", "caps"):
            for ln in bp[part]:
                ln.set(color=core.PALETTE[dev], lw=0.9,
                       linestyle="-" if dev == "ios" else (0, (3, 1.5)))
        rng = np.random.default_rng(11)
        for p, v in zip(pos, vals):
            if len(v) == 0:
                continue
            axL.scatter(p + rng.uniform(-0.08, 0.08, len(v)), v, s=7,
                        marker=MARKER[dev], facecolor="white",
                        edgecolor=core.PALETTE[dev], lw=0.5, alpha=0.9, zorder=4)

    lo_y = min(df[df.measurand == kind].pct_error.min(), -5)
    hi_y = max(df[df.measurand == kind].pct_error.max(), 5)
    pad = 0.13 * (hi_y - lo_y)
    # headroom for the slope inset, footroom for the n labels
    axL.set_ylim(lo_y - 2.0 * pad, hi_y + 2.6 * pad)
    for i, c in enumerate(classes):
        n_i = int(((df.measurand == kind) & (df.cls == c)
                   & (df.device == "ios")).sum())
        n_a = int(((df.measurand == kind) & (df.cls == c)
                   & (df.device == "android")).sum())
        lbl = f"n={n_i}" if n_i == n_a else f"n={n_i}/{n_a}"
        weak = min(n_i, n_a) < SMALL_N
        if weak:
            axL.axvspan(i - 0.5, i + 0.5, color=core.PALETTE["muted"],
                        alpha=0.09, zorder=0)
        axL.text(i, lo_y - 1.55 * pad, lbl, ha="center", va="center",
                 fontsize=7.5, color=core.PALETTE["warn"] if weak
                 else core.PALETTE["muted"],
                 fontweight="bold" if weak else "normal")

    axL.set_xticks(x)
    axL.set_xticklabels(classes)
    axL.set_xlim(-0.6, len(classes) - 0.4)
    axL.set_xlabel(f"Reference {axis_name} class ({unit})")
    axL.set_ylabel("Error (% of reference)")

    # the number the panel exists to deliver: the size slope
    lines = []
    for dev in core.DEVICES:
        t = trend[(trend.measurand == kind) & (trend.device == dev)].iloc[0]
        star = "" if t.p >= 0.05 else "*"
        lines.append(f"{core.DEVICE_SHORT[dev]} {t.slope:+.2f} "
                     f"[{t.lo:+.2f}, {t.hi:+.2f}] %/{unit}, p={t.p:.3f}{star}")
    axL.text(0.015, 0.975, "% error vs size (OLS, HC3):\n" + "\n".join(lines),
             transform=axL.transAxes, va="top", ha="left", fontsize=7.1,
             color=core.PALETTE["reference"],
             bbox=dict(boxstyle="round,pad=0.32", fc="white",
                       ec=core.PALETTE["grid"], lw=0.6, alpha=0.93))
    core.panel_tag(axL, tags[r][0])

    # ---- right: RMSE by class in real units ------------------------------
    axR = axes[r, 1]
    w = 0.36
    for j, dev in enumerate(core.DEVICES):
        vals = [tab[(tab.measurand == short)
                    & (tab.device == core.DEVICE_SHORT[dev])
                    & (tab.size_class == c)].rmse for c in classes]
        vals = [float(v.iloc[0]) if len(v) else np.nan for v in vals]
        axR.bar(x + (j * 2 - 1) * w / 2, vals, width=w,
                color=core.PALETTE[dev], alpha=0.85, hatch=HATCH[dev],
                edgecolor=core.PALETTE[dev], lw=0.9,
                label=core.DEVICE_LABEL[dev], zorder=3)
        for xi, v in zip(x + (j * 2 - 1) * w / 2, vals):
            if not np.isnan(v):
                axR.text(xi, v, f"{v:.1f}", ha="center", va="bottom",
                         fontsize=7.0, color=core.PALETTE[dev])
    for i, c in enumerate(classes):
        n_i = int(((df.measurand == kind) & (df.cls == c)
                   & (df.device == "ios")).sum())
        n_a = int(((df.measurand == kind) & (df.cls == c)
                   & (df.device == "android")).sum())
        if min(n_i, n_a) < SMALL_N:
            axR.axvspan(i - 0.5, i + 0.5, color=core.PALETTE["muted"],
                        alpha=0.09, zorder=0)
    axR.set_xticks(x)
    axR.set_xticklabels(classes)
    axR.set_xlim(-0.6, len(classes) - 0.4)
    axR.set_xlabel(f"Reference {axis_name} class ({unit})")
    axR.set_ylabel(f"RMSE ({unit})")
    axR.set_ylim(0, max(tab[tab.measurand == short].rmse) * 1.34)
    if r == 0:
        axR.legend(loc="upper left", fontsize=7.5, handlelength=1.6,
                   bbox_to_anchor=(0.0, 0.86))
    lines = []
    for dev in core.DEVICES:
        t = trend[(trend.measurand == kind) & (trend.device == dev)].iloc[0]
        star = "*" if t.abs_p < 0.05 else ""
        lines.append(f"{core.DEVICE_SHORT[dev]} {t.abs_slope:+.3f} "
                     f"[{t.abs_lo:+.3f}, {t.abs_hi:+.3f}], p={t.abs_p:.3f}{star}")
    axR.text(0.985, 0.975,
             f"signed error vs size ({unit}/{unit}, HC3):\n" + "\n".join(lines),
             transform=axR.transAxes, va="top", ha="right", fontsize=7.1,
             color=core.PALETTE["reference"],
             bbox=dict(boxstyle="round,pad=0.32", fc="white",
                       ec=core.PALETTE["grid"], lw=0.6, alpha=0.93))
    core.panel_tag(axR, tags[r][1])

axes[0, 0].text(0.985, 0.975, "shaded: n < 10, indicative only",
                transform=axes[0, 0].transAxes, ha="right", va="top",
                fontsize=7.0, color=core.PALETTE["warn"],
                bbox=dict(boxstyle="round,pad=0.28", fc="white",
                          ec=core.PALETTE["grid"], lw=0.6, alpha=0.93))
fig.tight_layout(w_pad=2.0, h_pad=2.4)

cap = (
    "Measurement error against stem size, binned on the reference value so "
    "that the phone's own over-read cannot sort stems into a higher class and "
    "manufacture a trend. (A, C) Percent error by diameter and height class "
    "for both handsets (boxes: median and interquartile range, whiskers "
    "1.5x IQR, points are individual stems; horizontal line = perfect "
    "agreement). The inset gives the slope of percent error on reference size "
    "with 95 % CI and p from OLS with HC3 heteroscedasticity-robust standard "
    "errors; percent error is heteroscedastic by construction, and a "
    "Breusch-Pagan test rejects homoscedasticity for iOS height (p = 0.007), "
    "so classical OLS intervals would be too narrow. No percent-error slope "
    "differs from zero (p = 0.29-0.50). (B, D) RMSE by class in field units, "
    "with the slope of SIGNED error on reference size inset: proportional "
    "error that is flat in percent still grows in inches and feet, and the "
    "iOS diameter over-read (+0.088 in per inch, p = 0.031) and iOS height "
    "under-read (-0.033 ft per foot, p = 0.010) do so significantly. n per "
    "class is printed under each group; classes with n < 10 (DBH 0-8 in and "
    "32+ in, n = 6 and 9) are shaded and are indicative only. Height class "
    "and site are confounded - every stem above 140 ft is at Starker and "
    "16 of 18 below 50 ft are at McDunn - so a height-class contrast cannot "
    "be separated from a stand contrast. Diameters in inches against a "
    "diameter tape, heights in feet against a laser rangefinder in 3-point "
    "mode; n = 100 stems (99 for iOS height). Excluding the 8 stems with a "
    "disputed tape value leaves every percent-error slope unchanged in sign "
    "and still non-significant, and strengthens rather than weakens the "
    "Android diameter signed slope (p = 0.066 to p = 0.010)."
)
core.save(fig, "fig06_by_size", cap)

out = tab.copy()
out.columns = [c for c in out.columns]
core.save_table(out.round(3), "t06_by_size", (
    "Accuracy by reference size class. Classes are defined on the reference "
    "measurement, not the phone reading. Percent-error CI is a 10 000-draw "
    "percentile bootstrap of the class mean. RMSE and MAE are in inches (DBH) "
    "and feet (height). small_n flags classes with fewer than 10 stems, where "
    "the class mean is unstable and the CI wide."
))

# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 200, "display.max_columns", 40)
print("\n=== per-class ===")
print(tab.round(2).to_string(index=False))
print("\n=== % error vs reference size (HC3) ===")
print(trend.round(4).to_string(index=False))
print("\n=== non-linear / any-difference checks ===")
print(shape.round(4).to_string(index=False))
print("\n=== classes whose bootstrap CI on mean % error excludes 0 ===")
sig = tab[(tab.pct_ci_low > 0) | (tab.pct_ci_high < 0)]
print(sig[["measurand", "device", "size_class", "n", "mean_pct_error",
           "pct_ci_low", "pct_ci_high"]].round(2).to_string(index=False))
print("\n=== same, excluding 8 disputed-tape stems ===")
print(trend_nodisp.round(4).to_string(index=False))
print("\n=== class means: full vs disputed-excluded ===")
merged = tab.merge(tab_nodisp, on=["measurand", "device", "size_class"],
                   suffixes=("", "_nd"))
merged["d_mean_pct"] = merged.mean_pct_error_nd - merged.mean_pct_error
merged["d_rmse"] = merged.rmse_nd - merged.rmse
print(merged[["measurand", "device", "size_class", "n", "n_nd",
              "mean_pct_error", "mean_pct_error_nd", "d_mean_pct",
              "rmse", "rmse_nd", "d_rmse"]].round(2).to_string(index=False))
print("\n=== size class x site (are size and site confounded?) ===")
print(site_mix.to_string())
print("\n=== extremes ===")
for kind in core.MEASURANDS:
    for dev in core.DEVICES:
        s = tab[(tab.measurand == core.MEASURANDS[kind]["short"])
                & (tab.device == core.DEVICE_SHORT[dev])]
        w = s.loc[s.mean_pct_error.abs().idxmax()]
        print(f"{kind:6s} {dev:8s} worst class {w.size_class:8s} "
              f"n={int(w.n):2d} mean {w.mean_pct_error:+.1f}% "
              f"[{w.pct_ci_low:+.1f},{w.pct_ci_high:+.1f}] RMSE {w.rmse:.2f}")
