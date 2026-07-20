plugins {
    java
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
