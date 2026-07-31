#!/usr/bin/env python3
"""Cross-platform agreement: does the answer depend on WHICH PHONE the cruiser carries?

Paired by construction — every stem was measured by both handsets against one
tape/laser reading, so the iOS-Android contrast is a within-stem difference and
is analysed that way. Neither device is a reference for the other, so the
Bland-Altman abscissa here IS the mean of the two (unlike the device-vs-tape
figures, where plotting against the mean would induce a slope out of nothing).
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()


def para(text: str) -> str:
    """Collapse an f-string's source line breaks so the caption is one paragraph."""
    return " ".join(text.split())

# --------------------------------------------------------------------------
# Assemble the paired sets, carrying the tape-dispute flag through
# --------------------------------------------------------------------------

def paired_flagged(measurand: str) -> pd.DataFrame:
    p = core.paired(df, measurand)
    flag = (df[df.measurand == measurand]
            .groupby("stem")["tape_disputed"].any().rename("tape_disputed"))
    p = p.merge(flag, on="stem", how="left")
    p["tape_disputed"] = p["tape_disputed"].fillna(False)
    return p


PAIRS = {m: paired_flagged(m) for m in core.MEASURANDS}

# TOST margins. Declared here, not read off the result: a cruiser records DBH
# to the nearest inch and height to the nearest 5 ft on a standard tally sheet,
# so a handset-to-handset discrepancy smaller than one recording increment
# cannot change what gets written down.
TOST_BOUND = {"dbh": 1.0, "height": 5.0}


# --------------------------------------------------------------------------
# Statistics for one paired set
# --------------------------------------------------------------------------

def block(p: pd.DataFrame, measurand: str, label: str) -> dict:
    d = p["delta"].to_numpy(float)          # iOS - Android
    x = p["measured_android"].to_numpy(float)
    y = p["measured_ios"].to_numpy(float)
    n = len(d)
    mean, sd = d.mean(), d.std(ddof=1)
    lo, hi = core.bootstrap_ci(d)
    slope_dem, icpt_dem = core.deming(x, y, lam=1.0)
    r, r_p = sps.pearsonr(x, y)

    # Normality of the DIFFERENCES — the assumption the paired t-test rests on.
    sw_W, sw_p = sps.shapiro(d)
    t_stat, t_p = sps.ttest_rel(y, x)
    try:
        w_stat, w_p = sps.wilcoxon(d, zero_method="wilcox", alternative="two-sided")
    except ValueError:
        w_stat, w_p = np.nan, np.nan
    # Hodges-Lehmann: the location estimate the Wilcoxon test is actually about.
    walsh = (d[:, None] + d[None, :])[np.triu_indices(n)] / 2.0
    hl = float(np.median(walsh))

    # Proportional bias: does the gap grow with tree size?
    pb_slope, pb_icpt, _, pb_p, pb_se = sps.linregress(p["mean_measured"], d)

    eq = core.tost(d, TOST_BOUND[measurand])

    return dict(
        measurand=core.MEASURANDS[measurand]["short"],
        unit=core.MEASURANDS[measurand]["unit"],
        subset=label,
        n=n,
        mean_diff=mean, ci_low=lo, ci_high=hi, sd=sd,
        loa_low=mean - 1.96 * sd, loa_high=mean + 1.96 * sd,
        # Nonparametric limits: the empirical central 95 % of differences, which
        # do not assume the differences are normal. Reported beside the +/-1.96 SD
        # limits because Shapiro-Wilk rejects normality for most subsets here.
        loa_np_low=float(np.percentile(d, 2.5)),
        loa_np_high=float(np.percentile(d, 97.5)),
        ccc=core.ccc(x, y), pearson_r=r,
        deming_slope=slope_dem, deming_intercept=icpt_dem,
        median_diff=float(np.median(d)), hodges_lehmann=hl,
        iqr_diff=float(np.percentile(d, 75) - np.percentile(d, 25)),
        skew=float(sps.skew(d)), excess_kurtosis=float(sps.kurtosis(d)),
        shapiro_W=sw_W, shapiro_p=sw_p,
        normal_diffs=bool(sw_p >= 0.05),
        t_stat=t_stat, t_p=t_p,
        wilcoxon_stat=w_stat, wilcoxon_p=w_p,
        propbias_slope=pb_slope, propbias_p=pb_p,
        tost_bound=TOST_BOUND[measurand], tost_p=eq["p"],
        tost_equivalent=bool(eq["equivalent"]),
    )


