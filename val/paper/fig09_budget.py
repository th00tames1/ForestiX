#!/usr/bin/env python3
"""Error budget: what the total error is MADE OF, and how much of it a developer can remove.

Mean squared error against the tape is one number, and it hides two distinctions
that decide what to do next.

FIRST SPLIT - systematic vs scattered.

    MSE_d = E[e_d^2] = b_d^2 + V_d ,   b_d = E[e_d],  V_d = Var(e_d)

with e_d(s) = measured_d(s) - reference(s) the error of device d on stem s.
Population moments (ddof = 0) are used so the split is exact rather than
approximately additive. b_d^2 is what a constant offset in the app would delete;
V_d is what it would not touch.

SECOND SPLIT - shared vs device-specific. Both handsets measured every stem
against the SAME tape reading, so write

    e_d(s) = b_d + c(s) + u_d(s)

with c(s) a stem-level term both devices see (an out-of-round bole, a leaning
stem, an ambiguous top, or a tape reading that is itself wrong) and u_d(s) the
part only that handset makes. Assume E[c] = E[u_d] = 0, Cov(c, u_d) = 0 and
Cov(u_ios, u_android) = 0 - the handsets' private errors are unrelated once the
stem is accounted for. Then

    Cov(e_ios, e_android) = Var(c)              == sigma_c^2
    Var(e_d) = sigma_c^2 + Var(u_d)   =>  Var(u_d) = V_d - sigma_c^2
    MSE_d = b_d^2 + sigma_c^2 + Var(u_d)

so the covariance of the two devices' errors on the same stem IS the common
variance, and everything else follows by subtraction. Equivalently
sigma_c^2 = rho * sqrt(V_ios * V_android) with rho the Pearson correlation of the
two error vectors.

WHAT THIS CANNOT SEPARATE. The loading of c is fixed at 1 for both devices. If
one handset were genuinely more sensitive to a difficult stem than the other
(e_d = b_d + lam_d*c + u_d) the covariance would return lam_ios*lam_android*sigma_c^2,
and lam_ios, lam_android are not identified from two devices alone - it would take
a third instrument, or repeated captures of the same stem by the same device.
The common term also cannot tell a bad tape reading apart from a hard stem, nor
either of those from a modelling choice the two apps happen to share: both are
the same authors' geometry, so a common algorithmic bias would land in sigma_c^2
and be read as if it came from the tree. sigma_c^2 is therefore an upper bound on
what is attributable to the stem and the reference.

CALIBRATION LADDER. "Removable by calibration" is reported at two strengths: an
offset (delete b_d) and an affine map fitted by least squares of reference on
measured (delete b_d and any proportional scale error). The affine fit is scored
both in sample and by exact leave-one-out, and - the test that matters - by
fitting at one site and applying at the other, because a calibration that has to
be refitted per stand is not a calibration.
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


# Operational tolerances. Fixed in advance and shared with the cross-platform
# analysis: a cruiser records DBH to the nearest inch and height to the nearest
# 5 ft, so an error smaller than one recording increment cannot change the tally.
TOLERANCE = {"dbh": 1.0, "height": 5.0}


# --------------------------------------------------------------------------
# Paired error frame: one row per stem, both devices' errors side by side
# --------------------------------------------------------------------------

def error_frame(measurand: str) -> pd.DataFrame:
    sub = df[df.measurand == measurand]
    wide = sub.pivot_table(index=["stem", "site"],
                           columns="device",
                           values=["measured", "reference", "error"],
                           aggfunc="first")
    wide.columns = [f"{a}_{b}" for a, b in wide.columns]
    wide = wide.dropna(subset=["error_ios", "error_android"]).reset_index()
    wide["reference"] = wide["reference_ios"].fillna(wide["reference_android"])
    flag = sub.groupby("stem")["tape_disputed"].any().rename("tape_disputed")
    wide = wide.merge(flag, on="stem", how="left")
    wide["tape_disputed"] = wide["tape_disputed"].fillna(False)
    return wide


EF = {m: error_frame(m) for m in core.MEASURANDS}


# --------------------------------------------------------------------------
# The decomposition
# --------------------------------------------------------------------------

def affine_calibration(measured, reference):
    """Least-squares map measured -> reference, with exact leave-one-out residuals.

    Reference is regressed ON measured (not the other way round) because the
    calibration is a PREDICTION: given what the phone says, what is the tape
    likely to have said. That is the direction least squares minimises. The
    structural slope in the Deming sense answers a different question and is not
    what a developer would ship.
    """
    x = np.asarray(measured, float)
    y = np.asarray(reference, float)
    n = len(x)
    beta, alpha = np.polyfit(x, y, 1)
    fitted = alpha + beta * x
    resid = fitted - y                      # same sign convention as `error`
    # Exact LOO for simple linear regression: e_(-i) = e_i / (1 - h_ii).
    sxx = ((x - x.mean()) ** 2).sum()
    h = 1.0 / n + (x - x.mean()) ** 2 / sxx
    loo = resid / (1.0 - h)
    return dict(slope=beta, intercept=alpha, resid=resid, loo=loo)


def budget(frame: pd.DataFrame, measurand: str, subset: str) -> list[dict]:
    """bias^2 / common / independent for both devices on one subset of stems."""
    ei = frame["error_ios"].to_numpy(float)
    ea = frame["error_android"].to_numpy(float)
    n = len(frame)
    cov = float(np.cov(ei, ea, ddof=0)[0, 1])          # == sigma_c^2
    r, r_p = sps.pearsonr(ei, ea)

    cal = {d: affine_calibration(frame[f"measured_{d}"], frame["reference"])
           for d in core.DEVICES}
    ri = cal["ios"]["resid"]
    ra = cal["android"]["resid"]
    cov_cal = float(np.cov(ri, ra, ddof=0)[0, 1])
    r_cal, _ = sps.pearsonr(ri, ra)

    rows = []
    for dev, e, res in (("ios", ei, ri), ("android", ea, ra)):
        b = float(e.mean())
        v = float(e.var(ddof=0))
        mse = float((e ** 2).mean())
        common = cov
        indep = v - cov
        # Post-calibration floor. OLS residuals have mean zero by construction,
        # so bias^2 is exactly nil and the floor is entirely variance.
        v_cal = float(res.var(ddof=0))
        mse_cal = float((res ** 2).mean())
        mse_loo = float((cal[dev]["loo"] ** 2).mean())
        rows.append(dict(
            measurand=core.MEASURANDS[measurand]["short"],
            key=measurand, unit=core.MEASURANDS[measurand]["unit"],
            device=core.DEVICE_SHORT[dev], device_key=dev, subset=subset, n=n,
            bias=b, mse=mse, var_total=v,
            bias2=b ** 2, common_var=common, indep_var=indep,
            frac_bias2=b ** 2 / mse, frac_common=common / mse,
            frac_indep=indep / mse,
            cov_err=cov, r_err=r, r_err_p=r_p,
            rmse=np.sqrt(mse),
            rmse_offset_cal=np.sqrt(v),
            rmse_affine_cal=np.sqrt(mse_cal),
            rmse_affine_loo=np.sqrt(mse_loo),
            cal_slope=cal[dev]["slope"], cal_intercept=cal[dev]["intercept"],
            # Composition of the floor that survives an affine calibration.
            cal_common_var=cov_cal, cal_indep_var=v_cal - cov_cal,
            cal_r_err=r_cal,
            frac_removable_offset=1.0 - v / mse,
            frac_removable_affine=1.0 - mse_cal / mse,
            frac_removable_affine_loo=1.0 - mse_loo / mse,
            # Floor if the instrument were made perfect but the stem and the
            # tape stayed as they are: only the common term survives.
            rmse_common_floor=np.sqrt(max(cov_cal, 0.0)),
            within_tol_raw=100.0 * np.mean(np.abs(e) <= TOLERANCE[measurand]),
            within_tol_cal=100.0 * np.mean(np.abs(res) <= TOLERANCE[measurand]),
            within_tol_loo=100.0 * np.mean(
                np.abs(cal[dev]["loo"]) <= TOLERANCE[measurand]),
            mean_reference=float(frame["reference"].mean()),
        ))
    return rows


rows = []
for m, f in EF.items():
    rows += budget(f, m, "Pooled")
    for site in core.SITES:
        s = f[f.site == site]
        if len(s) >= 5:
            rows += budget(s, m, site)
    rows += budget(f[~f.tape_disputed], m, "Pooled, tape-disputed excluded")

table = pd.DataFrame(rows)


# --------------------------------------------------------------------------
# How sure are we of the split? Paired bootstrap over stems.
#
# sigma_c^2 is a covariance estimated from ~100 stems and is the least stable
# quantity in the budget; quoting it without an interval would overstate it.
# --------------------------------------------------------------------------

def boot_split(frame: pd.DataFrame, measurand: str, n_boot: int = 5000,
               seed: int = 17) -> dict:
    rng = np.random.default_rng(seed)
    ei = frame["error_ios"].to_numpy(float)
    ea = frame["error_android"].to_numpy(float)
    n = len(ei)
    out = {d: {k: [] for k in ("bias2", "common", "indep", "removable")}
           for d in core.DEVICES}
    rs = []
    for _ in range(n_boot):
        idx = rng.integers(0, n, n)
        a, b = ei[idx], ea[idx]
        cov = np.cov(a, b, ddof=0)[0, 1]
        rs.append(cov / np.sqrt(a.var(ddof=0) * b.var(ddof=0)))
        for d, e in (("ios", a), ("android", b)):
            mse = (e ** 2).mean()
            out[d]["bias2"].append(e.mean() ** 2 / mse)
            out[d]["common"].append(cov / mse)
            out[d]["indep"].append((e.var(ddof=0) - cov) / mse)
            out[d]["removable"].append(e.mean() ** 2 / mse)
    ci = lambda v: tuple(np.percentile(v, [2.5, 97.5]))
    res = {"r": ci(rs)}
    for d in core.DEVICES:
        for k, v in out[d].items():
            res[(d, k)] = ci(v)
    return res


BOOT = {m: boot_split(EF[m], m) for m in core.MEASURANDS}
for m in core.MEASURANDS:
    for d in core.DEVICES:
        sel = (table.key == m) & (table.device_key == d) & (table.subset == "Pooled")
        for k, col in (("bias2", "frac_bias2"), ("common", "frac_common"),
                       ("indep", "frac_indep")):
            lo, hi = BOOT[m][(d, k)]
            table.loc[sel, f"{col}_ci_low"] = lo
            table.loc[sel, f"{col}_ci_high"] = hi

# Indexed views, taken AFTER the bootstrap columns exist so the caption can quote them.
POOLED = table[table.subset == "Pooled"].set_index(["key", "device_key"])
NODISP = table[table.subset.str.startswith("Pooled, tape")].set_index(
    ["key", "device_key"])
BY_SITE = table[table.subset.isin(core.SITES)].set_index(
    ["key", "device_key", "subset"])
# The subgroup that sits closest to the indep_var >= 0 boundary, quoted in the
# table caption as a warning about how hard the by-site rows can be pushed.
EDGE = BY_SITE.loc[("height", "android", "McDunn")]


# --------------------------------------------------------------------------
# Does a calibration TRANSFER? Fit at one site, apply at the other.
#
# The in-sample and leave-one-out numbers both re-use the stand the calibration
# came from. A shipped calibration has to work on a stand it has never seen, and
# this is the only test here that asks that question.
# --------------------------------------------------------------------------
transfer = []
TRANSFER_POOLED = {}
for m, f in EF.items():
    for dev in core.DEVICES:
        pooled_res, pooled_raw = [], []
        for fit_site, app_site in (("McDunn", "Starker"), ("Starker", "McDunn")):
            fit = f[f.site == fit_site]
            app = f[f.site == app_site]
            c = affine_calibration(fit[f"measured_{dev}"], fit["reference"])
            pred = c["intercept"] + c["slope"] * app[f"measured_{dev}"].to_numpy(float)
            res = pred - app["reference"].to_numpy(float)
            raw = app[f"error_{dev}"].to_numpy(float)
            own = affine_calibration(app[f"measured_{dev}"], app["reference"])
            pooled_res.append(res)
            pooled_raw.append(raw)
            transfer.append(dict(
                measurand=core.MEASURANDS[m]["short"], key=m,
                unit=core.MEASURANDS[m]["unit"],
                device=core.DEVICE_SHORT[dev], fit_site=fit_site,
                applied_site=app_site, n=len(app),
                rmse_raw=core.rmse(raw),
                rmse_transferred=core.rmse(res),
                rmse_refit_in_place=core.rmse(own["resid"]),
                bias_transferred=float(res.mean()),
                frac_removed=1.0 - (res ** 2).mean() / (raw ** 2).mean(),
            ))
        pr = np.concatenate(pooled_res)
        pw = np.concatenate(pooled_raw)
        TRANSFER_POOLED[(m, dev)] = dict(
            frac_removed=1.0 - (pr ** 2).mean() / (pw ** 2).mean(),
            rmse=float(np.sqrt((pr ** 2).mean())))
transfer = pd.DataFrame(transfer)


# --------------------------------------------------------------------------
# Is the common term really stem-level? Three checks the covariance cannot make
# on its own.
#
# The per-stem estimate of the shared effect is the mean of the two centred
# errors, c_hat(s) = ((e_i - b_i) + (e_a - b_a)) / 2. It is an estimate, not the
# quantity itself - it still carries (u_i + u_a)/2 - so it is used only for
# direction and shape, never for a variance.
# --------------------------------------------------------------------------
common_diag = {}
for m, f in EF.items():
    ei = f["error_ios"].to_numpy(float)
    ea = f["error_android"].to_numpy(float)
    ref = f["reference"].to_numpy(float)
    chat = ((ei - ei.mean()) + (ea - ea.mean())) / 2.0
    # (1) Does the shared error scale with the tree? If it did, an affine
    #     calibration would absorb it and it would not be a floor.
    rho_size, p_size = sps.spearmanr(ref, chat)
    rho_mag, p_mag = sps.spearmanr(ref, np.abs(chat))
    # (2) Do the two handsets err in the same DIRECTION on the same stem more
    #     often than a coin would? The sign test is distribution-free.
    same = ((ei - ei.mean()) * (ea - ea.mean())) > 0
    p_sign = sps.binomtest(int(same.sum()), len(same), 0.5).pvalue
    # (3) Is the covariance carried by a handful of stems? Share of the total
    #     cross-product contributed by the five largest contributors.
    cross = (ei - ei.mean()) * (ea - ea.mean())
    top5 = np.sort(cross)[-5:].sum() / cross.sum()
    common_diag[m] = dict(
        rho_size=rho_size, p_size=p_size, rho_mag=rho_mag, p_mag=p_mag,
        same_sign_pct=100.0 * same.mean(), p_sign=p_sign, top5_share=top5,
        cov_before=float(np.cov(ei, ea, ddof=0)[0, 1]))


# --------------------------------------------------------------------------
# Figure
# --------------------------------------------------------------------------
fig, axes = plt.subplots(2, 2, figsize=(core.FIG_W, core.FIG_H * 1.5))

COMPONENTS = [
    ("bias2",      "Bias$^2$",              1.00, ""),
    ("common_var", "Common variance",       0.52, "///"),
    ("indep_var",  "Independent variance",  0.20, "..."),
]
MARK = {"McDunn": "o", "Starker": "^"}

for j, m in enumerate(["dbh", "height"]):
    meta = core.MEASURANDS[m]
    u = meta["unit"]

    # ---- Top row: the stacked budget, raw and after an affine calibration ----
    ax = axes[0, j]
    xs, labels, devs, kinds = [], [], [], []
    for k, dev in enumerate(core.DEVICES):
        xs += [k * 2.6, k * 2.6 + 1.0]
        labels += [f"{core.DEVICE_SHORT[dev]}\nraw", f"{core.DEVICE_SHORT[dev]}\ncalib."]
        devs += [dev, dev]
        kinds += ["raw", "cal"]

    for x, dev, kind in zip(xs, devs, kinds):
        row = POOLED.loc[(m, dev)]
        if kind == "raw":
            parts = [row.bias2, row.common_var, row.indep_var]
        else:
            # Least squares leaves no mean offset, so the calibrated bar is all
            # variance; the affine fit is the strongest calibration on offer.
            parts = [0.0, row.cal_common_var, row.cal_indep_var]
        bottom = 0.0
        for (cname, _, alpha, hatch), val in zip(COMPONENTS, parts):
            ax.bar(x, val, bottom=bottom, width=0.86,
                   facecolor=core.PALETTE[dev], alpha=alpha,
                   edgecolor=core.PALETTE["reference"], linewidth=0.5,
                   hatch=hatch, zorder=3)
            bottom += val
        ax.text(x, bottom, f"{np.sqrt(bottom):.2f}", ha="center", va="bottom",
                fontsize=6.4, color=core.PALETTE["reference"], zorder=5)

    ax.set_xticks(xs)
    ax.set_xticklabels(labels, fontsize=7)
    ax.set_ylabel(f"Mean squared error ({u}$^2$)")
    ax.set_xlim(-0.75, max(xs) + 0.75)
    top = max(POOLED.loc[(m, d)].mse for d in core.DEVICES)
    ax.set_ylim(0, top * 1.68)
    ax.xaxis.grid(False)

    sec = ax.secondary_yaxis(
        "right", functions=(lambda v: np.sqrt(np.clip(v, 0, None)),
                            lambda v: np.asarray(v, float) ** 2))
    sec.set_ylabel(f"Root mean squared error ({u})", fontsize=8)
    sec.tick_params(labelsize=7)

    # The honest headline is a THREE-step ladder, and the third step is the one
    # that decides whether a calibration is shippable: fitted on the other stand.
    rem = {d: POOLED.loc[(m, d)].frac_removable_affine for d in core.DEVICES}
    floor = {d: POOLED.loc[(m, d)].rmse_affine_cal for d in core.DEVICES}
    xs_t = {d: TRANSFER_POOLED[(m, d)]["frac_removed"] for d in core.DEVICES}
    note = (f"calibration removes {100*rem['ios']:.0f} % (iOS) / "
            f"{100*rem['android']:.0f} % (Android) of MSE\n"
            f"floor {floor['ios']:.2f} / {floor['android']:.2f} {u} RMSE\n"
            f"fitted on the OTHER stand: {100*xs_t['ios']:+.0f} % / "
            f"{100*xs_t['android']:+.0f} %").replace("-", "−")
    ax.text(0.5, 0.995, note,
            transform=ax.transAxes, ha="center", va="top", fontsize=6.6, zorder=6,
            bbox=dict(boxstyle="round,pad=0.3", fc="white",
                      ec=core.PALETTE["grid"], lw=0.6, alpha=0.94))
    if j == 0:
        handles = [plt.Rectangle((0, 0), 1, 1, facecolor=core.PALETTE["muted"],
                                 alpha=a, hatch=h,
                                 edgecolor=core.PALETTE["reference"], linewidth=0.5)
                   for _, _, a, h in COMPONENTS]
        ax.legend(handles, [lab for _, lab, _, _ in COMPONENTS],
                  loc="upper left", bbox_to_anchor=(-0.01, 0.80),
                  fontsize=6.3, handlelength=1.5,
                  handletextpad=0.5, labelspacing=0.3, borderpad=0.2)
    core.panel_tag(ax, "A" if j == 0 else "B")

    # ---- Bottom row: where the common term comes from -----------------------
    ax = axes[1, j]
    f = EF[m]
    row_i = POOLED.loc[(m, "ios")]
    row_a = POOLED.loc[(m, "android")]
    lim_lo = min(f.error_ios.min(), f.error_android.min())
    lim_hi = max(f.error_ios.max(), f.error_android.max())
    pad = 0.08 * (lim_hi - lim_lo)
    lims = (lim_lo - pad, lim_hi + pad)
    ax.axhline(0, color=core.PALETTE["muted"], lw=0.7, ls=":", zorder=1)
    ax.axvline(0, color=core.PALETTE["muted"], lw=0.7, ls=":", zorder=1)
    ax.plot(lims, lims, color=core.PALETTE["reference"], lw=1.0, ls="--", zorder=2,
            label="identical error (purely common)")
    for site in core.SITES:
        s = f[f.site == site]
        ax.scatter(s.error_android, s.error_ios, s=20, marker=MARK[site],
                   facecolors="none", linewidths=0.9,
                   edgecolors=core.PALETTE["site"][site], zorder=3, label=site)
    ax.set_xlim(*lims)
    # Headroom for the stats box. Without it the box sits on top of the two
    # largest iOS diameter errors, which are exactly the stems a reader wants
    # to see; the aspect stays equal so the dashed line is still at 45 degrees.
    ax.set_ylim(lims[0], lims[0] + (lims[1] - lims[0]) * 1.26)
    ax.set_aspect("equal", adjustable="box")
    ax.set_xlabel(f"Android error, measured − tape ({u})")
    ax.set_ylabel(f"iOS error ({u})")
    lo_r, hi_r = BOOT[m]["r"]
    ax.text(0.03, 0.97,
            f"r = {row_i.r_err:.2f} ({lo_r:.2f}–{hi_r:.2f})\n"
            f"σ$_c$ = {np.sqrt(max(row_i.cov_err,0)):.2f} {u}\n"
            f"common = {100*row_i.frac_common:.0f} % / {100*row_a.frac_common:.0f} % of MSE",
            transform=ax.transAxes, va="top", ha="left", fontsize=6.8, zorder=6,
            bbox=dict(boxstyle="round,pad=0.3", fc="white",
                      ec=core.PALETTE["grid"], lw=0.6, alpha=0.94))
    if j == 0:
        ax.legend(loc="lower right", fontsize=6.2, handletextpad=0.4,
                  borderpad=0.2, labelspacing=0.25)
    core.panel_tag(ax, "C" if j == 0 else "D")

fig.tight_layout(w_pad=2.4, h_pad=1.6)

di, da = POOLED.loc[("dbh", "ios")], POOLED.loc[("dbh", "android")]
hi_, ha_ = POOLED.loc[("height", "ios")], POOLED.loc[("height", "android")]

caption = f"""
Error budget against the tape and laser, decomposed into the parts a developer can
act on separately. (A, B) Mean squared error of each handset for diameter at breast
height (in^2, n = {int(di.n)} stems) and total height (ft^2, n = {int(hi_.n)}; one iOS
height was typed rather than measured and its stem cannot enter a paired
decomposition), split into squared bias, the variance COMMON to both handsets on the
same stem, and the variance INDEPENDENT to each handset. The common term is estimated
as the covariance of the two devices' errors on the same stem, which under an additive
stem-effect model equals its variance; the independent term is each device's error
variance minus that covariance. The right-hand axis reads the same bars in RMSE, and
the number above each bar is the RMSE it corresponds to. "Calib." bars show what
survives an affine calibration (least squares of tape on handset, fitted on these same
stems and therefore optimistic). The third line of each box gives the figure that
decides whether a calibration is shippable - the same affine fit estimated on one
stand and applied to the other. Only iOS diameter keeps most of its gain that way
({100*TRANSFER_POOLED[('dbh','ios')]['frac_removed']:+.0f} % against
{100*di.frac_removable_affine:.0f} % in place); Android diameter keeps almost none
({100*TRANSFER_POOLED[('dbh','android')]['frac_removed']:+.0f} % against
{100*da.frac_removable_affine:.0f} %), and for height a transferred calibration makes
the error slightly worse on both handsets
({100*TRANSFER_POOLED[('height','ios')]['frac_removed']:+.0f} % and
{100*TRANSFER_POOLED[('height','android')]['frac_removed']:+.0f} %). Component is
encoded by fill pattern as well as tint so the stack reads in greyscale. (C, D) The
paired errors that produce the split: iOS error against Android error on the same
stem, with the dashed line marking errors that are identical and so entirely common.
Marker shape and colour both encode site. Diameter error is overwhelmingly private to
each handset - the two devices' errors correlate only r = {di.r_err:.2f} (95 %
bootstrap CI {BOOT['dbh']['r'][0]:.2f} to {BOOT['dbh']['r'][1]:.2f}), leaving
{100*di.frac_indep:.0f} % (iOS) and {100*da.frac_indep:.0f} % (Android) of MSE in the
instrument - whereas height error is substantially shared (r = {hi_.r_err:.2f},
{BOOT['height']['r'][0]:.2f} to {BOOT['height']['r'][1]:.2f};
sigma_c = {np.sqrt(hi_.cov_err):.1f} ft), pointing at the stem and the laser rather
than at either phone. Bias is a small part of the total everywhere
({100*di.frac_bias2:.0f} %, {100*da.frac_bias2:.0f} % for DBH;
{100*hi_.frac_bias2:.0f} %, {100*ha_.frac_bias2:.0f} % for height), so an offset
correction alone cannot make this method accurate. Two limits on how firmly the
common/independent split can be read. The DBH covariance is carried by very few
stems - the five largest cross-products supply
{100*common_diag['dbh']['top5_share']:.0f} % of it (height,
{100*common_diag['height']['top5_share']:.0f} %) - which is why the bootstrap interval
on the DBH common fraction runs from {100*di.frac_common_ci_low:.0f} % to
{100*di.frac_common_ci_high:.0f} %. And the shared error is not homoscedastic: its
magnitude grows with the tree for both measurands (Spearman rho
{common_diag['dbh']['rho_mag']:+.2f} and {common_diag['height']['rho_mag']:+.2f},
p = {common_diag['dbh']['p_mag']:.3f} and {common_diag['height']['p_mag']:.3f}), so a
single sigma_c summarises a quantity that is larger on large stems.
"""
core.save(fig, "fig09_budget", para(caption))


# --------------------------------------------------------------------------
# Table
# --------------------------------------------------------------------------
out = table.copy()
conv = {"in": core.CM_PER_IN, "ft": core.M_PER_FT}
out["metric_unit"] = out.unit.map({"in": "cm", "ft": "m"})
out["rmse_metric"] = [r.rmse * conv[r.unit] for r in out.itertuples()]
out["rmse_affine_cal_metric"] = [r.rmse_affine_cal * conv[r.unit]
                                 for r in out.itertuples()]
out["rmse_pct_of_mean"] = 100.0 * out.rmse / out.mean_reference
out["rmse_affine_cal_pct_of_mean"] = 100.0 * out.rmse_affine_cal / out.mean_reference
out["tolerance"] = out.key.map(TOLERANCE)

cols = ["measurand", "unit", "device", "subset", "n",
        "bias", "mse", "var_total",
        "bias2", "common_var", "indep_var",
        "frac_bias2", "frac_bias2_ci_low", "frac_bias2_ci_high",
        "frac_common", "frac_common_ci_low", "frac_common_ci_high",
        "frac_indep", "frac_indep_ci_low", "frac_indep_ci_high",
        "cov_err", "r_err", "r_err_p",
        "rmse", "rmse_metric", "rmse_pct_of_mean",
        "rmse_offset_cal", "rmse_affine_cal", "rmse_affine_loo",
        "rmse_affine_cal_metric", "rmse_affine_cal_pct_of_mean",
        "metric_unit",
        "frac_removable_offset", "frac_removable_affine",
        "frac_removable_affine_loo",
        "cal_slope", "cal_intercept",
        "cal_common_var", "cal_indep_var", "cal_r_err", "rmse_common_floor",
        "tolerance", "within_tol_raw", "within_tol_cal", "within_tol_loo",
        "mean_reference"]
out = out[cols]
for c in out.columns:
    if out[c].dtype.kind == "f":
        out[c] = out[c].round(4)

tcap = f"""
Error-budget components per measurand and device, pooled, by site, and with the
tape-disputed stems excluded as a sensitivity check. All moments are population
moments (ddof = 0) so that mse = bias2 + common_var + indep_var holds exactly.
`common_var` is Cov(e_iOS, e_Android) on the same stem, which equals the variance of a
shared additive stem effect under the model stated in the script header;
`indep_var` = var_total - common_var is what remains private to the handset. `r_err` is
the Pearson correlation of the two devices' errors and `cov_err` the covariance behind
it, repeated on both device rows because it is a property of the pair. Confidence
intervals on the three fractions are a seeded 5 000-draw paired bootstrap over stems
and are given for the pooled rows only; they are wide, and the common/independent
split should be read as an order-of-magnitude statement, not to two figures.
`rmse_offset_cal` is what survives deleting the mean bias; `rmse_affine_cal` what
survives a least-squares affine map from handset to tape (`cal_slope`,
`cal_intercept`), fitted and scored on the same stems and so optimistic;
`rmse_affine_loo` is the same fit scored by exact leave-one-out and is the honest
in-stand figure. `cal_common_var` and `cal_indep_var` decompose the post-calibration
floor the same way, and `rmse_common_floor` is what would remain if the instrument
were made perfect but the stem and the reference stayed as they are. `within_tol_*`
is the percentage of stems inside one tally-sheet recording increment
({TOLERANCE['dbh']:g} in for DBH, {TOLERANCE['height']:g} ft for height), before
calibration, after it, and under leave-one-out. Two cautions. Height uses
{int(hi_.n)} of the 100 stems because a paired decomposition needs both devices on the
same stem and one iOS height was typed rather than measured. And the affine
calibration cannot be assumed to transfer between stands: fitted on one stand and
applied to the other it removes {100*TRANSFER_POOLED[('dbh','ios')]['frac_removed']:.0f} %
(iOS) and {100*TRANSFER_POOLED[('dbh','android')]['frac_removed']:.0f} % (Android) of
DBH MSE against the {100*POOLED.loc[('dbh','ios')].frac_removable_affine:.0f} % and
{100*POOLED.loc[('dbh','android')].frac_removable_affine:.0f} % obtained in place, and
for height it removes nothing at all
({100*TRANSFER_POOLED[('height','ios')]['frac_removed']:+.0f} % and
{100*TRANSFER_POOLED[('height','android')]['frac_removed']:+.0f} %, i.e. it makes the
error worse). Finally, `common_var` is concentrated in few stems - the five largest
cross-products carry {100*common_diag['dbh']['top5_share']:.0f} % of the DBH covariance
and {100*common_diag['height']['top5_share']:.0f} % of the height covariance - and the
shared error grows with stem size (Spearman rho of |c_hat| on the reference
{common_diag['dbh']['rho_mag']:+.2f}, p = {common_diag['dbh']['p_mag']:.4f} for DBH;
{common_diag['height']['rho_mag']:+.2f}, p = {common_diag['height']['p_mag']:.4f} for
height), so the single common variance reported here is a stand-level average of a
size-dependent quantity. One boundary case to note when reading the by-site rows:
nothing constrains var_total - common_var to be non-negative, and Android height at
McDunn very nearly reaches that boundary (indep_var {EDGE.indep_var:.2f} ft^2 against a
total variance of {EDGE.var_total:.2f}), so its {100*EDGE.frac_common:.0f} %
common share should be read as "almost all of it" and not to the digit; a subgroup
that landed the other side of the boundary would have produced a negative variance
estimate and shown that the additive model is straining.
"""
core.save_table(out, "t09_budget", para(tcap))


# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
pd.set_option("display.width", 260, "display.max_columns", 80)
print(out[out.subset == "Pooled"].to_string(index=False))
print()
for m in core.MEASURANDS:
    meta = core.MEASURANDS[m]
    u = meta["unit"]
    for d in core.DEVICES:
        r = POOLED.loc[(m, d)]
        nd = NODISP.loc[(m, d)]
        print(f"{meta['short']:6s} {core.DEVICE_SHORT[d]:8s} n={int(r.n)}  "
              f"MSE {r.mse:7.3f} {u}^2 = bias^2 {r.bias2:6.3f} "
              f"({100*r.frac_bias2:4.1f}%) + common {r.common_var:7.3f} "
              f"({100*r.frac_common:4.1f}%) + indep {r.indep_var:7.3f} "
              f"({100*r.frac_indep:4.1f}%)")
        print(f"{'':16s}RMSE {r.rmse:.2f} -> offset {r.rmse_offset_cal:.2f} -> "
              f"affine {r.rmse_affine_cal:.2f} (LOO {r.rmse_affine_loo:.2f}) {u}; "
              f"removable {100*r.frac_removable_offset:.0f}% / "
              f"{100*r.frac_removable_affine:.0f}% / "
              f"{100*r.frac_removable_affine_loo:.0f}%")
        print(f"{'':16s}floor split: common {r.cal_common_var:.3f} + indep "
              f"{r.cal_indep_var:.3f} (r={r.cal_r_err:.2f}); instrument-perfect "
              f"floor {r.rmse_common_floor:.2f} {u}; within ±{TOLERANCE[m]:g} {u}: "
              f"{r.within_tol_raw:.0f}% -> {r.within_tol_cal:.0f}% "
              f"(LOO {r.within_tol_loo:.0f}%)")
        print(f"{'':16s}tape-disputed excluded (n={int(nd.n)}): MSE {nd.mse:.3f}, "
              f"bias^2 {100*nd.frac_bias2:.1f}%, common {100*nd.frac_common:.1f}%, "
              f"indep {100*nd.frac_indep:.1f}%, r={nd.r_err:.2f}, "
              f"affine floor {nd.rmse_affine_cal:.2f} {u}")
print()
print("IS THE COMMON TERM REALLY STEM-LEVEL?")
for m, c in common_diag.items():
    print(f"  {core.MEASURANDS[m]['short']:6s} c_hat vs size Spearman rho="
          f"{c['rho_size']:+.3f} (p={c['p_size']:.3f}); |c_hat| vs size rho="
          f"{c['rho_mag']:+.3f} (p={c['p_mag']:.4f}); both handsets err the same "
          f"way on {c['same_sign_pct']:.0f}% of stems (sign-test p={c['p_sign']:.2g}); "
          f"top 5 stems carry {100*c['top5_share']:.0f}% of the covariance")
print()
print("CROSS-SITE CALIBRATION TRANSFER (fit on one stand, apply to the other)")
print(transfer.round(3).to_string(index=False))
print()
for m in core.MEASURANDS:
    for d in core.DEVICES:
        t = transfer[(transfer.key == m) &
                     (transfer.device == core.DEVICE_SHORT[d])]
        u = core.MEASURANDS[m]["unit"]
        print(f"{core.MEASURANDS[m]['short']:6s} {core.DEVICE_SHORT[d]:8s} "
              f"transfer: RMSE raw {t.rmse_raw.mean():.2f} -> transferred "
              f"{t.rmse_transferred.mean():.2f} vs refit-in-place "
              f"{t.rmse_refit_in_place.mean():.2f} {u} "
              f"(mean over the two directions); MSE removed by a transferred "
              f"calibration {100*t.frac_removed.mean():.0f}%")
