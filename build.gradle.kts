import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    java
    id("com.gradleup.shadow") version "9.5.1" apply false
    id("com.modrinth.minotaur") version "2.9.0" apply false
}

abstract class VerifyReleaseVersion : DefaultTask() {
    @get:Input
    abstract val releaseTag: Property<String>

    @get:Input
    abstract val expectedVersion: Property<String>

    @TaskAction
    fun verify() {
        val tag = releaseTag.get().trim()
        if (tag.isNotEmpty()) {
            val taggedVersion = tag.removePrefix("v")
            check(taggedVersion == expectedVersion.get()) {
                "Release tag $tag does not match projectVersion ${expectedVersion.get()}"
            }
        }
    }
}

group = "dev.tkkr.tkchat"
version = providers.gradleProperty("projectVersion").get()

allprojects {
    group = rootProject.group
    version = rootProject.version
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion.set(JavaLanguageVersion.of(21))
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.release.set(21)
            options.compilerArgs.add("-parameters")
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}

tasks.register<Sync>("releaseArtifacts") {
    group = "build"
    description = "Builds the deployable Velocity, Paper-family, and every Fabric-version artifact."
    dependsOn(":velocity:shadowJar", ":paper-1.21:jar", ":paper-26.1:jar", ":paper-26.2:jar")
    dependsOn(gradle.includedBuilds.map { it.task(":build") })
    dependsOn(
        subprojects
            .filter { it.name.startsWith("fabric-") }
            .map { project ->
                val task = if (project.name.startsWith("fabric-26-")) "jar" else "remapJar"
                "${project.path}:$task"
            }
    )
    from(subprojects.map { it.layout.buildDirectory.dir("libs") })
    include("tkChat-*-${project.version}.jar")
    exclude("*-dev.jar", "*-sources.jar")
    into(layout.buildDirectory.dir("releases/${project.version}"))
}

tasks.register<VerifyReleaseVersion>("verifyReleaseVersion") {
    group = "verification"
    description = "Checks that the release tag matches projectVersion."
    releaseTag.set(providers.environmentVariable("RELEASE_TAG").orElse(""))
    expectedVersion.set(project.version.toString())
}

tasks.register("publishModrinth") {
    group = "publishing"
    description = "Publishes every Velocity, Paper, and Fabric release jar to Modrinth."
    dependsOn("verifyReleaseVersion")
    dependsOn(
        subprojects
            .filter { project ->
                project.name == "velocity"
                        || project.name.startsWith("paper-")
                        || project.name.startsWith("fabric-")
            }
            .map { project -> "${project.path}:modrinth" }
    )
}
