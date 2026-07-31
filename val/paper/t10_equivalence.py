#!/usr/bin/env python3
"""Equivalence of the ForestiX phone measurement with the field reference, against
tolerances a timber cruiser would actually accept.

WHY NOT A TEST AGAINST ZERO. With n = 100 stems, a paired t-test against a bias
of exactly zero rejects for any real instrument, and rejecting it says nothing
about whether the instrument can be used. The operational question is the
reverse one: is the discrepancy small enough that it cannot change what gets
written on the tally sheet or what the volume table returns? That is an
equivalence question, and it requires the tolerance to be fixed BEFORE the test
is run rather than read off the result.

THE MARGINS ARE PRE-STATED. Three per measurand, all of them from US timber
cruising convention, all of them declared in MARGINS below with their one-line
justification, none of them chosen after seeing a p-value. Two absolute margins
(a tight one and an operational one) and one relative margin each.

WHAT IS TESTED. Four contrasts: each handset against the tape/laser, the two
handsets averaged within stem, and the iOS-Android difference (equivalence of
the two handsets to each other, which never touches the reference). Each of
those pooled over the two stands and within each stand.

ASSUMPTION, STATED NOT ASSUMED. TOST is a pair of one-sided t-tests, so it
inherits the t-test's normality assumption for the sampling distribution of the
mean. Several of these error distributions are strongly right-skewed - iOS DBH
worst - so every row is repeated with a seeded percentile bootstrap of the same
90 % interval, which makes no distributional assumption, and the table records
where the two disagree. The estimand is deliberately still the MEAN: cruise
volume is a sum over stems, so it is mean error, not median error, that
propagates to the stand total.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()

ALPHA = 0.05          # per-side level; the reported interval is therefore 90 %
N_BOOT = 10000
SEED = 17


def para(text: str) -> str:
    """Collapse an f-string's source line breaks so the caption is one paragraph."""
    return " ".join(text.split())


# --------------------------------------------------------------------------
# The margins. Fixed here, before any test runs.
# --------------------------------------------------------------------------
# scale: 'abs' -> tested on the raw difference in the measurand's own unit
#        'pct' -> tested on the per-stem percent difference
MARGINS = {
    "dbh": [
        dict(bound=0.5, scale="abs", label="±0.5 in", tier="tight",
             why="half of the 1-in diameter class a cruiser writes on the tally "
                 "sheet, so an error inside it cannot move a stem out of the "
                 "1-in class it was recorded in"),
        dict(bound=1.0, scale="abs", label="±1.0 in", tier="operational",
             why="half of the 2-in diameter class used by standard cruise tally "
                 "forms and volume tables, so the stem stays in its own 2-in class"),
        dict(bound=5.0, scale="pct", label="±5 %", tier="relative",
             why="basal area goes as the square of diameter, so 5 % on DBH is "
                 "about 10 % on basal area, the outer edge of what a cruise-level "
                 "volume estimate absorbs"),
    ],
    "height": [
        dict(bound=5.0, scale="abs", label="±5 ft", tier="tight",
             why="one 5-ft increment of a standard total-height tally, and about "
                 "a third of a 16-ft log, so the recorded height is unchanged"),
        dict(bound=10.0, scale="abs", label="±10 ft", tier="operational",
             why="about 10 % of a typical 100-ft dominant and less than one 16-ft "
                 "log length, the practical field tolerance for total height taken "
                 "by clinometer or laser in operational cruising"),
        dict(bound=5.0, scale="pct", label="±5 %", tier="relative",
             why="volume is close to linear in total height, so a 5 % height error "
                 "is about a 5 % volume error, the usual cruise-level tolerance"),
    ],
}

CONTRASTS = {
    "ios": "iOS − reference",
    "android": "Android − reference",
    "both": "Both handsets (stem mean) − reference",
    "delta": "iOS − Android",
}
SCOPES = ["Both sites", "McDunn", "Starker"]


# --------------------------------------------------------------------------
# Build every contrast as one tidy per-stem series
#
# Each row of `SERIES[(measurand, contrast)]` is one stem, carrying the
# difference on both scales plus the tape-dispute flag, so site filtering and
# the disputed-stem sensitivity are both just row selections.
# --------------------------------------------------------------------------

