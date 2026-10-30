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
        // The gate checks the instance's Ed25519 signature on its policy (S2-20) with the OpenSSL 3 the binary already
        // links for the launcher: no second library in `ostrom`.
        nativeMain.dependencies {
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.openssl3)
        }
        commonTest.dependencies {
            implementation(libs.okio.fakefilesystem)
        }
    }
}
