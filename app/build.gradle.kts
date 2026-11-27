/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

data class ResolvedAppVersion(
    val versionName: String,
    val versionCode: Int
)

fun computeSemVerCode(major: Int, minor: Int, patch: Int, commitOffset: Int = 0): Int {
    if (major !in 0..200 || minor !in 0..999 || patch !in 0..999) {
        throw GradleException(
            "SemVer components out of supported Android versionCode bounds: $major.$minor.$patch " +
                "(expected major in 0..200, minor in 0..999, patch in 0..999)"
        )
    }
    val safeOffset = commitOffset.coerceIn(0, 9)
    return (major * 10_000_000 + minor * 10_000 + patch * 10 + safeOffset).coerceAtLeast(1)
}

fun gitExecOrNull(vararg args: String): String? {
    val execOut = providers.exec {
        commandLine(*args)
        isIgnoreExitValue = true
    }
    return if (execOut.result.get().exitValue == 0) {
        execOut.standardOutput.asText.get().trim()
    } else {
        null
    }
}

fun resolveAppVersion(): ResolvedAppVersion {
    val explicitName = providers.gradleProperty("appVersionName").orNull?.trim()?.removePrefix("v")
    val explicitCodeProp = providers.gradleProperty("appVersionCode").orNull?.trim()
    val explicitCode = if (!explicitCodeProp.isNullOrEmpty()) {
        explicitCodeProp.toIntOrNull()
            ?: throw GradleException("Invalid integer for -PappVersionCode='$explicitCodeProp'")
    } else {
        null
    }

    val semverRegex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:[-+].*)?$""")

    if (!explicitName.isNullOrEmpty()) {
        val match = semverRegex.matchEntire(explicitName)
            ?: throw GradleException("Invalid SemVer for -PappVersionName='$explicitName'")
        val code = explicitCode ?: computeSemVerCode(
            major = match.groupValues[1].toInt(),
            minor = match.groupValues[2].toInt(),
            patch = match.groupValues[3].toInt(),
            commitOffset = 0
        )
        return ResolvedAppVersion(versionName = explicitName, versionCode = code)
    }

    val strictTagRegex = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
    val mergedTag = gitExecOrNull("git", "tag", "--merged", "HEAD", "--list", "v*", "--sort=-v:refname")
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { strictTagRegex.matches(it) }

    val shortSha = gitExecOrNull("git", "rev-parse", "--short=7", "HEAD").orEmpty()

    if (mergedTag != null) {
        val match = strictTagRegex.matchEntire(mergedTag)!!
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        val patch = match.groupValues[3].toInt()
        val distance = gitExecOrNull("git", "rev-list", "--count", "$mergedTag..HEAD")
            ?.toIntOrNull() ?: 0

        val name = when {
            distance == 0 -> "$major.$minor.$patch"
            shortSha.isNotEmpty() -> "$major.$minor.$patch+$distance.g$shortSha"
            else -> "$major.$minor.$patch+$distance"
        }
        val code = explicitCode ?: computeSemVerCode(
            major = major,
            minor = minor,
            patch = patch,
            commitOffset = if (distance == 0) 0 else distance.coerceIn(1, 9)
        )
        return ResolvedAppVersion(versionName = name, versionCode = code)
    }

    val fallbackDistance = gitExecOrNull("git", "rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
    val fallbackName = if (shortSha.isNotEmpty()) "0.1.0+g$shortSha" else "0.1.0"
    val fallbackCode = explicitCode ?: computeSemVerCode(
        major = 0,
        minor = 1,
        patch = 0,
        commitOffset = if (fallbackDistance == 0) 0 else fallbackDistance.coerceIn(1, 9)
    )
    return ResolvedAppVersion(versionName = fallbackName, versionCode = fallbackCode)
}

val resolvedAppVersion = resolveAppVersion()

val signingStorePath = providers.environmentVariable("SIGNING_STORE_FILE").orNull
    ?: providers.gradleProperty("SIGNING_STORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
    ?: providers.gradleProperty("SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
    ?: providers.gradleProperty("SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
    ?: providers.gradleProperty("SIGNING_KEY_PASSWORD").orNull

val requireReleaseSigning = providers.gradleProperty("requireReleaseSigning").orNull?.toBoolean() == true

val hasReleaseSigning = if (!signingStorePath.isNullOrBlank()) {
    if (!file(signingStorePath).isFile) {
        throw GradleException("SIGNING_STORE_FILE does not exist: $signingStorePath")
    }
    if (signingStorePassword.isNullOrBlank() || signingKeyAlias.isNullOrBlank() || signingKeyPassword.isNullOrBlank()) {
        throw GradleException(
            "SIGNING_STORE_FILE is set, so SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, and SIGNING_KEY_PASSWORD must all be non-empty."
        )
    }
    true
} else {
    if (requireReleaseSigning) {
        throw GradleException(
            "requireReleaseSigning=true requires SIGNING_STORE_FILE, SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, and SIGNING_KEY_PASSWORD."
        )
    }
    false
}

android {
    namespace = "dev.duoime.app"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "dev.duoime.app"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedAppVersion.versionCode
        versionName = resolvedAppVersion.versionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        }
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("config/compose/stability.conf")
}

dependencies {
    implementation(project(":duoime-embed"))
    implementation(project(":engine-rime"))
    implementation(project(":keyboard-ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
