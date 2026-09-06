plugins {
    id("com.android.library")
    kotlin("android")
}

version = "0.2.0"

android {
    namespace = "dev.trace.android"
    compileSdk = 34

    defaultConfig {
        // minSdk 26: trace-core uses java.nio.file (Path, Files, StandardOpenOption),
        // which Android provides natively from API 26. Do not lower without either
        // core changes (core is frozen) or library desugaring verification.
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // trace-core is consumed, not re-exported: none of its types appear in
    // trace-android's public API.
    implementation(project(":trace-core"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
}
