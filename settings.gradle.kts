pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
        maven("https://maven.fabricmc.net/")
        maven("https://maven.ornithemc.net/releases")
        maven("https://repo.polyfrost.org/releases")
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }

    plugins {
        kotlin("jvm") version "2.4.21"
        kotlin("plugin.compose") version "2.4.21"
        id("me.modmuss50.mod-publish-plugin") version "2.2.0"
        id("dev.detekt") version "2.0.0-alpha.6"
        id("net.ltgt.errorprone") version "5.2.0"
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://maven.fabricmc.net/")
        maven("https://maven.ornithemc.net/releases")
        maven("https://repo.polyfrost.org/releases")
        maven("https://repo.polyfrost.org/snapshots")
        maven("https://redirector.kotlinlang.org/maven/compose-dev")
        google()
    }
}

stonecutter.create(rootProject) {
    versions(mapOf(
        "26.3-fabric" to "26.3",
        "26.2-fabric" to "26.2",
        "26.1-fabric" to "26.1",
        "1.21.11-fabric" to "1.21.11",
        "1.21.10-fabric" to "1.21.10",
        "1.21.8-fabric" to "1.21.8",
        "1.21.5-fabric" to "1.21.5",
        "1.21.4-fabric" to "1.21.4",
        "1.21.1-fabric" to "1.21.1",
        "1.8.9-ornithe" to "1.8.9",
    ))
    vcsVersion = "26.3-fabric"
}

include(":core")
include(":compose-ui")

rootProject.name = "PolyCompose"
