plugins {
    java
    id("com.modrinth.minotaur")
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("paper-1.21/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("paper-1.21/src/main/resources")))
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.62-beta")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(25))

val artifactVersion = project.version.toString()

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(25)
    }
    processResources {
        inputs.property("version", artifactVersion)
        expand(mapOf("version" to artifactVersion))
    }
    jar {
        archiveFileName.set("tkChat-Paper-26.2-$artifactVersion.jar")
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set(project.version.toString())
    versionName.set("tkChat Paper 26.2 ${project.version}")
    versionType.set(providers.environmentVariable("MODRINTH_VERSION_TYPE").orElse("release"))
    changelog.set(providers.environmentVariable("MODRINTH_CHANGELOG").orElse(
            "See https://github.com/thaddeuskkr/tkChat/releases/tag/v${project.version}"))
    uploadFile.set(tasks.jar)
    gameVersions.add("26.2")
    detectLoaders.set(false)
    loaders.addAll("paper", "purpur")
    dependencies {
        optional.project("signedvelocity")
    }
}
