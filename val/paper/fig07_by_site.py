#!/usr/bin/env python3
"""fig07 — does the method travel between stands?

The two stands were walked on different days (McDunn 28 Jul, Starker 29 Jul),
so SITE and DAY are perfectly confounded and nothing here can separate them.
They also differ in size: Starker stems average 21.9 in and 127.6 ft against
McDunn's 17.1 in and 68.5 ft. A site contrast in FIELD UNITS therefore mostly
reports that Starker trees are bigger, which is not a generalisation problem.
The contrast is run on PERCENT error, which is scale-free, and the field-unit
version is reported beside it so the reader can see the two disagree and why.

Tests. Welch's t on the means (unequal variance is not assumed away — Levene
rejects it for two of the four cells) AND Mann-Whitney with a Hodges-Lehmann
shift, because Shapiro rejects normality in six of the eight site x device
groups. At n = 50 per group the t-test is defensible by CLT, but it is not the
primary read. A failure to reject is not evidence of no difference, so each
contrast also gets a two-sample TOST against a +/-5 percentage-point margin —
the same 5 % band the cumulative panel is drawn around, fixed in advance, not
read off the result.

The confound is checked rather than waved at: percent error is re-run adjusted
for reference size, and again restricted to the diameter/height range the two
stands share.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd
import statsmodels.api as sm
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()

MARKER = {"ios": "o", "android": "^"}
HATCH = {"ios": "", "android": "///"}
SITE_LS = {"McDunn": "-", "Starker": (0, (4, 1.6))}
MARGIN_PP = 5.0          # equivalence margin, percentage points, fixed a priori
BANDS = (5.0, 10.0)      # the tolerances a cruiser reads off the CDF


# --------------------------------------------------------------------------
# Contrast machinery
# --------------------------------------------------------------------------

def hodges_lehmann(a, b, alpha=0.05):
    """Median of all pairwise differences, with the rank-based CI.

    The nonparametric partner to Mann-Whitney: MWU gives a p, this gives the
    shift it is a p FOR, in percentage points, which is the number a reader
    can act on.
    """
    a, b = np.asarray(a, float), np.asarray(b, float)
    d = np.sort((a[:, None] - b[None, :]).ravel())
    n1, n2 = len(a), len(b)
    N = n1 * n2
    z = sps.norm.ppf(1 - alpha / 2)
    k = int(math.floor(N / 2 - z * math.sqrt(N * (n1 + n2 + 1) / 12.0)))
    k = max(k, 0)
    return float(np.median(d)), float(d[k]), float(d[N - 1 - k])


def welch(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    n1, n2 = len(a), len(b)
    v1, v2 = a.var(ddof=1), b.var(ddof=1)
    se = math.sqrt(v1 / n1 + v2 / n2)
    dof = (v1 / n1 + v2 / n2) ** 2 / (
        (v1 / n1) ** 2 / (n1 - 1) + (v2 / n2) ** 2 / (n2 - 1))
    diff = a.mean() - b.mean()
    t = diff / se
    p = 2 * (1 - sps.t.cdf(abs(t), dof))
    crit = sps.t.ppf(0.975, dof)
    return dict(diff=diff, se=se, df=dof, t=t, p=p,
                lo=diff - crit * se, hi=diff + crit * se)


def tost2(a, b, bound, alpha=0.05):
    """Two-sample equivalence: is the site difference inside +/- `bound`?"""
    w = welch(a, b)
    p_low = 1 - sps.t.cdf((w["diff"] + bound) / w["se"], w["df"])
    p_high = sps.t.cdf((w["diff"] - bound) / w["se"], w["df"])
    p = max(p_low, p_high)
    return p, bool(p < alpha)


def within(v, band):
    v = np.asarray(v, float)
    v = v[~np.isnan(v)]
    return 100.0 * (np.abs(v) <= band).mean() if len(v) else np.nan


def cell(data, kind, dev, site):
    return data[(data.measurand == kind) & (data.device == dev)
                & (data.site == site)]


# --------------------------------------------------------------------------
# Per-site accuracy and the site contrast
# --------------------------------------------------------------------------

def build_table(data: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for kind in core.MEASURANDS:
        M = core.MEASURANDS[kind]
        for dev in core.DEVICES:
            for site in core.SITES:
                s = cell(data, kind, dev, site)
                summ = core.summarize(s)
                plo, phi = core.bootstrap_ci(s.pct_error)
                rows.append(dict(
                    measurand=M["short"], device=core.DEVICE_SHORT[dev],
                    site=site, unit=M["unit"], n=summ["n"],
                    ref_mean=s.reference.mean(),
                    bias=summ["bias"], bias_ci_low=summ["bias_ci_low"],
                    bias_ci_high=summ["bias_ci_high"],
                    pct_bias=summ["pct_bias"], pct_ci_low=plo, pct_ci_high=phi,
                    sd_units=summ["sd"], sd_pct=s.pct_error.std(ddof=1),
                    rmse=summ["rmse"], mae=summ["mae"], ccc=summ["ccc"],
                    within_5pct=within(s.pct_error, 5.0),
                    within_10pct=within(s.pct_error, 10.0),
                ))

            # ---- the contrast, McDunn minus Starker ----------------------
            a = cell(data, kind, dev, "McDunn").pct_error.dropna().to_numpy()
            b = cell(data, kind, dev, "Starker").pct_error.dropna().to_numpy()
            au = cell(data, kind, dev, "McDunn").error.dropna().to_numpy()
            bu = cell(data, kind, dev, "Starker").error.dropna().to_numpy()
            w, wu = welch(a, b), welch(au, bu)
            U, p_mwu = sps.mannwhitneyu(a, b, alternative="two-sided")
            hl, hl_lo, hl_hi = hodges_lehmann(a, b)
            p_tost, equiv = tost2(a, b, MARGIN_PP)
            rows.append(dict(
                measurand=M["short"], device=core.DEVICE_SHORT[dev],
                site="Difference (McDunn - Starker)", unit=M["unit"],
                n=len(a) + len(b),
                diff_pct_pp=w["diff"], diff_ci_low=w["lo"], diff_ci_high=w["hi"],
                welch_t=w["t"], welch_df=w["df"], welch_p=w["p"],
                mwu_U=U, mwu_p=p_mwu,
                hl_shift_pp=hl, hl_ci_low=hl_lo, hl_ci_high=hl_hi,
                tost_p=p_tost, equivalent_5pp=equiv,
                levene_pct_p=sps.levene(a, b, center="median").pvalue,
                levene_units_p=sps.levene(au, bu, center="median").pvalue,
                diff_units=wu["diff"], diff_units_p=wu["p"],
                shapiro_mcdunn_p=sps.shapiro(a).pvalue,
                shapiro_starker_p=sps.shapiro(b).pvalue,
            ))
    return pd.DataFrame(rows)


tab = build_table(df)
tab_nodisp = build_table(df[~df.tape_disputed])


# --------------------------------------------------------------------------
# Is a site difference really a SIZE difference? Two independent checks.
# --------------------------------------------------------------------------

def confound_checks(data: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for kind in core.MEASURANDS:
        sub_all = data[data.measurand == kind]
        m = sub_all[sub_all.site == "McDunn"].reference
        t = sub_all[sub_all.site == "Starker"].reference
        lo, hi = max(m.min(), t.min()), min(m.max(), t.max())
        for dev in core.DEVICES:
            s = data[(data.measurand == kind) & (data.device == dev)].dropna(
                subset=["pct_error"])
            X = sm.add_constant(np.column_stack([
                (s.site == "Starker").to_numpy(float),
                s.reference.to_numpy(float)]))
            fit = sm.OLS(s.pct_error.to_numpy(float), X).fit(cov_type="HC3")
            ci = fit.conf_int()[1]
            # how badly are site and size tangled?
            rpb, _ = sps.pointbiserialr((s.site == "Starker").astype(int),
                                        s.reference)
            ov = s[(s.reference >= lo) & (s.reference <= hi)]
            oa = ov[ov.site == "McDunn"].pct_error.to_numpy()
            ob = ov[ov.site == "Starker"].pct_error.to_numpy()
            wo = welch(oa, ob)
            rows.append(dict(
                measurand=core.MEASURANDS[kind]["short"],
                device=core.DEVICE_SHORT[dev],
                adj_site_coef_pp=fit.params[1], adj_ci_low=ci[0],
                adj_ci_high=ci[1], adj_p=fit.pvalues[1],
                size_coef=fit.params[2], size_p=fit.pvalues[2],
                site_size_r=rpb,
                overlap_low=lo, overlap_high=hi,
                overlap_n_mcdunn=len(oa), overlap_n_starker=len(ob),
                overlap_diff_pp=wo["diff"], overlap_welch_p=wo["p"],
                overlap_mwu_p=sps.mannwhitneyu(
                    oa, ob, alternative="two-sided").pvalue,
            ))
    return pd.DataFrame(rows)


conf = confound_checks(df)
conf_nodisp = confound_checks(df[~df.tape_disputed])

# Holm across the four site contrasts, per test family.
for fam, col in (("welch", "welch_p"), ("mwu", "mwu_p")):
    c = tab[tab.site.str.startswith("Difference")].copy()
    order = np.argsort(c[col].to_numpy())
    p = c[col].to_numpy()[order]
    adj = np.maximum.accumulate(p * (len(p) - np.arange(len(p))))
    holm = np.empty(len(p))
    holm[order] = np.minimum(adj, 1.0)
    tab.loc[c.index, f"{fam}_p_holm"] = holm

# What is on offer as an explanation, and what is not recorded at all.
species_cover = (df[df.device == "android"].drop_duplicates("stem")
                 .groupby("site").species
                 .agg(n="size", recorded=lambda s: int(s.notna().sum())))
sigma_by_site = df.groupby(["measurand", "device", "site"]).sigma.mean()


# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
fig, axes = plt.subplots(2, 2, figsize=(core.FIG_W * 1.19, core.FIG_H * 1.46))
tags = [["A", "B"], ["C", "D"]]
XCAP = 25.0          # ECDF x-limit; the informative region is 0-15 %

for r, kind in enumerate(core.MEASURANDS):
    M = core.MEASURANDS[kind]
    unit, short = M["unit"], M["short"]
    axis_name = "DBH" if kind == "dbh" else "height"

    # ---- left: percent error by site x device ---------------------------
    axL = axes[r, 0]
    axL.axhline(0, color=core.PALETTE["reference"], lw=1.0, zorder=2)
    off = 0.20
    rng = np.random.default_rng(7)
    for i, site in enumerate(core.SITES):
        axL.axvspan(i - 0.5, i + 0.5, color=core.PALETTE["site"][site],
                    alpha=0.045, zorder=0)
        pos = {}
        for j, dev in enumerate(core.DEVICES):
            s = cell(df, kind, dev, site).dropna(subset=["pct_error"])
            x = i + (j * 2 - 1) * off
            pos[dev] = s.set_index("stem").pct_error
            bp = axL.boxplot([s.pct_error.to_numpy()], positions=[x],
                             widths=0.30, patch_artist=True, showfliers=False,
                             medianprops=dict(color="white", lw=1.4), zorder=3)
            bp["boxes"][0].set(facecolor=core.PALETTE[dev], alpha=0.85,
                               edgecolor=core.PALETTE[dev], lw=0.9,
                               hatch=HATCH[dev])
            for part in ("whiskers", "caps"):
                for ln in bp[part]:
                    ln.set(color=core.PALETTE[dev], lw=0.9,
                           linestyle="-" if dev == "ios" else (0, (3, 1.5)))
            jit = rng.uniform(-0.075, 0.075, len(s))
            axL.scatter(x + jit, s.pct_error, s=7, marker=MARKER[dev],
                        facecolor="white", edgecolor=core.PALETTE[dev],
                        lw=0.5, alpha=0.9, zorder=5,
                        label=core.DEVICE_LABEL[dev] if (r == 0 and i == 0)
                        else None)
            s["_x"] = x + jit
            pos[dev] = s.set_index("stem")[["_x", "pct_error"]]
        # the pairing: same stem, two handsets
        both = pos["ios"].join(pos["android"], lsuffix="_i", rsuffix="_a",
                               how="inner")
        for _, row in both.iterrows():
            axL.plot([row._x_i, row._x_a], [row.pct_error_i, row.pct_error_a],
                     color=core.PALETTE["muted"], lw=0.3, alpha=0.16, zorder=1)

    lo_y = df[df.measurand == kind].pct_error.min()
    hi_y = df[df.measurand == kind].pct_error.max()
    pad = 0.10 * (hi_y - lo_y)
    axL.set_ylim(lo_y - 2.1 * pad, hi_y + 2.9 * pad)
    for i, site in enumerate(core.SITES):
        n_i = len(cell(df, kind, "ios", site).dropna(subset=["pct_error"]))
        n_a = len(cell(df, kind, "android", site).dropna(subset=["pct_error"]))
        lbl = f"n={n_i}" if n_i == n_a else f"n={n_i}/{n_a}"
        axL.text(i, lo_y - 1.55 * pad, lbl, ha="center", va="center",
                 fontsize=7.5, color=core.PALETTE["muted"])
    axL.set_xticks([0, 1])
    axL.set_xticklabels([f"{s}\n(mean ref "
                         f"{df[(df.measurand == kind) & (df.site == s) & (df.device == 'android')].reference.mean():.0f} {unit})"
                         for s in core.SITES])
    axL.set_xlim(-0.5, 1.5)
    axL.set_ylabel(f"{short} error (% of reference)")

    lines = []
    for dev in core.DEVICES:
        c = tab[(tab.measurand == short)
                & (tab.device == core.DEVICE_SHORT[dev])
                & (tab.site.str.startswith("Difference"))].iloc[0]
        lines.append(f"{core.DEVICE_SHORT[dev]} {c.diff_pct_pp:+.1f} "
                     f"[{c.diff_ci_low:+.1f}, {c.diff_ci_high:+.1f}] pp, "
                     f"t p={c.welch_p:.2f}, U p={c.mwu_p:.2f}")
    axL.text(0.5, 0.985, "site difference (McDunn - Starker):\n"
             + "\n".join(lines), transform=axL.transAxes, va="top",
             ha="center", fontsize=6.9, color=core.PALETTE["reference"],
             bbox=dict(boxstyle="round,pad=0.30", fc="white",
                       ec=core.PALETTE["grid"], lw=0.6, alpha=0.94))
    if r == 0:
        axL.legend(loc="lower left", fontsize=7.2, handlelength=1.0,
                   markerscale=1.5, bbox_to_anchor=(0.005, 0.075))
    core.panel_tag(axL, tags[r][0])

    # ---- right: cumulative distribution of |% error| ---------------------
    axR = axes[r, 1]
    for band in BANDS:
        axR.axvline(band, color=core.PALETTE["muted"], lw=0.7,
                    ls=(0, (2, 2)), zorder=1)
    for dev in core.DEVICES:
        for site in core.SITES:
            s = cell(df, kind, dev, site).pct_error.abs().dropna().to_numpy()
            s = np.sort(s)
            y = np.arange(1, len(s) + 1) / len(s) * 100.0
            xs = np.concatenate([[0], np.repeat(s, 2), [XCAP]])
            ys = np.concatenate([[0], [0], np.repeat(y, 2)[:-1], [y[-1]]])
            axR.plot(xs, ys, color=core.PALETTE[dev], ls=SITE_LS[site],
                     lw=1.5 if site == "McDunn" else 1.3, zorder=3,
                     label=f"{core.DEVICE_SHORT[dev]} · {site}  "
                           f"({within(cell(df, kind, dev, site).pct_error, 5):.0f} / "
                           f"{within(cell(df, kind, dev, site).pct_error, 10):.0f} %)")
            # marker on the line so device survives greyscale
            for band in BANDS:
                axR.plot([band], [within(cell(df, kind, dev, site).pct_error,
                                         band)],
                         marker=MARKER[dev], ms=4.2, mfc="white",
                         mec=core.PALETTE[dev], mew=1.0, zorder=4)
    axR.set_xlim(0, XCAP)
    axR.set_ylim(0, 103)
    axR.set_xlabel(f"Absolute {short.lower() if kind == 'height' else short} "
                   f"error (% of reference)")
    axR.set_ylabel("Stems within (%)")
    axR.set_xticks([0, 5, 10, 15, 20, 25])
    axR.legend(loc="lower right", fontsize=6.4, handlelength=2.0,
               labelspacing=0.35, borderpad=0.3,
               title="device · site  (≤5 / ≤10 %)", title_fontsize=6.4,
               bbox_to_anchor=(1.015, -0.02))
    core.panel_tag(axR, tags[r][1])

axes[1, 1].annotate(
    "iOS height: 43 % of McDunn stems\nwithin 5 %, 68 % at Starker",
    xy=(5.05, 43), xytext=(6.0, 30), fontsize=6.5, va="top", ha="left",
    color=core.PALETTE["reference"],
    arrowprops=dict(arrowstyle="->", lw=0.7, color=core.PALETTE["muted"],
                    shrinkB=1),
    bbox=dict(boxstyle="round,pad=0.30", fc="white",
              ec=core.PALETTE["grid"], lw=0.6, alpha=0.94))

fig.tight_layout(w_pad=2.0, h_pad=2.2)

cap = (
    "Measurement error by stand. (A, C) Percent error for each handset at each "
    "stand; boxes give median and interquartile range, whiskers 1.5x IQR, open "
    "symbols are individual stems and the faint grey segments join the two "
    "handsets on the same stem. The horizontal line is perfect agreement. Inset "
    "gives the McDunn-minus-Starker difference in mean percent error with its "
    "95 % Welch CI in percentage points (pp), the Welch p and the Mann-Whitney "
    "p. No stand contrast reaches significance on either test (Welch "
    "p = 0.22-0.98, Mann-Whitney p = 0.099-0.91, and every p is >= 0.39 after "
    "Holm correction across the four handset x measurand contrasts), and all "
    "four are positively equivalent to zero within a pre-specified +/-5 pp "
    "margin (TOST p = 0.0006-0.041). Shapiro-Wilk rejects normality in six of the "
    "eight groups, so the Mann-Whitney result is the primary read and the "
    "Welch test is reported for its interpretable difference and interval. "
    "(B, D) Cumulative distribution of absolute percent error, one curve per "
    "handset x stand (colour = handset, line style = stand); dotted guides at "
    "the 5 % and 10 % tolerances, with the percentage of stems inside each "
    "given in the legend. Curves are drawn to 25 %, which contains 90-100 % of "
    "each series; the largest single |error| is 53.7 % (DBH, Starker) and "
    "38.0 % (height, McDunn), both on iOS. RMSE in field units (Table 7) is "
    "higher at Starker in two of the four cells (Android DBH 1.6 vs 2.6 in, "
    "Android height 5.7 vs 8.1 ft) and lower in the other two, and the "
    "corresponding percent contrasts are null, so the field-unit gaps track "
    "stem size rather than stand. Stand and stem "
    "size are confounded by design - Starker stems average 21.9 in and "
    "127.6 ft against McDunn's 17.1 in and 70.0 ft - and stand is also "
    "perfectly confounded with day of measurement (McDunn 28 July, Starker "
    "29 July), so no analysis here can separate stand from either. Adjusting "
    "percent error for reference size leaves three of four contrasts null but "
    "raises iOS height to +5.8 pp (p = 0.019); restricting to the 34.6-144.8 ft "
    "range the stands share reproduces it (-3.9 pp, Mann-Whitney p = 0.016) on "
    "only 28 Starker stems, so it is reported as unresolved rather than as a "
    "stand effect. Diameters in inches against a diameter tape, heights in "
    "feet against a laser rangefinder in 3-point mode; n = 50 stems per stand "
    "(49 for iOS height at McDunn). Excluding the 8 stems with a disputed tape "
    "value - 6 of them McDunn diameters - leaves every contrast "
    "non-significant and does not change the conclusion."
)
core.save(fig, "fig07_by_site", cap)


# --------------------------------------------------------------------------
# Table
# --------------------------------------------------------------------------
out = tab.merge(conf, on=["measurand", "device"], how="left")
out.loc[~out.site.str.startswith("Difference"),
        [c for c in conf.columns if c not in ("measurand", "device")]] = np.nan
front = ["measurand", "device", "site", "unit", "n", "ref_mean",
         "bias", "bias_ci_low", "bias_ci_high", "pct_bias", "pct_ci_low",
         "pct_ci_high", "sd_units", "sd_pct", "rmse", "mae", "ccc",
         "within_5pct", "within_10pct"]
out = out[front + [c for c in out.columns if c not in front]]

core.save_table(out.round(4), "t07_by_site", (
    "Accuracy by stand, and the formal stand contrast. Rows named for a stand "
    "carry that stand's accuracy block: bias in field units with a 10 000-draw "
    "percentile bootstrap CI, mean percent error with its bootstrap CI, SD in "
    "both scales, RMSE, MAE and Lin's concordance correlation coefficient, "
    "plus the share of stems inside the 5 % and 10 % tolerances. The "
    "'Difference (McDunn - Starker)' row carries the contrast on PERCENT "
    "error: the Welch difference in pp with its 95 % CI, Welch t/df/p, "
    "Mann-Whitney U and p, the Hodges-Lehmann shift with its rank-based CI, "
    "Holm-adjusted p across the four contrasts, and a two-sample TOST against "
    "a +/-5 pp margin fixed in advance. Levene tests are given on both scales "
    "because they disagree, and Shapiro-Wilk p per group documents why "
    "Mann-Whitney leads. The same row also carries the two confound checks: "
    "the stand coefficient from percent error regressed on stand and "
    "reference size (HC3 robust), and the contrast re-run on the size range "
    "the two stands share. Diameters in inches, heights in feet."
))


# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 250, "display.max_columns", 60)
site_rows = tab[~tab.site.str.startswith("Difference")]
diff_rows = tab[tab.site.str.startswith("Difference")]

print("\n=== accuracy by stand ===")
print(site_rows[["measurand", "device", "site", "n", "ref_mean", "bias",
                 "bias_ci_low", "bias_ci_high", "pct_bias", "sd_pct", "rmse",
                 "mae", "ccc", "within_5pct", "within_10pct"]]
      .round(2).to_string(index=False))

print("\n=== stand contrast on PERCENT error (McDunn - Starker) ===")
print(diff_rows[["measurand", "device", "diff_pct_pp", "diff_ci_low",
                 "diff_ci_high", "welch_p", "welch_p_holm", "mwu_p",
                 "mwu_p_holm", "hl_shift_pp", "hl_ci_low", "hl_ci_high",
                 "tost_p", "equivalent_5pp"]].round(4).to_string(index=False))

print("\n=== same contrast in FIELD UNITS, and dispersion ===")
print(diff_rows[["measurand", "device", "unit", "diff_units", "diff_units_p",
                 "levene_pct_p", "levene_units_p", "shapiro_mcdunn_p",
                 "shapiro_starker_p"]].round(4).to_string(index=False))

print("\n=== confound checks: is a stand effect a size effect? ===")
print(conf.round(4).to_string(index=False))

print("\n=== sensitivity: excluding the 8 disputed-tape stems ===")
d2 = tab_nodisp[tab_nodisp.site.str.startswith("Difference")]
merged = diff_rows.merge(d2, on=["measurand", "device"], suffixes=("", "_nd"))
print(merged[["measurand", "device", "n", "n_nd", "diff_pct_pp",
              "diff_pct_pp_nd", "welch_p", "welch_p_nd", "mwu_p", "mwu_p_nd",
              "tost_p", "tost_p_nd"]].round(4).to_string(index=False))
print("disputed-tape stems by stand:")
print(df[df.device == "android"].groupby(["measurand", "site"])
      .tape_disputed.sum().to_string())
print("\n(confound checks, disputed excluded)")
print(conf_nodisp[["measurand", "device", "adj_site_coef_pp", "adj_p",
                   "overlap_diff_pp", "overlap_welch_p",
                   "overlap_mwu_p"]].round(4).to_string(index=False))

print("\n=== what could explain a stand difference? ===")
print("species recorded per stand (n stems / n with a species):")
print(species_cover.to_string())
print("\nreference size by stand:")
print(df[df.device == "android"].groupby(["measurand", "site"])
      .reference.agg(["mean", "std", "min", "max"]).round(1).to_string())
print("\napp-reported sigma by stand (field units):")
print(sigma_by_site.round(3).to_string())
print("\nmeasurement dates by stand:")
raw = pd.read_csv(core.PAIRS)
raw["date"] = pd.to_datetime(raw["ios_time"]).dt.date.astype(str)
print(raw.groupby(["plot", "kind", "date"]).size().to_string())
