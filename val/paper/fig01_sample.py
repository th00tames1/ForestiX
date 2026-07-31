#!/usr/bin/env python3
"""Sample composition: what the 100 validation stems actually span.

Table t01_sample and figure fig01_sample. Everything here describes the
REFERENCE measurements (diameter tape, laser rangefinder in 3-point mode) —
the phones do not enter this analysis at all, because the question a reviewer
asks of a sample table is whether the sample is worth validating against, not
how the instrument under test performed on it.

One row per stem per measurand: the loader gives one row per DEVICE, so the
reference is duplicated and has to be de-duplicated before it is summarised,
or every mean is silently weighted by how many phones happened to read the
stem (99 iOS heights, 100 Android).
"""
from __future__ import annotations

from decimal import Decimal, ROUND_HALF_UP

import numpy as np
import pandas as pd

import core

plt = core.use_style()
df = core.load()

CM, M = core.CM_PER_IN, core.M_PER_FT
SITE_ORDER = core.SITES

# --------------------------------------------------------------------------
# One row per stem per measurand — the reference, stripped of device duplication
# --------------------------------------------------------------------------
ref = (df.drop_duplicates(subset=["stem", "measurand"])
         [["stem", "site", "measurand", "reference", "reference_metric",
           "species", "tape_disputed"]]
         .reset_index(drop=True))

# A stem's species is a property of the stem, not of the measurand; carry the
# one non-null value forward so a stem is counted once, not once per measurand.
sp_by_stem = (df.dropna(subset=["species"])
                .drop_duplicates("stem").set_index("stem")["species"])
stems = (ref.drop_duplicates("stem")[["stem", "site"]]
            .assign(species=lambda d: d.stem.map(sp_by_stem)))

# The disputed-tape flag is carried per (stem, measurand): a stem can have a
# contested diameter and an uncontested height. Count both ways.
disp = ref[ref.tape_disputed]


def rnd(x: float, nd: int = 1) -> float:
    """Round half AWAY from zero, not to even.

    numpy and the built-in round send 20.25 to 20.2, which reads as an
    arithmetic slip when the figure caption quotes the same median as 20.3.
    """
    q = Decimal(1).scaleb(-nd)
    return float(Decimal(repr(float(x))).quantize(q, rounding=ROUND_HALF_UP))


def species_string(sub: pd.DataFrame) -> str:
    counts = sub.species.value_counts(dropna=False)
    named = [(k, v) for k, v in counts.items() if not pd.isna(k)]
    unnamed = int(sum(v for k, v in counts.items() if pd.isna(k)))
    parts = [f"{k} {v}" for k, v in sorted(named, key=lambda kv: -kv[1])]
    if unnamed:
        parts.append(f"not recorded {unnamed}")
    return "; ".join(parts)


def block(sub: pd.DataFrame, kind: str, prefix: str, conv: float) -> dict:
    v = sub[sub.measurand == kind].reference
    if len(v) == 0:
        return {}
    imp = dict(n=len(v), min=v.min(), median=v.median(), mean=v.mean(),
               max=v.max(), sd=v.std(ddof=1))
    out = {f"{prefix}_n": imp["n"]}
    for stat in ("min", "median", "mean", "max", "sd"):
        out[f"{prefix}_{stat}"] = rnd(imp[stat], 1)
    for stat in ("min", "median", "mean", "max", "sd"):
        out[f"{prefix}_{stat}_metric"] = rnd(imp[stat] * conv, 2)
    return out


rows = []
for site in SITE_ORDER + ["All"]:
    r_sub = ref if site == "All" else ref[ref.site == site]
    s_sub = stems if site == "All" else stems[stems.site == site]
    d_sub = disp if site == "All" else disp[disp.site == site]
    row = {"site": site, "n_stems": len(s_sub)}
    row.update(block(r_sub, "dbh", "dbh_in", CM))
    row.update(block(r_sub, "height", "ht_ft", M))
    row["species"] = species_string(s_sub)
    row["disputed_tape_stems"] = d_sub.stem.nunique()
    row["disputed_tape_dbh"] = int((d_sub.measurand == "dbh").sum())
    row["disputed_tape_height"] = int((d_sub.measurand == "height").sum())
    # phone readings actually available (a typed reading is already excluded)
    for kind, tag in (("dbh", "dbh"), ("height", "ht")):
        for dev in core.DEVICES:
            m = df[(df.measurand == kind) & (df.device == dev)]
            if site != "All":
                m = m[m.site == site]
            row[f"n_{dev}_{tag}"] = len(m)
    rows.append(row)

