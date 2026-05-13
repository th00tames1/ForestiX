plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "edu.oregonstate.forestrix"
    compileSdk = 35

    defaultConfig {
        applicationId = "edu.oregonstate.forestrix"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-android"

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.google.ar:core:1.53.0")

    testImplementation("junit:junit:4.13.2")
}
