#!/usr/bin/env python3
"""What this design supports, and what it does not — the slide that bounds the rest.

WHY THIS EXISTS. Every other figure here reports a number measured on two
stands with two handsets against one reference reading. Read alone, any of them
invites a claim the design cannot carry: that the app over-reads diameter (it
over-read diameter HERE), that one stand is harder than the other (the two
stands differ in four things besides stand), that the height numbers are
accuracy (the height reference inverts the same tangent geometry the app does),
or that the diameter scatter is stem shape (stem shape is bounded, and the
bound is measured). This figure states the boundary once, in the form a reader
cannot skip, so no other slide has to carry a disclaimer.

WHAT IS DRAWN, AND WHY IT IS A FIGURE. A text box on a slide is lost the moment
the deck is exported, re-themed, or pasted into a manuscript; a figure travels
with its caption. Both panels are therefore drawn, and every number in them is
computed here from the same loader every other script uses.

(A) THE CONFOUND. Stand is not a clean factor in this design. The panel shows
the two stands' REFERENCE distributions — the tape and the laser, not the
phones — because that is what makes the point: the stands are not size-matched.
Mann-Whitney rather than a t-test, because the pooled height distribution is
bimodal by construction. Beneath it, the four other things that differ with
stand, each read off the paired table rather than recalled.

(B) THE SCOPE. Two columns. Everything on the left is an ESTIMATE with an
interval, measured on these two stands, and is what the study reports.
Everything on the right is a question this design cannot answer, each carrying
the one number that makes it unanswerable — a confound's effect size, a shared
error component, a count of stands.

ON MULTIPLICITY. The result tables carry several hundred p-values and no
family-wise correction, so at alpha = 0.05 a couple of dozen would read
significant with nothing behind them. That governs HYPOTHESIS TESTS. It does
not touch an estimate: a bias with a bootstrap CI, an RMSE, a CCC, a limit of
agreement are point estimates of quantities that exist whether or not anyone
tests them, and they do not become less true because a different table ran a
test. The primary results are therefore estimates, the p-value-driven contrasts
are labelled exploratory where they appear, and the count printed in panel B is
counted from the result tables rather than asserted.

ON THE WORD "ACCURACY". It appears for diameter and never for height. The
diameter reference is a tape: an independent instrument measuring
circumference / pi. The height reference is a laser rangefinder in 3-point
mode, which inverts the same tangent geometry the app inverts, so a wrong
tangent assumption moves reference and app together. fig13 measures how much
that matters — the two handsets' height errors correlate, sharing about 5 ft of
SD — so height is reported as AGREEMENT with a 3-point laser throughout.
"""
from __future__ import annotations

import glob
import os
import re
import textwrap

import numpy as np
import pandas as pd
from matplotlib.patches import Rectangle
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()
raw = pd.read_csv(core.PAIRS)


def rnd1(x: float) -> str:
    """One decimal, rounded half AWAY from zero — the deck's convention.

    numpy and the built-in round send 20.25 to 20.2, and fig01 quotes the same
    median as 20.3; two figures disagreeing in the last digit of the same
    number is exactly the slip a reviewer screenshots.
    """
    from decimal import Decimal, ROUND_HALF_UP
    return str(Decimal(repr(float(x))).quantize(Decimal("0.1"),
                                                rounding=ROUND_HALF_UP))


def para(text: str) -> str:
    """Collapse an f-string's source line breaks so the caption is one paragraph."""
    return " ".join(text.split())


def fmt_p(p: float) -> str:
    """A p in the form a reader can read: two significant figures, or a x 10^b.

    Not `.3f`: that renders 0.00904 as "0.009", which throws away the figure
    that distinguishes it from 0.0094 and reads as a rounded-off number.
    """
    if p >= 1e-3:
        return f"{p:#.2g}"
    mant, exp = f"{p:.1e}".split("e")
    return f"{mant} × 10$^{{{int(exp)}}}$"


# ==========================================================================
# PANEL A — the confound, verified against the paired table
# ==========================================================================

# The REFERENCE, de-duplicated. The loader gives one row per device, so a stem
# read by both phones would otherwise contribute its tape value twice and
# weight every median by how many handsets happened to see it.
ref = (df.drop_duplicates(subset=["stem", "measurand"])
         [["stem", "site", "measurand", "reference"]].reset_index(drop=True))


def contrast(measurand: str) -> dict:
    a = ref[(ref.measurand == measurand) & (ref.site == "McDunn")].reference.to_numpy()
    b = ref[(ref.measurand == measurand) & (ref.site == "Starker")].reference.to_numpy()
    u = sps.mannwhitneyu(a, b, alternative="two-sided")
    return dict(McDunn=a, Starker=b, U=float(u.statistic), p=float(u.pvalue),
                med_McDunn=float(np.median(a)), med_Starker=float(np.median(b)),
                # Rank-biserial: the effect size behind the p, so the contrast is
                # reported as a magnitude and not only as a verdict.
                rbc=2 * float(u.statistic) / (len(a) * len(b)) - 1)


