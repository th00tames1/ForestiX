#!/usr/bin/env python3
"""Match every raw-capture bundle to the reading it produced, and ask why the
two disagree.

The cruiser noticed that the raw-capture console shows one diameter for a tree
and the field log another — 43.73 cm against 63.12 cm on the same stem. Both
are the app's own records of one measurement. This finds every case, measures
the gap, and looks for what separates the bundles that agree from the ones
that do not.
"""
import json, glob, os, csv, datetime as dt, collections, statistics as st

HERE = os.path.dirname(os.path.abspath(__file__))
VAL = os.path.dirname(HERE)
RAW = os.path.join(VAL, "raw")
CSV = {"android": "quick-measure-2026-07-30T19-22-37.csv",
       "ios": "quick-measure-2026-07-31T02-22-50Z.csv"}
PT = dt.timezone(dt.timedelta(hours=-7))


def f2(x):
    try:
        return float(x)
    except (TypeError, ValueError):
        return None


def T(s):
    if not s:
        return None
    try:
        return dt.datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(PT)
    except ValueError:
        return None


def bundles(plat):
    out = []
    for path in sorted(glob.glob(os.path.join(RAW, plat, "**", "manifest.json"),
                                 recursive=True)):
        try:
            m = json.load(open(path))
        except Exception:
            continue
        c = m.get("context") or {}
        live = m.get("result_live") or {}
        d = m.get("dbh") or {}
        br = d.get("bracket") or {}
        out.append(dict(
            plat=plat, dir=os.path.dirname(path), id=os.path.basename(os.path.dirname(path)),
            kind=m.get("kind"), tree=c.get("tree_number"), t=T(m.get("created_at")),
            value=f2(live.get("value")), per_frame=live.get("per_frame") or [],
            tier=live.get("tier"), accepted=live.get("accepted"),
            truth=f2((m.get("truth") or {}).get("value")),
            truth_unit=(m.get("truth") or {}).get("truth_unit"),
            bracket_on=bool(br.get("enabled")),
            bl=f2(br.get("left")), brr=f2(br.get("right")),
            nframes=len(d.get("frames") or []),
            selfcheck=(m.get("replay_selfcheck") or {}).get("status"),
            manifest=m))
    return out


def readings(plat):
    out = []
    with open(os.path.join(VAL, CSV[plat]), encoding="utf-8-sig") as fh:
        for r in csv.DictReader(fh):
            out.append(dict(plot=r["plot"], tree=int(r["tree"]) if r["tree"] else None,
                            kind=r["kind"], value=f2(r["value"]), truth=f2(r["truth"]),
                            t=T(r["timestamp"]), mode=r["capture_mode"],
                            method=r["method"], name=r["tree_name"]))
    return out


print("=" * 80)
print("BUNDLE vs READING — the app's two records of one measurement")
print("=" * 80)

ALL = {}
for plat in ("ios", "android"):
    B, R = bundles(plat), readings(plat)
    ALL[plat] = (B, R)
    # Match on (kind, tree) nearest in time; a bundle is written seconds after
    # its reading, so the true pair is far closer than any neighbour.
    pairs, unmatched = [], []
    used = set()
    cands = []
    for i, b in enumerate(B):
        for j, r in enumerate(R):
            if b["kind"] != r["kind"] or b["tree"] != r["tree"]:
                continue
            if not b["t"] or not r["t"]:
                continue
            gap = abs((b["t"] - r["t"]).total_seconds())
            if gap <= 120:
                cands.append((gap, i, j))
    cands.sort()
    ub, ur = set(), set()
    for gap, i, j in cands:
        if i in ub or j in ur:
            continue
        ub.add(i); ur.add(j); pairs.append((gap, B[i], R[j]))
    print(f"\n### {plat}   bundles {len(B)}   readings {len(R)}   matched {len(pairs)}")
    dbh = [(g, b, r) for g, b, r in pairs if b["kind"] == "dbh"
           and b["value"] and r["value"]]
    hgt = [(g, b, r) for g, b, r in pairs if b["kind"] == "height"
           and b["value"] and r["value"]]
    for label, rows in (("dbh", dbh), ("height", hgt)):
        ratios = [r["value"] / b["value"] for _, b, r in rows]
        same = sum(1 for x in ratios if abs(x - 1) < 0.01)
        print(f"   {label:6s} n={len(rows):3d}   reading/bundle: "
              f"same {same}, differ {len(rows)-same}"
              + (f"   differ-median {st.median([x for x in ratios if abs(x-1)>=0.01]):.3f}"
                 if len(rows) - same else ""))

print("\n" + "=" * 80)
print("WHERE THEY DIFFER — iOS diameters, worst first")
print("=" * 80)
B, R = ALL["ios"]
plot_of = {}
for r in readings("ios"):
    plot_of[(r["kind"], r["tree"], r["t"])] = r["plot"]
rows = []
cands = []
for i, b in enumerate(B):
    for j, r in enumerate(R):
        if b["kind"] != r["kind"] or b["tree"] != r["tree"] or b["kind"] != "dbh":
            continue
        if not b["t"] or not r["t"]:
            continue
        g = abs((b["t"] - r["t"]).total_seconds())
        if g <= 120:
            cands.append((g, i, j))
cands.sort()
ub, ur = set(), set()
for g, i, j in cands:
    if i in ub or j in ur:
        continue
    ub.add(i); ur.add(j)
    b, r = B[i], R[j]
    if not (b["value"] and r["value"]):
        continue
    rows.append((r["value"] / b["value"], b, r))
rows.sort(key=lambda x: -abs(x[0] - 1))
print(f"{'plot':9s} {'tree':>5s} {'bundle':>8s} {'reading':>8s} {'r/b':>6s} "
      f"{'truth':>7s} {'b/t':>6s} {'r/t':>6s}  brk  frames")
for ratio, b, r in rows[:18]:
    t = r["truth"] or b["truth"]
    bt = b["value"] / t if t else 0
    rt = r["value"] / t if t else 0
    print(f"{r['plot'][:9]:9s} {b['tree']:5d} {b['value']:8.2f} {r['value']:8.2f} "
          f"{ratio:6.3f} {t or 0:7.2f} {bt:6.3f} {rt:6.3f}  "
          f"{'Y' if b['bracket_on'] else 'n'}   {b['nframes']}")