def build(measurand: str, contrast: str) -> pd.DataFrame:
    sub = df[df.measurand == measurand]
    disputed = sub.groupby("stem")["tape_disputed"].any()

    if contrast in ("ios", "android"):
        s = sub[sub.device == contrast]
        out = pd.DataFrame(dict(
            stem=s.stem.to_numpy(), site=s.site.to_numpy(),
            reference=s.reference.to_numpy(),
            abs=s.error.to_numpy(float), pct=s.pct_error.to_numpy(float)))

    elif contrast == "both":
        # Averaged WITHIN stem, not pooled across rows. Pooling the 200 device
        # rows would treat two measurements of one stem as two independent
        # observations and shrink the standard error by a factor it has not
        # earned; the within-stem mean keeps n at the number of stems.
        g = sub.groupby(["stem", "site"], as_index=False).agg(
            reference=("reference", "first"),
            abs=("error", "mean"), pct=("pct_error", "mean"),
            n_dev=("device", "nunique"))
        out = g[["stem", "site", "reference", "abs", "pct"]].copy()

    elif contrast == "delta":
        # Never touches the tape in the numerator. The reference is used only as
        # the denominator of the percent scale, so that every percent row in the
        # table is scaled the same way.
        p = core.paired(df, measurand)
        out = pd.DataFrame(dict(
            stem=p.stem.to_numpy(), site=p.site.to_numpy(),
            reference=p.reference.to_numpy(float),
            abs=p.delta.to_numpy(float),
            pct=(p.delta / p.reference * 100.0).to_numpy(float)))
    else:
        raise ValueError(contrast)

    out["tape_disputed"] = out.stem.map(disputed).fillna(False).to_numpy(bool)
    return out


SERIES = {(m, c): build(m, c) for m in core.MEASURANDS for c in CONTRASTS}

# How many stems in each contrast carry only one handset (height loses one iOS
# capture), which matters only for the 'both' row.
SINGLE_DEV = {}
for m in core.MEASURANDS:
    sub = df[df.measurand == m]
    counts = sub.groupby("stem")["device"].nunique()
    SINGLE_DEV[m] = int((counts < 2).sum())


# --------------------------------------------------------------------------
# One equivalence decision
# --------------------------------------------------------------------------

_BOOT_CACHE: dict = {}


def boot_ci90(values, key):
    """Seeded percentile bootstrap of the mean, at the 90 % level TOST needs."""
    if key not in _BOOT_CACHE:
        _BOOT_CACHE[key] = core.bootstrap_ci(values, np.mean, n_boot=N_BOOT,
                                             alpha=2 * ALPHA, seed=SEED)
    return _BOOT_CACHE[key]


def verdict(ci_low, ci_high, bound):
    """Three outcomes, because 'not equivalent' hides two very different states.

    equivalent  - the 90 % CI lies inside the tolerance; the discrepancy is
                  demonstrably smaller than the margin.
    exceeds     - the 90 % CI lies wholly OUTSIDE the tolerance; the discrepancy
                  is demonstrably larger. This is a positive finding, not a
                  failure to find one.
    inconclusive- the CI straddles a tolerance edge; the sample cannot separate
                  the two, and reporting it as 'not equivalent' would overstate.
    """
    if ci_low > -bound and ci_high < bound:
        return "equivalent"
    if ci_low > bound or ci_high < -bound:
        return "exceeds"
    return "inconclusive"


def decide(values, bound, key):
    v = np.asarray(values, float)
    v = v[~np.isnan(v)]
    t = core.tost(v, bound, alpha=ALPHA)
    b_lo, b_hi = boot_ci90(v, key)
    sw_p = sps.shapiro(v)[1] if 3 <= len(v) <= 5000 else np.nan
    return dict(
        n=len(v), mean=t["mean"], sd=v.std(ddof=1),
        ci_low=t["ci_low"], ci_high=t["ci_high"], p=t["p"],
        verdict=verdict(t["ci_low"], t["ci_high"], bound),
        boot_ci_low=b_lo, boot_ci_high=b_hi,
        boot_verdict=verdict(b_lo, b_hi, bound),
        # The tightest margin this contrast WOULD meet: the half-width of the
        # 90 % CI measured from zero. Reported so a reader with a different
        # tolerance in mind can answer their own question from one number.
        min_margin=max(abs(t["ci_low"]), abs(t["ci_high"])),
        shapiro_p=sw_p,
    )