CON = {m: contrast(m) for m in core.MEASURANDS}

# --- the other four confounds, each read off the table -------------------
STEMS = raw.drop_duplicates("pair_id")
n_stems = {s: int((STEMS["plot"] == s).sum()) for s in core.SITES}

# Species. Recorded per stem, not per row: a stem contributes two rows.
species_recorded = {s: int(STEMS.loc[STEMS["plot"] == s, "species"].notna().sum())
                    for s in core.SITES}
species_mix = {s: (STEMS.loc[STEMS["plot"] == s, "species"].value_counts().to_dict())
               for s in core.SITES}

# Collection day. Taken from the ANDROID clock, which carries no typed
# re-entries; the iOS column has one McDunn height stamped two days later,
# which is the cruiser typing the tape value back into the app and is dropped
# by the loader. Both are reported rather than the convenient one.
day = {}
for c, key in (("android_time", "android"), ("ios_time", "ios")):
    t = pd.to_datetime(raw[c], errors="coerce")
    for s in core.SITES:
        d = sorted({x for x in t[raw["plot"] == s].dt.date if pd.notna(x)})
        day[(s, key)] = d

typed_rows = raw[raw["flags"].fillna("").str.contains("typed")]

# Stem pairing. Starker stems carry a tree NAME written on both handsets, so a
# pair is an identity; McDunn stems carry none and were paired by capture time,
# which is why their pair_gap_s is non-zero and Starker's is exactly zero.
named = {s: int(STEMS.loc[STEMS["plot"] == s, "tree_name"].notna().sum())
         for s in core.SITES}
gap = {s: raw.loc[raw["plot"] == s, "pair_gap_s"] for s in core.SITES}
gap_med = {s: float(gap[s].median()) for s in core.SITES}
gap_max = {s: float(gap[s].max()) for s in core.SITES}

# ==========================================================================
# PANEL B — the numbers each scope line rests on
# ==========================================================================
T02 = pd.read_csv(os.path.join(core.RESDIR, "t02_accuracy.csv"))
T04 = pd.read_csv(os.path.join(core.RESDIR, "t04_crossplatform.csv"))
T08 = pd.read_csv(os.path.join(core.RESDIR, "t08_sigma.csv"))
T11 = pd.read_csv(os.path.join(core.RESDIR, "t11_estimator.csv"))
T13 = pd.read_csv(os.path.join(core.RESDIR, "t13_shared_error.csv"))


def acc(measurand: str, device: str) -> pd.Series:
    return T02[(T02.Measurand == measurand) & (T02.Device == device)
               & (T02.Site == "Both sites")].iloc[0]


def xp(measurand: str) -> pd.Series:
    return T04[(T04.measurand == measurand) & (T04.subset == "Pooled")].iloc[0]


def sig(measurand: str, device: str) -> pd.Series:
    return T08[(T08.measurand == measurand) & (T08.device == device)].iloc[0]


def est(device: str, estimator: str) -> pd.Series:
    return T11[(T11.subset == "all stems") & (T11.device == device)
               & (T11.estimator == estimator)].iloc[0]


def shared(measurand: str) -> pd.Series:
    return T13[(T13.measurand == measurand) & (T13.subset == "Pooled")].iloc[0]


D_IOS, D_AND = acc("DBH", "iOS (LiDAR)"), acc("DBH", "Android (ARCore)")
H_IOS, H_AND = acc("Height", "iOS (LiDAR)"), acc("Height", "Android (ARCore)")
XD, XH = xp("DBH"), xp("Height")
SD_, SH_ = shared("DBH"), shared("Height")

# The handset changes which cruiser class a stem lands in — the cross-platform
# difference in the unit a cruiser actually books.
pdbh = core.paired(df, "dbh")
cls_ios = pdbh.measured_ios.apply(lambda v: core.size_class(v, "dbh"))
cls_and = pdbh.measured_android.apply(lambda v: core.size_class(v, "dbh"))
n_class_change = int((cls_ios != cls_and).sum())


def count_p_values() -> tuple[int, int]:
    """How many p-values the result tables carry, counted rather than recalled.

    The multiplicity statement on this slide is only honest if the count is
    audited from the tables it describes. Every column whose name marks it as a
    p-value is counted, across every result CSV present.
    """
    total, tables = 0, 0
    pat = re.compile(r'.*(^|_)p(_.*)?$|p_value|adj_p|size_p|coverage_p_vs_95')
    for f in sorted(glob.glob(os.path.join(core.RESDIR, "t*.csv"))):
        d = pd.read_csv(f)
        cols = [c for c in d.columns if pat.fullmatch(str(c))]
        tables += 1
        if cols:
            total += int(d[cols].notna().sum().sum())
    return total, tables


