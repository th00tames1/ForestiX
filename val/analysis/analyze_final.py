#!/usr/bin/env python3
"""The accuracy statistics, off the recomputed table.

Everything is reported against the tape as reference, and separately as one
phone against the other, because the two questions are different: the first
asks how far the method is from the truth, the second asks whether the answer
depends on which handset you carry. A cross-platform paper needs both.

Errors are signed and reported in the field's own units alongside the ratio,
because a 6 % error means 0.4 in on a sapling and 3 in on a veteran, and a
cruiser cares which.

Also checks the app's own uncertainty against the error it actually made. A
sigma is a claim; this is the first data able to test it.
"""
import csv, os, math, statistics as st, collections

HERE = os.path.dirname(os.path.abspath(__file__))
IN, FT = 2.54, 0.3048
UNIT = {"dbh": ("in", IN), "height": ("ft", FT)}


def f(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


ROWS = list(csv.DictReader(open(os.path.join(HERE, "final_pairs.csv"))))


def cells(kind, who, plot=None):
    """(measured, tape, sigma, confidence, row) for every usable cell."""
    out = []
    for r in ROWS:
        if r["kind"] != kind or (plot and r["plot"] != plot):
            continue
        if f"{who}-typed" in r["flags"]:
            continue
        v, t = f(r[f"{who}_value"]), f(r["truth"])
        if v is None or t is None:
            continue
        out.append((v, t, f(r[f"{who}_sigma"]), r[f"{who}_confidence"], r))
    return out


def block(label, rows, conv, unit):
    if not rows:
        return
    e = [v - t for v, t, *_ in rows]
    rel = [(v - t) / t for v, t, *_ in rows]
    bias = st.mean(e) / conv
    rmse = math.sqrt(st.mean([x * x for x in e])) / conv
    mae = st.mean([abs(x) for x in e]) / conv
    sd = st.pstdev(e) / conv
    print(f"    {label:20s} n={len(rows):3d}  bias {bias:+6.2f} {unit}  "
          f"({st.mean(rel)*100:+5.1f}%)   RMSE {rmse:5.2f}   MAE {mae:5.2f}   "
          f"sd {sd:5.2f}   LoA {bias-1.96*sd:+6.2f} .. {bias+1.96*sd:+6.2f}")


def fit(xs, ys):
    """Least squares, plus Lin's concordance — agreement, not just correlation."""
    n = len(xs)
    mx, my = st.mean(xs), st.mean(ys)
    sxx = sum((x - mx) ** 2 for x in xs)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    syy = sum((y - my) ** 2 for y in ys)
    b = sxy / sxx if sxx else 0
    a = my - b * mx
    r = sxy / math.sqrt(sxx * syy) if sxx and syy else 0
    vx, vy, cov = sxx / n, syy / n, sxy / n
    ccc = 2 * cov / (vx + vy + (mx - my) ** 2) if (vx + vy) else 0
    return a, b, r, ccc


print("=" * 100)
print("AGAINST THE TAPE")
print("=" * 100)
for kind in ("dbh", "height"):
    unit, conv = UNIT[kind]
    print(f"\n  {kind.upper()}   ({unit})")
    for who in ("ios", "android"):
        for plot in ("McDunn", "Starker", None):
            block(f"{who} · {plot or 'both plots'}", cells(kind, who, plot), conv, unit)

print("\n" + "=" * 100)
print("CALIBRATION — how the measured value tracks the tape across the size range")
print("=" * 100)
print(f"  {'':16s} {'slope':>7s} {'intercept':>10s} {'r':>7s} {'CCC':>7s}   reading")
for kind in ("dbh", "height"):
    unit, conv = UNIT[kind]
    for who in ("ios", "android"):
        rows = cells(kind, who)
        a, b, r, ccc = fit([t / conv for _, t, *_ in rows], [v / conv for v, _, *_ in rows])
        note = ("proportional over-read" if b > 1.03 else
                "proportional under-read" if b < 0.97 else "slope ~1, offset only")
        print(f"  {kind:7s} {who:8s} {b:7.3f} {a:+10.2f} {r:7.3f} {ccc:7.3f}   {note}")

print("\n" + "=" * 100)
print("ONE PHONE AGAINST THE OTHER — does the answer depend on the handset?")
print("=" * 100)
for kind in ("dbh", "height"):
    unit, conv = UNIT[kind]
    both = [(f(r["ios_value"]), f(r["android_value"]), r) for r in ROWS
            if r["kind"] == kind and f(r["ios_value"]) and f(r["android_value"])
            and "ios-typed" not in r["flags"] and "android-typed" not in r["flags"]]
    d = [(i - a) / conv for i, a, _ in both]
    a0, b0, r0, ccc = fit([x[1] / conv for x in both], [x[0] / conv for x in both])
    print(f"\n  {kind.upper()}  n={len(both)}")
    print(f"    iOS − Android:  mean {st.mean(d):+.2f} {unit}   sd {st.pstdev(d):.2f}   "
          f"LoA {st.mean(d)-1.96*st.pstdev(d):+.2f} .. {st.mean(d)+1.96*st.pstdev(d):+.2f} {unit}")
    print(f"    agreement:      r {r0:.3f}   CCC {ccc:.3f}   slope {b0:.3f}")
    worst = sorted(both, key=lambda x: -abs(x[0] - x[1]))[:4]
    for i, a, r in worst:
        print(f"      {r['pair_id']:8s} iOS {i/conv:6.1f}  Android {a/conv:6.1f}  "
              f"tape {f(r['truth'])/conv:6.1f}  Δ {abs(i-a)/conv:5.1f} {unit}")

print("\n" + "=" * 100)
print("ERROR AGAINST STEM SIZE")
print("=" * 100)
for kind in ("dbh", "height"):
    unit, conv = UNIT[kind]
    print(f"\n  {kind.upper()}")
    rows = [(f(r["truth"]) / conv, r) for r in ROWS if r["kind"] == kind and f(r["truth"])]
    qs = sorted(x[0] for x in rows)
    cuts = [qs[len(qs) * k // 4] for k in (1, 2, 3)]
    for lo, hi in zip([0] + cuts, cuts + [1e9]):
        for who in ("ios", "android"):
            sub = [(v, t) for v, t, *_ in cells(kind, who) if lo <= t / conv < hi]
            if len(sub) < 4:
                continue
            rel = [(v - t) / t * 100 for v, t in sub]
            print(f"    {lo:5.1f}–{hi if hi < 1e8 else 999:5.1f} {unit}  {who:8s} "
                  f"n={len(sub):3d}   median {st.median(rel):+6.1f}%   "
                  f"mean {st.mean(rel):+6.1f}%")

print("\n" + "=" * 100)
print("IS THE APP'S OWN SIGMA HONEST?")
print("=" * 100)
print("  If sigma means what it says, |error| should fall inside 1.96 sigma about")
print("  95 times in 100. Under-covering means the app tells the cruiser a number")
print("  is tighter than it is — the worse of the two failures.")
for kind in ("dbh", "height"):
    unit, conv = UNIT[kind]
    for who in ("ios", "android"):
        rows = [(v, t, s) for v, t, s, *_ in cells(kind, who) if s]
        if not rows:
            continue
        cover = sum(1 for v, t, s in rows if abs(v - t) <= 1.96 * s) / len(rows)
        z = [abs(v - t) / s for v, t, s in rows if s > 0]
        print(f"  {kind:7s} {who:8s} n={len(rows):3d}   "
              f"median sigma {st.median([s for *_, s in rows])/conv:5.2f} {unit}   "
              f"median |err| {st.median([abs(v-t) for v, t, _ in rows])/conv:5.2f} {unit}   "
              f"coverage {cover*100:4.0f}%   median z {st.median(z):.2f}")

print("\n" + "=" * 100)
print("DOES THE CONFIDENCE BADGE PREDICT THE ERROR?")
print("=" * 100)
for kind in ("dbh", "height"):
    unit, conv = UNIT[kind]
    for who in ("ios", "android"):
        by = collections.defaultdict(list)
        for v, t, s, c, _ in cells(kind, who):
            by[c or "—"].append(abs(v - t) / t * 100)
        parts = [f"{c} n={len(x):3d} median|err| {st.median(x):4.1f}%"
                 for c, x in sorted(by.items())]
        print(f"  {kind:7s} {who:8s}  " + "   ".join(parts))
