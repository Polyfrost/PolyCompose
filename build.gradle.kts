@file:Suppress("UnstableApiUsage")

import dev.detekt.gradle.Detekt
import java.nio.file.FileSystems
import java.nio.file.Files
import dev.kikugie.loomx.LoomCompatDependencyExtension
import dev.kikugie.loomx.LoomCompatProjectExtension
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.api.fabricapi.FabricApiExtension
import net.fabricmc.loom.build.nesting.NestableJarGenerationTask
import net.fabricmc.loom.task.NestJarsAction
import net.ltgt.gradle.errorprone.errorprone
import net.ornithemc.ploceus.api.PloceusGradleExtensionApi
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    java
    `maven-publish`
    kotlin("jvm")
    kotlin("plugin.compose")
    id("dev.kikugie.loom-back-compat")
    id("net.fabricmc.fabric-loom-remap") version "1.19-SNAPSHOT" apply false
    id("ploceus") version "1.17.4" apply false
    id("me.modmuss50.mod-publish-plugin")
    id("dev.detekt")
    id("net.ltgt.errorprone")
    checkstyle
}

apply(from = rootProject.file("gradle/skiko-remap.gradle.kts"))

@Suppress("UNCHECKED_CAST")
val skikoRemapped = extra["skikoRemappedAttribute"] as Attribute<Boolean>
@Suppress("UNCHECKED_CAST")
val registerSkikoRemapCheck = extra["registerSkikoRemapCheck"] as (Configuration, Any) -> Unit

val stonecutter = extensions.getByName("stonecutter") as StonecutterBuildExtension
val loomx = extensions.getByType<LoomCompatProjectExtension>()
val node = stonecutter.current.project
val mcVersion = stonecutter.current.version
val isOrnithe = mcVersion == "1.8.9"
val loader = if (isOrnithe) "ornithe" else "fabric"
val ploceus = if (isOrnithe) {
    pluginManager.apply("net.fabricmc.fabric-loom-remap")
    pluginManager.apply("ploceus")

    configurations.configureEach {
        exclude(group = "org.lwjgl.lwjgl")
    }

    extensions.getByType<PloceusGradleExtensionApi>().apply {
        setIntermediaryGeneration(2)
    }
} else {
    null
}

run {
    val (version, loader) = node.split("-", limit = 2)
    stonecutter.properties.tags(version, loader)
}

fun dep(name: String): String = property("deps.$name") as String

fun optionalProperty(vararg names: String): String? =
    names.firstNotNullOfOrNull { name -> findProperty(name)?.toString()?.takeIf { it.isNotBlank() } }

val modId = property("mod.id") as String
val modName = property("mod.name") as String
val modVersion = property("mod.version") as String
val modDescription = property("mod.description") as String
val minecraftPredicate = property("mod.mc_compat") as String
val fabricLoaderVersion = dep("fabric_loader")
val fabricLanguageKotlinVersion = dep("fabric_language_kotlin")
val compose = dep("compose")
val twelvemonkeys = dep("twelvemonkeys")

group = property("mod.group") as String
version = modVersion
base.archivesName = "$modId-$node"

repositories {
    if (isOrnithe) {
        exclusiveContent {
            forRepository { mavenCentral() }
            filter { includeGroup("org.lwjgl") }
        }
        maven("https://maven.cloverclient.com/releases") {
            content { includeGroup("pl.tomgirl") }
        }
    }
    mavenLocal()
    mavenCentral()
    google()
    maven("https://repo.polyfrost.org/releases")
    maven("https://repo.polyfrost.org/snapshots")
    maven("https://redirector.kotlinlang.org/maven/compose-dev") {
        content { includeGroupAndSubgroups("org.jetbrains") }
    }
    maven("https://maven.fabricmc.net/") {
        content { includeGroupAndSubgroups("net.fabricmc") }
    }
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
}

configurations.configureEach {
    exclude(group = "org.jetbrains.skiko")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-skiko")
    exclude(group = "org.jetbrains.compose.ui", module = "ui-skiko-desktop")
    if (name != "composeBundle") {
        exclude(group = "org.jetbrains.compose.ui", module = "ui")
        exclude(group = "org.jetbrains.compose.ui", module = "ui-desktop")
    }
}

val javaVersion = if (isOrnithe || stonecutter.eval(mcVersion, ">= 26.1")) 25 else 21

configure<KotlinJvmExtension> {
    jvmToolchain(javaVersion)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.toVersion(javaVersion)
}

