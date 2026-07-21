import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask

plugins {
    java
    id("com.modrinth.minotaur")
}

val targets = mapOf(
    "fabric-1-21" to ("1.21" to "0.102.0+1.21"),
    "fabric-26-1" to ("26.1" to "0.145.1+26.1"),
    "fabric-26-2" to ("26.2" to "0.155.2+26.2")
)
val (minecraftVersion, fabricApiVersion) = requireNotNull(targets[project.name]) {
    "No Fabric target is configured for ${project.name}"
}
val supportedMinecraftVersions = when (minecraftVersion) {
    "1.21" -> listOf(
            "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5",
            "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
        )
    "26.1" -> listOf("26.1", "26.1.1", "26.1.2")
    else -> listOf(minecraftVersion)
}
val artifactMinecraftVersion = if (supportedMinecraftVersions.size > 1) {
    "$minecraftVersion.x"
} else {
    minecraftVersion
}
val minecraftDependency = when (minecraftVersion) {
    "1.21" -> ">=1.21 <=1.21.11"
    "26.1" -> ">=26.1 <=26.1.2"
    else -> "=$minecraftVersion"
}
val javaVersion = if (minecraftVersion.startsWith("26.")) 25 else 21
val modernMinecraft = minecraftVersion.startsWith("26.")
val fabricLoaderVersion = if (minecraftVersion == "1.21") "0.15.11" else "0.19.0"
val artifactVersion = project.version.toString()

pluginManager.apply(if (modernMinecraft) "net.fabricmc.fabric-loom" else "fabric-loom")

base.archivesName.set("tkChat-Fabric-$artifactMinecraftVersion")

sourceSets {
    main {
        val bridgeSources = when {
            modernMinecraft -> "fabric-platform/src/modern/java"
            else -> "fabric-platform/src/mc_1_21/java"
        }
        java.setSrcDirs(listOf(
            rootProject.file("fabric-platform/src/main/java"),
            rootProject.file(bridgeSources)
        ))
        resources.setSrcDirs(listOf(rootProject.file("fabric-platform/src/main/resources")))
    }
}

dependencies {
    add("minecraft", "com.mojang:minecraft:$minecraftVersion")
    if (!modernMinecraft) {
        add("mappings", project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    val modConfiguration = if (modernMinecraft) "implementation" else "modImplementation"
    add(modConfiguration, "net.fabricmc:fabric-loader:$fabricLoaderVersion")
    add(modConfiguration, "net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))

tasks {
    withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
    }
    processResources {
        val resourceProperties = mapOf(
            "version" to artifactVersion,
            "minecraftVersion" to minecraftVersion,
            "minecraftDependency" to minecraftDependency,
            "javaVersion" to javaVersion,
            "fabricApiVersion" to fabricApiVersion,
            "fabricLoaderVersion" to fabricLoaderVersion
        )
        inputs.properties(resourceProperties)
        expand(resourceProperties)
    }
    if (modernMinecraft) {
        jar {
            archiveFileName.set("tkChat-Fabric-$artifactMinecraftVersion-$artifactVersion.jar")
        }
    } else {
        named<RemapJarTask>("remapJar") {
            archiveFileName.set("tkChat-Fabric-$artifactMinecraftVersion-$artifactVersion.jar")
        }
    }
}

modrinth {
    token.set(providers.environmentVariable("MODRINTH_TOKEN"))
    projectId.set(providers.environmentVariable("MODRINTH_PROJECT_ID"))
    versionNumber.set("${project.version}-fabric-$artifactMinecraftVersion")
    versionName.set("tkChat Fabric $artifactMinecraftVersion ${project.version}")
    versionType.set(providers.environmentVariable("MODRINTH_VERSION_TYPE").orElse("release"))
    changelog.set(providers.environmentVariable("MODRINTH_CHANGELOG").orElse(
            "See https://github.com/thaddeuskkr/tkChat/releases/tag/v${project.version}"))
    uploadFile.set(if (modernMinecraft) {
        tasks.named<Jar>("jar")
    } else {
        tasks.named<RemapJarTask>("remapJar")
    })
    gameVersions.addAll(supportedMinecraftVersions)
    detectLoaders.set(false)
    loaders.add("fabric")
    dependencies {
        required.project("fabric-api")
        required.project("fabricproxy-lite")
        required.project("signedvelocity")
    }
}
