// SPDX-License-Identifier: Apache-2.0
plugins {
    id("ostrom.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platform)
            implementation(libs.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
