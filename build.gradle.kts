plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
}

allprojects {
    group = "dev.trace"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}
