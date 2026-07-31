#!/usr/bin/env python3
"""The analysis table: what both handsets reported, against the tape and laser.

WHY NOT THE EXPORTED READINGS. The app stores each diameter twice — once on
the reading in the field log, once as `result_live` in the raw-capture bundle —
and on iOS the two disagree on 61 of 108 captures, the reading running about
1.4x the bundle. Recomputing the shipped identity

    d = w * z / (f - w/2)

directly from the stored depth frames reproduces the BUNDLE to a median of
1.0000 (99 of 107 within 2 %) and the reading to 0.998 on only 44 of 107. The
bundle is what the depth data says; the reading is not. So every diameter here
comes from the depth, through one function, for both phones and both plots —
which is also a cleaner thing to write in a methods section than "whatever the
app happened to store".

Heights are taken as recorded: the two records agree on 101 of 101 (iOS) and
109 of 109 (Android), and the tangent geometry needs poses this table does not
carry.

PAIRING. Starker by the cruiser's own tree names, which both phones recorded
and which agree 50/50. McDunn by capture time, smallest gap first. Two McDunn
pairs are named explicitly: a stem the iPhone split across two tally numbers,
and a pair the phones recorded eleven minutes apart.

TAPE VALUES. One person taped each stem and both phones were given that
number — the two records are byte-identical on 90+ of the pairs — so a
disagreement is a typing error on one phone, not two readings of a tape.
Both values are carried and the row is flagged whatever happens, and the
reference is settled WITHOUT CONSULTING THE PHONES: a gap over 10 % means one
record is a typing error with no way to tell which, so that stem's truth is
unrecoverable and it is dropped; anything smaller takes the mean of the two,
which is symmetric between the devices and never off by more than half a
disagreement that is already under 4 %.
"""
import json, glob, os, csv, struct, math, datetime as dt, statistics as st, collections

HERE = os.path.dirname(os.path.abspath(__file__))
VAL = os.path.dirname(HERE)
# Re-exported from both handsets on 2026-07-31, AFTER the tangent estimator
# shipped. These carry what the application now reports, which is what the
# recomputation below has to reproduce.
CSVS = {"android": "quick-measure-2026-07-31T03-41-44.csv",
        "ios": "quick-measure-2026-07-31T10-42-50Z.csv"}
PLOTS = ("Val_McDunn", "Val_Starker")
PT = dt.timezone(dt.timedelta(hours=-7))
IN, FT = 2.54, 0.3048

# iOS 26 + 27 are one stem: the diameter went on 26, the tally advanced, the
# height landed on 27. Android 22 holds both and its two tape values match.
IOS_MERGE = {27: 26}
# iOS 55 and Android 49: both heights taped 69.1 ft, recorded eleven minutes
# apart, past any sane time window.
FORCE_PAIR = [(55, 49)]

# Three McDunn stems come out, for defects in the RECORD — never for the size
# of the error, which would be choosing the answer:
#   i8/a7   the Android diameter is an Auto capture, and the iOS height is one
#           of the two office-typed placeholders below
#   i9/a8   the other placeholder: 160.20 ft typed for this stem and for i8,
#           two days later, against tapes of 162.7 and 167.8
#   i19/a15 the two phones hold different tapes in BOTH fields — 58.0 against
#           20.8 in, 74.1 against 58.0 ft — and 58.0 appears in both of the
#           iPhone's fields, so the truth is not recoverable for either
DROP = {"i8/a7", "i9/a8", "i19/a15"}

# Above this relative gap between the two recorded tape values, the stem's
# reference is treated as unrecoverable and the stem is dropped.
TAPE_DECIDE_ABOVE = 0.10


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


