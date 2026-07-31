#!/usr/bin/env python3
"""t05 — the inferential model.

Each stem is measured twice, once per handset, so DEVICE IS A WITHIN-STEM
FACTOR and the 200 diameter rows are 100 clusters of two, not 200 independent
observations. The primary model is therefore a linear mixed model,

    error ~ device * site * size_class,  random intercept for stem,

fitted separately for DBH (inches) and height (feet). Site and size class are
constant within a stem (between-stem); device varies within it. That split is
the whole point: the random intercept absorbs the stem-level common error, so
the within-stem contrast (device) is tested against the small residual and the
between-stem contrasts (site, size class) are tested against the much larger
stem variance. The naive factorial ANOVA over all 200 rows, reported alongside
in clearly-labelled columns, gets both of those wrong, and in opposite
directions.

Everything else in this script exists to keep that model honest: estimated
marginal contrasts in inches and feet rather than p-values alone, assumption
checks on the conditional residuals, a leave-one-stem-out influence pass, and —
because the constant-variance assumption fails in both measurands — a two-stage
per-stem analysis with heteroscedasticity-robust standard errors, sign-flip
permutation tests, rank tests and a cluster-robust GEE, plus a refit excluding
the disputed-tape stems.
"""
from __future__ import annotations

import core
plt = core.use_style()
df = core.load()

import math
import warnings

import numpy as np
import pandas as pd
import patsy
from scipy import stats as sps
import statsmodels.api as sm
import statsmodels.formula.api as smf

warnings.filterwarnings("ignore")
RNG_SEED = 17
N_SIGNFLIP = 10000
N_PERM = 5000

# --------------------------------------------------------------------------
# Size classes for the model.
#
# core.DBH_CLASSES / HEIGHT_CLASSES have six and five levels. Crossed with two
# sites and two devices that is 24 (resp. 20) cells, and the real cell counts
# run down to a single stem (McDunn 32+ in DBH, n = 1; McDunn 140+ ft, n = 1).
# A three-way interaction over those is not estimable in any useful sense. The
# classes are therefore collapsed to three per measurand at breaks a cruiser
# would still recognise, and the collapse is declared in the table caption and
# in the design block of the table itself.
# --------------------------------------------------------------------------
MODEL_CLASSES = {
    "dbh": [(0, 12, "0-12 in"), (12, 24, "12-24 in"), (24, 1e9, "24+ in")],
    "height": [(0, 80, "0-80 ft"), (80, 120, "80-120 ft"), (120, 1e9, "120+ ft")],
}

F_TREAT = "error ~ device * site * sclass"
F_SUM = "error ~ C(device, Sum) * C(site, Sum) * C(sclass, Sum)"
F_SUM_PCT = "pct_error ~ C(device, Sum) * C(site, Sum) * C(sclass, Sum)"


def add_class(sub, measurand):
    bins = MODEL_CLASSES[measurand]
    labels = [b[2] for b in bins]

    def lab(v):
        for lo, hi, name in bins:
            if lo <= v < hi:
                return name
        return labels[-1]

    sub = sub.copy()
    sub["sclass"] = pd.Categorical([lab(v) for v in sub.reference],
                                   categories=labels, ordered=True)
    return sub


PRETTY = {
    "Intercept": "intercept: iOS, McDunn, smallest class",
    "device[T.ios]": "device: iOS - Android (at McDunn, smallest class)",
    "site[T.Starker]": "site: Starker - McDunn (at iOS, smallest class)",
}


def pretty(term):
    if term in PRETTY:
        return PRETTY[term]
    return (term.replace("device[T.ios]", "iOS")
                .replace("site[T.Starker]", "Starker")
                .replace("sclass[T.", "size ")
                .replace("]", "")
                .replace(":", " x "))


# --------------------------------------------------------------------------
# Model machinery
# --------------------------------------------------------------------------

def fit_mixed(sub, formula):
    """REML fit, walking down a list of optimisers until one converges.

    The default pass hits a singular X'V^-1X on some designs even though the
    fixed-effect design matrix is full rank; Powell and Nelder-Mead recover the
    same optimum there, so this chain is a numerical fallback, not a change of
    model.
    """
    last = None
    for meth in (None, "powell", "nm", "cg"):
        try:
            md = smf.mixedlm(formula, data=sub, groups=sub["stem"])
            res = md.fit(reml=True) if meth is None else md.fit(reml=True, method=meth)
            if np.all(np.isfinite(np.asarray(res.fe_params, float))):
                return res
        except Exception as exc:                        # noqa: BLE001
            last = exc
    raise RuntimeError("MixedLM failed to converge: %r" % last)


def fixed_cov(res):
    """Top-left fixed-effect block of the parameter covariance."""
    k = len(res.fe_params)
    cov = res.cov_params()
    cov = cov.values if hasattr(cov, "values") else np.asarray(cov)
    return np.asarray(cov, float)[:k, :k]


def joint_wald(fe, cov, design_info):
    """Type-III-style joint Wald chi-square per model term (sum coding)."""
    out = {}
    for term, sl in design_info.term_name_slices.items():
        if term == "Intercept":
            continue
        idx = list(range(sl.start, sl.stop))
        b = np.asarray(fe, float)[idx]
        V = cov[np.ix_(idx, idx)]
        try:
            chi2 = float(b @ np.linalg.solve(V, b))
        except np.linalg.LinAlgError:
            continue
        out[term] = (chi2, len(idx), float(sps.chi2.sf(chi2, len(idx))))
    return out


def cell_grid(sub):
    """One row per device x site x size-class cell, for marginal contrasts."""
    rows = [(dev, site, sc)
            for dev in ("ios", "android")
            for site in core.SITES
            for sc in list(sub.sclass.cat.categories)]
    return pd.DataFrame(rows, columns=["device", "site", "sclass"])


def cell_design(design_info, grid):
    return np.asarray(patsy.build_design_matrices([design_info], grid,
                                                  return_type="dataframe")[0], float)


def contrast_vectors(design_info, grid):
    """L vectors for the estimated marginal contrasts we want to report.

    Cells are weighted equally (an estimated-marginal-means contrast), not by
    the number of stems in them, so the site comparison is not silently a size
    comparison — Starker holds most of the large stems.
    """
    X = cell_design(design_info, grid)
    g = grid
    Ls = {}

    def avg(mask):
        return X[np.asarray(mask, bool)].mean(axis=0)

    Ls["device: iOS - Android"] = avg(g.device == "ios") - avg(g.device == "android")
    for sc in g.sclass.unique():
        Ls[f"device: iOS - Android within {sc}"] = (
            avg((g.device == "ios") & (g.sclass == sc))
            - avg((g.device == "android") & (g.sclass == sc)))
    for site in core.SITES:
        Ls[f"device: iOS - Android at {site}"] = (
            avg((g.device == "ios") & (g.site == site))
            - avg((g.device == "android") & (g.site == site)))
    Ls["site: Starker - McDunn"] = (avg(g.site == "Starker")
                                    - avg(g.site == "McDunn"))
    cats = list(g.sclass.unique())
    Ls[f"size: {cats[-1]} - {cats[0]}"] = avg(g.sclass == cats[-1]) - avg(g.sclass == cats[0])
    Ls[f"size: {cats[1]} - {cats[0]}"] = avg(g.sclass == cats[1]) - avg(g.sclass == cats[0])
    return Ls


