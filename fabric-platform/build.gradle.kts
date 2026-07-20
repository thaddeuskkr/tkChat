import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask

plugins {
    java
}

val targets = mapOf(
    "fabric-1-21" to ("1.21" to "0.102.0+1.21"),
    "fabric-1-21-1" to ("1.21.1" to "0.116.14+1.21.1"),
    "fabric-1-21-2" to ("1.21.2" to "0.106.1+1.21.2"),
    "fabric-1-21-3" to ("1.21.3" to "0.114.1+1.21.3"),
    "fabric-1-21-4" to ("1.21.4" to "0.119.4+1.21.4"),
    "fabric-1-21-5" to ("1.21.5" to "0.128.2+1.21.5"),
    "fabric-1-21-6" to ("1.21.6" to "0.128.2+1.21.6"),
    "fabric-1-21-7" to ("1.21.7" to "0.129.0+1.21.7"),
    "fabric-1-21-8" to ("1.21.8" to "0.136.1+1.21.8"),
    "fabric-1-21-9" to ("1.21.9" to "0.134.1+1.21.9"),
    "fabric-1-21-10" to ("1.21.10" to "0.138.4+1.21.10"),
    "fabric-1-21-11" to ("1.21.11" to "0.141.5+1.21.11"),
    "fabric-26-1" to ("26.1" to "0.145.1+26.1"),
    "fabric-26-1-1" to ("26.1.1" to "0.145.4+26.1.1"),
    "fabric-26-1-2" to ("26.1.2" to "0.155.2+26.1.2"),
    "fabric-26-2" to ("26.2" to "0.155.2+26.2")
)
val (minecraftVersion, fabricApiVersion) = requireNotNull(targets[project.name]) {
    "No Fabric target is configured for ${project.name}"
}
val javaVersion = if (minecraftVersion.startsWith("26.")) 25 else 21
val modernMinecraft = minecraftVersion.startsWith("26.")
val artifactVersion = project.version.toString()

pluginManager.apply(if (modernMinecraft) "net.fabricmc.fabric-loom" else "fabric-loom")

base.archivesName.set("tkChat-Fabric-$minecraftVersion")

sourceSets {
    main {
        val bridgeSources = when {
            modernMinecraft -> "fabric-platform/src/modern/java"
            minecraftVersion == "1.21.11" -> "fabric-platform/src/mc_1_21_11/java"
            else -> "fabric-platform/src/mc_1_21_0_to_1_21_10/java"
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
    add(modConfiguration, "net.fabricmc:fabric-loader:0.19.3")
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
            "javaVersion" to javaVersion,
            "fabricApiVersion" to fabricApiVersion
        )
        inputs.properties(resourceProperties)
        expand(resourceProperties)
    }
    if (modernMinecraft) {
        jar {
            archiveFileName.set("tkChat-Fabric-$minecraftVersion.jar")
        }
    } else {
        named<RemapJarTask>("remapJar") {
            archiveFileName.set("tkChat-Fabric-$minecraftVersion.jar")
        }
    }
}