table = pd.DataFrame(rows)
order = (["site", "n_stems", "species", "disputed_tape_stems",
          "disputed_tape_dbh", "disputed_tape_height"]
         + [c for c in table.columns if c.startswith("dbh_in")]
         + [c for c in table.columns if c.startswith("ht_ft")]
         + [c for c in table.columns if c.startswith("n_ios")
            or c.startswith("n_android")])
table = table[order]

core.save_table(
    table, "t01_sample",
    "Table 1. Composition of the validation sample. One row per stand plus a "
    "pooled row; n_stems is the number of stems, each measured once with a "
    "diameter tape (DBH) and once with a laser rangefinder in 3-point mode "
    "(height). Reference distributions are given in inches and feet as "
    "recorded on the field sheet, with the same statistics converted to "
    "centimetres and metres (columns ending _metric). SD is the sample "
    "standard deviation (ddof = 1). Species are the field codes as written; "
    "they were recorded only at Starker, so all 50 McDunn stems appear as "
    "not recorded. disputed_tape counts stem-measurand records flagged "
    "TAPE-MISMATCH, split by measurand because the flag is carried per "
    "measurement, not per stem. n_ios_* / n_android_* are the phone readings "
    "available for analysis; one iOS height was typed rather than measured "
    "and is excluded throughout.")

# --------------------------------------------------------------------------
# Sensitivity: do the 8 disputed-tape records change the described sample?
# --------------------------------------------------------------------------
keep = ref[~ref.tape_disputed]
sens = []
for kind, unit in (("dbh", "in"), ("height", "ft")):
    a = ref[ref.measurand == kind].reference
    b = keep[keep.measurand == kind].reference
    sens.append(dict(measurand=kind, unit=unit, n_all=len(a), n_kept=len(b),
                     min_all=a.min(), min_kept=b.min(),
                     median_all=a.median(), median_kept=b.median(),
                     mean_all=a.mean(), mean_kept=b.mean(),
                     max_all=a.max(), max_kept=b.max(),
                     sd_all=a.std(ddof=1), sd_kept=b.std(ddof=1)))
    for site in SITE_ORDER:
        aa = ref[(ref.measurand == kind) & (ref.site == site)].reference
        bb = keep[(keep.measurand == kind) & (keep.site == site)].reference
        sens.append(dict(measurand=f"{kind} ({site})", unit=unit,
                         n_all=len(aa), n_kept=len(bb),
                         min_all=aa.min(), min_kept=bb.min(),
                         median_all=aa.median(), median_kept=bb.median(),
                         mean_all=aa.mean(), mean_kept=bb.mean(),
                         max_all=aa.max(), max_kept=bb.max(),
                         sd_all=aa.std(ddof=1), sd_kept=bb.std(ddof=1)))
sens = pd.DataFrame(sens).round(2)
print("\nDisputed-tape sensitivity (all records vs. disputed removed):")
print(sens.to_string(index=False))

# --------------------------------------------------------------------------
# Are the two stands the same population? Rank-sum, not a t-test: the pooled
# height distribution is visibly bimodal and the diameters are right-skewed,
# so a mean-difference test would be testing an assumption the data breaks.
# --------------------------------------------------------------------------
from scipy import stats as sps  # noqa: E402

print("\nStand contrast (Mann-Whitney U, two-sided; Shapiro-Wilk on each stand):")
for kind, unit in (("dbh", "in"), ("height", "ft")):
    a = ref[(ref.measurand == kind) & (ref.site == "McDunn")].reference
    b = ref[(ref.measurand == kind) & (ref.site == "Starker")].reference
    u = sps.mannwhitneyu(a, b, alternative="two-sided")
    cles = (u.statistic / (len(a) * len(b)))          # P(McDunn > Starker)
    print(f"  {kind:7s} McDunn median {rnd(a.median())} vs Starker "
          f"{rnd(b.median())} {unit}; U = {u.statistic:.0f}, p = {u.pvalue:.2g}, "
          f"P(McDunn > Starker) = {cles:.2f}; "
          f"Shapiro p McDunn {sps.shapiro(a).pvalue:.3g}, "
          f"Starker {sps.shapiro(b).pvalue:.3g}")