rows = []
for m, p in PAIRS.items():
    rows.append(block(p, m, "Pooled"))
    for site in core.SITES:
        s = p[p.site == site]
        if len(s) >= 3:
            rows.append(block(s, m, site))
    # Sensitivity: the stems whose tape reading is disputed (6 of the DBH stems,
    # 2 of the height stems — eight stem-measurand records in all). This contrast
    # never touches the tape, so the check should and does come back null.
    keep = p[~p.tape_disputed]
    rows.append(block(keep, m, "Pooled, tape-disputed excluded"))

table = pd.DataFrame(rows)

# --------------------------------------------------------------------------
# Operational consequence: does the handset move a stem into another class?
# --------------------------------------------------------------------------
ops = {}
for m, p in PAIRS.items():
    cls_i = p["measured_ios"].apply(lambda v: core.size_class(v, m))
    cls_a = p["measured_android"].apply(lambda v: core.size_class(v, m))
    ops[m] = dict(
        n=len(p),
        class_disagree=int((cls_i != cls_a).sum()),
        class_disagree_pct=100.0 * (cls_i != cls_a).mean(),
        p95_abs_diff=float(np.percentile(p["delta"].abs(), 95)),
        max_abs_diff=float(p["delta"].abs().max()),
        frac_over_bound=100.0 * (p["delta"].abs() > TOST_BOUND[m]).mean(),
    )

# Basal area, the number a cruise actually sells. Per-stem BA in ft^2 from
# DBH in inches: 0.005454 * D^2.
pd_ = PAIRS["dbh"]
ba_ios = 0.005454 * pd_["measured_ios"] ** 2
ba_and = 0.005454 * pd_["measured_android"] ** 2
ba_ref = 0.005454 * pd_["reference"] ** 2
ba_stand_pct = 100.0 * (ba_ios.sum() - ba_and.sum()) / ba_and.sum()
ba_stem_pct = float((100.0 * (ba_ios - ba_and) / ba_and).abs().median())

# --------------------------------------------------------------------------
# Diagnostics the pooled table cannot carry
# --------------------------------------------------------------------------
diag = {}
for m, p in PAIRS.items():
    # Do the two sites share a difference variance? Brown-Forsythe (median-centred
    # Levene) because the differences are not normal.
    a = p[p.site == "McDunn"].delta.to_numpy(float)
    b = p[p.site == "Starker"].delta.to_numpy(float)
    lev_W, lev_p = sps.levene(a, b, center="median")
    # Are the two handsets' errors against the tape independent, or does a hard
    # stem defeat both? Determines whether disagreement is device-specific.
    sub = df[df.measurand == m]
    e = sub.pivot_table(index="stem", columns="device", values="error",
                        aggfunc="first").dropna()
    r_err, p_err = sps.pearsonr(e["ios"], e["android"])
    diag[m] = dict(levene_W=lev_W, levene_p=lev_p,
                   sd_mcdunn=a.std(ddof=1), sd_starker=b.std(ddof=1),
                   err_r=r_err, err_p=p_err,
                   rmse_ios=core.rmse(sub[sub.device == "ios"].error),
                   rmse_android=core.rmse(sub[sub.device == "android"].error),
                   sd_delta=p.delta.std(ddof=1))

