// SPDX-License-Identifier: Apache-2.0
rootProject.name = "ostrom-cli"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // ostrom-core is published privately on GitHub Packages: gpr.user / gpr.key in ~/.gradle/gradle.properties (a
        // token with read:packages). CI builds it from source instead (ostrom.core.local, below).
        maven("https://maven.pkg.github.com/ostrom-hq/ostrom-core") {
            name = "ostromCore"
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("run.ostrom") }
        }
    }
}

// ostrom-core from a checkout next to this one instead of GitHub Packages: -Postrom.core.local=true (or the same line
// in gradle.properties). Local development uses it, and so does CI, which cannot read the packages of another
// repository: it checks ostrom-core out with its read-only deploy key at the commit of gradle/ostrom-core.ref (JOURNAL
// 05:50). Each published artifact is replaced by the project that publishes it.
if (providers.gradleProperty("ostrom.core.local").orNull == "true") {
    includeBuild("../ostrom-core") {
        dependencySubstitution {
            for (module in listOf("api", "receipts", "telemetry")) {
                substitute(module("run.ostrom:ostrom-$module")).using(project(":$module"))
            }
        }
    }
}

include(":platform", ":api-client", ":hooks", ":launcher", ":mcp", ":relay", ":todo", ":memory", ":runner", ":cli")
