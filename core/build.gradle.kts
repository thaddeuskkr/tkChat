plugins {
    `java-library`
}

dependencies {
    api(platform("com.fasterxml.jackson:jackson-bom:2.21.5"))
    api("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:6.0.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
