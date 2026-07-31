#!/usr/bin/env python3
"""Shared foundation for the manuscript analyses: the data, the statistics, the look.

ONE LOADER, ONE STYLE, ONE SET OF ESTIMATORS. Every figure and every table in
`../paper/` imports from here, so a change to the sample definition or the
house style propagates instead of being re-typed into a dozen scripts and
drifting.

UNITS. The reference measurements were taken in imperial — a diameter tape in
inches, a laser in feet — and every number a reader will want to check against
a field sheet is imperial. Analyses therefore run in inches and feet, with
metric shown alongside in the summary tables only. Converting the reference to
metric and back would introduce rounding the field sheet does not have.

WHAT THE SAMPLE IS. 100 stems, 50 in each of two stands, each measured by both
an iPhone (LiDAR) and an Android phone (ARCore depth), against one tape reading
per stem. Diameters are recomputed from the stored depth frames by a single
estimator implementation, because the recording binary changed partway through
the collection and the manifests cannot distinguish the two eras. Heights are
as recorded: the two records agree on every capture, and the tangent geometry
needs poses the table does not carry.
"""
from __future__ import annotations

import math
import os
import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
VAL = os.path.dirname(HERE)
PAIRS = os.path.join(VAL, "analysis", "final_pairs.csv")
FIGDIR = os.path.join(HERE, "figures")
RESDIR = os.path.join(HERE, "results")
os.makedirs(FIGDIR, exist_ok=True)
os.makedirs(RESDIR, exist_ok=True)

CM_PER_IN = 2.54
M_PER_FT = 0.3048

# What each measurand is called, and what it is measured in.
MEASURANDS = {
    "dbh": dict(label="Diameter at breast height", short="DBH",
                unit="in", metric="cm", conv=CM_PER_IN),
    "height": dict(label="Total height", short="Height",
                   unit="ft", metric="m", conv=M_PER_FT),
}
DEVICES = ["ios", "android"]
DEVICE_LABEL = {"ios": "iOS (LiDAR)", "android": "Android (ARCore)"}
DEVICE_SHORT = {"ios": "iOS", "android": "Android"}
SITES = ["McDunn", "Starker"]
# The stands' full names, for anything a reader sees. The short keys stay in the
# data because that is what the capture files carry; only the label changes.
SITE_LABEL = {"McDunn": "McDunn Forest", "Starker": "Starker Forest"}

# Diameter classes a cruiser would recognise. Open-ended at the top because
# the largest stems are few and a fixed top edge would leave an empty class.
DBH_CLASSES = [(0, 8), (8, 12), (12, 18), (18, 24), (24, 32), (32, 999)]
HEIGHT_CLASSES = [(0, 50), (50, 80), (80, 110), (110, 140), (140, 999)]


# --------------------------------------------------------------------------
# The sample
# --------------------------------------------------------------------------

def load(drop_typed: bool = True) -> pd.DataFrame:
    """The paired table, tidied to ONE ROW PER OBSERVATION.

    The stored file is one row per (stem, measurand) with the two devices side
    by side, which is convenient for reading and wrong for analysis: every
    model here treats device as a factor, and a factor cannot live in a column
    name. This melts it, so each row is one device measuring one stem.

    `drop_typed` removes readings the cruiser typed rather than measured —
    but ONLY the ones that are the tape written back into the app. Those
    measure nothing: they enter as near-perfect agreement and flatter every
    statistic they touch.

    A TYPED VALUE IS NOT AUTOMATICALLY A COPIED ONE, and treating it as one
    threw away a real measurement. McD001's iOS height was typed because the
    stem had been captured twice: the cruiser carried the reading from the
    duplicate record into the surviving one and deleted the other. The number
    is the app's own — 43.708 m against a 44.135 m reference — and dropping it
    left the iOS height sample at 99 for no reason anybody could see from the
    table.

    So the test is the VALUE, not the entry method: a typed reading is
    excluded only when it agrees with the reference closely enough that it
    cannot be an independent observation. A transcription of a genuine capture
    stays in.
    """
    raw = pd.read_csv(PAIRS)
    rows = []
    for _, r in raw.iterrows():
        m = MEASURANDS[r["kind"]]
        truth = r.get("truth")
        for dev in DEVICES:
            val = r.get(f"{dev}_value")
            if pd.isna(val) or pd.isna(truth):
                continue
            flags = str(r.get("flags") or "")
            typed = f"{dev}-typed" in flags
            # Within a tenth of a per cent of the reference is not a
            # measurement of it — it IS it, keyed in. Anything further out is
            # an independent number whatever route it took into the store.
            copied = typed and abs(float(val) - float(truth)) <= abs(float(truth)) * 1e-3
            if drop_typed and copied:
                continue
            rows.append(dict(
                stem=r["pair_id"], site=r["plot"], measurand=r["kind"],
                device=dev, device_label=DEVICE_LABEL[dev],
                measured=float(val) / m["conv"],
                reference=float(truth) / m["conv"],
                measured_metric=float(val), reference_metric=float(truth),
                sigma=(float(r[f"{dev}_sigma"]) / m["conv"]
                       if not pd.isna(r.get(f"{dev}_sigma")) else np.nan),
                confidence=r.get(f"{dev}_confidence"),
                capture_mode=r.get(f"{dev}_capture_mode"),
                species=r.get("species"),
                typed=typed,
                tape_disputed="TAPE-MISMATCH" in flags,
                unit=m["unit"],
            ))
    df = pd.DataFrame(rows)
    df["error"] = df["measured"] - df["reference"]
    df["pct_error"] = df["error"] / df["reference"] * 100.0
    df["abs_error"] = df["error"].abs()
    return df


