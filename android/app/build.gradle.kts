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

    // ON-DEVICE SEGMENTATION. The Auto diameter path can read the stem's
    // edges out of a YOLO-seg mask instead of walking the depth map; see
    // sensors/TreeSegmenter.kt. Same engine and same model file as iOS, so a
    // mask decoded on one phone is the mask decoded on the other. The weights
    // are gitignored (assets/models/README.md) and the app runs without them.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // AR — ARCore + SceneView (Sceneform successor) for placing world
    // anchored markers and depth/plane hit-testing, mirroring ARKit +
    // RealityKit on the iOS side.
    //
    // PINNED to the field-validated pair (ARCore 1.44 + SceneView 2.2.1).
    // The round-6 bump to ARCore 1.54 / SceneView 2.3.0 broke measurements
    // on-device (Samsung, Android 15/16): DBH read ~2× true and the height/
    // cylinder screen-centre hit landed off the crosshair. Source/bytecode
    // diff of SceneView 2.2.1↔2.3.0 and the ARCore 1.44↔1.54 API showed NO
    // convention change (hitTest coords, setDisplayGeometry, intrinsics,
    // depth-image contract all identical) — the behaviour delta is inside
    // ARCore's closed native client (the compiled-against SDK version is
    // negotiated with Play Services for AR and gates depth-map geometry /
    // depth-point hit sampling), so it can't be adapted to in app code with
    // confidence. Do NOT re-bump either artifact without re-running the
    // DBH/height field checks (dev-mode "geom" HUD line shows depth WxH +
    // fx + raw/smoothed distance for exactly this).
    //
    // 16 KB page-size status: ARCore 1.44's bundled .so files are already
    // 16 KB-aligned, so this rollback costs nothing there. SceneView 2.2.1's
    // Filament .so files are NOT yet aligned — that fix is DEFERRED until we
    // can take SceneView 2.3.1+ (needs the Kotlin 2.2 / Compose 1.10 /
    // Gradle ≥ 8.10 migration) AND its AR stack passes the field checks.
    // AGP 8.7.3, compileSdk 35, datastore 1.1.7, graphics-path 1.1.0 and
    // jniLibs.useLegacyPackaging=false are kept — safe and 16 KB-relevant.
    implementation("com.google.ar:core:1.44.0")
    implementation("io.github.sceneview:arsceneview:2.2.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