N_P, N_TABLES = count_p_values()
N_FALSE = N_P * 0.05

# ==========================================================================
# Figure
# ==========================================================================
INK = core.PALETTE["reference"]
MUTED = core.PALETTE["muted"]
YES = core.PALETTE["ios"]         # deep blue: what the design carries
NO = core.PALETTE["accent"]       # crimson: what it does not
SITE_MARK = {"McDunn": "o", "Starker": "^"}
SITE_HATCH = {"McDunn": "", "Starker": "///"}

fig = plt.figure(figsize=(13.0, 6.15))
outer = fig.add_gridspec(1, 2, width_ratios=[0.90, 1.14], wspace=0.20,
                         left=0.052, right=0.988, top=0.915, bottom=0.045)
left = outer[0, 0].subgridspec(2, 2, height_ratios=[1.0, 0.86],
                               hspace=0.42, wspace=0.42)
axD = fig.add_subplot(left[0, 0])
axH = fig.add_subplot(left[0, 1])
axT = fig.add_subplot(left[1, :])
axB = fig.add_subplot(outer[0, 1])

# ---- A: the two stands' reference distributions --------------------------
rng = np.random.default_rng(17)
for ax, m in ((axD, "dbh"), (axH, "height")):
    meta = core.MEASURANDS[m]
    c = CON[m]
    vals = [c[s] for s in core.SITES]
    bp = ax.boxplot(vals, positions=[0, 1], widths=0.52, patch_artist=True,
                    showfliers=False, whis=1.5, zorder=2)
    for k, site in enumerate(core.SITES):
        col = core.PALETTE["site"][site]
        bp["boxes"][k].set(facecolor="white", edgecolor=col, linewidth=1.2,
                           hatch=SITE_HATCH[site], alpha=1.0)
        bp["medians"][k].set(color=col, linewidth=2.0)
        for part in ("whiskers", "caps"):
            for art in bp[part][2 * k:2 * k + 2]:
                art.set(color=col, linewidth=1.0)
        v = c[site]
        # Every stem plotted, because a box hides how the two stands overlap
        # and the overlap is the part a reader has to judge.
        ax.scatter(k + rng.uniform(-0.16, 0.16, len(v)), v, s=11,
                   marker=SITE_MARK[site], facecolors="none", linewidths=0.65,
                   edgecolors=col, alpha=0.75, zorder=3)
        # The median printed in the headroom above its own box rather than on
        # it: on the hatched Starker box a number sitting on the fill is read
        # through the hatch lines, which is how 139.4 becomes unreadable.
        ax.text(k, 0.885, f"median {rnd1(np.median(v))}", transform=
                ax.get_xaxis_transform(), ha="center", va="center",
                fontsize=7.8, fontweight="bold", color=col, zorder=5)

    ax.set_xticks([0, 1])
    ax.set_xticklabels([f"{s}\nn = {len(c[s])}" for s in core.SITES], fontsize=8)
    ax.set_xlim(-0.62, 1.62)
    ax.set_ylabel(f"{meta['label']}, reference ({meta['unit']})", fontsize=8.2)
    top = max(np.max(c["McDunn"]), np.max(c["Starker"]))
    bot = min(np.min(c["McDunn"]), np.min(c["Starker"]))
    # Headroom for the median labels and the test result, so neither is ever
    # drawn over a stem.
    ax.set_ylim(bot - 0.06 * (top - bot), top + 0.42 * (top - bot))
    ax.text(0.5, 0.995,
            f"Mann–Whitney p = {fmt_p(c['p'])}",
            transform=ax.transAxes, ha="center", va="top", fontsize=8.2,
            fontweight="bold", color=INK,
            bbox=dict(boxstyle="round,pad=0.24", fc="white",
                      ec=core.PALETTE["grid"], lw=0.6, alpha=0.95), zorder=6)
    ax.tick_params(labelsize=8)

core.panel_tag(axD, "A")
axD.set_title("Reference: diameter tape", fontsize=8.6, pad=17, color=MUTED,
              fontweight="normal")
axH.set_title("Reference: laser, 3-point mode", fontsize=8.6, pad=17,
              color=MUTED, fontweight="normal")

# ---- A (lower): the confounds that travel with stand ---------------------
axT.set_axis_off()
axT.set_xlim(0, 1)
axT.set_ylim(0, 1)

COLX = (0.000, 0.455, 0.735)
rows = [
    ("Reference height, median",
     f"{rnd1(CON['height']['med_McDunn'])} ft", f"{rnd1(CON['height']['med_Starker'])} ft"),
    ("Reference diameter, median",
     f"{rnd1(CON['dbh']['med_McDunn'])} in", f"{rnd1(CON['dbh']['med_Starker'])} in"),
    ("Collection day",
     day[("McDunn", "android")][0].strftime("%d %b %Y"),
     day[("Starker", "android")][0].strftime("%d %b %Y")),
    ("Species recorded",
     f"{species_recorded['McDunn']} of {n_stems['McDunn']} stems",
     f"{species_recorded['Starker']} of {n_stems['Starker']} stems"),
    ("Stem pairing method",
     f"capture time (gap {gap_med['McDunn']:.0f} s)",
     f"tree name (gap {gap_med['Starker']:.0f} s)"),
]

