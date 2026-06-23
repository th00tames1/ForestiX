// Top-level build file. Plugin versions are declared here and applied
// per-module with `apply false`, mirroring the modular layout of the
// iOS SwiftPM package (Common / Models / Sensors / AR / UI ...).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
    id("com.google.devtools.ksp") version "2.0.20-1.0.25" apply false
}
