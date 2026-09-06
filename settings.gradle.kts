pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Lets Gradle auto-provision the JDK 17 toolchain (kotlin { jvmToolchain(17) })
    // on machines that don't already have one installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "trace"

include("trace-core")
include("trace-android")
include("trace-demo")