axT.text(0, 1.10, "THE TWO STANDS ALSO DIFFER IN", fontsize=8.6,
         fontweight="bold", color=INK, va="top", ha="left")
head_y = 0.905
axT.text(COLX[1], head_y, f"McDunn ({n_stems['McDunn']} stems)", fontsize=8.0,
         fontweight="bold", color=core.PALETTE["site"]["McDunn"], va="center")
axT.text(COLX[2], head_y, f"Starker ({n_stems['Starker']} stems)", fontsize=8.0,
         fontweight="bold", color=core.PALETTE["site"]["Starker"], va="center")
axT.plot([0, 1], [0.835, 0.835], color=INK, lw=0.9, clip_on=False)

# Fixed row positions rather than a running cursor: the two lines beneath the
# block must land INSIDE the axes, and a cursor that walks off the bottom is
# only rescued by the tight bounding box, which changes the figure's aspect.
ROW_Y, ROW_DY = 0.775, 0.133
for i, (lab, a, b) in enumerate(rows):
    y = ROW_Y - i * ROW_DY
    if i % 2 == 0:
        axT.add_patch(Rectangle((-0.012, y - 0.056), 1.024, 0.112,
                                facecolor="#F2F5F7", edgecolor="none", zorder=0))
    axT.text(COLX[0], y, lab, fontsize=8.0, color=INK, va="center", zorder=2)
    axT.text(COLX[1], y, a, fontsize=8.0, color=INK, va="center", zorder=2)
    axT.text(COLX[2], y, b, fontsize=8.0, color=INK, va="center", zorder=2)

rule_y = ROW_Y - (len(rows) - 1) * ROW_DY - 0.072
axT.plot([0, 1], [rule_y, rule_y], color=core.PALETTE["grid"], lw=0.9)
axT.text(0, rule_y - 0.055,
         "No analysis here separates stand from tree size or from day: "
         "one stand, one day, one size distribution.",
         fontsize=7.6, color=NO, va="center", ha="left", style="italic")
axT.text(0, rule_y - 0.125,
         f"Reference n = {len(CON['dbh']['McDunn'])} at McDunn for each measurand: one "
         f"stem has no recoverable tape diameter, a different stem no tape height.",
         fontsize=6.9, color=MUTED, va="center", ha="left")

# ---- B: what the design can and cannot separate --------------------------
axB.set_axis_off()
axB.set_xlim(0, 1)
axB.set_ylim(0, 1)

sup_items = [
    ("Agreement with the reference on these two stands",
     f"diameter bias +{D_IOS['Bias [in | ft]']:.2f} in (95 % CI "
     f"{D_IOS['Bias 95% CI [in | ft]'].replace('to', 'to')}) iOS, "
     f"+{D_AND['Bias [in | ft]']:.2f} in ({D_AND['Bias 95% CI [in | ft]']}) Android; "
     f"height {H_IOS['Bias [in | ft]']:+.1f} ft and {H_AND['Bias [in | ft]']:+.1f} ft"),
    ("The difference between the two handsets",
     f"mean iOS − Android {XD.mean_diff:+.2f} in "
     f"({XD.ci_low:+.2f} to {XD.ci_high:+.2f}); 95 % limits of agreement span "
     f"{XD.loa_high - XD.loa_low:.1f} in and {XH.loa_high - XH.loa_low:.1f} ft; "
     f"{n_class_change} of {len(pdbh)} stems change diameter class"),
    ("The diameter estimator's geometric bias",
     f"shipped chord identity {est('iOS (LiDAR)', 'shipped chord').bias_pct:+.1f} % "
     f"(iOS) and {est('Android (ARCore)', 'shipped chord').bias_pct:+.1f} % (Android); "
     f"the exact tangent inversion leaves "
     f"{est('iOS (LiDAR)', 'tangent').bias_pct:+.1f} % and "
     f"{est('Android (ARCore)', 'tangent').bias_pct:+.1f} %"),
    ("The uncertainty channel does not work",
     f"the app's nominal 95 % σ interval covers "
     f"{sig('DBH', 'iOS').coverage_pct:.0f} % (iOS) and "
     f"{sig('DBH', 'Android').coverage_pct:.0f} % (Android) of diameter readings, "
     f"{sig('Height', 'iOS').coverage_pct:.0f} % and "
     f"{sig('Height', 'Android').coverage_pct:.0f} % of heights"),
]