def contrast(fe, cov, L):
    est = float(np.asarray(L) @ np.asarray(fe, float))
    var = float(np.asarray(L) @ cov @ np.asarray(L))
    se = math.sqrt(max(var, 0.0))
    z = est / se if se > 0 else np.nan
    return dict(estimate=est, se=se, z=z, p=2 * sps.norm.sf(abs(z)),
                ci_low=est - 1.96 * se, ci_high=est + 1.96 * se)


def conditional_residuals(res, sub):
    """y - Xb - u_stem: the residual whose distribution the model assumes."""
    re = res.random_effects
    u = np.array([float(np.asarray(re[s]).ravel()[0]) for s in sub["stem"]])
    marg = np.asarray(res.fittedvalues, float)
    y = np.asarray(sub[res.model.endog_names], float)
    return y - marg - u, marg + u, u


# --------------------------------------------------------------------------
# Distribution-free and robust alternatives
# --------------------------------------------------------------------------

def per_stem(sub):
    """Two derived per-stem quantities, each with ONE independent value/stem.

    `diff` (iOS - Android) carries every within-stem effect: the device main
    effect and all its interactions. `mean_err` carries the between-stem
    effects: site, size class and their interaction. Analysing these two
    separately is the classical two-stage treatment of a split-plot, is exactly
    equivalent to the mixed model when the design is balanced, and — because
    each row is then one independent stem — can be given HC3 robust standard
    errors, which the mixed model cannot.
    """
    w = sub.pivot_table(index=["stem", "site", "sclass"], columns="device",
                        values="error", aggfunc="first", observed=True).reset_index()
    w["mean_err"] = w[["ios", "android"]].mean(axis=1)
    w["diff"] = w["ios"] - w["android"]
    return w


def wald_subset(fit, idx):
    R = np.zeros((len(idx), len(fit.params)))
    for j, i in enumerate(idx):
        R[j, i] = 1.0
    wt = fit.wald_test(R, use_f=False, scalar=True)
    return float(wt.statistic), len(idx), float(wt.pvalue)


def hc3_terms(response, data):
    """OLS with HC3 SEs on independent per-stem rows + Type-III joint tests.

    SUM CODING is not cosmetic here. Under treatment coding the coefficient
    block named `site` is the site effect inside the REFERENCE size class only,
    so testing it answers a question nobody asked and disagrees with every other
    site test in this script. Sum coding makes it the main effect, averaged over
    classes, which is what the mixed model's Type-III Wald and the permutation
    test are both testing.

    Also returns the JOINT test of each factor together with its interaction,
    because that — not the main effect — is the null the uncentred permutation
    test evaluates.
    """
    formula = "%s ~ C(site, Sum) * C(sclass, Sum)" % response
    fit = smf.ols(formula, data=data).fit(cov_type="HC3")
    di = fit.model.data.design_info

    def plain(t):
        return t.replace("C(", "").replace(", Sum)", "")

    out, slices = {}, {}
    for term, sl in di.term_name_slices.items():
        if term == "Intercept":
            continue
        slices[plain(term)] = list(range(sl.start, sl.stop))
        out[plain(term)] = wald_subset(fit, slices[plain(term)])
    for term in ("site", "sclass"):
        idx = sorted(set(slices.get(term, [])) | set(slices.get("site:sclass", [])))
        if idx:
            out[term + " (joint with interaction)"] = wald_subset(fit, idx)
    return fit, out


def perm_labels(y, labels, strata, rng, n=N_PERM, center=True):
    """Permutation F for a between-stem factor, labels shuffled within strata.

    With `center` the response is first centred within stratum, so the
    statistic is the stratum-adjusted (main-effect) F — the analogue of the
    Type-III test. Without it the statistic is the raw one-way F, which is
    sensitive to the factor's interaction with the stratum as well, i.e. it
    tests the joint null 'this factor has no effect at all'. Both are exact
    under label exchangeability within stratum and need neither normality nor
    equal variance. The distinction matters here: site and size class are badly
    confounded, so the two nulls are not close to each other.
    """
    y = np.asarray(y, float)
    lab = np.asarray(labels)
    strata = np.asarray(strata) if strata is not None else np.zeros(len(y), int)
    if center:
        y = y.copy()
        for u in np.unique(strata):
            m = strata == u
            y[m] -= y[m].mean()

    def F(l):
        groups = [y[l == u] for u in np.unique(l)]
        groups = [g for g in groups if len(g) > 1]
        if len(groups) < 2:
            return np.nan
        return float(sps.f_oneway(*groups).statistic)

    obs = F(lab)
    null = np.empty(n)
    for k in range(n):
        perm = lab.copy()
        for u in np.unique(strata):
            m = strata == u
            perm[m] = rng.permutation(lab[m])
        null[k] = F(perm)
    null = null[~np.isnan(null)]
    return obs, (np.sum(null >= obs - 1e-12) + 1) / (len(null) + 1)


def holm(pvals):
    """Holm step-down adjustment; the per-class contrasts are a family of 3."""
    p = np.asarray(pvals, float)
    order = np.argsort(p)
    adj = np.empty_like(p)
    running = 0.0
    for rank, i in enumerate(order):
        running = max(running, (len(p) - rank) * p[i])
        adj[i] = min(1.0, running)
    return adj


def signflip(d, rng, n=N_SIGNFLIP, w=None):
    """Sign-flip permutation test on the within-stem paired difference.

    Under the null of no device effect the paired difference is symmetric about
    zero, so its sign is exchangeable. Needs no normality and no equal variance
    between stems. `w` lets the statistic be a weighted mean, which is how the
    equally-weighted-cell (EMM) contrast gets an exact p-value rather than one
    that assumes constant residual variance.
    """
    d = np.asarray(d, float)
    w = np.full(len(d), 1.0 / len(d)) if w is None else np.asarray(w, float)
    obs = float(w @ d)
    signs = rng.choice([-1.0, 1.0], size=(n, len(d)))
    null = (signs * d) @ w
    return obs, (np.sum(np.abs(null) >= abs(obs) - 1e-12) + 1) / (n + 1)


def emm_weights(frame, keys):
    """Weights that give every non-empty cell of `keys` equal influence."""
    g = frame.assign(_one=1).groupby(keys, observed=True)["_one"]
    k = g.ngroup().nunique()
    return (1.0 / (k * g.transform("size"))).values


# --------------------------------------------------------------------------
# Per-measurand analysis
# --------------------------------------------------------------------------

rows = []
diag = {}
nv = {}


