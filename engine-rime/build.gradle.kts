/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

abstract class GenerateRimeStarterAssetsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatorScript: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val coreDictFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val s2tFile: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemasDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputAssetsDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val targetDir = outputAssetsDir.get().asFile.resolve("duoime/rime")
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()
        execOperations.exec {
            commandLine(
                "python3",
                "-B",
                generatorScript.get().asFile.absolutePath,
                "--core-dict",
                coreDictFile.get().asFile.absolutePath,
                "--s2t",
                s2tFile.get().asFile.absolutePath,
                "--schemas-dir",
                schemasDir.get().asFile.absolutePath,
                "--output-dir",
                targetDir.absolutePath
            )
        }
    }
}

fun readEnvKey(envFile: File, key: String): String? {
    if (!envFile.isFile) return null
    val prefix = "$key="
    return envFile.readLines()
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removeSurrounding("'")
        ?.takeIf { it.isNotEmpty() }
}

fun resolveJniLibsDir(candidateRoot: File): File? {
    val nested = candidateRoot.resolve("jniLibs")
    if (nested.resolve("arm64-v8a/libduoime_rime.so").isFile) {
        return nested
    }
    if (candidateRoot.resolve("arm64-v8a/libduoime_rime.so").isFile) {
        return candidateRoot
    }
    return null
}

val prebuiltTag = readEnvKey(rootProject.file("native/rime/prebuilt.env"), "RIME_PREBUILT_TAG")
val propPrebuiltDir = providers.gradleProperty("duoime.rimePrebuiltDir").orNull?.trim()?.takeIf { it.isNotEmpty() }
val envPrebuiltDir = providers.environmentVariable("DUOIME_RIME_PREBUILT_DIR").orNull?.trim()?.takeIf {
    it.isNotEmpty()
}
val xdgCacheBase = providers.environmentVariable("XDG_CACHE_HOME").orNull?.trim()?.takeIf { it.isNotEmpty() }
    ?: "${System.getProperty("user.home")}/.cache"
val defaultCacheDir = prebuiltTag?.let { "$xdgCacheBase/duoime/rime-prebuilt/$it" }

val requireRimeNative = providers.gradleProperty("requireRimeNative").orNull?.toBoolean() == true ||
    providers.environmentVariable("REQUIRE_RIME_NATIVE").orNull?.trim() == "1"

val resolvedRimeJniLibsDir: File? = listOfNotNull(propPrebuiltDir, envPrebuiltDir, defaultCacheDir)
    .asSequence()
    .map(::File)
    .mapNotNull(::resolveJniLibsDir)
    .firstOrNull()

if (requireRimeNative && resolvedRimeJniLibsDir == null) {
    throw GradleException(
        "requireRimeNative=true, but arm64-v8a/libduoime_rime.so was not found. " +
            "Fetch the pinned prebuilt via 'bash scripts/fetch_rime_prebuilt.sh' or pass " +
            "-Pduoime.rimePrebuiltDir=<dir> / DUOIME_RIME_PREBUILT_DIR."
    )
}

kotlin {
    explicitApi()
}

android {
    namespace = "dev.duoime.engine.rime"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    if (resolvedRimeJniLibsDir != null) {
        sourceSets["main"].jniLibs.srcDir(resolvedRimeJniLibsDir)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }
}

val generateRimeStarterAssets = tasks.register<GenerateRimeStarterAssetsTask>("generateRimeStarterAssets") {
    group = "build"
    description = "Generates permissive starter Rime dictionary, OpenCC text config, and schemas into build assets."
    generatorScript.set(rootProject.layout.projectDirectory.file("scripts/generate_rime_starter_assets.py"))
    coreDictFile.set(
        rootProject.layout.projectDirectory.file("engine-lite/src/main/resources/duoime/dict/core_dict.tsv")
    )
    s2tFile.set(
        rootProject.layout.projectDirectory.file("engine-lite/src/main/resources/duoime/dict/s2t.tsv")
    )
    schemasDir.set(rootProject.layout.projectDirectory.dir("native/rime/schemas"))
    outputAssetsDir.set(layout.buildDirectory.dir("generated/rimeStarterAssets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateRimeStarterAssets,
            GenerateRimeStarterAssetsTask::outputAssetsDir
        )
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