not_items = [
    ("Generalisation to any other stand",
     f"n = 2 stands, {n_stems['McDunn'] + n_stems['Starker']} stems, two consecutive "
     f"days in one locality — between-stand variance cannot be estimated from two "
     f"stands, however many stems each holds"),
    ("A stand effect separate from tree size and day",
     f"reference height median {rnd1(CON['height']['med_McDunn'])} vs "
     f"{rnd1(CON['height']['med_Starker'])} ft (Mann–Whitney p = "
     f"{fmt_p(CON['height']['p'])}); stand is 1:1 with collection day. A site "
     f"difference is a site difference, not a stand effect"),
    ("Height ACCURACY, independent of the tangent method",
     f"the reference is a 3-point laser inverting the same tangent geometry; the two "
     f"handsets' height errors correlate r = {SH_.r:+.3f} (p = {fmt_p(SH_.r_p)}), sharing "
     f"{min(SH_.shared_frac_ios, SH_.shared_frac_android) * 100:.0f}–"
     f"{max(SH_.shared_frac_ios, SH_.shared_frac_android) * 100:.0f} % of error "
     f"variance, shared SD {SH_.shared_sd:.2f} ft. Height is AGREEMENT, not accuracy"),
    ("Out-of-roundness split from instrument scatter",
     f"bounded, not resolved: shared SD {SD_.shared_sd:.2f} in (bootstrap upper "
     f"{SD_.shared_sd_ci_high:.2f}) against per-handset {SD_.sd_ios:.2f} in (iOS) and "
     f"{SD_.sd_android:.2f} in (Android) — stem shape and reference error together "
     f"cap at {min(SD_.shared_frac_ios, SD_.shared_frac_android) * 100:.0f}–"
     f"{max(SD_.shared_frac_ios, SD_.shared_frac_android) * 100:.0f} % of the variance"),
]

COLW = 0.472
COL_X = (0.0, 1.0 - COLW)


def column(x0, title, sub, items, colour, hatch, marker_filled):
    """One scope column: a banner, then four claims each carrying its number."""
    # Banner. The WORD carries the verdict; colour and hatch are redundant, so
    # the panel survives greyscale and a colour-blind reader.
    axB.add_patch(Rectangle((x0, 0.928), COLW, 0.062, facecolor=colour,
                            edgecolor="none", zorder=2))
    axB.text(x0 + 0.016, 0.959, title, fontsize=9.4, fontweight="bold",
             color="white", va="center", ha="left", zorder=3)
    axB.text(x0 + COLW - 0.016, 0.959, sub, fontsize=7.2, color="white",
             va="center", ha="right", zorder=3, alpha=0.92)

    # Spacing is solved, not fixed: the two columns hold different amounts of
    # text, and a constant gap leaves one of them ending well above the other,
    # which reads as the short column having less to say.
    laid = [(textwrap.wrap(c, 50), textwrap.wrap(n, 62)) for c, n in items]
    text_h = sum(0.038 * len(h) + 0.004 + 0.033 * len(w) for h, w in laid)
    gap = max(0.020, (Y_TOP - Y_BOT - text_h) / len(laid))

    y = Y_TOP
    for head, wrapped in laid:
        # Bullet: filled square for what the design carries, hatched open square
        # for what it does not — a second, non-colour channel for the verdict.
        axB.add_patch(Rectangle((x0 + 0.004, y - 0.013), 0.0135, 0.020,
                                facecolor=colour if marker_filled else "white",
                                edgecolor=colour, linewidth=0.9,
                                hatch=hatch, zorder=3))
        for j, line in enumerate(head):
            axB.text(x0 + 0.030, y - j * 0.038, line, fontsize=8.8,
                     fontweight="bold", color=INK, va="center", ha="left")
        y -= 0.038 * len(head) + 0.004
        for j, line in enumerate(wrapped):
            axB.text(x0 + 0.030, y - j * 0.033, line, fontsize=7.8,
                     color=MUTED, va="center", ha="left")
        y -= 0.033 * len(wrapped) + gap
    return y


Y_TOP, Y_BOT = 0.868, 0.135
yA = column(COL_X[0], "SUPPORTED", "estimates, with intervals", sup_items,
            YES, "", True)
yB = column(COL_X[1], "NOT SUPPORTED", "and the number that makes it so",
            not_items, NO, "xxxx", False)
# A hairline gutter, so the two columns read as one comparison rather than two
# unrelated lists.
axB.plot([0.5, 0.5], [min(yA, yB) + 0.02, 0.918], color=core.PALETTE["grid"],
         lw=0.8, zorder=1)