def add(measurand, unit, block, term, term_pretty, estimate=np.nan, se=np.nan,
        stat=np.nan, stat_type="", p_value=np.nan, ci_low=np.nan, ci_high=np.nan,
        naive_estimate=np.nan, naive_se=np.nan, naive_p=np.nan, note=""):
    rows.append(dict(measurand=measurand, unit=unit, block=block, term=term,
                     term_pretty=term_pretty, estimate=estimate, se=se, stat=stat,
                     stat_type=stat_type, p_value=p_value, ci_low=ci_low,
                     ci_high=ci_high, naive_estimate=naive_estimate,
                     naive_se=naive_se, naive_p=naive_p, note=note))


for measurand in ("dbh", "height"):
    meta = core.MEASURANDS[measurand]
    short, unit = meta["short"], meta["unit"]
    sub = add_class(df[df.measurand == measurand], measurand)
    sub = sub.sort_values(["stem", "device"]).reset_index(drop=True)
    rng = np.random.default_rng(RNG_SEED)
    n_obs, n_stem = len(sub), sub.stem.nunique()

    # ---- 0. the design a reviewer needs to see ------------------------------
    wide0 = sub.pivot_table(index=["stem", "site", "sclass"], columns="device",
                            values="error", aggfunc="first",
                            observed=True).reset_index()
    counts = wide0.groupby(["site", "sclass"], observed=True).stem.nunique()
    paired_counts = (wide0.dropna(subset=["ios", "android"])
                     .groupby(["site", "sclass"], observed=True).stem.nunique())
    for (site, sc), c in counts.items():
        pc = int(paired_counts.get((site, sc), 0))
        add(short, unit, "0 design", f"{site} / {sc}", f"{site}, {sc}",
            estimate=float(c), stat=float(pc), stat_type="stems (paired stems)",
            note="stems in this between-stem cell; %d of them measured by both "
                 "handsets" % pc)
    add(short, unit, "0 design", "observations", "rows entering the model",
        estimate=float(n_obs), stat=float(n_stem), stat_type="rows (stems)",
        note="typed readings already excluded by the loader")

    # Site and size class are badly confounded: Starker is the big-tree stand.
    # A reviewer needs the number, not the assurance.
    ct = pd.crosstab(wide0.site, wide0.sclass)
    chi2_c = sps.chi2_contingency(ct.values)[0]
    cramer = math.sqrt(chi2_c / (ct.values.sum() * (min(ct.shape) - 1)))
    add(short, unit, "0 design", "Cramer V, site vs size class",
        "confounding between the two between-stem factors", estimate=float(cramer),
        stat=float(chi2_c), stat_type="chi2",
        note="1.0 would mean site and size class are the same variable")
    Xv = patsy.dmatrix("site + sclass", wide0, return_type="dataframe")
    from statsmodels.stats.outliers_influence import variance_inflation_factor as vif
    for j, nm in enumerate(Xv.columns):
        if nm == "Intercept":
            continue
        add(short, unit, "0 design", "VIF %s" % nm, "variance inflation, %s" % nm,
            estimate=float(vif(Xv.values, j)), stat_type="VIF",
            note="additive per-stem design; >5 means the two factors cannot be "
                 "cleanly separated")

    # ---- 1. mixed model, treatment coding (readable coefficients) -----------
    mx_t = fit_mixed(sub, F_TREAT)
    ols_t = smf.ols(F_TREAT, data=sub).fit()      # the trap: same design, no
                                                  # clustering, 200 "independent" rows
    cov_t = fixed_cov(mx_t)
    se_t = np.sqrt(np.diag(cov_t))
    for i, nm in enumerate(mx_t.fe_params.index):
        est, se = float(mx_t.fe_params.iloc[i]), float(se_t[i])
        z = est / se if se else np.nan
        add(short, unit, "1 fixed effect (mixed)", nm, pretty(nm),
            estimate=est, se=se, stat=z, stat_type="z",
            p_value=2 * sps.norm.sf(abs(z)),
            ci_low=est - 1.96 * se, ci_high=est + 1.96 * se,
            naive_estimate=float(ols_t.params.get(nm, np.nan)),
            naive_se=float(ols_t.bse.get(nm, np.nan)),
            naive_p=float(ols_t.pvalues.get(nm, np.nan)),
            note="naive_* = OLS on all %d rows, pairing ignored" % n_obs)

    # ---- 2. marginal contrasts, in the measurand's own units ---------------
    mx_s = fit_mixed(sub, F_SUM)
    ols_s = smf.ols(F_SUM, data=sub).fit()
    di = mx_s.model.data.design_info
    grid = cell_grid(sub)
    Ls = contrast_vectors(di, grid)
    cov_s = fixed_cov(mx_s)
    cov_ols = np.asarray(ols_s.cov_params(), float)

    emm = {}
    for name, L in Ls.items():
        c = contrast(mx_s.fe_params, cov_s, L)
        cn = contrast(ols_s.params, cov_ols, L)
        emm[name] = c
        add(short, unit, "2 marginal contrast", name, name,
            estimate=c["estimate"], se=c["se"], stat=c["z"], stat_type="z",
            p_value=c["p"], ci_low=c["ci_low"], ci_high=c["ci_high"],
            naive_estimate=cn["estimate"], naive_se=cn["se"], naive_p=cn["p"],
            note="cells weighted equally (EMM); naive_* ignores pairing")

    # ---- 3. factor tests: mixed Wald vs the naive ANOVA --------------------
    aov = sm.stats.anova_lm(ols_s, typ=3)
    wald = joint_wald(mx_s.fe_params, cov_s, di)
    for term, (chi2, k, p) in wald.items():
        plain = term.replace("C(", "").replace(", Sum)", "")
        arow = aov.loc[term] if term in aov.index else None
        add(short, unit, "3 factor test", plain, plain.replace(":", " x "),
            stat=chi2, stat_type="Wald chi2 (df %d)" % k, p_value=p,
            naive_estimate=float(arow["F"]) if arow is not None else np.nan,
            naive_p=float(arow["PR(>F)"]) if arow is not None else np.nan,
            note="naive_estimate = ANOVA F on %d rows (resid df %d); naive_p its p"
                 % (n_obs, int(ols_s.df_resid)))

    ow = smf.ols("error ~ C(device)", data=sub).fit()
    ow_aov = sm.stats.anova_lm(ow, typ=2)
    add(short, unit, "3 factor test", "device (one-way, pairing ignored)",
        "device, naive one-way ANOVA",
        naive_estimate=float(ow_aov.loc["C(device)", "F"]),
        naive_p=float(ow_aov.loc["C(device)", "PR(>F)"]),
        note="the simplest wrong analysis: one-way over all %d rows, resid df %d"
             % (n_obs, int(ow.df_resid)))

    # ---- 4. variance components and ICC ------------------------------------
    gvar, rvar = float(mx_s.cov_re.iloc[0, 0]), float(mx_s.scale)
    icc = gvar / (gvar + rvar)
    for nm, val, note in [
        ("random intercept variance (stem)", gvar, "sigma2_stem, %s^2" % unit),
        ("residual variance", rvar, "sigma2_resid, %s^2" % unit),
        ("stem SD", math.sqrt(gvar), unit),
        ("residual SD", math.sqrt(rvar), unit),
        ("ICC", icc, "sigma2_stem / (sigma2_stem + sigma2_resid)"),
    ]:
        add(short, unit, "4 variance component", nm, nm, estimate=val, note=note)

    # ---- 5. assumptions -----------------------------------------------------
    cres, cfit, blup = conditional_residuals(mx_s, sub)
    re_vals = np.array([float(np.asarray(v).ravel()[0])
                        for v in mx_s.random_effects.values()])
    sw, sw_re = sps.shapiro(cres), sps.shapiro(re_vals)
    cell = (sub.device.astype(str) + "|" + sub.site.astype(str) + "|"
            + sub.sclass.astype(str))
    lev_cell = sps.levene(*[cres[cell.values == u] for u in cell.unique()
                            if (cell.values == u).sum() > 1], center="median")
    lev_dev = sps.levene(*[cres[sub.device.values == u] for u in ("ios", "android")],
                         center="median")
    lev_site = sps.levene(*[cres[sub.site.values == u] for u in core.SITES],
                          center="median")
    lev_size = sps.levene(*[cres[sub.sclass.astype(str).values == u]
                            for u in sub.sclass.cat.categories], center="median")
    sp_rho, sp_p = sps.spearmanr(sub.reference.values, np.abs(cres))

    for nm, stat, p, note in [
        ("Shapiro-Wilk, conditional residuals", sw.statistic, sw.pvalue,
         "normality of the residual term"),
        ("Shapiro-Wilk, stem random effects", sw_re.statistic, sw_re.pvalue,
         "normality of the random intercepts (BLUPs)"),
        ("Levene, device x site x size cells", lev_cell.statistic, lev_cell.pvalue,
         "homogeneity across all 12 model cells"),
        ("Levene, device", lev_dev.statistic, lev_dev.pvalue, "iOS vs Android"),
        ("Levene, site", lev_site.statistic, lev_site.pvalue, "McDunn vs Starker"),
        ("Levene, size class", lev_size.statistic, lev_size.pvalue,
         "across the three size classes"),
        ("Spearman |residual| vs reference", sp_rho, sp_p,
         "spread grows with stem size if positive"),
    ]:
        add(short, unit, "5 assumption", nm, nm, stat=float(stat),
            stat_type="statistic", p_value=float(p), note=note)

    # ---- 6. influence: leave one stem out -----------------------------------
    L_dev = Ls["device: iOS - Android"]
    base = contrast(mx_s.fe_params, cov_s, L_dev)
    loo = []
    for s in sorted(sub.stem.unique()):
        d2 = sub[sub.stem != s]
        try:
            m2 = fit_mixed(d2, F_SUM)
            L2 = contrast_vectors(m2.model.data.design_info, grid)["device: iOS - Android"]
            v = contrast(m2.fe_params, fixed_cov(m2), L2)["estimate"]
        except Exception:                                # noqa: BLE001
            v = np.nan
        loo.append((s, v))
    loo = pd.DataFrame(loo, columns=["stem", "dev_effect"])
    loo["dfbeta"] = (loo.dev_effect - base["estimate"]) / base["se"]
    worst = loo.iloc[loo.dfbeta.abs().values.argmax()]
    n_big = int((loo.dfbeta.abs() > 2 / math.sqrt(n_stem)).sum())
    add(short, unit, "6 influence", "max |DFBETA|, device contrast",
        "most influential stem: %s" % worst.stem,
        estimate=float(worst.dev_effect - base["estimate"]),
        stat=float(abs(worst.dfbeta)), stat_type="DFBETA (SE units)",
        note="leave-one-stem-out; device contrast %.3f %s, becomes %.3f without "
             "%s; %d of %d stems exceed the 2/sqrt(n) = %.2f cutoff"
             % (base["estimate"], unit, worst.dev_effect, worst.stem, n_big,
                n_stem, 2 / math.sqrt(n_stem)))

    # ---- 7. two-stage per-stem analysis with HC3, and permutation tests -----
    ps = per_stem(sub)
    ps_paired = ps.dropna(subset=["ios", "android"])
    fit_d, terms_d = hc3_terms("Q('diff')", ps_paired)
    fit_m, terms_m = hc3_terms("mean_err", ps)

    # Whether the between-stem permutation tests are even valid depends on the
    # per-stem quantities having equal spread across the groups whose labels are
    # shuffled: exchangeability, not just equal means, is the null being imposed.
    # The within-stem sign-flip test carries no such requirement, because each
    # stem's own difference is flipped in place.
    lv_d_size = sps.levene(*[ps_paired["diff"][ps_paired.sclass.astype(str) == u]
                             for u in sub.sclass.cat.categories], center="median")
    lv_m_size = sps.levene(*[ps.mean_err[ps.sclass.astype(str) == u]
                             for u in sub.sclass.cat.categories], center="median")
    lv_m_site = sps.levene(*[ps.mean_err[ps.site == u] for u in core.SITES],
                           center="median")
    for nm, lv, note in [
        ("Levene, paired difference by size class", lv_d_size,
         "exchangeability check for the device x size permutation"),
        ("Levene, per-stem mean error by size class", lv_m_size,
         "exchangeability check for the size-class permutation"),
        ("Levene, per-stem mean error by site", lv_m_site,
         "exchangeability check for the site permutation"),
    ]:
        add(short, unit, "5 assumption", nm, nm, stat=float(lv.statistic),
            stat_type="statistic", p_value=float(lv.pvalue), note=note)
    perm_ok = min(lv_m_size.pvalue, lv_m_site.pvalue) > 0.05
    perm_caveat = ("" if perm_ok else " CAUTION: spread differs across these "
                   "groups (see block 5), so label exchangeability fails and "
                   "this permutation p is not exact; prefer the HC3 test")

    obs_d, p_flip = signflip(ps_paired["diff"].values, rng)
    wil = sps.wilcoxon(ps_paired["ios"].values, ps_paired["android"].values)
    F_ds, p_ds = perm_labels(ps_paired["diff"], ps_paired.sclass.astype(str),
                             ps_paired.site.astype(str), rng)
    F_dt, p_dt = perm_labels(ps_paired["diff"], ps_paired.site.astype(str),
                             ps_paired.sclass.astype(str), rng)
    F_ms, p_ms = perm_labels(ps.mean_err, ps.sclass.astype(str),
                             ps.site.astype(str), rng)
    F_mt, p_mt = perm_labels(ps.mean_err, ps.site.astype(str),
                             ps.sclass.astype(str), rng)
    _, p_ms_j = perm_labels(ps.mean_err, ps.sclass.astype(str),
                            ps.site.astype(str), rng, center=False)
    _, p_mt_j = perm_labels(ps.mean_err, ps.site.astype(str),
                            ps.sclass.astype(str), rng, center=False)
    mw = sps.mannwhitneyu(ps.mean_err[ps.site == "McDunn"],
                          ps.mean_err[ps.site == "Starker"])
    kw = sps.kruskal(*[ps.mean_err[ps.sclass.astype(str) == u]
                       for u in sub.sclass.cat.categories])

    se_flip = ps_paired["diff"].std(ddof=1) / math.sqrt(len(ps_paired))
    add(short, unit, "7 robust alternative", "device contrast, sign-flip permutation",
        "device: iOS - Android (%d paired stems)" % len(ps_paired),
        estimate=float(obs_d), se=float(se_flip), p_value=float(p_flip),
        stat_type="mean paired difference",
        ci_low=float(obs_d - 1.96 * se_flip), ci_high=float(obs_d + 1.96 * se_flip),
        note="%d sign-flips; exact under symmetry. NOTE this is the plain sample "
             "mean of the paired difference, so cells are weighted by how many "
             "stems they hold; the block 2 contrast weights them equally"
             % N_SIGNFLIP)

    # per-size-class device contrast: same estimand as block 2, but HC3 SE on
    # one independent difference per stem, and an exact within-class sign-flip
    # of the SAME weighted statistic, so the two p-values answer one question.
    grid2 = grid[["site", "sclass"]].drop_duplicates().reset_index(drop=True)
    X2 = cell_design(fit_d.model.data.design_info, grid2)
    cov_d = np.asarray(fit_d.cov_params(), float)
    perclass = []
    for sc in list(sub.sclass.cat.categories):
        L2 = X2[(grid2.sclass == sc).values].mean(axis=0)
        c2 = contrast(fit_d.params, cov_d, L2)
        m = (ps_paired.sclass.astype(str) == sc).values
        dsub = ps_paired.loc[m]
        wsub = emm_weights(dsub, ["site"])
        est_w, pf = signflip(dsub["diff"].values, rng, w=wsub)
        est_raw, pf_raw = signflip(dsub["diff"].values, rng)
        perclass.append((sc, c2, float(pf), int(m.sum()), float(est_raw),
                         float(pf_raw), float(est_w)))
    holm_p = holm([c[2] for c in perclass])
    for (sc, c2, pf, nsc, est_raw, pf_raw, est_w), hp in zip(perclass, holm_p):
        add(short, unit, "7 robust alternative",
            "device contrast within %s (HC3 + sign-flip)" % sc,
            "device: iOS - Android within %s" % sc, estimate=c2["estimate"],
            se=c2["se"], stat=c2["z"], stat_type="z (HC3)", p_value=c2["p"],
            ci_low=c2["ci_low"], ci_high=c2["ci_high"], naive_p=float(hp),
            note="%d paired stems; equal site weights, sign-flip p %.4g, Holm "
                 "across the three classes %.4g (naive_p). Sample-weighted mean "
                 "difference %.3f %s, sign-flip p %.4g"
                 % (nsc, pf, hp, est_raw, unit, pf_raw))
    L_all = X2.mean(axis=0)
    c_all = contrast(fit_d.params, cov_d, L_all)
    w_all = emm_weights(ps_paired, ["site", "sclass"])
    est_all_w, p_all_w = signflip(ps_paired["diff"].values, rng, w=w_all)
    add(short, unit, "7 robust alternative", "device contrast, HC3 two-stage",
        "device: iOS - Android (equal cell weights, HC3)",
        estimate=c_all["estimate"], se=c_all["se"], stat=c_all["z"],
        stat_type="z (HC3)", p_value=c_all["p"], ci_low=c_all["ci_low"],
        ci_high=c_all["ci_high"],
        note="same estimand as block 2 but with a heteroscedasticity-robust SE "
             "on one independent difference per stem; the exact sign-flip test "
             "of this same weighted statistic gives %.3f %s, p %.4g"
             % (est_all_w, unit, p_all_w))

    # robust versions of the BETWEEN-stem contrasts of block 2
    cov_m = np.asarray(fit_m.cov_params(), float)
    Xm = cell_design(fit_m.model.data.design_info, grid2)
    cats = list(sub.sclass.cat.categories)
    for nm, L in [
        ("site: Starker - McDunn",
         Xm[(grid2.site == "Starker").values].mean(axis=0)
         - Xm[(grid2.site == "McDunn").values].mean(axis=0)),
        (f"size: {cats[-1]} - {cats[0]}",
         Xm[(grid2.sclass == cats[-1]).values].mean(axis=0)
         - Xm[(grid2.sclass == cats[0]).values].mean(axis=0)),
        (f"size: {cats[1]} - {cats[0]}",
         Xm[(grid2.sclass == cats[1]).values].mean(axis=0)
         - Xm[(grid2.sclass == cats[0]).values].mean(axis=0)),
    ]:
        cm = contrast(fit_m.params, cov_m, L)
        add(short, unit, "7 robust alternative", "%s (HC3 on per-stem mean)" % nm,
            nm, estimate=cm["estimate"], se=cm["se"], stat=cm["z"],
            stat_type="z (HC3)", p_value=cm["p"], ci_low=cm["ci_low"],
            ci_high=cm["ci_high"],
            note="same estimand as block 2, robust SE on one row per stem")

    # does mean error depend on the between-stem cell AT ALL? one test, six cells
    cellstr = (ps.site.astype(str) + "|" + ps.sclass.astype(str)).values
    F_cell, p_cell = perm_labels(ps.mean_err, cellstr, None, rng, center=False)
    idx_all = [i for i, nm in enumerate(fit_m.params.index) if nm != "Intercept"]
    st_all, k_all, p_all = wald_subset(fit_m, idx_all)
    add(short, unit, "7 robust alternative", "any between-stem structure, permutation",
        "mean error depends on site x size cell", stat=float(F_cell), stat_type="F",
        p_value=float(p_cell),
        note="%d permutations of the six-cell label over stems; one test instead "
             "of trying to separate two strongly associated factors.%s"
             % (N_PERM, perm_caveat))
    add(short, unit, "7 robust alternative", "any between-stem structure, HC3",
        "mean error depends on site x size cell", stat=st_all,
        stat_type="Wald chi2 (df %d)" % k_all, p_value=p_all,
        note="all non-intercept terms of the per-stem model tested jointly")
    add(short, unit, "7 robust alternative", "device, Wilcoxon signed-rank",
        "device, rank-based", stat=float(wil.statistic), stat_type="W",
        p_value=float(wil.pvalue),
        note="tests the median paired difference, not the mean")
    for lbl, (st, k, p), src in [
        ("device x size class (HC3 on paired differences)", terms_d.get("sclass", (np.nan,) * 3), "d"),
        ("device x site (HC3 on paired differences)", terms_d.get("site", (np.nan,) * 3), "d"),
        ("device x site x size (HC3 on paired differences)",
         terms_d.get("site:sclass", (np.nan,) * 3), "d"),
        ("device x size, joint with 3-way (HC3)",
         terms_d.get("sclass (joint with interaction)", (np.nan,) * 3), "d"),
        ("site (HC3 on per-stem mean error)", terms_m.get("site", (np.nan,) * 3), "m"),
        ("size class (HC3 on per-stem mean error)", terms_m.get("sclass", (np.nan,) * 3), "m"),
        ("site x size (HC3 on per-stem mean error)",
         terms_m.get("site:sclass", (np.nan,) * 3), "m"),
        ("site, joint with interaction (HC3)",
         terms_m.get("site (joint with interaction)", (np.nan,) * 3), "m"),
        ("size class, joint with interaction (HC3)",
         terms_m.get("sclass (joint with interaction)", (np.nan,) * 3), "m"),
    ]:
        add(short, unit, "7 robust alternative", lbl, lbl, stat=st,
            stat_type="Wald chi2 (df %s)" % k, p_value=p,
            note="one independent row per stem (%d); HC3 sandwich SE, so unequal "
                 "variance across cells is allowed" %
                 (len(ps_paired) if src == "d" else len(ps)))
    for lbl, F, p, note in [
        ("device x size class, permutation", F_ds, p_ds,
         "size labels shuffled within site, differences centred within site"),
        ("device x site, permutation", F_dt, p_dt,
         "site labels shuffled within size class"),
        ("size class, permutation, adjusted (per-stem mean error)", F_ms, p_ms,
         "size labels shuffled within site, errors centred within site: the "
         "main-effect null"),
        ("site, permutation, adjusted (per-stem mean error)", F_mt, p_mt,
         "site labels shuffled within size class, errors centred within class: "
         "site adjusted for size"),
        ("size class, permutation, joint (per-stem mean error)", np.nan, p_ms_j,
         "uncentred: the null that size class has no effect of any kind"),
        ("site, permutation, joint (per-stem mean error)", np.nan, p_mt_j,
         "uncentred: the null that site has no effect of any kind"),
    ]:
        add(short, unit, "7 robust alternative", lbl, lbl,
            stat=float(F) if np.isfinite(F) else np.nan,
            stat_type="F", p_value=float(p),
            note="%d permutations; %s.%s" % (N_PERM, note, perm_caveat))
    add(short, unit, "7 robust alternative", "site, Mann-Whitney (per-stem mean error)",
        "site, rank-based", stat=float(mw.statistic), stat_type="U",
        p_value=float(mw.pvalue), note="unadjusted for size class")
    add(short, unit, "7 robust alternative", "size class, Kruskal-Wallis (per-stem mean error)",
        "size class, rank-based", stat=float(kw.statistic), stat_type="H",
        p_value=float(kw.pvalue), note="unadjusted for site")

    gee = smf.gee(F_SUM, groups="stem", data=sub,
                  cov_struct=sm.cov_struct.Exchangeable()).fit()
    g_dev = contrast(gee.params, np.asarray(gee.cov_params(), float), L_dev)
    add(short, unit, "7 robust alternative", "device contrast, GEE exchangeable",
        "device: iOS - Android, cluster-robust", estimate=g_dev["estimate"],
        se=g_dev["se"], stat=g_dev["z"], stat_type="z", p_value=g_dev["p"],
        ci_low=g_dev["ci_low"], ci_high=g_dev["ci_high"],
        note="sandwich SE clustered on stem; same fixed effects as the mixed model")

    # proportional-error version: the natural response if spread scales with size
    mx_p = fit_mixed(sub, F_SUM_PCT)
    Lp = contrast_vectors(mx_p.model.data.design_info, grid)
    p_dev = contrast(mx_p.fe_params, fixed_cov(mx_p), Lp["device: iOS - Android"])
    wald_p = joint_wald(mx_p.fe_params, fixed_cov(mx_p), mx_p.model.data.design_info)
    cres_p, _, _ = conditional_residuals(mx_p, sub)
    rho_p, rp_p = sps.spearmanr(sub.reference.values, np.abs(cres_p))
    add(short, unit, "7 robust alternative", "device contrast, percent-error model",
        "device: iOS - Android (% of reference)", estimate=p_dev["estimate"],
        se=p_dev["se"], stat=p_dev["z"], stat_type="z (units are %)",
        p_value=p_dev["p"], ci_low=p_dev["ci_low"], ci_high=p_dev["ci_high"],
        note="same mixed model on pct_error; Spearman |resid| vs size rho %+.3f "
             "(p %.3g) vs %+.3f on the absolute scale" % (rho_p, rp_p, sp_rho))
    for term, (chi2, k, p) in wald_p.items():
        plain = term.replace("C(", "").replace(", Sum)", "")
        if ":" in plain:
            continue
        add(short, unit, "7 robust alternative", "%s, percent-error model" % plain,
            "%s (%% scale)" % plain, stat=chi2, stat_type="Wald chi2 (df %d)" % k,
            p_value=p, note="absolute-scale p %.4g" % wald[term][2])

    # ---- 8. sensitivity: drop the disputed-tape stems -----------------------
    bad = sub.stem[sub.tape_disputed].unique()
    keep = sub[~sub.stem.isin(bad)]
    mx_k = fit_mixed(keep, F_SUM)
    Lk = contrast_vectors(mx_k.model.data.design_info, grid)
    k_dev = contrast(mx_k.fe_params, fixed_cov(mx_k), Lk["device: iOS - Android"])
    wald_k = joint_wald(mx_k.fe_params, fixed_cov(mx_k), mx_k.model.data.design_info)
    add(short, unit, "8 sensitivity", "device contrast, disputed tape excluded",
        "device: iOS - Android (%d stems)" % (n_stem - len(bad)),
        estimate=k_dev["estimate"], se=k_dev["se"], stat=k_dev["z"], stat_type="z",
        p_value=k_dev["p"], ci_low=k_dev["ci_low"], ci_high=k_dev["ci_high"],
        note="%d TAPE-MISMATCH stems dropped (%s); full-sample estimate %.3f %s "
             "(p %.4g)" % (len(bad), ",".join(sorted(bad)), base["estimate"], unit,
                           wald["C(device, Sum)"][2]))
    for term, (chi2, k, p) in wald_k.items():
        plain = term.replace("C(", "").replace(", Sum)", "")
        add(short, unit, "8 sensitivity", "%s, disputed tape excluded" % plain,
            "%s (%d stems)" % (plain.replace(":", " x "), n_stem - len(bad)),
            stat=chi2, stat_type="Wald chi2 (df %d)" % k, p_value=p,
            note="full-sample p %.4g" % wald[term][2])

    # ---- carry forward -------------------------------------------------------
    diag[measurand] = dict(sub=sub, cres=cres, loo=loo, sw=sw, lev_cell=lev_cell,
                           unit=unit, base=base, sp_rho=sp_rho, sp_p=sp_p,
                           ps=ps_paired)
    nv[measurand] = dict(
        short=short, unit=unit, n_obs=n_obs, n_stem=n_stem, n_pair=len(ps_paired),
        emm=emm, base=base, icc=icc, gvar=gvar, rvar=rvar,
        wald=wald, aov=aov, p_flip=p_flip, p_wil=float(wil.pvalue),
        gee=g_dev, obs_d=obs_d, se_flip=se_flip, c_all=c_all,
        perclass=perclass, holm_p=holm_p, cramer=cramer,
        est_all_w=est_all_w, p_all_w=p_all_w, p_cell=p_cell, p_cell_hc3=p_all,
        lv_m_size=float(lv_m_size.pvalue), lv_m_site=float(lv_m_site.pvalue),
        lv_d_size=float(lv_d_size.pvalue), perm_ok=bool(perm_ok),
        terms_d=terms_d, terms_m=terms_m,
        p_ds=p_ds, p_dt=p_dt, p_ms=p_ms, p_mt=p_mt,
        p_ms_j=p_ms_j, p_mt_j=p_mt_j,
        sw_p=float(sw.pvalue), lev_p=float(lev_cell.pvalue),
        sp_rho=sp_rho, sp_p=sp_p, rho_p=rho_p,
        k_dev=k_dev, n_drop=len(bad), wald_k=wald_k,
        max_dfbeta=float(abs(worst.dfbeta)), worst_stem=str(worst.stem), n_big=n_big,
        ow_p=float(ow_aov.loc["C(device)", "PR(>F)"]),
        pct_dev=p_dev,
        cellmeans=sub.groupby(["sclass", "device"], observed=True).error.mean().unstack(),
    )

