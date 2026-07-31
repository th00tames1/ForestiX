#!/usr/bin/env python3
"""What the axis fix does to the 100 validation trees, plot by plot.

The bug: on iOS the diameter written to the field log was computed against a
guide axis latched once per SESSION, while the raw-capture bundle recomputed
its own per capture. When the session latched the wrong one, the bracket
fractions were read against the 256 px extent instead of the 192 px one and
every diameter in that session came out about 4:3 too large.

So each capture has two numbers already on disk — what the field log kept, and
what the bundle kept — and the fix makes the app keep the second. That makes
this measurable without re-measuring anything: run both against the tape.

Matching is strictly one-to-one, nearest in time. A tree measured twice has two
bundles, and letting both match the single surviving reading would count a
retake as if the fix had changed it.
"""
import json, glob, os, csv, datetime as dt, statistics as st, collections, math

HERE = os.path.dirname(os.path.abspath(__file__))
VAL = os.path.dirname(HERE)
PT = dt.timezone(dt.timedelta(hours=-7))
IN = 2.54


def f2(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def T(s):
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(PT)
    except Exception:
        return None


ROWS = [r for r in csv.DictReader(open(os.path.join(HERE, "final_pairs.csv")))
        if r["kind"] == "dbh"]

RESULT = {}
for plat in ("ios", "android"):
    # The readings of interest: one per validation tree, with its tape.
    reads = []
    for r in ROWS:
        key = "tree_ios" if plat == "ios" else "tree_android"
        if not r[key] or not r[f"{plat}_time"]:
            continue
        v, t = f2(r[f"{plat}_value_asrecorded"]), f2(r["truth"])
        if v is None or t is None:
            continue
        reads.append(dict(tree=int(r[key]), pid=r["pair_id"], plot=r["plot"], reading=v,
                          truth=t, when=dt.datetime.strptime(r[f"{plat}_time"],
                                                             "%Y-%m-%d %H:%M:%S")))
    bundles = []
    for p in sorted(glob.glob(os.path.join(VAL, "raw", plat, "**", "manifest.json"),
                              recursive=True)):
        try:
            m = json.load(open(p))
        except Exception:
            continue
        if m.get("kind") != "dbh":
            continue
        t = T(m.get("created_at"))
        v = f2((m.get("result_live") or {}).get("value"))
        if not t or not v:
            continue
        bundles.append(dict(tree=(m.get("context") or {}).get("tree_number"), t=t, live=v))

    cands = sorted((abs((b["t"].replace(tzinfo=None) - r["when"]).total_seconds()), i, j)
                   for i, b in enumerate(bundles) for j, r in enumerate(reads)
                   if b["tree"] == r["tree"]
                   and abs((b["t"].replace(tzinfo=None) - r["when"]).total_seconds()) <= 90)
    ub, ur, pairs = set(), set(), []
    for gap, i, j in cands:
        if i in ub or j in ur:
            continue
        ub.add(i); ur.add(j)
        pairs.append({**reads[j], "live": bundles[i]["live"]})
    RESULT[plat] = pairs

print("=" * 92)
print("WHAT THE FIX MOVES, AND WHERE ACCURACY LANDS")
print("=" * 92)
for plat in ("ios", "android"):
    pairs = RESULT[plat]
    print(f"\n{plat.upper()}")
    for plot in ("McDunn", "Starker"):
        rows = [p for p in pairs if p["plot"] == plot]
        moved = [p for p in rows if abs(p["reading"] / p["live"] - 1) >= 0.01]
        print(f"\n  {plot:8s} matched {len(rows)}/50 trees   "
              f"unchanged {len(rows)-len(moved)}   CHANGED {len(moved)}")
        for label, key in (("field log (as captured)", "reading"), ("after the fix", "live")):
            rel = [(p[key] - p["truth"]) / p["truth"] * 100 for p in rows]
            rmse = math.sqrt(st.mean([((p[key] - p["truth"]) / IN) ** 2 for p in rows]))
            print(f"    {label:24s} bias {st.mean(rel):+7.2f}%   median {st.median(rel):+7.2f}%"
                  f"   RMSE {rmse:5.2f} in")
        if moved:
            fac = [p["reading"] / p["live"] for p in moved]
            big = [x for x in fac if abs(x - 1) > 0.15]
            print(f"    of the changed: median factor {st.median(fac):.3f};  "
                  f"{len(big)} are the 4:3 rescale, {len(moved)-len(big)} are small")

print("\n" + "=" * 92)
print("EVERY TREE THE FIX MOVES BY MORE THAN 15 %")
print("=" * 92)
for plat in ("ios", "android"):
    moved = [p for p in RESULT[plat]
             if abs(p["reading"] / p["live"] - 1) > 0.15]
    print(f"\n  {plat}   {len(moved)} trees")
    if not moved:
        print("    none")
        continue
    print(f"    {'tree':8s} {'log':>8s} {'fixed':>8s} {'tape':>8s} {'factor':>7s}   "
          f"{'log err':>8s} {'fixed err':>9s}")
    for p in sorted(moved, key=lambda x: (x["plot"], x["pid"])):
        le = (p["reading"] - p["truth"]) / p["truth"] * 100
        fe = (p["live"] - p["truth"]) / p["truth"] * 100
        print(f"    {p['pid']:8s} {p['reading']/IN:8.1f} {p['live']/IN:8.1f} "
              f"{p['truth']/IN:8.1f} {p['reading']/p['live']:7.3f}   "
              f"{le:+7.1f}% {fe:+8.1f}%")

print("\n" + "=" * 92)
print("SUMMARY")
print("=" * 92)
for plat in ("ios", "android"):
    for plot in ("McDunn", "Starker"):
        rows = [p for p in RESULT[plat] if p["plot"] == plot]
        if not rows:
            continue
        big = sum(1 for p in rows if abs(p["reading"] / p["live"] - 1) > 0.15)
        before = st.mean([(p["reading"] - p["truth"]) / p["truth"] * 100 for p in rows])
        after = st.mean([(p["live"] - p["truth"]) / p["truth"] * 100 for p in rows])
        verdict = ("rescaled" if big > len(rows) / 2 else
                   "mostly untouched" if big <= 3 else "partly rescaled")
        print(f"  {plat:8s} {plot:8s} {big:2d}/{len(rows)} trees rescaled   "
              f"bias {before:+6.1f}% → {after:+6.1f}%   [{verdict}]")