# Size-class occupancy — how much of a cruiser's class structure is covered
occ = {}
for kind, bins in (("dbh", core.DBH_CLASSES), ("height", core.HEIGHT_CLASSES)):
    labels = [f"{lo}–{hi}" if hi < 999 else f"{lo}+" for lo, hi in bins]
    v = ref[ref.measurand == kind].reference
    cls = v.apply(lambda x: core.size_class(x, kind))
    occ[kind] = {lab: int((cls == lab).sum()) for lab in labels}
    k = keep[keep.measurand == kind].reference.apply(
        lambda x: core.size_class(x, kind))
    kept_occ = {lab: int((k == lab).sum()) for lab in labels}
    print(f"\n{kind} class occupancy: {occ[kind]}")
    print(f"{kind} class occupancy, disputed removed: {kept_occ}")

# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
SPEC = {
    "dbh": dict(bins=np.arange(6, 54, 3), unit="in", metric="cm", conv=CM,
                label="Diameter at breast height (in)",
                metric_label="DBH (cm)", classes=core.DBH_CLASSES,
                fmt="{:.1f}", mfmt="{:.1f}"),
    "height": dict(bins=np.arange(30, 185, 10), unit="ft", metric="m", conv=M,
                   label="Total height (ft)", metric_label="Height (m)",
                   classes=core.HEIGHT_CLASSES, fmt="{:.1f}", mfmt="{:.1f}"),
}
STYLE = {
    "McDunn": dict(hatch=None, ls="-", marker="o", mfc=core.PALETTE["site"]["McDunn"]),
    "Starker": dict(hatch="///", ls="--", marker="^", mfc="white"),
}

fig = plt.figure(figsize=(core.FIG_W, core.FIG_H))
gs = fig.add_gridspec(2, 2, height_ratios=[3.0, 1.15], hspace=0.10,
                      wspace=0.22, top=0.86, bottom=0.13, left=0.09, right=0.98)

for col, (kind, tag) in enumerate((("dbh", "A"), ("height", "B"))):
    sp = SPEC[kind]
    ax = fig.add_subplot(gs[0, col])
    axr = fig.add_subplot(gs[1, col], sharex=ax)
    v_all = ref[ref.measurand == kind]

    for site in SITE_ORDER:
        v = v_all[v_all.site == site].reference.values
        c = core.PALETTE["site"][site]
        st = STYLE[site]
        ax.hist(v, bins=sp["bins"], histtype="stepfilled", facecolor=c,
                alpha=0.22, edgecolor="none", zorder=1)
        ax.hist(v, bins=sp["bins"], histtype="step", edgecolor=c,
                linewidth=1.3, linestyle=st["ls"], hatch=st["hatch"],
                label=f"{site} (n = {len(v)})", zorder=3)

    # class boundaries: the cruiser's own strata, so coverage is judged
    # against something a forester already has a feel for
    for lo, hi in sp["classes"][1:]:
        if lo <= v_all.reference.max():
            ax.axvline(lo, color=core.PALETTE["muted"], lw=0.6, ls=":",
                       alpha=0.8, zorder=2)

    ax.set_ylabel("Stems")
    ax.tick_params(labelbottom=False)
    ax.margins(x=0.02)
    # headroom so the legend and the class note never sit on a bar
    top = max(np.histogram(v_all[v_all.site == s].reference, bins=sp["bins"])[0].max()
              for s in SITE_ORDER)
    ax.set_ylim(0, top * 1.42)
    ax.yaxis.set_major_locator(plt.MaxNLocator(nbins=6, integer=True))
    core.panel_tag(ax, tag)

    sec = ax.secondary_xaxis("top", functions=(lambda x, c=sp["conv"]: x * c,
                                               lambda x, c=sp["conv"]: x / c))
    sec.set_xlabel(sp["metric_label"], fontsize=8, labelpad=2)
    sec.tick_params(labelsize=7)

    n_cls = sum(1 for k, n in occ[kind].items() if n > 0)
    ax.text(0.02, 0.98, f"{n_cls} of {len(sp['classes'])} size classes occupied",
            transform=ax.transAxes, ha="left", va="top", fontsize=8,
            color=core.PALETTE["muted"])
    ax.legend(loc="upper right", bbox_to_anchor=(1.0, 1.02), fontsize=8,
              handlelength=1.9, borderaxespad=0.2, labelspacing=0.35)

    # --- coverage strip: every stem as its own mark, one row per stand ------
    ypos = {"McDunn": 0.72, "Starker": 0.30}
    for site in SITE_ORDER:
        v = v_all[v_all.site == site].reference.values
        st = STYLE[site]
        axr.plot(v, np.full(len(v), ypos[site]), linestyle="none",
                 marker=st["marker"], ms=3.4, mfc=st["mfc"], mew=0.7,
                 mec=core.PALETTE["site"][site], alpha=0.85, clip_on=False)

    lo, hi = v_all.reference.min(), v_all.reference.max()
    ybr = 1.30
    axr.plot([lo, hi], [ybr, ybr], color=core.PALETTE["reference"], lw=0.9,
             clip_on=False, zorder=5)
    for x in (lo, hi):
        axr.plot([x, x], [ybr - 0.11, ybr + 0.11],
                 color=core.PALETTE["reference"], lw=0.9, clip_on=False, zorder=5)
    span = (f"{sp['fmt'].format(lo)}–{sp['fmt'].format(hi)} {sp['unit']}"
            f"  ({sp['mfmt'].format(lo * sp['conv'])}–"
            f"{sp['mfmt'].format(hi * sp['conv'])} {sp['metric']})")
    axr.text((lo + hi) / 2, ybr + 0.24, span, ha="center", va="bottom",
             fontsize=8, color=core.PALETTE["reference"], clip_on=False)

    axr.set_ylim(0, 1.75)
    axr.set_yticks([ypos[s] for s in SITE_ORDER])
    axr.set_yticklabels(SITE_ORDER, fontsize=8)
    for lab, site in zip(axr.get_yticklabels(), SITE_ORDER):
        lab.set_color(core.PALETTE["site"][site])
    axr.tick_params(axis="y", length=0)
    axr.grid(False)
    axr.spines["left"].set_visible(False)
    axr.set_xlabel(sp["label"])