foot = min(yA, yB) - 0.012
axB.plot([0, 1], [foot, foot], color=INK, lw=0.9)
footer = (
    f"Primary results are ESTIMATES with intervals, and multiplicity does not touch an "
    f"estimate. The {N_TABLES} result tables carry {N_P} p-values with no family-wise "
    f"correction, so about {N_FALSE:.0f} would read significant at α = 0.05 by chance "
    f"alone; every p-value-driven contrast — size class, site, interaction — is therefore "
    f"labelled EXPLORATORY where it appears."
)
for j, line in enumerate(textwrap.wrap(footer, 142)):
    axB.text(0, foot - 0.034 - j * 0.031, line, fontsize=7.7, color=INK,
             va="center", ha="left")
core.panel_tag(axB, "B")

caption = f"""
What this validation supports, and what it does not. (A) The two stands are not
interchangeable, and stand is not a clean factor in this design. Box plots give the median
and interquartile range of the REFERENCE measurements only — a diameter tape and a laser
rangefinder in 3-point mode, with the phones absent from this panel — whiskers extend to
1.5 x IQR, and every stem is plotted beside its box (circles McDunn, triangles Starker;
colour and marker both encode stand). Starker stems are the taller by a wide margin
(median {rnd1(CON['height']['med_Starker'])} against {rnd1(CON['height']['med_McDunn'])} ft,
Mann-Whitney U = {CON['height']['U']:.0f}, p = {CON['height']['p']:.1e}, rank-biserial
{CON['height']['rbc']:+.2f}) and the thicker by a smaller one
({rnd1(CON['dbh']['med_Starker'])} against {rnd1(CON['dbh']['med_McDunn'])} in, U =
{CON['dbh']['U']:.0f}, p = {CON['dbh']['p']:.4f}, rank-biserial {CON['dbh']['rbc']:+.2f});
a rank test is used because the pooled height distribution is bimodal by construction. The
block beneath lists the four further differences that travel with stand, each read off the
paired table: the stands were measured on different days
({day[('McDunn', 'android')][0]:%d %b %Y} and {day[('Starker', 'android')][0]:%d %b %Y},
one stand per day), species was recorded for {species_recorded['Starker']} of
{n_stems['Starker']} Starker stems ({species_mix['Starker'].get('DF', 0)} Douglas-fir,
{species_mix['Starker'].get('BM', 0)} bigleaf maple) and for
{species_recorded['McDunn']} of {n_stems['McDunn']} at McDunn, and the two handsets' stems
were matched by a written tree name at Starker (all {named['Starker']} stems named, pairing
gap exactly 0 s) but by capture timestamp at McDunn (no stems named, median gap
{gap_med['McDunn']:.0f} s, maximum {gap_max['McDunn']:.0f} s). Reference n is
{len(CON['dbh']['McDunn'])} rather than {n_stems['McDunn']} at McDunn for each measurand
because one stem has no recoverable tape diameter and a different stem no tape height; the
one McDunn record timestamped two days later is the cruiser typing the tape value back into
the app, which the loader drops. A difference between these stands is therefore a SITE
difference confounded with tree size and collection day, and is never reported as a stand
effect. (B) What the design can and cannot separate, each line carrying the number it rests
on. Supported, as estimates with intervals: agreement with the reference on these two
stands (diameter bias +{D_IOS['Bias [in | ft]']:.2f} in, 95 % CI
{D_IOS['Bias 95% CI [in | ft]']} on iOS and +{D_AND['Bias [in | ft]']:.2f} in,
{D_AND['Bias 95% CI [in | ft]']} on Android); the difference between the two handsets (mean
iOS - Android {XD.mean_diff:+.2f} in, {XD.ci_low:+.2f} to {XD.ci_high:+.2f}, with 95 %
limits of agreement spanning {XD.loa_high - XD.loa_low:.1f} in and
{XH.loa_high - XH.loa_low:.1f} ft, and {n_class_change} of {len(pdbh)} stems changing
diameter class with the handset carried); the shipped chord estimator's geometric bias
({est('iOS (LiDAR)', 'shipped chord').bias_pct:+.1f} % and
{est('Android (ARCore)', 'shipped chord').bias_pct:+.1f} %, falling to
{est('iOS (LiDAR)', 'tangent').bias_pct:+.1f} % and
{est('Android (ARCore)', 'tangent').bias_pct:+.1f} % under the exact tangent inversion);
and the failure of the reported uncertainty channel (a nominal 95 % sigma interval covering
{sig('DBH', 'iOS').coverage_pct:.0f} % and {sig('DBH', 'Android').coverage_pct:.0f} % of
diameter readings and {sig('Height', 'iOS').coverage_pct:.0f} % and
{sig('Height', 'Android').coverage_pct:.0f} % of heights). Not supported: generalisation to
any other stand, since between-stand variance cannot be estimated from two stands however
many stems each holds; a stand effect separable from tree size or collection day, for the
reasons in panel A; height ACCURACY independent of the tangent method, because the
reference laser inverts the same geometry the app does and the two handsets' height errors
correlate r = {SH_.r:+.3f} (p = {SH_.r_p:.1e}), sharing
{min(SH_.shared_frac_ios, SH_.shared_frac_android) * 100:.0f}-{max(SH_.shared_frac_ios, SH_.shared_frac_android) * 100:.0f} %
of error variance and {SH_.shared_sd:.2f} ft of shared SD, so height is reported throughout
as agreement with a 3-point laser; and any split of stem out-of-roundness from instrument
scatter finer than the ceiling the design measures, a shared SD of {SD_.shared_sd:.2f} in
(bootstrap upper {SD_.shared_sd_ci_high:.2f} in) against per-handset totals of
{SD_.sd_ios:.2f} in (iOS) and {SD_.sd_android:.2f} in (Android), which caps stem shape and
reference error together at
{min(SD_.shared_frac_ios, SD_.shared_frac_android) * 100:.0f}-{max(SD_.shared_frac_ios, SD_.shared_frac_android) * 100:.0f} %
of the diameter error variance and leaves the rest with the instrument. Multiplicity is the
last line of the panel and applies only to hypothesis tests: the {N_TABLES} result tables
carry {N_P} p-values with no family-wise correction, about {N_FALSE:.0f} of which would
reach alpha = 0.05 by chance, so the primary results are estimates with intervals - a bias,
an RMSE, a concordance coefficient, a limit of agreement - and every p-value-driven
contrast is labelled exploratory where it appears. Diameters in inches, heights in feet.
"""
core.save(fig, "fig14_scope", para(caption))