# How much of each headline rests on a handful of stems? Refit after dropping the
# two largest |difference| stems in the subset, and report BOTH numbers rather
# than the more convenient one.
infl = {}
for m, p in PAIRS.items():
    q = p.assign(ad=p.delta.abs())
    for label, s in [("Pooled", q)] + [(st, q[q.site == st]) for st in core.SITES]:
        drop = s.nlargest(2, "ad")
        k = s[~s.stem.isin(drop.stem)]
        full = sps.linregress(s.mean_measured, s.delta)
        cut = sps.linregress(k.mean_measured, k.delta)
        infl[(m, label)] = dict(
            dropped=list(drop.stem), n_kept=len(k),
            mean_full=s.delta.mean(), mean_cut=k.delta.mean(),
            sd_full=s.delta.std(ddof=1), sd_cut=k.delta.std(ddof=1),
            slope_full=full.slope, p_full=full.pvalue,
            slope_cut=cut.slope, p_cut=cut.pvalue)

# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
# Top row is 1:1 (square, forced); bottom row is Bland-Altman (rectangular is
# correct there — the axes carry different quantities). Sized so the squares
# come out square and the whole figure stays wide enough for a slide.
fig, axes = plt.subplots(2, 2, figsize=(core.FIG_W, core.FIG_W * 0.62))
MARK = {"McDunn": "o", "Starker": "^"}
LETTERS = [["A", "B"], ["C", "D"]]

