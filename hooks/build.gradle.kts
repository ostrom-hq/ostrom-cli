plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.platform)
            implementation(libs.serialization)
        }
    }
}
