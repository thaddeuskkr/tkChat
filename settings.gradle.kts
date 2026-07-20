pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://maven.fabricmc.net/")
        maven("https://mvn-repo.arim.space/affero-gpl3/")
        maven("https://mvn-repo.arim.space/lesser-gpl3/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("fabric-loom") version "1.17.16"
}

rootProject.name = "tkChat"

include("core", "velocity", "paper-1.21", "paper-26.2")

val fabricTargets = mapOf(
    "1.21" to "0.102.0+1.21",
    "1.21.1" to "0.116.14+1.21.1",
    "1.21.2" to "0.106.1+1.21.2",
    "1.21.3" to "0.114.1+1.21.3",
    "1.21.4" to "0.119.4+1.21.4",
    "1.21.5" to "0.128.2+1.21.5",
    "1.21.6" to "0.128.2+1.21.6",
    "1.21.7" to "0.129.0+1.21.7",
    "1.21.8" to "0.136.1+1.21.8",
    "1.21.9" to "0.134.1+1.21.9",
    "1.21.10" to "0.138.4+1.21.10",
    "1.21.11" to "0.141.5+1.21.11",
    "26.1" to "0.145.1+26.1",
    "26.1.1" to "0.145.4+26.1.1",
    "26.1.2" to "0.155.2+26.1.2",
    "26.2" to "0.155.2+26.2"
)

fabricTargets.forEach { (minecraftVersion, fabricApiVersion) ->
    val projectName = "fabric-${minecraftVersion.replace('.', '-')}"
    include(projectName)
    project(":$projectName").projectDir = file("fabric-targets/$projectName")
    project(":$projectName").buildFileName = "../../fabric-platform/build.gradle.kts"
}
