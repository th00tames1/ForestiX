#!/usr/bin/env python3
"""Splitting each handset's error into the part the two SHARE and the part they do not.

WHY THIS EXISTS. Two objections to this study rest on the same unmeasured
quantity. The first says total height cannot be called accuracy, because the
reference is a laser rangefinder in 3-point mode, which inverts the same tangent
geometry the app does — so a wrong tangent assumption moves reference and app
together and never shows up as disagreement. The second says the diameter
scatter is not the instrument at all, but stem shape: a tape returns
circumference / pi, the mean caliper diameter over all azimuths, while the app
reads one silhouette width from one azimuth, so an out-of-round stem contributes
error to the app and none to the tape.

Both objections are about the SAME thing — error that does not belong to the
handset — and the design already measures it. Every stem was measured by an
iPhone and by an Android phone against ONE reference reading. Two different
handsets, two different depth technologies, two different capture sessions: an
error that is a property of the phone cannot be common to both. An error that is
a property of the stem, of the reference, or of a method assumption both
inherit, must be.

THE ALGEBRA. For stem i and handset d, write the error against the reference as

    e[i,d] = measured[i,d] - reference[i] = b[d] + s[i] + u[i,d]

    b[d]    the handset's mean bias (a constant, removed by centring below)
    s[i]    the stem-level component COMMON to both handsets: the stem's
            departure from what the reference instrument reports, the reference
            reading's own error, and any method assumption both handsets inherit
    u[i,d]  the handset-specific component: the instrument

Assume the instrument terms are uncorrelated with each other and with s — which
is what "different phone, different depth sensor, different session" buys. Then

    Cov(e_ios, e_android) = Var(s)                      = sigma_shared^2
    Var(e_ios)            = sigma_shared^2 + Var(u_ios)
    Var(e_android)        = sigma_shared^2 + Var(u_android)

so the covariance IS the shared variance, and each handset's independent
variance is what is left:

    sigma_shared    = sqrt(max(Cov(e_ios, e_android), 0))
    sigma_indep[d]  = sqrt(max(Var(e_d) - sigma_shared^2, 0))
    shared fraction f[d] = sigma_shared^2 / Var(e_d) = r * sd[d'] / sd[d]

The two shared fractions bracket the correlation r (their geometric mean is
exactly r), which is why one r yields a RANGE of shared fractions.

WHAT IT SETTLES. Stem shape and reference error live entirely inside s. They are
therefore bounded above by sigma_shared: on diameter that ceiling is about one
inch of sd against per-handset totals of 2.3 and 1.9 in, so they cannot be the
whole story and the remainder is the instrument. On height the shared component
is the large one, which is exactly the signature a common method assumption
leaves and independent instrument error cannot fake.

WHAT IT DOES NOT SETTLE. Two things.

Centring removes b[d], so a shared OFFSET — a reference that reads
systematically small on every stem — contributes nothing to the covariance and
is invisible here. This decomposition is about scatter, not bias. Bias is
reported elsewhere; both handsets over-read diameter on these two stands by
about an inch, and no correlation can tell you whether the tape or the phones
own that inch.

And the two handsets were carried by two people, who may have stood at different
azimuths on the same stem. Whatever part of an out-of-round stem's effect changes
with viewing angle is then NOT common to the two handsets, and lands in u rather
than in s. So sigma_shared measures the part of stem shape that acted on both
handsets alike, plus reference error, plus the common method assumption. The
shared fraction is a LOWER bound on the stem-and-reference contribution and the
instrument share an UPPER bound. The diameter conclusion survives it — the
objection was that stem shape explains ALL the scatter, and a mechanism that
lands partly in the independent term is a mechanism that a single-azimuth
instrument genuinely suffers from — but the ceiling below is a ceiling on the
identically-acting part, and is written that way.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from matplotlib.patches import Ellipse
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()

N_BOOT, SEED = 10000, 17


def para(text: str) -> str:
    """Collapse an f-string's source line breaks so the caption is one paragraph."""
    return " ".join(text.split())


