version = "1.21.11-core-dev.1"

val outputJarName = "nodes"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.2.20"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
    id("org.jlleitschuh.gradle.ktlint") version "14.1.0"
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/nexus/repository/public/")
    maven("https://repo.extendedclip.com/releases/")
    maven("https://jitpack.io/") {
        content {
            includeGroupByRegex("com\\.github\\..*")
        }
    }

    maven("https://repo.codemc.io/repository/maven-releases/")

    maven("https://repo.codemc.io/repository/maven-snapshots/")
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    runtimeOnly("com.mysql:mysql-connector-j:9.2.0")
    compileOnly("me.clip:placeholderapi:2.12.2")
    compileOnly("com.github.NEZNAMY", "TAB-API", "5.4.0")
    compileOnly("com.google.code.gson:gson:2.13.2")
    compileOnly("com.github.retrooper:packetevents-spigot:2.12.0")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    named("reobfJar") {
        dependsOn(jar)
    }

    jar {
        archiveBaseName.set(outputJarName)
    }

    runServer {
        // Configure the Minecraft version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        minecraftVersion("1.21.11")
    }
}