# --------------------------------------------------------------------------
# Headline and numbers blocks for the deck
# --------------------------------------------------------------------------
headline = (
    f"This study describes what ForestiX measured on two stands, not what the app does in "
    f"general: with n = 2 stands whose reference heights differ by a median "
    f"{rnd1(CON['height']['med_McDunn'])} vs {rnd1(CON['height']['med_Starker'])} ft "
    f"(Mann-Whitney p = {CON['height']['p']:.1e}) and which were measured on different "
    f"days, a site difference is not a stand effect, height is agreement with a 3-point "
    f"laser rather than accuracy (shared error SD {SH_.shared_sd:.2f} ft, r = {SH_.r:+.3f}), "
    f"and stem out-of-roundness is a bounded limitation, capped at {SD_.shared_sd:.2f} in "
    f"of shared SD against per-handset {SD_.sd_ios:.2f} / {SD_.sd_android:.2f} in."
)

numbers = "\n".join([
    f"Two stands, not size-matched: reference height median "
    f"{rnd1(CON['height']['med_McDunn'])} ft (McDunn) vs {rnd1(CON['height']['med_Starker'])} ft "
    f"(Starker), Mann-Whitney U = {CON['height']['U']:.0f}, p = {CON['height']['p']:.1e}; "
    f"diameter {rnd1(CON['dbh']['med_McDunn'])} vs {rnd1(CON['dbh']['med_Starker'])} in, "
    f"p = {CON['dbh']['p']:.4f}",

    f"Stand travels with three more things: different collection days "
    f"({day[('McDunn', 'android')][0]:%d %b} vs {day[('Starker', 'android')][0]:%d %b}), "
    f"species recorded {species_recorded['Starker']}/{n_stems['Starker']} at Starker vs "
    f"{species_recorded['McDunn']}/{n_stems['McDunn']} at McDunn, stems paired by name at "
    f"Starker (gap 0 s) vs by timestamp at McDunn (median gap {gap_med['McDunn']:.0f} s)",

    f"Supported - agreement on these two stands: diameter bias "
    f"+{D_IOS['Bias [in | ft]']:.2f} in ({D_IOS['Bias 95% CI [in | ft]']}) iOS, "
    f"+{D_AND['Bias [in | ft]']:.2f} in ({D_AND['Bias 95% CI [in | ft]']}) Android",

    f"Supported - the handset difference: mean iOS - Android {XD.mean_diff:+.2f} in "
    f"({XD.ci_low:+.2f} to {XD.ci_high:+.2f}), limits of agreement spanning "
    f"{XD.loa_high - XD.loa_low:.1f} in and {XH.loa_high - XH.loa_low:.1f} ft, "
    f"{n_class_change}/{len(pdbh)} stems changing diameter class",

    f"Supported - the estimator's geometry and the sigma channel: chord identity "
    f"{est('iOS (LiDAR)', 'shipped chord').bias_pct:+.1f} % / "
    f"{est('Android (ARCore)', 'shipped chord').bias_pct:+.1f} % vs tangent "
    f"{est('iOS (LiDAR)', 'tangent').bias_pct:+.1f} % / "
    f"{est('Android (ARCore)', 'tangent').bias_pct:+.1f} %; nominal 95 % sigma covers "
    f"{sig('DBH', 'iOS').coverage_pct:.0f} % / {sig('DBH', 'Android').coverage_pct:.0f} % "
    f"of diameters, {sig('Height', 'iOS').coverage_pct:.0f} % / "
    f"{sig('Height', 'Android').coverage_pct:.0f} % of heights",

    f"NOT supported - height accuracy: the two handsets' height errors correlate "
    f"r = {SH_.r:+.3f} (p = {SH_.r_p:.1e}), "
    f"{min(SH_.shared_frac_ios, SH_.shared_frac_android) * 100:.0f}-"
    f"{max(SH_.shared_frac_ios, SH_.shared_frac_android) * 100:.0f} % of each handset's "
    f"error variance shared, shared SD {SH_.shared_sd:.2f} ft - independent instruments "
    f"cannot do that, a common tangent assumption can",

    f"NOT supported - stem shape as the whole diameter story: shared SD "
    f"{SD_.shared_sd:.2f} in (bootstrap upper {SD_.shared_sd_ci_high:.2f}) against "
    f"per-handset {SD_.sd_ios:.2f} in (iOS) and {SD_.sd_android:.2f} in (Android), so stem "
    f"shape plus reference error cap at "
    f"{min(SD_.shared_frac_ios, SD_.shared_frac_android) * 100:.0f}-"
    f"{max(SD_.shared_frac_ios, SD_.shared_frac_android) * 100:.0f} % of the variance and "
    f"the rest is the instrument",

    f"NOT supported - generalisation and multiplicity: n = 2 stands cannot yield a "
    f"between-stand variance, and the {N_TABLES} result tables carry {N_P} p-values with no "
    f"family-wise correction (about {N_FALSE:.0f} significant at alpha = 0.05 by chance), so "
    f"estimates with intervals lead and every test-driven contrast is labelled exploratory",
])