# --------------------------------------------------------------------------
# The paired error table: one row per stem, both handsets' errors side by side
# --------------------------------------------------------------------------

def errors(measurand: str) -> pd.DataFrame:
    """Stems where BOTH handsets have an error against the same reference.

    Dropping unpaired stems is not optional here: the whole quantity is a
    covariance between two handsets on one stem, which an unpaired row cannot
    contribute to.
    """
    sub = df[df.measurand == measurand]
    e = sub.pivot_table(index=["stem", "site"], columns="device",
                        values="error", aggfunc="first").dropna()
    flag = sub.groupby("stem")["tape_disputed"].any()
    ref = sub.groupby("stem")["reference"].first()
    e = e.reset_index()
    e["tape_disputed"] = e.stem.map(flag).fillna(False)
    e["reference"] = e.stem.map(ref)
    return e


ERR = {m: errors(m) for m in core.MEASURANDS}


# --------------------------------------------------------------------------
# The decomposition
# --------------------------------------------------------------------------

def decompose(a: np.ndarray, b: np.ndarray) -> dict:
    """Shared / independent split of two handsets' errors on the same stems.

    `a` is iOS, `b` is Android. Variances are about each handset's own mean, so
    the handset biases drop out; see the module docstring on what that costs.
    The shared variance is floored at zero because a covariance estimated from
    ~100 stems can come out negative by sampling noise, and a negative variance
    is not a quantity anyone can report.
    """
    n = len(a)
    cov = float(np.cov(a, b, ddof=1)[0, 1])
    sa, sb = float(a.std(ddof=1)), float(b.std(ddof=1))
    shared_var = max(cov, 0.0)
    shared = np.sqrt(shared_var)
    return dict(
        n=n, cov=cov, shared_sd=shared,
        sd_ios=sa, sd_android=sb,
        indep_sd_ios=np.sqrt(max(sa ** 2 - shared_var, 0.0)),
        indep_sd_android=np.sqrt(max(sb ** 2 - shared_var, 0.0)),
        shared_frac_ios=shared_var / sa ** 2 if sa else np.nan,
        shared_frac_android=shared_var / sb ** 2 if sb else np.nan,
    )


def boot(a: np.ndarray, b: np.ndarray, keys, n_boot=N_BOOT, seed=SEED, alpha=0.05):
    """Percentile CIs by resampling STEMS, which keeps the pairing intact.

    Resampling the two error columns independently would destroy the very
    covariance being estimated, so the resampling unit is the stem and both of
    its errors travel together.
    """
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(a), size=(n_boot, len(a)))
    out = {k: np.empty(n_boot) for k in keys}
    for j, ix in enumerate(idx):
        d = decompose(a[ix], b[ix])
        d["r"] = float(np.corrcoef(a[ix], b[ix])[0, 1])
        for k in keys:
            out[k][j] = d[k]
    return {k: tuple(np.nanpercentile(v, [100 * alpha / 2, 100 * (1 - alpha / 2)]))
            for k, v in out.items()}


BOOT_KEYS = ("r", "shared_sd", "shared_frac_ios", "shared_frac_android",
             "indep_sd_ios", "indep_sd_android")


