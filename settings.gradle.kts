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

// A local clone of ostrom-core (next to this clone, or next to the worktrees folder) replaces the published package,
// so a change to the contract is tried here before it is released.
listOf("../ostrom-core", "../../ostrom-core")
    .map { file(it) }
    .firstOrNull { it.resolve("settings.gradle.kts").exists() }
    ?.let { core ->
        includeBuild(core) {
            // ostrom-core publishes its projects under other names (`api` as `ostrom-api`): map them by hand.
            dependencySubstitution {
                substitute(module("dev.ostrom:ostrom-api")).using(project(":api"))
                substitute(module("dev.ostrom:ostrom-receipts")).using(project(":receipts"))
            }
        }
    }

include(":platform", ":api-client", ":hooks", ":launcher", ":mcp", ":relay", ":cli")
