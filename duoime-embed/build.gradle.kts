/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "dev.duoime.embed"
    compileSdk = 35
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }
}

composeCompiler {
    reportsDestination = layout.buildDirectory.dir("compose_compiler")
    stabilityConfigurationFile = rootProject.layout.projectDirectory.file("config/compose/stability.conf")
}

dependencies {
    api(project(":core"))
    api(project(":keyboard-ui"))
    implementation(project(":engine-lite"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.register("verifyNoMaterial3") {
    group = "verification"
    description = "Verifies :duoime-embed runtime classpaths contain zero Material 3 or Material Components."
    doLast {
        val forbidden = listOf("material3", "com.google.android.material")
        val configNames = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath")
        val violations = mutableListOf<String>()
        for (configName in configNames) {
            val config = configurations.findByName(configName)
                ?: error("Expected configuration $configName in :duoime-embed")
            for (component in config.incoming.resolutionResult.allComponents) {
                val coords = component.id.displayName
                if (forbidden.any { coords.contains(it, ignoreCase = true) }) {
                    violations += "$configName -> $coords"
                }
            }
        }
        if (violations.isNotEmpty()) {
            error("Forbidden Material dependency found in :duoime-embed:\n${violations.joinToString("\n")}")
        }
    }
}