def block(e: pd.DataFrame, measurand: str, subset: str, primary: bool) -> dict:
    a = e["ios"].to_numpy(float)
    b = e["android"].to_numpy(float)
    d = decompose(a, b)
    r, p = sps.pearsonr(a, b)
    rho, rho_p = sps.spearmanr(a, b)

    # Outlier robustness. A covariance is a sum of per-stem products, so two
    # stems can carry it. Drop the two largest contributors to that sum and
    # report what is left rather than the more convenient number.
    za = (a - a.mean()) / a.std(ddof=1)
    zb = (b - b.mean()) / b.std(ddof=1)
    drop = np.argsort(-np.abs(za * zb))[:2]
    keep = np.setdiff1d(np.arange(len(a)), drop)
    r_cut, p_cut = sps.pearsonr(a[keep], b[keep])
    d_cut = decompose(a[keep], b[keep])

    ci = boot(a, b, BOOT_KEYS)
    # Both handsets wrong in the same direction on the same stem: the same
    # signal as the correlation, in a form a field reader can check by eye
    # against the quadrants of panel A or B.
    concord = float(np.mean(np.sign(a - a.mean()) == np.sign(b - b.mean())) * 100)

    meta = core.MEASURANDS[measurand]
    return dict(
        measurand=meta["short"], unit=meta["unit"], subset=subset,
        role="primary" if primary else "exploratory",
        n=d["n"], r=r, r_p=p, r_ci_low=ci["r"][0], r_ci_high=ci["r"][1],
        spearman_rho=rho, spearman_p=rho_p,
        r_drop2=r_cut, r_drop2_p=p_cut,
        concordant_sign_pct=concord,
        cov=d["cov"],
        shared_sd=d["shared_sd"],
        shared_sd_ci_low=ci["shared_sd"][0], shared_sd_ci_high=ci["shared_sd"][1],
        shared_sd_drop2=d_cut["shared_sd"],
        sd_ios=d["sd_ios"], sd_android=d["sd_android"],
        shared_frac_ios=d["shared_frac_ios"],
        shared_frac_ios_ci_low=ci["shared_frac_ios"][0],
        shared_frac_ios_ci_high=ci["shared_frac_ios"][1],
        shared_frac_android=d["shared_frac_android"],
        shared_frac_android_ci_low=ci["shared_frac_android"][0],
        shared_frac_android_ci_high=ci["shared_frac_android"][1],
        indep_sd_ios=d["indep_sd_ios"],
        indep_sd_ios_ci_low=ci["indep_sd_ios"][0],
        indep_sd_ios_ci_high=ci["indep_sd_ios"][1],
        indep_sd_android=d["indep_sd_android"],
        indep_sd_android_ci_low=ci["indep_sd_android"][0],
        indep_sd_android_ci_high=ci["indep_sd_android"][1],
        # The quantity the stem-shape objection has to live inside.
        ceiling_stem_ref_sd=d["shared_sd"],
        ceiling_stem_ref_sd_upper=ci["shared_sd"][1],
        ceiling_pct_of_mean_reference=100 * d["shared_sd"] / e["reference"].mean(),
        bias_ios=float(a.mean()), bias_android=float(b.mean()),
        n_boot=N_BOOT, seed=SEED,
    )


rows = []
for m, e in ERR.items():
    rows.append(block(e, m, "Pooled", True))
    # Sensitivity: the stems whose tape reading is disputed. Reference error is
    # one of the candidates inside the shared term, so the check is on point.
    keep = e[~e.tape_disputed]
    rows.append(block(keep, m, "Pooled, tape-disputed excluded", True))
    # Per-site rows are EXPLORATORY and are not a stand effect: the two sites
    # differ in tree size, collection day, species recording and stem-pairing
    # method as well as in stand, and nothing here can separate those.
    for site in core.SITES:
        s = e[e.site == site]
        if len(s) >= 3:
            rows.append(block(s, m, site, False))

table = pd.DataFrame(rows)
P = {m: table[(table.measurand == core.MEASURANDS[m]["short"])
              & (table.subset == "Pooled")].iloc[0] for m in core.MEASURANDS}


# --------------------------------------------------------------------------
# Figure
#
# Panels A and B are the scatter, one per measurand because the units differ and
# a 1:1 line cannot span inches and feet. Panel C is the decomposition for all
# four handset x measurand cells on one axis, which is where the contrast that
# the whole analysis exists to show becomes a single glance.
# --------------------------------------------------------------------------
fig = plt.figure(figsize=(12.6, 4.6))
# Explicit margins rather than tight_layout: two of the three panels are forced
# square, and tight_layout cannot solve a layout with a fixed aspect ratio in it.
gs = fig.add_gridspec(1, 3, width_ratios=[1.14, 1.14, 1.0], wspace=0.30,
                      left=0.045, right=0.985, top=0.90, bottom=0.155)
MARK = {"McDunn": "o", "Starker": "^"}