# --------------------------------------------------------------------------
# Table
# --------------------------------------------------------------------------
tab = pd.DataFrame(rows)[["measurand", "unit", "block", "term", "term_pretty",
                          "estimate", "se", "stat", "stat_type", "p_value",
                          "ci_low", "ci_high", "naive_estimate", "naive_se",
                          "naive_p", "note"]]
for c in ("estimate", "se", "stat", "p_value", "ci_low", "ci_high",
          "naive_estimate", "naive_se", "naive_p"):
    tab[c] = pd.to_numeric(tab[c], errors="coerce").round(6)

core.save_table(tab, "t05_anova", caption=(
    "Table 5. Repeated-measures model of measurement error. Linear mixed model "
    "error ~ device x site x size class with a random intercept for stem, fitted "
    "separately for DBH (in) and height (ft); device is a within-stem factor, site "
    "and size class are between-stem. Block 1 gives treatment-coded fixed effects "
    "(reference iOS, McDunn, smallest class); block 2 gives estimated marginal "
    "contrasts in the measurand's own units with equal cell weighting; block 3 "
    "gives sum-coded Type-III joint tests. In blocks 1-3 the naive_* columns are "
    "the same quantity from an ordinary least-squares fit that treats all "
    "observations as independent — the point estimates are identical, the standard "
    "errors and p-values are not. Blocks 4-8 give variance components and the ICC, "
    "assumption checks on conditional residuals, leave-one-stem-out influence on "
    "the device contrast, distribution-free / heteroscedasticity-robust "
    "alternatives, and a refit excluding stems with a disputed tape reading. Size "
    "classes were collapsed to three levels per measurand (DBH 0-12 / 12-24 / 24+ "
    "in; height 0-80 / 80-120 / 120+ ft) because the published six- and five-level "
    "schemes leave cells of a single stem and make the three-way interaction "
    "inestimable; realised cell counts, and the association between the two "
    "between-stem factors, are given in block 0. Residual variance is not constant "
    "across cells in either measurand and the residuals are heavy-tailed (block 5), "
    "so the block 7 results, not the Wald tests, are the ones defended in the text. "
    "Note that the mixed-model contrasts weight cells equally while a plain mean "
    "paired difference weights them by the stems they hold; both are reported "
    "because the two stands differ in size distribution and the two weightings do "
    "not give the same number."))

