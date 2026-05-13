# ForestiX Monorepo

ForestiX now keeps the native iOS app, native Android app, and shared cross-platform contracts in one repository.

## Layout

- `ios/` — iOS Swift/SwiftUI app and tests
- `android/` — Android native app and tests
- `shared/` — cross-platform specs, golden fixtures, and test vectors

## Daily workflow

- Open the iOS app from `ios/` in Xcode.
- Open the Android app from `android/` in Android Studio.
- Put platform-neutral measurement contracts and golden test cases in `shared/`.
- When DBH/height/data behavior changes, update `shared/` first, then make both native test suites pass against the same fixture.

## Android verification

From `android/`:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon
```

## Shared DBH contract

The first shared contract is DBH chord/silhouette geometry:

- `shared/specs/dbh_chord_contract.md`
- `shared/fixtures/dbh_golden_cases.csv`

Both iOS and Android tests read the same fixture so DBH logic does not silently diverge between platforms.
