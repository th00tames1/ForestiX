#!/usr/bin/env python3
"""What the measurement error COSTS a cruiser.

Inches of bias are not a decision. A cruiser decides on the quantities a cruise
reports: which 2-inch class a stem falls in, how much basal area the plot
carries, and how much wood that implies. This script translates the per-stem
error into those three currencies.

THE POINT OF THE ARITHMETIC. Basal area is quadratic in diameter, so a
diameter bias does not pass through at face value — it is roughly doubled, and
random diameter error adds on top of it because squaring is convex and E[D^2]
exceeds (E[D])^2. The exact identity used here, for measured D_hat = D + e:

    sum(D_hat^2) - sum(D^2) = 2*sum(D*e) + sum(e^2)

so the plot-level basal-area error splits cleanly into a size-weighted bias
term (which can be either sign) and a noise term (which is ALWAYS positive).
That second term is why a phone with no average bias would still over-report
plot basal area, and it is reported separately below rather than buried.

THE VOLUME NUMBER IS A PROXY. BA * H is a cylinder. Real volume is roughly
0.4-0.5 of that. The proxy is used because it is form-factor-free: it carries
the error propagation of a real volume equation (quadratic in D, linear in H)
without importing a species- and region-specific equation whose own error we
have not validated. Percentages from it transfer; the cubic feet do not.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

import core

plt = core.use_style()
df = core.load()

BA_K = 0.005454          # basal area, sq ft, from diameter in inches
SEED = 17
N_BOOT = 10000


# --------------------------------------------------------------------------
# 1. Diameter classes
# --------------------------------------------------------------------------

def dclass(d, width, convention="floor"):
    """Assign a diameter to a class, in inches.

    Two conventions are in use in the field and they do not agree, so both are
    reported. `floor` bins on the lower edge: the 12-in class is [12, 14).
    `round` is the common US "2-inch class": the 12-in class is centred on 12
    and runs [11, 13). Agreement rates depend on where the edges sit relative
    to the stems, which is a property of the tally rule and not of the phone,
    so quoting one convention alone would overstate the precision of the claim.
    """
    d = np.asarray(d, float)
    if convention == "floor":
        return np.floor(d / width) * width
    return np.round(d / width) * width


def wilson(k, n, z=1.96):
    """Wilson 95 % interval for a proportion, in percent.

    A hit rate out of 100 stems carries about +/-10 points of binomial
    uncertainty, so quoting "46 % agree" bare would imply a precision the
    sample does not have. Wilson rather than the normal approximation because
    the latter misbehaves as the rate approaches 0 or 1, which the 4-inch
    within-one-class rates do.
    """
    if n == 0:
        return (np.nan, np.nan)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    h = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (100 * max(0.0, c - h), 100 * min(1.0, c + h))


def agreement_block(sub, width, convention):
    """Exact / within-one-class agreement and the direction of the misses."""
    tape = dclass(sub.reference, width, convention)
    phone = dclass(sub.measured, width, convention)
    shift = (phone - tape) / width          # in whole classes
    n = len(sub)
    ex_lo, ex_hi = wilson(int((shift == 0).sum()), n)
    w1_lo, w1_hi = wilson(int((np.abs(shift) <= 1).sum()), n)
    return dict(
        n=n,
        exact_pct=100.0 * np.mean(shift == 0),
        exact_ci_low=ex_lo, exact_ci_high=ex_hi,
        within1_pct=100.0 * np.mean(np.abs(shift) <= 1),
        within1_ci_low=w1_lo, within1_ci_high=w1_hi,
        over_pct=100.0 * np.mean(shift > 0),
        under_pct=100.0 * np.mean(shift < 0),
        mean_shift=float(np.mean(shift)),
        max_shift=float(np.max(np.abs(shift))),
    )


# --------------------------------------------------------------------------
# 2 & 3. Plot-level totals
# --------------------------------------------------------------------------

def stem_table(measurand_frames, device, use_height):
    """One row per stem: tape and phone diameter, height, BA and proxy.

    Stems are kept only where the phone produced every input the quantity
    needs. That matters for the proxy: iOS has no height on one McDunn stem,
    so both the iOS proxy total AND the tape total it is compared against are
    computed over the other 49. Comparing a 49-stem phone total to a 50-stem
    tape total would report a missing tree as a measurement error.
    """
    d = measurand_frames["dbh"]
    d = d[d.device == device][["stem", "site", "measured", "reference",
                               "tape_disputed"]]
    d = d.rename(columns={"measured": "d_phone", "reference": "d_tape"})
    if not use_height:
        out = d.copy()
    else:
        h = measurand_frames["height"]
        h = h[h.device == device][["stem", "measured", "reference"]]
        h = h.rename(columns={"measured": "h_phone", "reference": "h_tape"})
        out = d.merge(h, on="stem", how="inner")
    out["ba_tape"] = BA_K * out.d_tape ** 2
    out["ba_phone"] = BA_K * out.d_phone ** 2
    if use_height:
        out["prx_tape"] = out.ba_tape * out.h_tape
        out["prx_phone"] = out.ba_phone * out.h_phone
    return out


def total_error(tape_vals, phone_vals, seed=SEED, n_boot=N_BOOT):
    """Plot total for each method, the percent error, and a stem bootstrap CI.

    The percent error of a SUM has no textbook standard error, and the naive
    move — averaging the per-stem percent errors — answers a different
    question (it weights a 6-inch stem the same as a 50-inch one, while the
    total does not). Resampling stems with replacement and recomputing the
    ratio of sums propagates exactly the quantity plotted. The CI describes
    sampling variability of stems within this stand; it is not a claim about
    other stands.
    """
    t = np.asarray(tape_vals, float)
    p = np.asarray(phone_vals, float)
    pct = 100.0 * (p.sum() - t.sum()) / t.sum()
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(t), size=(n_boot, len(t)))
    boots = 100.0 * (p[idx].sum(axis=1) - t[idx].sum(axis=1)) / t[idx].sum(axis=1)
    lo, hi = np.percentile(boots, [2.5, 97.5])
    return dict(n=len(t), tape_total=t.sum(), phone_total=p.sum(),
                error=p.sum() - t.sum(), pct_error=pct,
                ci_low=lo, ci_high=hi)


def ba_decomposition(d_tape, d_phone):
    """Split the basal-area total error into size-weighted bias and noise.

    sum(D_hat^2) - sum(D^2) = 2*sum(D*e) + sum(e^2), both terms expressed as a
    percent of the tape total. The noise term cannot be negative: random
    diameter error alone inflates plot basal area.
    """
    d_t = np.asarray(d_tape, float)
    e = np.asarray(d_phone, float) - d_t
    denom = (d_t ** 2).sum()
    return (100.0 * 2 * (d_t * e).sum() / denom,
            100.0 * (e ** 2).sum() / denom)


# --------------------------------------------------------------------------
# Run it
# --------------------------------------------------------------------------

def frames(source):
    return {k: source[source.measurand == k] for k in ("dbh", "height")}


def run_all(source, tag):
    """Every number in the paper, for one definition of the sample."""
    fr = frames(source)
    rows = []

    # -- class agreement -------------------------------------------------
    for device in core.DEVICES:
        sub = fr["dbh"][fr["dbh"].device == device]
        for width in (1, 2, 4):
            for convention in ("floor", "round"):
                a = agreement_block(sub, width, convention)
                rows.append(dict(sample=tag, block="class_agreement",
                                 device=core.DEVICE_SHORT[device],
                                 metric=f"{width}-in class",
                                 class_width_in=width, convention=convention,
                                 site="both", **a))

    # -- plot totals -----------------------------------------------------
    for device in core.DEVICES:
        for metric, use_h, col_t, col_p, unit in (
                ("basal area", False, "ba_tape", "ba_phone", "sq ft"),
                ("BA x H proxy", True, "prx_tape", "prx_phone", "cu ft")):
            st = stem_table(fr, device, use_h)
            for site in core.SITES + ["both"]:
                s = st if site == "both" else st[st.site == site]
                r = total_error(s[col_t], s[col_p])
                extra = {}
                if not use_h:
                    bias_t, noise_t = ba_decomposition(s.d_tape, s.d_phone)
                    extra = dict(bias_term_pct=bias_t, noise_term_pct=noise_t)
                rows.append(dict(sample=tag, block="plot_total",
                                 device=core.DEVICE_SHORT[device],
                                 metric=metric, unit=unit, site=site,
                                 **r, **extra))
    return pd.DataFrame(rows)


full = run_all(df, "all stems")
clean = run_all(df[~df.tape_disputed], "tape-disputed excluded")
table = pd.concat([full, clean], ignore_index=True)

order = ["sample", "block", "metric", "site", "device", "unit",
         "class_width_in", "convention", "n",
         "exact_pct", "exact_ci_low", "exact_ci_high",
         "within1_pct", "within1_ci_low", "within1_ci_high",
         "over_pct", "under_pct", "mean_shift",
         "max_shift", "tape_total", "phone_total", "error", "pct_error",
         "ci_low", "ci_high", "bias_term_pct", "noise_term_pct"]
table = table[[c for c in order if c in table.columns]]
for c in table.columns:
    if table[c].dtype.kind == "f":
        table[c] = table[c].round(3)

core.save_table(
    table, "t12_impact",
    "Operational impact of ForestiX measurement error. Upper block: agreement "
    "between the diameter class assigned from the tape and from each phone, "
    "for 1-, 2- and 4-inch classes under two tally conventions (floor: the "
    "12-in class is [12,14); round: the 12-in class is centred on 12). "
    "'exact_pct' is the share of stems landing in the same class as the tape "
    "(Wilson 95 % interval alongside), 'over_pct'/'under_pct' the share the "
    "phone places in a higher/lower "
    "class. Lower block: plot-level totals of basal area (0.005454*D^2, sq ft) "
    "and of the form-factor-free BA x H proxy (cu ft), summed over the stems "
    "of each stand, with the phone-minus-tape error in percent and a "
    "10 000-draw stem bootstrap 95 % CI. 'bias_term_pct' and 'noise_term_pct' "
    "split the basal-area error into 2*sum(D*e)/sum(D^2) and sum(e^2)/sum(D^2); "
    "the second is positive by construction. Proxy rows for iOS exclude one "
    "McDunn stem with no iOS height, and the tape total they are compared with "
    "excludes the same stem. Rows are repeated with the 8 tape-disputed stems "
    "removed. Typed readings are already excluded by the loader.")


def get(t, **kw):
    m = pd.Series(True, index=t.index)
    for k, v in kw.items():
        m &= t[k] == v
    return t[m]


# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
fig, axes = plt.subplots(1, 2, figsize=(core.FIG_W * 1.24, core.FIG_H * 0.98),
                         gridspec_kw=dict(width_ratios=[1.0, 1.15], wspace=0.30))

# -- Panel A: 2-inch class confusion, both devices on one diagonal ---------
axA = axes[0]
W = 2
dbh = df[df.measurand == "dbh"]
style = {"ios": dict(marker="o", face=core.PALETTE["ios"], off=-0.32),
         "android": dict(marker="s", face=core.PALETTE["android"], off=+0.32)}
lo = np.floor(min(dbh.reference.min(), dbh.measured.min()) / W) * W
hi = np.ceil(max(dbh.reference.max(), dbh.measured.max()) / W) * W

for device in core.DEVICES:
    sub = dbh[dbh.device == device]
    tape = dclass(sub.reference, W)
    phone = dclass(sub.measured, W)
    cnt = pd.Series(list(zip(tape, phone))).value_counts()
    s = style[device]
    xs = np.array([k[0] for k in cnt.index], float) + s["off"]
    ys = np.array([k[1] for k in cnt.index], float)
    on = ys == np.array([k[0] for k in cnt.index], float)
    axA.scatter(xs, ys, s=16 * cnt.values, marker=s["marker"],
                facecolor=np.where(on, s["face"], "none"),
                edgecolor=s["face"], linewidth=0.9, alpha=0.85, zorder=3,
                label=core.DEVICE_LABEL[device])

axA.plot([lo, hi], [lo, hi], color=core.PALETTE["reference"], lw=0.9,
         ls="--", zorder=1)
for off, ls in ((W, ":"), (-W, ":")):
    axA.plot([lo, hi], [lo + off, hi + off], color=core.PALETTE["muted"],
             lw=0.7, ls=ls, zorder=1)
axA.set_xlim(lo - 1, hi + 1)
axA.set_ylim(lo - 1, hi + 1)
axA.set_xlabel("Tape diameter class (in, 2-in classes)")
axA.set_ylabel("Phone diameter class (in)")
axA.set_xticks(np.arange(lo, hi + 1, 8))
axA.set_yticks(np.arange(lo, hi + 1, 8))
axA.set_aspect("equal", adjustable="box")

agree_txt = []
for device in core.DEVICES:
    r = get(table, sample="all stems", block="class_agreement",
            device=core.DEVICE_SHORT[device], class_width_in=2,
            convention="floor").iloc[0]
    agree_txt.append(f"{core.DEVICE_SHORT[device]}: {r.exact_pct:.0f}% exact, "
                     f"{r.within1_pct:.0f}% ±1 class, {r.over_pct:.0f}% high")
axA.text(0.02, 0.99, "dotted: one class out\nsymbol area: no. of stems",
         transform=axA.transAxes, va="top", ha="left", fontsize=7,
         color=core.PALETTE["muted"], linespacing=1.4)
axA.text(0.02, 0.845, "\n".join(agree_txt), transform=axA.transAxes,
         va="top", ha="left", fontsize=7.2, linespacing=1.5,
         bbox=dict(boxstyle="round,pad=0.35", fc="white",
                   ec=core.PALETTE["grid"], lw=0.6))
axA.legend(loc="lower right", bbox_to_anchor=(1.0, 0.02), fontsize=7.4,
           handletextpad=0.2, borderpad=0.2)
core.panel_tag(axA, "A")

# -- Panel B: plot-level totals as a percent of the tape total -------------
axB = axes[1]
groups = [("basal area", "McDunn"), ("basal area", "Starker"),
          ("BA x H proxy", "McDunn"), ("BA x H proxy", "Starker")]
bar_w = 0.26
xs = np.arange(len(groups), dtype=float)

axB.bar(xs - bar_w, [100] * len(groups), bar_w, color="white",
        edgecolor=core.PALETTE["reference"], hatch="///", linewidth=0.9,
        label="Tape / laser (reference)", zorder=2)
for i, device in enumerate(core.DEVICES):
    vals, errs, labels = [], [[], []], []
    for metric, site in groups:
        r = get(table, sample="all stems", block="plot_total", metric=metric,
                site=site, device=core.DEVICE_SHORT[device]).iloc[0]
        vals.append(100.0 + r.pct_error)
        errs[0].append(r.pct_error - r.ci_low)
        errs[1].append(r.ci_high - r.pct_error)
        labels.append(f"{r.pct_error:+.1f}%")
    pos = xs + (i * bar_w)
    axB.bar(pos, vals, bar_w, color=style[device]["face"],
            edgecolor=("#6E1C14" if device == "android" else
                       style[device]["face"]),
            hatch=("" if device == "ios" else "\\\\\\"), linewidth=0.0,
            label=core.DEVICE_LABEL[device], zorder=2)
    axB.errorbar(pos, vals, yerr=np.array(errs), fmt="none", ecolor="#3A3A3A",
                 elinewidth=0.8, capsize=2.0, zorder=4)
    for j, (x, v, t) in enumerate(zip(pos, vals, labels)):
        axB.text(x, v + errs[1][j] + 1.5, t, ha="center", va="bottom",
                 fontsize=7.0, rotation=90, color=style[device]["face"],
                 fontweight="bold")

axB.axhline(100, color=core.PALETTE["reference"], lw=0.9, ls="--", zorder=3)
axB.set_ylim(0, 150)
axB.set_yticks([0, 25, 50, 75, 100, 125])
axB.set_ylabel("Stand total, % of tape / laser total")
axB.text(xs[0] - bar_w, 50, "tape / laser", rotation=90, ha="center",
         va="center", fontsize=7.0, color=core.PALETTE["reference"],
         fontweight="bold", zorder=5,
         bbox=dict(boxstyle="square,pad=0.12", fc="white", ec="none"))
for i, device in enumerate(core.DEVICES):
    axB.text(xs[0] + i * bar_w, 50, core.DEVICE_SHORT[device], rotation=90,
             ha="center", va="center", fontsize=7.0, color="white",
             fontweight="bold", zorder=5,
             bbox=dict(boxstyle="square,pad=0.12",
                       fc=style[device]["face"], ec="none"))

tick_lab = []
for metric, site in groups:
    # The reference total printed is the one over every stem in the stand.
    # For the iOS proxy the comparison behind the bar uses the 49-stem tape
    # total instead, because iOS has no height on one McDunn stem.
    rr = get(table, sample="all stems", block="plot_total", metric=metric,
             site=site).sort_values("n")
    r = rr.iloc[-1]
    unit = "ft²" if metric == "basal area" else "ft³"
    tick_lab.append(f"{site}\n{r.tape_total:,.0f} {unit}")
axB.set_xticks(xs)
axB.set_xticklabels(tick_lab, fontsize=7.4)
axB.axvline(1.5, color=core.PALETTE["grid"], lw=1.0, zorder=1)
axB.text(0.5, 147, "Basal area (Σ 0.005454·D²)", ha="center", va="top",
         fontsize=7.8, fontweight="bold", color=core.PALETTE["reference"])
axB.text(2.5, 147, "Volume proxy (Σ BA·H)", ha="center", va="top",
         fontsize=7.8, fontweight="bold", color=core.PALETTE["reference"])
axB.set_xlim(-0.62, len(groups) - 0.12)
core.panel_tag(axB, "B")

both_ba = {d: get(table, sample="all stems", block="plot_total",
                  metric="basal area", site="both",
                  device=core.DEVICE_SHORT[d]).iloc[0] for d in core.DEVICES}
rnd = {d: get(table, sample="all stems", block="class_agreement",
              device=core.DEVICE_SHORT[d], class_width_in=2,
              convention="round").iloc[0] for d in core.DEVICES}
ba_clean = {d: get(table, sample="tape-disputed excluded", block="plot_total",
                   metric="basal area", site="both",
                   device=core.DEVICE_SHORT[d]).iloc[0] for d in core.DEVICES}

core.save(
    fig, "fig12_impact",
    "Operational consequences of ForestiX error for a cruise. "
    "(A) Diameter class assigned from the tape versus from each phone, using "
    "2-inch classes binned on the lower edge; symbol area is proportional to "
    "the number of stems, filled symbols sit on the 1:1 diagonal (same class "
    "as the tape), dotted lines mark a one-class miss. Symbols are offset "
    "horizontally by device so overlapping cells stay visible. "
    "(B) Stand totals of basal area and of the BA x H volume proxy, expressed "
    "as a percentage of the tape/laser total (dashed line, hatched bar); the "
    "absolute reference total is printed under each stand. Whiskers are "
    "10 000-draw stem bootstrap 95 % CIs; annotations give the signed "
    "percentage error of the phone total. The BA x H proxy is a cylinder, not "
    "a volume equation — its percentages transfer to a real equation, its "
    "cubic feet do not. iOS proxy totals and the tape totals they are "
    "compared with exclude one McDunn stem lacking an iOS height (n = 49). "
    f"Basal-area totals over both stands are {both_ba['ios'].pct_error:+.1f} % "
    f"(iOS) and {both_ba['android'].pct_error:+.1f} % (Android); removing the "
    "eight stems with a disputed tape value moves them to "
    f"{ba_clean['ios'].pct_error:+.1f} % and "
    f"{ba_clean['android'].pct_error:+.1f} %. Agreement rates in (A) depend on "
    "where the class edges fall relative to the stems: under the alternative "
    f"midpoint-centred convention they are {rnd['ios'].exact_pct:.0f} % (iOS) "
    f"and {rnd['android'].exact_pct:.0f} % (Android) exact. Wilson 95 % "
    "intervals on all agreement rates are about ±10 points at n = 100 and are "
    "given in the accompanying table.")


# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 200)
print("\n=== DIAMETER CLASS AGREEMENT (all stems) ===")
ca = get(table, sample="all stems", block="class_agreement")
print(ca[["device", "metric", "convention", "n", "exact_pct", "exact_ci_low",
          "exact_ci_high", "within1_pct", "over_pct", "under_pct",
          "max_shift"]].to_string(index=False))

print("\n=== PLOT-LEVEL TOTALS (all stems) ===")
pt = get(table, sample="all stems", block="plot_total")
print(pt[["metric", "site", "device", "n", "tape_total", "phone_total",
          "pct_error", "ci_low", "ci_high", "bias_term_pct",
          "noise_term_pct"]].to_string(index=False))

print("\n=== SENSITIVITY: 8 tape-disputed stems removed ===")
pt2 = get(table, sample="tape-disputed excluded", block="plot_total")
print(pt2[["metric", "site", "device", "n", "pct_error", "ci_low",
           "ci_high"]].to_string(index=False))
ca2 = get(table, sample="tape-disputed excluded", block="class_agreement",
          class_width_in=2, convention="floor")
print(ca2[["device", "n", "exact_pct", "within1_pct"]].to_string(index=False))

print("\n=== SINGLE-STEM INFLUENCE ON THE STAND BA TOTAL (jackknife) ===")
for device in core.DEVICES:
    st = stem_table(frames(df), device, use_height=False)
    for site in core.SITES:
        s = st[st.site == site]
        full_pct = 100.0 * (s.ba_phone.sum() - s.ba_tape.sum()) / s.ba_tape.sum()
        drops = []
        for i in range(len(s)):
            k = s.drop(s.index[i])
            drops.append(100.0 * (k.ba_phone.sum() - k.ba_tape.sum())
                         / k.ba_tape.sum())
        drops = np.array(drops)
        j = int(np.argmax(np.abs(drops - full_pct)))
        print(f"{core.DEVICE_SHORT[device]:8s} {site:8s} full {full_pct:+.2f}%  "
              f"most influential stem {s.iloc[j].stem} "
              f"(tape {s.iloc[j].d_tape:.1f} in, phone {s.iloc[j].d_phone:.1f} in) "
              f"-> without it {drops[j]:+.2f}%")

print("\n=== AMPLIFICATION CHECK ===")
for device in core.DEVICES:
    sub = dbh[dbh.device == device]
    mean_pct_d = sub.pct_error.mean()
    ba_row = both_ba[device]
    print(f"{core.DEVICE_SHORT[device]:8s} mean per-stem diameter error "
          f"{mean_pct_d:+.2f}%  ->  stand BA total {ba_row.pct_error:+.2f}% "
          f"(bias term {ba_row.bias_term_pct:+.2f}, "
          f"noise term {ba_row.noise_term_pct:+.2f})")
    hh = df[(df.measurand == "height") & (df.device == device)]
    print(f"{'':8s} mean per-stem height error {hh.pct_error.mean():+.2f}%  ->  "
          "proxy totals: " + ", ".join(
              f"{s} {get(table, sample='all stems', block='plot_total', metric='BA x H proxy', site=s, device=core.DEVICE_SHORT[device]).iloc[0].pct_error:+.2f}%"
              for s in core.SITES))