# --------------------------------------------------------------------------
# Diagnostic figure
# --------------------------------------------------------------------------
fig, axes = plt.subplots(2, 3, figsize=(core.FIG_W * 1.5, core.FIG_H * 1.5))
tags = "ABCDEF"
for r, measurand in enumerate(("dbh", "height")):
    d = diag[measurand]
    unit, short = d["unit"], core.MEASURANDS[measurand]["short"]
    sub, cres = d["sub"], d["cres"]

    # normal Q-Q of the conditional residuals
    ax = axes[r, 0]
    core.panel_tag(ax, tags[r * 3])
    (osm, osr), (slope, inter, _) = sps.probplot(cres, dist="norm")
    ax.plot(osm, osr, "o", ms=3, mfc="none", mew=0.8, color=core.PALETTE["reference"])
    lim = [osm.min(), osm.max()]
    ax.plot(lim, [slope * v + inter for v in lim], "-", lw=1.1,
            color=core.PALETTE["accent"])
    ax.set_xlabel("Theoretical normal quantile")
    ax.set_ylabel(f"Conditional residual ({unit})")
    ax.text(0.04, 0.96, f"{short}\nShapiro $W$ = {d['sw'].statistic:.3f}\n"
                        f"$p$ = {d['sw'].pvalue:.1e}",
            transform=ax.transAxes, va="top", ha="left", fontsize=7.5)

    # residual against stem size, by device
    ax = axes[r, 1]
    core.panel_tag(ax, tags[r * 3 + 1])
    for dev, mk in (("ios", "o"), ("android", "^")):
        m = (sub.device == dev).values
        ax.plot(sub.reference.values[m], cres[m], mk, ms=3.6, mfc="none", mew=0.9,
                color=core.PALETTE[dev], label=core.DEVICE_LABEL[dev])
    ax.axhline(0, lw=0.8, color=core.PALETTE["muted"], ls="--")
    ax.set_xlabel(f"Reference ({unit})")
    ax.set_ylabel(f"Conditional residual ({unit})")
    ax.text(0.04, 0.96,
            f"Levene (12 cells) $p$ = {d['lev_cell'].pvalue:.1e}\n"
            f"Spearman |resid| vs size\n$r_s$ = {d['sp_rho']:+.2f}, "
            f"$p$ = {d['sp_p']:.1e}",
            transform=ax.transAxes, va="top", ha="left", fontsize=7.5)
    if r == 0:
        # the house style has frameon=False, but this panel is dense enough that
        # a transparent legend reads as a third series
        ax.legend(loc="lower right", fontsize=7, frameon=True, framealpha=0.92,
                  facecolor="white", edgecolor="none")

    # leave-one-stem-out influence on the device contrast
    ax = axes[r, 2]
    core.panel_tag(ax, tags[r * 3 + 2])
    loo = d["loo"]
    x = np.arange(len(loo))
    ax.vlines(x, 0, loo.dfbeta.values, lw=0.7, color=core.PALETTE["reference"])
    ax.plot(x, loo.dfbeta.values, "o", ms=2.6, mfc="none", mew=0.7,
            color=core.PALETTE["reference"])
    ax.axhline(0, lw=0.8, color=core.PALETTE["muted"])
    cut = 2 / math.sqrt(len(loo))
    for lvl in (-cut, cut):
        ax.axhline(lvl, lw=0.8, ls=":", color=core.PALETTE["warn"])
    im = int(loo.dfbeta.abs().values.argmax())
    side = -1 if im > len(loo) / 2 else 1
    ax.annotate(f"{loo.stem.iloc[im]}, {loo.dfbeta.iloc[im]:+.2f} SE",
                xy=(im, loo.dfbeta.iloc[im]), xytext=(9 * side, 0),
                textcoords="offset points", fontsize=7.5, va="center",
                ha="left" if side > 0 else "right")
    ax.set_xlabel("Stem left out (index)")
    ax.set_ylabel("DFBETA, device contrast (SE)")
    ax.text(0.04, 0.06,
            f"device contrast {d['base']['estimate']:+.2f} {unit}",
            transform=ax.transAxes, fontsize=7.5)