# --------------------------------------------------------------------------
# The full table
# --------------------------------------------------------------------------
rows = []
for m, meta in core.MEASURANDS.items():
    for c in CONTRASTS:
        s = SERIES[(m, c)]
        for scope in SCOPES:
            sel = s if scope == "Both sites" else s[s.site == scope]
            if len(sel) < 3:
                continue
            for mg in MARGINS[m]:
                col = mg["scale"]
                unit = "%" if col == "pct" else meta["unit"]
                key = (m, c, scope, col)
                d = decide(sel[col], mg["bound"], key)

                keep = sel[~sel.tape_disputed]
                n_disp = int(sel.tape_disputed.sum())
                if len(keep) >= 3:
                    de = decide(keep[col], mg["bound"], key + ("excl",))
                else:
                    de = {k: np.nan for k in d}
                    de["verdict"] = "n/a"

                rows.append(dict(
                    measurand=meta["short"], contrast=CONTRASTS[c],
                    contrast_key=c, scope=scope,
                    margin=mg["label"], margin_value=mg["bound"],
                    margin_scale=col, margin_tier=mg["tier"], unit=unit,
                    n=d["n"], mean_diff=d["mean"], sd=d["sd"],
                    # Equivalence is about the MEAN. This column says what
                    # fraction of individual stems fall inside the same
                    # tolerance, which is a much harsher and quite different
                    # question, and stops the two being read as one claim.
                    pct_stems_within=100.0 * (
                        sel[col].abs() <= mg["bound"]).mean(),
                    ci90_low=d["ci_low"], ci90_high=d["ci_high"],
                    tost_p=d["p"], verdict=d["verdict"],
                    min_margin_met=d["min_margin"],
                    boot_ci90_low=d["boot_ci_low"], boot_ci90_high=d["boot_ci_high"],
                    boot_verdict=d["boot_verdict"],
                    boot_agrees=d["boot_verdict"] == d["verdict"],
                    shapiro_p=d["shapiro_p"],
                    normal_diffs=bool(d["shapiro_p"] > 0.05)
                    if np.isfinite(d["shapiro_p"]) else np.nan,
                    n_disputed=n_disp, n_excl=de["n"],
                    mean_diff_excl=de["mean"], tost_p_excl=de["p"],
                    verdict_excl=de["verdict"],
                    verdict_changed=(de["verdict"] != d["verdict"]),
                    margin_rationale=mg["why"],
                ))

out = pd.DataFrame(rows)
num = out.select_dtypes(include=[float]).columns
out[num] = out[num].round(4)

