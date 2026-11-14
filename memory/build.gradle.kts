// SPDX-License-Identifier: Apache-2.0
plugins {
    id("ostrom.kmp")
}

val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platform)
            api(projects.mcp)
            implementation(libs.serialization.json)
            api("run.ostrom:ostrom-api:$ostromCoreVersion")
        }
    }
}
