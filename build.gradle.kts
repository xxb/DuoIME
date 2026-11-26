/*
 * SPDX-License-Identifier: BSD-3-Clause
 * Copyright (c) 2026 DuoIME Contributors
 */
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
}

subprojects {
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            listOf("com.android.application", "com.android.library").forEach { androidPluginId ->
                pluginManager.withPlugin(androidPluginId) {
                    android.set(true)
                }
            }
        }
    }

    pluginManager.withPlugin("io.gitlab.arturbosch.detekt") {
        configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
            buildUponDefaultConfig = false
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            val baselineFile = file("detekt-baseline.xml")
            if (baselineFile.exists()) {
                baseline = baselineFile
            }
            source.setFrom(
                files("src/main/java", "src/main/kotlin").filter { it.exists() }
            )
        }
    }
}