def paired(df: pd.DataFrame, measurand: str) -> pd.DataFrame:
    """Stems measured by BOTH devices, one row per stem.

    Needed for anything that compares the handsets to each other rather than
    to the tape — those questions are paired by construction, and analysing
    them unpaired throws away the pairing that makes the comparison sharp.
    """
    sub = df[df.measurand == measurand]
    wide = sub.pivot_table(index=["stem", "site"], columns="device",
                           values=["measured", "reference"], aggfunc="first")
    wide.columns = [f"{a}_{b}" for a, b in wide.columns]
    wide = wide.dropna(subset=["measured_ios", "measured_android"]).reset_index()
    wide["reference"] = wide["reference_ios"].fillna(wide["reference_android"])
    wide["delta"] = wide["measured_ios"] - wide["measured_android"]
    wide["mean_measured"] = (wide["measured_ios"] + wide["measured_android"]) / 2
    return wide


def size_class(value: float, kind: str) -> str:
    bins = DBH_CLASSES if kind == "dbh" else HEIGHT_CLASSES
    for lo, hi in bins:
        if lo <= value < hi:
            return f"{lo}–{hi}" if hi < 999 else f"{lo}+"
    return "?"


# --------------------------------------------------------------------------
# Statistics
#
# Written out rather than pulled from a library wherever the library's default
# would answer a subtly different question. Each one says what it assumes.
# --------------------------------------------------------------------------

def ccc(x, y):
    """Lin's concordance correlation coefficient.

    Pearson's r asks whether two methods move together; it is 1.0 for a method
    that reads double the truth. CCC multiplies r by a bias-correction factor
    that penalises any departure from the 1:1 line, which is the question a
    validation asks. Reported instead of r, not alongside it, wherever only one
    number fits.
    """
    x, y = np.asarray(x, float), np.asarray(y, float)
    mx, my = x.mean(), y.mean()
    vx, vy = x.var(), y.var()
    cov = ((x - mx) * (y - my)).mean()
    denom = vx + vy + (mx - my) ** 2
    return 2 * cov / denom if denom else np.nan


def deming(x, y, lam=1.0):
    """Deming regression of y on x when BOTH carry error.

    Ordinary least squares assumes the predictor is known exactly. Here the
    predictor is a tape reading — better than the phone, but not exact — and
    OLS under that violation attenuates the slope toward zero, which would
    show up as a spurious proportional under-read. `lam` is the ratio of error
    variances (sigma_y^2 / sigma_x^2); 1.0 assumes they are comparable, which
    is conservative for us because the tape is the better instrument.
    """
    x, y = np.asarray(x, float), np.asarray(y, float)
    n = len(x)
    mx, my = x.mean(), y.mean()
    sxx = ((x - mx) ** 2).sum() / (n - 1)
    syy = ((y - my) ** 2).sum() / (n - 1)
    sxy = ((x - mx) * (y - my)).sum() / (n - 1)
    d = syy - lam * sxx
    slope = (d + math.sqrt(d * d + 4 * lam * sxy * sxy)) / (2 * sxy)
    return slope, my - slope * mx


def ols(x, y):
    x, y = np.asarray(x, float), np.asarray(y, float)
    slope, intercept = np.polyfit(x, y, 1)
    pred = slope * x + intercept
    ss_res = ((y - pred) ** 2).sum()
    ss_tot = ((y - y.mean()) ** 2).sum()
    return slope, intercept, (1 - ss_res / ss_tot if ss_tot else np.nan)


