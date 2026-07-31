#!/usr/bin/env python3
"""The size-class gate, redone against the corrected shipped baseline.

Same question as `by_size.py` — does the change hold from saplings to veterans,
or only on average — but the baseline there was wrong: it medianed the whole
bracket where the app medians the middle half. See `sweep2.py`.

Only the ZERO-PARAMETER candidates are carried forward. With the shipped trim
in place the honest geometric offset is 0.032 R, not 0.134 R, because the trim
has already removed most of the curve; using 0.134 R on top scores better and
is no longer a derivation, so it is shown but not proposed.
"""
import math, statistics as st, collections, sweep2

IN = 2.54

VARIANTS = [
    ("shipped", sweep2.CANDIDATES[0][1]),
    ("tangent only", sweep2.CANDIDATES[2][1]),
    ("tangent+0.032R", sweep2.CANDIDATES[5][1]),
]
BINS = [(0, 8), (8, 12), (12, 18), (18, 24), (24, 32), (32, 999)]


def stats(rows, fn):
    rel, err = [], []
    for r in rows:
        d = sweep2.value(r, fn)
        if d is None:
            continue
        rel.append((d - r["truth"]) / r["truth"] * 100)
        err.append((d - r["truth"]) / IN)
    if not rel:
        return None
    return dict(n=len(rel), bias=st.mean(rel), rmse=math.sqrt(st.mean([e * e for e in err])),
                mae=st.mean([abs(e) for e in err]))


print("=" * 102)
print("BY SIZE CLASS — corrected baseline, zero-parameter candidates only")
print("=" * 102)
verdict = collections.Counter()
for plat in ("ios", "android"):
    print(f"\n{plat.upper()}")
    print(f"  {'class':>9s} {'n':>4s}  " + "".join(f"{n:>25s}" for n, _ in VARIANTS))
    print(f"  {'':>9s} {'':>4s}  " + "".join(f"{'bias':>9s}{'RMSE':>8s}{'MAE':>8s}"
                                             for _ in VARIANTS))
    for lo, hi in BINS:
        rows = [r for r in sweep2.MATCHED[plat] if lo <= r["truth"] / IN < hi]
        if len(rows) < 4:
            continue
        sc = {n: stats(rows, f) for n, f in VARIANTS}
        cells = "".join(f"{sc[n]['bias']:+8.1f}%{sc[n]['rmse']:7.2f}\"{sc[n]['mae']:7.2f}\""
                        for n, _ in VARIANTS)
        print(f"  {(f'{lo}-{hi}' if hi < 999 else f'{lo}+'):>9s} {len(rows):4d}  " + cells)
        for name in ("tangent only", "tangent+0.032R"):
            old, new = sc["shipped"], sc[name]
            ok = abs(new["bias"]) < abs(old["bias"]) and new["rmse"] <= old["rmse"] + 0.02
            verdict[(plat, name, "better" if ok else "not better")] += 1

print("\n" + "=" * 102)
print("VERDICT — classes improved on BOTH bias and RMSE")
print("=" * 102)
for plat in ("ios", "android"):
    for name in ("tangent only", "tangent+0.032R"):
        got = {k[2]: v for k, v in verdict.items() if k[0] == plat and k[1] == name}
        tot = sum(got.values())
        print(f"  {plat:8s} {name:16s} {got.get('better', 0)}/{tot} classes"
              + ("" if got.get("not better", 0) == 0 else
                 f"   ({got['not better']} NOT better)"))

print("\n" + "=" * 102)
print("OVERALL, ON THE CORRECTED BASELINE")
print("=" * 102)
for plat in ("ios", "android"):
    print(f"\n  {plat}")
    base = stats(sweep2.MATCHED[plat], VARIANTS[0][1])
    for name, fn in VARIANTS:
        s = stats(sweep2.MATCHED[plat], fn)
        d_rmse = (s["rmse"] / base["rmse"] - 1) * 100
        print(f"    {name:16s} n={s['n']:3d}  bias {s['bias']:+6.2f}%  "
              f"RMSE {s['rmse']:5.2f} in ({d_rmse:+5.1f}%)  MAE {s['mae']:5.2f} in")