def depth_of(path, w, h, fmt):
    raw = open(path, "rb").read()
    n = w * h
    if fmt == "f32m":
        return None if len(raw) < n * 4 else [
            (v if 0.001 <= v <= 8.0 else 0.0) for v in struct.unpack_from("<%df" % n, raw, 0)]
    if fmt == "u16mm":
        return None if len(raw) < n * 2 else [
            (v / 1000.0 if 1 <= v <= 8000 else 0.0) for v in struct.unpack_from("<%dH" % n, raw, 0)]
    return None


# How far behind the near face the middle-half depth median sits, as a fraction
# of the radius: 1 - sqrt(15)/4. Same constant as
# `DBHEstimator.medianDepthOffsetFactor` on both platforms.
MEDIAN_DEPTH_OFFSET_FACTOR = 1.0 - math.sqrt(15.0) / 4.0
IDENTITY = os.environ.get("FORESTIX_IDENTITY", "tangent")


def core_range(i_lo, i_hi):
    """`bracketCoreRange` — the bracket's middle half.

    The handles sit ON the silhouette, so the span's end pixels straddle the
    edge and routinely return whatever stands behind the stem. Dropping a
    quarter from each end leaves a sample that cannot be background if the
    bracket is on a trunk at all.
    """
    span = i_hi - i_lo
    if span < 8:
        return i_lo, i_hi
    q = span // 4
    return i_lo + q, i_hi - q