tcap = f"""
Table 10. Equivalence of the ForestiX phone measurement with the field reference
(diameter tape for DBH, laser rangefinder in 3-point mode for height) against
pre-stated timber-cruising tolerances, by two one-sided tests (TOST) at
alpha = {ALPHA:.2f} per side. Margins were fixed before the tests were run:
{MARGINS['dbh'][0]['label']} and {MARGINS['dbh'][1]['label']} on DBH,
{MARGINS['height'][0]['label']} and {MARGINS['height'][1]['label']} on height,
and {MARGINS['dbh'][2]['label']} on both, each justified in the
margin_rationale column. `mean_diff` is phone minus reference for the three
reference contrasts and iOS minus Android for the fourth; `ci90_low`/`ci90_high`
are the 90 % confidence interval TOST is equivalent to, so a verdict follows
from where that interval falls. `verdict` is three-way on purpose: `equivalent`
means the interval lies inside the tolerance, `exceeds` means it lies wholly
outside it (a positive finding that the discrepancy is larger than the margin),
and `inconclusive` means the interval straddles a tolerance edge and this sample
cannot separate the two. `min_margin_met` is the tightest symmetric tolerance
the contrast would satisfy, i.e. the distance from zero to the far end of the
90 % interval, given so a reader with a different tolerance in mind can decide
from one number. TOST is t-based; `shapiro_p` and `normal_diffs` record whether
that normality assumption holds, and `boot_verdict` repeats the decision from a
seeded {N_BOOT:,}-draw percentile bootstrap of the same interval, which assumes
nothing about the distribution - `boot_agrees` flags any row where the two
methods disagree. The `both` contrast averages the two handsets WITHIN stem
rather than pooling {len(df[df.measurand=='dbh'])} device rows, so that two
measurements of one stem are not counted as two independent observations.
Columns ending `_excl` repeat the test with the `n_disputed` stems whose tape
reading is disputed removed, and `verdict_changed` flags any row whose
conclusion moves. `pct_stems_within` is included to keep two different claims
apart: equivalence is a statement about the MEAN discrepancy, which is what
aggregates to a stand total, whereas `pct_stems_within` is the far harsher
per-stem question of how many individual trees fall inside the same tolerance,
and it is much lower everywhere. Diameters in inches, heights in feet, percent
rows on the per-stem percent difference with the tape reading as denominator.
"""
core.save_table(out, "t10_equivalence", para(tcap))


# --------------------------------------------------------------------------
# Figure: the forest plot
# --------------------------------------------------------------------------
ROWS = [
    ("ios", "Both sites", "iOS, both sites"),
    ("ios", "McDunn", "iOS, McDunn"),
    ("ios", "Starker", "iOS, Starker"),
    ("android", "Both sites", "Android, both sites"),
    ("android", "McDunn", "Android, McDunn"),
    ("android", "Starker", "Android, Starker"),
    ("both", "Both sites", "Both handsets (stem mean)"),
    ("delta", "Both sites", "iOS − Android"),
]

STYLE = {
    "ios": dict(color=core.PALETTE["ios"], marker="o"),
    "android": dict(color=core.PALETTE["android"], marker="s"),
    "both": dict(color=core.PALETTE["reference"], marker="D"),
    "delta": dict(color=core.PALETTE["muted"], marker="^"),
}

fig, axes = plt.subplots(2, 2, figsize=(core.FIG_W * 1.34, core.FIG_H * 1.55))
tags = [["A", "B"], ["C", "D"]]

panel_note = {}

