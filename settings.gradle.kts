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
        // ostrom-core is published privately on GitHub Packages; CI reads it with its GITHUB_TOKEN.
        maven("https://maven.pkg.github.com/ostrom-hq/ostrom-core") {
            name = "ostromCore"
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("dev.ostrom") }
        }
    }
}

include(":platform", ":api-client", ":hooks", ":launcher", ":mcp", ":cli")
