// Top-level build file. Plugin versions are declared here and applied
// per-module with `apply false`, mirroring the modular layout of the
// iOS SwiftPM package (Common / Models / Sensors / AR / UI ...).
plugins {
    // 8.7.x is the newest AGP line Gradle 8.9 supports (8.8 needs 8.10.2).
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