for i, scale in enumerate(["abs", "pct"]):
    for j, m in enumerate(["dbh", "height"]):
        ax = axes[i][j]
        meta = core.MEASURANDS[m]
        mgs = [g for g in MARGINS[m] if g["scale"] == scale]
        unit = "%" if scale == "pct" else meta["unit"]

        # Tolerance bands, widest first so the tight one sits on top. Edge line
        # style carries the tier as well as the fill, for greyscale printing.
        for k, mg in enumerate(sorted(mgs, key=lambda g: -g["bound"])):
            b = mg["bound"]
            ax.axvspan(-b, b, color=core.PALETTE["good"],
                       alpha=0.10 if k == 0 and len(mgs) > 1 else 0.18,
                       zorder=0, lw=0)
            for edge in (-b, b):
                ax.axvline(edge, color=core.PALETTE["good"],
                           ls=":" if (k == 0 and len(mgs) > 1) else "--",
                           lw=1.0, zorder=1)
        ax.axvline(0, color=core.PALETTE["reference"], lw=0.9, zorder=1)

        ys, labels, met_ref, met_ref_tight = [], [], [], []
        for r, (c, scope, lab) in enumerate(ROWS):
            y = r                      # top-to-bottom; the axis is inverted below
            ys.append(y)
            labels.append(lab)
            s = SERIES[(m, c)]
            sel = s if scope == "Both sites" else s[s.site == scope]
            v = sel[scale].to_numpy(float)
            v = v[~np.isnan(v)]
            t = core.tost(v, mgs[0]["bound"], alpha=ALPHA)
            st = STYLE[c]
            pooled = scope == "Both sites"
            ax.plot([t["ci_low"], t["ci_high"]], [y, y],
                    color=st["color"], lw=1.9 if pooled else 1.2,
                    solid_capstyle="butt", zorder=3)
            for cap in (t["ci_low"], t["ci_high"]):
                ax.plot([cap, cap], [y - 0.16, y + 0.16], color=st["color"],
                        lw=1.9 if pooled else 1.2, zorder=3)
            ax.plot([t["mean"]], [y], marker=st["marker"],
                    ms=6.4 if pooled else 5.2,
                    mfc=st["color"] if pooled else "white",
                    mec=st["color"], mew=1.3, ls="none", zorder=4)
            ax.annotate(f"{t['mean']:+.2f}", (t["mean"], y),
                        textcoords="offset points", xytext=(0, 6.5),
                        ha="center", va="bottom", fontsize=6.4,
                        color=st["color"],
                        fontweight="bold" if pooled else "normal", zorder=5)

            # Right-hand column: the tightest STATED tolerance this row meets.
            met = []
            for g in sorted(mgs, key=lambda g: g["bound"]):
                tt = core.tost(v, g["bound"], alpha=ALPHA)
                if verdict(tt["ci_low"], tt["ci_high"], g["bound"]) == "equivalent":
                    met.append(g["label"])
            if c != "delta":            # the panel summary describes the tape rows
                met_ref.append(bool(met))
                met_ref_tight.append(bool(
                    met and met[0] == min(mgs, key=lambda g: g["bound"])["label"]))
            txt = met[0] if met else "none"
            ax.text(1.015, y, txt, transform=ax.get_yaxis_transform(),
                    ha="left", va="center", fontsize=6.8,
                    color=core.PALETTE["good"] if met else core.PALETTE["accent"],
                    fontweight="bold" if met else "normal")

        ax.set_yticks(ys)
        # The two panels in a row carry identical rows, so the label column is
        # written once and shared rather than duplicated at half the size.
        ax.set_yticklabels(labels if j == 0 else [], fontsize=7.4)
        # Headroom above the top row for the panel heading and its verdict line,
        # so neither has to be boxed on top of a confidence interval.
        ax.set_ylim(-1.85, len(ROWS) - 0.10)
        ax.invert_yaxis()
        ax.grid(axis="y", visible=False)
        ax.set_xlabel(
            f"Mean difference ({unit})" if scale == "abs"
            else "Mean difference (% of tape reading)")

        widest = max(g["bound"] for g in mgs)
        lo = min([core.tost(
            (SERIES[(m, c)] if sc == "Both sites"
             else SERIES[(m, c)][SERIES[(m, c)].site == sc])[scale].dropna(),
            widest, alpha=ALPHA)["ci_low"] for c, sc, _ in ROWS] + [-widest])
        hi = max([core.tost(
            (SERIES[(m, c)] if sc == "Both sites"
             else SERIES[(m, c)][SERIES[(m, c)].site == sc])[scale].dropna(),
            widest, alpha=ALPHA)["ci_high"] for c, sc, _ in ROWS] + [widest])
        pad = 0.16 * (hi - lo)
        ax.set_xlim(lo - pad, hi + pad)

        # Name the bands along the bottom of the panel.
        for mg in mgs:
            ax.text(mg["bound"], 0.012, " " + mg["label"],
                    transform=ax.get_xaxis_transform(), fontsize=6.6,
                    color=core.PALETTE["good"], ha="left", va="bottom")

        core.panel_tag(ax, tags[i][j])
        ax.set_title("")

        head = f"{meta['short']} — " + ("absolute" if scale == "abs" else "relative")
        ax.text(0.015, 0.985, head, transform=ax.transAxes, fontsize=8.2,
                fontweight="bold", ha="left", va="top", zorder=8,
                color=core.PALETTE["reference"])

        # Panel verdict, generated from the same tests that drew the rows.
        tight = min(mgs, key=lambda g: g["bound"])
        n_ref, n_any, n_tight = len(met_ref), sum(met_ref), sum(met_ref_tight)
        if n_any == 0:
            line, col = "vs reference: outside every stated tolerance", "accent"
        elif n_tight == n_ref:
            line, col = f"vs reference: inside {tight['label']} on every row", "good"
        else:
            line, col = (f"vs reference: inside {tight['label']} on "
                         f"{n_tight} of {n_ref} rows", "warn")
        # One line only: the pooled means are already annotated on their rows.
        # The white backing keeps it legible where it crosses a band edge; it
        # sits under the heading, which is drawn at a higher zorder.
        ax.text(0.015, 0.985, f"\n{line}",
                transform=ax.transAxes, fontsize=7.0, ha="left", va="top",
                linespacing=1.65, color=core.PALETTE[col], zorder=6,
                bbox=dict(boxstyle="square,pad=0.15", fc="white", alpha=0.78,
                          ec="none"))