val loomExt = extensions.getByName<LoomGradleExtensionAPI>("loom")

val stbNatives = configurations.create("stbNatives") { isTransitive = false }

interface Injected {
    @get:Inject val archives: ArchiveOperations
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")

    if (isOrnithe) {
        mappings(ploceus!!.layeredMappings {
            mappings("net.ornithemc:feather-gen2:$mcVersion+build.${property("feather_build")}:v2")
            mappings(rootProject.file("mappings/feather-overrides.tiny"))
        })
        ploceus.dependOsl(dep("osl"))
        modApi("pl.tomgirl:pylon:${dep("pylon")}")
        val lwjglStb = "org.lwjgl:lwjgl-stb:3.4.3"
        api(lwjglStb) { isTransitive = false }
        include(lwjglStb)
        val stbPlatforms = listOf("linux", "linux-arm32", "linux-arm64", "linux-ppc64le", "linux-riscv64", "freebsd", "macos", "macos-arm64", "windows", "windows-x86", "windows-arm64")
        for (natives in stbPlatforms) {
            stbNatives("$lwjglStb:natives-$natives")
        }
        modImplementation("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlinVersion")
        modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    } else {
        dependencies.extensions.getByType<LoomCompatDependencyExtension>().applyMojangMappings()
        api("net.fabricmc:fabric-language-kotlin:$fabricLanguageKotlinVersion") { isTransitive = false }
        modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
        val fabricApi = project.extensions.getByType<FabricApiExtension>()
        for (module in listOf("fabric-api-base", "fabric-resource-loader-${dep("fabric_resource_loader")}")) {
            val dependency = fabricApi.module(module, dep("fabric_api"))
            modImplementation(dependency)
            include(dependency)
        }
        optionalProperty("deps.controlify")?.let { modCompileOnly("maven.modrinth:controlify:$it") { isTransitive = false } }
    }

    api("org.jetbrains.compose.runtime:runtime-desktop:$compose")
    api("org.jetbrains.compose.foundation:foundation-desktop:$compose")
    api("org.jetbrains.compose.material:material-desktop:$compose")
    api(project(":compose-ui"))
    api("org.jetbrains.compose.ui:ui-util-desktop:$compose")
    api("org.jetbrains.compose.ui:ui-backhandler-desktop:$compose")
    api("org.jetbrains.androidx.navigation:navigation-compose:${dep("compose_navigation")}")
    api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:${dep("lifecycle")}")
    api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:${dep("viewmodel")}")

    api(project(":core"))
    include(project(":core"))
    for (module in listOf("imageio:imageio-webp", "imageio:imageio-bmp", "imageio:imageio-core", "imageio:imageio-metadata", "common:common-lang", "common:common-io", "common:common-image")) {
        include("com.twelvemonkeys.$module:$twelvemonkeys")
    }

    errorprone("com.google.errorprone:error_prone_core:${dep("errorprone")}")
}

val composeBundle: Configuration = configurations.create("composeBundle") {
    isCanBeConsumed = false
    isTransitive = true
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains.androidx.lifecycle")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "org.jetbrains.compose.runtime")
    attributes.attribute(skikoRemapped, true)
}

dependencies {
    composeBundle("androidx.compose.runtime:runtime-desktop:${dep("androidx_compose_runtime")}")
    composeBundle("androidx.compose.runtime:runtime-saveable-desktop:${dep("androidx_compose_runtime")}")
    composeBundle("org.jetbrains.compose.foundation:foundation-desktop:$compose")
    composeBundle("org.jetbrains.compose.material:material-desktop:$compose")
    composeBundle("org.jetbrains.compose.ui:ui-desktop:$compose")
    composeBundle("org.jetbrains.compose.ui:ui-util-desktop:$compose")
    composeBundle("org.jetbrains.compose.ui:ui-backhandler-desktop:$compose")
    composeBundle("org.jetbrains.androidx.navigation:navigation-compose:${dep("compose_navigation")}")
    composeBundle("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:${dep("lifecycle")}")
    composeBundle("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:${dep("viewmodel")}")
}

registerSkikoRemapCheck(composeBundle, project(":core"))

val bundleArtifacts = composeBundle.incoming.artifacts.resolvedArtifacts

fun bundleVersionRank(version: String): List<Long> {
    val numbers = version.substringBefore('-').split('.').map { it.toLongOrNull() ?: 0L }
    return numbers + (if ('-' in version) 0L else 1L)
}

