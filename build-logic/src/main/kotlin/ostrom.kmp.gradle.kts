// SPDX-License-Identifier: Apache-2.0
// Convention for every Kotlin module of Ostrom (docs/CONVENTIONS.md sections 7, 8, 9 and 10).
// A module applies `id("ostrom.kmp")` and declares its own dependencies; nothing else goes in its build file.
//
// Native targets come from the Gradle property `ostrom.nativeTargets` (gradle.properties of the repository):
//   ostrom-core, ostrom-cli: macosArm64,linuxX64,linuxArm64,mingwX64
//   ostrom-server:           linuxX64,linuxArm64,macosArm64
// The jvm() target is for tests and Kover coverage of commonMain only: never published, never shipped.

import io.gitlab.arturbosch.detekt.Detekt
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlinx.kover")
    id("io.gitlab.arturbosch.detekt")
}

val libs = the<VersionCatalogsExtension>().named("libs")
fun lib(alias: String) = libs.findLibrary(alias).get()

val allNativeTargets = setOf("macosArm64", "linuxX64", "linuxArm64", "mingwX64")
val nativeTargets = providers.gradleProperty("ostrom.nativeTargets")
    .orElse(allNativeTargets.joinToString(","))
    .get().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
require(nativeTargets.isNotEmpty() && allNativeTargets.containsAll(nativeTargets)) {
    "ostrom.nativeTargets must be a comma-separated subset of $allNativeTargets, got $nativeTargets."
}

kotlin {
    jvmToolchain(21)
    jvm {
        testRuns.named("test") { executionTask.configure { useJUnitPlatform() } }
        // Interface default methods without the DefaultImpls compatibility stubs, which no caller uses and which
        // Kover would count as uncovered.
        compilerOptions { freeCompilerArgs.add("-jvm-default=no-compatibility") }
    }
    if ("macosArm64" in nativeTargets) macosArm64()
    if ("linuxX64" in nativeTargets) linuxX64()
    if ("linuxArm64" in nativeTargets) linuxArm64()
    if ("mingwX64" in nativeTargets) mingwX64()

    compilerOptions {
        optIn.addAll("kotlin.uuid.ExperimentalUuidApi", "kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(lib("coroutines-core"))
            implementation(lib("serialization-json"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(lib("coroutines-test"))
        }
        jvmTest.dependencies {
            runtimeOnly(lib("junit-jupiter-engine"))
        }
    }
}

// 100 % of lines and branches of commonMain, measured through the jvm() test run (BRIEF, rule TESTS).
// An exclusion is added here with a one-line reason, and reviewed.
kover {
    reports {
        filters {
            excludes {
                // Each use carries a `// coverage: <why>` line right above it, read in review (ostrom-e2e templates).
                annotatedBy("*.ExcludeFromCoverage")
            }
        }
        verify {
            rule {
                minBound(100, CoverageUnit.LINE)
                minBound(100, CoverageUnit.BRANCH)
            }
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(fileTree("src") { include("**/*.kt") })
    parallel = true
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

// The jvm target exists for tests only: never publish it.
tasks.matching { it.name.contains("JvmPublication") }.configureEach { enabled = false }