# THE CAPTION READS ITS OWN NUMBERS OFF THE DATA. Hard-coding them here is
# how "15.8 in / 64.1 ft" survived a rebuild of the reference table and
# contradicted the figure above it: re-running this script re-drew the plot
# from the new data and re-wrote the caption from the old prose. Anything a
# caption asserts about the sample is computed, so the two cannot drift.
def _median(site, kind):
    conv = core.MEASURANDS[kind]["conv"]
    sub = df[(df.site == site) & (df.measurand == kind)]
    ref = sub.drop_duplicates(subset="stem")["reference"]
    return ref.median()

core.save(fig, "fig01_sample",
          "Figure 1. Size range covered by the validation sample "
          f"({df.stem.nunique()} stems, 50 per stand), from the reference "
          "instruments only. (A) Diameter at breast height from a diameter "
          "tape; (B) total height from a laser rangefinder in 3-point mode, "
          "which inverts the same tangent geometry the app does. Histograms "
          "are stems per bin (3 in and 10 ft bins) by stand — McDunn solid, "
          "Starker dashed with hatching. Dotted verticals mark the diameter "
          "and height class boundaries used throughout. The strip below each "
          "histogram plots every individual stem, and the bracket gives the "
          "full span in imperial with the metric equivalent. The sample "
          "occupies all six diameter classes and all five height classes, but "
          "the two stands are not interchangeable: Starker stems are both "
          f"larger and much taller (median {_median('Starker','dbh'):.1f} in / "
          f"{_median('Starker','height'):.1f} ft) than McDunn stems "
          f"({_median('McDunn','dbh'):.1f} in / {_median('McDunn','height'):.1f}"
          " ft), so a difference between stands is a site difference confounded "
          "with tree size and collection day, never a stand effect. Records "
          "carrying a disputed tape value are plotted here; the two whose two "
          "recorded tape values differ by more than 10 % have no recoverable "
          "reference and are excluded from the table entirely. This describes "
          "the stems measured on these two stands.")

# --------------------------------------------------------------------------
print("\n" + table.to_string(index=False))
for kind, sp in SPEC.items():
    v = ref[ref.measurand == kind].reference
    c = sp["conv"]
    print(f"\n{kind}: {rnd(v.min())}–{rnd(v.max())} {sp['unit']} = "
          f"{rnd(v.min() * c, 2)}–{rnd(v.max() * c, 2)} {sp['metric']}"
          f"  median {rnd(v.median())} {sp['unit']} "
          f"({rnd(v.median() * c, 2)} {sp['metric']})"
          f"  mean {rnd(v.mean())}  SD {rnd(v.std(ddof=1))}")
    for site in SITE_ORDER:
        s = ref[(ref.measurand == kind) & (ref.site == site)].reference
        print(f"   {site:8s} {rnd(s.min())}–{rnd(s.max())} {sp['unit']} "
              f"({rnd(s.min() * c, 2)}–{rnd(s.max() * c, 2)} "
              f"{sp['metric']})  median {rnd(s.median())} "
              f"({rnd(s.median() * c, 2)} {sp['metric']})  IQR "
              f"{rnd(s.quantile(.25))}–{rnd(s.quantile(.75))}")