fig.tight_layout()
core.save(fig, "fig05_model_diagnostics", caption=(
    "Figure 5. Diagnostics for the repeated-measures models of Table 5; top row "
    "DBH (in), bottom row height (ft). (A, D) normal Q-Q of the conditional "
    "residuals with the Shapiro-Wilk statistic. (B, E) conditional residuals "
    "against the reference measurement by device, with Levene's test across the "
    "twelve device x site x size cells and the Spearman correlation between "
    "residual magnitude and stem size. (C, F) leave-one-stem-out DFBETA for the "
    "marginal device contrast, in standard errors of that contrast; dotted lines "
    "mark the 2/sqrt(n) = 0.20 cutoff. Residuals are heavy-tailed and their spread "
    "grows with stem size in both measurands, so the constant-variance assumption "
    "of the mixed model is violated; the permutation and heteroscedasticity-robust "
    "results in Table 5 block 7 are the ones defended in the text."))

# --------------------------------------------------------------------------
# Console report
# --------------------------------------------------------------------------
for k, v in nv.items():
    u = v["unit"]
    print("=" * 76)
    print(f"{v['short']}  {v['n_obs']} rows / {v['n_stem']} stems / "
          f"{v['n_pair']} paired  ({u})")
    b = v["base"]
    print(f"  device contrast iOS-Android {b['estimate']:+.3f} {u} "
          f"[{b['ci_low']:+.3f},{b['ci_high']:+.3f}]")
    print(f"    mixed p {v['wald']['C(device, Sum)'][2]:.4g} | naive factorial "
          f"{v['aov'].loc['C(device, Sum)','PR(>F)']:.4g} | naive one-way {v['ow_p']:.4g}"
          f" | sign-flip {v['p_flip']:.4g} | Wilcoxon {v['p_wil']:.4g}"
          f" | GEE {v['gee']['p']:.4g}")
    print(f"  device x size  mixed {v['wald']['C(device, Sum):C(sclass, Sum)'][2]:.4g}"
          f" | HC3 {v['terms_d'].get('sclass',(0,0,float('nan')))[2]:.4g}"
          f" | perm {v['p_ds']:.4g}")
    print(f"  device x site  mixed {v['wald']['C(device, Sum):C(site, Sum)'][2]:.4g}"
          f" | HC3 {v['terms_d'].get('site',(0,0,float('nan')))[2]:.4g} | perm {v['p_dt']:.4g}")
    print(f"  3-way          mixed {v['wald']['C(device, Sum):C(site, Sum):C(sclass, Sum)'][2]:.4g}"
          f" | HC3 {v['terms_d'].get('site:sclass',(0,0,float('nan')))[2]:.4g}")
    print(f"  site   mixed {v['wald']['C(site, Sum)'][2]:.4g} | naive "
          f"{v['aov'].loc['C(site, Sum)','PR(>F)']:.4g} | HC3 "
          f"{v['terms_m'].get('site',(0,0,float('nan')))[2]:.4g} | HC3-joint "
          f"{v['terms_m'].get('site (joint with interaction)',(0,0,float('nan')))[2]:.4g}"
          f" | perm(adj) {v['p_mt']:.4g} | perm(joint) {v['p_mt_j']:.4g}")
    print(f"  size   mixed {v['wald']['C(sclass, Sum)'][2]:.4g} | naive "
          f"{v['aov'].loc['C(sclass, Sum)','PR(>F)']:.4g} | HC3 "
          f"{v['terms_m'].get('sclass',(0,0,float('nan')))[2]:.4g} | HC3-joint "
          f"{v['terms_m'].get('sclass (joint with interaction)',(0,0,float('nan')))[2]:.4g}"
          f" | perm(adj) {v['p_ms']:.4g} | perm(joint) {v['p_ms_j']:.4g}")
    for nm, c in v["emm"].items():
        print(f"    {nm:44s} {c['estimate']:+7.3f} {u} "
              f"[{c['ci_low']:+7.3f},{c['ci_high']:+7.3f}] p {c['p']:.4g}")
    print(f"  sample-weighted mean paired diff {v['obs_d']:+.3f} {u} "
          f"(SE {v['se_flip']:.3f}, sign-flip p {v['p_flip']:.4g}); "
          f"equal-weight {v['c_all']['estimate']:+.3f} "
          f"[{v['c_all']['ci_low']:+.3f},{v['c_all']['ci_high']:+.3f}] HC3 p "
          f"{v['c_all']['p']:.4g}, sign-flip p {v['p_all_w']:.4g}")
    for (sc, c2, pf, nsc, est_raw, pf_raw, est_w), hp in zip(v["perclass"], v["holm_p"]):
        print(f"    within {sc:10s} EMM {c2['estimate']:+7.3f} "
              f"[{c2['ci_low']:+7.3f},{c2['ci_high']:+7.3f}] HC3 p {c2['p']:.4g} | "
              f"flip p {pf:.4g} Holm {hp:.4g} | raw {est_raw:+7.3f} "
              f"flip p {pf_raw:.4g}  (n={nsc})")
    print(f"  Cramer V(site,size) {v['cramer']:.3f}; any between-stem structure: "
          f"perm p {v['p_cell']:.4g}, HC3 p {v['p_cell_hc3']:.4g}")
    print(f"  Levene per-stem: mean_err by size p {v['lv_m_size']:.3g}, by site "
          f"p {v['lv_m_site']:.3g}, diff by size p {v['lv_d_size']:.3g} -> "
          f"between-stem permutation {'valid' if v['perm_ok'] else 'NOT exact'}")
    print(f"  stem var {v['gvar']:.3f}  resid var {v['rvar']:.3f}  ICC {v['icc']:.3f}")
    print(f"  Shapiro p {v['sw_p']:.3g} | Levene p {v['lev_p']:.3g} | "
          f"Spearman rho {v['sp_rho']:+.3f} p {v['sp_p']:.3g} "
          f"(percent scale rho {v['rho_p']:+.3f})")
    print(f"  pct-error device contrast {v['pct_dev']['estimate']:+.2f}% "
          f"p {v['pct_dev']['p']:.4g}")
    print(f"  max |DFBETA| {v['max_dfbeta']:.3f} ({v['worst_stem']}); "
          f"{v['n_big']} stems over cutoff")
    print(f"  excluding {v['n_drop']} disputed stems: device "
          f"{v['k_dev']['estimate']:+.3f} {u} p {v['k_dev']['p']:.4g}; "
          f"size p {v['wald_k']['C(sclass, Sum)'][2]:.4g}; "
          f"site p {v['wald_k']['C(site, Sum)'][2]:.4g}")
    print("  mean error by size class x device:")
    print(v["cellmeans"].round(3).to_string())
