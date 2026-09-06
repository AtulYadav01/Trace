plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    id("com.android.library") version "8.5.2" apply false
    id("com.android.application") version "8.5.2" apply false
}

allprojects {
    group = "dev.trace"
    // trace-core stays 0.1.0 (frozen). Modules that need a different version
    // (e.g. trace-android 0.2.0) override this in their own build script.
    version = "0.1.0-SNAPSHOT"

    repositories {
        google()
        mavenCentral()
    }
}
