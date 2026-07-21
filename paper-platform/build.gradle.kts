plugins {
    java
    id("com.modrinth.minotaur")
}

data class PaperTarget(
    val family: String,
    val apiDependency: String,
    val minimumApiVersion: String,
    val javaVersion: Int,
    val gameVersions: List<String>
)

val target = when (project.name) {
    "paper-1.21" -> PaperTarget(
        family = "1.21.x",
        apiDependency = "1.21-R0.1-SNAPSHOT",
        minimumApiVersion = "1.21",
        javaVersion = 21,
        gameVersions = listOf(
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
            "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
        )
    )
    "paper-26.1" -> PaperTarget(
        family = "26.1.x",
        apiDependency = "26.1.1.build.29-alpha",
        minimumApiVersion = "26.1.1",
        javaVersion = 25,
        gameVersions = listOf("26.1.1", "26.1.2")
    )
    "paper-26.2" -> PaperTarget(
        family = "26.2.x",
        apiDependency = "26.2.build.62-beta",
        minimumApiVersion = "26.2",
        javaVersion = 25,
        gameVersions = listOf("26.2")
    )
    else -> error("Unsupported Paper target project: ${project.name}")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("paper-platform/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("paper-platform/src/main/resources")))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${target.apiDependency}")
}

val paper121ApiVersions = listOf(
    "1.21-R0.1-SNAPSHOT",
    "1.21.1-R0.1-SNAPSHOT",
    "1.21.3-R0.1-SNAPSHOT",
    "1.21.4-R0.1-SNAPSHOT",
    "1.21.5-R0.1-SNAPSHOT",
    "1.21.6-R0.1-SNAPSHOT",
    "1.21.7-R0.1-SNAPSHOT",
    "1.21.8-R0.1-SNAPSHOT",
    "1.21.9-R0.1-SNAPSHOT",
    "1.21.10-R0.1-SNAPSHOT",
    "1.21.11-R0.1-SNAPSHOT"
)

val paper121CompatibilityTasks = if (project.name == "paper-1.21") {
    paper121ApiVersions.map { apiVersion ->
        val taskSuffix = apiVersion.substringBefore("-R0.1").replace('.', '_')
        val apiConfiguration = configurations.create("paperApiCompatibility$taskSuffix") {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add(apiConfiguration.name, "io.papermc.paper:paper-api:$apiVersion")
        tasks.register<JavaCompile>("compilePaperApiCompatibility$taskSuffix") {
            group = "verification"
            description = "Compiles the shared Paper bridge against Paper $apiVersion."
            source(rootProject.fileTree("paper-platform/src/main/java") { include("**/*.java") })
            classpath = apiConfiguration
            destinationDirectory.set(layout.buildDirectory.dir("compatibility/$taskSuffix"))
            javaCompiler.set(javaToolchains.compilerFor {
                languageVersion.set(JavaLanguageVersion.of(21))
            })
            options.encoding = "UTF-8"
            options.release.set(21)
            options.compilerArgs.add("-parameters")
        }
    }
} else {
    emptyList()
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(target.javaVersion))

val artifactVersion = project.version.toString()

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(target.javaVersion)
    }
    processResources {
        inputs.property("version", artifactVersion)
        inputs.property("apiVersion", target.minimumApiVersion)
        expand(
            mapOf(
                "version" to artifactVersion,
                "apiVersion" to target.minimumApiVersion
            )
        )
    }
    jar {
        archiveFileName.set("tkChat-Paper-${target.family}-$artifactVersion.jar")
    }
    test {
        dependsOn(paper121CompatibilityTasks)
    }
    check {
        dependsOn(paper121CompatibilityTasks)
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set("${project.version}-paper-${target.family}")
    versionName.set("tkChat Paper ${target.family} ${project.version}")
    versionType.set(providers.environmentVariable("MODRINTH_VERSION_TYPE").orElse("release"))
    changelog.set(providers.environmentVariable("MODRINTH_CHANGELOG").orElse(
            "See https://github.com/thaddeuskkr/tkChat/releases/tag/v${project.version}"))
    uploadFile.set(tasks.jar)
    gameVersions.addAll(target.gameVersions)
    detectLoaders.set(false)
    loaders.addAll("paper", "purpur")
    dependencies {
        optional.project("signedvelocity")
    }
}