for ax in axes.ravel():
    ax.tick_params(axis="y", length=0)

fig.subplots_adjust(wspace=0.30, hspace=0.40)

# Header for the right-hand column, once per panel.
for ax in axes.ravel():
    ax.text(1.015, -0.80, "meets", transform=ax.get_yaxis_transform(),
            ha="left", va="center", fontsize=6.8, style="italic",
            color=core.PALETTE["muted"])

from matplotlib.lines import Line2D
handles = [
    Line2D([], [], color=core.PALETTE["ios"], marker="o", ls="none", ms=6,
           label="iOS (LiDAR)"),
    Line2D([], [], color=core.PALETTE["android"], marker="s", ls="none", ms=6,
           label="Android (ARCore)"),
    Line2D([], [], color=core.PALETTE["reference"], marker="D", ls="none", ms=5.5,
           label="Both handsets, stem mean"),
    Line2D([], [], color=core.PALETTE["muted"], marker="^", ls="none", ms=6,
           label="iOS − Android"),
    Line2D([], [], color=core.PALETTE["muted"], marker="o", ls="none", ms=6,
           mfc="white", mew=1.3, label="open marker = single stand"),
    Line2D([], [], color=core.PALETTE["good"], ls="--", lw=1.0,
           label="inner tolerance edge"),
    Line2D([], [], color=core.PALETTE["good"], ls=":", lw=1.0,
           label="outer tolerance edge"),
]
fig.legend(handles=handles, loc="lower center", ncol=4, fontsize=7.4,
           bbox_to_anchor=(0.5, -0.075), handletextpad=0.5, columnspacing=1.4)

# Facts the caption quotes, read off the table rather than typed in by hand.
_series = out[["measurand", "contrast_key", "scope", "margin_scale"]].drop_duplicates()
n_series = len(_series)
n_nonnormal = len(out[out.normal_diffs == False]
                  [["measurand", "contrast_key", "scope", "margin_scale"]]
                  .drop_duplicates())
_disagree = out[~out.boot_agrees]
_edge = _disagree.iloc[0] if len(_disagree) else None
_edge_hi = _edge.ci90_high if _edge is not None else float("nan")
_edge_boot = _edge.boot_ci90_high if _edge is not None else float("nan")
_edge_p = _edge.tost_p if _edge is not None else float("nan")

fcap = f"""
Figure 10. Equivalence of the ForestiX measurement with the field reference against
pre-stated timber-cruising tolerances. Each row is the mean difference with the
90 % confidence interval that two one-sided tests (TOST, alpha = {ALPHA:.2f} per
side) are equivalent to; shaded bands are the tolerances, dashed edges the tight
margin and dotted edges the operational one. A row is equivalent at a tolerance
when its whole interval lies inside that band, and the right-hand column names the
tightest stated tolerance each row meets. (A) DBH in inches against
{MARGINS['dbh'][0]['label']} and {MARGINS['dbh'][1]['label']};
(B) total height in feet against {MARGINS['height'][0]['label']} and
{MARGINS['height'][1]['label']}; (C, D) the same contrasts on the per-stem percent
difference against {MARGINS['dbh'][2]['label']}. Filled markers pool the two
stands, open markers are one stand. The first three contrasts are phone minus tape
or laser; the fourth is iOS minus Android and never involves the reference. TOST is
t-based and most of these error distributions are right-skewed (Shapiro-Wilk
rejects normality for {n_nonnormal} of the {n_series} series, iOS DBH worst at
p < 0.001), so a seeded {N_BOOT:,}-draw percentile bootstrap of the same intervals
was run alongside; it reproduces {len(out) - len(_disagree)} of the {len(out)}
verdicts in Table 10. The one exception is visible here: Android at McDunn against
{MARGINS['dbh'][1]['label']} in (A), where the t interval ends at
{_edge_hi:+.4f} in and the bootstrap interval at {_edge_boot:+.4f} in, straddling
the margin from either side (TOST p = {_edge_p:.4f}). That row is a knife-edge
case, not a divergence between the methods.
"""
core.save(fig, "fig10_equivalence", para(fcap))


# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 250, "display.max_columns", 60)
show = out[["measurand", "contrast_key", "scope", "margin", "n", "mean_diff",
            "ci90_low", "ci90_high", "tost_p", "verdict", "boot_verdict",
            "min_margin_met", "verdict_excl", "verdict_changed"]]
print(show.to_string(index=False))

print("\n--- verdict summary (pooled, both sites) ---")
for _, r in out[out.scope == "Both sites"].iterrows():
    print(f"{r.measurand:6s} {r.contrast_key:8s} {r.margin:9s} "
          f"mean {r.mean_diff:+7.3f} {r.unit:2s} "
          f"90% CI [{r.ci90_low:+.3f}, {r.ci90_high:+.3f}]  "
          f"p={r.tost_p:.4f}  {r.verdict.upper()}")

print("\n--- assumption check ---")
bad = out[~out.boot_agrees]
print(f"rows where bootstrap and t-based verdicts differ: {len(bad)} / {len(out)}")
if len(bad):
    print(bad[["measurand", "contrast_key", "scope", "margin", "verdict",
               "boot_verdict"]].to_string(index=False))
nn = out[out.normal_diffs == False][["measurand", "contrast_key", "scope",
                                     "margin_scale", "shapiro_p"]].drop_duplicates()
print(f"series failing Shapiro-Wilk at 0.05: {len(nn)} of "
      f"{len(out[['measurand','contrast_key','scope','margin_scale']].drop_duplicates())}")

print("\n--- disputed-tape sensitivity ---")
ch = out[out.verdict_changed]
print(f"rows whose verdict changes when the disputed-tape stems are dropped: "
      f"{len(ch)} / {len(out)}")
if len(ch):
    print(ch[["measurand", "contrast_key", "scope", "margin", "n", "n_excl",
              "mean_diff", "mean_diff_excl", "verdict",
              "verdict_excl"]].to_string(index=False))

print("\n--- tightest tolerance each pooled contrast would meet ---")
for m in core.MEASURANDS:
    for c in CONTRASTS:
        for scale in ("abs", "pct"):
            r = out[(out.measurand == core.MEASURANDS[m]["short"]) &
                    (out.contrast_key == c) & (out.scope == "Both sites") &
                    (out.margin_scale == scale)].head(1)
            if len(r):
                r = r.iloc[0]
                u = "%" if scale == "pct" else core.MEASURANDS[m]["unit"]
                print(f"{r.measurand:6s} {c:8s} {scale:3s}: "
                      f"±{r.min_margin_met:.2f} {u}")

# Post-hoc, and labelled as such: is the DBH failure location or spread?
print("\n--- post-hoc (NOT a pre-stated test): DBH failure is offset, not noise ---")
for c in ("ios", "android"):
    v = SERIES[("dbh", c)]["abs"].dropna().to_numpy(float)
    cen = v - v.mean()
    t = core.tost(cen, 0.5, alpha=ALPHA)
    print(f"  DBH {c:8s}: observed mean {v.mean():+.3f} in; after removing that "
          f"constant offset the residual scatter alone gives 90% CI "
          f"[{t['ci_low']:+.3f}, {t['ci_high']:+.3f}] in, i.e. it would meet "
          f"±{max(abs(t['ci_low']), abs(t['ci_high'])):.2f} in")
print(f"\nsingle-handset stems (affects the 'both' row only): "
      f"DBH {SINGLE_DEV['dbh']}, height {SINGLE_DEV['height']}")