for j, m in enumerate(["dbh", "height"]):
    e = ERR[m]
    meta = core.MEASURANDS[m]
    u = meta["unit"]
    st = P[m]
    ax = fig.add_subplot(gs[0, j])

    a = e["ios"].to_numpy(float)
    b = e["android"].to_numpy(float)
    core.square_identity_axes(ax, a, b)
    ax.axhline(0, color=core.PALETTE["grid"], lw=0.8, zorder=0)
    ax.axvline(0, color=core.PALETTE["grid"], lw=0.8, zorder=0)
    core.identity_line(ax, label="1:1 (identical error)")

    # The 95 % concentration ellipse of the joint error distribution. This is
    # the shape of the finding: round means the two handsets miss independently,
    # elongated along the 1:1 line means they miss together.
    cov2 = np.cov(a, b, ddof=1)
    vals, vecs = np.linalg.eigh(cov2)
    order = np.argsort(vals)[::-1]
    vals, vecs = vals[order], vecs[:, order]
    ang = np.degrees(np.arctan2(vecs[1, 0], vecs[0, 0]))
    w, h = 2 * np.sqrt(5.991 * np.maximum(vals, 0))
    ax.add_patch(Ellipse((a.mean(), b.mean()), w, h, angle=ang, fill=False,
                         edgecolor=core.PALETTE["reference"], lw=1.3, ls="-",
                         zorder=2, label="95 % concentration ellipse"))

    for site in core.SITES:
        s = e[e.site == site]
        ax.scatter(s["ios"], s["android"], s=22, marker=MARK[site],
                   facecolors="none", linewidths=0.9,
                   edgecolors=core.PALETTE["site"][site], zorder=3, label=site)

    ax.set_xlabel(f"iOS (LiDAR) error, measured − reference ({u})")
    ax.set_ylabel(f"Android (ARCore) error ({u})")
    lo_f = min(st.shared_frac_ios, st.shared_frac_android) * 100
    hi_f = max(st.shared_frac_ios, st.shared_frac_android) * 100
    ax.text(0.03, 0.975,
            f"{meta['short']}\n"
            f"r = {st.r:.3f} (95 % CI {st.r_ci_low:.2f} to {st.r_ci_high:.2f})\n"
            f"shared SD = {st.shared_sd:.2f} {u}\n"
            f"shared = {lo_f:.0f}–{hi_f:.0f} % of variance\n"
            f"n = {int(st.n)} stems",
            transform=ax.transAxes, va="top", ha="left", fontsize=7.4,
            bbox=dict(boxstyle="round,pad=0.32", fc="white",
                      ec=core.PALETTE["grid"], lw=0.6, alpha=0.93), zorder=6)
    ax.legend(loc="lower right", fontsize=6.4, handletextpad=0.4,
              borderpad=0.25, labelspacing=0.25)
    # Top-align the square panels with the full-height bar panel, so the three
    # panel tags sit on one line instead of the squares floating in the slot.
    ax.set_anchor("N")
    core.panel_tag(ax, "AB"[j])

# ---- Panel C: the decomposition ------------------------------------------
axc = fig.add_subplot(gs[0, 2])
cells = [("dbh", "ios"), ("dbh", "android"), ("height", "ios"), ("height", "android")]
xpos = np.array([0.0, 0.9, 2.3, 3.2])
SHARED_FC = "#DCE3E9"

