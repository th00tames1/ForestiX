# Android Port Notes

## Summary

The iOS app relies on ARKit, scene depth, and LiDAR mesh reconstruction. Android should not assume equivalent hardware. The closest practical path is ARCore with Depth/Raw Depth on supported devices, plus explicit fallback workflows for forest conditions.

## Android sensor decision

Use ARCore as `optional`, not `required`.

Reasons:

- ARCore-supported devices are certified for camera, sensors, CPU, and motion tracking, but not every supported device has Depth API support.
- ARCore Depth uses depth-from-motion and can merge hardware depth sensors when present; it does not require ToF/LiDAR hardware.
- Raw Depth is the better DBH source because it provides higher-accuracy values for some pixels and a confidence image, while full Depth fills more pixels through smoothing/interpolation.
- ARCore tracking and plane detection depend on feature points. Forests can help because bark is textured, but moving leaves, grass, dim light, glare, and weak ground planes can hurt tracking.

Primary Android equivalents:

| iOS ForestiX piece | Android port |
| --- | --- |
| `ARKitSessionManager` sceneDepth | `ArCoreDepthBridge` Raw Depth + confidence |
| LiDAR DBH scan | Raw Depth chord/silhouette DBH |
| VIO walk-off height | ARCore pose + IMU/tangent state machine |
| GPS averaging | Android location samples + same ENU median math |
| Offset-from-opening | ARCore VIO displacement + clean GPS fix |
| AR plot ring | Pure ring vertices now; render through ARCore later |

## Implemented Android app flow

- Home screen opens the active plot tally, not a synthetic sample runner.
- `Add Tree` follows the iOS Species -> DBH -> Height -> Extras -> Review flow.
- DBH and Height scan screens return their real accepted results into the current tree form.
- Tree records persist on-device and survive app restart.
- Plot tally recomputes live stats from saved records and supports soft delete / undo.
- CSV export writes tree-level and plot-level files from the saved records.

## Forest reality check

AR can be useful, but the app should not require a successful ground scan before field work starts.

Good AR uses in forest:

- DBH at close range when Raw Depth confidence is high.
- Height walk-off if tracking remains normal.
- Plot boundary visualization as an aid, not the legal source of the in/out call.

Risky AR uses in forest:

- Requiring a detected horizontal plane before the user can work.
- Depending on AR mesh coverage for every plot boundary vertex.
- Using Geospatial Depth/VPS as a forest baseline; it is designed for VPS/Streetscape-covered areas, not remote stands.

The current Android project therefore keeps the numeric forestry workflow alive even when AR is unavailable.

## Sources Checked

- ARCore Fundamental Concepts: https://developers.google.com/ar/develop/fundamentals
- ARCore Raw Depth: https://developers.google.com/ar/develop/java/depth/raw-depth
- ARCore Depth overview: https://developers.google.com/ar/develop/depth
- ARCore Environment limitations: https://developers.google.com/ar/design/environment/definition
- ARCore Geospatial Depth limitations: https://developers.google.com/ar/develop/unity-arf/depth/geospatial-depth
- ARCore supported devices: https://developers.google.com/ar/discover/supported-devices
- Android Gradle Plugin 8.7 compatibility: https://developer.android.com/build/releases/agp-8-7-0-release-notes
