# DBH measurement contract

The normal measurement paths on iOS and Android share one numerical contract:
manual Adjust guides, AI-assisted guides, and depth-walk Auto all measure a
single registered depth frame. AI proposes guide positions; it does not predict
a diameter or modify the depth pixels.

## Sampling and conversion

Screen-horizontal guides are mapped through the frame's display transform to
either a depth row or a depth column. The corresponding focal component is
used: `fx` for a row, `fy` for a column. Missing, degenerate or oblique mappings
are refused rather than inferred from the scene.

For continuous boundary coordinates `left` and `right`, the sample contains
integer pixel centres in `[ceil(left), floor(right)]` at measurement height.
Only finite depths in `(0.001, 8]` metres are valid. At least three valid samples
are required, and their spatial median must be in `[0.3, 5]` metres. For an even
number of samples, the median is the mean of the two middle values.

With `w = right - left`, axis focal `f`, and spatial median `z`:

```
k = w / (2 f)
q = k (k + sqrt(1 + k²))
c = 1 - sqrt(3) / 2
diameter_m = 2 q z / (1 + c q)
```

This is a centred, uniform-lateral circular approximation. The correction
converts the aggregate depth, not individual sensor pixels. The normal paths
use the full span (100%); a narrow-window coefficient must not be substituted.

Depth-walk Auto requires valid boundaries on the measurement row. Neighbouring
rows grade boundary consistency but do not substitute a different height or
provide a different width for the displayed guides. The same span can be
passed to Adjust and produces the same raw diameter.

## AI placement

The current Auto alignment action runs YOLO26n once from the operator's guides.
Each side needs consistent mask and local depth support. A proposed shift over
10% of the original span is refused; an accepted side moves halfway toward the
candidate edge. Foreground occlusion retains both original guides. Unsupported
sides retain their original positions independently. Missing weights or a
moved camera retain the current placement and report that alignment is
unavailable. Model weights remain outside Git under the publication boundary.

## Compatibility and verification

Estimator epoch 8 identifies the shared full-span geometry, including depth
Auto. Round-post coefficients from another epoch are not applied. Saved trees
are not automatically rewritten; explicit replay/recompute remains a separate
action. The historical middle-half helper and selectable legacy estimators are
not the normal full-span calculation.

`FullSpanGeometryTests.swift` and `FullSpanGeometryTest.kt` use synthetic inputs
to cover the analytic conversion, exact pixel inclusion, even medians, invalid
inputs, unequal axis focals, Auto/Adjust parity, single-frame capture and
missing measurement rows. No field datasets are embedded in these tests.
