#!/usr/bin/env python3
"""The estimator comparison, redone against the ACTUAL shipped baseline.

`sweep.py` modelled the shipped fit as "median depth over the whole bracket".
It is not. `bracketChordFit` medians the bracket's MIDDLE HALF — `bracketCoreRange`
drops a quarter off each end, because the handles sit on the silhouette and the
end pixels read the background. That trim is itself a partial near-face
correction: dropping the outer quarters removes the pixels furthest down the
curve. So the earlier sweep credited the new geometry with a gain the app
already has, and every number in it that involved the baseline is suspect.

This reproduces the shipped path exactly:

    iLo = ceil(lo * extent), iHi = floor(hi * extent)
    if iHi - iLo >= 8:  drop (span/4) from each end        <- bracketCoreRange
    z = median of valid depths over that inner range
    d = w * z / (f - w/2),  w = (hi - lo) * extent

Validity: the recorder canonicalises confidence from depth, so `confidence >= 1`
is exactly `depth is valid` and needs no separate channel.

The candidates are then layered on that same sample, so every row differs from
the baseline only in what it is meant to differ in.
"""
import json, glob, os, csv, struct, math, datetime as dt, statistics as st

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


def core_range(i_lo, i_hi):
    """bracketCoreRange, verbatim."""
    span = i_hi - i_lo
    if span < 8:
        return i_lo, i_hi
    q = span // 4
    return i_lo + q, i_hi - q


def scanline(frame, dirpath, lo, hi, plat):
    """(core depths, full-span depths, span px, fx) — the shipped sample first.

    THE TWO PLATFORMS DO NOT MEASURE THE SAME SPAN, and modelling them the same
    way was the second error in this analysis. iOS keeps the span fractional —
    `widthPx = (hi - lo) * extent` — and uses ceil/floor only to pick which
    pixels to median. Android rounds each handle to a pixel first and takes
    `w = b - a`, an integer. On Android's 160x90 depth map a stem spans ~17 px,
    so one pixel of rounding is ~6 % of the diameter; on iOS's 256x192 the same
    rounding would matter less, and iOS does not do it anyway.
    """
    w, h = frame.get("width"), frame.get("height")
    axis, fx, df = frame.get("axis"), f2(frame.get("fx")), frame.get("depth_file")
    if not (w and h and fx and df):
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
    if plat == "android":
        a = min(max(round(lo * extent), 0), extent - 1)
        b = min(max(round(hi * extent), 0), extent - 1)
        span = float(b - a)
        i_lo, i_hi = a, b
    else:
        span = (hi - lo) * extent
        i_lo = max(0, math.ceil(lo * extent))
        i_hi = min(extent - 1, math.floor(hi * extent))
    if span < 2 or fx - span / 2 <= 1 or i_hi < i_lo:
        return None

    def sample(a, b):
        out = []
        for i in range(a, b + 1):
            px, py = (i, fixed) if axis == "row" else (fixed, i)
            v = dep[py * w + px]
            if v > 0:
                out.append(v)
        return out

    c_lo, c_hi = core_range(i_lo, i_hi)
    core = sample(c_lo, c_hi)
    full = sample(i_lo, i_hi)
    if len(core) < 3:
        return None
    if not (0.3 <= st.median(core) <= 5.0):
        return None
    return core, full, span, fx


def pct(vals, p):
    s = sorted(vals)
    if len(s) == 1:
        return s[0]
    i = p * (len(s) - 1)
    lo = int(math.floor(i))
    return s[lo] + (s[min(lo + 1, len(s) - 1)] - s[lo]) * (i - lo)


def chord(z, span, fx):
    return span * z / (fx - span / 2) * 100.0


def tangent(z, span, fx):
    k = span / (2 * fx)
    return 2 * z * k * (k + math.sqrt(k * k + 1)) * 100.0


def tangent_selfconsistent(z_med, span, fx, offset):
    """Tangent with the median walked forward to the near face by offset*R."""
    k = span / (2 * fx)
    g = 2 * k * (k + math.sqrt(k * k + 1))
    z0 = z_med
    for _ in range(12):
        r = g * z0 / 2.0
        z0 = z_med - offset * r
        if z0 <= 0.05:
            return None
    return g * z0 * 100.0


# The median of a uniform sample across a cylinder's face sits 0.134 R behind
# the near face. Over the MIDDLE HALF only, the sample runs |x| <= R/2, whose
# median sits at |x| = R/4: R(1 - sqrt(1 - 1/16)) = 0.0317 R. The shipped trim
# already removed most of that offset, which is exactly why the baseline
# mattered.
OFFSET_FULL = 0.134
OFFSET_CORE = 1 - math.sqrt(1 - 1 / 16.0)