with open(os.path.join(core.FIGDIR, "fig14_scope.headline.txt"), "w") as fh:
    fh.write(headline + "\n")
with open(os.path.join(core.FIGDIR, "fig14_scope.numbers.txt"), "w") as fh:
    fh.write(numbers + "\n")

# --------------------------------------------------------------------------
# Console report — every claim on the slide, with what it was checked against
# --------------------------------------------------------------------------
print("PANEL A — confound checks against", core.PAIRS)
for m in core.MEASURANDS:
    c = CON[m]
    u = core.MEASURANDS[m]["unit"]
    print(f"  {core.MEASURANDS[m]['short']:6s} McDunn n={len(c['McDunn'])} "
          f"median {c['med_McDunn']:.2f} {u} | Starker n={len(c['Starker'])} "
          f"median {c['med_Starker']:.2f} {u} | U={c['U']:.0f} p={c['p']:.3g} "
          f"rank-biserial {c['rbc']:+.3f}")
for s in core.SITES:
    print(f"  {s:8s} stems={n_stems[s]} species={species_recorded[s]} "
          f"{species_mix[s]} named={named[s]} "
          f"pair_gap median={gap_med[s]:.0f}s max={gap_max[s]:.0f}s "
          f"android days={[str(d) for d in day[(s, 'android')]]} "
          f"ios days={[str(d) for d in day[(s, 'ios')]]}")
print(f"  typed records (dropped by the loader): {len(typed_rows)} "
      f"{typed_rows[['plot', 'pair_id', 'kind', 'flags']].to_dict('records')}")
print(f"\nPANEL B — {N_P} p-values across {N_TABLES} result tables "
      f"(~{N_FALSE:.0f} significant at alpha=0.05 by chance)")
print(f"  DBH   bias iOS {D_IOS['Bias [in | ft]']:+.2f} in "
      f"[{D_IOS['Bias 95% CI [in | ft]']}], Android {D_AND['Bias [in | ft]']:+.2f} in "
      f"[{D_AND['Bias 95% CI [in | ft]']}]")
print(f"  Hgt   bias iOS {H_IOS['Bias [in | ft]']:+.2f} ft, "
      f"Android {H_AND['Bias [in | ft]']:+.2f} ft (AGREEMENT with a 3-point laser)")
print(f"  cross-platform DBH {XD.mean_diff:+.3f} in [{XD.ci_low:+.2f},{XD.ci_high:+.2f}], "
      f"LoA span {XD.loa_high - XD.loa_low:.2f} in / {XH.loa_high - XH.loa_low:.2f} ft, "
      f"{n_class_change}/{len(pdbh)} stems change class")
print(f"  shared DBH r={SD_.r:+.3f} sd={SD_.shared_sd:.2f} in "
      f"(upper {SD_.shared_sd_ci_high:.2f}) vs {SD_.sd_ios:.2f}/{SD_.sd_android:.2f} in")
print(f"  shared Hgt r={SH_.r:+.3f} p={SH_.r_p:.2e} sd={SH_.shared_sd:.2f} ft "
      f"({SH_.shared_frac_ios * 100:.0f}/{SH_.shared_frac_android * 100:.0f} % of variance)")
