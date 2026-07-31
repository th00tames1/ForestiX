#!/usr/bin/env python3
"""What the in-app repair will do, before the cruiser taps it.

Mirrors `CaptureReadingMatch.repairs` exactly — same key (kind, tree, plot),
same 90 s window, same greedy one-to-one from the smallest gap, same refusal to
touch a typed reading — over the corpus actually exported from both phones. The
point is to know the blast radius in advance, including where it is wrong.
"""
import json, glob, os, csv, datetime as dt, statistics as st, collections

HERE = os.path.dirname(os.path.abspath(__file__))
VAL = os.path.dirname(HERE)
CSVS = {"android": "quick-measure-2026-07-30T19-22-37.csv",
        "ios": "quick-measure-2026-07-31T02-22-50Z.csv"}
PT = dt.timezone(dt.timedelta(hours=-7))
WINDOW = 90.0
IN, FT = 2.54, 0.3048
TYPED_METHOD = {"dbh": "manualVisual", "height": "manualEntry"}


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


TAPE = {}
for r in csv.DictReader(open(os.path.join(HERE, "final_pairs.csv"))):
    for who, key in (("ios", "tree_ios"), ("android", "tree_android")):
        if r[key]:
            TAPE[(who, r["kind"], int(r[key]))] = (f2(r["truth"]), r["pair_id"], r["plot"])

print("=" * 96)
print("SIMULATED REPAIR — what 'Apply' would change")
print("=" * 96)

for plat in ("ios", "android"):
    captures = []
    for p in sorted(glob.glob(os.path.join(VAL, "raw", plat, "**", "manifest.json"),
                              recursive=True)):
        try:
            m = json.load(open(p))
        except Exception:
            continue
        ctx = m.get("context") or {}
        if ctx.get("mode") == "cruise":          # skipped, as in the app
            continue
        kind = m.get("kind")
        if kind not in ("dbh", "height"):
            continue
        t = T(m.get("created_at"))
        v = f2((m.get("result_live") or {}).get("value"))
        if not t or not v or v <= 0:
            continue
        captures.append(dict(id=os.path.basename(os.path.dirname(p)), kind=kind,
                             tree=ctx.get("tree_number"), plot=ctx.get("plot_id"),
                             t=t, value=v))

    entries = []
    with open(os.path.join(VAL, CSVS[plat]), encoding="utf-8-sig") as fh:
        for r in csv.DictReader(fh):
            if r["kind"] not in ("dbh", "height"):
                continue
            if r["capture_mode"] == "typed" or r["method"] == TYPED_METHOD.get(r["kind"]):
                continue
            t, v = T(r["timestamp"]), f2(r["value"])
            if not t or v is None:
                continue
            entries.append(dict(kind=r["kind"], tree=int(r["tree"]) if r["tree"] else None,
                                plot=r["plot"], t=t, value=v, row=r))

    def same_plot(a, b):
        return True if (a is None or b is None) else a == b

    cands = sorted(
        (abs((c["t"] - e["t"]).total_seconds()), ci, ei)
        for ci, c in enumerate(captures) for ei, e in enumerate(entries)
        if c["kind"] == e["kind"] and c["tree"] == e["tree"]
        and abs((c["t"] - e["t"]).total_seconds()) <= WINDOW)
    uc, ue, repairs = set(), set(), []
    for gap, ci, ei in cands:
        if ci in uc or ei in ue:
            continue
        uc.add(ci); ue.add(ei)
        c, e = captures[ci], entries[ei]
        if abs(e["value"] - c["value"]) <= 1e-4:
            continue
        repairs.append((c, e, gap))

    print(f"\n{plat.upper()}   {len(captures)} replayable captures, "
          f"{len(entries)} untyped readings  ->  {len(repairs)} would change")
    by = collections.Counter(e["row"]["plot"] for _, e, _ in repairs)
    print("    by plot: " + ", ".join(f"{k or '(none)'} {v}" for k, v in by.most_common()))
    big = [(c, e) for c, e, _ in repairs if abs(e["value"] / c["value"] - 1) > 0.15]
    print(f"    {len(big)} are the 4:3 rescale, {len(repairs)-len(big)} are small")

    # Where a tape exists, did the repair help or hurt?
    better = worse = 0
    hurt = []
    for c, e, _ in repairs:
        got = TAPE.get((plat, c["kind"], c["tree"]))
        if not got or not got[0]:
            continue
        truth, pid, plot = got
        before, after = abs(e["value"] - truth), abs(c["value"] - truth)
        if after < before:
            better += 1
        else:
            worse += 1
            hurt.append((pid, plot, c["kind"], e["value"], c["value"], truth))
    print(f"    against the tape: {better} improved, {worse} made worse")
    for pid, plot, kind, old, new, truth in sorted(hurt,
                                                   key=lambda h: -abs(h[3] - h[4])):
        u = IN if kind == "dbh" else FT
        print(f"      WORSE  {pid} {plot} {kind}: {old/u:.1f} -> {new/u:.1f} "
              f"(tape {truth/u:.1f})")