def bland_altman(measured, reference):
    """Bias and 95 % limits of agreement, in the measurand's own units.

    Plotted against the REFERENCE rather than the mean of the two. Plotting
    against the mean is the textbook choice for two interchangeable methods;
    here one of them is the reference, and regressing a difference on a mean
    that contains that difference induces a slope out of nothing.
    """
    d = np.asarray(measured, float) - np.asarray(reference, float)
    bias, sd = d.mean(), d.std(ddof=1)
    return dict(bias=bias, sd=sd, loa_low=bias - 1.96 * sd, loa_high=bias + 1.96 * sd,
                n=len(d))


def bootstrap_ci(values, stat=np.mean, n_boot=10000, alpha=0.05, seed=17):
    """Percentile bootstrap CI. Seeded, so a re-run reproduces the manuscript."""
    rng = np.random.default_rng(seed)
    v = np.asarray(values, float)
    v = v[~np.isnan(v)]
    if len(v) < 3:
        return (np.nan, np.nan)
    draws = rng.integers(0, len(v), size=(n_boot, len(v)))
    stats = np.array([stat(v[d]) for d in draws])
    return tuple(np.percentile(stats, [100 * alpha / 2, 100 * (1 - alpha / 2)]))


def tost(values, bound, alpha=0.05):
    """Two one-sided tests: is the mean inside +/- `bound`?

    A t-test against zero answers "can we rule out perfect agreement", which
    with n=100 is answered yes by any instrument and tells a forester nothing.
    Equivalence asks the useful question — is the discrepancy small enough to
    not matter — and requires the margin to be stated in advance rather than
    read off the result.
    """
    from scipy import stats as sps
    v = np.asarray(values, float)
    v = v[~np.isnan(v)]
    n = len(v)
    se = v.std(ddof=1) / math.sqrt(n)
    t_low = (v.mean() - (-bound)) / se
    t_high = (v.mean() - bound) / se
    p_low = 1 - sps.t.cdf(t_low, n - 1)
    p_high = sps.t.cdf(t_high, n - 1)
    p = max(p_low, p_high)
    crit = sps.t.ppf(1 - alpha, n - 1)
    return dict(mean=v.mean(), n=n, bound=bound, p=p, equivalent=p < alpha,
                ci_low=v.mean() - crit * se, ci_high=v.mean() + crit * se)


def rmse(err):
    e = np.asarray(err, float)
    e = e[~np.isnan(e)]
    return math.sqrt((e ** 2).mean()) if len(e) else np.nan


def summarize(sub: pd.DataFrame) -> dict:
    """The accuracy block reported for every cell of every table."""
    if len(sub) == 0:
        return {}
    ba = bland_altman(sub.measured, sub.reference)
    s_ols, i_ols, r2 = ols(sub.reference, sub.measured)
    s_dem, i_dem = deming(sub.reference, sub.measured)
    lo, hi = bootstrap_ci(sub.error)
    return dict(
        n=len(sub),
        bias=ba["bias"], bias_ci_low=lo, bias_ci_high=hi,
        pct_bias=sub.pct_error.mean(),
        sd=ba["sd"], loa_low=ba["loa_low"], loa_high=ba["loa_high"],
        rmse=rmse(sub.error), mae=sub.abs_error.mean(),
        # MAPE, in per cent. Reported on the 1:1 panels in place of MAE
        # because a mean absolute error in inches is read against a sample
        # that runs 6 to 50 inches: 1.8 in is most of a small stem and
        # nothing on a large one, so one number cannot be judged without
        # also knowing which trees it came from. The same error as a
        # percentage of each stem's own reference can.
        mape=sub.pct_error.abs().mean(),
        r2=r2, ccc=ccc(sub.reference, sub.measured),
        ols_slope=s_ols, ols_intercept=i_ols,
        deming_slope=s_dem, deming_intercept=i_dem,
    )


# --------------------------------------------------------------------------
# House style
# --------------------------------------------------------------------------

PALETTE = {
    "ios": "#1F4E79",         # deep blue
    "android": "#C0392B",     # crimson, the deck's accent
    "reference": "#2C3E50",
    "grid": "#D8DEE4",
    "muted": "#7F8C8D",
    "accent": "#C0392B",
    "good": "#27AE60",
    "warn": "#E67E22",
    # SITE COLOURS MUST NOT BE THE DEVICE COLOURS. They were, and it made the
    # calibration panels ambiguous: an iOS panel drawn in blue, containing
    # crimson Starker triangles, invites the reading "crimson = Android". Teal
    # and amber are distinct from both handset colours and from each other,
    # separate in greyscale, and safe for red-green colour blindness. Marker
    # shape still carries the site as well, so colour is never the only cue.
    "site": {"McDunn": "#0F7B6C", "Starker": "#D6800F"},
}