CANDIDATES = [
    ("SHIPPED  chord + core median", lambda c, f_, s, fx: chord(st.median(c), s, fx)),
    ("chord + full-span median", lambda c, f_, s, fx: chord(st.median(f_), s, fx) if f_ else None),
    ("tangent + core median", lambda c, f_, s, fx: tangent(st.median(c), s, fx)),
    ("tangent + core p10", lambda c, f_, s, fx: tangent(pct(c, 0.10), s, fx)),
    ("tangent + core min", lambda c, f_, s, fx: tangent(min(c), s, fx)),
    ("tangent + 0.032R (core, 0-param)",
     lambda c, f_, s, fx: tangent_selfconsistent(st.median(c), s, fx, OFFSET_CORE)),
    ("tangent + 0.134R (core, mismatched)",
     lambda c, f_, s, fx: tangent_selfconsistent(st.median(c), s, fx, OFFSET_FULL)),
]


def captures(plat):
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
        if not br.get("enabled"):
            continue
        lo, hi = f2(br.get("left")), f2(br.get("right"))
        if lo is None or hi is None:
            continue
        lo, hi = min(lo, hi), max(lo, hi)
        dirp = os.path.dirname(path)
        lines = [x for x in (scanline(fr, dirp, lo, hi, plat) for fr in (d.get("frames") or [])) if x]
        if not lines:
            continue
        out.append(dict(tree=(m.get("context") or {}).get("tree_number"),
                        t=T(m.get("created_at")), lines=lines,
                        live=f2((m.get("result_live") or {}).get("value"))))
    return out


ROWS = [r for r in csv.DictReader(open(os.path.join(HERE, "final_pairs.csv")))
        if r["kind"] == "dbh"]
TAPE = {}
for r in ROWS:
    for who, key in (("ios", "tree_ios"), ("android", "tree_android")):
        if r[key] and r[f"{who}_time"]:
            TAPE[(who, int(r[key]))] = (f2(r["truth"]), r["pair_id"], r["plot"],
                                        dt.datetime.strptime(r[f"{who}_time"],
                                                             "%Y-%m-%d %H:%M:%S"))

MATCHED = {}
for plat in ("ios", "android"):
    rows = []
    for c in captures(plat):
        got = TAPE.get((plat, c["tree"]))
        if not got or not got[0]:
            continue
        truth, pid, plot, when = got
        if abs((c["t"].replace(tzinfo=None) - when).total_seconds()) > 90:
            continue
        rows.append({**c, "truth": truth, "pair_id": pid, "plot": plot})
    MATCHED[plat] = rows


def value(r, fn):
    per = [x for x in (fn(c, f_, s, fx) for c, f_, s, fx in r["lines"]) if x]
    return st.median(per) if per else None


if __name__ == "__main__":
    print("=" * 96)
    print("DOES THE CORRECTED BASELINE REPRODUCE WHAT THE APP STORED?")
    print("=" * 96)
    print("  If the model of the shipped fit is right, it should land on the")
    print("  bundle's own live value. This is the check the old sweep skipped.")
    for plat in ("ios", "android"):
        for label, fn in (("core median (claimed shipped)", CANDIDATES[0][1]),
                          ("full-span median (old model)", CANDIDATES[1][1])):
            rs = [value(r, fn) / r["live"] for r in MATCHED[plat]
                  if r["live"] and value(r, fn)]
            within = sum(1 for x in rs if abs(x - 1) < 0.005)
            print(f"  {plat:8s} {label:32s} median {st.median(rs):.4f}   "
                  f"within 0.5% on {within}/{len(rs)}")

    print("\n" + "=" * 96)
    print("EVERY CANDIDATE, AGAINST THE TAPE, ON THE CORRECTED BASELINE")
    print("=" * 96)
    for plat in ("ios", "android"):
        rows = MATCHED[plat]
        print(f"\n  {plat.upper()}   n={len(rows)}")
        print(f"    {'estimator':36s} {'bias':>8s} {'median':>8s} {'RMSE':>9s} {'MAE':>8s}")
        for name, fn in CANDIDATES:
            rel, err = [], []
            for r in rows:
                d = value(r, fn)
                if d is None:
                    continue
                rel.append((d - r["truth"]) / r["truth"] * 100)
                err.append((d - r["truth"]) / IN)
            if not rel:
                continue
            print(f"    {name:36s} {st.mean(rel):+7.2f}% {st.median(rel):+7.2f}% "
                  f"{math.sqrt(st.mean([e*e for e in err])):8.2f}in "
                  f"{st.mean([abs(e) for e in err]):7.2f}in")
