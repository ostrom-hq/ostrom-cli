plugins {
    id("ostrom.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platform)
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
