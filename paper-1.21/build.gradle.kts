plugins {
    java
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
        archiveFileName.set("tkChat-Paper-1.21.jar")
    }
}
