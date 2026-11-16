// SPDX-License-Identifier: Apache-2.0
plugins {
    id("ostrom.kmp")
}

// ostrom-core's typed client, from GitHub Packages (gradle.properties, ostromCoreVersion).
val ostromCoreVersion = providers.gradleProperty("ostromCoreVersion").get()

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platform)
            api("run.ostrom:ostrom-client:$ostromCoreVersion")
            implementation(libs.ktor.client.core)
        }
        nativeMain.dependencies {
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.openssl3)
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