for j, m in enumerate(["dbh", "height"]):
    p = PAIRS[m]
    meta = core.MEASURANDS[m]
    u = meta["unit"]
    stats_row = table[(table.measurand == meta["short"]) & (table.subset == "Pooled")].iloc[0]

    # ---- Top row: iOS vs Android ----------------------------------------
    ax = axes[0, j]
    lo = min(p.measured_ios.min(), p.measured_android.min())
    hi = max(p.measured_ios.max(), p.measured_android.max())
    pad = 0.06 * (hi - lo)
    lims = (lo - pad, hi + pad)
    ax.plot(lims, lims, color=core.PALETTE["reference"], lw=1.0, ls="--",
            zorder=1, label="1:1")
    xs = np.array(lims)
    ax.plot(xs, stats_row.deming_slope * xs + stats_row.deming_intercept,
            color=core.PALETTE["accent"], lw=1.4, ls="-", zorder=2, label="Deming")
    for site in core.SITES:
        s = p[p.site == site]
        ax.scatter(s.measured_android, s.measured_ios, s=22,
                   marker=MARK[site], facecolors="none", linewidths=0.9,
                   edgecolors=core.PALETTE["site"][site], zorder=3, label=site)
    ax.set_xlim(*lims)
    ax.set_ylim(*lims)
    ax.set_aspect("equal", adjustable="box")
    ax.set_xlabel(f"Android (ARCore) {meta['short']} ({u})")
    ax.set_ylabel(f"iOS (LiDAR) {meta['short']} ({u})")
    ax.text(0.03, 0.97,
            f"CCC = {stats_row.ccc:.3f}\nDeming slope = {stats_row.deming_slope:.3f}\n"
            f"n = {int(stats_row.n)} stems",
            transform=ax.transAxes, va="top", ha="left", fontsize=7.5,
            bbox=dict(boxstyle="round,pad=0.32", fc="white", ec=core.PALETTE["grid"],
                      lw=0.6, alpha=0.92))
    if j == 0:
        ax.legend(loc="lower right", fontsize=6.8, handletextpad=0.4,
                  borderpad=0.2, labelspacing=0.25)
    core.panel_tag(ax, LETTERS[0][j])

    # ---- Bottom row: Bland-Altman, iOS - Android vs their mean ----------
    ax = axes[1, j]
    bias = stats_row.mean_diff
    ax.axhline(0, color=core.PALETTE["muted"], lw=0.8, ls=":", zorder=1)
    ax.axhline(bias, color=core.PALETTE["accent"], lw=1.4, zorder=4,
               label="mean difference")
    for k, lvl in enumerate((stats_row.loa_low, stats_row.loa_high)):
        ax.axhline(lvl, color=core.PALETTE["accent"], lw=1.0, ls="--", zorder=4,
                   label="95 % LoA (±1.96 SD)" if k == 0 else None)
    # The differences are not normal (see table), so the empirical central 95 %
    # is shown alongside the SD-based limits rather than instead of the caveat.
    for k, lvl in enumerate((stats_row.loa_np_low, stats_row.loa_np_high)):
        ax.axhline(lvl, color=core.PALETTE["reference"], lw=0.9, ls=(0, (1, 2)),
                   zorder=4, label="2.5–97.5 percentile" if k == 0 else None)
    for site in core.SITES:
        # Site key lives in the top row; repeating it here only crowds the panel.
        s = p[p.site == site]
        ax.scatter(s.mean_measured, s.delta, s=22, marker=MARK[site],
                   facecolors="none", linewidths=0.9,
                   edgecolors=core.PALETTE["site"][site], zorder=3)
    ax.set_xlabel(f"Mean of the two handsets, {meta['short']} ({u})")
    ax.set_ylabel(f"iOS − Android ({u})")

    xr = ax.get_xlim()
    ax.set_xlim(*xr)
    yr = ax.get_ylim()
    ax.set_ylim(yr[0], yr[0] + (yr[1] - yr[0]) * 1.22)   # headroom for the box
    ax.text(xr[1], bias, f" bias {bias:+.2f} {u} ", va="center", ha="right",
            fontsize=7.2, color=core.PALETTE["accent"], fontweight="bold",
            zorder=6,
            bbox=dict(boxstyle="round,pad=0.18", fc="white", ec="none", alpha=0.88))
    ax.text(xr[1], stats_row.loa_high, f" +1.96 SD {stats_row.loa_high:+.2f} ",
            va="bottom", ha="right", fontsize=6.8, color=core.PALETTE["accent"],
            zorder=6)
    ax.text(xr[1], stats_row.loa_low, f" −1.96 SD {stats_row.loa_low:+.2f} ",
            va="top", ha="right", fontsize=6.8, color=core.PALETTE["accent"], zorder=6)
    ax.text(xr[0], stats_row.loa_np_low, f" {stats_row.loa_np_low:+.1f} ",
            va="top", ha="left", fontsize=6.8, color=core.PALETTE["reference"], zorder=6)
    ax.text(xr[0], stats_row.loa_np_high, f" {stats_row.loa_np_high:+.1f} ",
            va="bottom", ha="left", fontsize=6.8, color=core.PALETTE["reference"],
            zorder=6)
    ax.text(0.02, 0.98,
            f"95 % LoA span {stats_row.loa_high - stats_row.loa_low:.1f} {u}\n"
            f"|Δ| > {TOST_BOUND[m]:g} {u} on {ops[m]['frac_over_bound']:.0f} % of stems",
            transform=ax.transAxes, va="top", ha="left", fontsize=7.0, zorder=6,
            bbox=dict(boxstyle="round,pad=0.3", fc="white", ec=core.PALETTE["grid"],
                      lw=0.6, alpha=0.94))
    if j == 0:
        ax.legend(loc="upper right", fontsize=6.4, handletextpad=0.5,
                  borderpad=0.3, labelspacing=0.3, handlelength=2.2)
    core.panel_tag(ax, LETTERS[1][j])

fig.tight_layout(w_pad=1.6, h_pad=1.8)

dbh_p = table[(table.measurand == "DBH") & (table.subset == "Pooled")].iloc[0]
hgt_p = table[(table.measurand == "Height") & (table.subset == "Pooled")].iloc[0]

