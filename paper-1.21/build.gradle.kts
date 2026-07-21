plugins {
    java
    id("com.modrinth.minotaur")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

val artifactVersion = project.version.toString()

tasks {
    processResources {
        inputs.property("version", artifactVersion)
        expand(mapOf("version" to artifactVersion))
    }
    jar {
        archiveFileName.set("tkChat-Paper-1.21-$artifactVersion.jar")
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set(project.version.toString())
    versionName.set("tkChat Paper 1.21.x ${project.version}")
    versionType.set(providers.environmentVariable("MODRINTH_VERSION_TYPE").orElse("release"))
    changelog.set(providers.environmentVariable("MODRINTH_CHANGELOG").orElse(
            "See https://github.com/thaddeuskkr/tkChat/releases/tag/v${project.version}"))
    uploadFile.set(tasks.jar)
    gameVersions.addAll(
        "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
        "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
    )
    detectLoaders.set(false)
    loaders.addAll("paper", "purpur")
    dependencies {
        optional.project("signedvelocity")
    }
}
