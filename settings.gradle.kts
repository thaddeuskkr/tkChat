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

include("core", "velocity")

val paperTargets = listOf("1.21", "26.1", "26.2")

paperTargets.forEach { targetFamily ->
    val projectName = "paper-$targetFamily"
    include(projectName)
    project(":$projectName").projectDir = file("paper-targets/$projectName")
    project(":$projectName").buildFileName = "../../paper-platform/build.gradle.kts"
}

val fabricTargets = mapOf(
    "1.21" to "0.102.0+1.21",
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
