# ForestiX Android

Native Android port of the ForestiX iOS timber cruising app.

This first Android folder keeps the iOS app's module split:

- `models`: Project, Plot, Tree, cruise design, and measurement result types.
- `inventory`: fixed-area and BAF plot math.
- `measurement`: DBH chord/silhouette estimator and VIO/tangent height estimator.
- `storage`: on-device project/plot/tree persistence.
- `export`: tree-level and plot-level CSV output.
- `positioning`: GPS averaging and offset-from-opening under canopy.
- `ar`: pure plot-boundary geometry.
- `sensors`: Android/ARCore adapters for capability checks and Raw Depth frames.

## Why ARCore is optional

The iOS app uses ARKit LiDAR scene depth. Android does not have one consistent LiDAR equivalent across phones, so this port uses ARCore Depth when available and keeps manual/tape fallbacks active everywhere.

The DBH path is:

1. ARCore Raw Depth burst.
2. Chord/silhouette DBH calculation from projected stem width and depth.
3. Quality tier from frame spread.
4. Manual DBH entry when depth is missing or confidence is poor.

The height path is:

1. ARCore VIO walk-off tangent.
2. Tape-distance tangent fallback when AR tracking degrades.

## Open in Android Studio

Open the repository's `android/` folder in Android Studio.

The project is configured for:

- Android Gradle Plugin `8.6.0`
- Gradle `8.7`
- JDK `17`
- ARCore SDK `1.53.0`
- `compileSdk` / `targetSdk` `35`
- `minSdk` `24`

If Android Studio asks for an SDK, use your normal Android SDK location or let Android Studio install one and update `local.properties`.

## Verify

From this folder:

```bat
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

The debug APK is produced at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Current status

This is a working Android project with the iOS-style tally flow wired through persistence:

- `Add Tree` opens a Species -> DBH -> Height -> Extras -> Review stepper.
- The DBH step can open the ARCore camera screen, draw the fixed DBH guide line/crosshair, read live Raw Depth frames, capture a burst, and feed that burst into `DbhChordEstimator`.
- The Height step appears when the height-subsample rule asks for it. It can open the ARCore camera screen, anchor the trunk from a center hit-test, use ARCore VIO for walk-off distance, use the IMU pitch buffer for top/base aim, and feed the tuple into `HeightEstimator`.
- Accepted DBH and height results write into the tree being reviewed, not a throwaway sample.
- Saving writes the tree record to on-device storage; the plot tally reloads after app restart.
- Tree rows support soft delete / undo.
- CSV export writes tree-level and plot-level files from the saved records.
- Manual DBH/height entry remains available when ARCore tracking or Depth support is not good enough.
