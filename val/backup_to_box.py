#!/usr/bin/env python3
"""Mirror the validation study into Box, so it can be picked up on another machine.

WHAT GOES AND WHY. Enough to re-run every analysis from scratch, plus the
finished outputs so nothing has to be re-run to read the result:

  data/        the two phone exports and the derived 100-stem table — the input
               to everything downstream
  analysis/    the scripts that build the table and settle the estimator
               question, including deprecated/ and its note on which older
               analyses are void
  paper/       core.py, every figure script, the figures themselves (PNG + PDF),
               the result tables, and the deck
  raw/         the depth-frame corpus, ~730 MB — OPTIONAL, and only needed to
               rebuild the table from the frames rather than read it

THE PATHS DIFFER BY MACHINE. Box mounts at ~/Library/CloudStorage/Box-Box on
this Mac and at C:\\Users\\<user>\\Box on Windows, so the same folder is
`work/Forestix` under whichever root the machine has. Nothing here writes an
absolute path into a file.

MIRRORS, NEVER DELETES. Files removed locally are left alone in Box rather than
cleaned up, because the destination is shared storage and a sync tool that
deletes is a sync tool that eventually deletes the wrong thing.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import shutil
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
BOX_ROOTS = [
    os.path.expanduser("~/Library/CloudStorage/Box-Box"),
    os.path.expanduser("~/Box"),
]
DEST_REL = os.path.join("work", "Forestix")

# (source, destination, description, include-by-default)
SETS = [
    (os.path.join(HERE, "analysis"), "analysis", "analysis scripts + notes", True),
    (os.path.join(HERE, "paper"), "paper", "figures, results, deck, scripts", True),
    (os.path.join(HERE, "raw"), "raw", "raw depth captures (~730 MB)", False),
]

# Inside those trees, what never travels: caches, and the OS's own droppings.
SKIP_DIRS = {"__pycache__", ".ipynb_checkpoints", ".git"}
SKIP_FILES = {".DS_Store"}


def box_root() -> str | None:
    for r in BOX_ROOTS:
        if os.path.isdir(r):
            return r
    return None


def digest(path: str, chunk: int = 1 << 20) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        while True:
            b = fh.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def same(a: str, b: str) -> bool:
    """Cheap first, exact second. Box rewrites mtimes on sync, so size alone is
    not enough and a hash on every file of a 730 MB tree is too slow — compare
    size, then hash only when the sizes agree but the mtime does not."""
    if not os.path.exists(b):
        return False
    sa, sb = os.stat(a), os.stat(b)
    if sa.st_size != sb.st_size:
        return False
    if abs(sa.st_mtime - sb.st_mtime) < 2:
        return True
    return digest(a) == digest(b)


def mirror(src: str, dst: str, dry: bool) -> tuple[int, int, int]:
    copied = skipped = 0
    total_bytes = 0
    for root, dirs, files in os.walk(src):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        rel = os.path.relpath(root, src)
        target_dir = dst if rel == "." else os.path.join(dst, rel)
        for name in sorted(files):
            if name in SKIP_FILES:
                continue
            s = os.path.join(root, name)
            d = os.path.join(target_dir, name)
            if same(s, d):
                skipped += 1
                continue
            if not dry:
                os.makedirs(target_dir, exist_ok=True)
                shutil.copy2(s, d)
            copied += 1
            total_bytes += os.path.getsize(s)
    return copied, skipped, total_bytes


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--with-raw", action="store_true",
                    help="also mirror the ~730 MB depth-frame corpus")
    ap.add_argument("--dry-run", action="store_true",
                    help="report what would be copied, write nothing")
    args = ap.parse_args()

    root = box_root()
    if root is None:
        print("Box is not mounted on this machine. Looked for:", file=sys.stderr)
        for r in BOX_ROOTS:
            print(f"  {r}", file=sys.stderr)
        return 1
    dest = os.path.join(root, DEST_REL)
    print(f"Box     {root}")
    print(f"Target  {os.path.join(DEST_REL)}"
          + ("   (DRY RUN — nothing will be written)" if args.dry_run else ""))
    print()

    # The two phone exports live loose in val/; they are the input to
    # build_final.py and are useless separated from it.
    data_src = [os.path.join(HERE, f) for f in sorted(os.listdir(HERE))
                if f.startswith("quick-measure") and f.endswith(".csv")]
    if data_src:
        target = os.path.join(dest, "data")
        n = 0
        for s in data_src:
            d = os.path.join(target, os.path.basename(s))
            if same(s, d):
                continue
            if not args.dry_run:
                os.makedirs(target, exist_ok=True)
                shutil.copy2(s, d)
            n += 1
        print(f"  data/      {len(data_src)} phone export(s), {n} updated")

    total = 0
    for src, rel, desc, default_on in SETS:
        if rel == "raw" and not args.with_raw:
            if os.path.isdir(src):
                print(f"  raw/       SKIPPED ({desc}) — pass --with-raw to include")
            continue
        if not os.path.isdir(src):
            print(f"  {rel + '/':10s} missing locally, skipped")
            continue
        t0 = time.time()
        copied, skipped, nbytes = mirror(src, os.path.join(dest, rel), args.dry_run)
        total += nbytes
        print(f"  {rel + '/':10s} {copied} copied, {skipped} already current"
              f"   {human(nbytes)}   {time.time() - t0:.1f}s   ({desc})")

    readme = os.path.join(dest, "README.md")
    if not args.dry_run:
        os.makedirs(dest, exist_ok=True)
        with open(readme, "w") as fh:
            fh.write(README.format(stamp=time.strftime("%Y-%m-%d %H:%M"),
                                   raw="included" if args.with_raw else "NOT included"))
    print(f"\n  total copied {human(total)}")
    print("  Box will now sync in the background; large trees take a while.")
    return 0


README = """# ForestiX validation — analysis package

Mirrored {stamp}. Raw depth captures: {raw}.

## What is here

- `data/` — the two phone exports (`quick-measure-*.csv`) and, under
  `analysis/`, the derived 100-stem table `final_pairs.csv`. Everything
  downstream reads that table.
- `analysis/` — builds the table and settles the estimator question.
  `build_final.py` is the one to read first: it documents the pairing, the
  reference-resolution rule and the three dropped stems. `deprecated/README.md`
  says which older analyses are void and why — do not cite anything in there.
- `paper/` — `core.py` (the sample, the statistics, the house style) plus one
  script per analysis, the figures as PNG and PDF, the result tables, and the
  deck.

## Running it on another machine

    python3 -m pip install numpy pandas scipy statsmodels matplotlib python-pptx
    cd paper
    python3 core.py            # sanity check: prints n and the headline stats
    python3 fig02_accuracy.py  # any figure script; they all import core
    python3 build_deck.py      # rebuilds the deck from whatever is on disk

`analysis/build_final.py` needs `raw/`, which is only in this package if it was
mirrored with `--with-raw`. Everything in `paper/` runs from `final_pairs.csv`
alone.

## Reading the results

Height figures report AGREEMENT with a 3-point laser, not accuracy: the laser
inverts the same tangent geometry the app does, and 52-62 % of each handset's
height error is shared between the two handsets (shared sd 5.04 ft). Diameter
errors are mostly independent between handsets (19-29 % shared, ceiling 1.03 in
of sd), so the diameter scatter is instrument, not stem shape. Results describe
these two stands; the stands differ in tree size and collection day as well as
in stand, so a site difference is not a stand effect.
"""


if __name__ == "__main__":
    raise SystemExit(main())
