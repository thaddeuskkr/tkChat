plugins {
    java
    id("com.gradleup.shadow") version "9.5.1" apply false
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
    description = "Builds the deployable Velocity, Paper, and every Fabric-version artifact."
    dependsOn(":velocity:shadowJar", ":paper-1.21:jar", ":paper-26.2:jar")
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
    include("tkChat-*.jar")
    exclude("*-dev.jar", "*-sources.jar")
    into(layout.buildDirectory.dir("releases/${project.version}"))
}