fun isOlder(a: String, b: String): Boolean {
    val x = bundleVersionRank(a)
    val y = bundleVersionRank(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val l = x.getOrElse(i) { 0L }
        val r = y.getOrElse(i) { 0L }
        if (l != r) return l < r
    }
    return false
}

val supersededBundleJars = bundleArtifacts.map { artifacts ->
    val modules = artifacts.mapNotNull { artifact -> (artifact.id.componentIdentifier as? ModuleComponentIdentifier)?.let { it to artifact.file } }
    val platformVersions = modules.filter { (id, _) -> id.group.startsWith("androidx.") }.associate { (id, _) -> id.module to id.version }
    modules.filter { (id, _) ->
        val platform = platformVersions[id.module]
        id.group.startsWith("org.jetbrains.androidx.") && platform != null && isOlder(id.version, platform)
    }.map { it.second.name }
}

val processComposeBundle = tasks.register<NestableJarGenerationTask>("processComposeBundle") {
    from(composeBundle)
    outputDirectory = layout.buildDirectory.dir(name)
    uncompressNestedJars = true

    val nestedVersionTag = 1
    val nestedJars = outputDirectory
    inputs.property("nestedVersionTag", nestedVersionTag)
    doLast {
        val versionField = Regex("\"version\"\\s*:\\s*\"([^\"]+)\"")
        fun tagged(version: String): String {
            val core = version.substringBefore('+')
            val build = version.substringAfter('+', "")
            val tagged = if ('-' in core) "$core.polycompose.$nestedVersionTag" else "$core.$nestedVersionTag+polycompose"
            val separator = if ('+' in tagged) "." else "+"
            return if (build.isEmpty()) tagged else "$tagged$separator$build"
        }
        for (jar in nestedJars.get().asFile.listFiles { file -> file.name.endsWith(".jar") }.orEmpty()) {
            FileSystems.newFileSystem(jar.toPath()).use { zip ->
                val modJson = zip.getPath("fabric.mod.json")
                if (!Files.exists(modJson)) return@use
                val json = Files.readString(modJson)
                if ("fabric-loom:generated" !in json) return@use
                val version = checkNotNull(versionField.find(json)?.groups?.get(1)) { "No version in ${jar.name}!/fabric.mod.json" }
                val modified = Files.getLastModifiedTime(modJson)
                Files.writeString(modJson, json.replaceRange(version.range, tagged(version.value)))
                Files.setLastModifiedTime(modJson, modified)
            }
        }
    }

    val supersededJars = supersededBundleJars
    doLast {
        for (name in supersededJars.get()) nestedJars.get().asFile.resolve(name).delete()
    }
    doFirst {
        val collisions = bundleArtifacts.get()
            .groupBy { it.file.name }
            .filterValues { it.size > 1 }
        check(collisions.isEmpty()) {
            "Nested jar filename collisions in the Compose bundle, exclude the redundant artifacts:\n" +
                collisions.entries.joinToString("\n") { (name, artifacts) ->
                    "  $name -> ${artifacts.map { it.id.componentIdentifier.displayName }}"
                }
        }
    }
}

loomx.modJar.configure {
    dependsOn(processComposeBundle, "verifySkikoRemap")
    NestJarsAction.addToTask(this, fileTree(processComposeBundle.flatMap { it.outputDirectory }).matching { include("*.jar") })
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-classfile,-processing", "-Werror"))
    options.errorprone {
        disableAllChecks = true
        error("UnnecessarilyFullyQualified")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        allWarningsAsErrors = true
        extraWarnings = true
        progressiveMode = true
        freeCompilerArgs.add("-Xdont-warn-on-error-suppression")
    }
}

tasks.named<JavaCompile>("compileJava") {
    finalizedBy("checkstyleMain")
}

tasks.named<Detekt>("detekt") {
    enabled = false
    dependsOn(tasks.withType<Detekt>().matching { it.name != "detekt" && !it.name.endsWith("SourceSet") })
}

loomExt.apply {
    accessWidenerPath = stonecutter.process(
        rootProject.file("src/main/resources/polycompose.classtweaker"),
        "build/processed.classtweaker",
    )
    uncompressNestedJars = true
}

loomExt.runConfigs.named("client") {
    generateRunConfig = true
    runDirectory = layout.projectDirectory.dir("../../run")
    if (isOrnithe && System.getProperty("os.name").startsWith("Mac")) jvmArguments.add("-XstartOnFirstThread")
}