for x, (m, dev) in zip(xpos, cells):
    st = P[m]
    u = core.MEASURANDS[m]["unit"]
    tot = st[f"sd_{dev}"]
    sh = st.shared_sd
    ind = st[f"indep_sd_{dev}"]
    frac = st[f"shared_frac_{dev}"] * 100
    # Bars are SHARES of each cell's error variance, because that is the one
    # scale on which inches and feet can sit side by side without inventing a
    # comparison. The real-unit SDs are annotated on every segment instead;
    # variances add, standard deviations do not, so the two segment SDs combine
    # in quadrature to the total printed above the bar.
    axc.bar(x, frac, width=0.72, bottom=0, color=SHARED_FC,
            edgecolor=core.PALETTE["reference"], linewidth=0.9, hatch="///",
            zorder=3, label="shared (stem / reference / method)" if x == 0 else None)
    axc.bar(x, 100 - frac, width=0.72, bottom=frac, color=core.PALETTE[dev],
            edgecolor=core.PALETTE["reference"], linewidth=0.9,
            hatch="" if dev == "ios" else "....", zorder=3,
            label=(f"independent ({core.DEVICE_SHORT[dev]})"
                   if m == "dbh" else None))
    # White plate behind the shared label only: it sits on a hatched fill, and
    # hatch lines through digits are how a reader misreads 1.03 as 1.08.
    axc.text(x, frac / 2, f"{frac:.0f} %\n{sh:.2f} {u}", ha="center", va="center",
             fontsize=7.4, color=core.PALETTE["reference"], fontweight="bold",
             zorder=5, bbox=dict(boxstyle="round,pad=0.22", fc="white",
                                 ec="none", alpha=0.85))
    axc.text(x, frac + (100 - frac) / 2, f"{100 - frac:.0f} %\n{ind:.2f} {u}",
             ha="center", va="center", fontsize=7.4, color="white",
             fontweight="bold", zorder=5)
    axc.text(x, 101.5, f"total {tot:.2f} {u}", ha="center", va="bottom",
             fontsize=7.0, color=core.PALETTE["reference"])

axc.set_xticks(xpos)
axc.set_xticklabels([core.DEVICE_SHORT[d] for _, d in cells], fontsize=8)
axc.set_xlim(-0.62, 3.82)
axc.set_ylim(0, 118)
axc.set_yticks([0, 20, 40, 60, 80, 100])
axc.set_ylabel("Share of error variance (%)\n"
               "(segment labels give that component's SD in real units)")
axc.axvline(1.6, color=core.PALETTE["muted"], lw=0.8, ls=":", zorder=1)
# Group labels below the handset ticks: the units differ between the pairs, and
# a reader must not be invited to compare an inch bar with a foot bar.
for xc, lab in ((0.45, "Diameter (in)"), (2.75, "Height (ft)")):
    axc.annotate(lab, xy=(xc, 0), xycoords=("data", "axes fraction"),
                 xytext=(0, -26), textcoords="offset points",
                 ha="center", va="top", fontsize=8.5, fontweight="bold")
axc.legend(loc="lower center", bbox_to_anchor=(0.5, 1.01), ncol=1, fontsize=6.6,
           handletextpad=0.5, labelspacing=0.25, borderpad=0.2)
core.panel_tag(axc, "C")