# LANDSCAPE BY DEFAULT, because these figures are read off a 16:9 slide before
# they are read in a column. A wide figure drops into a slide at full width; a
# square one leaves half the slide empty and gets shrunk to fit the height.
FIG_W, FIG_H = 12.0, 5.0

# One square panel, for anything carrying a 1:1 line. Lay these out as a WIDE
# ROW of square panels rather than a grid — that keeps each panel square, which
# a 1:1 line requires to be readable, while the figure stays slide-shaped.
PANEL = 3.6


def use_style():
    """Journal-figure defaults: no chartjunk, hairline axes, real units."""
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    # SERIF, and specifically Times New Roman: it is what the target journals
    # set their body text in, and a figure in a different face reads as
    # imported from somewhere else.
    family = "Times New Roman"
    try:
        import matplotlib.font_manager as fm
        names = {f.name for f in fm.fontManager.ttflist}
        for candidate in ("Times New Roman", "Times", "Nimbus Roman",
                          "Liberation Serif", "DejaVu Serif"):
            if candidate in names:
                family = candidate
                break
    except Exception:
        pass
    plt.rcParams.update({
        "font.family": family, "font.serif": [family], "mathtext.fontset": "stix",
        "font.size": 9,
        "axes.titlesize": 10, "axes.titleweight": "bold", "axes.labelsize": 9,
        "axes.edgecolor": "#4A4A4A", "axes.linewidth": 0.8,
        "axes.spines.top": False, "axes.spines.right": False,
        # Item 3: bars are outlined in black everywhere. Set in the style so a
        # new bar chart cannot be added without it.
        "patch.edgecolor": "black", "patch.force_edgecolor": False,
        "hatch.linewidth": 0.7,
        "axes.grid": True, "grid.color": PALETTE["grid"],
        "grid.linewidth": 0.6, "grid.alpha": 0.9,
        "xtick.labelsize": 8, "ytick.labelsize": 8,
        "xtick.direction": "out", "ytick.direction": "out",
        "legend.frameon": False, "legend.fontsize": 8,
        "figure.dpi": 120, "savefig.dpi": 300,
        "savefig.bbox": "tight", "savefig.pad_inches": 0.05,
        "figure.facecolor": "white", "axes.facecolor": "white",
    })
    return plt


def save(fig, name: str, caption: str = ""):
    """Write PNG for the deck, PDF for the manuscript, and the caption beside them."""
    for ext in ("png", "pdf"):
        fig.savefig(os.path.join(FIGDIR, f"{name}.{ext}"))
    if caption:
        with open(os.path.join(FIGDIR, f"{name}.txt"), "w") as fh:
            fh.write(caption.strip() + "\n")
    import matplotlib.pyplot as plt
    plt.close(fig)
    return os.path.join(FIGDIR, f"{name}.png")


def save_table(df: pd.DataFrame, name: str, caption: str = ""):
    df.to_csv(os.path.join(RESDIR, f"{name}.csv"), index=False)
    if caption:
        with open(os.path.join(RESDIR, f"{name}.txt"), "w") as fh:
            fh.write(caption.strip() + "\n")
    return os.path.join(RESDIR, f"{name}.csv")