def frame_diameter(frame, dirpath, lo, hi):
    """One frame's diameter, by the estimator the app ships today (epoch 3).

    ONE IMPLEMENTATION FOR EVERY CAPTURE, deliberately. The recording binary
    changed during the collection — the middle-half trim landed on 2026-07-28,
    partway through — and the manifests cannot tell the two eras apart, because
    `app_commit` records a marketing version that never moved: every iOS bundle
    says "1.0 (2)" and every Android one "1.0 (1)". So "what the app stored" is
    not a single quantity over this corpus and cannot be analysed as one.
    Recomputing all 200 readings from their own depth frames, with one
    estimator, makes the column mean one thing again.

    The focal is AXIS-MATCHED: a bracket walked down the image's y axis is
    measured against fy. iOS has square pixels so it makes no difference there;
    Android's fy runs 0.25 % above its fx, and every Android bracket in this
    corpus is a column walk.
    """
    w, h = frame.get("width"), frame.get("height")
    axis, df = frame.get("axis"), frame.get("depth_file")
    focal = f2(frame.get("fx")) if axis == "row" else f2(frame.get("fy"))
    if focal is None:
        focal = f2(frame.get("fx"))
    if not (w and h and focal and df):
        return None
    p = os.path.join(dirpath, df)
    if not os.path.exists(p):
        return None
    dep = depth_of(p, w, h, frame.get("format"))
    if dep is None:
        return None
    tap = frame.get("tap_px") or [w // 2, h // 2]
    extent = w if axis == "row" else h
    fixed = int(tap[1]) if axis == "row" else int(tap[0])
    if not (0 <= fixed < (h if axis == "row" else w)):
        return None
    span = (hi - lo) * extent
    i0 = max(0, math.ceil(lo * extent))
    i1 = min(extent - 1, math.floor(hi * extent))
    if i1 - i0 < 3 or span < 2 or focal - span / 2 <= 1:
        return None
    c0, c1 = core_range(i0, i1)
    vals = []
    for i in range(c0, c1 + 1):
        px, py = (i, fixed) if axis == "row" else (fixed, i)
        v = dep[py * w + px]
        if v > 0:
            vals.append(v)
    if len(vals) < 3:
        return None
    z = st.median(vals)
    if not (0.3 <= z <= 5.0):
        return None
    # `DBHEstimator.silhouetteDiameterCm`, epoch 3 — the identity the app
    # ships. The bracket's edges are TANGENT points, not the ends of a chord,
    # and the middle-half depth median sits (1 - sqrt(15)/4) R behind the near
    # face the tangent form wants, so R is substituted and solved rather than
    # iterated. Keeping this in step with the Swift and the Kotlin is the whole
    # point: the table has to say what the application says.
    if IDENTITY == "chord":
        return span * z / (focal - span / 2) * 100.0
    k = span / (2.0 * focal)
    kk = k * (k + math.sqrt(k * k + 1.0))
    radius_m = z * kk / (1.0 + MEDIAN_DEPTH_OFFSET_FACTOR * kk)
    if not (radius_m > 0):
        return None
    return 2.0 * radius_m * 100.0


def recomputed(plat):
    """Every stored diameter capture, recomputed from its own depth frames.

    A capture taken in Auto keeps no bracket, so there is no span to put
    through the identity; those fall back to the value the silhouette detector
    itself wrote into the bundle, and say so.
    """
    out = []
    for path in sorted(glob.glob(os.path.join(VAL, "raw", plat, "**", "manifest.json"),
                                 recursive=True)):
        try:
            m = json.load(open(path))
        except Exception:
            continue
        if m.get("kind") != "dbh":
            continue
        d = m.get("dbh") or {}
        br = d.get("bracket") or {}
        live = f2((m.get("result_live") or {}).get("value"))
        rec = dict(tree=(m.get("context") or {}).get("tree_number"),
                   t=T(m.get("created_at")), dbh=None, frames=0, source="")
        lo, hi = f2(br.get("left")), f2(br.get("right"))
        if br.get("enabled") and lo is not None and hi is not None:
            lo, hi = min(lo, hi), max(lo, hi)
            dirp = os.path.dirname(path)
            per = [x for x in (frame_diameter(fr, dirp, lo, hi)
                               for fr in (d.get("frames") or [])) if x]
            if per:
                rec.update(dbh=st.median(per), frames=len(per), source="raw-depth")
        if rec["dbh"] is None and live:
            rec.update(dbh=live, frames=len(d.get("frames") or []), source="bundle-auto")
        if rec["dbh"] is not None:
            out.append(rec)
    return out


def load_readings(plat):
    rows = []
    with open(os.path.join(VAL, CSVS[plat]), encoding="utf-8-sig") as fh:
        for r in csv.DictReader(fh):
            if r["plot"] not in PLOTS or r["kind"] not in ("dbh", "height"):
                continue
            tree = int(r["tree"]) if r["tree"] else None
            if plat == "ios" and r["plot"] == "Val_McDunn":
                tree = IOS_MERGE.get(tree, tree)
            rows.append(dict(plat=plat, plot=r["plot"], tree=tree, kind=r["kind"],
                             name=r["tree_name"], t=T(r["timestamp"]),
                             value=f2(r["value"]), truth=f2(r["truth"]),
                             sigma=f2(r["sigma"]), sigma_unit=r["sigma_unit"],
                             conf=r["confidence"], mode=r["capture_mode"],
                             method=r["method"], species=r["species"]))
    return rows


# Attach the recomputed diameter to its reading, by tree + nearest time.
DATA = {}
for plat in ("ios", "android"):
    R = load_readings(plat)
    C = recomputed(plat)
    cands = sorted((abs((c["t"] - r["t"]).total_seconds()), i, j)
                   for i, c in enumerate(C) for j, r in enumerate(R)
                   if r["kind"] == "dbh" and c["t"] and r["t"]
                   and (c["tree"] == r["tree"]
                        or (plat == "ios" and IOS_MERGE.get(c["tree"], c["tree"]) == r["tree"]))
                   and abs((c["t"] - r["t"]).total_seconds()) <= 120)
    uc, ur = set(), set()
    for g, i, j in cands:
        if i in uc or j in ur:
            continue
        uc.add(i); ur.add(j)
        R[j]["recomputed"] = C[i]["dbh"]
        R[j]["recomputed_frames"] = C[i]["frames"]
        R[j]["recomputed_source"] = C[i]["source"]
    DATA[plat] = R


def group(rows, plot, key):
    by = collections.defaultdict(lambda: {"dbh": [], "height": []})
    for r in rows:
        if r["plot"] != plot:
            continue
        k = key(r)
        if k is None:
            continue
        by[k][r["kind"]].append(r)
    for rec in by.values():
        for k in rec:
            rec[k].sort(key=lambda r: r["t"])
    return by


def stamp(rec):
    ts = [r["t"] for k in rec for r in rec[k]]
    return min(ts) if ts else None


pairs = []
gi = group(DATA["ios"], "Val_Starker", lambda r: r["name"] or None)
ga = group(DATA["android"], "Val_Starker", lambda r: r["name"] or None)
for n in sorted(set(gi) & set(ga), key=lambda x: int("".join(c for c in x if c.isdigit()) or 0)):
    pairs.append(("Val_Starker", n, gi[n], ga[n], 0.0))

gi = group(DATA["ios"], "Val_McDunn", lambda r: r["tree"])
ga = group(DATA["android"], "Val_McDunn", lambda r: r["tree"])
ti = {t: stamp(v) for t, v in gi.items()}
ta = {t: stamp(v) for t, v in ga.items()}
made, ui, ua = [], set(), set()
for i, a in FORCE_PAIR:
    if i in gi and a in ga:
        ui.add(i); ua.add(a); made.append((0.0, i, a))
for gap, i, a in sorted((abs((ti[i] - ta[a]).total_seconds()), i, a)
                        for i in gi for a in ga if ti[i] and ta[a]
                        and abs((ti[i] - ta[a]).total_seconds()) <= 600):
    if i in ui or a in ua:
        continue
    ui.add(i); ua.add(a); made.append((gap, i, a))
dropped = []
for gap, i, a in sorted(made, key=lambda m: ti[m[1]]):
    label = f"i{i}/a{a}"
    (dropped if label in DROP else pairs).append(
        ("Val_McDunn", label, gi[i], ga[a], gap))

OUT, RESOLVED = [], []
seq = collections.Counter()
for plot, label, irec, arec, gap in pairs:
    seq[plot] += 1
    pid = ("McD" if "McDunn" in plot else "Stk") + f"{seq[plot]:03d}"
    for kind in ("dbh", "height"):
        ir, ar = irec[kind], arec[kind]
        if not ir and not ar:
            continue
        i, a = (ir[-1] if ir else None), (ar[-1] if ar else None)
        conv = IN if kind == "dbh" else FT
        flags = []
        if len(ir) > 1: flags.append(f"ios-retakes:{len(ir)}")
        if len(ar) > 1: flags.append(f"android-retakes:{len(ar)}")
        for who, r in (("ios", i), ("android", a)):
            if r is None:
                flags.append(f"{who}-missing"); continue
            if r["mode"] == "typed" or r["method"] in ("manualEntry", "manualVisual"):
                flags.append(f"{who}-typed")
            if kind == "dbh":
                src = r.get("recomputed_source")
                if src is None:
                    flags.append(f"{who}-no-raw")
                elif src == "bundle-auto":
                    flags.append(f"{who}-auto")
        gt_i = i["truth"] if i else None
        gt_a = a["truth"] if a else None
        # THE APPLICATION'S OWN NUMBER IS THE MEASUREMENT. What is being
        # validated is the app a cruiser carries, and the app's number is the
        # one it wrote down in the field — so that is what goes in the table,
        # for diameters exactly as it already did for heights.
        #
        # The from-raw recomputation still runs, and is kept beside each
        # reading as `*_value_recomputed`. It earns its keep as a CHECK — it is
        # how the wrong-guide-axis readings were found — but it is a
        # reconstruction, and a reconstruction must not be reported as though
        # the handset had displayed it.
        #
        # These 100 stems were captured before the tangent identity shipped, so
        # the diameters here are the chord identity's. That is a fact about
        # when the data was taken, not something to paper over: see
        # `deprecated/README.md` and the limitation stated with the results.
        iv = i["value"] if i else None
        av = a["value"] if a else None

        # One tape, resolved. See the module docstring for the rule.
        if gt_i is None and gt_a is not None:
            truth, why = gt_a, "tape-from-android"
        elif gt_a is None and gt_i is not None:
            truth, why = gt_i, "tape-from-ios"
        elif gt_i is None:
            truth, why = None, "tape-missing"
        elif abs(gt_i - gt_a) <= 0.02:
            truth, why = gt_i, "tape-agreed"
        else:
            flags.append("TAPE-MISMATCH")
            rel = abs(gt_i - gt_a) / max(gt_i, gt_a)
            meas = [x for x in (iv, av) if x]
            # THE MEASUREMENTS DO NOT GET A VOTE. An earlier version of this
            # resolved a large disagreement by taking whichever candidate lay
            # closer to the mean of the two phone readings, on the reasoning
            # that two independent instruments agreeing with one of them is
            # evidence. It is evidence — and it is also a rule that can only
            # ever move the reference TOWARDS the device under test, so it
            # cannot increase measured error and is structurally incapable of
            # finding the instrument wrong. It fired twice and rescued a
            # disaster both times: McD009 went from +53.5 % / +45.7 % to
            # −5.6 % / −10.3 %, McD015 from +21.8 % / +25.9 % to −3.3 % / 0.0 %.
            # Excluding those stems, which is what the sensitivity analyses
            # tested, cannot detect the problem, because the problem is in the
            # values that were KEPT.
            #
            # So the reference is now settled without consulting the phones at
            # all. A gap that large means one of the two records is a typing
            # error and there is no way to tell which from the tape alone, so
            # the stem's truth is unrecoverable and it is dropped. A small gap
            # takes the MEAN of the two records: symmetric, device-neutral, and
            # never larger than half the disagreement, which is under 2 % here.
            if rel > TAPE_DECIDE_ABOVE:
                truth, why = None, "tape-unrecoverable"
                RESOLVED.append((pid, kind, gt_i, gt_a, None, meas, why))
            else:
                truth, why = (gt_i + gt_a) / 2.0, "tape-mean"
                RESOLVED.append((pid, kind, gt_i, gt_a, truth, meas, why))

        def cell(v):
            return "" if v is None else f"{v:.3f}"

        def imp(v):
            return "" if v is None else f"{v/conv:.2f}"

        OUT.append({
            "plot": "McDunn" if "McDunn" in plot else "Starker",
            "pair_id": pid,
            "tree_name": label if "Starker" in plot else "",
            "tree_ios": (i or a or {}).get("tree", "") if i is None else i["tree"],
            "tree_android": a["tree"] if a else "",
            "kind": kind,
            "unit_metric": "cm" if kind == "dbh" else "m",
            "unit_imperial": "in" if kind == "dbh" else "ft",
            "truth": cell(truth), "truth_imp": imp(truth), "truth_source": why,
            "truth_ios": cell(gt_i), "truth_android": cell(gt_a),
            "truth_ios_imp": imp(gt_i), "truth_android_imp": imp(gt_a),
            "ios_value": cell(iv), "android_value": cell(av),
            "ios_value_imp": imp(iv), "android_value_imp": imp(av),
            "ios_ratio": f"{iv/truth:.4f}" if iv and truth else "",
            "android_ratio": f"{av/truth:.4f}" if av and truth else "",
            "ios_value_source": ("as-recorded" if iv is not None else ""),
            "android_value_source": ("as-recorded" if av is not None else ""),
            "ios_value_recomputed": cell(i.get("recomputed") if i and kind == "dbh"
                                         else None),
            "android_value_recomputed": cell(a.get("recomputed") if a and kind == "dbh"
                                             else None),
            "ios_sigma": cell(i["sigma"] if i else None),
            "android_sigma": cell(a["sigma"] if a else None),
            "ios_confidence": (i["conf"] if i else ""),
            "android_confidence": (a["conf"] if a else ""),
            "ios_capture_mode": (i["mode"] if i else ""),
            "android_capture_mode": (a["mode"] if a else ""),
            "ios_time": (f"{i['t']:%Y-%m-%d %H:%M:%S}" if i else ""),
            "android_time": (f"{a['t']:%Y-%m-%d %H:%M:%S}" if a else ""),
            "pair_gap_s": f"{gap:.0f}",
            "species": (i or a or {}).get("species", "") if i is None else i["species"],
            "flags": ";".join(flags),
        })

path = os.path.join(HERE, "final_pairs.csv")
with open(path, "w", newline="", encoding="utf-8") as fh:
    w = csv.DictWriter(fh, fieldnames=list(OUT[0].keys()))
    w.writeheader()
    w.writerows(OUT)

print("=" * 78)
print("FINAL TABLE")
print("=" * 78)
print(f"  {path}")
print(f"  {len(OUT)} rows, {len(pairs)} trees, {len(dropped)} dropped")
for p in ("McDunn", "Starker"):
    sub = [r for r in OUT if r["plot"] == p]
    print(f"    {p}: {len({r['pair_id'] for r in sub})} trees, {len(sub)} rows")
print("    dropped: " + ", ".join(d[1] for d in dropped))

print("\n" + "=" * 78)
print("THE TAPE, WHERE THE TWO PHONES HELD DIFFERENT NUMBERS")
print("=" * 78)
print(f"  {'pair':8s} {'kind':7s} {'iOS tape':>9s} {'Android':>9s} "
      f"{'measured':>18s}  {'kept':>9s}  rule")
for pid, kind, gi_, ga_, kept, meas, why in RESOLVED:
    c = IN if kind == "dbh" else FT
    ms = " ".join(f"{m/c:.1f}" for m in meas) or "—"
    kepts = "dropped" if kept is None else f"{kept/c:.2f}"
    print(f"  {pid:8s} {kind:7s} {gi_/c:9.2f} {ga_/c:9.2f} {ms:>18s}  "
          f"{kepts:>9s}  {why}")

print("\n" + "=" * 78)
print("MEASURED / TAPE — as the two handsets reported it")
print("=" * 78)
print("  Cells the cruiser typed rather than measured are left out; they are")
print("  the tape written back to itself, not an independent reading.")


def series(rows, who, kind, plot=None):
    return [float(r[f"{who}_ratio"]) for r in rows
            if r["kind"] == kind and r[f"{who}_ratio"]
            and (plot is None or r["plot"] == plot)
            and f"{who}-typed" not in r["flags"]]


for kind in ("dbh", "height"):
    print(f"\n  {kind}")
    for p in ("McDunn", "Starker", None):
        for who in ("ios", "android"):
            v = series(OUT, who, kind, p)
            if not v:
                continue
            err = [abs(x - 1) for x in v]
            print(f"    {(p or 'BOTH'):8s} {who:8s} n={len(v):3d}  "
                  f"median {st.median(v):.3f}   mean {st.mean(v):.3f}   "
                  f"sd {st.pstdev(v):.3f}   median|err| {st.median(err)*100:4.1f}%")

print("\n" + "=" * 78)
print("HOW EACH VALUE WAS OBTAINED")
print("=" * 78)
src = collections.Counter(r[f"{w}_value_source"] for r in OUT for w in ("ios", "android")
                          if r["kind"] == "dbh" and r[f"{w}_value_source"])
print("  diameters: " + ", ".join(f"{k} {v}" for k, v in src.most_common()))
ts = collections.Counter(r["truth_source"] for r in OUT)
print("  tape:      " + ", ".join(f"{k} {v}" for k, v in ts.most_common()))
fl = collections.Counter(f for r in OUT for f in r["flags"].split(";") if f)
print("  flags:     " + ", ".join(f"{k} {v}" for k, v in fl.most_common()))