d_, h_ = P["dbh"], P["height"]
caption = f"""
How much of each handset's error is SHARED with the other handset, and how much is
its own, on the {int(d_.n)} stems (diameter) and {int(h_.n)} stems (height) measured by
both phones against one reference reading on these two stands. Every stem was measured
by an iPhone and by an Android phone, so each error splits as
e = bias + shared + instrument, the covariance of the two handsets' errors estimates the
shared variance, and what remains in each handset is its own. Two different handsets
running two different depth technologies cannot share an instrument error; a stem's
departure from what the reference instrument reports, the reference reading's own error,
and any method assumption both handsets inherit are shared by construction.
(A, B) iOS error against Android error on the same stem, drawn square on equal scales so
the 1:1 line — the line on which the two handsets make the identical error — is readable;
marker shape and colour both encode site; the ellipse is the 95 % concentration ellipse of
the joint error distribution, and its shape is the result: round means independent misses,
elongated along the 1:1 line means shared ones. (C) The same numbers as a share of each
cell's error variance, with the standard deviation of each component printed in inches or
feet on the segment and the total above the bar; segment SDs combine in quadrature, not by
addition. Diameter and height behave oppositely. On diameter the errors are mostly
independent — r = {d_.r:.3f} (95 % bootstrap CI {d_.r_ci_low:.2f} to {d_.r_ci_high:.2f},
p = {d_.r_p:.3f}), a shared SD of {d_.shared_sd:.2f} in against per-handset totals of
{d_.sd_ios:.2f} in (iOS) and {d_.sd_android:.2f} in (Android), so only
{min(d_.shared_frac_ios, d_.shared_frac_android) * 100:.0f}–{max(d_.shared_frac_ios, d_.shared_frac_android) * 100:.0f} %
of the variance is shared. Error in the reference reading acts on both handsets
identically, and so does out-of-roundness wherever the two phones saw the stem from a
similar azimuth, so both live inside the shared term and
{d_.shared_sd:.2f} in is a ceiling on their combined
contribution (bootstrap upper bound {d_.shared_sd_ci_high:.2f} in): the single-azimuth
silhouette against a tape's mean caliper diameter is a real limitation of the method, but
it cannot account for the diameter scatter, and roughly three quarters of that variance
remains with the instrument. Two people carried the two handsets and may have stood at
different azimuths on the same stem, so whatever part of an out-of-round stem's effect
changes with viewing angle is not common to the two handsets and falls in the independent
term; the {d_.shared_sd:.2f} in therefore bounds the part of stem shape and reference error
that acted on both handsets alike, and the instrument share is an upper bound. On height the errors are mostly shared —
r = {h_.r:.3f} ({h_.r_ci_low:.2f} to {h_.r_ci_high:.2f}, p = {h_.r_p:.1e}), a shared SD of
{h_.shared_sd:.2f} ft, which is
{min(h_.shared_frac_ios, h_.shared_frac_android) * 100:.0f}–{max(h_.shared_frac_ios, h_.shared_frac_android) * 100:.0f} %
of each handset's error variance. Independent instrument error cannot produce that; a
method assumption both handsets inherit can, and the height reference is a laser
rangefinder in 3-point mode, which inverts the same tangent geometry the app does. Height
results are therefore reported throughout as AGREEMENT with a 3-point laser rather than as
accuracy, and the {h_.shared_sd:.2f} ft shared component is reported as a finding rather
than absorbed into the residual. The decomposition describes scatter about each handset's
own mean and is blind to any offset common to both, so it neither supports nor rules out a
shared bias. These are the two stands measured, not a property of the app in general.
"""
core.save(fig, "fig13_shared_error", para(caption))

# --------------------------------------------------------------------------
# Table
# --------------------------------------------------------------------------
out = table.copy()
conv = {"in": core.CM_PER_IN, "ft": core.M_PER_FT}
out["shared_sd_metric"] = [r.shared_sd * conv[r.unit] for r in out.itertuples()]
out["metric_unit"] = out.unit.map({"in": "cm", "ft": "m"})
cols = ["measurand", "unit", "subset", "role", "n",
        "r", "r_ci_low", "r_ci_high", "r_p",
        "spearman_rho", "spearman_p", "r_drop2", "r_drop2_p",
        "concordant_sign_pct", "cov",
        "shared_sd", "shared_sd_ci_low", "shared_sd_ci_high", "shared_sd_drop2",
        "shared_sd_metric", "metric_unit",
        "sd_ios", "sd_android",
        "shared_frac_ios", "shared_frac_ios_ci_low", "shared_frac_ios_ci_high",
        "shared_frac_android", "shared_frac_android_ci_low",
        "shared_frac_android_ci_high",
        "indep_sd_ios", "indep_sd_ios_ci_low", "indep_sd_ios_ci_high",
        "indep_sd_android", "indep_sd_android_ci_low", "indep_sd_android_ci_high",
        "ceiling_stem_ref_sd", "ceiling_stem_ref_sd_upper",
        "ceiling_pct_of_mean_reference",
        "bias_ios", "bias_android", "n_boot", "seed"]
out = out[cols]
# p-values keep three significant figures rather than four decimals: rounding
# 1.24e-09 to 0.0000 would print a zero the data cannot support.
PCOLS = ["r_p", "spearman_p", "r_drop2_p"]
for c in out.columns:
    if out[c].dtype.kind != "f":
        continue
    out[c] = (out[c].map(lambda v: float(f"{v:.3g}")) if c in PCOLS
              else out[c].round(4))

