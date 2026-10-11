plugins {
    java
    id("com.gradleup.shadow") version "9.2.2"
}

group = "dev.skyboxy"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://maven.enginehub.org/repo/")
    maven("https://maven.intellectualsites.com/content/repositories/releases/")
    maven("https://jitpack.io")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    // Keep the server API deterministic: dynamic build selectors can silently
    // change the API and the transitive dependency graph between builds.
    compileOnly("io.papermc.paper:paper-api:26.2.build.133-stable")

    // WorldGuard 7.0.18 is built against WorldEdit 7.4.0. Paper supplies the
    // Guava and Gson versions at runtime; the EngineHub metadata marks those
    // dependencies as Mojang-provided, so do not resolve its older strict
    // constraints against Paper's API artifacts.
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.4.0") {
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "com.google.code.gson", module = "gson")
    }
    compileOnly("com.sk89q.worldguard:worldguard-bukkit:7.0.18") {
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "com.google.code.gson", module = "gson")
    }

    // VaultAPI 1.7's POM points at the pre-Paper Bukkit artifact. The server
    // API above already supplies these types.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("org.slf4j:slf4j-nop:2.0.17")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
    testImplementation("io.papermc.paper:paper-api:26.2.build.133-stable")
    testImplementation("com.sk89q.worldedit:worldedit-bukkit:7.4.0") {
        exclude(group = "com.google.guava", module = "guava")
        exclude(group = "com.google.code.gson", module = "gson")
    }
}

tasks.test { useJUnitPlatform() }

tasks.shadowJar {
    archiveClassifier.set("")
    mergeServiceFiles()
}
tasks.build { dependsOn(tasks.shadowJar) }
