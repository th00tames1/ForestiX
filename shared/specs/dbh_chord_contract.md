# DBH Chord/Silhouette Contract

The DBH chord estimator reads the visible trunk width from a depth map and uses
that width with depth-map-space camera intrinsics. The camera feed, UI, ARKit,
and ARCore remain platform-specific, but this contract must stay identical.

## Coordinate rules

- Depth pixels are in the depth map's own coordinate system.
- `fx`, `fy`, `cx`, and `cy` must be scaled into that same depth map coordinate system before DBH math runs.
- A row walk measures horizontal pixel width and must use `fx`.
- A column walk measures vertical pixel width and must use `fy`.
- Tap depth is the visible front surface of the trunk, not the cylinder axis.

## Diameter formula

Given:
- `w`: measured trunk silhouette width in depth-map pixels
- `h = w / 2`
- `S`: visible front-surface depth at the tap, in meters
- `f`: focal length in pixels along the measured axis

Use the pinhole tangent-cylinder solution:

```text
D = 2 * S * h * (h + sqrt(f*f + h*h)) / (f*f)
```

where `D` is DBH in meters before project calibration.

## Golden fixture expectation

Both apps should read `fixtures/dbh_golden_cases.csv` and recover the expected
DBH for each synthetic cylinder within the fixture tolerance. These cases are
parametric rather than raw depth dumps so they stay small and easy to review.
