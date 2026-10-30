plugins {
    id("ostrom.kmp")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platform)
            implementation(libs.serialization.json)
        }
    }
}