tcap = f"""
Shared and independent components of handset error, from the two handsets' errors on the
same stems against one reference reading. Writing e = bias + shared + instrument and
assuming the two instrument terms are uncorrelated with each other and with the shared
term - which is what two different handsets, two different depth technologies and two
separate capture sessions buy - the covariance of the two error columns estimates the
shared variance, `shared_sd` is its square root floored at zero, and `indep_sd_*` is what
remains inside each handset. `shared_frac_ios` and `shared_frac_android` are the shared
share of each handset's error variance; they bracket `r` because their geometric mean is
exactly r, which is why one correlation yields a range. All intervals are seeded
{N_BOOT}-draw percentile bootstraps that resample STEMS, keeping both of a stem's errors
together - resampling the columns separately would destroy the covariance being estimated.
Read the estimates and their intervals as the result; `r_p` is reported for completeness
and is not what the finding rests on. `spearman_rho` and `r_drop2` (the correlation after
dropping the two stems contributing most to the covariance) are robustness checks, not
separate results. `ceiling_stem_ref_sd` restates `shared_sd` as what it bounds: stem
out-of-roundness against a tape's mean caliper diameter, and error in the reference
reading itself, both act on the two handsets alike and so cannot exceed it - with the
qualification that two people carried the two handsets and may have stood at different
azimuths, so any azimuth-dependent part of stem shape falls in `indep_sd_*` instead. That
makes every `shared_frac` a lower bound on the stem-and-reference contribution and every
instrument share an upper bound. Rows marked
exploratory are per-site splits, and they are where this estimator strains: with about 50
stems a bootstrap draw can put the estimated covariance above one handset's own variance,
so a `shared_frac` interval can reach past 1.0, which the model does not allow. That is a
small-subset artefact of estimating a covariance, not evidence of anything, and it is left
unclipped rather than tidied away. The two sites also differ in tree size (height median 64.1 vs
139.4 ft), collection day, species recording and stem-pairing method as well as in stand,
so a difference between them is a site difference confounded with tree size and collection
day and must not be read as a stand effect. The tape-disputed exclusion is a sensitivity
check on the reference, which is one of the candidates inside the shared term. Every
variance here is taken about the handset's own mean, so `bias_ios` and `bias_android` are
carried alongside as context only: an offset common to both handsets contributes nothing
to a covariance and is invisible to this decomposition. Diameters in inches, heights in
feet, with the shared SD repeated in cm and m. These are the two stands measured.
"""
core.save_table(out, "t13_shared_error", para(tcap))

# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 260, "display.max_columns", 80)
print(out.to_string(index=False))
print()
for m in core.MEASURANDS:
    st = P[m]
    u = st.unit
    print(f"{st.measurand}: n={int(st.n)}  r={st.r:+.3f} "
          f"[{st.r_ci_low:+.3f}, {st.r_ci_high:+.3f}] p={st.r_p:.3g}  "
          f"shared SD={st.shared_sd:.3f} {u} "
          f"[{st.shared_sd_ci_low:.3f}, {st.shared_sd_ci_high:.3f}]")
    print(f"    iOS      total SD {st.sd_ios:.3f} {u} = shared {st.shared_sd:.3f} "
          f"(+) indep {st.indep_sd_ios:.3f} in quadrature; shared "
          f"{st.shared_frac_ios * 100:.1f} % of variance "
          f"[{st.shared_frac_ios_ci_low * 100:.0f}, {st.shared_frac_ios_ci_high * 100:.0f}]")
    print(f"    Android  total SD {st.sd_android:.3f} {u} = shared {st.shared_sd:.3f} "
          f"(+) indep {st.indep_sd_android:.3f} in quadrature; shared "
          f"{st.shared_frac_android * 100:.1f} % of variance "
          f"[{st.shared_frac_android_ci_low * 100:.0f}, "
          f"{st.shared_frac_android_ci_high * 100:.0f}]")
    print(f"    ceiling on stem-shape + reference SD: {st.ceiling_stem_ref_sd:.3f} {u} "
          f"(bootstrap upper {st.ceiling_stem_ref_sd_upper:.3f}; "
          f"{st.ceiling_pct_of_mean_reference:.1f} % of the mean reference); "
          f"same-direction errors on {st.concordant_sign_pct:.0f} % of stems")
