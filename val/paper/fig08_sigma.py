#!/usr/bin/env python3
"""Is the sigma the app shows the cruiser an honest uncertainty?

The app prints a sigma beside every measurement. That is not decoration: it is a
falsifiable claim about how far the reading is likely to be from the truth. This
script tests it four ways, per measurand x handset.

  1. SCALE.       Median reported sigma against median |error|.
  2. COVERAGE.    Fraction of stems with |error| <= 1.96 sigma. Nominal 95 %.
                  Reported with an exact (Clopper-Pearson) interval and a
                  binomial test against 0.95, because coverage is a proportion
                  and 100 stems is not enough to read it to the percent.
  3. SHAPE.       Median standardised residual |error| / sigma. For a normal
                  error of the claimed size this is 0.6745, the median of |Z|.
  4. RANK.        Spearman correlation between sigma and |error|. A sigma whose
                  SCALE is wrong but whose ORDER is right is a calibration bug -
                  multiply it by a constant and it works. A sigma that does not
                  even order the errors carries no information and no constant
                  can save it. These are different failures and the manuscript
                  should not conflate them.

  4b. The rank test alone is not enough. If sigma is a fixed percentage of the
      measurement, and error also grows with size, sigma will correlate with
      |error| while telling the cruiser nothing that the tree's own size did not
      already tell them. So the Spearman is repeated as a PARTIAL rank
      correlation holding the reference size fixed. That is the test of whether
      sigma knows anything about THIS capture.

CALIBRATION FACTORS. Two, because they answer different questions:
  k_med  = median(|e|/sigma) / 0.6745  - the rescale that puts the TYPICAL
           residual where a normal of the claimed size would put it.
  k_cov  = q95(|e|/sigma) / 1.96       - the rescale that delivers 95 % coverage.
           Undefined (infinite) if 5 % or more of the stems report sigma = 0
           with a non-zero error, since no multiple of zero covers anything.
  A third, sigma_flat = q95(|e|) / 1.96, is the single CONSTANT sigma that would
  have delivered 95 % coverage - the fallback available if the per-stem number
  is unsalvageable.

UNITS. sigma arrives from core.load() already converted into the measurand's own
unit (inches for DBH, feet for height). Panel A plots both measurands on shared
axes by dividing sigma and |error| by the same per-stem reference; that is a
per-point rescale, so the 1:1 and 1.96-sigma lines are exactly preserved and no
point changes side.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from scipy import stats as sps

import core

plt = core.use_style()
df = core.load()

MED_ABS_Z = sps.norm.ppf(0.75)      # 0.6745: median |Z| for a standard normal
K95 = 1.96
MARK = {"dbh": "o", "height": "^"}
LSTY = {"dbh": "-", "height": (0, (5, 2))}
BOOT = 4000
RNG_SEED = 17


# --------------------------------------------------------------------------
# statistics for one measurand x device cell
# --------------------------------------------------------------------------

def std_resid(sigma, abs_err):
    """|error| / sigma, with sigma = 0 mapped to +inf rather than dropped.

    Dropping the zeros would quietly delete the app's worst claims - a stem for
    which it reported perfect precision and was wrong by 6.5 in. +inf is the
    honest value: the median survives it, the mean and the upper quantiles do
    not, and their being infinite is the result, not a nuisance.
    """
    sigma = np.asarray(sigma, float)
    abs_err = np.asarray(abs_err, float)
    out = np.full(len(sigma), np.inf)
    ok = sigma > 0
    out[ok] = abs_err[ok] / sigma[ok]
    return out


def coverage(sigma, abs_err, k=K95):
    return float(np.mean(np.asarray(abs_err, float) <= k * np.asarray(sigma, float)))


def clopper_pearson(c, n, alpha=0.05):
    lo = sps.beta.ppf(alpha / 2, c, n - c + 1) if c > 0 else 0.0
    hi = sps.beta.ppf(1 - alpha / 2, c + 1, n - c) if c < n else 1.0
    return 100 * lo, 100 * hi


def partial_spearman(x, y, z):
    """Rank correlation of x and y with the rank of z partialled out."""
    rx, ry, rz = (sps.rankdata(v) for v in (x, y, z))
    rxy = sps.pearsonr(rx, ry)[0]
    rxz = sps.pearsonr(rx, rz)[0]
    ryz = sps.pearsonr(ry, rz)[0]
    r = (rxy - rxz * ryz) / np.sqrt((1 - rxz ** 2) * (1 - ryz ** 2))
    n = len(x)
    t = r * np.sqrt((n - 3) / (1 - r ** 2))
    return r, 2 * (1 - sps.t.cdf(abs(t), n - 3))


def spearman_ci(x, y, seed=RNG_SEED, n_boot=BOOT):
    """Percentile bootstrap CI for rho. Preferred over the Fisher-z interval
    because sigma is heavily tied (the zeros) and far from bivariate normal."""
    rng = np.random.default_rng(seed)
    x, y = np.asarray(x, float), np.asarray(y, float)
    n = len(x)
    idx = rng.integers(0, n, size=(n_boot, n))
    draws = np.array([sps.spearmanr(x[i], y[i]).statistic for i in idx])
    draws = draws[np.isfinite(draws)]
    return float(np.percentile(draws, 2.5)), float(np.percentile(draws, 97.5))


def cell(sub: pd.DataFrame) -> dict:
    sg = sub.sigma.values
    ae = sub.abs_error.values
    er = sub.error.values
    ref = sub.reference.values
    n = len(sub)
    z = std_resid(sg, ae)
    c = int((ae <= K95 * sg).sum())
    cov = 100 * c / n
    cp_lo, cp_hi = clopper_pearson(c, n)

    q95_z = float(np.percentile(z, 95))                      # inf if >=5 % zeros
    fin = np.isfinite(z)
    q95_z_pos = float(np.percentile(z[fin], 95)) if fin.any() else np.nan

    rho, rho_p = sps.spearmanr(sg, ae)
    rho_lo, rho_hi = spearman_ci(sg, ae)
    prho, prho_p = partial_spearman(sg, ae, ref)
    rho_size, rho_size_p = sps.spearmanr(sg, ref)

    # sigma as a pure PRECISION claim: strip the systematic bias out of the
    # error first, since a sigma need not promise to cover a fixed offset.
    d_centred = np.abs(er - er.mean())

    return dict(
        n=n, n_sigma_zero=int((sg == 0).sum()),
        median_sigma=float(np.median(sg)),
        iqr_sigma_low=float(np.percentile(sg, 25)),
        iqr_sigma_high=float(np.percentile(sg, 75)),
        median_abs_error=float(np.median(ae)),
        ratio_median_abserr_to_sigma=(float(np.median(ae) / np.median(sg))
                                      if np.median(sg) > 0 else np.inf),
        coverage_pct=cov, coverage_ci_low=cp_lo, coverage_ci_high=cp_hi,
        coverage_p_vs_95=float(sps.binomtest(c, n, 0.95).pvalue),
        median_std_resid=float(np.median(z)), expected_std_resid=MED_ABS_Z,
        calib_factor_median=float(np.median(z) / MED_ABS_Z),
        calib_factor_cov95=q95_z / K95,
        calib_factor_cov95_sigma_gt0=q95_z_pos / K95,
        sigma_flat_for_95=float(np.percentile(ae, 95) / K95),
        spearman_rho=float(rho), spearman_p=float(rho_p),
        spearman_ci_low=rho_lo, spearman_ci_high=rho_hi,
        partial_rho_given_size=float(prho), partial_rho_p=float(prho_p),
        rho_sigma_vs_reference=float(rho_size),
        rho_sigma_vs_reference_p=float(rho_size_p),
        coverage_pct_debiased=100 * coverage(sg, d_centred),
        median_sigma_over_measured=float(np.median(sg / sub.measured.values)),
    )


def sens(sub: pd.DataFrame) -> dict:
    """The same cell with the disputed-tape stems removed."""
    keep = sub[~sub.tape_disputed]
    sg, ae = keep.sigma.values, keep.abs_error.values
    rho, p = sps.spearmanr(sg, ae)
    prho, prho_p = partial_spearman(sg, ae, keep.reference.values)
    return dict(
        n_disputed=int(sub.tape_disputed.sum()), n_excl=len(keep),
        coverage_pct_excl=100 * coverage(sg, ae),
        median_std_resid_excl=float(np.median(std_resid(sg, ae))),
        spearman_rho_excl=float(rho), spearman_p_excl=float(p),
        partial_rho_excl=float(prho), partial_rho_p_excl=float(prho_p),
    )


# --------------------------------------------------------------------------
# table
# --------------------------------------------------------------------------

cells = {}
rows = []
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        sub = df[(df.measurand == k) & (df.device == dev)]
        c = cell(sub)
        cells[(k, dev)] = c
        rows.append(dict(measurand=core.MEASURANDS[k]["short"],
                         device=core.DEVICE_SHORT[dev],
                         unit=core.MEASURANDS[k]["unit"],
                         **c, **sens(sub)))

tab = pd.DataFrame(rows)
# Round the estimates, but NOT the p-values: several are of order 1e-100 and
# rounding them to four decimals would print them as exactly zero, which is a
# claim the data cannot make.
numcols = [c for c in tab.select_dtypes(include=[float]).columns
           if not (c.endswith("_p") or c.startswith("spearman_p")
                   or c == "coverage_p_vs_95")]
tab[numcols] = tab[numcols].round(4)

core.save_table(
    tab, "t08_sigma",
    "Table 8. Calibration of the uncertainty the ForestiX app reports to the "
    "cruiser, per measurand and handset. sigma is the app's own reported standard "
    "uncertainty, in the measurand's unit (inches for DBH, feet for height); "
    "error is phone minus field reference (diameter tape for DBH, laser "
    "rangefinder in 3-point mode for height). coverage_pct is the percentage of "
    "stems with |error| <= 1.96 sigma against a nominal 95 %, with an exact "
    "Clopper-Pearson interval and a binomial test of the 0.95 null. "
    "median_std_resid is the median of |error|/sigma, which for a normal error of "
    "the claimed size would be 0.6745 (expected_std_resid). The calibration "
    "factors are the constants sigma would have to be multiplied by: "
    "calib_factor_median puts the typical residual right, calib_factor_cov95 "
    "delivers 95 % coverage and is INFINITE wherever 5 % or more of the stems "
    "report sigma = 0 with a non-zero error (n_sigma_zero), so "
    "calib_factor_cov95_sigma_gt0 repeats it on the sigma > 0 subset; "
    "sigma_flat_for_95 is instead the single CONSTANT sigma, in the measurand's "
    "unit, that would have covered 95 % of stems. spearman_rho with its 4 000-draw "
    "bootstrap interval tests whether sigma at least ORDERS the errors correctly "
    "even where its scale is wrong; partial_rho_given_size repeats that with the "
    "reference size partialled out, because sigma is largely a function of the "
    "measurement itself (rho_sigma_vs_reference) and would inherit a correlation "
    "with error from size alone. coverage_pct_debiased re-tests coverage after "
    "removing the mean error, i.e. treating sigma as a claim about precision only "
    "and not about bias. Columns ending _excl repeat coverage, the standardised "
    "residual and the rank correlation with the n_disputed stems whose tape "
    "reading is disputed removed.")


# --------------------------------------------------------------------------
# figure
# --------------------------------------------------------------------------

fig, axes = plt.subplots(1, 2, figsize=(core.FIG_W, core.FIG_H * 0.98))
axA, axB = axes

# ---- Panel A: reported sigma against realised |error| ----------------------
# Both are divided by the stem's own reference so inches and feet share axes.
# That is a per-point rescale: the 1:1 and 1.96-sigma lines are unchanged and
# no point crosses them.
rel = {}
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        rel[(k, dev)] = (100 * s.sigma.values / s.reference.values,
                         100 * s.abs_error.values / s.reference.values)

pos = np.concatenate([x[x > 0] for x, _ in rel.values()])
ally = np.concatenate([y for _, y in rel.values()])
ally = ally[ally > 0]
xhi = pos.max() * 2.4
xlo_data = pos.min() / 1.6
xlo = xlo_data / 7.0                 # strip at the far left for the sigma = 0 stems
ylo, yhi = ally.min() / 3.0, ally.max() * 3.0   # headroom for the annotations

axA.set_xscale("log")
axA.set_yscale("log")
axA.set_xlim(xlo, xhi)
axA.set_ylim(ylo, yhi)

# Shade the region where the app's OWN 95 % claim FAILS - above |e| = 1.96 sigma -
# because that is where the stems are, and the point of the panel is that they
# are there. Shading the passing region instead would fill most of the axes with
# reassuring colour and almost no data.
gx = np.logspace(np.log10(xlo), np.log10(xhi), 60)
axA.fill_between(gx, np.minimum(K95 * gx, yhi), yhi, color=core.PALETTE["accent"],
                 alpha=0.055, lw=0, zorder=0)
axA.plot([xlo, xhi], [xlo, xhi], color=core.PALETTE["muted"], lw=0.9,
         ls=(0, (1.5, 1.5)), zorder=2)
axA.plot([xlo, xhi], [K95 * xlo, K95 * xhi], color=core.PALETTE["reference"],
         lw=1.4, zorder=2)

# the sigma = 0 strip
xzero_hi = xlo_data / 2.6
axA.axvspan(xlo, xzero_hi, color=core.PALETTE["grid"], alpha=0.55, lw=0, zorder=0)
xzero = float(np.sqrt(xlo * xzero_hi))

for k in core.MEASURANDS:
    for dev in core.DEVICES:
        x, y = rel[(k, dev)]
        ok = x > 0
        c = cells[(k, dev)]
        axA.scatter(x[ok], y[ok], s=17, marker=MARK[k],
                    facecolor=core.PALETTE[dev], alpha=0.72,
                    edgecolor="white", linewidth=0.3, zorder=4,
                    label=(f"{core.MEASURANDS[k]['short']}, "
                           f"{core.DEVICE_SHORT[dev]}   "
                           f"{c['median_std_resid']:>4.1f}"))
        if (~ok).any():
            axA.scatter(np.full((~ok).sum(), xzero), y[~ok], s=26, marker=MARK[k],
                        facecolor="none", edgecolor=core.PALETTE[dev],
                        linewidth=1.0, zorder=5)

n_zero_total = int((df.sigma == 0).sum())
axA.text(xzero, ylo * 2.2, f"σ = 0\nn = {n_zero_total}", fontsize=6.4, ha="center",
         va="bottom", color=core.PALETTE["reference"], linespacing=1.2, zorder=8)

# label the two reference lines near where they leave the axes
axA.text(xhi / 1.6, K95 * xhi / 1.6 * 1.45, "|error| = 1.96 σ", fontsize=6.9,
         ha="right", va="bottom", color=core.PALETTE["reference"])
axA.text(xhi / 1.6, xhi / 1.6 / 1.5, "1:1", fontsize=6.9, ha="right", va="top",
         color=core.PALETTE["muted"])
axA.text(xzero_hi * 1.5, yhi / 1.1, "shaded: where the app's\nown 95 % claim fails",
         fontsize=6.7, ha="left", va="top", color=core.PALETTE["accent"],
         style="italic", linespacing=1.25, zorder=8)

# the vertical stripe of height points IS the finding: sigma is a formula, not a
# per-capture uncertainty. Annotated from the empty floor of the panel.
sig_pct = 100 * np.median(
    df[df.measurand == "height"].sigma / df[df.measurand == "height"].measured)
axA.annotate(f"every height σ is\n{sig_pct:.1f} % of the reading",
             xy=(sig_pct * 0.85, ylo * 9), xytext=(xzero_hi * 4.0, ylo * 1.6),
             fontsize=6.7, ha="left", va="bottom", color=core.PALETTE["reference"],
             linespacing=1.25, zorder=8,
             arrowprops=dict(arrowstyle="->", lw=0.7,
                             color=core.PALETTE["reference"],
                             connectionstyle="arc3,rad=0.18"))

axA.set_xlabel("Reported σ (% of reference)")
axA.set_ylabel("|error| (% of reference)")
core.panel_tag(axA, "A")

axA.legend(loc="lower right", fontsize=6.3, handletextpad=0.2, borderpad=0.4,
           labelspacing=0.3, title="median |e|/σ   (expect 0.67)",
           title_fontsize=6.3, framealpha=0.92, facecolor="white",
           edgecolor=core.PALETTE["grid"], frameon=True)

# ---- Panel B: empirical coverage against nominal ---------------------------
nominal = np.linspace(0.01, 0.999, 400)
zq = sps.norm.ppf(0.5 + nominal / 2)

axB.fill_between([0, 100], [0, 100], 103, color=core.PALETTE["accent"],
                 alpha=0.055, lw=0, zorder=0)
axB.plot([0, 100], [0, 100], color=core.PALETTE["muted"], lw=1.0,
         ls=(0, (1.5, 1.5)), zorder=2)
axB.text(78, 82, "perfectly calibrated", fontsize=6.6,
         color=core.PALETTE["muted"], ha="center", va="bottom", rotation=41)
axB.axvline(95, color=core.PALETTE["reference"], lw=0.8, ls=(0, (4, 3)), zorder=1)
axB.text(93.5, 2, "the 95 % the app displays", fontsize=6.6, rotation=90,
         ha="right", va="bottom", color=core.PALETTE["reference"])

for k in core.MEASURANDS:
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        sg, ae = s.sigma.values, s.abs_error.values
        emp = np.array([100 * np.mean(ae <= q * sg) for q in zq])
        c = cells[(k, dev)]
        axB.plot(100 * nominal, emp, color=core.PALETTE[dev], lw=1.6,
                 ls=LSTY[k], zorder=4,
                 label=(f"{core.MEASURANDS[k]['short']}, "
                        f"{core.DEVICE_SHORT[dev]}   {c['coverage_pct']:>4.0f} %"))
        axB.plot([95], [c["coverage_pct"]], marker=MARK[k], ms=6.5,
                 mfc=core.PALETTE[dev], mec="white", mew=0.7, zorder=6)

axB.set_xlim(0, 103)
axB.set_ylim(0, 103)
axB.set_xlabel("Nominal coverage claimed by σ (%)")
axB.set_ylabel("Coverage actually achieved (%)")
core.panel_tag(axB, "B")

axB.legend(loc="upper left", fontsize=6.3, handlelength=2.4, handletextpad=0.4,
           borderpad=0.4, labelspacing=0.3,
           title="achieved where σ claims 95 %", title_fontsize=6.3,
           framealpha=0.92, facecolor="white", edgecolor=core.PALETTE["grid"],
           frameon=True)

fig.tight_layout()
fig.subplots_adjust(wspace=0.28)


def g(k, dev, f):
    return cells[(k, dev)][f]


caption = (
    "Figure 8. The uncertainty the ForestiX app reports to the cruiser is not "
    "honest: it is far too small, and it does not know which readings are bad. "
    "(A) Reported σ against the realised |error| for every stem, on log axes, with "
    "DBH (circles) and height (triangles) placed on shared axes by dividing both "
    "coordinates by the stem's own reference - a per-point rescale, so the plotted "
    "lines and every point's side of them are exactly preserved. The heavy line is "
    "|error| = 1.96 σ, the app's own 95 % claim; a stem must fall BELOW it for that "
    "claim to hold, and the shaded region above it is where the claim fails. The "
    "dotted line is 1:1. Almost every stem sits in the shaded region. "
    "The median standardised residual "
    "|error|/σ is "
    + ", ".join(f"{g(k, d, 'median_std_resid'):.1f} "
                f"({core.MEASURANDS[k]['short']}/{core.DEVICE_SHORT[d]})"
                for k in core.MEASURANDS for d in core.DEVICES)
    + f", against the 0.67 a normal error of the claimed size would give. The "
    f"{n_zero_total} open symbols in the left-hand strip are stems for which the "
    "app reported σ = 0 - a claim of perfect precision - and was wrong by up to "
    f"{df[df.sigma == 0].abs_error.max():.1f} in; they cannot be drawn on a log "
    "axis and no multiple of zero covers anything, so the coverage-matching "
    "calibration factor for DBH is infinite. (B) Coverage actually achieved "
    "against the coverage σ claims, swept over every nominal level; the dashed "
    "diagonal is perfect calibration and the vertical line marks the nominal 95 % "
    "the app displays. Every curve lies far below the diagonal at every level. At "
    "the nominal 95 %, coverage is "
    + ", ".join(f"{g(k, d, 'coverage_pct'):.0f} % "
                f"(95 % CI {g(k, d, 'coverage_ci_low'):.0f}-"
                f"{g(k, d, 'coverage_ci_high'):.0f}) for "
                f"{core.MEASURANDS[k]['short']}/{core.DEVICE_SHORT[d]}"
                for k in core.MEASURANDS for d in core.DEVICES)
    + " (exact binomial vs 0.95, all p < 1e-30). DBH σ would need multiplying by "
    f"{g('dbh','ios','calib_factor_median'):.0f} (iOS) and "
    f"{g('dbh','android','calib_factor_median'):.0f} (Android) merely to place the "
    "typical residual correctly, height σ by "
    f"{g('height','ios','calib_factor_median'):.1f} and "
    f"{g('height','android','calib_factor_median'):.1f}. Rescaling is not "
    "sufficient, because σ also fails to RANK the errors: the Spearman correlation "
    "between σ and |error| is "
    + ", ".join(f"{g(k, d, 'spearman_rho'):+.2f} "
                f"({core.MEASURANDS[k]['short']}/{core.DEVICE_SHORT[d]})"
                for k in core.MEASURANDS for d in core.DEVICES)
    + ", and the one clearly non-zero value, Android height, is an artefact of "
    "size - height σ is essentially a fixed 2.3 % of the measured height "
    f"(R2 = 0.99 on the measurement), and with reference size partialled out the "
    "rank correlations fall to "
    + ", ".join(f"{g(k, d, 'partial_rho_given_size'):+.2f} (p = "
                f"{g(k, d, 'partial_rho_p'):.2f})"
                for k in core.MEASURANDS for d in core.DEVICES)
    + ", none significant. Caveat: the reference carries its own error, so "
    "|error| overstates the phone's dispersion slightly and the σ failure reported "
    "here is, if anything, conservative. Removing the eight stems with a disputed "
    "tape reading does not change the conclusion (coverage "
    + ", ".join(f"{r['coverage_pct']:.0f}→{r['coverage_pct_excl']:.0f} %"
                for r in rows) +
    "); the only difference it makes is that the DBH/iOS rank correlation rises to "
    f"{rows[0]['spearman_rho_excl']:+.2f} (p = {rows[0]['spearman_p_excl']:.3f}), "
    "which is still weak and still not significant once size is partialled out "
    f"({rows[0]['partial_rho_excl']:+.2f}, p = "
    f"{rows[0]['partial_rho_p_excl']:.3f}). "
    "n = 100 stems per series except height/iOS, n = 99, where one reading was "
    "typed rather than measured and is excluded. Diameters in inches, heights in "
    "feet.")

core.save(fig, "fig08_sigma", caption)


# --------------------------------------------------------------------------
# console report
# --------------------------------------------------------------------------
print("\n=== reported sigma vs realised error ===")
for k in core.MEASURANDS:
    u = core.MEASURANDS[k]["unit"]
    for dev in core.DEVICES:
        c = cells[(k, dev)]
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"n={c['n']:3d}  med sigma {c['median_sigma']:.3f} {u} "
              f"(IQR {c['iqr_sigma_low']:.3f}-{c['iqr_sigma_high']:.3f})  "
              f"med |e| {c['median_abs_error']:.3f} {u}  "
              f"x{c['ratio_median_abserr_to_sigma']:.1f}")

print("\n=== coverage of the 1.96-sigma interval (nominal 95 %) ===")
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        c = cells[(k, dev)]
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"{c['coverage_pct']:5.1f} % "
              f"[{c['coverage_ci_low']:.1f},{c['coverage_ci_high']:.1f}] "
              f"binomial p = {c['coverage_p_vs_95']:.2g}   "
              f"debiased {c['coverage_pct_debiased']:5.1f} %   "
              f"sigma=0 in {c['n_sigma_zero']:2d} stems")

print("\n=== shape and calibration factor ===")
for k in core.MEASURANDS:
    u = core.MEASURANDS[k]["unit"]
    for dev in core.DEVICES:
        c = cells[(k, dev)]
        kc = c["calib_factor_cov95"]
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"med |e|/sigma {c['median_std_resid']:7.2f} (expected 0.67)  "
              f"k_med x{c['calib_factor_median']:6.1f}  "
              f"k_cov95 {'INF' if not np.isfinite(kc) else f'x{kc:.1f}'} "
              f"(sigma>0 only x{c['calib_factor_cov95_sigma_gt0']:.1f})  "
              f"flat sigma for 95 % = {c['sigma_flat_for_95']:.2f} {u}")

print("\n=== does sigma at least RANK the errors? ===")
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        c = cells[(k, dev)]
        verdict = ("informative" if c["spearman_ci_low"] > 0 else "NOT informative")
        pv = ("still informative" if c["partial_rho_p"] < 0.05
              else "explained by size")
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"rho {c['spearman_rho']:+.3f} "
              f"[{c['spearman_ci_low']:+.3f},{c['spearman_ci_high']:+.3f}] "
              f"p={c['spearman_p']:.3f} -> {verdict:15s} | "
              f"rho(sigma,size) {c['rho_sigma_vs_reference']:+.3f}  "
              f"partial rho {c['partial_rho_given_size']:+.3f} "
              f"(p={c['partial_rho_p']:.3f}) -> {pv}")

print("\n=== disputed-tape sensitivity ===")
for r in rows:
    print(f"{r['measurand']:6s} {r['device']:8s} drop {r['n_disputed']}: "
          f"coverage {r['coverage_pct']:.1f} -> {r['coverage_pct_excl']:.1f} %  "
          f"med|e|/sigma {r['median_std_resid']:.2f} -> "
          f"{r['median_std_resid_excl']:.2f}  "
          f"rho {r['spearman_rho']:+.3f} (p={r['spearman_p']:.3f}) -> "
          f"{r['spearman_rho_excl']:+.3f} (p={r['spearman_p_excl']:.3f})")

print("\n=== what sigma actually is: a function of the measurement ===")
for k in core.MEASURANDS:
    for dev in core.DEVICES:
        s = df[(df.measurand == k) & (df.device == dev)]
        sl, ic, r2 = core.ols(s.measured.values, s.sigma.values)
        c = cells[(k, dev)]
        print(f"{core.MEASURANDS[k]['short']:6s} {core.DEVICE_SHORT[dev]:8s} "
              f"sigma = {sl:.5f} * measured {ic:+.4f}  R2 = {r2:.3f}   "
              f"median sigma/measured = {100*c['median_sigma_over_measured']:.2f} %")

print("\nsaved:", core.FIGDIR, core.RESDIR)
