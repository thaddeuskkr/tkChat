plugins {
    java
    id("com.gradleup.shadow")
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
        archiveFileName.set("tkChat-Velocity.jar")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        relocate("com.zaxxer.hikari", "dev.tkkr.tkchat.libs.hikari")
        relocate("org.mariadb.jdbc", "dev.tkkr.tkchat.libs.mariadb")
        mergeServiceFiles()
    }
    build {
        dependsOn(shadowJar)
    }
}