caption = f"""
Cross-platform agreement between the two handsets on the same stems
({int(dbh_p.n)} for DBH, {int(hgt_p.n)} for height, 50 per site; one iOS height was
typed rather than measured and is excluded). (A, B) iOS against Android for diameter
at breast height (in) and total height (ft), with the 1:1 line (dashed) and a Deming
fit (solid, error-variance ratio 1, the defensible choice because neither handset is
a reference). (C, D) Bland-Altman of the iOS minus Android difference against the
mean of the two; the mean is the correct abscissa here precisely because neither
device is a reference. Solid line, mean difference; dashed lines, 95 % limits of
agreement (mean +/- 1.96 SD); fine dotted lines, the empirical 2.5th and 97.5th
percentiles, shown because Shapiro-Wilk rejects normality of the differences for
both measurands (DBH p = {dbh_p.shapiro_p:.1e}, height p = {hgt_p.shapiro_p:.1e}) and
the SD-based limits are therefore approximate. Marker shape and colour both encode
site. The two handsets agree on the stand in the mean for DBH, where the mean
difference is only {dbh_p.mean_diff:.2f} in (95 % bootstrap CI {dbh_p.ci_low:+.2f} to
{dbh_p.ci_high:+.2f}, Wilcoxon p = {dbh_p.wilcoxon_p:.3f}), but iOS reads height
{abs(hgt_p.mean_diff):.2f} ft lower than Android on average
({hgt_p.ci_low:+.2f} to {hgt_p.ci_high:+.2f}; Wilcoxon p = {hgt_p.wilcoxon_p:.4f},
Hodges-Lehmann {hgt_p.hodges_lehmann:+.2f} ft). Agreement on individual stems is much
weaker than either mean suggests: the limits of agreement span
{dbh_p.loa_high - dbh_p.loa_low:.1f} in and {hgt_p.loa_high - hgt_p.loa_low:.1f} ft,
and the handsets place {ops['dbh']['class_disagree']} of {ops['dbh']['n']} stems in
different diameter classes. The height differences are also markedly more variable at
Starker than at McDunn (SD {diag['height']['sd_starker']:.1f} vs
{diag['height']['sd_mcdunn']:.1f} ft, Brown-Forsythe p = {diag['height']['levene_p']:.4f}),
so the pooled limits in panel D overstate the spread at one site and understate it at
the other.
"""
core.save(fig, "fig04_crossplatform", para(caption))

# --------------------------------------------------------------------------
# Table
# --------------------------------------------------------------------------
out = table.copy()
conv = {"in": core.CM_PER_IN, "ft": core.M_PER_FT}
out["mean_diff_metric"] = [r.mean_diff * conv[r.unit] for r in out.itertuples()]
out["metric_unit"] = out.unit.map({"in": "cm", "ft": "m"})
cols = ["measurand", "unit", "subset", "n", "mean_diff", "ci_low", "ci_high",
        "mean_diff_metric", "metric_unit", "sd", "loa_low", "loa_high",
        "loa_np_low", "loa_np_high",
        "ccc", "pearson_r", "deming_slope", "deming_intercept",
        "median_diff", "hodges_lehmann", "iqr_diff", "skew", "excess_kurtosis",
        "shapiro_W", "shapiro_p", "normal_diffs",
        "t_stat", "t_p", "wilcoxon_stat", "wilcoxon_p",
        "propbias_slope", "propbias_p",
        "tost_bound", "tost_p", "tost_equivalent"]
out = out[cols]
for c in out.columns:
    if out[c].dtype.kind == "f":
        out[c] = out[c].round(4)

