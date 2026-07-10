plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.hcjeong.forestix"
    // 35: androidx.core 1.16 (transitive via the 16 KB-aligned deps)
    // requires it. targetSdk stays 34 — no runtime behaviour change.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hcjeong.forestix"
        minSdk = 26          // ARCore minimum; matches "modern device" target
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // 16 KB page-size devices (Android 15+): native libs must ship
        // uncompressed + zip-aligned so the OS can map them directly.
        // Modern-AGP default, pinned explicitly.
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Persistence (mirrors Core Data + the JSONL/UserDefaults sidecar).
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // 1.1.2+ ships a 16 KB-aligned libdatastore_shared_counter.so.
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    // Compose's transitive graphics-path 1.0.1 predates the 16 KB ELF
    // alignment; 1.1.0 is aligned (libandroidx.graphics.path.so).
    implementation("androidx.graphics:graphics-path:1.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")

    // AR — ARCore + SceneView (Sceneform successor) for placing world
    // anchored markers and depth/plane hit-testing, mirroring ARKit +
    // RealityKit on the iOS side. ARCore ≥ 1.49 and SceneView 2.3.0
    // (bundles Filament 1.56) ship 16 KB-aligned .so files. NOTE:
    // SceneView 2.3.1+ needs Kotlin 2.2 / Compose 1.10 / compileSdk 35 —
    // beyond what Gradle 8.9 + Kotlin 2.0.20 support here.
    implementation("com.google.ar:core:1.54.0")
    implementation("io.github.sceneview:arsceneview:2.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