def statbox(ax, pairs, loc="lower right", fontsize=9.5, gap=14.0):
    """The statistics block, as a table with its rules taken out.

    Labels ragged-left, values ragged-RIGHT, sharing one frame: a stat block
    whose numbers do not line up is read digit by digit instead of at a glance,
    and lining up the decimal points is the whole reason the block is there.
    Two text artists on a measured grid do what a tab stop would — matplotlib
    has no tab stops, and padding a string with spaces only aligns under a
    monospace font, which the house style is not.

    The columns are MEASURED, not estimated from character counts: `$R^2$`
    renders four glyphs wide and reads as five characters, and one such guess
    puts the two columns on top of each other.
    """
    from matplotlib.patches import FancyBboxPatch

    labels = "\n".join(k for k, _ in pairs)
    values = "\n".join(v for _, v in pairs)
    right = "right" in loc
    x0 = 0.97 if right else 0.03
    y0, va = (0.05, "bottom") if "lower" in loc else (0.95, "top")
    kw = dict(transform=ax.transAxes, va=va, fontsize=fontsize, zorder=7,
              linespacing=1.5)
    t_lab = ax.text(0, 0, labels, ha="left", **kw)
    t_val = ax.text(0, 0, values, ha="right", **kw)

    fig = ax.figure
    fig.canvas.draw()
    inv = ax.transAxes.inverted()
    def width_of(t):
        bb = t.get_window_extent(fig.canvas.get_renderer())
        return abs(inv.transform((bb.x1, 0))[0] - inv.transform((bb.x0, 0))[0])
    w_lab, w_val = width_of(t_lab), width_of(t_val)
    g = gap / (ax.get_window_extent().width or 1) * 1.0
    g = abs(inv.transform((gap, 0))[0] - inv.transform((0, 0))[0])
    total = w_lab + g + w_val

    left = x0 - total if right else x0
    t_lab.set_position((left, y0))
    t_val.set_position((left + total, y0))

    pad = abs(inv.transform((6, 0))[0] - inv.transform((0, 0))[0])
    bb = t_val.get_window_extent(fig.canvas.get_renderer())
    h = abs(inv.transform((0, bb.y1))[1] - inv.transform((0, bb.y0))[1])
    y_bot = y0 if va == "bottom" else y0 - h
    ax.add_patch(FancyBboxPatch(
        (left - pad, y_bot - pad), total + 2 * pad, h + 2 * pad,
        boxstyle="round,pad=0.012", transform=ax.transAxes, zorder=6,
        facecolor="white", edgecolor=PALETTE["grid"], linewidth=0.8,
        alpha=0.94, clip_on=False))
    return t_lab, t_val


def grouped_legend(ax, loc="upper left", fontsize=8.5, **kw):
    """Legend with the markers together and the lines together.

    Mixing a fitted line in among the site markers makes the reader sort the
    entries themselves; keeping like with like means the key reads as two short
    lists rather than one shuffled one.
    """
    handles, labels = ax.get_legend_handles_labels()
    def is_line(h):
        ls = getattr(h, "get_linestyle", lambda: "None")()
        return ls not in ("None", "none", "")
    order = ([i for i, h in enumerate(handles) if not is_line(h)]
             + [i for i, h in enumerate(handles) if is_line(h)])
    return ax.legend([handles[i] for i in order], [labels[i] for i in order],
                     loc=loc, fontsize=fontsize, **kw)


def square_identity_axes(ax, *series, pad=0.04):
    """Make a panel that carries a 1:1 line SQUARE, on matched limits.

    A 1:1 line is only readable when one unit on x is one unit on y. Left to
    itself matplotlib fits the axes to the box, so the identity line comes out
    at whatever angle the aspect ratio dictates and points that sit ON it look
    like they sit above or below it. Both scales are therefore forced to one
    range and the box to a square.

    Pass every series that must be inside the frame — measurements AND
    reference — so the limits cover the data rather than the last thing drawn.
    """
    vals = np.concatenate([np.asarray(s, float).ravel() for s in series if len(s)])
    vals = vals[np.isfinite(vals)]
    if not len(vals):
        return
    lo, hi = float(vals.min()), float(vals.max())
    span = hi - lo or 1.0
    lo, hi = lo - pad * span, hi + pad * span
    ax.set_xlim(lo, hi)
    ax.set_ylim(lo, hi)
    ax.set_aspect("equal", adjustable="box")


def identity_line(ax, **kw):
    """The 1:1 line, drawn across whatever limits the panel ends up with."""
    style = dict(color=PALETTE["muted"], linestyle="--", linewidth=1.0,
                 zorder=1, label="1:1 line")
    style.update(kw)
    lo = min(ax.get_xlim()[0], ax.get_ylim()[0])
    hi = max(ax.get_xlim()[1], ax.get_ylim()[1])
    return ax.plot([lo, hi], [lo, hi], **style)


def panel_tag(ax, letter):
    ax.text(-0.13, 1.06, letter, transform=ax.transAxes,
            fontsize=11, fontweight="bold", va="top", ha="left")


if __name__ == "__main__":
    d = load()
    print(f"observations: {len(d)}")
    print(d.groupby(["measurand", "device"]).size())
    print("\nstems:", d.stem.nunique(), " sites:", sorted(d.site.unique()))
    for k in MEASURANDS:
        for dev in DEVICES:
            sub = d[(d.measurand == k) & (d.device == dev)]
            s = summarize(sub)
            print(f"  {k:7s} {dev:8s} n={s['n']:3d}  bias {s['bias']:+.2f} "
                  f"({s['pct_bias']:+.1f}%)  RMSE {s['rmse']:.2f}  "
                  f"R2 {s['r2']:.3f}  CCC {s['ccc']:.3f}  "
                  f"Deming slope {s['deming_slope']:.3f}")
