import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class GeneratePluginBuildInfo : DefaultTask() {
    @get:Input
    abstract val pluginVersion: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val escapedVersion = pluginVersion.get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val sourceFile = outputFile.get().asFile
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(
            """package dev.tkkr.tkchat.velocity;

public final class BuildInfo {
    public static final String VERSION = "$escapedVersion";

    private BuildInfo() {
    }
}
"""
        )
    }
}

plugins {
    java
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
}

dependencies {
    implementation(project(":core"))

    compileOnly("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")
    compileOnly("net.luckperms:api:5.5")
    compileOnly("space.arim.libertybans:bans-api:1.2.0-M1-SNAPSHOT")

    implementation("org.mariadb.jdbc:mariadb-java-client:3.5.8") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.zaxxer:HikariCP:7.0.2") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation("com.rabbitmq:amqp-client:5.34.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.21.5"))
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(platform("org.junit:junit-bom:6.0.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.velocitypowered:velocity-api:4.1.0-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))

val artifactVersion = project.version.toString()
val generatedBuildInfoDirectory = layout.buildDirectory.dir("generated/sources/build-info/java")
val generateBuildInfo = tasks.register<GeneratePluginBuildInfo>("generateBuildInfo") {
    pluginVersion.set(artifactVersion)
    outputFile.set(generatedBuildInfoDirectory.map {
        it.file("dev/tkkr/tkchat/velocity/BuildInfo.java")
    })
}

sourceSets.main {
    java.srcDir(generatedBuildInfoDirectory)
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateBuildInfo)
}

tasks.named("sourcesJar") {
    dependsOn(generateBuildInfo)
}

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.compilerArgs.add("-Xlint:deprecation")
    }
    jar {
        archiveClassifier.set("dev")
    }
    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("tkChat-Velocity-$artifactVersion.jar")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        relocate("com.zaxxer.hikari", "dev.tkkr.tkchat.libs.hikari")
        relocate("org.mariadb.jdbc", "dev.tkkr.tkchat.libs.mariadb")
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set("${project.version}-velocity")
    versionName.set("tkChat Velocity ${project.version}")
    versionType.set(providers.environmentVariable("MODRINTH_VERSION_TYPE").orElse("release"))
    changelog.set(providers.environmentVariable("MODRINTH_CHANGELOG").orElse(
            "See https://github.com/thaddeuskkr/tkChat/releases/tag/v${project.version}"))
    uploadFile.set(tasks.shadowJar)
    gameVersions.addAll(
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
        "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
        "26.1", "26.1.1", "26.1.2", "26.2"
    )
    detectLoaders.set(false)
    loaders.add("velocity")
    dependencies {
        required.project("luckperms")
        required.project("libertybans")
        optional.project("signedvelocity")
    }
}