tcap = f"""
Handset-to-handset agreement (iOS minus Android) on the same stems, pooled and by
site, with the tape-disputed stems excluded as a sensitivity check. Differences are
within-stem, so every statistic is paired. CI is a seeded 10 000-draw percentile
bootstrap of the mean difference; `loa_low`/`loa_high` are mean +/- 1.96 SD and
`loa_np_low`/`loa_np_high` are the empirical 2.5th and 97.5th percentiles of the
differences, which make no normality assumption - read the latter wherever
`normal_diffs` is False. Deming slope regresses iOS on Android with error-variance
ratio 1, which is the defensible choice here because neither handset is a reference.
Both a paired t-test and a Wilcoxon signed-rank test are given; `normal_diffs`
records whether Shapiro-Wilk failed to reject normality of the differences, and
where it is False the Wilcoxon result and the Hodges-Lehmann location estimate are
the ones to read. `propbias_slope` regresses the difference on the mean of the two
handsets, testing whether the gap grows with tree size. TOST margins are one DBH
inch and 5 ft of height - one tally-sheet recording increment - and were fixed
before the tests were run, not chosen from the result; note that TOST is itself
t-based and so inherits the same normality assumption, though with n around 100
the central limit theorem makes the mean-based inference far more robust than the
+/-1.96 SD limits, which describe individual stems and do not benefit from it.
The cross-platform contrast never uses the tape, so a disputed tape value cannot
bias it by construction; the exclusion rows confirm this empirically. One caution
on `propbias_slope`: the only significant entry, McDunn DBH, rests on two stems -
dropping the two largest absolute differences (McD048, McD022) takes the slope from
+0.194 in per inch (p = 0.0007) to +0.074 (p = 0.128), so it should be read as
outlier influence rather than as an established proportional trend.
"""
core.save_table(out, "t04_crossplatform", para(tcap))

# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 220, "display.max_columns", 60)
print(out.to_string(index=False))
print()
for m in core.MEASURANDS:
    meta = core.MEASURANDS[m]
    o = ops[m]
    print(f"{meta['short']}: |diff| > {TOST_BOUND[m]} {meta['unit']} on "
          f"{o['frac_over_bound']:.0f}% of stems; p95 |diff| = {o['p95_abs_diff']:.2f} "
          f"{meta['unit']}; max {o['max_abs_diff']:.2f}; size-class disagreement on "
          f"{o['class_disagree']}/{o['n']} stems ({o['class_disagree_pct']:.0f}%)")
print(f"\nStand basal area: iOS total {ba_ios.sum():.1f} ft2, Android "
      f"{ba_and.sum():.1f} ft2, tape {ba_ref.sum():.1f} ft2 -> "
      f"iOS vs Android {ba_stand_pct:+.2f}% at stand level; "
      f"median per-stem |BA difference| {ba_stem_pct:.1f}%")
print()
for m, d_ in diag.items():
    u = core.MEASURANDS[m]["unit"]
    print(f"{core.MEASURANDS[m]['short']}: difference SD McDunn {d_['sd_mcdunn']:.2f} vs "
          f"Starker {d_['sd_starker']:.2f} {u}, Brown-Forsythe p={d_['levene_p']:.4f}; "
          f"tape-error correlation between handsets r={d_['err_r']:.3f} "
          f"(p={d_['err_p']:.2g}); RMSE vs tape iOS {d_['rmse_ios']:.2f} / Android "
          f"{d_['rmse_android']:.2f}, SD of handset difference {d_['sd_delta']:.2f} "
          f"(independent errors would give "
          f"{np.hypot(d_['rmse_ios'], d_['rmse_android']):.2f})")
print()
for (m, label), v in infl.items():
    u = core.MEASURANDS[m]["unit"]
    print(f"{core.MEASURANDS[m]['short']:6s} {label:8s} drop {','.join(v['dropped'])}: "
          f"mean {v['mean_full']:+.2f} -> {v['mean_cut']:+.2f} {u}, "
          f"SD {v['sd_full']:.2f} -> {v['sd_cut']:.2f}, "
          f"prop-bias slope {v['slope_full']:+.3f} (p={v['p_full']:.4f}) -> "
          f"{v['slope_cut']:+.3f} (p={v['p_cut']:.4f})")
