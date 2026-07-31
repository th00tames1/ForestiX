#!/usr/bin/env python3
"""Bland-Altman agreement with the reference, and the size trend in the difference.

Difference (phone minus reference) is plotted against the REFERENCE, not against
the mean of the two, for the reason core.bland_altman gives: the tape and the
laser are the standard here, and regressing a difference on a mean that contains
half of that difference manufactures a slope.

The extra question this script answers is whether the difference TRENDS with
size. It does, in three of the four panels, and the trend is what a constant
percentage error looks like when you plot it in absolute units. A flat bias line
and a flat limit-of-agreement band would therefore understate the error on big
stems and overstate it on small ones, so regression-based (proportional) limits
are drawn alongside the constant ones, following Bland & Altman (1999): the
difference is regressed on the reference, the ABSOLUTE RESIDUALS are regressed
on the reference too, and the limits are

    d_hat(x)  +/-  2.46 * |resid|_hat(x)          2.46 = 1.96 * sqrt(pi/2)

which lets the band both tilt and fan.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import statsmodels.api as sm
import statsmodels.formula.api as smf
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()

K = 2.46  # 1.96 * sqrt(pi/2): |residual| -> SD under normality
SITE_MARKER = {"McDunn": "o", "Starker": "^"}
REF_NAME = {"dbh": "tape", "height": "laser"}
AXIS_NAME = {"dbh": "DBH", "height": "height"}
# The site colours ARE the device colours in core.PALETTE, so the fitted lines
# cannot use the device colour here: in panel A a blue line would read as a
# McDunn line. The proportional fit gets its own hue and its own dash pattern.
FIT_C = core.PALETTE["warn"]
FLAT_C = core.PALETTE["reference"]


# --------------------------------------------------------------------------
# statistics for one cell
# --------------------------------------------------------------------------

def trend(x, d):
    """Regress difference on reference. Slope, its 95 % CI, p, and the spread fit."""
    x = np.asarray(x, float)
    d = np.asarray(d, float)
    X = sm.add_constant(x)
    fit = sm.OLS(d, X).fit()
    ci = fit.conf_int(0.05)[1]
    res = sm.OLS(np.abs(fit.resid), X).fit()  # |residual| vs size: does spread fan?
    return dict(
        slope=fit.params[1], slope_lo=ci[0], slope_hi=ci[1], p=fit.pvalues[1],
        intercept=fit.params[0], r2=fit.rsquared,
        spread_slope=res.params[1], spread_intercept=res.params[0],
        spread_p=res.pvalues[1],
    )


def prop_limits(t, x):
    """Fitted difference and the proportional 95 % limits at abscissa x."""
    x = np.asarray(x, float)
    centre = t["intercept"] + t["slope"] * x
    halfwidth = K * np.clip(t["spread_intercept"] + t["spread_slope"] * x, 0, None)
    return centre, centre - halfwidth, centre + halfwidth


def cell(sub: pd.DataFrame) -> dict:
    """Every number the table reports for one measurand x device x scope."""
    x, d = sub.reference.values, sub.error.values
    ba = core.bland_altman(sub.measured, sub.reference)
    t = trend(x, d)
    lo, hi = core.bootstrap_ci(d)
    _, plo, phi = prop_limits(t, x)
    inside_flat = np.mean((d >= ba["loa_low"]) & (d <= ba["loa_high"])) * 100
    inside_prop = np.mean((d >= plo) & (d <= phi)) * 100
    return dict(
        n=ba["n"], bias=ba["bias"], bias_ci_low=lo, bias_ci_high=hi,
        sd=ba["sd"], loa_low=ba["loa_low"], loa_high=ba["loa_high"],
        pct_bias=sub.pct_error.mean(), pct_sd=sub.pct_error.std(ddof=1),
        trend_slope=t["slope"], slope_ci_low=t["slope_lo"],
        slope_ci_high=t["slope_hi"], trend_p=t["p"], trend_r2=t["r2"],
        spread_slope=t["spread_slope"], spread_p=t["spread_p"],
        shapiro_p=sps.shapiro(d).pvalue,
        pct_inside_flat_loa=inside_flat, pct_inside_prop_loa=inside_prop,
        # slope-1 of a Deming fit estimates the same proportional trend without
        # the attenuation that the shared reference induces in the OLS slope
        deming_slope_minus_1=core.deming(x, sub.measured)[0] - 1.0,
        _t=t,
    )


def fmt_p(p):
    return "p < 0.001" if p < 0.001 else f"p = {p:.3f}"


def sgn(v, dp=2):
    """Signed number with a typographic minus, to match the tick labels."""
    return f"{v:+.{dp}f}".replace("-", "−")


# --------------------------------------------------------------------------
# table: every measurand x device, pooled and by site, with the disputed-tape
# sensitivity carried in the same row so the two are read together
# --------------------------------------------------------------------------

def site_models(sub: pd.DataFrame) -> dict:
    """The trend once the two stands are allowed to differ.

    Stem size and stand are confounded in this sample - Starker carries the
    larger stems - so a slope fitted to the pooled data is part size effect and
    part stand effect. Two extra fits separate them: a common slope with a stand
    intercept, and a stand-by-size interaction that tests whether one slope is
    defensible at all.
    """
    adj = smf.ols("error ~ reference + C(site)", data=sub).fit()
    inter = smf.ols("error ~ reference * C(site)", data=sub).fit()
    ikey = [t for t in inter.params.index if ":" in t][0]
    skey = [t for t in adj.params.index if t.startswith("C(site)")][0]
    return dict(
        trend_slope_site_adj=adj.params["reference"],
        trend_p_site_adj=adj.pvalues["reference"],
        site_offset=adj.params[skey], site_offset_p=adj.pvalues[skey],
        site_x_size_diff=inter.params[ikey], site_x_size_p=inter.pvalues[ikey],
    )


rows = []
for k in core.MEASURANDS:
    unit = core.MEASURANDS[k]["unit"]
    for dev in core.DEVICES:
        base = df[(df.measurand == k) & (df.device == dev)]
        for scope in ["Both sites"] + core.SITES:
            sub = base if scope == "Both sites" else base[base.site == scope]
            if len(sub) < 5:
                continue
            c = cell(sub)
            c.pop("_t")
            keep = sub[~sub.tape_disputed]
            n_disp = int(sub.tape_disputed.sum())
            if n_disp and len(keep) >= 5:
                ck = cell(keep)
                sens = dict(n_disputed=n_disp, n_excl=ck["n"],
                            bias_excl=ck["bias"], sd_excl=ck["sd"],
                            trend_slope_excl=ck["trend_slope"],
                            trend_p_excl=ck["trend_p"])
            else:
                sens = dict(n_disputed=n_disp, n_excl=c["n"], bias_excl=c["bias"],
                            sd_excl=c["sd"], trend_slope_excl=c["trend_slope"],
                            trend_p_excl=c["trend_p"])
            # the stand/size separation only means anything on the pooled row
            sm_ = site_models(sub) if scope == "Both sites" else {}
            rows.append(dict(measurand=core.MEASURANDS[k]["short"],
                             device=core.DEVICE_SHORT[dev], scope=scope,
                             unit=unit, **c, **sm_, **sens))

tab = pd.DataFrame(rows)
floatcols = tab.select_dtypes(include=[float]).columns
tab[floatcols] = tab[floatcols].round(4)

core.save_table(
    tab, "t03_bland_altman",
    "Table 3. Bland-Altman agreement of the ForestiX phone measurement with the "
    "field reference (diameter tape for DBH, laser rangefinder in 3-point mode "
    "for height), for each measurand and handset, pooled over the two stands and "
    "within each stand. Bias is the mean of (phone - reference) with a 10 000-draw "
    "percentile bootstrap CI; LoA are the conventional bias +/- 1.96 SD limits. "
    "trend_slope is the OLS slope of the difference on the reference, with its 95 % "
    "CI and p; a non-zero slope means the error is proportional rather than "
    "constant and the flat LoA are misleading. spread_slope is the slope of the "
    "absolute residual on the reference, i.e. whether the scatter also fans out. "
    "Stand and stem size are confounded in this sample - Starker carries the "
    "larger stems - so the pooled rows also carry trend_slope_site_adj, the size "
    "trend with a stand intercept in the model, and site_x_size_p, the test of "
    "whether the two stands share one slope at all; where that interaction is "
    "significant the pooled trend_slope is not a single device property and the "
    "two stand rows should be read instead. "
    "shapiro_p tests the normality that the +/-1.96 SD limits assume; "
    "pct_inside_flat_loa and pct_inside_prop_loa give the observed coverage of the "
    "constant and the regression-based limits against the nominal 95 %. Columns "
    "ending _excl repeat bias, SD and trend with the n_disputed stems whose tape "
    "reading is disputed removed. Diameters in inches, heights in feet.")


# --------------------------------------------------------------------------
# figure
# --------------------------------------------------------------------------

TAG = {("dbh", "ios"): "A", ("dbh", "android"): "B",
       ("height", "ios"): "C", ("height", "android"): "D"}

sitemod, by_site = {}, {}
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        sub_ = df[(df.measurand == k) & (df.device == dev)]
        sitemod[(k, dev)] = site_models(sub_)
        for site in core.SITES:
            by_site[(k, dev, site)] = cell(sub_[sub_.site == site])


# Sized for a full-width (170 mm) reproduction: four annotated panels cannot be
# read at 85 mm, and shrinking the type to pretend otherwise would be worse.
fig, axes = plt.subplots(2, 2, figsize=(core.FIG_W, core.FIG_H * 1.42))
tags = [["A", "B"], ["C", "D"]]
panel_notes = []

for i, k in enumerate(core.MEASURANDS):
    unit = core.MEASURANDS[k]["unit"]
    row = df[df.measurand == k]
    rng = row.reference.max() - row.reference.min()
    # extra room on the left: the constant lines are labelled in that strip
    xlo = row.reference.min() - 0.15 * rng
    xhi = row.reference.max() + 0.04 * rng

    # one y-scale per row, so the two handsets are compared and not merely
    # each described on its own axis. Everything drawn has to fit.
    cells, grids = {}, {}
    lo_v, hi_v = [], []
    for dev in core.DEVICES:
        sub = row[row.device == dev]
        c = cell(sub)
        cells[dev] = c
        gx = np.linspace(sub.reference.min(), sub.reference.max(), 200)
        ctr, plo, phi = prop_limits(c["_t"], gx)
        grids[dev] = (gx, ctr, plo, phi)
        lo_v += [sub.error.min(), c["loa_low"], plo.min()]
        hi_v += [sub.error.max(), c["loa_high"], phi.max()]
    ylo, yhi = min(lo_v), max(hi_v)
    yr = yhi - ylo
    # headroom for the stats box, on the side the trend leaves empty
    up = any(cells[d]["trend_slope"] > 0 for d in core.DEVICES)
    ylo -= yr * (0.08 + (0.0 if up else 0.26))
    yhi += yr * (0.08 + (0.26 if up else 0.0))

    for j, dev in enumerate(core.DEVICES):
        ax = axes[i][j]
        sub = row[row.device == dev]
        c = cells[dev]

        ax.axhline(0, color=core.PALETTE["muted"], lw=0.7, zorder=1)

        # constant Bland-Altman band
        ax.axhspan(c["loa_low"], c["loa_high"], color=core.PALETTE["grid"],
                   alpha=0.5, zorder=0, lw=0)
        ax.axhline(c["bias"], color=FLAT_C, lw=1.5, zorder=4)
        for y in (c["loa_low"], c["loa_high"]):
            ax.axhline(y, color=FLAT_C, lw=1.0, ls=(0, (5, 3)), zorder=4)

        # points, by site: colour AND marker, so greyscale still separates them
        for site in core.SITES:
            s = sub[sub.site == site]
            ax.scatter(s.reference, s.measured - s.reference,
                       s=24, marker=SITE_MARKER[site],
                       facecolor=core.PALETTE["site"][site], alpha=0.8,
                       edgecolor="white", linewidth=0.4, zorder=3)
            d = s[s.tape_disputed]
            if len(d):
                ax.scatter(d.reference, d.measured - d.reference, s=62,
                           marker="o", facecolor="none", edgecolor=FLAT_C,
                           linewidth=0.9, zorder=5)

        # regression-based (proportional) limits, drawn only over the data
        gx, ctr, plo, phi = grids[dev]
        ax.plot(gx, ctr, color=FIT_C, lw=1.7, zorder=6)
        ax.plot(gx, plo, color=FIT_C, lw=1.2, ls=(0, (1.5, 1.5)), zorder=6)
        ax.plot(gx, phi, color=FIT_C, lw=1.2, ls=(0, (1.5, 1.5)), zorder=6)

        ax.set_xlim(xlo, xhi)
        ax.set_ylim(ylo, yhi)

        # label the three constant lines with their values
        xt = xlo + 0.012 * (xhi - xlo)
        bb = dict(boxstyle="round,pad=0.12", fc="white", ec="none", alpha=0.85)
        ax.text(xt, c["loa_high"], f"+1.96 SD  {sgn(c['loa_high'])}", fontsize=6.6,
                va="bottom", ha="left", color=FLAT_C, bbox=bb, zorder=7)
        ax.text(xt, c["bias"], f"bias  {sgn(c['bias'])} {unit}", fontsize=7.4,
                va="bottom", ha="left", fontweight="bold", color=FLAT_C,
                bbox=bb, zorder=7)
        ax.text(xt, c["loa_low"], f"−1.96 SD  {sgn(c['loa_low'])}", fontsize=6.6,
                va="top", ha="left", color=FLAT_C, bbox=bb, zorder=7)

        # the point of the panel: does the difference trend with size?
        ns = "" if c["trend_p"] < 0.05 else "  n.s."
        note = (f"{core.DEVICE_LABEL[dev]}\n"
                f"trend {sgn(c['trend_slope'], 3)} {unit}/{unit}\n"
                f"95 % CI {sgn(c['slope_ci_low'], 3)} to "
                f"{sgn(c['slope_ci_high'], 3)}\n"
                f"{fmt_p(c['trend_p'])}{ns}")
        # where the stands do not share a slope, one pooled line is a fiction
        # and the reader has to be told so on the panel, not only in the caption
        if sitemod[(k, dev)]["site_x_size_p"] < 0.05:
            note += (f"\nbut stands differ, "
                     f"{fmt_p(sitemod[(k, dev)]['site_x_size_p'])}:\n"
                     f"{sgn(by_site[(k, dev, 'McDunn')]['trend_slope'], 3)} McDunn "
                     f"vs {sgn(by_site[(k, dev, 'Starker')]['trend_slope'], 3)} "
                     "Starker")
        va, ypos = ("top", 0.98) if up else ("bottom", 0.02)
        ax.text(0.98, ypos, note, transform=ax.transAxes, fontsize=7.2,
                va=va, ha="right", color=FIT_C, linespacing=1.35,
                bbox=dict(boxstyle="round,pad=0.3", fc="white",
                          ec=core.PALETTE["grid"], lw=0.7, alpha=0.95), zorder=8)

        core.panel_tag(ax, tags[i][j])
        ax.set_xlabel(f"Reference {AXIS_NAME[k]} by {REF_NAME[k]} ({unit})")
        if j == 0:
            ax.set_ylabel(f"Phone − reference ({unit})")
        else:
            ax.set_ylabel("")
            ax.tick_params(labelleft=False)

        panel_notes.append((tags[i][j], k, dev, c))

# one legend for the whole figure: the sites, then the three kinds of line
from matplotlib.lines import Line2D

keys = [
    Line2D([], [], ls="none", marker=SITE_MARKER["McDunn"], ms=5,
           mfc=core.PALETTE["site"]["McDunn"], mec="white", mew=0.4,
           label="McDunn"),
    Line2D([], [], ls="none", marker=SITE_MARKER["Starker"], ms=5,
           mfc=core.PALETTE["site"]["Starker"], mec="white", mew=0.4,
           label="Starker"),
    Line2D([], [], ls="none", marker="o", ms=7, mfc="none", mec=FLAT_C, mew=0.9,
           label="disputed tape"),
    Line2D([], [], color=FLAT_C, lw=1.5, label="bias"),
    Line2D([], [], color=FLAT_C, lw=1.0, ls=(0, (5, 3)), label="constant 95 % LoA"),
    Line2D([], [], color=FIT_C, lw=1.7, label="proportional fit"),
    Line2D([], [], color=FIT_C, lw=1.2, ls=(0, (1.5, 1.5)),
           label="proportional 95 % limits"),
]
fig.tight_layout(rect=[0, 0.055, 1, 1])
fig.subplots_adjust(hspace=0.30, wspace=0.05)
fig.legend(handles=keys, loc="lower center", ncol=4, fontsize=7.6,
           bbox_to_anchor=(0.5, 0.0), handletextpad=0.4, columnspacing=1.5,
           handlelength=1.8)

# ---- caption, written from the numbers just computed -----------------------
def get(k, dev):
    return next(c for _, kk, dd, c in panel_notes if kk == k and dd == dev)


# Is the trend simply one constant percentage? If the error is multiplicative,
# log(phone/reference) should have NO size dependence left in it.
logratio = {}
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        lr = np.log(s.measured.values / s.reference.values)
        f = sm.OLS(lr, sm.add_constant(s.reference.values)).fit()
        logratio[(k, dev)] = dict(mean=lr.mean(), pct=100 * (np.exp(lr.mean()) - 1),
                                  slope=f.params[1], p=f.pvalues[1],
                                  shapiro_p=sps.shapiro(lr).pvalue)

parts = []
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        c = get(k, dev)
        u = core.MEASURANDS[k]["unit"]
        parts.append(f"{core.MEASURANDS[k]['short']}/{core.DEVICE_SHORT[dev]} "
                     f"bias {c['bias']:+.2f} {u} ({c['pct_bias']:+.1f} %), "
                     f"LoA {c['loa_low']:+.2f} to {c['loa_high']:+.2f} {u}, "
                     f"trend {c['trend_slope']:+.3f} {u}/{u} "
                     f"({fmt_p(c['trend_p'])})")

sig = [f"{TAG[(k, dev)]} ({core.MEASURANDS[k]['short']}, "
       f"{core.DEVICE_SHORT[dev]}, {fmt_p(get(k, dev)['trend_p'])})"
       for k in core.MEASURANDS for dev in core.DEVICES
       if get(k, dev)["trend_p"] < 0.05]
nsig = [TAG[(k, dev)] for k in core.MEASURANDS for dev in core.DEVICES
        if get(k, dev)["trend_p"] >= 0.05]

# disputed-tape records, counted per measurand because a ring only appears in
# the panel for the measurand whose tape is in dispute
disp = (df[df.tape_disputed].drop_duplicates(["stem", "measurand"])
        .measurand.value_counts())
disp_txt = " and ".join(f"{int(disp.get(k, 0))} {AXIS_NAME[k]}"
                        for k in core.MEASURANDS)

# sensitivity: does dropping the disputed tape readings change the verdict?
sens_txt, verdict_flips = [], []
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        b = cell(s[~s.tape_disputed])
        a = get(k, dev)
        if (a["trend_p"] < 0.05) != (b["trend_p"] < 0.05):
            verdict_flips.append(TAG[(k, dev)])
        sens_txt.append(f"{TAG[(k, dev)]} bias {b['bias']:+.2f}, trend "
                        f"{b['trend_slope']:+.3f} ({fmt_p(b['trend_p'])})")
sens_lead = ("Excluding the disputed-tape records leaves every trend verdict "
             "unchanged" if not verdict_flips else
             "Excluding the disputed-tape records REVERSES the trend verdict in "
             + ", ".join(verdict_flips))

caption = (
    "Figure 3. Bland-Altman agreement between the ForestiX phone measurement and "
    "the field reference, plotted against the REFERENCE rather than the mean of "
    "the two methods because the tape and the laser are the standard and "
    "regressing a difference on a mean containing it induces a slope. "
    "(A) DBH, iOS; (B) DBH, Android; (C) height, iOS; (D) height, Android. "
    "n = 100 stems per panel except (C), n = 99, where one iOS height was typed "
    "rather than measured and is excluded. Points are coloured and shaped by "
    f"stand (circles McDunn, triangles Starker); the {disp_txt} records whose tape "
    "reading is disputed are ringed. Dark solid line is the constant bias, dark "
    "dashed lines with the grey band the conventional 95 % limits of agreement "
    "(bias +/- 1.96 SD); all three are labelled with their values. "
    + "; ".join(parts) + ". "
    "CRITICAL: the difference trends significantly with size in panels "
    + ", ".join(sig) + f", and not in {', '.join(nsig)}"
    + ", so a flat bias and a flat LoA band misdescribe the error - it is "
    "proportional, not constant, and the flat band is too wide on small stems and "
    "too narrow on large ones. The orange solid line and orange dotted lines are "
    "therefore regression-based limits (Bland & Altman 1999): the difference "
    "regressed on the reference, plus and minus 2.46 times the fitted absolute "
    "residual, which lets the band both tilt and fan. The scatter widens with size "
    "as well - the absolute residual regressed on the reference has slope "
    + ", ".join(f"{get(k, dev)['spread_slope']:+.3f} in {TAG[(k, dev)]} "
                f"({fmt_p(get(k, dev)['spread_p'])})"
                for k in core.MEASURANDS for dev in core.DEVICES)
    + " - so the limits fan where that slope is significant and run essentially "
    "parallel where it is not. Expressed as a ratio the error carries no "
    "residual size dependence in any panel (log(phone/reference) regressed on the "
    "reference, p = "
    + ", ".join(f"{logratio[(k, dev)]['p']:.2f}"
                for k in core.MEASURANDS for dev in core.DEVICES)
    + " for A-D), which identifies the trend as one constant percentage - "
    + ", ".join(f"{logratio[(k, dev)]['pct']:+.1f} %"
                for k in core.MEASURANDS for dev in core.DEVICES)
    + " for A-D - rather than a size-specific distortion. Two qualifications on "
    "the pooled trends: stand and stem size are confounded, Starker carrying the "
    "larger stems, and with a stand intercept in the model the height trend "
    f"strengthens to {sitemod[('height','ios')]['trend_slope_site_adj']:+.3f} ft/ft "
    f"({fmt_p(sitemod[('height','ios')]['trend_p_site_adj'])}) in C and "
    f"{sitemod[('height','android')]['trend_slope_site_adj']:+.3f} ft/ft "
    f"({fmt_p(sitemod[('height','android')]['trend_p_site_adj'])}) in D; and in B "
    "the two stands do not share one slope (stand-by-size interaction "
    f"{fmt_p(sitemod[('dbh','android')]['site_x_size_p'])}: "
    f"{by_site[('dbh','android','Starker')]['trend_slope']:+.3f} in/in at Starker "
    f"against {by_site[('dbh','android','McDunn')]['trend_slope']:+.3f} in/in at "
    "McDunn), so the Android DBH trend is a property of one stand and not of the "
    "handset. Differences "
    "depart from normality for DBH on both handsets (Shapiro-Wilk "
    f"p = {get('dbh','ios')['shapiro_p']:.1e} iOS, "
    f"p = {get('dbh','android')['shapiro_p']:.1e} Android) and for Android height "
    f"(p = {get('height','android')['shapiro_p']:.3f}), so the +/-1.96 SD limits "
    "are approximate; observed coverage of the constant band is "
    + ", ".join(f"{get(k, dev)['pct_inside_flat_loa']:.0f} %"
                for k in core.MEASURANDS for dev in core.DEVICES)
    + " for A-D against a nominal 95 %. " + sens_lead
    + " (" + "; ".join(sens_txt) + "). Diameters in inches, "
    "heights in feet; the two panels of a row share one y-scale so the handsets "
    "are compared and not merely each described.")

core.save(fig, "fig03_bland_altman", caption)


# --------------------------------------------------------------------------
# console report
# --------------------------------------------------------------------------
print("\n=== Bland-Altman, pooled ===")
for k in core.MEASURANDS:
    u = core.MEASURANDS[k]["unit"]
    for dev in core.DEVICES:
        c = get(k, dev)
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"n={c['n']:3d} bias {c['bias']:+.2f} {u} "
              f"[{c['bias_ci_low']:+.2f},{c['bias_ci_high']:+.2f}] "
              f"({c['pct_bias']:+.1f}%)  SD {c['sd']:.2f}  "
              f"LoA {c['loa_low']:+.2f} to {c['loa_high']:+.2f}  "
              f"slope {c['trend_slope']:+.4f} "
              f"[{c['slope_ci_low']:+.4f},{c['slope_ci_high']:+.4f}] "
              f"{fmt_p(c['trend_p'])}  spread slope {c['spread_slope']:+.4f} "
              f"({fmt_p(c['spread_p'])})  Deming-1 {c['deming_slope_minus_1']:+.4f}  "
              f"shapiro {c['shapiro_p']:.2g}  "
              f"cover flat {c['pct_inside_flat_loa']:.0f}% "
              f"prop {c['pct_inside_prop_loa']:.0f}%")

print("\n=== is the trend just a constant percentage? (log-ratio vs size) ===")
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        lr = np.log(s.measured.values / s.reference.values)
        f = sm.OLS(lr, sm.add_constant(s.reference.values)).fit()
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"mean log-ratio {lr.mean():+.4f} ({100*(np.exp(lr.mean())-1):+.2f}%) "
              f"slope {f.params[1]:+.5f} {fmt_p(f.pvalues[1])} -> "
              f"{'no residual size trend in %' if f.pvalues[1] >= 0.05 else 'SIZE TREND REMAINS IN %'}")

print("\n=== disputed-tape sensitivity (pooled) ===")
for k in core.MEASURANDS:
    u = core.MEASURANDS[k]["unit"]
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        a, b = cell(s), cell(s[~s.tape_disputed])
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"drop {int(s.tape_disputed.sum())}: bias {a['bias']:+.2f} -> "
              f"{b['bias']:+.2f} {u}; SD {a['sd']:.2f} -> {b['sd']:.2f}; "
              f"slope {a['trend_slope']:+.4f} ({fmt_p(a['trend_p'])}) -> "
              f"{b['trend_slope']:+.4f} ({fmt_p(b['trend_p'])})")

print("\n=== by site (trend), and the stand/size confound ===")
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        for site in core.SITES:
            c = by_site[(k, dev, site)]
            print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
                  f"{site:8s} n={c['n']:3d} bias {c['bias']:+.2f} "
                  f"slope {c['trend_slope']:+.4f} ({fmt_p(c['trend_p'])})")
        m = sitemod[(k, dev)]
        flag = "  <-- ONE SLOPE NOT DEFENSIBLE" if m["site_x_size_p"] < 0.05 else ""
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"{'pooled':8s} site-adjusted slope {m['trend_slope_site_adj']:+.4f} "
              f"({fmt_p(m['trend_p_site_adj'])}); stand offset "
              f"{m['site_offset']:+.2f} ({fmt_p(m['site_offset_p'])}); "
              f"stand x size {fmt_p(m['site_x_size_p'])}{flag}")

print("\nsaved:", core.FIGDIR, core.RESDIR)