tasks.withType<ProcessResources>().configureEach {
    val properties = mapOf(
        "mod_version" to modVersion,
        "fabric_mc_version" to minecraftPredicate,
        "loader_version" to fabricLoaderVersion,
        "flk_version" to fabricLanguageKotlinVersion,
        "java_version" to javaVersion.toString(),
        "java_level" to "JAVA_$javaVersion",
    )
    inputs.properties(properties)

    filesMatching(listOf(if (isOrnithe) "ornithe.mod.json" else "fabric.mod.json", "mixins.polycompose*.json")) {
        expand(properties)
    }
    if (isOrnithe) {
        exclude("fabric.mod.json")
        eachFile { if (path == "ornithe.mod.json") path = "fabric.mod.json" }
        val archives = objects.newInstance<Injected>().archives
        from(stbNatives.elements.map { jars -> jars.map { archives.zipTree(it) } }) {
            exclude("META-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/versions/**")
        }
    } else {
        exclude("ornithe.mod.json")
    }
    if (stonecutter.eval(mcVersion, "< 1.21.11")) {
        exclude("assets/polycompose/post_effect/**")
        exclude("assets/polycompose/shaders/core/liquid_glass*", "assets/polycompose/shaders/post/ui_blur*")
    }
}

tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
    dependsOn("stonecutterGenerate")
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    description = "Builds mod jars and copies results to `build/libs/{mod version}/`"

    inputs.property("version", modVersion)
    from(loomx.modJar.flatMap { it.archiveFile }, loomx.modSourcesJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
}

val modrinthId = optionalProperty("publish.modrinth")
val modrinthToken = optionalProperty("publish.modrinth.token", "modrinth.token")
val curseforgeId = optionalProperty("publish.curseforge")
val curseforgeToken = optionalProperty("publish.curseforge.token", "curseforge.token")

val releaseVersions = (property("mod.mc_releases") as String).split(",").map { it.trim() }
val changelogFile = rootProject.file("CHANGELOG.md")

fun currentChangelogSection(): String? {
    if (!changelogFile.exists()) return null
    val headingPattern = Regex("^##\\s+\\[?v?" + Regex.escape(modVersion) + "]?(\\s.*)?$")
    val lines = changelogFile.readLines()
    val start = lines.indexOfFirst { headingPattern.matches(it.trimEnd()) }
    if (start < 0) return null
    val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
    return lines.subList(start + 1, end).joinToString("\n").trim().takeIf { it.isNotEmpty() }
}

val validateChangelog = tasks.register("validateChangelog") {
    description = "Validates that the changelog is written for the current version."
    doLast {
        if (currentChangelogSection() == null) {
            throw GradleException("Changelog for version $modVersion not found.")
        }
    }
}

tasks.matching { it.name == "publishMods" || it.name == "publishModrinth" || it.name == "publishCurseforge" }.configureEach {
    dependsOn(validateChangelog)
}

publishMods {
    file = loomx.modJar.flatMap { it.archiveFile }

    displayName = modVersion
    version = "v$modVersion"
    changelog = providers.provider { currentChangelogSection() ?: throw GradleException("Changelog for version $modVersion not found.") }
    type = STABLE

    modLoaders.add(loader)

    dryRun = modrinthId == null || modrinthToken == null || (curseforgeId != null && curseforgeToken == null)

    if (modrinthId != null) {
        modrinth {
            projectId = modrinthId
            accessToken = modrinthToken.orEmpty()

            minecraftVersions.addAll(releaseVersions)

            requires(if (isOrnithe) "osl" else "fabric-api")
            requires("fabric-language-kotlin")
        }
    }

    if (curseforgeId != null && !isOrnithe) {
        curseforge {
            projectId = curseforgeId
            accessToken = curseforgeToken.orEmpty()

            minecraftVersions.addAll(releaseVersions)
            client = true

            requires("fabric-api")
            requires("fabric-language-kotlin")
        }
    }
}

if (curseforgeId != null && isOrnithe) tasks.register("publishCurseforge")

publishing {
    repositories {
        listOf("releases", "snapshots").forEach { type ->
            maven("https://repo.polyfrost.org/$type") {
                name = type
                credentials {
                    username = providers.gradleProperty("polyfrostRepoUsername").orNull
                    password = providers.gradleProperty("polyfrostRepoToken").orNull
                }
                authentication { create<BasicAuthentication>("basic") }
            }
        }
    }
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "$modId-$node"
            suppressAllPomMetadataWarnings()
        }
    }
}
